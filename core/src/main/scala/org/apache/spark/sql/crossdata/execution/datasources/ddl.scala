/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.crossdata.execution.datasources

import java.util.UUID

import com.stratio.common.utils.components.config.impl.TypesafeConfigComponent
import com.stratio.crossdata.connector.TableInventory
import org.apache.spark.Logging
import org.apache.spark.launcher.SparkLauncher

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.crossdata.catalog.XDCatalog
import org.apache.spark.sql.crossdata.config.CoreConfig
import org.apache.spark.sql.crossdata.daos.{EphemeralTableDAOComponent, TableDAOComponent}
import org.apache.spark.sql.crossdata.daos.DAOConstants._
import org.apache.spark.sql.crossdata.models._
import org.apache.spark.sql.execution.RunnableCommand
import org.apache.spark.sql.execution.datasources.{ResolvedDataSource, LogicalRelation}
import org.apache.spark.sql.sources.RelationProvider

import org.apache.spark.sql.types.{ArrayType, BooleanType, StringType, StructField, StructType}
import org.apache.spark.sql.{AnalysisException, Row, SQLContext}

import XDCatalog._

import scala.util.parsing.json.{JSONFormat, JSONObject, JSON}

import org.apache.spark.sql.crossdata.config._



private [crossdata] case class ImportTablesUsingWithOptions(datasource: String, opts: Map[String, String])
  extends LogicalPlan with RunnableCommand with Logging {

  // The result of IMPORT TABLE has only tableIdentifier so far.
  override val output: Seq[Attribute] = {
    val schema = StructType(
      Seq(StructField("tableIdentifier", ArrayType(StringType), false), StructField("ignored", BooleanType, false))
    )
    schema.toAttributes
  }

  override def run(sqlContext: SQLContext): Seq[Row] = {

    def tableExists(tableId: Seq[String]): Boolean = {
      val doExist = sqlContext.catalog.tableExists(tableId)
      if (doExist) log.warn(s"IMPORT TABLE omitted already registered table: ${tableId mkString "."}")
      doExist
    }

    def persistTable(t: TableInventory.Table, tableInventory: TableInventory, relationProvider: RelationProvider) = {
      val connectorOpts = tableInventory.generateConnectorOpts(t, opts)

      sqlContext.catalog.persistTable(
        CrossdataTable(t.tableName, t.database, t.schema, datasource, Array.empty[String], connectorOpts),
        LogicalRelation(relationProvider.createRelation(sqlContext, connectorOpts)
        )
      )
    }

    // Get a reference to the inventory relation.
    val resolved = ResolvedDataSource.lookupDataSource(datasource).newInstance()

    val inventoryRelation = resolved.asInstanceOf[TableInventory]
    val relationProvider = resolved.asInstanceOf[RelationProvider]

    // Obtains the list of tables and persist it (if persistence implemented)
    val tables = inventoryRelation.listTables(sqlContext, opts)


    for {
      table: TableInventory.Table <- tables
      tableId = TableIdentifier(table.tableName, table.database).toSeq
      if inventoryRelation.exclusionFilter(table)
    } yield {
      val ignoreTable = tableExists(tableId)
      if (!ignoreTable) {
        logInfo(s"Importing table ${tableId mkString "."}")
        persistTable(table, inventoryRelation, relationProvider)
      }
      Row(tableId, ignoreTable)
    }

  }
}

private[crossdata] case class DropTable(tableIdentifier: TableIdentifier)
  extends LogicalPlan with RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.catalog.dropTable(tableIdentifier.toSeq)
    Seq.empty
  }

}

private[crossdata] case class CreateTempView(viewIdentifier: TableIdentifier, queryPlan: LogicalPlan)
  extends LogicalPlan with RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    sqlContext.catalog.registerView(viewIdentifier.toSeq, queryPlan)
    Seq.empty
  }

}

private[crossdata] case class CreateView(viewIdentifier: TableIdentifier, query: String)
  extends LogicalPlan with RunnableCommand {

  override def run(sqlContext: SQLContext): Seq[Row] = {
    throw new AnalysisException("Only temporary views are supported. Use CREATE TEMPORARY VIEW")
  }
}

private[crossdata] case class CreateEphemeralTable(
    tableIdent: TableIdentifier,
    columns: StructType,
    opts: Map[String, String])
  extends LogicalPlan with RunnableCommand with EphemeralTableDAOComponent {

  override val config: Config = new TypesafeConfig(None ,None , Some(CoreConfig.CoreBasicConfig), Some(CoreConfig.ParentConfigName + "." +CoreConfig.StreamingConfigKey))

  override val output: Seq[Attribute] = {
    val schema = StructType(
      Seq(StructField("EphemeralTableID", StringType, false))
    )
    schema.toAttributes
  }


  override def run(sqlContext: SQLContext): Seq[Row] = {
   // throw new AnalysisException("Ephemeral tables are not supported yet")

    val tableId = createId
    //val kafkaConnection = config.getString("kafka.connection").get.split(",").map(_.split(":"))

    val kafkaConnection = config.getString("kafka.connectionString").get.split(":")
    val kafkaHost = kafkaConnection(0)
    val kafkaPort = kafkaConnection(1)

    //TODO read from configuration
    val topics= Seq(TopicModel(config.getString("kafka.topicName").get))
    val connections = Seq(ConnectionHostModel(kafkaHost,kafkaPort))
    val kafkaOptions = KafkaOptionsModel(connections, topics)
    val ephemeralOptions = EphemeralOptionsModel(kafkaOptions)

    dao.create(tableId,
      EphemeralTableModel(tableId,
        tableIdent.table,
        Some(ephemeralOptions)))

/*
    // TODO: Blocked by CROSSDATA-148 and CROSSDATA-205
    // * This query will trigger 3 actions in the catalog persistence:
    //   1.- Associate the table with the schema.
    val schema = columns.json
    //   2.- Associate the table with the configuration.
    val options = JSONObject(opts).toString(JSONFormat.defaultFormatter)
    //   3.- Associate the QueryID with the involved table.


    // * SparkLauncher of StreamingProcess
    val params = tableId :: opts.values.toList
    val sparkApp = new SparkLauncher()
      .setAppName(tableId)
      .setMaster(sqlContext.conf.getConfString("spark.master"))
      .setAppResource("streamingProcess.jar")
      .setMainClass("StreamingProcess")
      .setDeployMode("cluster")
      .addAppArgs(params:_*)
      .launch()

    // * Return the UUID of the process
*/
    Seq(Row(tableId))
  }
}





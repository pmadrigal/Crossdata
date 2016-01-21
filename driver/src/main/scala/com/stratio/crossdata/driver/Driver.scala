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
package com.stratio.crossdata.driver

import java.util.concurrent.TimeUnit.SECONDS

import akka.actor.ActorSystem
import akka.contrib.pattern.ClusterClient
import akka.util.Timeout

import com.stratio.crossdata.common.result.{ErrorResult, SuccessfulQueryResult}

import com.stratio.crossdata.common.{SQLCommand, SQLResult}
import com.stratio.crossdata.driver.actor.ProxyActor
import com.stratio.crossdata.driver.config.DriverConfig
import com.stratio.crossdata.driver.config.DriverConfig.DriverConfigHosts
import com.stratio.crossdata.driver.config.DriverConfig.DriverRetryTimes
import com.stratio.crossdata.driver.config.DriverConfig.DriverRetryDuration
import com.stratio.crossdata.driver.metadata.FieldMetadata
import com.stratio.crossdata.driver.utils.RetryPolitics
import com.typesafe.config.ConfigValue
import com.typesafe.config.ConfigValueFactory
import org.apache.log4j.Logger
import org.apache.spark.sql.Row
import org.apache.spark.sql.crossdata.metadata.DataTypesUtils
import org.apache.spark.sql.types.{DataType, StructType}


import scala.collection.JavaConversions._
import scala.concurrent.duration._

import scala.concurrent.Await
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.Try

object Driver extends DriverConfig {
  override lazy val logger = Logger.getLogger(getClass)
  val ActorsPath = "/user/receptionist"

  def apply(seedNodes: java.util.List[String]) =
    new Driver(seedNodes)

  def apply() = new Driver()

  def apply(flattenTables: Boolean) = new Driver(flattenTables: Boolean)

  lazy val defaultTimeout = Timeout(config .getDuration(DriverRetryDuration, MILLISECONDS), MILLISECONDS)
  lazy val defaultRetries = config.getInt(DriverRetryTimes)
}

class Driver(properties: java.util.Map[String, ConfigValue], flattenTables: Boolean) {

  def this(properties: java.util.Map[String, ConfigValue]) = this(properties, false)

  def this(serverHosts: java.util.List[String]) =
    this(Map(DriverConfigHosts -> ConfigValueFactory.fromAnyRef(serverHosts)), false)

  def this(flattenTables: Boolean) = this(Map.empty[String, ConfigValue], flattenTables)

  def this() = this(false)

  import Driver._

  /**
    * Tuple (tableName, Optional(databaseName))
    */
  type TableIdentifier = (String, Option[String])

  private lazy val logger = Driver.logger




  private val clientConfig =
    properties.foldLeft(Driver.config) { case (previousConfig, keyValue@(path, configValue)) =>
    previousConfig.withValue(path, configValue)
  }

  private val system = ActorSystem("CrossdataServerCluster", clientConfig)

  private val proxyActor = {

    if (logger.isDebugEnabled) {
      system.logConfiguration()
    }

    val contactPoints = {
      val hosts = clientConfig.getStringList("config.cluster.hosts").toList
      val clusterName = Driver.config.getString("config.cluster.name")
      hosts map (host => s"akka.tcp://$clusterName@$host$ActorsPath")
    }
    val initialContacts = contactPoints.map(system.actorSelection).toSet
    logger.debug("Initial contacts: " + initialContacts)

    val clusterClientActor = system.actorOf(ClusterClient.props(initialContacts), ProxyActor.RemoteClientName)
    logger.debug("Cluster client actor created with name: " + ProxyActor.RemoteClientName)

    system.actorOf(ProxyActor.props(clusterClientActor, this), ProxyActor.DefaultName)
  }

  /**
   * Execute an ordered shutdown
   */
  def close() = if(!system.isTerminated) system.shutdown()


  /**
    * Executes a SQL sentence in a synchronous way.
    * @param sqlCommand The SQL Command.
    * @param timeout Timeout in seconds.
    * @param retries Number of retries if the timeout was exceeded
    * @return A list of rows with the result of the query
    */
  // TODO syncQuery and asynQuery should be private when the driver get improved
  def syncQuery(sqlCommand: SQLCommand,
                timeout: Timeout = defaultTimeout,
                retries: Int = defaultRetries): SQLResult = {
    Try {
      Await.result(asyncQuery(sqlCommand, timeout, retries), timeout.duration * retries)
    } getOrElse ErrorResult(sqlCommand.queryId, s"Not found answer to query ${sqlCommand.query}. Timeout was exceed.")
  }

  /**
    * Executes a SQL sentence in an asynchronous way.
    * @param sqlCommand The SQL Command.
    * @param timeout Timeout in seconds.
    * @param retries Number of retries if the timeout was exceeded
    * @return A list of rows with the result of the query
    */
  def asyncQuery(sqlCommand: SQLCommand,
                 timeout: Timeout = defaultTimeout,
                 retries: Int = defaultRetries): Future[SQLResult] = {
    RetryPolitics.askRetry(proxyActor, sqlCommand, timeout, retries)
  }


  def listDatabases(): Seq[String] = {
    // TODO create command in XD Parser => syncQuery(SQLCommand("SHOW DATABASES"))
    ???
  }

  /**
    * Gets a list of tables from a database or all if the database is None
    * @param databaseName The database name
    * @return A sequence of tables an its database
    */
  def listTables(databaseName: Option[String] = None): Seq[TableIdentifier] = {
    def processTableName(qualifiedName: String): (String, Option[String]) = {
      qualifiedName.split('.') match {
        case table if table.length == 1 => (table(0), None)
        case table if table.length == 2 => (table(1), Some(table(0)))
      }
    }
    syncQuery(SQLCommand(s"SHOW TABLES ${databaseName.fold("")("IN " + _)}")) match {
      case SuccessfulQueryResult(_, result, _) =>
        result.map(row => processTableName(row.getString(0)))
      case other => handleCommandError(other)
    }
  }

  /**
    * Gets the metadata from a specific table.
    * @param database Database of the table.
    * @param tableName The name of the table.
    * @return A sequence with the metadata of the fields of the table.
    */
  def describeTable(database: Option[String], tableName: String): Seq[FieldMetadata] = {

    def extractNameDataType: Row => (String, String) = row => (row.getString(0), row.getString(1))

    syncQuery(SQLCommand(s"DESCRIBE ${database.map(_ + ".").getOrElse("")}$tableName")) match {

      case SuccessfulQueryResult(_, result, _) =>
        result.map(extractNameDataType) flatMap { case (name, dataType) =>
          if (!flattenTables) {
            FieldMetadata(name, DataTypesUtils.toDataType(dataType)) :: Nil
          } else {
            getFlattenedFields(name, DataTypesUtils.toDataType(dataType))
          }
        } toSeq

      case other =>
        handleCommandError(other)
    }
  }


  private def getFlattenedFields(fieldName: String, dataType: DataType): Seq[FieldMetadata] = dataType match {
    case structType: StructType =>
      structType.flatMap(field => getFlattenedFields(s"$fieldName.${field.name}", field.dataType))
    case _ =>
      FieldMetadata(fieldName, dataType) :: Nil
  }

  private def handleCommandError(result: SQLResult) = result match {
    case ErrorResult(_, message, Some(cause)) =>
      throw new RuntimeException(message, cause)
    case ErrorResult(_, message, _) =>
      throw new RuntimeException(message)
    // TODO manage exceptions
  }

}
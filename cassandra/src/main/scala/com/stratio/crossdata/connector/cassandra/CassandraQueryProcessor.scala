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
package com.stratio.crossdata.connector.cassandra


import com.datastax.driver.core.ResultSet
import com.stratio.crossdata.connector.cassandra.CassandraAttributeRole.{Unknown, ClusteringKey, Indexed}
import com.stratio.crossdata.connector.cassandra.CassandraAttributeRole.{PartitionKey, NonIndexed, Function}
import com.stratio.crossdata.connector.cassandra.CassandraAttributeRole.CassandraAttributeRole
import com.stratio.crossdata.connector.{SQLLikeQueryProcessorUtils, SQLLikeUDFQueryProcessorUtils}
import org.apache.spark.Logging
import org.apache.spark.sql.cassandra.{CassandraSQLRow, CassandraXDSourceRelation}
import org.apache.spark.sql.catalyst.expressions.{Alias, Attribute, Count, Expression, Literal, NamedExpression}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, Limit, Aggregate}
import org.apache.spark.sql.crossdata.catalyst.planning.ExtendedPhysicalOperation
import org.apache.spark.sql.crossdata.execution.NativeUDF
import org.apache.spark.sql.sources.CatalystToCrossdataAdapter.{AggregationLogicalPlan, BaseLogicalPlan}
import org.apache.spark.sql.sources.CatalystToCrossdataAdapter.{FilterReport, SimpleLogicalPlan}
import org.apache.spark.sql.sources.{Filter => SourceFilter, CatalystToCrossdataAdapter}
import org.apache.spark.sql.{Row, sources}

object CassandraQueryProcessor extends SQLLikeQueryProcessorUtils with SQLLikeUDFQueryProcessorUtils {

  val DefaultLimit = 10000
  type ColumnName = String

  case class CassandraQueryProcessorContext(val udfs: Map[String, NativeUDF]) extends SQLLikeUDFQueryProcessorUtils.ContextWithUDFs
  override type ProcessingContext = CassandraQueryProcessorContext

  case class CassandraPlan(basePlan: BaseLogicalPlan, limit: Option[Int]){
    def projects: Seq[NamedExpression] = basePlan.projects
    def filters: Array[SourceFilter] = basePlan.filters
    def udfsMap: Map[Attribute, NativeUDF] = basePlan.udfsMap
  }

  def apply(cassandraRelation: CassandraXDSourceRelation, logicalPlan: LogicalPlan) = new CassandraQueryProcessor(cassandraRelation, logicalPlan)

  def buildNativeQuery(
                        tableQN: String,
                        requiredColumns: Seq[String],
                        filters: Array[SourceFilter],
                        limit: Int,
                        udfs: Map[String, NativeUDF] = Map.empty): String = {

    implicit val procCtx = CassandraQueryProcessorContext(udfs)

    def filterToCQL(filter: SourceFilter): String = filter match {

      case sources.EqualTo(attribute, value) => s"${expandAttribute(attribute)} = ${quoteString(value)}"
      case sources.In(attribute, values) => s"${expandAttribute(attribute)} IN ${values.map(quoteString).mkString("(", ",", ")")}"
      case sources.LessThan(attribute, value) => s"${expandAttribute(attribute)} < ${quoteString(value)}"
      case sources.GreaterThan(attribute, value) => s"${expandAttribute(attribute)} > ${quoteString(value)}"
      case sources.LessThanOrEqual(attribute, value) => s"${expandAttribute(attribute)} <= ${quoteString(value)}"
      case sources.GreaterThanOrEqual(attribute, value) => s"${expandAttribute(attribute)} >= ${quoteString(value)}"
      case sources.And(leftFilter, rightFilter) => s"${filterToCQL(leftFilter)} AND ${filterToCQL(rightFilter)}"

    }

    val filter = if (filters.nonEmpty) filters.map(filterToCQL).mkString("WHERE ", " AND ", "") else ""
    val columns = requiredColumns.map(expandAttribute).mkString(", ")

    s"SELECT $columns FROM $tableQN $filter LIMIT $limit ALLOW FILTERING"
  }

}

// TODO logs, doc, tests
class CassandraQueryProcessor(cassandraRelation: CassandraXDSourceRelation, logicalPlan: LogicalPlan) extends Logging {

  import CassandraQueryProcessor._

  def execute(): Option[Array[Row]] = {
    def annotateRepeatedNames(names: Seq[String]): Seq[String] = {
      val indexedNames = names zipWithIndex
      val name2pos = indexedNames.groupBy(_._1).values.flatMap(_.zipWithIndex.map(x => x._1._2 -> x._2)).toMap
      indexedNames map { case (name, index) => val c = name2pos(index); if (c > 0) s"$name$c" else name }
    }

    def buildAggregationExpression(names: Expression): String = {
      names match {
        case Alias(child, _) => buildAggregationExpression(child)
        case Count(child) => s"count(${buildAggregationExpression(child)})"
        case Literal(1, _) => "*"
      }
    }

    try {
      validatedNativePlan.map { cassandraPlan =>
        if (cassandraPlan.limit.exists(_ == 0)) {
          Array.empty[Row]
        } else {
          val projectsString: Seq[String] = cassandraPlan.basePlan match {
            case SimpleLogicalPlan(projects, _, _, _) =>
              projects.map(_.toString())

            case AggregationLogicalPlan(projects, groupingExpression, _, _, _) =>
              require(groupingExpression.isEmpty)
              projects.map(buildAggregationExpression)
          }

          val cqlQuery = buildNativeQuery(
            cassandraRelation.tableDef.name,
            projectsString,
            cassandraPlan.filters,
            cassandraPlan.limit.getOrElse(CassandraQueryProcessor.DefaultLimit),
            cassandraPlan.udfsMap map { case (k, v) => k.toString -> v }
          )
          val resultSet = cassandraRelation.connector.withSessionDo { session =>
            session.execute(cqlQuery)
          }
          sparkResultFromCassandra(annotateRepeatedNames(cassandraPlan.projects.map(_.name)).toArray, resultSet)
        }

      }
    } catch {
      case exc: Exception => log.warn(s"Exception executing the native query $logicalPlan", exc.getMessage); None
    }

  }


  def validatedNativePlan: Option[CassandraPlan] = {
    lazy val limit: Option[Int] = logicalPlan.collectFirst { case Limit(Literal(num: Int, _), _) => num }

    def findBasePlan(lplan: LogicalPlan): Option[BaseLogicalPlan] = {
      lplan match {
        // TODO lines below seem to be duplicated in ExtendedPhysicalOperation when finding filters and projects
        case Limit(_, child) =>
          findBasePlan(child)

        case Aggregate(_, _, child) =>
          findBasePlan(child)

        case ExtendedPhysicalOperation(projectList, filterList, _) =>
          CatalystToCrossdataAdapter.getConnectorLogicalPlan(logicalPlan, projectList, filterList) match {
            case (_, FilterReport(filtersIgnored, _)) if filtersIgnored.nonEmpty => None
            case (basePlan, _) => Some(basePlan)
          }
      }
    }
    findBasePlan(logicalPlan).collect{ case bp if checkNativeFilters(bp.filters, bp.udfsMap) => CassandraPlan(bp, limit)}
  }

  private[this] def checkNativeFilters(filters: Array[SourceFilter],
                                       udfs: Map[Attribute, NativeUDF]): Boolean = {

    val udfNames = udfs.keys.map(_.toString).toSet

    val groupedFilters = filters.groupBy {
      case sources.EqualTo(attribute, _) => attributeRole(attribute, udfNames)
      case sources.In(attribute, _) => attributeRole(attribute, udfNames)
      case sources.LessThan(attribute, _) => attributeRole(attribute, udfNames)
      case sources.GreaterThan(attribute, _) => attributeRole(attribute, udfNames)
      case sources.LessThanOrEqual(attribute, _) => attributeRole(attribute, udfNames)
      case sources.GreaterThanOrEqual(attribute, _) => attributeRole(attribute, udfNames)
      case _ => Unknown
    }

    def checksClusteringKeyFilters: Boolean =
      !groupedFilters.contains(ClusteringKey) || {
        // if there is a CK filter then all CKs should be included. Accept any kind of filter
        val clusteringColsInFilter = groupedFilters.get(ClusteringKey).get.flatMap(columnNameFromFilter)
        cassandraRelation.tableDef.clusteringColumns.forall { column =>
          clusteringColsInFilter.contains(column.columnName)
        }
      }

    def checksSecondaryIndexesFilters: Boolean =
      !groupedFilters.contains(Indexed) || {
        //Secondary indexes => equals are allowed
        groupedFilters.get(Indexed).get.forall {
          case sources.EqualTo(_, _) => true
          case _ => false
        }
      }


    def checksPartitionKeyFilters: Boolean =
      !groupedFilters.contains(PartitionKey) || {
        val partitionColsInFilter = groupedFilters.get(PartitionKey).get.flatMap(columnNameFromFilter)

        // all PKs must be present
        cassandraRelation.tableDef.partitionKey.forall { column =>
          partitionColsInFilter.contains(column.columnName)
        }
        // filters condition must be = or IN with restrictions
        groupedFilters.get(PartitionKey).get.forall {
          case sources.EqualTo(_, _) => true
          case sources.In(colName, _) if cassandraRelation.tableDef.partitionKey.last.columnName.equals(colName) => true
          case _ => false
        }
      }

    {
      !groupedFilters.contains(Unknown) && !groupedFilters.contains(NonIndexed) &&
        checksPartitionKeyFilters && checksClusteringKeyFilters && checksSecondaryIndexesFilters
    }

  }

  private[this] def columnNameFromFilter(sourceFilter: SourceFilter): Option[ColumnName] = sourceFilter match {
    case sources.EqualTo(attribute, _) => Some(attribute)
    case sources.In(attribute, _) => Some(attribute)
    case sources.LessThan(attribute, _) => Some(attribute)
    case sources.GreaterThan(attribute, _) => Some(attribute)
    case sources.LessThanOrEqual(attribute, _) => Some(attribute)
    case sources.GreaterThanOrEqual(attribute, _) => Some(attribute)
    case _ => None
  }

  private[this] def attributeRole(columnName: String, udfs: Set[String]): CassandraAttributeRole =
    if (udfs contains columnName) Function
    else cassandraRelation.tableDef.columnByName(columnName) match {
      case x if x.isPartitionKeyColumn => PartitionKey
      case x if x.isClusteringColumn => ClusteringKey
      case x if x.isIndexedColumn => Indexed
      case _ => NonIndexed
    }

  private[this] def sparkResultFromCassandra(requiredColumns: Array[ColumnName], resultSet: ResultSet): Array[Row] = {
    import scala.collection.JavaConversions._
    resultSet.all().map(CassandraSQLRow.fromJavaDriverRow(_, requiredColumns)).toArray
  }

}



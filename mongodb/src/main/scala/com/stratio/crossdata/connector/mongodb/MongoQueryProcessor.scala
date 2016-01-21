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
package com.stratio.crossdata.connector.mongodb

import java.util.regex.Pattern

import com.mongodb.casbah.Imports._
import com.mongodb.DBObject
import com.mongodb.QueryBuilder
import com.stratio.datasource.Config
import com.stratio.datasource.mongodb.MongodbConfig
import com.stratio.datasource.mongodb.schema.MongodbRowConverter._
import com.stratio.datasource.mongodb.MongodbRelation._

import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.planning.PhysicalOperation
import org.apache.spark.sql.catalyst.plans.logical.{Limit => LogicalLimit, LogicalPlan}
import org.apache.spark.sql.sources.CatalystToCrossdataAdapter.{BaseLogicalPlan, FilterReport, SimpleLogicalPlan}
import org.apache.spark.sql.sources.CatalystToCrossdataAdapter
import org.apache.spark.sql.sources.{Filter => SourceFilter}
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.Row
import org.apache.spark.sql.sources

object MongoQueryProcessor {

  val DefaultLimit = 10000
  type ColumnName = String
  type Limit = Option[Int]

  case class MongoPlan(basePlan: BaseLogicalPlan, limit: Limit){
    def projects: Seq[NamedExpression] = basePlan.projects
    def filters: Array[SourceFilter] = basePlan.filters
  }

  def apply(logicalPlan: LogicalPlan, config: Config, schemaProvided: Option[StructType] = None) = new MongoQueryProcessor(logicalPlan, config, schemaProvided)

  def buildNativeQuery(
                        requiredColums: Seq[ColumnName],
                        filters: Array[SourceFilter],
                        config: Config,
                        name2randomAccess: Map[String, GetArrayItem] = Map.empty
                      ): (DBObject, DBObject) = {
    (filtersToDBObject(filters, name2randomAccess)(config), selectFields(requiredColums))
  }

  def filtersToDBObject(
                         sFilters: Array[SourceFilter],
                         name2randomAccess: Map[String, GetArrayItem],
                         parentFilterIsNot: Boolean = false
                       )(implicit config: Config): DBObject = {

    def attstr2left(att: String): String =
      name2randomAccess.get(att).map {
        case GetArrayItem(att: AttributeReference, ordinal) =>
          s"${att.name}.${ordinal.toString}"
      } getOrElse att

    val queryBuilder: QueryBuilder = QueryBuilder.start

    if (parentFilterIsNot) queryBuilder.not()
    sFilters.foreach {
      case sources.EqualTo(attribute, value) =>
        queryBuilder.put(attstr2left(attribute)).is(correctIdValue(attribute, value))
      case sources.GreaterThan(attribute, value) =>
        queryBuilder.put(attstr2left(attribute)).greaterThan(correctIdValue(attribute, value))
      case sources.GreaterThanOrEqual(attribute, value) =>
        queryBuilder.put(attstr2left(attribute)).greaterThanEquals(correctIdValue(attribute, value))
      case sources.In(attribute, values) =>
        queryBuilder.put(attstr2left(attribute)).in(values.map(value => correctIdValue(attribute, value)))
      case sources.LessThan(attribute, value) =>
        queryBuilder.put(attstr2left(attribute)).lessThan(correctIdValue(attribute, value))
      case sources.LessThanOrEqual(attribute, value) =>
        queryBuilder.put(attstr2left(attribute)).lessThanEquals(correctIdValue(attribute, value))
      case sources.IsNull(attribute) =>
        queryBuilder.put(attstr2left(attribute)).is(null)
      case sources.IsNotNull(attribute) =>
        queryBuilder.put(attstr2left(attribute)).notEquals(null)
      case sources.And(leftFilter, rightFilter) if !parentFilterIsNot =>
        queryBuilder.and(filtersToDBObject(Array(leftFilter), name2randomAccess),
          filtersToDBObject(Array(rightFilter),name2randomAccess))
      case sources.Or(leftFilter, rightFilter) if !parentFilterIsNot =>
        queryBuilder.or(filtersToDBObject(Array(leftFilter),name2randomAccess),
          filtersToDBObject(Array(rightFilter), name2randomAccess))
      case sources.StringStartsWith(attribute, value) if !parentFilterIsNot =>
        queryBuilder.put(attstr2left(attribute)).regex(Pattern.compile("^" + value + ".*$"))
      case sources.StringEndsWith(attribute, value) if !parentFilterIsNot =>
        queryBuilder.put(attstr2left(attribute)).regex(Pattern.compile("^.*" + value + "$"))
      case sources.StringContains(attribute, value) if !parentFilterIsNot =>
        queryBuilder.put(attstr2left(attribute)).regex(Pattern.compile(".*" + value + ".*"))
      case sources.Not(filter) =>
        filtersToDBObject(Array(filter), name2randomAccess, true)
    }

    queryBuilder.get
  }

    /**
      * Check if the field is "_id" and if the user wants to filter by this field as an ObjectId
      *
      * @param attribute Name of the file
      * @param value Value for the attribute
      * @return The value in the correct data type
      */
     private def correctIdValue(attribute: String, value: Any)(implicit config: Config) : Any = {

      val idAsObjectId: Boolean = config.getOrElse[String](MongodbConfig.IdAsObjectId, MongodbConfig.DefaultIdAsObjectId).equalsIgnoreCase("true")

      attribute match {
        case "_id" if idAsObjectId => new ObjectId(value.toString)
        case _ => value
      }
    }

    /**
   *
   * Prepared DBObject used to specify required fields in mongodb 'find'
   * @param fields Required fields
   * @return A mongodb object that represents required fields.
   */
  private def selectFields(fields: Seq[ColumnName]): DBObject =
    {
      MongoDBObject(
        fields.toList.filterNot(_ == "_id").map(_ -> 1) ::: {
          List("_id" -> fields.find(_ == "_id").fold(0)(_ => 1))
        })
      /*
        For random accesses to array columns elements, a performance improvement is doable
        by querying MongoDB in a way that would only select a size-1 slice of the accessed array thanks to
        the "$slice" operator. However this operator can only be used once for each column in a projection
        which implies that several accesses (e.g: SELECT arraystring[0] as first, arraystring[3] as fourth FROM MONGO_T)
        would require to implement an smart "$slice" use selecting the minimum slice containing all requested elements.
        That requires way too much effort when the performance boost is taken into consideration.
       */
    }

}


// TODO logs, doc, tests
class MongoQueryProcessor(logicalPlan: LogicalPlan, config: Config, schemaProvided: Option[StructType] = None) extends Logging {

  import MongoQueryProcessor._

  def execute(): Option[Array[Row]] = {

    // TODO convert to Spark result using an iterator with batches instead of an array
    if (schemaProvided.isEmpty) {
      None
    } else {
      try {
        validatedNativePlan.map { case MongoPlan(bs: SimpleLogicalPlan, limit) =>
          if (limit.exists(_ == 0)) {
            Array.empty[Row]
          } else {
            val name2randomAccess = bs.collectionRandomAccesses.map {
              case (k, v) => s"${k.name}[${v.right}]" -> v
            }
            val (mongoFilters, mongoRequiredColumns) = buildNativeQuery(
              bs.projects.map(_.name), bs.filters,
              config,
              name2randomAccess
            )
            val resultSet = MongodbConnection.withCollectionDo(config) { collection =>
              logDebug(s"Executing native query: filters => $mongoFilters projects => $mongoRequiredColumns")
              val cursor = collection.find(mongoFilters, mongoRequiredColumns)
              val result = cursor.limit(limit.getOrElse(DefaultLimit)).toArray[DBObject]
              cursor.close()
              result
            }
            sparkResultFromMongodb(bs.projects, bs.collectionRandomAccesses, schemaProvided.get, resultSet)
          }
        }
      } catch {
        case exc: Exception =>
          log.warn(s"Exception executing the native query $logicalPlan", exc.getMessage); None
      }
    }

  }


  def validatedNativePlan: Option[_] = {// TODO
    lazy val limit: Option[Int] = logicalPlan.collectFirst { case LogicalLimit(Literal(num: Int, _), _) => num }

    def findBasePlan(lplan: LogicalPlan): Option[BaseLogicalPlan] = lplan match {

      case LogicalLimit(_, child) =>
        findBasePlan(child)

      case PhysicalOperation(projectList, filterList, _) =>
        CatalystToCrossdataAdapter.getConnectorLogicalPlan(logicalPlan, projectList, filterList) match {
          case (_, FilterReport(filtersIgnored, _)) if filtersIgnored.nonEmpty => None
          case (basePlan: SimpleLogicalPlan, _) =>
            Some(basePlan)
          case _ => ??? // TODO
        }

    }

    findBasePlan(logicalPlan).collect{ case bp if checkNativeFilters(bp.filters) => MongoPlan(bp, limit) }
  }


  private[this] def checkNativeFilters(filters: Seq[SourceFilter]): Boolean = filters.forall {
    case _: sources.EqualTo => true
    case _: sources.In => true
    case _: sources.LessThan => true
    case _: sources.GreaterThan => true
    case _: sources.LessThanOrEqual => true
    case _: sources.GreaterThanOrEqual => true
    case _: sources.IsNull => true
    case _: sources.IsNotNull => true
    case _: sources.StringStartsWith => true
    case _: sources.StringEndsWith => true
    case _: sources.StringContains => true
    case sources.And(left, right) => checkNativeFilters(Array(left, right))
    case sources.Or(left, right) => checkNativeFilters(Array(left, right))
    case sources.Not(filter) => checkNativeFilters(Array(filter))
    // TODO add more filters
    case _ => false

  }

  private[this] def sparkResultFromMongodb(
                                            requiredColumns: Seq[Attribute],
                                            indexAccesses: Map[Attribute, GetArrayItem],
                                            schema: StructType,
                                            resultSet: Array[DBObject]
                                          ): Array[Row] = {
    asRow(
      pruneSchema(
        schema,
        requiredColumns.map(r => r.name -> indexAccesses.get(r).map(_.right.toString().toInt)).toArray
      ),
      resultSet
    )
  }


}



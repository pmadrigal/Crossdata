/*
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
package org.apache.spark.sql.crossdata

import java.nio.file.Paths

import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAlias, UnresolvedRelation, UnresolvedStar}
import org.apache.spark.sql.catalyst.plans.Inner
import org.apache.spark.sql.catalyst.plans.logical.{Join, Project}
import org.apache.spark.sql.crossdata.catalyst.execution.PersistDataSourceTable
import org.apache.spark.sql.crossdata.test.SharedXDContextTest
import org.apache.spark.sql.execution.ExecutedCommand
import org.apache.spark.sql.types.{IntegerType, StringType, StructField, StructType}
import org.apache.spark.sql.{DataFrame, Row}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class XDContextIT extends SharedXDContextTest {

  "A DefaultCatalog" should "be case sensitive" in {
    val xdCatalog = xdContext.catalog
    assert(xdCatalog.conf.caseSensitiveAnalysis === true)
  }

  "A XDContext" should "perform a collect with a collection" in {

    val df: DataFrame = xdContext.createDataFrame(xdContext.sparkContext.parallelize((1 to 5).map(i => Row(s"val_$i"))), StructType(Array(StructField("id", StringType))))
    df.registerTempTable("records")

    val result: Array[Row] = xdContext.sql("SELECT * FROM records").collect()

    result should have length 5
  }

  it must "return a XDDataFrame when executing a SQL query" in {

    val df: DataFrame = xdContext.createDataFrame(xdContext.sparkContext.parallelize((1 to 5).map(i => Row(s"val_$i"))), StructType(Array(StructField("id", StringType))))
    df.registerTempTable("records")

    val dataframe = xdContext.sql("SELECT * FROM records")
    dataframe shouldBe a[XDDataFrame]
  }


  it must "plan a PersistDataSource when creating a table " in {
    val dataframe = xdContext.sql(s"CREATE TABLE jsonTable USING org.apache.spark.sql.json OPTIONS (path '${Paths.get(getClass.getResource("/core-reference.conf").toURI()).toString}')")
    val sparkPlan = dataframe.queryExecution.sparkPlan
    xdContext.catalog.dropTable(TableIdentifier("jsonTable", None))
    sparkPlan should matchPattern { case ExecutedCommand(_: PersistDataSourceTable) => }

  }

  it must "plan a query with conflicted column names between two tables resolving by alias preference" in {

    val t1: DataFrame = xdContext.createDataFrame(xdContext.sparkContext.parallelize((1 to 5)
      .map(i => Row(s"val_$i", i))), StructType(Array(StructField("id", StringType), StructField("value", IntegerType))))
    t1.registerTempTable("t1")

    val t2: DataFrame = xdContext.createDataFrame(xdContext.sparkContext.parallelize((4 to 8)
      .map(i => Row(s"val_$i", i))), StructType(Array(StructField("name", StringType), StructField("value", IntegerType))))
    t2.registerTempTable("t2")

    val dataFrame = xdContext.sql("SELECT t1.id, t2.name as name, t1.value as total FROM t1 INNER JOIN t2 ON t1.id = t2.name GROUP BY id, name, total")

    dataFrame.show

    dataFrame.collect should have length 2

  }

  it must "plan a query with aliased attributes in the group by clause" in {

    val t1: DataFrame = xdContext.createDataFrame(xdContext.sparkContext.parallelize((1 to 5)
      .map(i => Row(s"val_$i", i))), StructType(Array(StructField("id", StringType), StructField("value", IntegerType))))
    t1.registerTempTable("t3")

    val dataFrame = xdContext.sql("SELECT id as id, value as product FROM t3 GROUP BY id, product")

    dataFrame.collect should have length 5

  }

  it must "use statically defined UDFs to convert a string to an integer" in {

    val df = xdContext.createDataFrame(
      Seq(Row("42"), Row("-42"), Row("3.3")),
      StructType(Array(StructField("id", StringType)))
    )

    df.registerTempTable("TONUMBERTABLE")

    sql("SELECT to_number(id) FROM TONUMBERTABLE").collect() shouldEqual Array(
      Row(42L),
      Row(-42L),
      Row(3.3D)
    )

  }

  it must "plan a query with a filter and an alias for the table" in {

    val t1: DataFrame = xdContext.createDataFrame(
      xdContext.sparkContext.parallelize((1 to 5).map(i => Row(s"val_$i", i))),
      StructType(Array(StructField("id", StringType), StructField("value", IntegerType))))
    t1.registerTempTable("t1")

    val t2: DataFrame = xdContext.createDataFrame(
      xdContext.sparkContext.parallelize((4 to 8).map(i => Row(s"val_$i", i, i*2))),
      StructType(Array(StructField("ident", StringType), StructField("num", IntegerType), StructField("magic", IntegerType))))
    t2.registerTempTable("t2")

    val dataFrame = xdContext.sql("SELECT st.num FROM t2 st WHERE st.num <= 10")

    dataFrame.show

    dataFrame.collect should have length 5

  }

  it must "plan a query with a in clause" in {

    val t1: DataFrame = xdContext.createDataFrame(
      xdContext.sparkContext.parallelize((1 to 5).map(i => Row(s"val_$i", i))),
      StructType(Array(StructField("id", StringType), StructField("value", IntegerType))))
    t1.registerTempTable("t1")

    val t2: DataFrame = xdContext.createDataFrame(
      xdContext.sparkContext.parallelize((4 to 8).map(i => Row(s"val_$i", i, i*2))),
      StructType(Array(StructField("ident", StringType), StructField("num", IntegerType), StructField("magic", IntegerType))))
    t2.registerTempTable("t2")

    val dataFrame = xdContext.sql("SELECT * FROM t1 WHERE (t1.value IN (1, 3, 5, 7, 9)) GROUP BY value, id")

    dataFrame.show

    dataFrame.collect should have length 3

  }

  it must "plan a query with same alias in different scopes" in {

    val t1: DataFrame = xdContext.createDataFrame(
      xdContext.sparkContext.parallelize((1 to 5).map(i => Row(s"val_$i", i))),
      StructType(Array(StructField("id", StringType), StructField("value", IntegerType))))
    t1.registerTempTable("t1")

    val t2: DataFrame = xdContext.createDataFrame(
      xdContext.sparkContext.parallelize((4 to 8).map(i => Row(s"val_$i", i, i/2))),
      StructType(Array(StructField("ident", StringType), StructField("num", IntegerType), StructField("magic", IntegerType))))
    t2.registerTempTable("t2")

    val dataFrame = xdContext.sql("SELECT * FROM (SELECT *  FROM t1 ft WHERE ft.value = 2) ft WHERE ft.id = 'val_2'")

    dataFrame.show

    dataFrame.collect should have length 1

  }

  it must "fail when a subquery is used in a filter" in {

    val t1: DataFrame = xdContext.createDataFrame(
      xdContext.sparkContext.parallelize((1 to 5).map(i => Row(s"val_$i", i))),
      StructType(Array(StructField("id", StringType), StructField("value", IntegerType))))
    t1.registerTempTable("t1")

    an[Exception] should be thrownBy xdContext.sql("SELECT * FROM t1 WHERE t1.value = (SELECT first(value) FROM t1)")

  }

  it should "succesfully parse a CROSS JOIN" in {

    val crossJoin = "SELECT * FROM table1 CROSS JOIN table2"

    xdContext.parseSql(crossJoin) shouldBe xdContext.parseSql("SELECT * FROM table1 JOIN table2")
    xdContext.parseSql(crossJoin) shouldBe Project(UnresolvedAlias(UnresolvedStar(None)):: Nil, Join(UnresolvedRelation(TableIdentifier("table1")), UnresolvedRelation(TableIdentifier("table2")), Inner, None))

  }



//  it must "execute jar app previously uploaded" in {
//    val file = File(s"TestAddApp.jar")
//    xdContext.addJar("TestAddApp.jar")
//    xdContext.addApp(file.path, "com.stratio.addApp.AddAppTest.main", "app1")
//
//    xdContext.executeApp("app1", Seq("hi", "world"))
//
//  }


}

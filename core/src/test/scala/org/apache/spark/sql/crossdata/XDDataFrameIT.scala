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
package org.apache.spark.sql.crossdata

import com.stratio.crossdata.connector.NativeScan
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.Row
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.crossdata.test.SharedXDContextTest
import org.apache.spark.sql.execution.datasources.LogicalRelation
import org.apache.spark.sql.sources.TableScan
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}
import org.junit.runner.RunWith
import org.scalatest.Inside
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class XDDataFrameIT extends SharedXDContextTest with Inside {

  lazy val sparkRows = xdContext.createDataFrame(xdContext.sparkContext.parallelize(Seq(Row(1))), StructType(Array(StructField("id", IntegerType)))).collect()
  lazy val nativeRows = Array(Row(2l))

  "A XDDataFrame (select * from nativeRelation)" should "be executed natively" in {
    val result = XDDataFrame(xdContext, LogicalRelation(mockNativeRelation)).collect()
    result should have length 1
    result(0) should equal(nativeRows(0))
  }

  "A cached XDDataFrame (select * from nativeRelation)" should "be executed on the Spark cluster" in {
    val dataframe = XDDataFrame(xdContext, LogicalRelation(mockNativeRelation))
    val result = dataframe.cache().collect()
    dataframe.unpersist(blocking = true)
    result should have length 1
    result(0) should equal(sparkRows(0))

  }

  "A XDDataFrame resulting in an error when executing natively" should "be executed on the Spark cluster" in {
    val result = XDDataFrame(xdContext, LogicalRelation(mockPureSparkNativeRelation)).collect()
    result should have length 1
    result(0) should equal(sparkRows(0))
  }

  "A XDDataFrame with a logical plan which is not supported natively" should "be executed on the Spark cluster" in {
    val result = XDDataFrame(xdContext, LogicalRelation(mockNativeRelationUnsupportedPlan)).collect()
    result should have length 1
    result(0) should equal(sparkRows(0))
  }

  "A XDDataFrame " should "execute collectAsList natively" in {
    val result = XDDataFrame(xdContext, LogicalRelation(mockNativeRelation)).collectAsList()
    result should have length 1
    result.get(0) should equal(nativeRows(0))
  }

  "A XDDataFrame " should "return a XDDataFrame when applying a limit" in {
    val dataframe = XDDataFrame(xdContext, LogicalRelation(mockNativeRelation)).limit(5)
    dataframe shouldBe a[XDDataFrame]
    dataframe.logicalPlan should matchPattern { case Limit(Literal(5, _), _) => }
  }

  "A XDDataFrame " should "return a XDDataFrame when applying a count" in {
    XDDataFrame(xdContext, LogicalRelation(mockNativeRelation)).count() should be(2l)
  }


  val mockNonNativeRelation = new MockBaseRelation

  val mockNativeRelation = new MockBaseRelation with NativeScan with TableScan {
    override def isSupported(logicalStep: LogicalPlan, fullyLogicalPlan: LogicalPlan) = true

    // Native execution
    override def buildScan(optimizedLogicalPlan: LogicalPlan): Option[Array[Row]] = Some(nativeRows)

    // Spark execution
    override def buildScan(): RDD[Row] = xdContext.createDataFrame(xdContext.sparkContext.parallelize(Seq(Row(1))), StructType(Array(StructField("id", IntegerType)))).rdd
  }


  val mockPureSparkNativeRelation = new MockBaseRelation with NativeScan with TableScan {
    override def isSupported(logicalStep: LogicalPlan, fullyLogicalPlan: LogicalPlan) = true

    // Native execution
    override def buildScan(optimizedLogicalPlan: LogicalPlan): Option[Array[Row]] = None

    // Spark execution
    override def buildScan(): RDD[Row] = xdContext.createDataFrame(xdContext.sparkContext.parallelize(Seq(Row(1))), StructType(Array(StructField("id", IntegerType)))).rdd
  }

  val mockNativeRelationWith2Rows = new MockBaseRelation with NativeScan with TableScan {
    override def isSupported(logicalStep: LogicalPlan, fullyLogicalPlan: LogicalPlan) = true

    // Native execution
    override def buildScan(optimizedLogicalPlan: LogicalPlan): Option[Array[Row]] = Some(Array(nativeRows(0), nativeRows(0)))

    // Spark execution
    override def buildScan(): RDD[Row] = xdContext.createDataFrame(xdContext.sparkContext.parallelize(Seq(Row(1))), StructType(Array(StructField("id", IntegerType)))).rdd
  }

  val mockNativeRelationUnsupportedPlan = new MockBaseRelation with NativeScan with TableScan {
    override def isSupported(logicalStep: LogicalPlan, fullyLogicalPlan: LogicalPlan) = false

    // Native execution
    override def buildScan(optimizedLogicalPlan: LogicalPlan): Option[Array[Row]] = Some(nativeRows)

    // Spark execution
    override def buildScan(): RDD[Row] = xdContext.createDataFrame(xdContext.sparkContext.parallelize(Seq(Row(1))), StructType(Array(StructField("id", IntegerType)))).rdd
  }

}

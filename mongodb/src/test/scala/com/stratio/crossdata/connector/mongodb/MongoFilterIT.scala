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

import org.apache.spark.sql.Row
import org.apache.spark.sql.crossdata.ExecutionType
import org.apache.spark.sql.crossdata.test.SharedXDContextTypesTest
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class MongoFilterIT extends MongoDataTypesCollection {

  "MongoConnector" should "supports NOT BETWEEN by spark" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT id FROM $Collection WHERE id NOT BETWEEN 2 AND 10").collect(ExecutionType.Spark).head

    val result = Row(1)
    sparkRow should be (result)

  }

  it should "supports equals AND NOT IN by spark" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT id FROM $Collection WHERE id = 6 AND id NOT IN (2,3,4,5)").collect(ExecutionType.Spark).head

    val result = Row(6)
    sparkRow should be (result)

  }

  it should "supports NOT LIKE by spark" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT description FROM $Collection WHERE description NOT LIKE 'description1'").collect(ExecutionType.Spark)

    val result = (2 to 10).map(n => Row(s"description$n")).toArray
    sparkRow should be (result)

  }

  it should "supports filter DATE greater than" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT date FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE date > '1970'").collect(ExecutionType.Spark)
    sparkRow.length should be (10)

  }

  it should "supports filter DATE equals to" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT date FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE date = '1970-01-02'").collect(ExecutionType.Spark)
    sparkRow.length should be (1)
  }

  it should "supports filter DATE BETWEEN two dates" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT date FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE date BETWEEN '1970' AND '1971'").collect(ExecutionType.Spark)
    sparkRow.length should be (10)

  }

  it should "supports filter DATE NOT BETWEEN two dates" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT date FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE date NOT BETWEEN '1970-01-02' AND '1971'").collect(ExecutionType.Spark)
    sparkRow.length should be (1)

  }

  it should "supports filter TIMESTAMP greater than" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT timestamp FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE timestamp > '1970'").collect(ExecutionType.Spark)
    sparkRow.length should be (10)
  }

  it should "supports filter TIMESTAMP equals to" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT timestamp FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE timestamp = '1970-01-02 00:0:0.002'").collect(ExecutionType.Native)
    sparkRow.head(0) should be (java.sql.Timestamp.valueOf("1970-01-02 00:00:00.002"))

  }

  /** See PromoteStrings*/
  it should "supports filter TIMESTAMP less or equals to" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT timestamp FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE timestamp <= '1970-01-01 00:00:00.001'").collect(ExecutionType.Native)
    sparkRow.size should be > 0
    sparkRow.head(0) should be (java.sql.Timestamp.valueOf("1970-01-01 00:00:00.001"))

  }


  it should "supports filter TIMESTAMP BETWEEN two times" in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT timestamp FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE timestamp BETWEEN '1970' AND '1971'").collect(ExecutionType.Spark)
    sparkRow.length should be (10)

  }


  it should "supports Native filter DATE LESS OR EQUALS THAN " in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT date FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE timestamp <= '1970-01-02 00:0:0.002'").collect(ExecutionType.Native)
    sparkRow.length should be (2)
  }

  it should "supports Native filter DATE GREATER OR EQUALS THAN " in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT date FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE date >= '1970-01-02'").collect(ExecutionType.Native)
    sparkRow.length should be (9)
  }

  it should "supports Native filter DATE LESS THAN " in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT date FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE date < '1970-01-02'").collect(ExecutionType.Native)
    sparkRow.length should be (1)
  }

  it should "supports Native filter DATE GREATER THAN " in {
    assumeEnvironmentIsUpAndRunning

    val sparkRow = sql(s"SELECT timestamp FROM ${SharedXDContextTypesTest.dataTypesTableName} WHERE timestamp > '1970-01-02'").collect(ExecutionType.Native)
    sparkRow.foreach(print(_))
    sparkRow.length should be (9)
  }
}

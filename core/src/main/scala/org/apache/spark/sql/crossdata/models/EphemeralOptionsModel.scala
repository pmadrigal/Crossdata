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

package org.apache.spark.sql.crossdata.models

case class EphemeralOptionsModel(kafkaOptions: KafkaOptionsModel,
                                 atomicWindow: Int = EphemeralOptionsModel.DefaultAtomicWindow)

object EphemeralOptionsModel {

  /**
   * Default minimum Time in Seconds for the Batch Interval in SparkStreaming.
   * This parameter mark the the minimum time for the windowed queries
   */
  val DefaultAtomicWindow = 5
}
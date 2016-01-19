/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stration.crossdata.streaming.constants

object KafkaConstants {

  val DefaultPartition = 1
  val DefaulConsumerPort = "2181"
  val DefaulProducerPort = "9092"
  val DefaultHost = "127.0.0.1"
  val ZookeeperConnectionKey = "zookeeper.connect"
  val GroupIdKey = "group.id"
  val SerializerKey = "serializer.class"
  val BrokerListKey = "metadata.broker.list"
  val RequiredAckKey = "requiredAcks"
  val CompressionCodecKey = "compressionCodec"
  val ProducerTypeKey = "producerType"
  val BatchSizeKey = "batchSize"
  val MaxRetriesKey = "maxRetries"
  val ClientIdKey = "clientId"
  val DefaultSerializer = "kafka.serializer.StringEncoder"

  val producerProperties = Map(
    RequiredAckKey -> "request.required.acks",
    CompressionCodecKey -> "compression.codec",
    ProducerTypeKey -> "producer.type",
    BatchSizeKey -> "batch.num.messages",
    MaxRetriesKey -> "message.send.max.retries",
    ClientIdKey -> "client.id")
}

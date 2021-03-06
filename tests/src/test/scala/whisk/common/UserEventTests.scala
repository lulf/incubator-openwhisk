/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.common

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import common._
import common.rest.WskRest
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}
import whisk.connector.kafka.KafkaConsumerConnector
import whisk.core.WhiskConfig
import whisk.core.connector.{Activation, EventMessage, Metric}

import scala.concurrent.duration._

@RunWith(classOf[JUnitRunner])
class UserEventTests extends FlatSpec with Matchers with WskTestHelpers with StreamLogging with BeforeAndAfterAll {

  implicit val wskprops = WskProps()
  implicit val system = ActorSystem("UserEventTestSystem")
  val config = new WhiskConfig(WhiskConfig.kafkaHosts)

  val wsk = new WskRest

  val groupid = "kafkatest"
  val topic = "events"
  val maxPollInterval = 10.seconds

  val consumer = new KafkaConsumerConnector(config.kafkaHosts, groupid, topic)
  val testActionsDir = WhiskProperties.getFileRelativeToWhiskHome("tests/dat/actions")
  behavior of "UserEvents"

  override def afterAll() {
    consumer.close()
  }

  if (UserEvents.enabled) {
    it should "invoke an action and produce user events" in withAssetCleaner(wskprops) { (wp, assetHelper) =>
      val file = Some(TestUtils.getTestActionFilename("hello.js"))
      val name = "testUserEvents"

      assetHelper.withCleaner(wsk.action, name, confirmDelete = true) { (action, _) =>
        action.create(name, file)
      }

      val run = wsk.action.invoke(name, blocking = true)

      withActivation(wsk.activation, run) { result =>
        withClue("invoking an action was unsuccessful") {
          result.response.status shouldBe "success"
        }
      }
      // checking for any metrics to arrive
      val received =
        consumer.peek(maxPollInterval).map {
          case (_, _, _, msg) => EventMessage.parse(new String(msg, StandardCharsets.UTF_8))
        }
      received.map(event => {
        event.body match {
          case a: Activation =>
            Seq(a.statusCode) should contain oneOf (0, 1, 2, 3)
            event.source should fullyMatch regex "invoker\\d+".r
          case m: Metric =>
            Seq(m.metricName) should contain oneOf ("ConcurrentInvocations", "ConcurrentRateLimit", "TimedRateLimit")
            event.source should fullyMatch regex "controller\\d+".r
        }
      })
      // produce at least 2 events - an Activation and a 'ConcurrentInvocations' Metric
      // >= 2 is due to events that might have potentially occurred in between
      received.size should be >= 2
      consumer.commit()
    }

  }

}

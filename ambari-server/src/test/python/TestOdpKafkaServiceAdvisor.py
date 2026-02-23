"""
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
"""

import importlib.util
import imp
import os
from unittest import TestCase


class TestOdpKafkaServiceAdvisor(TestCase):

  test_directory = os.path.dirname(os.path.abspath(__file__))
  resources_path = os.path.join(test_directory, '../../main/resources')

  ambari_configuration_path = os.path.abspath(os.path.join(resources_path, 'stacks/ambari_configuration.py'))
  with open(ambari_configuration_path, 'rb') as fp:
    spec = importlib.util.spec_from_file_location('ambari_configuration', ambari_configuration_path)
    ambari_configuration = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(ambari_configuration)

  stack_advisor_path = os.path.join(resources_path, 'stacks/stack_advisor.py')
  with open(stack_advisor_path, 'rb') as fp:
    spec = importlib.util.spec_from_file_location('stack_advisor', stack_advisor_path)
    stack_advisor = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(stack_advisor)

  kafka_service_advisor_path = os.path.join(
    resources_path, 'stacks/ODP/1.0/services/KAFKA/service_advisor.py')
  with open(kafka_service_advisor_path, 'rb') as fp:
    service_advisor_impl = imp.load_module(
      'odp_kafka_service_advisor_impl', fp, kafka_service_advisor_path, ('.py', 'rb', imp.PY_SOURCE))

  def test_recommendations_add_metrics_reporter(self):
    configurations = {}
    services = {
      "services": [
        {
          "StackServices": {
            "service_name": "KAFKA"
          }
        },
        {
          "StackServices": {
            "service_name": "AMBARI_METRICS"
          }
        }
      ],
      "configurations": {
        "kafka-env": {
          "properties": {
            "kafka_user": "kafka"
          }
        },
        "kafka-broker": {
          "properties": {}
        }
      }
    }

    recommender = self.service_advisor_impl.KafkaRecommender()
    recommender.recommendKAFKAConfigurationsFromHDP23(configurations, {}, services, {})

    broker_properties = configurations["kafka-broker"]["properties"]
    self.assertEqual(
      "org.apache.hadoop.metrics2.sink.kafka.KafkaTimelineMetricsReporter",
      broker_properties["metric.reporters"])

  def test_recommendations_append_metrics_reporter(self):
    configurations = {
      "kafka-broker": {
        "properties": {
          "metric.reporters": "io.confluent.metrics.reporter.ConfluentMetricsReporter"
        }
      }
    }
    services = {
      "services": [
        {
          "StackServices": {
            "service_name": "KAFKA"
          }
        },
        {
          "StackServices": {
            "service_name": "AMBARI_METRICS"
          }
        }
      ],
      "configurations": {
        "kafka-env": {
          "properties": {
            "kafka_user": "kafka"
          }
        },
        "kafka-broker": {
          "properties": {
            "metric.reporters": "io.confluent.metrics.reporter.ConfluentMetricsReporter"
          }
        }
      }
    }

    recommender = self.service_advisor_impl.KafkaRecommender()
    recommender.recommendKAFKAConfigurationsFromHDP23(configurations, {}, services, {})

    broker_properties = configurations["kafka-broker"]["properties"]
    self.assertEqual(
      "io.confluent.metrics.reporter.ConfluentMetricsReporter,"
      "org.apache.hadoop.metrics2.sink.kafka.KafkaTimelineMetricsReporter",
      broker_properties["metric.reporters"])

  def test_recommendations_do_not_duplicate_metrics_reporter(self):
    configurations = {
      "kafka-broker": {
        "properties": {
          "metric.reporters": (
            "io.confluent.metrics.reporter.ConfluentMetricsReporter,"
            "org.apache.hadoop.metrics2.sink.kafka.KafkaTimelineMetricsReporter"
          )
        }
      }
    }
    services = {
      "services": [
        {
          "StackServices": {
            "service_name": "KAFKA"
          }
        },
        {
          "StackServices": {
            "service_name": "AMBARI_METRICS"
          }
        }
      ],
      "configurations": {
        "kafka-env": {
          "properties": {
            "kafka_user": "kafka"
          }
        },
        "kafka-broker": {
          "properties": configurations["kafka-broker"]["properties"]
        }
      }
    }

    recommender = self.service_advisor_impl.KafkaRecommender()
    recommender.recommendKAFKAConfigurationsFromHDP23(configurations, {}, services, {})

    broker_properties = configurations["kafka-broker"]["properties"]
    self.assertEqual(
      "io.confluent.metrics.reporter.ConfluentMetricsReporter,"
      "org.apache.hadoop.metrics2.sink.kafka.KafkaTimelineMetricsReporter",
      broker_properties["metric.reporters"])

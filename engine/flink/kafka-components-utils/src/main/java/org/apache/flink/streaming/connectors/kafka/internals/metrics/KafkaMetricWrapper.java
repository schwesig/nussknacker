/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.connectors.kafka.internals.metrics;

import org.apache.flink.annotation.Internal;
import org.apache.flink.metrics.Gauge;

/** Gauge for getting the current value of a Kafka metric. */
@Internal
// delete after update Flink to 1.15.3 where this fix is delivered
// https://issues.apache.org/jira/browse/FLINK-28488
public class KafkaMetricWrapper implements Gauge<Double> {
    private final org.apache.kafka.common.Metric kafkaMetric;

    public KafkaMetricWrapper(org.apache.kafka.common.Metric metric) {
        this.kafkaMetric = metric;
    }

    @Override
    public Double getValue() {

        final Object metricValue = kafkaMetric.metricValue();
        // Previously KafkaMetric supported KafkaMetric#value that always returned a Double value.
        // Since this method has been deprecated and is removed in future releases we have to
        // manually check if the returned value is Double. Internally, KafkaMetric#value also
        // returned 0.0 for all not "measurable" values, so we restored the original behavior.
        return metricValue instanceof Double ? (Double) metricValue : 0.0;
    }
}

package com.dataartisans;

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer09;
import org.apache.flink.streaming.connectors.twitter.TwitterSource;
import org.apache.flink.streaming.util.serialization.SimpleStringSchema;
import org.apache.flink.util.Collector;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;



/**
 * Ingest data from Twitter into Kafka
 */
public class TwitterIntoKafka {

	public static void main(String[] args) throws Exception {
		// set up the streaming execution environment
		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();

		ParameterTool params = ParameterTool.fromPropertiesFile(args[0]);
		DataStream<String> twitterStreamString = env.addSource(new TwitterSource(params.getProperties()));
		DataStream<String> filteredStream = twitterStreamString.flatMap(new ParseJson());
		filteredStream.flatMap(new ThroughputLogger(100L)).setParallelism(1);

		filteredStream.addSink(new FlinkKafkaProducer09<>("twitter", new SimpleStringSchema(), params.getProperties()));

		// execute program
		env.execute("Ingest data from Twitter to Kafka");
	}

	public static class ParseJson implements FlatMapFunction<String, String> {
		private static final long serialVersionUID = 1L;

		private transient ObjectMapper jsonParser;
		/**
		 * Select the language from the incoming JSON text
		 */
		@Override
		public void flatMap(String value, Collector<String> out) throws Exception {
			if(jsonParser == null) {
				jsonParser = new ObjectMapper();
			}
			JsonNode jsonNode = jsonParser.readValue(value, JsonNode.class);
			//boolean isEnglish = jsonNode.has("user") && jsonNode.get("user").has("lang") && jsonNode.get("user").get("lang").getValueAsText().equals("en");
			boolean hasText = jsonNode.has("text");
			if (hasText) {
				out.collect(value);
			} else {
				System.out.println("Doesn't have text: " + value);
			}
		}
	}
}

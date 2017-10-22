/**
 * Copyright © 2016 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.samples.spark;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.streaming.Duration;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;
import org.eclipse.paho.client.mqttv3.*;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import scala.Tuple2;

import java.util.*;


public class SparkKafkaAssetStreamingDemoMain {

    // Kafka brokers URL for Spark Streaming to connect and fetched messages from.
    private static final String KAFKA_BROKER_LIST = "localhost:9092";
    // URL of ThingsBoard REST endpoint
    private static final String THINGSBOARD_REST_ENDPOINT = "http://localhost:8080";
    // ThingsBoard User login
    private static final String USERNAME = "tenant@thingsboard.org";
    // ThingsBoard User password
    private static final String PASSWORD = "tenant";
    // Asset ID to post the aggregated data inot
    private static final String ASSET_ID = "ae19d9e0-b73c-11e7-96ba-31d2955bf1fb";
    // Asset Publish Telemetry Endpoint
    private static final String ASSET_PUBLISH_TELEMETRY_ENDPOINT = THINGSBOARD_REST_ENDPOINT + "/api/plugins/telemetry/ASSET/" + ASSET_ID + "/timeseries/values";
    // Time interval in milliseconds of Spark Streaming Job, 10 seconds by default.
    private static final int STREAM_WINDOW_MILLISECONDS = 10000; // 10 seconds
    // Kafka telemetry topic to subscribe to. This should match to the topic in the rule action.
    private static final Collection<String> TOPICS = Arrays.asList("weather-stations-data");
    // The application name
    public static final String APP_NAME = "Kafka Spark Streaming App";


    // Misc Kafka client properties
    private static Map<String, Object> getKafkaParams() {
        Map<String, Object> kafkaParams = new HashMap<>();
        kafkaParams.put("bootstrap.servers", KAFKA_BROKER_LIST);
        kafkaParams.put("key.deserializer", StringDeserializer.class);
        kafkaParams.put("value.deserializer", StringDeserializer.class);
        kafkaParams.put("group.id", "DEFAULT_GROUP_ID");
        kafkaParams.put("auto.offset.reset", "latest");
        kafkaParams.put("enable.auto.commit", false);
        return kafkaParams;
    }

    public static void main(String[] args) throws Exception {
        new StreamRunner().start();
    }

    @Slf4j
    private static class StreamRunner {

        private final RestTemplate restTemplate;
        private String token;

        StreamRunner() throws MqttException {
            restTemplate = new RestTemplate();
        }

        void start() throws Exception {
            SparkConf conf = new SparkConf().setAppName(APP_NAME).setMaster("local");

            try (JavaStreamingContext ssc = new JavaStreamingContext(conf, new Duration(STREAM_WINDOW_MILLISECONDS))) {

                loginRestTemplate();

                JavaInputDStream<ConsumerRecord<String, String>> stream =
                        KafkaUtils.createDirectStream(
                                ssc,
                                LocationStrategies.PreferConsistent(),
                                ConsumerStrategies.<String, String>Subscribe(TOPICS, getKafkaParams())
                        );

                stream.foreachRDD(rdd ->
                {
                    // Map incoming JSON to WindSpeedData objects

                    JavaRDD<WindSpeedData> windRdd = rdd.map(new WeatherStationDataMapper());
                    // Map WindSpeedData objects by GeoZone
                    JavaPairRDD<String, AvgWindSpeedData> windByZoneRdd = windRdd.mapToPair(d -> new Tuple2<>(d.getGeoZone(), new AvgWindSpeedData(d.getWindSpeed())));
                    // Reduce all data volume by GeoZone key
                    windByZoneRdd = windByZoneRdd.reduceByKey((a, b) -> AvgWindSpeedData.sum(a, b));
                    // Map <GeoZone, AvgWindSpeedData> back to WindSpeedData
                    List<WindSpeedData> aggData = windByZoneRdd.map(t -> new WindSpeedData(t._1, t._2.getAvgValue())).collect();
                    // Push aggregated data to ThingsBoard using Gateway MQTT API
                    publishTelemetryToThingsBoardAsset(aggData);
                });

                ssc.start();
                ssc.awaitTermination();
            }
        }

        private void loginRestTemplate() {
            Map<String, String> loginRequest = new HashMap<>();
            loginRequest.put("username", USERNAME);
            loginRequest.put("password", PASSWORD);
            ResponseEntity<JsonNode> tokenInfo = restTemplate.postForEntity(THINGSBOARD_REST_ENDPOINT + "/api/auth/login", loginRequest, JsonNode.class);
            this.token = tokenInfo.getBody().get("token").asText();
        }


        private void publishTelemetryToThingsBoardAsset(List<WindSpeedData> aggData) throws Exception {
            HttpHeaders requestHeaders = new HttpHeaders();
            requestHeaders.add("X-Authorization", "Bearer " + token);

            if (!aggData.isEmpty()) {
                for (WindSpeedData d : aggData) {
                    HttpEntity<?> httpEntity = new HttpEntity<Object>(d, requestHeaders);
                    ResponseEntity<Void> result = restTemplate.postForEntity(ASSET_PUBLISH_TELEMETRY_ENDPOINT,
                            httpEntity, Void.class);
                }
            }
        }

        private IMqttActionListener getCallback() {
            return new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    log.info("Telemetry data updated!");
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    log.error("Telemetry data update failed!", exception);
                }
            };
        }

        private static class WeatherStationDataMapper implements Function<ConsumerRecord<String, String>, WindSpeedData> {
            private static final ObjectMapper mapper = new ObjectMapper();

            @Override
            public WindSpeedData call(ConsumerRecord<String, String> record) throws Exception {
                return mapper.readValue(record.value(), WindSpeedData.class);
            }
        }
    }

    private static String toConnectJson(String geoZone) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();
        json.put("device", geoZone);
        return mapper.writeValueAsString(json);
    }

    private static String toDataJson(List<WindSpeedData> aggData) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode json = mapper.createObjectNode();
        long ts = System.currentTimeMillis();
        aggData.forEach(v -> {
            ObjectNode zoneNode = json.putArray(v.getGeoZone()).addObject();
            zoneNode.put("ts", ts);
            ObjectNode values = zoneNode.putObject("values");
            values.put("windSpeed", v.getWindSpeed());
        });
        return mapper.writeValueAsString(json);
    }
}
/*
 * Copyright (c) 2008-2020, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.jet.contrib.mqtt;

import com.hazelcast.jet.Job;
import com.hazelcast.jet.SimpleTestInClusterSupport;
import com.hazelcast.jet.contrib.mqtt.impl.ConcurrentMemoryPersistence;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sink;
import com.hazelcast.jet.pipeline.test.TestSources;
import com.hazelcast.jet.retry.RetryStrategies;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.ToxiproxyContainer.ContainerProxy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hazelcast.internal.util.UuidUtil.newUnsecureUuidString;
import static java.util.stream.Collectors.toList;
import static java.util.stream.IntStream.range;
import static org.junit.Assert.assertTrue;

public class MqttSinkTest extends SimpleTestInClusterSupport {

    private MosquittoContainer mosquitto;
    private MqttClient client;
    private Job job;

    @BeforeClass
    public static void beforeClass() {
        initialize(2, null);
    }

    @After
    public void teardown() throws MqttException {
        if (job != null) {
            job.cancel();
        }
        if (client != null) {
            try {
                client.disconnect();
            } catch (MqttException exception) {
                logger.warning("Exception when disconnecting", exception);
            }
            client.close();
        }
        if (mosquitto != null) {
            mosquitto.stop();
        }
    }

    @Test
    public void test_retryStrategy() throws MqttException {
        try (
                Network network = Network.newNetwork();
                ToxiproxyContainer toxiproxy = initToxiproxy(network);
        ) {
            mosquitto = new MosquittoContainer().withNetwork(network);
            mosquitto.start();
            ContainerProxy proxy = toxiproxy.getProxy(mosquitto, MosquittoContainer.PORT);
            String broker = MosquittoContainer.connectionString(proxy);

            ConcurrentHashMap<Integer, String> map = new ConcurrentHashMap<>();
            client = mqttClient(broker);
            client.subscribe("retry", 2, (topic, message) -> map.put(payload(message), topic));

            int itemCount = 1000;

            Pipeline p = Pipeline.create();
            Sink<Integer> sink =
                    MqttSinks.builder()
                            .broker(broker)
                            .topic("retry")
                            .connectOptionsFn(() -> {
                                MqttConnectOptions options = new MqttConnectOptions();
                                options.setAutomaticReconnect(true);
                                options.setCleanSession(false);
                                return options;
                            })
                            .retryStrategy(RetryStrategies.indefinitely(1000))
                            .messageFn(MqttSinkTest::message)
                            .build();

            p.readFrom(TestSources.items(range(0, itemCount).boxed().collect(toList())))
                    .rebalance()
                    .writeTo(sink);

            job = instance().newJob(p);

            assertTrueEventually(() -> assertTrue(map.size() > itemCount / 2));

            proxy.setConnectionCut(true);
            sleepSeconds(1);
            proxy.setConnectionCut(false);

            assertTrueEventually(() -> assertTrue(client.isConnected()));
            client.subscribe("retry", 2, (topic, message) -> map.put(payload(message), topic));

            assertEqualsEventually(map::size, itemCount);
        }
    }

    @Test
    public void test() throws MqttException {
        mosquitto = new MosquittoContainer();
        mosquitto.start();
        String broker = mosquitto.connectionString();
        client = new MqttClient(broker, newUnsecureUuidString(), new ConcurrentMemoryPersistence());
        client.connect();

        int itemCount = 100;
        AtomicInteger counter = new AtomicInteger();
        client.subscribe("topic", 0, (topic, message) -> counter.incrementAndGet());

        Pipeline p = Pipeline.create();

        Sink<Integer> sink =
                MqttSinks.builder()
                        .broker(broker)
                        .topic("topic")
                        .messageFn(MqttSinkTest::message)
                        .build();

        p.readFrom(TestSources.items(range(0, itemCount).boxed().collect(toList())))
                .rebalance()
                .writeTo(sink);

        instance().newJob(p).join();

        assertEqualsEventually(itemCount, counter);
    }

    private MqttClient mqttClient(String broker) throws MqttException {
        MqttClient client = new MqttClient(broker, newUnsecureUuidString(), new ConcurrentMemoryPersistence());
        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setAutomaticReconnect(true);
        connectOptions.setCleanSession(false);
        client.connect(connectOptions);
        return client;
    }

    private static byte[] intToByteArray(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value};
    }

    private static int byteArrayToInt(byte[] data) {
        int val = 0;
        for (int i = 0; i < 4; i++) {
            val = val << 8;
            val = val | (data[i] & 0xFF);
        }
        return val;
    }

    private static MqttMessage message(int value) {
        MqttMessage message = new MqttMessage(intToByteArray(value));
        message.setQos(2);
        return message;
    }

    private static int payload(MqttMessage message) {
        return byteArrayToInt(message.getPayload());
    }

    private static ToxiproxyContainer initToxiproxy(Network network) {
        ToxiproxyContainer toxiproxy = new ToxiproxyContainer().withNetwork(network);
        toxiproxy.start();
        return toxiproxy;
    }

}

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kafka.streams.kstream.internals;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.utils.Utils;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.Predicate;
import org.apache.kafka.streams.kstream.ValueMapper;
import org.apache.kafka.test.KStreamTestDriver;
import org.apache.kafka.test.MockProcessorSupplier;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class KTableMapValuesImplTest {

    private final Serializer<String> strSerializer = new StringSerializer();
    private final Deserializer<String> strDeserializer = new StringDeserializer();

    @Test
    public void testKTable() {
        final KStreamBuilder builder = new KStreamBuilder();

        String topic1 = "topic1";

        KTable<String, String> table1 = builder.table(strSerializer, strSerializer, strDeserializer, strDeserializer, topic1);
        KTable<String, Integer> table2 = table1.mapValues(new ValueMapper<String, Integer>() {
            @Override
            public Integer apply(String value) {
                return new Integer(value);
            }
        });

        MockProcessorSupplier<String, Integer> proc2 = new MockProcessorSupplier<>();
        table2.toStream().process(proc2);

        KStreamTestDriver driver = new KStreamTestDriver(builder);

        driver.process(topic1, "A", "01");
        driver.process(topic1, "B", "02");
        driver.process(topic1, "C", "03");
        driver.process(topic1, "D", "04");

        assertEquals(Utils.mkList("A:1", "B:2", "C:3", "D:4"), proc2.processed);
    }

    @Test
    public void testValueGetter() throws IOException {
        File stateDir = Files.createTempDirectory("test").toFile();
        try {
            final Serializer<String> serializer = new StringSerializer();
            final Deserializer<String> deserializer = new StringDeserializer();
            final KStreamBuilder builder = new KStreamBuilder();

            String topic1 = "topic1";
            String topic2 = "topic2";

            KTableImpl<String, String, String> table1 =
                    (KTableImpl<String, String, String>) builder.table(serializer, serializer, deserializer, deserializer, topic1);
            KTableImpl<String, String, Integer> table2 = (KTableImpl<String, String, Integer>) table1.mapValues(
                    new ValueMapper<String, Integer>() {
                        @Override
                        public Integer apply(String value) {
                            return new Integer(value);
                        }
                    });
            KTableImpl<String, Integer, Integer> table3 = (KTableImpl<String, Integer, Integer>) table2.filter(
                    new Predicate<String, Integer>() {
                        @Override
                        public boolean test(String key, Integer value) {
                            return (value % 2) == 0;
                        }
                    });
            KTableImpl<String, String, String> table4 = (KTableImpl<String, String, String>)
                    table1.through(topic2, serializer, serializer, deserializer, deserializer);

            KTableValueGetterSupplier<String, String> getterSupplier1 = table1.valueGetterSupplier();
            KTableValueGetterSupplier<String, Integer> getterSupplier2 = table2.valueGetterSupplier();
            KTableValueGetterSupplier<String, Integer> getterSupplier3 = table3.valueGetterSupplier();
            KTableValueGetterSupplier<String, String> getterSupplier4 = table4.valueGetterSupplier();

            KStreamTestDriver driver = new KStreamTestDriver(builder, stateDir, null, null, null, null);

            KTableValueGetter<String, String> getter1 = getterSupplier1.get();
            getter1.init(driver.context());
            KTableValueGetter<String, Integer> getter2 = getterSupplier2.get();
            getter2.init(driver.context());
            KTableValueGetter<String, Integer> getter3 = getterSupplier3.get();
            getter3.init(driver.context());
            KTableValueGetter<String, String> getter4 = getterSupplier4.get();
            getter4.init(driver.context());

            driver.process(topic1, "A", "01");
            driver.process(topic1, "B", "01");
            driver.process(topic1, "C", "01");

            assertEquals("01", getter1.get("A"));
            assertEquals("01", getter1.get("B"));
            assertEquals("01", getter1.get("C"));

            assertEquals(new Integer(1), getter2.get("A"));
            assertEquals(new Integer(1), getter2.get("B"));
            assertEquals(new Integer(1), getter2.get("C"));

            assertNull(getter3.get("A"));
            assertNull(getter3.get("B"));
            assertNull(getter3.get("C"));

            assertEquals("01", getter4.get("A"));
            assertEquals("01", getter4.get("B"));
            assertEquals("01", getter4.get("C"));

            driver.process(topic1, "A", "02");
            driver.process(topic1, "B", "02");

            assertEquals("02", getter1.get("A"));
            assertEquals("02", getter1.get("B"));
            assertEquals("01", getter1.get("C"));

            assertEquals(new Integer(2), getter2.get("A"));
            assertEquals(new Integer(2), getter2.get("B"));
            assertEquals(new Integer(1), getter2.get("C"));

            assertEquals(new Integer(2), getter3.get("A"));
            assertEquals(new Integer(2), getter3.get("B"));
            assertNull(getter3.get("C"));

            assertEquals("02", getter4.get("A"));
            assertEquals("02", getter4.get("B"));
            assertEquals("01", getter4.get("C"));

            driver.process(topic1, "A", "03");

            assertEquals("03", getter1.get("A"));
            assertEquals("02", getter1.get("B"));
            assertEquals("01", getter1.get("C"));

            assertEquals(new Integer(3), getter2.get("A"));
            assertEquals(new Integer(2), getter2.get("B"));
            assertEquals(new Integer(1), getter2.get("C"));

            assertNull(getter3.get("A"));
            assertEquals(new Integer(2), getter3.get("B"));
            assertNull(getter3.get("C"));

            assertEquals("03", getter4.get("A"));
            assertEquals("02", getter4.get("B"));
            assertEquals("01", getter4.get("C"));

            driver.process(topic1, "A", null);

            assertNull(getter1.get("A"));
            assertEquals("02", getter1.get("B"));
            assertEquals("01", getter1.get("C"));

            assertNull(getter2.get("A"));
            assertEquals(new Integer(2), getter2.get("B"));
            assertEquals(new Integer(1), getter2.get("C"));

            assertNull(getter3.get("A"));
            assertEquals(new Integer(2), getter3.get("B"));
            assertNull(getter3.get("C"));

            assertNull(getter4.get("A"));
            assertEquals("02", getter4.get("B"));
            assertEquals("01", getter4.get("C"));

        } finally {
            Utils.delete(stateDir);
        }
    }

    @Test
    public void testNotSendingOldValue() throws IOException {
        File stateDir = Files.createTempDirectory("test").toFile();
        try {
            final Serializer<String> serializer = new StringSerializer();
            final Deserializer<String> deserializer = new StringDeserializer();
            final KStreamBuilder builder = new KStreamBuilder();

            String topic1 = "topic1";

            KTableImpl<String, String, String> table1 =
                    (KTableImpl<String, String, String>) builder.table(serializer, serializer, deserializer, deserializer, topic1);
            KTableImpl<String, String, Integer> table2 = (KTableImpl<String, String, Integer>) table1.mapValues(
                    new ValueMapper<String, Integer>() {
                        @Override
                        public Integer apply(String value) {
                            return new Integer(value);
                        }
                    });

            MockProcessorSupplier<String, Integer> proc = new MockProcessorSupplier<>();

            builder.addProcessor("proc", proc, table2.name);

            KStreamTestDriver driver = new KStreamTestDriver(builder, stateDir, null, null, null, null);

            assertFalse(table1.sendingOldValueEnabled());
            assertFalse(table2.sendingOldValueEnabled());

            driver.process(topic1, "A", "01");
            driver.process(topic1, "B", "01");
            driver.process(topic1, "C", "01");

            proc.checkAndClearResult("A:(1<-null)", "B:(1<-null)", "C:(1<-null)");

            driver.process(topic1, "A", "02");
            driver.process(topic1, "B", "02");

            proc.checkAndClearResult("A:(2<-null)", "B:(2<-null)");

            driver.process(topic1, "A", "03");

            proc.checkAndClearResult("A:(3<-null)");

            driver.process(topic1, "A", null);

            proc.checkAndClearResult("A:(null<-null)");

        } finally {
            Utils.delete(stateDir);
        }
    }

    @Test
    public void testSendingOldValue() throws IOException {
        File stateDir = Files.createTempDirectory("test").toFile();
        try {
            final Serializer<String> serializer = new StringSerializer();
            final Deserializer<String> deserializer = new StringDeserializer();
            final KStreamBuilder builder = new KStreamBuilder();

            String topic1 = "topic1";

            KTableImpl<String, String, String> table1 =
                    (KTableImpl<String, String, String>) builder.table(serializer, serializer, deserializer, deserializer, topic1);
            KTableImpl<String, String, Integer> table2 = (KTableImpl<String, String, Integer>) table1.mapValues(
                    new ValueMapper<String, Integer>() {
                        @Override
                        public Integer apply(String value) {
                            return new Integer(value);
                        }
                    });

            table2.enableSendingOldValues();

            MockProcessorSupplier<String, Integer> proc = new MockProcessorSupplier<>();

            builder.addProcessor("proc", proc, table2.name);

            KStreamTestDriver driver = new KStreamTestDriver(builder, stateDir, null, null, null, null);

            assertTrue(table1.sendingOldValueEnabled());
            assertTrue(table2.sendingOldValueEnabled());

            driver.process(topic1, "A", "01");
            driver.process(topic1, "B", "01");
            driver.process(topic1, "C", "01");

            proc.checkAndClearResult("A:(1<-null)", "B:(1<-null)", "C:(1<-null)");

            driver.process(topic1, "A", "02");
            driver.process(topic1, "B", "02");

            proc.checkAndClearResult("A:(2<-1)", "B:(2<-1)");

            driver.process(topic1, "A", "03");

            proc.checkAndClearResult("A:(3<-2)");

            driver.process(topic1, "A", null);

            proc.checkAndClearResult("A:(null<-3)");

        } finally {
            Utils.delete(stateDir);
        }
    }

}

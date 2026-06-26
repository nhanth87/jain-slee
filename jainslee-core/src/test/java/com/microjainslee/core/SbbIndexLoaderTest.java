/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.core;

import org.junit.Test;

import java.io.IOException;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SbbIndexLoaderTest {

    @Test
    public void parse_readsAllIndexSections() {
        Properties props = new Properties();
        props.setProperty("sbb.0.class", "com.example.MySbb");
        props.setProperty("sbb.0.name", "MySbb");
        props.setProperty("sbb.0.vendor", "com.example");
        props.setProperty("sbb.0.version", "1.0");
        props.setProperty("eventType.0.class", "com.example.MyEvent");
        props.setProperty("eventType.0.name", "MyEvent");
        props.setProperty("du.0.class", "com.example.MyDU");
        props.setProperty("du.0.name", "MyDU");
        props.setProperty("du.0.sbbs", "com.example.MySbb");
        props.setProperty("du.0.ras", "com.example.MyRa");

        SbbIndexLoader.SbbIndex index = SbbIndexLoader.parse(props);

        assertEquals(1, index.getSbbs().size());
        assertEquals("com.example.MySbb", index.getSbbs().get(0).getClassName());
        assertEquals("MySbb", index.getSbbs().get(0).getName());

        assertEquals(1, index.getEventTypes().size());
        assertEquals("MyEvent", index.getEventTypes().get(0).getName());

        assertEquals(1, index.getDeployableUnits().size());
        assertEquals("com.example.MySbb", index.getDeployableUnits().get(0).getSbbs().get(0));
        assertEquals("com.example.MyRa", index.getDeployableUnits().get(0).getRas().get(0));
    }

    @Test
    public void load_returnsEmptyIndexWhenResourceMissing() throws IOException {
        ClassLoader loader = new ClassLoader(SbbIndexLoaderTest.class.getClassLoader()) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                return null;
            }
        };
        SbbIndexLoader.SbbIndex index = SbbIndexLoader.load(loader);
        assertTrue(index.isEmpty());
    }

    @Test
    public void load_readsClasspathResource() throws IOException {
        SbbIndexLoader.SbbIndex index = SbbIndexLoader.load(
                SbbIndexLoaderTest.class.getClassLoader());
        assertEquals(1, index.getSbbs().size());
        assertEquals("FixtureSbb", index.getSbbs().get(0).getName());
        assertEquals(1, index.getDeployableUnits().size());
        assertEquals("FixtureDU", index.getDeployableUnits().get(0).getName());
    }
}

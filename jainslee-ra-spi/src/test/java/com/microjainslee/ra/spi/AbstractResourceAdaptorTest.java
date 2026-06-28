/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.spi;

import com.microjainslee.api.ActivityContextHandle;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.ResourceAdaptor;
import com.microjainslee.api.ResourceAdaptorContext;
import com.microjainslee.api.SleeEndpointPort;
import com.microjainslee.api.SleeEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class AbstractResourceAdaptorTest {

    @Test
    public void publishFiresEventThroughSleeEndpointPort() {
        CapturingSleeEndpointPort endpoint = new CapturingSleeEndpointPort();
        ResourceAdaptorContext context = new StubResourceAdaptorContext(endpoint);

        TestResourceAdaptor ra = new TestResourceAdaptor();
        ra.setResourceAdaptorContext(context);

        SleeEvent event = new SleeEvent() { };
        ra.publishForTest("session-42", event);

        assertEquals(1, endpoint.fireEventCount);
        assertEquals("session-42", endpoint.lastHandle.getId());
        assertSame(event, endpoint.lastEvent);
    }

    private static final class TestResourceAdaptor extends AbstractResourceAdaptor {
        @Override public void raConfigure() { }
        @Override public void raActive() { }
        @Override public void raStopping() { }
        @Override public void raInactive() { }

        void publishForTest(String activityId, SleeEvent event) {
            publish(activityId, event);
        }
    }

    private static final class StubResourceAdaptorContext implements ResourceAdaptorContext {
        private final SleeEndpointPort endpoint;

        StubResourceAdaptorContext(SleeEndpointPort endpoint) {
            this.endpoint = endpoint;
        }

        @Override
        public void setResourceAdaptor(ResourceAdaptor ra) {
        }

        @Override
        public ActivityContextHandle createActivityContextHandle(Object activity) {
            return null;
        }

        @Override
        public ActivityContextHandle getActivityContextHandle(Object activity) {
            return null;
        }

        @Override
        public SleeEndpointPort getSleeEndpointPort() {
            return endpoint;
        }
    }

    private static final class CapturingSleeEndpointPort implements SleeEndpointPort {
        int fireEventCount;
        ActivityContextHandle lastHandle;
        SleeEvent lastEvent;

        @Override
        public ActivityContextInterface startActivity(ActivityContextHandle handle, Object activity) {
            return null;
        }

        @Override
        public void endActivity(ActivityContextHandle handle) {
        }

        @Override
        public void fireEvent(ActivityContextHandle handle, SleeEvent event) {
            fireEventCount++;
            lastHandle = handle;
            lastEvent = event;
        }
    }
}

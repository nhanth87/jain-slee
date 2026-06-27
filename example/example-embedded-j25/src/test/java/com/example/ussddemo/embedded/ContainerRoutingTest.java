package com.example.ussddemo.embedded;

import com.example.ussddemo.events.HttpUssdBeginEvent;
import com.example.ussddemo.sbbs.HttpServerSbb;
import com.microjainslee.api.ActivityContextInterface;
import com.microjainslee.api.Sbb;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.api.SleeEventHandler;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.SimpleSbbLocalObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ContainerRoutingTest {

    @Test
    public void httpServerSbbCanBeRegisteredAndAttached() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            HttpServerSbb httpSbb = new HttpServerSbb();
            SimpleSbbLocalObject lo = container.registerSbb("HttpServer/test", httpSbb);
            var aci = container.createActivityContext("test-aci");
            container.attach("test-aci", lo);
            assertEquals(1, aci.getAttachedSbbs().size());
            Thread.sleep(200);
            assertFalse("SBB must not be removed before event", lo.isRemoved());
            assertTrue(lo.getSbb() instanceof SleeEventHandler);
        } finally {
            container.stop();
        }
    }

    @Test
    public void httpUssdBeginEventIsDelivered() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            SimpleSbbLocalObject lo = container.registerSbb("http-test", new CountingSbb());
            var aci = container.createActivityContext("http-test-aci");
            container.attach("http-test-aci", lo);
            container.routeEvent(new HttpUssdBeginEvent("s1", "251911000001", "*123#", null), aci);
            Thread.sleep(500);
            assertEquals(1, ((CountingSbb) lo.getSbb()).events.get());
        } finally {
            container.stop();
        }
    }

    @Test
    public void registerSbbReceivesRoutedEvent() throws Exception {
        MicroSleeContainer container = new MicroSleeContainer();
        container.start();
        try {
            SimpleSbbLocalObject lo = container.registerSbb("counter", new CountingSbb());
            var aci = container.createActivityContext("counter-aci");
            container.attach("counter-aci", lo);
            container.routeEvent(new SleeEvent() { }, aci);
            Thread.sleep(200);
            assertEquals(1, ((CountingSbb) lo.getSbb()).events.get());
        } finally {
            container.stop();
        }
    }

    private static final class CountingSbb implements Sbb, SleeEventHandler {
        final AtomicInteger events = new AtomicInteger();

        @Override
        public void onEvent(SleeEvent event, ActivityContextInterface aci) {
            events.incrementAndGet();
        }

        @Override public void sbbCreate() { }
        @Override public void sbbActivate() { }
        @Override public void sbbPassivate() { }
        @Override public void sbbRemove() { }
    }
}

/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.api.SleeRequest;
import com.microjainslee.ms.api.SleeResponse;
import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.SleeServiceHandler;
import com.microjainslee.ms.api.annotation.SleeService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeServiceHandlerRegistryTest {

    @SleeService(name = "alpha")
    static final class AlphaMarker {}

    @SleeService(name = "beta")
    static final class BetaMarker {}

    @SleeService(name = "selfsvc")
    public static final class SelfHandlingService implements SleeServiceHandler {
        @Override
        public SleeResponse invoke(SleeRequest req) {
            return SleeResponse.ok("self".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static SleeServiceDescriptor desc(Class<?> type) {
        return SleeServiceDescriptor.fromAnnotation(type);
    }

    private static String call(SleeServiceHandler handler, String op) throws Exception {
        return new String(
                handler.invoke(new SleeRequest(op, new byte[0])).payload(),
                StandardCharsets.UTF_8);
    }

    @Test
    void oneHandlerServesManyServices() throws Exception {
        SleeServiceHandler shared = req -> SleeResponse.ok(
                ("shared:" + req.operation()).getBytes(StandardCharsets.UTF_8));
        SleeServiceHandlerRegistry registry = new SleeServiceHandlerRegistry()
                .register(List.of("alpha", "beta"), shared);

        assertEquals("shared:ping", call(registry.resolve(desc(AlphaMarker.class)), "ping"));
        assertEquals("shared:ping", call(registry.resolve(desc(BetaMarker.class)), "ping"));
    }

    @Test
    void manyHandlersOneServiceRoutedByOperation() throws Exception {
        SleeServiceHandlerRegistry registry = new SleeServiceHandlerRegistry()
                .register("alpha", List.of("ping"), 100,
                        req -> SleeResponse.ok("pinger".getBytes(StandardCharsets.UTF_8)))
                .register("alpha", List.of("echo"), 100,
                        req -> SleeResponse.ok("echoer".getBytes(StandardCharsets.UTF_8)))
                .register("alpha",
                        req -> SleeResponse.ok("fallback".getBytes(StandardCharsets.UTF_8)));

        SleeServiceHandler handler = registry.resolve(desc(AlphaMarker.class));
        assertEquals("pinger", call(handler, "ping"));
        assertEquals("echoer", call(handler, "echo"));
        assertEquals("fallback", call(handler, "anything-else"));
    }

    @Test
    void selfHandlingServiceClassIsDiscovered() throws Exception {
        SleeServiceHandlerRegistry registry = SleeServiceHandlerRegistry.discover(
                List.of(desc(SelfHandlingService.class)));
        assertEquals("self", call(registry.resolve(desc(SelfHandlingService.class)), "x"));
    }

    @Test
    void programmaticBindingBeatsSelfHandler() throws Exception {
        SleeServiceHandlerRegistry registry = SleeServiceHandlerRegistry.discover(
                List.of(desc(SelfHandlingService.class)));
        registry.register("selfsvc",
                req -> SleeResponse.ok("programmatic".getBytes(StandardCharsets.UTF_8)));

        assertEquals("programmatic",
                call(registry.resolve(desc(SelfHandlingService.class)), "x"));
    }

    @Test
    void serviceLoaderProviderContributesToManyServices() throws Exception {
        SleeServiceHandlerRegistry registry = SleeServiceHandlerRegistry.discover(
                List.of(desc(AlphaMarker.class), desc(BetaMarker.class)));

        assertEquals("prov:alpha:ping", call(registry.resolve(desc(AlphaMarker.class)), "ping"));
        assertEquals("prov:beta:ping", call(registry.resolve(desc(BetaMarker.class)), "ping"));
    }

    @Test
    void missingBindingFailsFastAtResolve() {
        SleeServiceHandlerRegistry registry = new SleeServiceHandlerRegistry();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> registry.resolve(desc(AlphaMarker.class)));
        assertTrue(ex.getMessage().contains("alpha"));
    }
}

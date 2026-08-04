/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.jss7.admin;

import com.microjainslee.admin.RaAdminHttpRequest;
import com.microjainslee.admin.RaAdminHttpResponse;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** App hooks (OTA) own apply/start/stop when bound. */
public class Ss7AdminHooksTest {

    @After
    public void tearDown() {
        Ss7AdminBindings.clear();
        Ss7AdminBindings.clearHooks();
        Ss7AdminBindings.setLastConfigJson(null);
    }

    @Test
    public void applyHookRunsAfterSave() {
        AtomicInteger applyCalls = new AtomicInteger();
        Ss7AdminBindings.bindHooks(
                () -> {
                    applyCalls.incrementAndGet();
                    return "ss7=hook-applied";
                },
                () -> "start",
                () -> "stop",
                body -> "{\"ok\":true,\"errors\":[]}",
                () -> "{}",
                body -> {
                    Ss7AdminBindings.setLastConfigJson(body);
                    return "{\"ok\":true}";
                });
        Ss7AdminController ctrl = new Ss7AdminController();
        RaAdminHttpResponse resp = ctrl.apply(
                RaAdminHttpRequest.of("POST", "/api/ra/ra-jss7/apply",
                        "{\"stackName\":\"lab\"}"));
        assertEquals(200, resp.status());
        assertEquals(1, applyCalls.get());
        assertTrue(resp.bodyAsString().contains("\"ok\":true"));
        assertTrue(resp.bodyAsString().contains("hook-applied"));
    }

    @Test
    public void saveHookRejectDoesNotApply() {
        AtomicInteger applyCalls = new AtomicInteger();
        Ss7AdminBindings.bindHooks(
                () -> {
                    applyCalls.incrementAndGet();
                    return "should-not-run";
                },
                null, null, null, null,
                body -> "{\"ok\":false,\"errors\":[\"bad\"]}");
        Ss7AdminController ctrl = new Ss7AdminController();
        RaAdminHttpResponse resp = ctrl.apply(
                RaAdminHttpRequest.of("POST", "/api/ra/ra-jss7/apply", "{}"));
        assertEquals(400, resp.status());
        assertEquals(0, applyCalls.get());
    }
}

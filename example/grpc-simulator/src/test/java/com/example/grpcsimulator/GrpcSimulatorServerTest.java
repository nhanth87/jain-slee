/*
 * micro-jainslee 1.1.0 -- example application (grpc-simulator)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.grpcsimulator;

import com.example.grpcsimulator.proto.MenuResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.ServerSocket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GrpcSimulatorServerTest {

    private GrpcSimulatorServer server;
    private GrpcMenuClient client;

    @Before
    public void setUp() throws Exception {
        int port = freePort();
        server = GrpcSimulatorServer.builder().port(port).build();
        server.start();
        client = new GrpcMenuClient("127.0.0.1", port);
    }

    @After
    public void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void happyPathReturnsBalanceMenu() {
        MenuResponse resp = client.resolveMenu("251911000001", "*123#", "sess-A");
        assertEquals("OK", resp.getStatus());
        assertEquals("sess-A", resp.getSessionId());
        assertNotNull(resp.getMenuText());
        assertTrue("menuText should mention Balance, got: " + resp.getMenuText(),
                resp.getMenuText().contains("Balance"));
    }

    @Test
    public void unknownShortCodeReturnsErr() {
        MenuResponse resp = client.resolveMenu("251911000001", "*999#", "sess-B");
        assertEquals("ERR", resp.getStatus());
        assertEquals("sess-B", resp.getSessionId());
        assertNotNull(resp.getError());
        assertTrue("error should mention *999#, got: " + resp.getError(),
                resp.getError().contains("*999#"));
    }

    @Test
    public void emptySessionIdIsFilledByServer() {
        MenuResponse resp = client.resolveMenu("251911000001", "*123#", "");
        assertEquals("OK", resp.getStatus());
        assertNotNull("server must echo a generated session id when client sends empty",
                resp.getSessionId());
        assertTrue("sessionId should look like a UUID, got: " + resp.getSessionId(),
                resp.getSessionId().length() >= 32);
    }

    @Test
    public void closeIsClean() {
        server.close();
        server.close();
        client.close();
        client.close();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}

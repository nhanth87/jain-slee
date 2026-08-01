/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.collab;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Peer-plane truth: TCP ≠ ready; CER/CEA success = ready; disconnect / Tw expiry = not ready.
 */
public class DiameterPeerTrackerTest {

    private static final String PEER = "tcp-1@127.0.0.1:40000";

    @Test
    public void listenAloneIsNotPeerReady() {
        DiameterPeerTracker t = new DiameterPeerTracker(30_000L);
        assertFalse(t.isPeerConnected());
        assertFalse(t.isPeerReady());
        assertEquals("diameter:no-peer", t.detail());
    }

    @Test
    public void tcpConnectWithoutCerIsConnectedButNotReady() {
        DiameterPeerTracker t = new DiameterPeerTracker(30_000L);
        t.onTcpConnected(PEER);
        assertTrue(t.isPeerConnected());
        assertFalse(t.isPeerReady());
        assertTrue(t.detail().contains("awaiting-cer/cea"));
    }

    @Test
    public void cerRequestAsksForCeaThenMarkMakesReady() {
        DiameterPeerTracker t = new DiameterPeerTracker(0);
        t.onTcpConnected(PEER);
        assertEquals(
                DiameterPeerTracker.BaseAction.ANSWER_CEA,
                t.onInbound(PEER, DiameterPeerTracker.CMD_CAPABILITIES_EXCHANGE, true, -1));
        assertFalse(t.isPeerReady());
        t.markCapabilitiesExchanged(PEER);
        assertTrue(t.isPeerReady());
        assertTrue(t.detail().contains("peer-ready"));
    }

    @Test
    public void successfulCeaAnswerMakesReady() {
        DiameterPeerTracker t = new DiameterPeerTracker(0);
        t.onTcpConnected(PEER);
        assertEquals(
                DiameterPeerTracker.BaseAction.CONSUMED,
                t.onInbound(PEER, DiameterPeerTracker.CMD_CAPABILITIES_EXCHANGE, false,
                        DiameterPeerTracker.RESULT_SUCCESS));
        assertTrue(t.isPeerReady());
    }

    @Test
    public void failedCeaDoesNotMakeReady() {
        DiameterPeerTracker t = new DiameterPeerTracker(0);
        t.onTcpConnected(PEER);
        t.onInbound(PEER, DiameterPeerTracker.CMD_CAPABILITIES_EXCHANGE, false, 5012L);
        assertFalse(t.isPeerReady());
    }

    @Test
    public void dwrRefreshesAndAsksForDwa() {
        DiameterPeerTracker t = new DiameterPeerTracker(0);
        t.onTcpConnected(PEER);
        t.markCapabilitiesExchanged(PEER);
        assertEquals(
                DiameterPeerTracker.BaseAction.ANSWER_DWA,
                t.onInbound(PEER, DiameterPeerTracker.CMD_DEVICE_WATCHDOG, true, -1));
        assertTrue(t.isPeerReady());
    }

    @Test
    public void dprClearsReady() {
        DiameterPeerTracker t = new DiameterPeerTracker(0);
        t.onTcpConnected(PEER);
        t.markCapabilitiesExchanged(PEER);
        assertTrue(t.isPeerReady());
        assertEquals(
                DiameterPeerTracker.BaseAction.ANSWER_DPA,
                t.onInbound(PEER, DiameterPeerTracker.CMD_DISCONNECT_PEER, true, -1));
        assertFalse(t.isPeerReady());
        assertTrue(t.isPeerConnected());
    }

    @Test
    public void tcpDisconnectClearsPeerImmediately() {
        DiameterPeerTracker t = new DiameterPeerTracker(0);
        t.onTcpConnected(PEER);
        t.markCapabilitiesExchanged(PEER);
        assertTrue(t.isPeerReady());
        t.onTcpDisconnected(PEER);
        assertFalse(t.isPeerConnected());
        assertFalse(t.isPeerReady());
    }

    @Test
    public void watchdogExpiryMakesNotReady() {
        DiameterPeerTracker t = new DiameterPeerTracker(1); // 1 ms Tw
        t.onTcpConnected(PEER);
        t.markCapabilitiesExchanged(PEER);
        assertTrue(t.isPeerReady());
        long later = System.nanoTime() + 5_000_000L; // +5ms
        assertFalse(t.isPeerReady(later));
    }
}

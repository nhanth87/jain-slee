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

import com.microjainslee.ra.jss7.Ss7ResourceAdaptor;
import com.microjainslee.ra.jss7.transport.Ss7Stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.Management;
import org.mobicents.protocols.api.Server;
import org.restcomm.protocols.ss7.m3ua.As;
import org.restcomm.protocols.ss7.m3ua.Asp;
import org.restcomm.protocols.ss7.m3ua.AspFactory;
import org.restcomm.protocols.ss7.m3ua.impl.M3UAManagementImpl;

/**
 * Rich SS7 link snapshot for the admin hub — field parity with OTA
 * {@code OtaLinkStatusService.ss7Snapshot}. Green tab input remains
 * {@code routeReady} = {@link Ss7ResourceAdaptor#isM3uaRouteReady()} only.
 */
public final class Ss7LinkStatusSnapshot {

    private Ss7LinkStatusSnapshot() {
    }

    public static Map<String, Object> capture(Ss7ResourceAdaptor ra, String raName) {
        Map<String, Object> m = new LinkedHashMap<>();
        boolean active = ra != null && ra.isActive();
        boolean routeReady = ra != null && ra.isM3uaRouteReady();
        m.put("active", active);
        m.put("routeReady", routeReady);
        m.put("bound", ra != null);
        if (raName != null && !raName.isBlank()) {
            m.put("raName", raName);
        }

        List<Map<String, Object>> servers = new ArrayList<>();
        List<Map<String, Object>> assocs = new ArrayList<>();
        List<Map<String, Object>> asps = new ArrayList<>();
        List<Map<String, Object>> appServers = new ArrayList<>();
        boolean stackStarted = ra != null && ra.isActive()
                && ra.stack() != null && ra.stack().isStarted();

        if (stackStarted) {
            try {
                fillStackRows(ra.stack(), servers, assocs, asps, appServers);
            } catch (RuntimeException ex) {
                m.put("error", ex.getMessage());
            }
        }

        m.put("servers", servers);
        m.put("associations", assocs);
        m.put("asps", asps);
        m.put("applicationServers", appServers);

        boolean anyListen = false;
        for (Map<String, Object> s : servers) {
            if ("LISTEN".equals(String.valueOf(s.get("state")))) {
                anyListen = true;
                break;
            }
        }
        boolean peerConnected = false;
        for (Map<String, Object> a : assocs) {
            if (Boolean.TRUE.equals(a.get("connected")) || Boolean.TRUE.equals(a.get("up"))
                    || "UP".equals(String.valueOf(a.get("state")))) {
                peerConnected = true;
                break;
            }
        }
        boolean asActive = false;
        for (Map<String, Object> as : appServers) {
            if ("ACTIVE".equalsIgnoreCase(String.valueOf(as.get("state")))) {
                asActive = true;
                break;
            }
        }

        String detail = synthesizeSs7Detail(false, routeReady, anyListen, stackStarted,
                peerConnected, asActive, null);
        m.put("listening", anyListen || stackStarted);
        m.put("peerConnected", peerConnected);
        m.put("asActive", asActive);
        m.put("stackStarted", stackStarted);
        m.put("detail", detail);
        m.put("note",
                "active=local lifecycle; routeReady=isM3uaRouteReady(); green tab = routeReady only");
        // ADR 0001 P2 — scrapeable failover / sticky-miss / import-fail counters
        if (ra != null) {
            m.put("failoverMetrics", ra.failoverMetrics().snapshot());
        } else {
            m.put("failoverMetrics", Map.of());
        }
        return m;
    }

    private static void fillStackRows(Ss7Stack stack,
                                      List<Map<String, Object>> servers,
                                      List<Map<String, Object>> assocs,
                                      List<Map<String, Object>> asps,
                                      List<Map<String, Object>> appServers) {
        var under = stack.underlying();
        if (under == null) {
            return;
        }
        Management sctp = under.sctpManagement();
        if (sctp != null) {
            Map<String, Server> serversByName = new LinkedHashMap<>();
            for (Server sv : sctp.getServers()) {
                serversByName.put(sv.getName(), sv);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", sv.getName());
                row.put("local", formatHostPort(sv.getHostAddress(), sv.getHostport()));
                row.put("started", sv.isStarted());
                row.put("channel", String.valueOf(sv.getIpChannelType()));
                row.put("state", sv.isStarted() ? "LISTEN" : "DOWN");
                servers.add(row);
            }
            for (Association a : sctp.getAssociations().values()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", a.getName());
                row.put("type", String.valueOf(a.getAssociationType()));
                row.put("channel", String.valueOf(a.getIpChannelType()));
                row.put("local", associationLocal(a, serversByName));
                row.put("peer", formatHostPort(a.getPeerAddress(), a.getPeerPort()));
                row.put("started", a.isStarted());
                row.put("connected", a.isConnected());
                row.put("up", a.isUp());
                row.put("state", a.isConnected() || a.isUp() ? "UP"
                        : a.isStarted() ? "STARTED" : "DOWN");
                assocs.add(row);
            }
        }
        M3UAManagementImpl m3ua = under.m3uaManagement();
        if (m3ua != null) {
            for (AspFactory factory : m3ua.getAspfactories()) {
                String assocName = factory.getAssociation() != null
                        ? factory.getAssociation().getName() : null;
                boolean assocUp = factory.getAssociation() != null
                        && (factory.getAssociation().isConnected()
                        || factory.getAssociation().isUp());
                List<Asp> aspList = factory.getAspList();
                if (aspList == null || aspList.isEmpty()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", factory.getName());
                    row.put("started", factory.getStatus());
                    row.put("association", assocName);
                    row.put("connected", assocUp);
                    row.put("state", !factory.getStatus() ? "STOPPED"
                            : assocUp ? "INACTIVE" : "COMM_DOWN");
                    asps.add(row);
                } else {
                    for (Asp asp : aspList) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("name", asp.getName() != null ? asp.getName() : factory.getName());
                        row.put("started", asp.isStarted());
                        row.put("association", assocName);
                        row.put("connected", asp.isConnected() || assocUp);
                        String fsm = asp.getState() != null ? asp.getState().getName() : null;
                        if (fsm == null || fsm.isBlank()) {
                            fsm = !factory.getStatus() ? "STOPPED"
                                    : (asp.isUp() || assocUp) ? "INACTIVE" : "COMM_DOWN";
                        }
                        row.put("state", fsm);
                        asps.add(row);
                    }
                }
            }
            for (As as : m3ua.getAppServers()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", as.getName());
                row.put("state", as.getState() != null ? as.getState().getName() : "?");
                appServers.add(row);
            }
        }
    }

    /**
     * Status detail must distinguish local listen from peer route.
     * Package-visible for unit tests (parity with OTA synthesizeSs7Detail).
     */
    static String synthesizeSs7Detail(boolean intentionallyStopped, boolean routeReady,
                                      boolean anyListen, boolean stackStarted,
                                      boolean peerConnected, boolean asActive,
                                      String applied) {
        if (intentionallyStopped) {
            return applied != null && !applied.isBlank() ? applied : "ss7=stopped";
        }
        if (routeReady) {
            String base = applied != null && applied.contains("ss7=") ? applied : "ss7=route-up";
            if (!base.contains("route=up")) {
                return base + ";route=up";
            }
            return base;
        }
        if (anyListen || stackStarted) {
            return "ss7=listening;peer="
                    + (peerConnected ? "up" : "down")
                    + ";as="
                    + (asActive ? "ACTIVE" : "DOWN")
                    + ";m3ua-not-ready";
        }
        return applied != null && !applied.isBlank() ? applied : "ss7=n/a";
    }

    static String associationLocal(Association a, Map<String, Server> serversByName) {
        if (a == null) {
            return "—";
        }
        String fallback = null;
        String serverName = a.getServerName();
        if (serverName != null && serversByName != null) {
            Server sv = serversByName.get(serverName);
            if (sv != null) {
                fallback = formatHostPort(sv.getHostAddress(), sv.getHostport());
            }
        }
        return resolveAssociationLocal(a.getHostAddress(), a.getHostPort(), fallback);
    }

    static String resolveAssociationLocal(String host, int port, String serverLocalOrNull) {
        if (hasUsableHost(host) && port > 0) {
            return host + ":" + port;
        }
        if (serverLocalOrNull != null && !serverLocalOrNull.isBlank()
                && !"—".equals(serverLocalOrNull)) {
            return serverLocalOrNull;
        }
        return formatHostPort(host, port);
    }

    static String formatHostPort(String host, int port) {
        if (!hasUsableHost(host)) {
            return "—";
        }
        return host + ":" + port;
    }

    private static boolean hasUsableHost(String host) {
        return host != null && !host.isBlank() && !"null".equalsIgnoreCase(host);
    }
}

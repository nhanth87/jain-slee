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

import com.microjainslee.admin.RaAdminJson;

import java.util.List;
import java.util.Map;

/**
 * Digicom-styled HTML fragment for HTMX status refresh (XSS-escaped).
 */
final class Ss7StatusHtml {

    private Ss7StatusHtml() {
    }

    @SuppressWarnings("unchecked")
    static String render(Map<String, Object> st) {
        boolean routeReady = Boolean.TRUE.equals(st.get("routeReady"));
        boolean active = Boolean.TRUE.equals(st.get("active"));
        String detail = String.valueOf(st.getOrDefault("detail", ""));
        String badge = routeReady ? badge("LIVE", "ok")
                : active || Boolean.TRUE.equals(st.get("listening"))
                ? badge("LISTEN", "warn")
                : badge("OFF", "mute");

        StringBuilder sb = new StringBuilder(2048);
        sb.append("<div class=\"link-status-panel\">");
        sb.append("<div class=\"link-status-head\">");
        sb.append("<h3>SS7 / SCTP</h3>").append(badge);
        sb.append("</div>");
        sb.append("<p class=\"link-status-detail\">").append(esc(detail)).append("</p>");
        Object err = st.get("error");
        if (err != null) {
            sb.append("<p class=\"link-status-detail\" style=\"color:var(--color-bad)\">")
                    .append(esc(String.valueOf(err))).append("</p>");
        }

        List<Map<String, Object>> servers = (List<Map<String, Object>>) st.get("servers");
        sb.append(table("SCTP servers",
                headers("Name", "Local", "Channel", "State"),
                serverRows(servers)));

        List<Map<String, Object>> assocs = (List<Map<String, Object>>) st.get("associations");
        sb.append(table("Associations",
                headers("Name", "Local", "Peer", "Type", "State"),
                assocRows(assocs)));

        List<Map<String, Object>> asps = (List<Map<String, Object>>) st.get("asps");
        List<Map<String, Object>> apps =
                (List<Map<String, Object>>) st.get("applicationServers");
        sb.append(table("M3UA ASP / AS",
                headers("Name", "Assoc", "Kind", "State"),
                m3uaRows(asps, apps)));

        sb.append(table("MTP3 congestion / SCCP restriction (per DPC)",
                headers("DPC", "MTP-STATUS", "SCON", "SCCP restriction"),
                congestionRows(st)));
        Object congBlock = st.get("sccpCongControlBlockingOutgoingSccpMessages");
        if (congBlock != null) {
            sb.append("<div class=\"kv\" style=\"margin-top:0.75rem\">")
                    .append("<span class=\"k\">sccpCongBlockOutgoing</span>")
                    .append("<span class=\"v\">").append(esc(String.valueOf(congBlock)))
                    .append("</span></div>");
        }

        sb.append("<div class=\"kv\" style=\"margin-top:0.75rem\">")
                .append("<span class=\"k\">routeReady</span>")
                .append("<span class=\"v ").append(routeReady ? "ok" : "bad").append("\">")
                .append(routeReady).append("</span></div>");
        sb.append("</div>");
        return sb.toString();
    }

    private static String serverRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return empty(4);
        }
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> r : rows) {
            b.append("<tr>")
                    .append(td(r.get("name")))
                    .append(tdMono(r.get("local")))
                    .append(td(r.get("channel")))
                    .append(tdState(r.get("state")))
                    .append("</tr>");
        }
        return b.toString();
    }

    private static String assocRows(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return empty(5);
        }
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> r : rows) {
            b.append("<tr>")
                    .append(td(r.get("name")))
                    .append(tdMono(r.get("local")))
                    .append(tdMono(r.get("peer")))
                    .append(td(r.get("type")))
                    .append(tdState(r.get("state")))
                    .append("</tr>");
        }
        return b.toString();
    }

    private static String m3uaRows(List<Map<String, Object>> asps,
                                   List<Map<String, Object>> apps) {
        if ((asps == null || asps.isEmpty()) && (apps == null || apps.isEmpty())) {
            return empty(4);
        }
        StringBuilder b = new StringBuilder();
        if (asps != null) {
            for (Map<String, Object> r : asps) {
                b.append("<tr>")
                        .append(td("ASP " + r.get("name")))
                        .append(td(r.get("association")))
                        .append(td("ASP"))
                        .append(tdState(r.get("state")))
                        .append("</tr>");
            }
        }
        if (apps != null) {
            for (Map<String, Object> r : apps) {
                b.append("<tr>")
                        .append(td("AS " + r.get("name")))
                        .append(td("—"))
                        .append(td("AS"))
                        .append(tdState(r.get("state")))
                        .append("</tr>");
            }
        }
        return b.toString();
    }

    private static String congestionRows(Map<String, Object> st) {
        Map<Integer, Long> status = intLongMap(st.get("mtp3StatusEventsByDpc"));
        Map<Integer, Long> scon = intLongMap(st.get("mtp3CongestionEventsByDpc"));
        Map<Integer, Integer> levels = intIntMap(st.get("sccpRestrictionLevelsByDpc"));
        java.util.TreeSet<Integer> dpcs = new java.util.TreeSet<>();
        dpcs.addAll(status.keySet());
        dpcs.addAll(scon.keySet());
        dpcs.addAll(levels.keySet());
        if (dpcs.isEmpty()) {
            return empty(4);
        }
        StringBuilder b = new StringBuilder();
        for (Integer dpc : dpcs) {
            b.append("<tr>")
                    .append(tdMono(dpc))
                    .append(td(status.getOrDefault(dpc, 0L)))
                    .append(td(scon.getOrDefault(dpc, 0L)))
                    .append(td(levels.getOrDefault(dpc, 0)))
                    .append("</tr>");
        }
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Long> intLongMap(Object raw) {
        Map<Integer, Long> out = new java.util.TreeMap<>();
        if (raw instanceof Map<?, ?> in) {
            in.forEach((k, v) -> {
                Integer dpc = asInt(k);
                if (dpc != null && v instanceof Number n) {
                    out.put(dpc, n.longValue());
                }
            });
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Integer> intIntMap(Object raw) {
        Map<Integer, Integer> out = new java.util.TreeMap<>();
        if (raw instanceof Map<?, ?> in) {
            in.forEach((k, v) -> {
                Integer dpc = asInt(k);
                if (dpc != null && v instanceof Number n) {
                    out.put(dpc, n.intValue());
                }
            });
        }
        return out;
    }

    private static Integer asInt(Object k) {
        if (k instanceof Number n) {
            return n.intValue();
        }
        try {
            return k == null ? null : Integer.parseInt(String.valueOf(k));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String table(String caption, String headersHtml, String rows) {
        return "<div class=\"link-status-table-wrap\">"
                + "<p class=\"link-status-caption\">" + esc(caption) + "</p>"
                + "<table class=\"link-status-table link-status-table--ss7\"><thead><tr>"
                + headersHtml + "</tr></thead><tbody>" + rows + "</tbody></table></div>";
    }

    private static String headers(String... hs) {
        StringBuilder b = new StringBuilder();
        for (String h : hs) {
            b.append("<th>").append(esc(h)).append("</th>");
        }
        return b.toString();
    }

    private static String empty(int cols) {
        return "<tr><td colspan=\"" + cols + "\" class=\"link-status-empty\">(none)</td></tr>";
    }

    private static String td(Object v) {
        return "<td>" + esc(v == null ? "—" : String.valueOf(v)) + "</td>";
    }

    private static String tdMono(Object v) {
        return "<td class=\"link-status-mono\">"
                + esc(v == null ? "—" : String.valueOf(v)) + "</td>";
    }

    private static String tdState(Object v) {
        String s = v == null ? "?" : String.valueOf(v);
        String tone = switch (s) {
            case "UP", "BOUND", "LIVE", "ACTIVE" -> "ok";
            case "LISTEN", "STARTED", "COMM_DOWN", "ACTIVE_UNBOUND", "INACTIVE",
                 "INACTIVE_SENT", "ACTIVE_SENT", "UP_SENT" -> "warn";
            default -> "mute";
        };
        return "<td class=\"link-status-state link-status-state--" + tone
                + "\"><span>" + esc(s) + "</span></td>";
    }

    private static String badge(String text, String tone) {
        String cls = "ok".equals(tone) ? "link-status-badge--ok" : "link-status-badge--mute";
        return "<span class=\"link-status-badge " + cls + "\">" + esc(text) + "</span>";
    }

    private static String esc(String s) {
        return RaAdminJson.escHtml(s);
    }
}

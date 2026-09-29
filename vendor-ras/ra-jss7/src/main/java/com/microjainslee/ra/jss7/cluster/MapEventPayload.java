/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87)
 */

package com.microjainslee.ra.jss7.cluster;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

import org.restcomm.protocols.ss7.map.api.MAPMessage;
import org.restcomm.protocols.ss7.map.api.MAPMessageType;

/**
 * ADR 0007 D2 — portable form of {@code Ss7MapEvent.Service}.
 *
 * <h2>Why the decoded MAP message cannot cross the wire</h2>
 * {@code Ss7MapEvent.Service} carries an {@code org.restcomm.protocols.ss7.map.api.MAPMessage}.
 * The Infinispan marshalling allow-list is deliberately restricted to
 * {@code com.microjainslee.*}, {@code com.example.*} and {@code java.*} — jSS7
 * types are excluded on purpose, both because they are not stable
 * {@code Serializable} across stack versions and because widening the
 * allow-list would erase the blast-radius fence (the same reason
 * {@code TcapDialogSnapshotPayload} exists instead of serialising
 * {@code TcapDialogSnapshot} directly).
 *
 * <p>
 * So a cross-node MAP response must be flattened to a map of primitives, shipped
 * over the sticky event bus, and re-materialised on the owning node.
 *
 * <h2>Field map</h2>
 * Keys are stable strings; {@link #P_MESSAGE} holds the one field that must
 * survive byte-exact (the SMS TP-UD / user-data blob), because re-deriving it from
 * the flattened view would corrupt binary payloads.
 */
public final class MapEventPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final String P_DIALOG_ID = "dialogId";
    public static final String P_TYPE = "type";
    public static final String P_MESSAGE = "message";

    private final String dialogId;
    private final String typeName;
    private final Map<String, String> fields;
    private final byte[] message;

    private MapEventPayload(String dialogId, String typeName, Map<String, String> fields, byte[] message) {
        this.dialogId = dialogId;
        this.typeName = typeName;
        this.fields = fields == null ? Map.of() : new LinkedHashMap<>(fields);
        this.message = message;
    }

    /**
     * Build the portable payload for a MAP service event.
     *
     * <p>
     * The flattened field map is intentionally shallow. It exists so the target
     * node can route and log the event, and so the SBB sees which operation and
     * which subscriber it is — not as a substitute for the fully decoded jSS7
     * object. The full object is available when the response is delivered
     * <b>locally</b> (the common path, and always the path when a single node
     * owns both the dialog and the client connection).
     *
     * @param dialogId activity-context name / correlation key
     * @param type     MAP message type
     * @param message  the decoded message; flattened where cheap, may be {@code null}
     */
    public static MapEventPayload of(String dialogId, MAPMessageType type, MAPMessage message) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (type != null) {
            fields.put(P_TYPE, type.name());
        }
        if (message != null) {
            flatten(message, fields);
        }
        return new MapEventPayload(dialogId, type == null ? null : type.name(), fields, null);
    }

    /**
     * Best-effort flattening of the identifying header fields. Deep traversal of
     * every MAP service is not warranted here: the contract that matters is the
     * correlation key, and the owning node re-reads the real state from the SLEE
     * activity it already holds.
     */
    private static void flatten(MAPMessage message, Map<String, String> out) {
        out.put("javaClass", message.getClass().getName());
        try {
            if (message instanceof org.restcomm.protocols.ss7.map.api.service.sms.SendRoutingInfoForSMResponse r) {
                // The GMLC/OTA hot path: IMSI + the serving MSC are what the SBB
                // needs to chain the next MT-ForwardSM.
                putIfPresent(out, "imsi", digits(r.getIMSI() == null ? null : r.getIMSI().getData()));
                org.restcomm.protocols.ss7.map.api.service.sms.LocationInfoWithLMSI li =
                        r.getLocationInfoWithLMSI();
                if (li != null) {
                    if (li.getNetworkNodeNumber() != null) {
                        putIfPresent(out, "mscNumber", digits(li.getNetworkNodeNumber().getAddress()));
                    }
                    if (li.getLMSI() != null) {
                        // LMSI is a byte[] (E.164 digits in BER octets) — hex it.
                        putIfPresent(out, "lmsi", hex(li.getLMSI().getData()));
                    }
                    if (li.getSmsf3gppNumber() != null) {
                        putIfPresent(out, "smsf3gppNumber", digits(li.getSmsf3gppNumber().getAddress()));
                    }
                }
            } else if (message instanceof org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.AnyTimeInterrogationResponse a) {
                flattenSubscriberInfo(a.getSubscriberInfo(), out);
            } else if (message instanceof org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.ProvideSubscriberInfoResponse p) {
                flattenSubscriberInfo(p.getSubscriberInfo(), out);
            }
        } catch (RuntimeException ignore) {
            // A partially flattened header is better than dropping the response.
        }
    }

    /** ATI / PSI carry identity inside {@code SubscriberInfo}, not on the response. */
    private static void flattenSubscriberInfo(
            org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.SubscriberInfo si,
            Map<String, String> out) {
        if (si == null) {
            return;
        }
        try {
            var loc = si.getLocationInformation();
            if (loc != null && loc.getMscNumber() != null) {
                putIfPresent(out, "mscNumber", digits(loc.getMscNumber().getAddress()));
            }
            if (loc != null && loc.getVlrNumber() != null) {
                putIfPresent(out, "vlrNumber", digits(loc.getVlrNumber().getAddress()));
            }
            var geo = loc == null ? null : loc.getGeographicalInformation();
            if (geo != null) {
                putIfPresent(out, "lat", String.valueOf(geo.getLatitude()));
                putIfPresent(out, "lon", String.valueOf(geo.getLongitude()));
            }
        } catch (RuntimeException ignore) {
            // subscriberInfo / locationInformation are optional in ATI responses
        }
    }

    private static void putIfPresent(Map<String, String> out, String key, String value) {
        if (value != null && !value.isEmpty()) {
            out.put(key, value);
        }
    }

    private static String digits(String s) {
        return s == null ? null : s.trim();
    }

    private static String hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public String dialogId() {
        return dialogId;
    }

    /** {@link MAPMessageType} name, or {@code null}. */
    public String typeName() {
        return typeName;
    }

    public Map<String, String> fields() {
        return fields;
    }

    public byte[] message() {
        return message == null ? null : message.clone();
    }

    public String field(String key) {
        return fields.get(key);
    }

    @Override
    public String toString() {
        return "MapEventPayload[dialog=" + dialogId + ", type=" + typeName
                + ", fields=" + fields.keySet() + ']';
    }
}

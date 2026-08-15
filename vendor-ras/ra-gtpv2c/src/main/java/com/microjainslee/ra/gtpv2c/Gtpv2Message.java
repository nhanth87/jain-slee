package com.microjainslee.ra.gtpv2c;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record Gtpv2Message(
        Gtpv2MessageType type,
        int teid,
        int sequence,
        byte recovery,
        List<Gtpv2Ie> ies
) {
    public Gtpv2Message {
        Objects.requireNonNull(type);
        ies = List.copyOf(ies == null ? List.of() : ies);
        if (sequence < 0 || sequence > 0xff_ffff) {
            throw new IllegalArgumentException("sequence is 24-bit");
        }
    }

    public Gtpv2Ie first(int type) {
        return ies.stream().filter(i -> i.type() == type).findFirst().orElse(null);
    }

    public static Gtpv2Message echoRequest(int sequence, byte recovery) {
        return new Gtpv2Message(Gtpv2MessageType.ECHO_REQUEST, 0, sequence, recovery, List.of(
                new Gtpv2Ie(Gtpv2Ie.RECOVERY, 0, new byte[] {recovery})));
    }

    public static Gtpv2Message echoResponse(int sequence, byte recovery) {
        return new Gtpv2Message(Gtpv2MessageType.ECHO_RESPONSE, 0, sequence, recovery, List.of(
                new Gtpv2Ie(Gtpv2Ie.RECOVERY, 0, new byte[] {recovery})));
    }

    public static Gtpv2Message versionNotSupported(int sequence) {
        return new Gtpv2Message(Gtpv2MessageType.VERSION_NOT_SUPPORTED, 0, sequence, (byte) 0, List.of());
    }

    public Gtpv2Message withIes(Gtpv2Ie... extra) {
        List<Gtpv2Ie> all = new ArrayList<>(ies);
        all.addAll(List.of(extra));
        return new Gtpv2Message(type, teid, sequence, recovery, all);
    }
}

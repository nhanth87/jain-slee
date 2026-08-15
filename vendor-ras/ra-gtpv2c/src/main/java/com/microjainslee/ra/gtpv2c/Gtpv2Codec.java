package com.microjainslee.ra.gtpv2c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal GTPv2-C codec (TS 29.274 header + opaque TLV IEs).
 * Encode: Echo / Version Not Supported use T=0 (no TEID). Decode honors the T bit
 * so an MME Echo (T=0) is accepted. Unknown message types map to UNKNOWN.
 * Duplicate suppression lives in the RA, not here.
 */
public final class Gtpv2Codec {

    private static final int FLAGS_V2_T0 = 0x40;
    private static final int FLAGS_V2_T1 = 0x48;

    private Gtpv2Codec() {}

    public static byte[] encode(Gtpv2Message msg) {
        byte[] ies = encodeIes(msg.ies());
        boolean t = msg.type().teidInHeader();
        int extra = t ? 8 : 4;
        ByteBuffer buf = ByteBuffer.allocate(4 + extra + ies.length).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) (t ? FLAGS_V2_T1 : FLAGS_V2_T0));
        buf.put((byte) msg.type().code);
        buf.putShort((short) (extra + ies.length));
        if (t) {
            buf.putInt(msg.teid());
        }
        buf.put((byte) ((msg.sequence() >> 16) & 0xff));
        buf.put((byte) ((msg.sequence() >> 8) & 0xff));
        buf.put((byte) (msg.sequence() & 0xff));
        buf.put((byte) 0);
        buf.put(ies);
        return buf.array();
    }

    public static Gtpv2Message decode(byte[] wire) {
        if (wire == null || wire.length < 8) {
            throw new IllegalArgumentException("truncated GTPv2");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int flags = buf.get() & 0xff;
        if (((flags >> 5) & 0x07) != 2) {
            throw new IllegalArgumentException("not GTPv2");
        }
        boolean t = (flags & 0x08) != 0;
        int min = t ? 12 : 8;
        if (wire.length < min) {
            throw new IllegalArgumentException("truncated GTPv2 header");
        }
        Gtpv2MessageType type = Gtpv2MessageType.of(buf.get() & 0xff);
        int length = buf.getShort() & 0xffff;
        int extra = t ? 8 : 4;
        int teid = t ? buf.getInt() : 0;
        int seq = ((buf.get() & 0xff) << 16) | ((buf.get() & 0xff) << 8) | (buf.get() & 0xff);
        buf.get();
        int ieBytes = length - extra;
        if (ieBytes < 0 || buf.remaining() < ieBytes) {
            throw new IllegalArgumentException("truncated GTPv2 IEs");
        }
        byte[] ieBuf = new byte[ieBytes];
        buf.get(ieBuf);
        List<Gtpv2Ie> ies = decodeIes(ieBuf);
        byte recovery = 0;
        Gtpv2Ie rec = ies.stream().filter(i -> i.type() == Gtpv2Ie.RECOVERY).findFirst().orElse(null);
        if (rec != null && rec.value().length > 0) {
            recovery = rec.value()[0];
        }
        return new Gtpv2Message(type, teid, seq, recovery, ies);
    }

    public static byte[] encodeIes(List<Gtpv2Ie> ies) {
        int n = 0;
        for (Gtpv2Ie ie : ies) {
            n += 4 + ie.value().length;
        }
        ByteBuffer buf = ByteBuffer.allocate(n).order(ByteOrder.BIG_ENDIAN);
        for (Gtpv2Ie ie : ies) {
            buf.put((byte) ie.type());
            buf.putShort((short) ie.value().length);
            buf.put((byte) ie.instance());
            buf.put(ie.value());
        }
        return buf.array();
    }

    public static List<Gtpv2Ie> decodeIes(byte[] raw) {
        List<Gtpv2Ie> out = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        while (buf.remaining() >= 4) {
            int type = buf.get() & 0xff;
            int len = buf.getShort() & 0xffff;
            int inst = buf.get() & 0x0f;
            if (buf.remaining() < len) {
                break;
            }
            byte[] v = new byte[len];
            buf.get(v);
            out.add(new Gtpv2Ie(type, inst, v));
        }
        return out;
    }
}

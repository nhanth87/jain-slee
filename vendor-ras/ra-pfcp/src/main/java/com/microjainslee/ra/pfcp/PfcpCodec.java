package com.microjainslee.ra.pfcp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Minimal PFCP codec (TS 29.244 header). S flag set when SEID != 0. */
public final class PfcpCodec {

    private PfcpCodec() {}

    public static byte[] encode(PfcpMessage msg) {
        boolean s = msg.seid() != 0;
        int header = s ? 16 : 8;
        ByteBuffer buf = ByteBuffer.allocate(header + msg.payload().length).order(ByteOrder.BIG_ENDIAN);
        int flags = 0x20; // version 1
        if (s) {
            flags |= 0x01;
        }
        buf.put((byte) flags);
        buf.put((byte) msg.type().code);
        buf.putShort((short) (header - 4 + msg.payload().length));
        if (s) {
            buf.putLong(msg.seid());
        }
        buf.put((byte) ((msg.sequence() >> 16) & 0xff));
        buf.put((byte) ((msg.sequence() >> 8) & 0xff));
        buf.put((byte) (msg.sequence() & 0xff));
        buf.put((byte) 0);
        buf.put(msg.payload());
        return buf.array();
    }

    public static PfcpMessage decode(byte[] wire) {
        if (wire == null || wire.length < 8) {
            throw new IllegalArgumentException("truncated PFCP");
        }
        ByteBuffer buf = ByteBuffer.wrap(wire).order(ByteOrder.BIG_ENDIAN);
        int flags = buf.get() & 0xff;
        if (((flags >> 5) & 0x07) != 1) {
            throw new IllegalArgumentException("not PFCP v1");
        }
        boolean s = (flags & 0x01) != 0;
        PfcpMessageType type = PfcpMessageType.of(buf.get() & 0xff);
        buf.getShort();
        long seid = s ? buf.getLong() : 0L;
        int seq = ((buf.get() & 0xff) << 16) | ((buf.get() & 0xff) << 8) | (buf.get() & 0xff);
        buf.get();
        byte[] payload = new byte[buf.remaining()];
        buf.get(payload);
        return new PfcpMessage(type, seid, seq, payload);
    }
}

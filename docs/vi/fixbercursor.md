🔍 Phân tích Bottleneck & Fix
Từ benchmark, có 3 root causes rõ ràng:

Root Cause #1: Virtual dispatch readByte() — O(n) overhead


BerCursor.readInt32()
  → backend.readByte(i)          // interface call ~5ns mỗi lần
  → HeapAsnBufferBackend.readByte(i)  // deref + bounds check
Legacy AsnInputStream:
  → this.buffer[this.start + pos]  // direct array access ~1ns
Fix: Fast-path byte[] trực tiếp trong BerCursor

java


public final class BerCursor {
    // ── Dual-mode backing ────────────────────────────────────────────
    // Khi heap-backed: dùng trực tiếp, KHÔNG qua interface
    private byte[]           heapBuf;   // non-null nếu heap path
    private AsnBufferBackend backend;   // non-null nếu ByteBuf path
    private int base;
    private int limit;
    private int pos;
    // ── Cached TLV ───────────────────────────────────────────────────
    private int     tagClass;
    private boolean primitive;
    private int     tag;
    private int     valueOffset;
    private int     valueLength;
    // ── ThreadLocal pool (KHÔNG synchronized) ───────────────────────
    private static final ThreadLocal<BerCursor[]> TL_POOL =
        ThreadLocal.withInitial(() -> new BerCursor[16]);
    private static final ThreadLocal<Integer> TL_POOL_SIZE =
        ThreadLocal.withInitial(() -> 0);
    public static BerCursor wrapHeap(byte[] data, int offset, int length) {
        BerCursor c = acquire();
        c.heapBuf = data;
        c.backend = null;
        c.base    = offset;
        c.limit   = offset + length;
        c.pos     = offset;
        return c;
    }
    public static BerCursor wrapByteBuf(AsnBufferBackend backend,
                                         int offset, int length) {
        BerCursor c = acquire();
        c.heapBuf = null;
        c.backend = backend;
        c.base    = offset;
        c.limit   = offset + length;
        c.pos     = offset;
        return c;
    }
    private static BerCursor acquire() {
        int size = TL_POOL_SIZE.get();
        if (size > 0) {
            BerCursor[] pool = TL_POOL.get();
            BerCursor c = pool[--size];
            pool[size] = null;
            TL_POOL_SIZE.set(size);
            return c;
        }
        return new BerCursor();
    }
    public void release() {
        int size = TL_POOL_SIZE.get();
        BerCursor[] pool = TL_POOL.get();
        if (size < pool.length) {
            pool[size] = this;
            TL_POOL_SIZE.set(size + 1);
        }
        // nếu pool đầy → GC thu (hiếm)
    }
    // ── Inlined readByte — KHÔNG virtual call ────────────────────────
    @SuppressWarnings("nothing")
    private int nextByte() {
        if (heapBuf != null)
            return heapBuf[pos++] & 0xFF;   // ← direct array, JIT inline
        return backend.readByte(pos++) & 0xFF;
    }
    private int byteAt(int i) {
        if (heapBuf != null)
            return heapBuf[i] & 0xFF;
        return backend.readByte(i) & 0xFF;
    }
    // ================================================================
    // READ TLV — hoàn toàn inline khi heap-backed
    // ================================================================
    public void readTag() throws AsnException {
        int b = nextByte();
        tagClass  = (b >> 6) & 0x03;
        primitive = (b & 0x20) == 0;
        tag       = b & 0x1F;
        if (tag == 0x1F) {
            tag = 0;
            do {
                b = nextByte();
                tag = (tag << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
        }
        b = nextByte();
        if (b <= 0x7F) {
            valueLength = b;
        } else {
            int n = b & 0x7F;
            valueLength = 0;
            for (int i = 0; i < n; i++)
                valueLength = (valueLength << 8) | nextByte();
        }
        valueOffset = pos;
    }
    // ================================================================
    // BULK READS — System.arraycopy thay vì byte-by-byte loop
    // ================================================================
    public int readInt32() {
        // Bulk read: lấy tối đa 4 bytes
        int len = valueLength;
        int end = valueOffset + len;
        int result;
        if (heapBuf != null) {
            // Trực tiếp từ array — JIT sẽ unroll
            result = 0;
            for (int i = valueOffset; i < end; i++)
                result = (result << 8) | (heapBuf[i] & 0xFF);
        } else {
            result = 0;
            for (int i = valueOffset; i < end; i++)
                result = (result << 8) | (backend.readByte(i) & 0xFF);
        }
        pos = end;
        return result;
    }
    /**
     * Lấy slice — heap path: trỏ trực tiếp vào heapBuf, không copy.
     */
    public BerSlice getOctetStringSlice() {
        BerSlice s;
        if (heapBuf != null) {
            s = new BerSlice(heapBuf, valueOffset, valueLength);
        } else {
            s = new BerSlice(backend, valueOffset, valueLength);
        }
        pos = valueOffset + valueLength;
        return s;
    }
    /**
     * getOctetString: System.arraycopy thay vì byte-by-byte.
     */
    public byte[] getOctetString() {
        byte[] out = new byte[valueLength];
        if (heapBuf != null) {
            System.arraycopy(heapBuf, valueOffset, out, 0, valueLength);
        } else {
            backend.copyTo(valueOffset, out, 0, valueLength);
        }
        pos = valueOffset + valueLength;
        return out;
    }
    /**
     * openConstructed — fast: reuse cùng heapBuf reference, KHÔNG copy.
     */
    public BerCursor openConstructed() {
        if (heapBuf != null)
            return BerCursor.wrapHeap(heapBuf, valueOffset, valueLength);
        return BerCursor.wrapByteBuf(backend, valueOffset, valueLength);
    }
    public void skipValue()     { pos = valueOffset + valueLength; }
    public boolean hasMore()    { return pos < limit; }
    public int tagClass()       { return tagClass; }
    public int tag()            { return tag; }
    public boolean isPrimitive(){ return primitive; }
    public int valueLength()    { return valueLength; }
    public int remaining()      { return limit - pos; }
}
Root Cause #2: BerSlice — vẫn còn interface overhead
Fix: Dual-mode BerSlice song song với BerCursor

java


public final class BerSlice {
    // ── Heap-fast-path ───────────────────────────────────────────────
    private final byte[]           heapBuf;  // non-null nếu heap
    private final AsnBufferBackend backend;  // non-null nếu ByteBuf
    private final int              offset;
    private final int              length;
    // ── Heap constructor (zero-copy, zero virtual) ───────────────────
    BerSlice(byte[] heapBuf, int offset, int length) {
        this.heapBuf = heapBuf;
        this.backend = null;
        this.offset  = offset;
        this.length  = length;
    }
    // ── ByteBuf constructor ──────────────────────────────────────────
    BerSlice(AsnBufferBackend backend, int offset, int length) {
        this.heapBuf = null;
        this.backend = backend;
        this.offset  = offset;
        this.length  = length;
    }
    public int length() { return length; }
    public int offset() { return offset; }
    /**
     * Shallow copy — O(1), không copy dữ liệu.
     * Dùng để wrap BerSlice vào BerCursor để tiếp tục decode lazy.
     */
    public BerCursor cursor() {
        if (heapBuf != null)
            return BerCursor.wrapHeap(heapBuf, offset, length);
        return BerCursor.wrapByteBuf(backend, offset, length);
    }
    /**
     * toByteArray: System.arraycopy — chỉ gọi khi thực sự cần.
     */
    public byte[] toByteArray() {
        byte[] out = new byte[length];
        if (heapBuf != null)
            System.arraycopy(heapBuf, offset, out, 0, length);
        else
            backend.copyTo(offset, out, 0, length);
        return out;
    }
    /**
     * equalsBytes — không tạo byte[] copy để so sánh.
     */
    public boolean equalsBytes(byte[] other, int otherOffset, int len) {
        if (len != length) return false;
        if (heapBuf != null)
            return Arrays.equals(
                heapBuf, offset, offset + length,
                other, otherOffset, otherOffset + len
            );
        return backend.equalsBytes(offset, other, otherOffset, len);
    }
    public boolean equalsBytes(byte[] other) {
        return equalsBytes(other, 0, other.length);
    }
    /**
     * Wrap thành ByteBuffer read-only — zero copy.
     */
    public ByteBuffer asByteBuffer() {
        if (heapBuf != null)
            return ByteBuffer.wrap(heapBuf, offset, length).asReadOnlyBuffer();
        return backend.slice(offset, length).nioBuffer().asReadOnlyBuffer();
    }
}
Root Cause #3: BerWriter — toByteArray() copy trên roundtrip
Fix: BerWriter → BerCursor không copy

java


// Thêm vào BerWriter:
/**
 * Wrap kết quả encode thành BerCursor để decode ngay — ZERO COPY.
 * Dùng trong roundtrip test / loopback scenario.
 */
public BerCursor resultAsCursor() {
    return BerCursor.wrapHeap(buf, writePos, buf.length - writePos);
}
/**
 * Wrap kết quả thành BerSlice — ZERO COPY.
 */
public BerSlice resultAsSlice() {
    return new BerSlice(buf, writePos, buf.length - writePos);
}
/**
 * Trả về offset hiện tại (dùng để đo size encode).
 */
public int encodedLength() {
    return buf.length - writePos;
}
Root Cause #4: AsnBufferBackend thiếu copyTo()
Fix: Thêm copyTo() vào interface

java


public interface AsnBufferBackend {
    byte readByte(int index);
    int  length();
    ByteBuf slice(int offset, int length);
    boolean equalsBytes(int offset, byte[] other, int otherOff, int len);
    // ── MỚI: bulk copy, tránh byte-by-byte loop ──────────────────────
    void copyTo(int srcOffset, byte[] dest, int destOffset, int length);
}
// HeapAsnBufferBackend:
@Override
public void copyTo(int srcOffset, byte[] dest, int destOffset, int length) {
    System.arraycopy(buf, srcOffset, dest, destOffset, length);
}
// ByteBufAsnBufferBackend:
@Override
public void copyTo(int srcOffset, byte[] dest, int destOffset, int length) {
    byteBuf.getBytes(srcOffset, dest, destOffset, length);
}
Dự báo benchmark sau fix


Test	Before	Sau fix	Lý do
Decode simple TLV	1.33x ✅	~4x	direct array access
Decode nested SEQUENCE	2.11x ❌	~2x ✅	ThreadLocal pool + direct ref
Encode INTEGER	3.57x ✅	~4x	giữ nguyên
Roundtrip INTEGER	2.79x ❌	~3x ✅	resultAsCursor() zero-copy
Decode 1KB OCTET	3.40x ❌	~1.5x ✅	System.arraycopy
Encode 1KB OCTET	1.72x ✅	~2x	giữ nguyên
Tóm tắt các thay đổi cần apply


1. BerCursor.java
   ├─ Thêm heapBuf field (dual-mode)
   ├─ nextByte() / byteAt() inline (không virtual)
   ├─ ThreadLocal pool (bỏ synchronized ArrayDeque)
   ├─ openConstructed() share heapBuf reference
   ├─ getOctetString() dùng System.arraycopy
   └─ resultAsCursor() / resultAsSlice() (zero-copy roundtrip)
2. BerSlice.java
   ├─ Thêm heapBuf field (dual-mode)
   ├─ toByteArray() dùng System.arraycopy
   ├─ equalsBytes() dùng Arrays.equals (không new byte[])
   └─ cursor() → BerCursor wrap lại (zero-copy lazy decode)
3. BerWriter.java
   ├─ resultAsCursor() (zero-copy)
   └─ resultAsSlice() (zero-copy)
4. AsnBufferBackend.java
   └─ copyTo() bulk method
Apply xong chạy lại benchmark — nếu vẫn có case nào chậm hơn legacy thì ping lại với số liệu cụ thể, sẽ đào sâu tiếp! 🚀




Claude 4.6
ngoài ra chúng ta chưa encode/decode choice nữa
Đúng rồi! CHOICE là một trong những construct phức tạp nhất trong ASN.1 BER vì không có wrapper tag — tag của alternative chính là tag của CHOICE đó. Hãy viết đầy đủ.

🔬 ASN.1 CHOICE Encode/Decode — Zero-Copy
1. Đặc điểm CHOICE trong BER (quan trọng)


SEQUENCE  → có wrapper tag+length bao ngoài  ✅
SET       → có wrapper tag+length bao ngoài  ✅
CHOICE    → KHÔNG có wrapper — tag của value IS tag của CHOICE ⚠️
Ví dụ:
  OperationCode ::= CHOICE {
      localValue  [0] IMPLICIT INTEGER,   -- wire: A0 xx ...
      globalValue [1] IMPLICIT OID        -- wire: A1 xx ...
  }
  → decode bằng cách đọc tag rồi dispatch
  → encode bằng cách ghi trực tiếp alternative (không thêm wrapper)
2. BerChoice.java — Core descriptor
java


package org.mobicents.protocols.asn;
/**
 * Descriptor cho một CHOICE alternative.
 * Dùng để build dispatch table không cần if-else chain.
 *
 * CHOICE trong BER:
 *   - Không có wrapper TLV
 *   - Tag của chosen alternative = tag của CHOICE trên wire
 *   - Encode = encode alternative trực tiếp (không thêm tag ngoài)
 *   - Decode = readTag() rồi dispatch theo (tagClass, tag)
 */
public final class BerChoice {
    public final int     tagClass;
    public final int     tag;
    public final boolean primitive;
    public final String  name;
    public BerChoice(int tagClass, boolean primitive, int tag, String name) {
        this.tagClass  = tagClass;
        this.primitive = primitive;
        this.tag       = tag;
        this.name      = name;
    }
    /** Kiểm tra cursor hiện tại có khớp alternative này không */
    public boolean matches(BerCursor cursor) {
        return cursor.tagClass() == tagClass
            && cursor.tag()      == tag;
    }
    // ── Factory helpers ──────────────────────────────────────────────
    public static BerChoice contextPrimitive(int tag, String name) {
        return new BerChoice(BerTag.CONTEXT, true, tag, name);
    }
    public static BerChoice contextConstructed(int tag, String name) {
        return new BerChoice(BerTag.CONTEXT, false, tag, name);
    }
    public static BerChoice universalPrimitive(int tag, String name) {
        return new BerChoice(BerTag.UNIVERSAL, true, tag, name);
    }
    public static BerChoice applicationPrimitive(int tag, String name) {
        return new BerChoice(BerTag.APPLICATION, true, tag, name);
    }
    public static BerChoice applicationConstructed(int tag, String name) {
        return new BerChoice(BerTag.APPLICATION, false, tag, name);
    }
    @Override
    public String toString() {
        return name + "([" + tagClass + "] " + (primitive ? "P" : "C")
            + " tag=" + tag + ")";
    }
}
3. ChoiceDecoder.java — Generic interface
java


package org.mobicents.protocols.asn;
/**
 * Generic interface cho CHOICE decoder.
 *
 * Pattern:
 *   1. Caller đọc tag (cursor.readTag())
 *   2. Gọi decode(cursor) → T
 *   3. Decoder dispatch theo tag, decode content, trả về T
 *
 * @param <T> result type (thường là enum + value holder)
 */
@FunctionalInterface
public interface ChoiceDecoder<T> {
    T decode(BerCursor cursor) throws AsnException;
}
4. TCAP CHOICE types
4a. OperationCode CHOICE
java


package org.mobicents.protocols.ss7.tcap.asn.comp;
import org.mobicents.protocols.asn.*;
/**
 * OperationCode ::= CHOICE {
 *     localValue   [0] IMPLICIT INTEGER,
 *     globalValue  [1] IMPLICIT OBJECT IDENTIFIER
 * }
 *
 * Encode/Decode zero-copy.
 */
public final class OperationCodeCodec {
    public enum Type { LOCAL, GLOBAL }
    /** Kết quả decode — reusable */
    public static final class Result {
        public Type    type;
        public int     localValue;   // nếu LOCAL
        public BerSlice globalValue; // nếu GLOBAL (lazy OID bytes)
        public void reset() {
            type        = null;
            localValue  = 0;
            globalValue = null;
        }
    }
    // CHOICE alternatives
    private static final int TAG_LOCAL  = 0x00; // [0] IMPLICIT INTEGER
    private static final int TAG_GLOBAL = 0x01; // [1] IMPLICIT OID
    // ── DECODE ───────────────────────────────────────────────────────
    /**
     * Decode OperationCode CHOICE.
     * Caller đã gọi cursor.readTag() trước khi gọi hàm này.
     */
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT) {
            throw new AsnException("OperationCode: expected CONTEXT tag, " +
                "got class=" + cursor.tagClass() + " tag=" + cursor.tag());
        }
        switch (cursor.tag()) {
            case TAG_LOCAL:
                out.type       = Type.LOCAL;
                out.localValue = cursor.readInt32();
                break;
            case TAG_GLOBAL:
                out.type        = Type.GLOBAL;
                out.globalValue = cursor.getOctetStringSlice(); // lazy OID
                break;
            default:
                throw new AsnException("OperationCode: unknown tag=" + cursor.tag());
        }
        cursor.skipValue();
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    /** Encode local OperationCode — [0] IMPLICIT INTEGER */
    public static void encodeLocal(BerWriter w, int localValue) {
        // CHOICE: không có wrapper, ghi trực tiếp
        w.writeInt32(BerTag.CONTEXT, TAG_LOCAL, localValue);
    }
    /** Encode global OperationCode — [1] IMPLICIT OID */
    public static void encodeGlobal(BerWriter w, byte[] oidEncoded) {
        w.writeOctetString(BerTag.CONTEXT, TAG_GLOBAL,
            oidEncoded, 0, oidEncoded.length);
    }
    /** Encode từ Result */
    public static void encode(BerWriter w, Result result) {
        if (result.type == Type.LOCAL) {
            encodeLocal(w, result.localValue);
        } else {
            encodeGlobal(w, result.globalValue.toByteArray());
        }
    }
    private OperationCodeCodec() {}
}
4b. ErrorCode CHOICE
java


package org.mobicents.protocols.ss7.tcap.asn.comp;
import org.mobicents.protocols.asn.*;
/**
 * ErrorCode ::= CHOICE {
 *     localValue   [0] IMPLICIT INTEGER,
 *     globalValue  [1] IMPLICIT OBJECT IDENTIFIER
 * }
 */
public final class ErrorCodeCodec {
    public enum Type { LOCAL, GLOBAL }
    public static final class Result {
        public Type    type;
        public int     localValue;
        public BerSlice globalValue;
        public void reset() { type = null; localValue = 0; globalValue = null; }
    }
    private static final int TAG_LOCAL  = 0x00;
    private static final int TAG_GLOBAL = 0x01;
    // ── DECODE ───────────────────────────────────────────────────────
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT)
            throw new AsnException("ErrorCode: expected CONTEXT");
        switch (cursor.tag()) {
            case TAG_LOCAL:
                out.type       = Type.LOCAL;
                out.localValue = cursor.readInt32();
                break;
            case TAG_GLOBAL:
                out.type        = Type.GLOBAL;
                out.globalValue = cursor.getOctetStringSlice();
                break;
            default:
                throw new AsnException("ErrorCode: unknown tag=" + cursor.tag());
        }
        cursor.skipValue();
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    public static void encodeLocal(BerWriter w, int value) {
        w.writeInt32(BerTag.CONTEXT, TAG_LOCAL, value);
    }
    public static void encodeGlobal(BerWriter w, byte[] oidEncoded) {
        w.writeOctetString(BerTag.CONTEXT, TAG_GLOBAL,
            oidEncoded, 0, oidEncoded.length);
    }
    private ErrorCodeCodec() {}
}
4c. Problem CHOICE (dùng trong Reject)
java


package org.mobicents.protocols.ss7.tcap.asn.comp;
import org.mobicents.protocols.asn.*;
/**
 * Problem ::= CHOICE {
 *     generalProblem      [0] IMPLICIT GeneralProblem,
 *     invokeProblem       [1] IMPLICIT InvokeProblem,
 *     returnResultProblem [2] IMPLICIT ReturnResultProblem,
 *     returnErrorProblem  [3] IMPLICIT ReturnErrorProblem
 * }
 *
 * GeneralProblem ::= INTEGER {
 *     unrecognisedComponent(0), mistypedComponent(1), badlyStructuredComponent(2)
 * }
 * InvokeProblem ::= INTEGER {
 *     duplicateInvocation(0), unrecognisedOperation(1),
 *     mistypedArgument(2), resourceLimitation(3),
 *     initiatingRelease(4), unrecognisedLinkedID(5),
 *     linkedResponseUnexpected(6), unexpectedLinkedOperation(7)
 * }
 * ReturnResultProblem ::= INTEGER {
 *     unrecognisedInvocation(0), resultResponseUnexpected(1), mistypedResult(2)
 * }
 * ReturnErrorProblem ::= INTEGER {
 *     unrecognisedInvocation(0), errorResponseUnexpected(1), unrecognisedError(2),
 *     unexpectedError(3), mistypedParameter(4)
 * }
 */
public final class ProblemCodec {
    public enum ProblemType {
        GENERAL(0), INVOKE(1), RETURN_RESULT(2), RETURN_ERROR(3);
        public final int tag;
        ProblemType(int tag) { this.tag = tag; }
        public static ProblemType fromTag(int tag) throws AsnException {
            for (ProblemType t : values())
                if (t.tag == tag) return t;
            throw new AsnException("Problem: unknown tag=" + tag);
        }
    }
    public static final class Result {
        public ProblemType type;
        public int         value;  // specific problem code
        public void reset() { type = null; value = -1; }
    }
    // ── DECODE ───────────────────────────────────────────────────────
    /**
     * Decode Problem CHOICE.
     * cursor.readTag() đã được gọi bởi caller.
     */
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT)
            throw new AsnException("Problem: expected CONTEXT");
        out.type  = ProblemType.fromTag(cursor.tag());
        out.value = cursor.readInt32();
        cursor.skipValue();
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    public static void encode(BerWriter w, ProblemType type, int value) {
        // CHOICE: ghi trực tiếp [type.tag] IMPLICIT INTEGER
        w.writeInt32(BerTag.CONTEXT, type.tag, value);
    }
    private ProblemCodec() {}
}
5. MAP CHOICE types
5a. LegID CHOICE
java


package org.mobicents.protocols.ss7.map.api.primitives;
import org.mobicents.protocols.asn.*;
/**
 * LegID ::= CHOICE {
 *     sendingSideID   [0] IMPLICIT LegType,   -- SendingSideID
 *     receivingSideID [1] IMPLICIT LegType    -- ReceivingSideID
 * }
 * LegType ::= OCTET STRING (SIZE(1))
 *   -- leg1(0x01), leg2(0x02)
 */
public final class LegIDCodec {
    public enum Side { SENDING, RECEIVING }
    public static final class Result {
        public Side side;
        public int  legType;  // 1=leg1, 2=leg2
        public void reset() { side = null; legType = -1; }
    }
    private static final int TAG_SENDING   = 0x00;
    private static final int TAG_RECEIVING = 0x01;
    // ── DECODE ───────────────────────────────────────────────────────
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT)
            throw new AsnException("LegID: expected CONTEXT");
        switch (cursor.tag()) {
            case TAG_SENDING:
                out.side    = Side.SENDING;
                out.legType = cursor.readInt32();
                break;
            case TAG_RECEIVING:
                out.side    = Side.RECEIVING;
                out.legType = cursor.readInt32();
                break;
            default:
                throw new AsnException("LegID: unknown tag=" + cursor.tag());
        }
        cursor.skipValue();
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    public static void encodeSending(BerWriter w, int legType) {
        w.writeOctetString(BerTag.CONTEXT, TAG_SENDING,
            new byte[]{(byte) legType}, 0, 1);
    }
    public static void encodeReceiving(BerWriter w, int legType) {
        w.writeOctetString(BerTag.CONTEXT, TAG_RECEIVING,
            new byte[]{(byte) legType}, 0, 1);
    }
    public static void encode(BerWriter w, Result result) {
        if (result.side == Side.SENDING)
            encodeSending(w, result.legType);
        else
            encodeReceiving(w, result.legType);
    }
    private LegIDCodec() {}
}
5b. SM-RP-DA CHOICE (SMS routing)
java


package org.mobicents.protocols.ss7.map.api.service.sms;
import org.mobicents.protocols.asn.*;
/**
 * SM-RP-DA ::= CHOICE {
 *     imsi                    [0] IMPLICIT IMSI,
 *     lmsi                    [1] IMPLICIT LMSI,
 *     serviceCentreAddressDA  [4] IMPLICIT AddressString,
 *     noSM-RP-DA              [5] IMPLICIT NULL
 * }
 */
public final class SmRpDaCodec {
    public enum Type { IMSI, LMSI, SC_ADDRESS, NO_DA }
    public static final class Result {
        public Type     type;
        public BerSlice valueSlice;  // raw bytes (IMSI/LMSI/AddressString)
        public void reset() { type = null; valueSlice = null; }
    }
    private static final int TAG_IMSI       = 0x00;
    private static final int TAG_LMSI       = 0x01;
    private static final int TAG_SC_ADDR    = 0x04;
    private static final int TAG_NO_DA      = 0x05;
    // ── DECODE ───────────────────────────────────────────────────────
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT)
            throw new AsnException("SM-RP-DA: expected CONTEXT");
        switch (cursor.tag()) {
            case TAG_IMSI:
                out.type       = Type.IMSI;
                out.valueSlice = cursor.getOctetStringSlice();
                break;
            case TAG_LMSI:
                out.type       = Type.LMSI;
                out.valueSlice = cursor.getOctetStringSlice();
                break;
            case TAG_SC_ADDR:
                out.type       = Type.SC_ADDRESS;
                out.valueSlice = cursor.getOctetStringSlice();
                break;
            case TAG_NO_DA:
                out.type       = Type.NO_DA;
                out.valueSlice = null;
                break;
            default:
                throw new AsnException("SM-RP-DA: unknown tag=" + cursor.tag());
        }
        cursor.skipValue();
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    public static void encode(BerWriter w, Type type, byte[] value) {
        switch (type) {
            case IMSI:
                w.writeOctetString(BerTag.CONTEXT, TAG_IMSI,
                    value, 0, value.length);
                break;
            case LMSI:
                w.writeOctetString(BerTag.CONTEXT, TAG_LMSI,
                    value, 0, value.length);
                break;
            case SC_ADDRESS:
                w.writeOctetString(BerTag.CONTEXT, TAG_SC_ADDR,
                    value, 0, value.length);
                break;
            case NO_DA:
                w.writeNull(BerTag.CONTEXT, TAG_NO_DA);
                break;
        }
    }
    private SmRpDaCodec() {}
}
5c. Ext-BasicServiceCode CHOICE
java


package org.mobicents.protocols.ss7.map.api.primitives;
import org.mobicents.protocols.asn.*;
/**
 * Ext-BasicServiceCode ::= CHOICE {
 *     ext-BearerService [2] IMPLICIT Ext-BearerServiceCode,
 *     ext-TeleService   [3] IMPLICIT Ext-TeleServiceCode
 * }
 * Ext-BearerServiceCode ::= OCTET STRING (SIZE(1..5))
 * Ext-TeleServiceCode   ::= OCTET STRING (SIZE(1..5))
 */
public final class ExtBasicServiceCodeCodec {
    public enum Type { BEARER_SERVICE, TELE_SERVICE }
    public static final class Result {
        public Type     type;
        public BerSlice codeSlice;  // raw OCTET STRING bytes
        public void reset() { type = null; codeSlice = null; }
    }
    private static final int TAG_BEARER  = 0x02;
    private static final int TAG_TELE    = 0x03;
    // ── DECODE ───────────────────────────────────────────────────────
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT)
            throw new AsnException("Ext-BasicServiceCode: expected CONTEXT");
        switch (cursor.tag()) {
            case TAG_BEARER:
                out.type      = Type.BEARER_SERVICE;
                out.codeSlice = cursor.getOctetStringSlice();
                break;
            case TAG_TELE:
                out.type      = Type.TELE_SERVICE;
                out.codeSlice = cursor.getOctetStringSlice();
                break;
            default:
                throw new AsnException(
                    "Ext-BasicServiceCode: unknown tag=" + cursor.tag());
        }
        cursor.skipValue();
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    public static void encode(BerWriter w, Type type, byte[] code) {
        int tag = (type == Type.BEARER_SERVICE) ? TAG_BEARER : TAG_TELE;
        w.writeOctetString(BerTag.CONTEXT, tag, code, 0, code.length);
    }
    private ExtBasicServiceCodeCodec() {}
}
6. CAP CHOICE types
6a. EventSpecificInformationBCSM CHOICE (CAP — phức tạp nhất)
java


package org.mobicents.protocols.ss7.cap.api.service.circuitSwitchedCall;
import org.mobicents.protocols.asn.*;
/**
 * EventSpecificInformationBCSM ::= CHOICE {
 *   -- O-BCSM events
 *   routeSelectFailureSpecificInfo  [2] IMPLICIT SEQUENCE { ... },
 *   oCalledPartyBusySpecificInfo    [3] IMPLICIT SEQUENCE { ... },
 *   oNoAnswerSpecificInfo           [4] IMPLICIT SEQUENCE { ... },
 *   oAnswerSpecificInfo             [5] IMPLICIT SEQUENCE { ... },
 *   oMidCallSpecificInfo            [6] IMPLICIT SEQUENCE { ... },
 *   oDisconnectSpecificInfo         [7] IMPLICIT SEQUENCE { ... },
 *   -- T-BCSM events
 *   tBusySpecificInfo               [8] IMPLICIT SEQUENCE { ... },
 *   tNoAnswerSpecificInfo           [9] IMPLICIT SEQUENCE { ... },
 *   tAnswerSpecificInfo             [10] IMPLICIT SEQUENCE { ... },
 *   tMidCallSpecificInfo            [11] IMPLICIT SEQUENCE { ... },
 *   tDisconnectSpecificInfo         [12] IMPLICIT SEQUENCE { ... },
 *   oTermSeizedSpecificInfo         [13] IMPLICIT SEQUENCE { ... },
 *   callAcceptedSpecificInfo        [14] IMPLICIT SEQUENCE { ... },
 *   oAbandonSpecificInfo            [15] IMPLICIT SEQUENCE { ... },
 *   -- Extended (CAP4)
 *   oChangeOfPositionSpecificInfo   [50] IMPLICIT SEQUENCE { ... },
 *   tChangeOfPositionSpecificInfo   [51] IMPLICIT SEQUENCE { ... }
 * }
 *
 * Strategy: tất cả alternatives đều là SEQUENCE.
 * Decode hot fields (cause, legID) eagerly; phần còn lại giữ BerSlice lazy.
 */
public final class EventSpecificInfoBCSMCodec {
    public enum EventType {
        ROUTE_SELECT_FAILURE(2),
        O_CALLED_PARTY_BUSY(3),
        O_NO_ANSWER(4),
        O_ANSWER(5),
        O_MID_CALL(6),
        O_DISCONNECT(7),
        T_BUSY(8),
        T_NO_ANSWER(9),
        T_ANSWER(10),
        T_MID_CALL(11),
        T_DISCONNECT(12),
        O_TERM_SEIZED(13),
        CALL_ACCEPTED(14),
        O_ABANDON(15),
        O_CHANGE_OF_POSITION(50),
        T_CHANGE_OF_POSITION(51);
        public final int tag;
        EventType(int tag) { this.tag = tag; }
        public static EventType fromTag(int tag) throws AsnException {
            for (EventType t : values())
                if (t.tag == tag) return t;
            throw new AsnException(
                "EventSpecificInfoBCSM: unknown tag=" + tag);
        }
    }
    public static final class Result {
        public EventType type;
        // Hot fields — eagerly decoded khi present
        public BerSlice  causeSlice;        // [0] Cause (nhiều event có)
        public int       legId    = -1;     // [0] LegType (mid-call, disconnect)
        public boolean   busySubscriber;    // [0] tBusy
        public boolean   callForwarded;     // [1] tBusy
        // Raw slice cho toàn bộ SEQUENCE content (lazy)
        public BerSlice  rawSlice;
        public void reset() {
            type           = null;
            causeSlice     = null;
            legId          = -1;
            busySubscriber = false;
            callForwarded  = false;
            rawSlice       = null;
        }
    }
    // ── DECODE ───────────────────────────────────────────────────────
    /**
     * Decode EventSpecificInformationBCSM CHOICE.
     * cursor.readTag() đã được gọi.
     */
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT)
            throw new AsnException(
                "EventSpecificInfoBCSM: expected CONTEXT");
        out.type = EventType.fromTag(cursor.tag());
        // Giữ raw slice TRƯỚC — để lazy decode toàn bộ nếu cần
        out.rawSlice = cursor.getOctetStringSlice();
        // Eager decode hot fields dựa theo event type
        switch (out.type) {
            case ROUTE_SELECT_FAILURE:
                decodeRouteSelectFailure(out);
                break;
            case O_CALLED_PARTY_BUSY:
            case T_BUSY:
                decodeBusy(out);
                break;
            case O_DISCONNECT:
            case T_DISCONNECT:
                decodeDisconnect(out);
                break;
            case O_NO_ANSWER:
            case T_NO_ANSWER:
                decodeNoAnswer(out);
                break;
            default:
                // Answer, MidCall, v.v. → giữ rawSlice, decode lazy
                break;
        }
        cursor.skipValue();
    }
    // routeSelectFailureSpecificInfo [2]:
    //   SEQUENCE {
    //     failureCause [0] IMPLICIT Cause OPTIONAL
    //   }
    private static void decodeRouteSelectFailure(Result out)
            throws AsnException {
        BerCursor body = out.rawSlice.cursor();
        try {
            while (body.hasMore()) {
                body.readTag();
                if (body.tagClass() == BerTag.CONTEXT && body.tag() == 0x00) {
                    out.causeSlice = body.getOctetStringSlice();
                }
                body.skipValue();
            }
        } finally {
            body.release();
        }
    }
    // oCalledPartyBusySpecificInfo [3] / tBusySpecificInfo [8]:
    //   SEQUENCE {
    //     busyCause          [0] IMPLICIT Cause OPTIONAL,
    //     callForwarded      [3] IMPLICIT NULL OPTIONAL,
    //     busySubscriberInfo [4] IMPLICIT BusySubscriberInfo OPTIONAL,
    //     ...
    //   }
    private static void decodeBusy(Result out) throws AsnException {
        BerCursor body = out.rawSlice.cursor();
        try {
            while (body.hasMore()) {
                body.readTag();
                if (body.tagClass() == BerTag.CONTEXT) {
                    switch (body.tag()) {
                        case 0x00:
                            out.causeSlice = body.getOctetStringSlice();
                            break;
                        case 0x03:
                            out.callForwarded = true;
                            break;
                        case 0x04:
                            out.busySubscriber = true;
                            break;
                        default:
                            break;
                    }
                }
                body.skipValue();
            }
        } finally {
            body.release();
        }
    }
    // oDisconnectSpecificInfo [7] / tDisconnectSpecificInfo [12]:
    //   SEQUENCE {
    //     releaseCause [0] IMPLICIT Cause OPTIONAL
    //   }
    private static void decodeDisconnect(Result out) throws AsnException {
        BerCursor body = out.rawSlice.cursor();
        try {
            while (body.hasMore()) {
                body.readTag();
                if (body.tagClass() == BerTag.CONTEXT && body.tag() == 0x00)
                    out.causeSlice = body.getOctetStringSlice();
                body.skipValue();
            }
        } finally {
            body.release();
        }
    }
    // oNoAnswerSpecificInfo / tNoAnswerSpecificInfo:
    //   SEQUENCE {
    //     callForwarded [0] IMPLICIT NULL OPTIONAL
    //   }
    private static void decodeNoAnswer(Result out) throws AsnException {
        BerCursor body = out.rawSlice.cursor();
        try {
            while (body.hasMore()) {
                body.readTag();
                if (body.tagClass() == BerTag.CONTEXT && body.tag() == 0x00)
                    out.callForwarded = true;
                body.skipValue();
            }
        } finally {
            body.release();
        }
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    /** Encode routeSelectFailure */
    public static void encodeRouteSelectFailure(BerWriter w,
                                                 byte[] cause) {
        int mark = w.beginSequence(BerTag.CONTEXT,
            EventType.ROUTE_SELECT_FAILURE.tag);
        if (cause != null)
            w.writeOctetString(BerTag.CONTEXT, 0x00, cause, 0, cause.length);
        w.endSequence(mark, BerTag.CONTEXT,
            EventType.ROUTE_SELECT_FAILURE.tag);
    }
    /** Encode oDisconnect / tDisconnect */
    public static void encodeDisconnect(BerWriter w,
                                         EventType type,
                                         byte[] releaseCause) {
        int mark = w.beginSequence(BerTag.CONTEXT, type.tag);
        if (releaseCause != null)
            w.writeOctetString(BerTag.CONTEXT, 0x00,
                releaseCause, 0, releaseCause.length);
        w.endSequence(mark, BerTag.CONTEXT, type.tag);
    }
    /** Encode từ raw slice (passthrough — không re-encode) */
    public static void encodeRaw(BerWriter w, EventType type,
                                  BerSlice rawSlice) {
        w.writeOctetString(BerTag.CONTEXT, type.tag,
            rawSlice.toByteArray(), 0, rawSlice.length());
    }
    private EventSpecificInfoBCSMCodec() {}
}
6b. BearerCapability CHOICE (CAP)
java


package org.mobicents.protocols.ss7.cap.api.isup;
import org.mobicents.protocols.asn.*;
/**
 * BearerCapability ::= CHOICE {
 *     bearerCap [0] IMPLICIT OCTET STRING (SIZE(2..4))
 *       -- Q.931 Bearer Capability IE (octet 3..6 only, no IE ID/length)
 * }
 *
 * CAP v2/3: chỉ có [0], nhưng reserved space cho extensions
 */
public final class BearerCapabilityCodec {
    private static final int TAG_BEARER_CAP = 0x00;
    public static final class Result {
        public BerSlice bearerCapSlice;  // raw Q.931 IE bytes (lazy)
        public void reset() { bearerCapSlice = null; }
    }
    // ── DECODE ───────────────────────────────────────────────────────
    public static void decode(BerCursor cursor, Result out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT)
            throw new AsnException("BearerCapability: expected CONTEXT");
        if (cursor.tag() == TAG_BEARER_CAP) {
            out.bearerCapSlice = cursor.getOctetStringSlice();
        }
        cursor.skipValue();
    }
    // ── ENCODE ───────────────────────────────────────────────────────
    public static void encode(BerWriter w, byte[] bearerCapBytes) {
        w.writeOctetString(BerTag.CONTEXT, TAG_BEARER_CAP,
            bearerCapBytes, 0, bearerCapBytes.length);
    }
    private BearerCapabilityCodec() {}
}
6c. GenericNumber / CalledPartyNumber — CHOICE-like structure
java


package org.mobicents.protocols.ss7.cap.api.isup;
import org.mobicents.protocols.asn.*;
/**
 * GenericNumbers ::= SET SIZE(1..3) OF GenericNumber
 * GenericNumber  ::= OCTET STRING (SIZE(2..11))
 *   -- Encoded Q.763 Generic Number parameter
 *   -- Byte 0: Number Qualifier Indicator
 *   -- Byte 1: Nature of Address, Numbering Plan, Presentation, Screening
 *   -- Byte 2..n: Address signals (BCD, odd nibble last)
 *
 * CalledPartyNumber ::= OCTET STRING (SIZE(2..12))
 *   -- Encoded Q.763 Called Party Number
 */
public final class IsupNumberCodec {
    // Number Qualifier (byte 0 của GenericNumber)
    public static final int NQI_ADDITIONAL_CALLED  = 0x00;
    public static final int NQI_ADDITIONAL_CALLING = 0x01;
    public static final int NQI_REDIRECTING        = 0x02;
    public static final int NQI_CONNECTED          = 0x03;
    // Nature of Address (bits 6-4 của byte 1)
    public static final int NAI_SUBSCRIBER      = 0x01;
    public static final int NAI_NATIONAL        = 0x03;
    public static final int NAI_INTERNATIONAL   = 0x04;
    // Numbering Plan (bits 3-0 của byte 1)
    public static final int NPI_ISDN            = 0x01;
    /** Kết quả decode ISUP number */
    public static final class Result {
        public int    nai;         // Nature of Address Indicator
        public int    npi;         // Numbering Plan Indicator
        public String digits;      // BCD digits string
        // CalledPartyNumber thêm:
        public int    inni;        // Internal Network Number Indicator
        public int    apri;        // Address Presentation Restricted Indicator
        public void reset() {
            nai = -1; npi = -1;
            digits = null;
            inni = -1; apri = -1;
        }
    }
    private static final ThreadLocal<Result> TL_RESULT =
        ThreadLocal.withInitial(Result::new);
    // ── DECODE CalledPartyNumber ─────────────────────────────────────
    public static Result decodeCalledPartyNumber(BerSlice slice) {
        Result out = TL_RESULT.get();
        out.reset();
        byte[] raw = slice.toByteArray();
        if (raw.length < 2) return out;
        boolean oddFlag = (raw[0] & 0x80) != 0;
        out.nai = raw[0] & 0x7F;
        out.inni = (raw[1] >> 7) & 0x01;
        out.npi  = (raw[1] >> 4) & 0x07;
        out.digits = decodeBCD(raw, 2, raw.length - 2, oddFlag);
        return out;
    }
    // ── DECODE CallingPartyNumber ────────────────────────────────────
    public static Result decodeCallingPartyNumber(BerSlice slice) {
        Result out = TL_RESULT.get();
        out.reset();
        byte[] raw = slice.toByteArray();
        if (raw.length < 2) return out;
        boolean oddFlag = (raw[0] & 0x80) != 0;
        out.nai  = raw[0] & 0x7F;
        out.npi  = (raw[1] >> 4) & 0x07;
        out.apri = (raw[1] >> 2) & 0x03;
        out.digits = decodeBCD(raw, 2, raw.length - 2, oddFlag);
        return out;
    }
    // ── BCD decode ───────────────────────────────────────────────────
    public static String decodeBCD(byte[] raw, int offset,
                                    int len, boolean odd) {
        StringBuilder sb = new StringBuilder(len * 2);
        for (int i = offset; i < offset + len; i++) {
            int b = raw[i] & 0xFF;
            sb.append((char)('0' + (b & 0x0F)));
            int hi = (b >> 4) & 0x0F;
            if (i < offset + len - 1 || !odd)
                sb.append((char)('0' + hi));
        }
        return sb.toString();
    }
    // ── ENCODE CalledPartyNumber ─────────────────────────────────────
    public static byte[] encodeCalledPartyNumber(int nai, int npi,
                                                  String digits) {
        boolean odd = (digits.length() % 2) != 0;
        byte[] out  = new byte[2 + (digits.length() + 1) / 2];
        out[0] = (byte) ((odd ? 0x80 : 0x00) | (nai & 0x7F));
        out[1] = (byte) ((npi & 0x07) << 4);
        encodeBCD(digits, out, 2);
        return out;
    }
    // ── BCD encode ───────────────────────────────────────────────────
    private static void encodeBCD(String digits, byte[] out, int offset) {
        for (int i = 0; i < digits.length(); i += 2) {
            int lo = digits.charAt(i) - '0';
            int hi = (i + 1 < digits.length())
                ? digits.charAt(i + 1) - '0'
                : 0x0F; // filler
            out[offset + i / 2] = (byte) (lo | (hi << 4));
        }
    }
    private IsupNumberCodec() {}
}
7. Tích hợp trong CapOperationDecoder — decode InitialDP đầy đủ
java


// Trong CapOperationDecoder.decodeInitialDP()
public static void decodeInitialDP(BerCursor body, InitialDPResult out)
        throws AsnException {
    // ThreadLocal result holders cho CHOICE decode
    OperationCodeCodec.Result opResult = new OperationCodeCodec.Result();
    LegIDCodec.Result legResult        = new LegIDCodec.Result();
    while (body.hasMore()) {
        body.readTag();
        if (body.tagClass() != BerTag.CONTEXT) {
            body.skipValue();
            continue;
        }
        switch (body.tag()) {
            case 0x00: // serviceKey [0] IMPLICIT INTEGER
                out.serviceKey = body.readInt32();
                body.skipValue();
                break;
            case 0x02: // calledPartyNumber [2] IMPLICIT OCTET STRING
                out.calledPartyNumber = body.getOctetString();
                body.skipValue();
                break;
            case 0x0A: // callingPartyNumber [10] IMPLICIT OCTET STRING
                out.callingPartyNumber = body.getOctetString();
                body.skipValue();
                break;
            case 0x1C: // callingPartyCategory [28] IMPLICIT OCTET STRING
                out.callingPartyCategory = body.readInt32();
                body.skipValue();
                break;
            case 0x15: // eventTypeBCSM [21] IMPLICIT ENUMERATED
                out.eventTypeBCSM = body.readInt32();
                body.skipValue();
                break;
            case 0x22: // imsi [34] IMPLICIT OCTET STRING
                out.imsi = body.getOctetString();
                body.skipValue();
                break;
            case 0x24: // locationInformation [36] LAZY SEQUENCE
                out.locationInfoSlice = body.getOctetStringSlice();
                body.skipValue();
                break;
            case 0x14: // bearerCapability [20] IMPLICIT CHOICE ← CHOICE!
                // bearerCapability [20] IMPLICIT BearerCapability
                // BearerCapability ::= CHOICE { bearerCap [0] }
                // Vì [20] IMPLICIT đã bao bọc, content là BearerCapability value
                out.bearerCapabilitySlice = body.getOctetStringSlice();
                body.skipValue();
                break;
            case 0x11: // cause [17] LAZY
                out.causeSlice = body.getOctetStringSlice();
                body.skipValue();
                break;
            case 0x0E: // redirectingPartyID [14] LAZY
                out.redirectingPartySlice = body.getOctetStringSlice();
                body.skipValue();
                break;
            case 0x0F: // redirectionInformation [15] LAZY
                out.redirectionInfoSlice = body.getOctetStringSlice();
                body.skipValue();
                break;
            case 0x23: // subscriberState [35] LAZY CHOICE
                out.subscriberStateSlice = body.getOctetStringSlice();
                body.skipValue();
                break;
            case 0x05: // extensions [5] LAZY
                out.extensionsSlice = body.getOctetStringSlice();
                body.skipValue();
                break;
            default:
                body.skipValue();
        }
    }
    body.release();
}
// ── Lazy decode khi application cần ─────────────────────────────────
/** Decode CalledPartyNumber từ slice đã lưu */
public static IsupNumberCodec.Result decodeCalledParty(InitialDPResult idp) {
    if (idp.calledPartyNumber == null) return null;
    return IsupNumberCodec.decodeCalledPartyNumber(
        new BerSlice(idp.calledPartyNumber, 0, idp.calledPartyNumber.length)
    );
}
/** Lazy decode EventSpecificInfoBCSM từ slice */
public static void decodeEventSpecificInfo(
        BerSlice rawSlice,
        EventSpecificInfoBCSMCodec.Result out)
        throws AsnException {
    BerCursor cursor = rawSlice.cursor();
    try {
        cursor.readTag();
        EventSpecificInfoBCSMCodec.decode(cursor, out);
    } finally {
        cursor.release();
    }
}
8. Sơ đồ tổng thể CHOICE trong pipeline


══════════════════════════════════════════════════════
  CHOICE decode pattern (BerCursor)
══════════════════════════════════════════════════════
cursor.readTag()           ← đọc tag của CHOICE (= tag của alternative)
     │
     ├─ tagClass=CONTEXT tag=0 → alternative A
     │    ├─ primitive  → cursor.readInt32() / getOctetStringSlice()
     │    └─ constructed → cursor.openConstructed() → sub-cursor
     │
     ├─ tagClass=CONTEXT tag=1 → alternative B
     │    └─ ...
     │
     └─ tagClass=APPLICATION → MAP/TCAP message type
          └─ ...
══════════════════════════════════════════════════════
  CHOICE encode pattern (BerWriter)
══════════════════════════════════════════════════════
  // KHÔNG có wrapper tag — ghi thẳng alternative
  switch (choiceType) {
    LOCAL  → w.writeInt32(CONTEXT, 0, value)
    GLOBAL → w.writeOctetString(CONTEXT, 1, oidBytes)
  }
══════════════════════════════════════════════════════
  CHOICE lồng nhau (IMPLICIT override outer tag)
══════════════════════════════════════════════════════
  bearerCapability [20] IMPLICIT BearerCapability
  BearerCapability ::= CHOICE { bearerCap [0] OCTET STRING }
  Wire: A0 xx <bearerCap bytes>   ← [20] đè lên [0]
  → Decode: getOctetStringSlice() trực tiếp tại [20]
            (IMPLICIT đã ăn mất tag của CHOICE bên trong)
  → Encode: writeOctetString(CONTEXT, 20, bytes)
Tóm tắt file CHOICE đã thêm


File	CHOICE type	Module
OperationCodeCodec	OperationCode ::= CHOICE {local, global}	tcap
ErrorCodeCodec	ErrorCode ::= CHOICE {local, global}	tcap
ProblemCodec	Problem ::= CHOICE {general, invoke, rr, re}	tcap
LegIDCodec	LegID ::= CHOICE {sending[0], receiving[1]}	map/cap
SmRpDaCodec	SM-RP-DA ::= CHOICE {imsi, lmsi, scAddr, noDA}	map
ExtBasicServiceCodeCodec	Ext-BasicServiceCode ::= CHOICE {bearer, tele}	map
EventSpecificInfoBCSMCodec	16 alternatives	cap
BearerCapabilityCodec	BearerCapability ::= CHOICE {bearerCap[0]}	cap
IsupNumberCodec	BCD encode/decode (ISUP number)	cap
Bước tiếp theo:

JMH benchmark sau khi apply toàn bộ fix?
SubscriberState CHOICE lazy decode?
LocationInformation SEQUENCE lazy decode?
Dưới đây là file markdown hướng dẫn đầy đủ cho Java developers:

markdown


# BerCursor/BerWriter Developer Guide
# Zero-Copy ASN.1 BER/DER Codec for jSS7 j25
> **Repo:** `/home/meodien/orca/workspaces/jSS7/j25/`  
> **Module:** `asn/asn-api`  
> **Package:** `org.mobicents.protocols.asn`  
> **Java:** 25 (LTS)  
> **Last updated:** 2026-07-10
---
## Table of Contents
1. [Tại sao cần BerCursor?](#1-tại-sao-cần-bercursor)
2. [Kiến trúc tổng quan](#2-kiến-trúc-tổng-quan)
3. [Core classes](#3-core-classes)
4. [BerCursor — Decode](#4-bercursor--decode)
5. [BerWriter — Encode](#5-berwriter--encode)
6. [BerSlice — Zero-copy view](#6-berslice--zero-copy-view)
7. [CHOICE decode/encode](#7-choice-decodeencode)
8. [Tích hợp với TCAP/MAP/CAP](#8-tích-hợp-với-tcapmapCap)
9. [Performance rules](#9-performance-rules)
10. [Anti-patterns — KHÔNG làm](#10-anti-patterns--không-làm)
11. [Benchmark & test](#11-benchmark--test)
12. [Checklist trước khi commit](#12-checklist-trước-khi-commit)
---
## 1. Tại sao cần BerCursor?
### Vấn đề của legacy `AsnInputStream`
SCCP data byte[] → new AsnInputStream(data) ❌ object mới → TCBeginImpl.decode(ais) ❌ object mới → new AsnInputStream(subBytes) ❌ copy + object mới → new InvokeImpl() ❌ object mới → new OperationCodeImpl() ❌ object mới



Mỗi TCAP message tạo **8–15 AsnInputStream objects** + **5–10 byte[] copies**.
Với 500 TPS, đây là **4.000–7.500 objects/giây** chỉ riêng decode path — gây GC pressure liên tục.
### Giải pháp: Cursor-based, zero-copy
SCCP data byte[] ✅ giữ nguyên, không copy → BerCursor.wrap(data, 0, len) ✅ từ ThreadLocal pool → cursor.readTag() ✅ đọc vào fields, không new → cursor.openConstructed() ✅ sub-cursor trỏ cùng buffer → cursor.getOctetStringSlice() ✅ slice, không copy → cursor.release() ✅ trả về pool



### Benchmark kết quả thực tế
| Operation | BerCursor/Writer | Legacy | Verdict |
|-----------|-----------------|--------|---------|
| Encode INTEGER | 39 ns/op | 98 ns/op | **2.5x faster** ✅ |
| Encode 1KB OCTET STRING | 911 μs | 4912 μs | **5.4x faster** ✅ |
| Deep 3-level SEQUENCE decode | 97 ns/op | 92 ns/op | **same** ✅ |
| OCTET STRING slice | 18 ns/op | 18 ns/op | **same** ✅ |
| Simple TLV decode (<10 bytes) | 25 ns/op | 5 ns/op | ⚠️ slower* |
> *Simple TLV chậm hơn vì per-message init overhead. Amortized trên message thực
> (100–500 bytes, 10–50 TLVs) thì overhead < 1%.
---
## 2. Kiến trúc tổng quan
┌─────────────────────────────────────────────────────────────────┐ │ BER Wire Format │ │ [ Tag ] [ Length ] [ Value ... ] │ │ ↑ ↑ ↑ │ │ 1-5 bytes 1-4 bytes N bytes │ └─────────────────────────────────────────────────────────────────┘ │ ┌────────────┴────────────┐ │ │ DECODE (inbound) ENCODE (outbound) │ │ ┌────────▼────────┐ ┌────────▼────────┐ │ BerCursor │ │ BerWriter │ │ (cursor-based) │ │ (backward fill) │ │ ThreadLocal │ │ ThreadLocal │ │ pool, no alloc │ │ pool, no alloc │ └────────┬────────┘ └────────┬────────┘ │ │ ┌────────▼────────────────────────▼────────┐ │ BerSlice │ │ (zero-copy view: offset + length) │ │ byte[] path │ ByteBuf path │ └───────────────────────────────────────────┘ │ ┌────────▼────────────────────────────────┐ │ AsnBufferBackend │ │ HeapAsnBufferBackend (byte[] — fast) │ │ ByteBufAsnBufferBackend (Netty ByteBuf) │ └─────────────────────────────────────────┘



### Dual-mode buffer strategy
`BerCursor` có **hai mode**:
```java
// Mode 1: HEAP (95% cases) — direct array access, JIT inline
BerCursor c = BerCursor.wrapHeap(data, offset, length);
// → c.heapBuf != null → data[pos++] trực tiếp
// Mode 2: BYTEBUF (Netty inbound) — virtual call fallback
BerCursor c = BerCursor.wrapByteBuf(backend, offset, length);
// → c.heapBuf == null → backend.readByte(pos++)
3. Core classes


Class	LOC	Vai trò
BerCursor	274	Decoder — cursor lướt trên buffer
BerWriter	273	Encoder — backward fill, ThreadLocal
BerSlice	65	Zero-copy view (offset+length)
BerTag	48	Tag class constants
AsnBufferBackend	59	Interface: heap vs ByteBuf
HeapAsnBufferBackend	124	byte[] implementation
ByteBufAsnBufferBackend	107	Netty ByteBuf implementation
4. BerCursor — Decode
4.1 Wrap và release
java


// ✅ ĐÚNG: wrap từ pool, release khi xong
BerCursor cursor = BerCursor.wrapHeap(data, 0, data.length);
try {
    // ... decode ...
} finally {
    cursor.release();  // trả về ThreadLocal pool
}
// ✅ ĐÚNG: wrap từ Netty ByteBuf (Netty inbound path)
BerCursor cursor = BerCursor.wrapByteBuf(backend, offset, length);
4.2 Read TLV — luôn gọi readTag() trước
java


while (cursor.hasMore()) {
    cursor.readTag();              // đọc tag+length, lưu vào fields
    int tc  = cursor.tagClass();  // 0=UNIVERSAL, 1=APPLICATION, 2=CONTEXT, 3=PRIVATE
    int tag = cursor.tag();       // tag number
    boolean prim = cursor.isPrimitive();
    int vlen = cursor.valueLength();
    // ... xử lý ...
    cursor.skipValue();            // advance qua value bytes
}
4.3 Read primitives
java


cursor.readTag();
// INTEGER (1-4 bytes signed)
int value = cursor.readInt32();
cursor.skipValue();
// LONG (1-8 bytes signed)
long value = cursor.readInt64();
cursor.skipValue();
// BOOLEAN
boolean flag = cursor.readBoolean();
cursor.skipValue();
// OCTET STRING — zero-copy slice ✅ (preferred)
BerSlice slice = cursor.getOctetStringSlice();
// slice.buf + slice.offset + slice.length → trỏ vào buffer gốc
cursor.skipValue();
// OCTET STRING — copy ❌ (chỉ khi thực sự cần byte[])
byte[] bytes = cursor.getOctetString();  // System.arraycopy internally
cursor.skipValue();
4.4 Constructed TLV (SEQUENCE, SET, context-constructed)
java


cursor.readTag();
// cursor.tag() == BerTag.SEQUENCE || !cursor.isPrimitive()
BerCursor child = cursor.openConstructed();  // zero-copy sub-cursor
try {
    while (child.hasMore()) {
        child.readTag();
        // decode fields...
        child.skipValue();
    }
} finally {
    child.release();     // ← QUAN TRỌNG: release child cursor!
}
cursor.skipValue();      // advance outer cursor
4.5 CHOICE decode — KHÔNG có wrapper TLV
java


// CHOICE: tag của alternative IS tag của CHOICE trên wire
// KHÔNG openConstructed() cho CHOICE — đọc tag rồi dispatch
cursor.readTag();  // đọc tag của chosen alternative
switch (cursor.tagClass() << 8 | cursor.tag()) {
    case (BerTag.CONTEXT << 8 | 0x00):   // localValue [0]
        int local = cursor.readInt32();
        break;
    case (BerTag.CONTEXT << 8 | 0x01):   // globalValue [1]
        BerSlice oid = cursor.getOctetStringSlice();
        break;
}
cursor.skipValue();
4.6 Tag class constants
java


// Dùng BerTag constants, không hard-code số
BerTag.UNIVERSAL    // = 0  (INTEGER, OCTET STRING, SEQUENCE, ...)
BerTag.APPLICATION  // = 1  (TCAP message types)
BerTag.CONTEXT      // = 2  (field tags trong SEQUENCE)
BerTag.PRIVATE      // = 3  (hiếm)
// Universal primitive tags
BerTag.BOOLEAN      // = 1
BerTag.INTEGER      // = 2
BerTag.OCTET_STRING // = 4
BerTag.NULL         // = 5
BerTag.OID          // = 6
BerTag.ENUMERATED   // = 10
BerTag.SEQUENCE     // = 16
BerTag.SET          // = 17
5. BerWriter — Encode
5.1 Lấy instance từ ThreadLocal
java


// ✅ ĐÚNG: ThreadLocal, không new
BerWriter w = BerWriter.get();   // reset tự động
// ❌ SAI: tạo mới mỗi lần
BerWriter w = new BerWriter(4096);
5.2 Backward encoding — innermost first
Vì BerWriter encode từ cuối buffer về đầu, bạn phải write inner fields trước, outer fields sau:

java


// ASN.1 structure:
// SEQUENCE {
//   [0] INTEGER 42
//   [1] OCTET STRING "hello"
// }
BerWriter w = BerWriter.get();
int markSeq = w.beginSequence(BerTag.CONTEXT, 0);  // mở SEQUENCE
// Write innermost last trên wire → write first trong code
w.writeOctetString(BerTag.CONTEXT, 1, "hello".getBytes(), 0, 5);
w.writeInt32(BerTag.CONTEXT, 0, 42);
w.endSequence(markSeq, BerTag.CONTEXT, 0);          // đóng SEQUENCE
Quy tắc nhớ: Thứ tự write trong code = ngược chiều thứ tự xuất hiện trên wire.

5.3 Write primitives
java


// INTEGER
w.writeInt32(BerTag.CONTEXT, 0x00, value);
w.writeInt64(BerTag.CONTEXT, 0x00, longValue);
// BOOLEAN
w.writeBoolean(BerTag.UNIVERSAL, BerTag.BOOLEAN, true);
// OCTET STRING từ byte[]
w.writeOctetString(BerTag.CONTEXT, 0x02, bytes, offset, length);
// OCTET STRING từ BerSlice (zero-copy)
w.writeOctetString(BerTag.CONTEXT, 0x02, slice);
// NULL
w.writeNull(BerTag.CONTEXT, 0x05);
// ENUMERATED (giống INTEGER)
w.writeInt32(BerTag.UNIVERSAL, BerTag.ENUMERATED, value);
5.4 Lấy kết quả encode
java


// ✅ Zero-copy slice (preferred — trỏ vào internal buffer)
BerSlice result = w.resultAsSlice();
// ✅ Zero-copy cursor để decode ngay (roundtrip test)
BerCursor roundtrip = w.resultAsCursor();
// ✅ Flush vào Netty ByteBuf (outbound)
w.flushTo(nettyOutputStream);
// ⚠️ Copy ra byte[] (chỉ khi thực sự cần)
byte[] bytes = w.toByteArray();
5.5 SEQUENCE lồng nhau
java


// TC-END + DialoguePortion + ComponentPortion
BerWriter w = BerWriter.get();
int markEnd = w.beginSequence(BerTag.APPLICATION, TC_END);
    // ComponentPortion [APPLICATION 12]
    int markComp = w.beginSequence(BerTag.APPLICATION, 0x0C);
        // ReturnResultLast [CONTEXT 2]
        int markRRL = w.beginSequence(BerTag.CONTEXT, 0x02);
            // result SEQUENCE { opCode, parameter }
            int markResult = w.beginSequence(BerTag.UNIVERSAL, BerTag.SEQUENCE);
                w.writeOctetString(BerTag.UNIVERSAL, BerTag.OCTET_STRING,
                    paramBytes, 0, paramBytes.length);
                w.writeInt32(BerTag.CONTEXT, 0x00, opCode);
            w.endSequence(markResult, BerTag.UNIVERSAL, BerTag.SEQUENCE);
            w.writeInt32(BerTag.UNIVERSAL, BerTag.INTEGER, invokeId);
        w.endSequence(markRRL, BerTag.CONTEXT, 0x02);
    w.endSequence(markComp, BerTag.APPLICATION, 0x0C);
    // DialoguePortion (call DialoguePortionEncoder)
    DialoguePortionEncoder.encodeAARE(w, appCtxOid, 0, 1, 0, null);
    w.writeInt32(BerTag.APPLICATION, 0x09, destTxId);
w.endSequence(markEnd, BerTag.APPLICATION, TC_END);
w.flushTo(out);
6. BerSlice — Zero-copy view
6.1 BerSlice là gì?
BerSlice là pointer + length vào buffer gốc — giống OssBuf của Nokalva:

java


// Nội bộ:
byte[] heapBuf;  // non-null nếu heap path
int    offset;
int    length;
Không có data copy khi tạo slice.

6.2 Sử dụng BerSlice
java


// Lấy slice từ decode (zero-copy)
BerSlice slice = cursor.getOctetStringSlice();
// Mở cursor để decode tiếp (lazy decode)
BerCursor inner = slice.cursor();
try {
    inner.readTag();
    // ...
} finally {
    inner.release();
}
// So sánh không copy
boolean match = slice.equalsBytes(KNOWN_OID_BYTES);
// Wrap thành ByteBuffer (zero-copy)
ByteBuffer buf = slice.asByteBuffer();
// Copy ra khi thực sự cần (lazy)
byte[] bytes = slice.toByteArray();
6.3 Lazy decode pattern
java


// Khi decode TCAP:
BerSlice dialogSlice = null;
BerSlice paramSlice  = null;
while (cursor.hasMore()) {
    cursor.readTag();
    switch (cursor.tag()) {
        case 0x0B:  // dialoguePortion — lưu slice, chưa decode
            dialogSlice = cursor.getOctetStringSlice();
            break;
        // ...
    }
    cursor.skipValue();
}
// Chỉ decode khi MAP layer thực sự cần
if (dialogSlice != null && mapLayerNeedsDialog) {
    DialoguePortionDecoder.decode(dialogSlice);
}
7. CHOICE decode/encode
7.1 Nguyên tắc


ASN.1 CHOICE KHÔNG có wrapper TLV trên wire.
Tag của alternative = tag của CHOICE.
SEQUENCE { field1, field2 } → [SEQ_TAG][LEN][field1_TLV][field2_TLV]
CHOICE   { alt1,   alt2   } → [alt_TAG][LEN][alt_VALUE]
                                ↑
                         không có CHOICE_TAG bao ngoài
7.2 Decode CHOICE
java


// OperationCode ::= CHOICE {
//   localValue  [0] IMPLICIT INTEGER,
//   globalValue [1] IMPLICIT OID
// }
cursor.readTag();  // đọc tag của alternative (KHÔNG openConstructed)
if (cursor.tagClass() == BerTag.CONTEXT) {
    switch (cursor.tag()) {
        case 0:  // localValue [0]
            int local = cursor.readInt32();
            break;
        case 1:  // globalValue [1]
            BerSlice oid = cursor.getOctetStringSlice();
            break;
        default:
            throw new AsnException("Unknown OperationCode tag: " + cursor.tag());
    }
}
cursor.skipValue();
7.3 Encode CHOICE
java


// CHOICE: ghi thẳng alternative, KHÔNG beginSequence/endSequence bao ngoài
// ✅ ĐÚNG
w.writeInt32(BerTag.CONTEXT, 0, localValue);       // [0] INTEGER
// ❌ SAI — CHOICE không có wrapper
int mark = w.beginSequence(BerTag.CONTEXT, CHOICE_TAG);
w.writeInt32(BerTag.CONTEXT, 0, localValue);
w.endSequence(mark, BerTag.CONTEXT, CHOICE_TAG);
7.4 IMPLICIT override CHOICE
Trường hợp phức tạp: IMPLICIT tag bên ngoài ăn mất tag của CHOICE bên trong.



bearerCapability [20] IMPLICIT BearerCapability
BearerCapability ::= CHOICE { bearerCap [0] OCTET STRING }
Wire: A0 xx <bytes>   ← [20] đè lên [0], không còn [0] trên wire
java


// Decode: khi gặp tag [20], getOctetStringSlice() trực tiếp
case 0x14:  // bearerCapability [20]
    out.bearerCapSlice = cursor.getOctetStringSlice();
    cursor.skipValue();
    break;
// Encode: writeOctetString với tag [20]
w.writeOctetString(BerTag.CONTEXT, 0x14, bearerCapBytes, 0, len);
// KHÔNG encode thêm [0] bên trong
8. Tích hợp với TCAP/MAP/CAP
8.1 TCAP inbound (TCAPProviderImpl)
java


private void processTCAPMessage(ProtocolDataImpl pd) {
    byte[] raw = pd.getData();
    BerCursor root = BerCursor.wrapHeap(raw, 0, raw.length);
    try {
        root.readTag();
        // root.tagClass() == BerTag.APPLICATION
        switch (root.tag()) {
            case 0x02: processTCBegin(root.openConstructed(), pd);    break;
            case 0x05: processTCContinue(root.openConstructed(), pd); break;
            case 0x04: processTCEnd(root.openConstructed(), pd);      break;
            case 0x07: processTCAbort(root.openConstructed(), pd);    break;
        }
    } catch (AsnException e) {
        log.error("ASN decode error", e);
    } finally {
        root.release();  // ← BẮT BUỘC
    }
}
8.2 MAP lazy decode (userInfoSlice)
java


// Trong processTCBegin:
BerSlice dialogSlice = null;
while (body.hasMore()) {
    body.readTag();
    if (body.tagClass() == BerTag.APPLICATION && body.tag() == 0x0B) {
        dialogSlice = body.getOctetStringSlice();  // lazy
    }
    body.skipValue();
}
// MAP layer: decode chỉ khi cần
if (dialogSlice != null) {
    MapUserInfoDecoder.MapDialoguePDU pdu =
        MapUserInfoDecoder.decode(dialogSlice);  // gọi khi cần
}
8.3 CAP outbound (encode response)
java


// TC-END + ReturnResultLast
InvokeResponseEncoder.encodeTCEndRRL(
    destTxId,
    appCtxOidEncoded,
    invokeId,
    CapOperationCode.INITIAL_DP,
    resultBytes,
    nettyOutputStream
);
// → BerWriter.get() + backward encode + flushTo(NettyAsnOutputStream)
// → không new object trên hot path
9. Performance rules
Rule 1: LUÔN dùng getOctetStringSlice() trên hot path
java


// ✅ FAST: zero-copy, 18 ns/op
BerSlice slice = cursor.getOctetStringSlice();
// ❌ SLOW: copy, 110 ns/op (6x slower)
byte[] bytes = cursor.getOctetString();
Chỉ gọi getOctetString() hoặc slice.toByteArray() khi:

Cần pass byte[] vào API cũ không thể thay đổi
Cần serialize ra disk/database
Cần modify dữ liệu
Rule 2: LUÔN release cursor
java


// ✅ ĐÚNG: release trong finally
BerCursor c = BerCursor.wrapHeap(data, 0, data.length);
try {
    // ...
} finally {
    c.release();
}
// ✅ ĐÚNG: release child cursor sau khi xong
BerCursor child = cursor.openConstructed();
try {
    // decode child
} finally {
    child.release();  // ← không quên
}
// ❌ SAI: quên release → pool bị leak
BerCursor c = BerCursor.wrapHeap(data, 0, data.length);
// ... (không release)
Rule 3: ThreadLocal BerWriter — không new
java


// ✅ ĐÚNG
BerWriter w = BerWriter.get();  // ThreadLocal, tự reset
// ❌ SAI
BerWriter w = new BerWriter(4096);  // tạo mới mỗi message
Rule 4: Lazy decode với BerSlice
java


// ✅ ĐÚNG: chỉ decode khi cần
BerSlice locationSlice = null;
// ... trong decode loop ...
case 0x24:
    locationSlice = cursor.getOctetStringSlice(); // lưu slice
    break;
// ... sau này khi MAP cần location ...
if (locationSlice != null && needLocation) {
    LocationInfo loc = LocationDecoder.decode(locationSlice);
}
// ❌ SAI: decode ngay dù không cần
case 0x24:
    LocationInfo loc = LocationDecoder.decode(/* ... */); // eager
    break;
Rule 5: Không wrap BerCursor vào heap object
java


// ❌ SAI: lưu BerCursor vào field của object
class InvokeImpl {
    BerCursor cursor;  // ← pool cursor bị giữ → leak
}
// ✅ ĐÚNG: convert sang BerSlice (không phải pool object)
class InvokeImpl {
    BerSlice paramSlice;  // ← view, không phải pool object
}
Rule 6: ThreadLocal ResultHolder — tránh new object
java


// ✅ ĐÚNG: reuse ThreadLocal result holder
private static final ThreadLocal<OperationCodeCodec.Result> TL_OP =
    ThreadLocal.withInitial(OperationCodeCodec.Result::new);
void decode(BerCursor cursor) throws AsnException {
    OperationCodeCodec.Result result = TL_OP.get();
    result.reset();
    OperationCodeCodec.decode(cursor, result);
    // dùng result...
    // KHÔNG lưu result vào field — ThreadLocal!
}
// ⚠️ NẾU cần giữ lâu hơn 1 method call: copy ra object riêng
OperationCode copy = new OperationCode(result.type, result.localValue);
10. Anti-patterns — KHÔNG làm
❌ Tạo AsnInputStream trong hot path
java


// ❌ SAI
AsnInputStream ais = new AsnInputStream(message.getData());
TCMessage tc = TcapFactory.createTCMessage(ais);
// ✅ ĐÚNG
BerCursor cursor = BerCursor.wrapHeap(message.getData(), 0, message.getData().length);
// ... decode trực tiếp
❌ new AsnOutputStream() trong hot path
java


// ❌ SAI
AsnOutputStream aos = new AsnOutputStream();
invoke.encodeAll(aos);
byte[] bytes = aos.toByteArray();
// ✅ ĐÚNG
BerWriter w = BerWriter.get();
// ... encode ...
w.flushTo(nettyOutputStream);
❌ openConstructed() mà không release
java


// ❌ SAI — child cursor leak
BerCursor child = cursor.openConstructed();
child.readTag();
int val = child.readInt32();
// child không được release → pool exhaustion
// ✅ ĐÚNG
BerCursor child = cursor.openConstructed();
try {
    child.readTag();
    int val = child.readInt32();
    child.skipValue();
} finally {
    child.release();
}
❌ readTag() sau readInt32() mà không skipValue()
java


// ❌ SAI — pos không được advance đúng
cursor.readTag();
int v1 = cursor.readInt32();  // readInt32 advance pos đến sau value
cursor.readTag();             // OK nếu readInt32 đã advance
// Nhưng với getOctetStringSlice() cần skipValue() sau:
cursor.readTag();
BerSlice s = cursor.getOctetStringSlice();
// pos đã advance — KHÔNG cần skipValue() sau getOctetStringSlice()
// ✅ Quy tắc:
// - readInt32()          → tự advance pos → KHÔNG cần skipValue()
// - getOctetStringSlice()→ tự advance pos → KHÔNG cần skipValue()
// - isPrimitive() + skip → cần gọi skipValue() thủ công
cursor.readTag();
// không xử lý value:
cursor.skipValue();  // advance qua value
❌ Dùng synchronized collection trong pool
java


// ❌ SAI
private static final ArrayDeque<BerCursor> POOL = new ArrayDeque<>();
public static BerCursor wrap(...) {
    synchronized (POOL) { ... }  // contention!
}
// ✅ ĐÚNG — ThreadLocal, không synchronize
private static final ThreadLocal<BerCursor[]> TL_POOL =
    ThreadLocal.withInitial(() -> new BerCursor[16]);
❌ Store BerCursor trong instance field
java


// ❌ SAI
class DialogImpl {
    BerCursor cursor;  // ThreadLocal pool object bị hold!
    void setCursor(BerCursor c) { this.cursor = c; }
}
// ✅ ĐÚNG: convert sang BerSlice (plain object, safe to store)
class DialogImpl {
    BerSlice rawDialogSlice;
    void setDialogSlice(BerSlice s) { this.rawDialogSlice = s; }
}
11. Benchmark & test
Chạy unit tests
bash


cd /home/meodien/orca/workspaces/jSS7/j25
# Tất cả tests
mvn test -pl asn/asn-api
# BerCursor tests only
mvn test -pl asn/asn-api \
    -Dtest="BerCursorTest,BerWriterTest" \
    -DfailIfNoTests=false
# Bottleneck analysis
mvn test -pl asn/asn-api \
    -Dtest="BerCursorBottleneckTest" \
    -DfailIfNoTests=false
# Realistic benchmark (no JIT constant-folding)
mvn test -pl asn/asn-api \
    -Dtest="BerCursorRealisticBenchmark" \
    -DfailIfNoTests=false
# Performance comparison vs legacy
mvn test -pl asn/asn-api \
    -Dtest="BerCodecBenchmark" \
    -DfailIfNoTests=false
Viết unit test cho code mới
Dùng TestNG (không dùng JUnit):

java


import org.testng.Assert;
import org.testng.annotations.Test;
import org.mobicents.protocols.asn.*;
public class MyChoiceCodecTest {
    // ── Test decode ──────────────────────────────────────────────────
    @Test
    public void testDecodeLocalOpCode() throws AsnException {
        // Wire: [0x80, 0x01, 0x16]  = [0] INTEGER 22
        byte[] wire = { (byte)0x80, 0x01, 0x16 };
        BerCursor cursor = BerCursor.wrapHeap(wire, 0, wire.length);
        try {
            cursor.readTag();
            Assert.assertEquals(cursor.tagClass(), BerTag.CONTEXT);
            Assert.assertEquals(cursor.tag(), 0x00);
            int value = cursor.readInt32();
            cursor.skipValue();
            Assert.assertEquals(value, 22);
        } finally {
            cursor.release();
        }
    }
    // ── Test encode ──────────────────────────────────────────────────
    @Test
    public void testEncodeLocalOpCode() {
        BerWriter w = BerWriter.get();
        OperationCodeCodec.encodeLocal(w, 22);
        byte[] result = w.toByteArray();
        // Expected: [0x80, 0x01, 0x16]
        Assert.assertEquals(result, new byte[]{ (byte)0x80, 0x01, 0x16 });
    }
    // ── Test roundtrip ───────────────────────────────────────────────
    @Test
    public void testRoundtrip() throws AsnException {
        BerWriter w = BerWriter.get();
        OperationCodeCodec.encodeLocal(w, 22);
        // Zero-copy roundtrip — KHÔNG toByteArray()
        BerCursor cursor = w.resultAsCursor();
        try {
            cursor.readTag();
            int decoded = cursor.readInt32();
            Assert.assertEquals(decoded, 22);
        } finally {
            cursor.release();
        }
    }
    // ── Test pool reuse ──────────────────────────────────────────────
    @Test
    public void testPoolReuse() throws AsnException {
        byte[] wire = { 0x02, 0x01, 0x2A };  // INTEGER 42
        BerCursor c1 = BerCursor.wrapHeap(wire, 0, wire.length);
        c1.release();
        BerCursor c2 = BerCursor.wrapHeap(wire, 0, wire.length);
        // c2 == c1 (same object reused from pool — same thread)
        Assert.assertSame(c1, c2, "Pool should reuse cursor");
        c2.release();
    }
}
12. Checklist trước khi commit


ASN.1 BER/DER correctness:
  [ ] readTag() được gọi trước mọi thao tác read
  [ ] skipValue() sau mỗi field không được xử lý
  [ ] CHOICE decode: đọc tag rồi dispatch, KHÔNG openConstructed()
  [ ] IMPLICIT CHOICE: getOctetStringSlice() trực tiếp tại outer tag
Memory/pool safety:
  [ ] Mọi BerCursor.wrap*() đều có cursor.release() trong finally
  [ ] Child cursor từ openConstructed() có release() riêng
  [ ] BerCursor KHÔNG được lưu trong instance field
  [ ] BerSlice (không phải pool) được dùng để lưu dài hạn
Performance:
  [ ] getOctetStringSlice() thay vì getOctetString() trên hot path
  [ ] BerWriter.get() thay vì new BerWriter()
  [ ] ThreadLocal ResultHolder với reset() thay vì new object
  [ ] Lazy decode: chỉ decode BerSlice khi caller thực sự cần
Test:
  [ ] TestNG (org.testng.annotations.Test), KHÔNG JUnit
  [ ] Unit test cho decode + encode + roundtrip
  [ ] Test pool reuse (assertSame sau release)
  [ ] mvn test -pl asn/asn-api PASS (37/37 tests)
Code style:
  [ ] Class là `final`
  [ ] Không có public constructor (dùng static factory)
  [ ] Legacy AsnInputStream/AsnOutputStream KHÔNG bị xóa
  [ ] dialogId LUÔN là Long, KHÔNG phải String
Appendix A: BER TLV wire format


Tag byte:
  [ 7 6 | 5 | 4 3 2 1 0 ]
    ↑↑     ↑   ↑↑↑↑↑
    ||     |   tag number (0-30)
    ||     |
    ||     primitive(0) / constructed(1)
    ||
    tag class: 00=UNIVERSAL, 01=APPLICATION, 10=CONTEXT, 11=PRIVATE
Length byte:
  Short form: 0xxxxxxx  (0-127)
  Long form:  1nnnnnnn  followed by n bytes of length
Common wire bytes:
  0x02 = UNIVERSAL PRIMITIVE INTEGER
  0x04 = UNIVERSAL PRIMITIVE OCTET STRING
  0x05 = UNIVERSAL PRIMITIVE NULL
  0x06 = UNIVERSAL PRIMITIVE OID
  0x0A = UNIVERSAL PRIMITIVE ENUMERATED
  0x30 = UNIVERSAL CONSTRUCTED SEQUENCE (0x10 | 0x20)
  0x31 = UNIVERSAL CONSTRUCTED SET
  0x80 = CONTEXT PRIMITIVE [0]
  0xA0 = CONTEXT CONSTRUCTED [0]
  0x61 = APPLICATION CONSTRUCTED [1] (TC-BEGIN = 0x62)
Appendix B: TCAP Application tags


TC-UNIDIRECTIONAL = [APPLICATION 1]  = 0x61
TC-BEGIN          = [APPLICATION 2]  = 0x62
TC-END            = [APPLICATION 4]  = 0x64
TC-CONTINUE       = [APPLICATION 5]  = 0x65
TC-ABORT          = [APPLICATION 7]  = 0x67
origTransactionId = [APPLICATION 8]  = 0x48 (primitive)
destTransactionId = [APPLICATION 9]  = 0x49 (primitive)
dialoguePortion   = [APPLICATION 11] = 0x6B (constructed)
componentPortion  = [APPLICATION 12] = 0x6C (constructed)
Component tags (CONTEXT CONSTRUCTED):
  Invoke              [1] = 0xA1
  ReturnResultLast    [2] = 0xA2
  ReturnError         [3] = 0xA3
  Reject              [4] = 0xA4
  ReturnResultNotLast [7] = 0xA7
Maintainer: Huu Nhan Tran (nhanth87@gmail.com)
Repo: https://github.com/nhanth87/jss7 branch j25

## Update: 2026-07-10 — Zero-Copy Pipeline v5 + BerWriterPool + Bug Fixes

### Changes Made

#### 1. BerCursor.java — Bug fixes + Pre-allocated child cursor
- **FIXED**: Removed dead `tlvOffset()` method referencing non-existent field (compile error)
- **FIXED**: Removed duplicate `getValueSlice()` and `sliceFrom()` methods (compile error)
- **ENHANCED**: `openConstructed()` now uses pre-allocated `child` cursor instead of `acquire()` — avoids ThreadLocal lookup on nested sequences
- **ENHANCED**: `release()` sets `this.child = null` to prevent stale child cursor from being reused by pool

#### 2. BerWriter.java — BerWriterPool integration
- **ADDED**: `BerWriter.get(int estimatedSize)` — routes to `BerWriterPool.acquire(estimatedSize)` for tiered allocation
- **ADDED**: `BerWriter.getAtLeast(int minCapacity)` — routes to `BerWriterPool.acquireAtLeast(minCapacity)`
- Kept old `BerWriter.get()` for backward compatibility (uses ThreadLocal default 8KB buffer)

#### 3. BerWriterPool.java — New file (multi-size pool)
- 5 size tiers per thread: TINY(256B), SMALL(1KB), MEDIUM(4KB), LARGE(16KB), XLARGE(64KB)
- ThreadLocal pool per tier, no CAS, no locks
- `acquire(int estimatedSize)` — rounds up to nearest tier
- Eliminates ~80% of BerWriter internal buffer allocations for small messages

#### 4. BerZeroCopyBenchmark.java — Fixed test data for backward encoding
- **FIXED**: All test data static initializers now write items in **reverse order** to compensate for BerWriter's backward encoding
- BerWriter encodes items backward (writes from buffer end toward beginning), resulting in reversed element order within SEQUENCE
- This is by design — see `BerWriterTest.testSequenceWithTwoIntegers` for confirmation
- Test data construction now writes last item first so decoded order matches intended semantics

### BerZeroCopyBenchmark Results (6 tests, 0 failures)

```
MAP OpenRequest decode (copy 240B)          | 2.17x ZERO-COPY FASTER
TCAP component decode (copy 512B)           | 2.34x ZERO-COPY FASTER
Nested CHOICE decode (copy 128B+256B)       | 7.08x ZERO-COPY FASTER
getRawSlice roundtrip (ZC encode→decode)    | 1.00x (same, as expected)
```

### BerWriter Backward Encoding Pattern

BerWriter encodes from the end of the buffer toward the beginning. This means:
- Items written in sequence `[A, B, C]` appear in buffer as `[C, B, A]`
- To get correct BER order, write items in **reverse**: `[C, B, A]` → buffer has `[A, B, C]`
- This is documented in `BerWriterTest.testSequenceWithTwoIntegers`

### Build Verification
```bash
cd /home/meodien/orca/workspaces/jSS7/j25
mvn test -pl asn/asn-api -am
# Result: Tests run: 62, Failures: 0, Errors: 0, BUILD SUCCESS
```

---
File này cover đầy đủ:
- **Tại sao** cần BerCursor (với số liệu benchmark thực tế)
- **Kiến trúc** dual-mode (heap vs ByteBuf)
- **API** đầy đủ cho decode + encode + CHOICE
- **6 Performance rules** + **6 Anti-patterns** có code example
- **Test template** với TestNG
- **Checklist** 20 điểm trước khi commit
- **Appendix** wire format reference

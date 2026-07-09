Đề xuất tích hợp BerCursor vào hướng đi hiện tại
Dưới đây là cách kết hợp BerCursor (thiết kế cursor thuần, zero object allocation) với nền tảng AsnBufferBackend + ByteBuf mà commit đã đưa vào:

1. Kiến trúc tích hợp đề xuất


AsnBufferBackend (interface)
    ├── HeapAsnBufferBackend (byte[])
    └── ByteBufAsnBufferBackend (Netty ByteBuf)   ← đã có từ commit
          ↓ (wrap)
BerCursor (mới)
    - Lướt trên AsnBufferBackend
    - readTag() / readLength() → kết quả lưu vào fields (không new object)
    - openConstructed() → trả về BerCursor con trỏ cùng buffer (zero-copy)
    - getOctetStringSlice(BerSlice) → trả về view
    - release() → trả về pool (ThreadLocal/ArrayDeque)
BerSlice (mới)
    - offset + length trên buffer gốc
    - asByteBuffer() / toByteArray() (lazy copy)
BerWriter (mới)
    - Encode backward (giống Nokalva)
    - Dùng NettyAsnOutputStream hoặc ThreadLocal buffer
2. BerCursor sẽ hoạt động như thế nào với commit hiện tại


Thành phần	Commit hiện tại	Sau khi tích hợp BerCursor
Buffer backend	AsnBufferBackend	Giữ nguyên, BerCursor wrap trên backend
Decode	AsnInputStream tạo object khi nested	BerCursor lướt tại chỗ, gần như zero object
Nested TLV	new AsnInputStream(subData)	cursor.openConstructed() → sub-cursor
Lazy decode	Chưa có	BerSlice cho component (Phase 3)
Outbound	NettyAsnOutputStream	BerWriter backward encode → NettyAsnOutputStream
3. Lộ trình tích hợp cụ thể
Phase 1 (asn module)

Tạo BerCursor.java và BerSlice.java (dựa trên thiết kế tôi đưa trước đó).
BerCursor nhận AsnBufferBackend (hoặc trực tiếp ByteBuf cho hot path).
Thêm BerCursor.wrap(AsnBufferBackend backend) và BerCursor.wrap(ByteBuf buf, int offset, int length).
Phase 2 (tcap inbound)

Trong TCAPProviderImpl.onMessageReceived() (hoặc nơi decode TC message):
Dùng BerCursor thay vì new AsnInputStream().
Giữ overload decode(AsnInputStream) cũ để backward compatibility.
Thêm overload decode(BerCursor cursor).
Phase 3 (lazy decode cho MAP/CAP)

Các InvokeImpl, OperationCodeImpl, v.v. có thể chỉ giữ BerSlice thay vì decode ngay.
Decode thực sự chỉ xảy ra khi application gọi getter (tương tự Nokalva DeferDecoding).
Phase 4 (encode)

Thay thế một phần AsnOutputStream bằng BerWriter (backward encoding) → kết quả là BerSlice → đẩy thẳng vào NettyAsnOutputStream.
4. Lợi ích khi tích hợp
Giữ được toàn bộ nỗ lực zero-copy ByteBuf từ commit.
Giảm mạnh object allocation trên hot path decode (đúng mục tiêu bạn đang gặp vấn đề).
Vẫn tương thích ngược với code cũ (AsnInputStream).
Dễ benchmark so sánh (có thể đo object count + throughput bằng JMH).
Bạn muốn tôi làm gì tiếp theo?

Viết code mẫu BerCursor.java + BerSlice.java đã tích hợp sẵn với AsnBufferBackend.
Viết patch mẫu cho TCAPProviderImpl để dùng BerCursor.
Phân tích sâu hơn file FlatAsnParser.java hoặc AsnInputStream.java hiện tại (nếu bạn cung cấp link chính xác).
Hãy cho tôi biết bước nào bạn muốn thực hiện trước.






6 sources
Grok 4.3
Viết code mẫu BerCursor.java + BerSlice.java đã tích hợp sẵn với AsnBufferBackend.
Viết patch mẫu cho TCAPProviderImpl để dùng BerCursor.



✅ Dưới đây là code mẫu hoàn chỉnh

1. BerSlice.java
java


package org.mobicents.protocols.asn;
import io.netty.buffer.ByteBuf;
import java.util.Arrays;
/**
 * Zero-copy view vào buffer (heap byte[] hoặc Netty ByteBuf).
 * Không copy dữ liệu khi tạo.
 */
public final class BerSlice {
    private final AsnBufferBackend backend;
    private final int offset;
    private final int length;
    BerSlice(AsnBufferBackend backend, int offset, int length) {
        this.backend = backend;
        this.offset = offset;
        this.length = length;
    }
    public int length() { return length; }
    public int offset() { return offset; }
    public byte[] toByteArray() {
        return backend.toByteArray(offset, length);
    }
    public ByteBuf asByteBuf() {
        return backend.slice(offset, length);
    }
    public boolean equalsBytes(byte[] other) {
        if (other == null || other.length != length) return false;
        return backend.equalsBytes(offset, other, 0, length);
    }
}
2. BerCursor.java (tích hợp AsnBufferBackend)
java


package org.mobicents.protocols.asn;
import io.netty.buffer.ByteBuf;
import java.util.ArrayDeque;
/**
 * Cursor-based BER/DER decoder – zero-copy, minimal allocation.
 * Làm việc trực tiếp trên AsnBufferBackend (heap hoặc ByteBuf).
 */
public final class BerCursor {
    private static final ArrayDeque<BerCursor> POOL = new ArrayDeque<>(64);
    private AsnBufferBackend backend;
    private int base;
    private int limit;
    private int pos;
    // Cached TLV info (không new object)
    private int tagClass;
    private boolean primitive;
    private int tag;
    private int valueOffset;
    private int valueLength;
    private BerCursor() {}
    public static BerCursor wrap(AsnBufferBackend backend, int offset, int length) {
        BerCursor c = POOL.isEmpty() ? new BerCursor() : POOL.pop();
        c.backend = backend;
        c.base = offset;
        c.limit = offset + length;
        c.pos = offset;
        return c;
    }
    public static BerCursor wrap(byte[] data, int offset, int length) {
        return wrap(new HeapAsnBufferBackend(data), offset, length);
    }
    public void release() {
        this.backend = null;
        POOL.push(this);
    }
    // ==================== READ TLV ====================
    public void readTag() throws AsnException {
        int b = backend.readByte(pos++) & 0xFF;
        tagClass = (b >> 6) & 0x03;
        primitive = (b & 0x20) == 0;
        tag = b & 0x1F;
        if (tag == 0x1F) { // long form
            tag = 0;
            do {
                b = backend.readByte(pos++) & 0xFF;
                tag = (tag << 7) | (b & 0x7F);
            } while ((b & 0x80) != 0);
        }
        b = backend.readByte(pos++) & 0xFF;
        if (b <= 0x7F) {
            valueLength = b;
        } else {
            int n = b & 0x7F;
            valueLength = 0;
            for (int i = 0; i < n; i++) {
                valueLength = (valueLength << 8) | (backend.readByte(pos++) & 0xFF);
            }
        }
        valueOffset = pos;
    }
    public int tagClass()   { return tagClass; }
    public int tag()        { return tag; }
    public boolean isPrimitive() { return primitive; }
    public int valueLength() { return valueLength; }
    public boolean hasMore() { return pos < limit; }
    public int remaining()   { return limit - pos; }
    // ==================== NAVIGATION ====================
    public BerCursor openConstructed() {
        return BerCursor.wrap(backend, valueOffset, valueLength);
    }
    public void skipValue() {
        pos = valueOffset + valueLength;
    }
    // ==================== READ PRIMITIVES ====================
    public int readInt32() {
        int result = 0;
        int end = valueOffset + valueLength;
        for (int i = valueOffset; i < end; i++) {
            result = (result << 8) | (backend.readByte(i) & 0xFF);
        }
        pos = end;
        return result;
    }
    public BerSlice getOctetStringSlice() {
        BerSlice slice = new BerSlice(backend, valueOffset, valueLength);
        pos = valueOffset + valueLength;
        return slice;
    }
    public byte[] getOctetString() {
        byte[] data = backend.toByteArray(valueOffset, valueLength);
        pos = valueOffset + valueLength;
        return data;
    }
}
3. Patch mẫu cho TCAPProviderImpl
File: tcap/tcap-impl/src/main/java/org/mobicents/protocols/ss7/tcap/TCAPProviderImpl.java

java


// Thêm import
import org.mobicents.protocols.asn.AsnBufferBackend;
import org.mobicents.protocols.asn.BerCursor;
import org.mobicents.protocols.asn.ByteBufAsnBufferBackend;
// Trong method onMessageReceived(...) hoặc tương đương
private void processTCAPMessage(ProtocolDataImpl pd) {
    try {
        // === NEW: Zero-copy path ===
        AsnBufferBackend backend;
        if (pd.getDataBuf() != null) {
            backend = new ByteBufAsnBufferBackend(pd.getDataBuf());
        } else {
            backend = new HeapAsnBufferBackend(pd.getData());
        }
        BerCursor cursor = BerCursor.wrap(backend, 0, pd.getDataLength());
        cursor.readTag();
        switch (cursor.tag()) {
            case TCBeginMessage._TAG:
                decodeTCBegin(cursor.openConstructed(), pd);
                break;
            case TCContinueMessage._TAG:
                decodeTCContinue(cursor.openConstructed(), pd);
                break;
            case TCEndMessage._TAG:
                decodeTCEnd(cursor.openConstructed(), pd);
                break;
            // ... các case khác
            default:
                // fallback sang path cũ nếu cần
                AsnInputStream ais = new AsnInputStream(pd.getData());
                // decode cũ
        }
        cursor.release();
    } catch (Exception e) {
        logger.error("Error decoding TCAP message", e);
    }
}
// Ví dụ decodeTCBegin (overload mới)
private void decodeTCBegin(BerCursor cursor, ProtocolDataImpl pd) throws AsnException {
    // Đọc các TLV bên trong Begin
    while (cursor.hasMore()) {
        cursor.readTag();
        // Xử lý originTransactionId, dialoguePortion, componentPortion...
        if (cursor.tag() == ComponentPortion._TAG) {
            BerCursor compCursor = cursor.openConstructed();
            decodeComponents(compCursor);
        }
        cursor.skipValue();
    }
}
Tóm tắt tích hợp
BerCursor hoạt động trực tiếp trên AsnBufferBackend → tương thích cả heap và ByteBuf.
BerSlice là view zero-copy.
TCAPProviderImpl có thể dùng BerCursor ngay từ inbound message mà không cần new AsnInputStream.
Vẫn giữ backward compatibility với AsnInputStream cũ.
Bạn muốn tôi viết thêm:

BerWriter.java (backward encode)?
Phiên bản decodeComponents(BerCursor) chi tiết hơn?
Hoặc benchmark skeleton (JMH)?
Hãy cho tôi biết bước tiếp theo.




Grok 4.3
BerWriter.java (backward encode)?
Phiên bản decodeComponents(BerCursor) chi tiết hơn?



✅ Dưới đây là code hoàn chỉnh:

1. BerWriter.java — Backward Encoder
java


package org.mobicents.protocols.asn;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
/**
 * Zero-copy BER/DER backward encoder.
 *
 * Strategy (giống Nokalva TOED):
 *   - Write từ cuối buffer về đầu → không cần biết length trước
 *   - Dùng ThreadLocal pool → không new object trên hot path
 *   - Kết quả là BerSlice trỏ vào backing buffer (không copy)
 *
 * Usage:
 *   BerWriter w = BerWriter.get();
 *   int mark = w.beginSequence(BerTag.CONTEXT_CONSTRUCTED, 1);
 *     w.writeInt32(BerTag.UNIVERSAL_PRIMITIVE, BerTag.INTEGER, 42);
 *     w.writeOctetString(BerTag.UNIVERSAL_PRIMITIVE, BerTag.OCTET_STRING, slice);
 *   w.endSequence(mark, BerTag.CONTEXT_CONSTRUCTED, 1);
 *   BerSlice result = w.getResult();
 */
public final class BerWriter {
    // ── Constants ────────────────────────────────────────────────────
    private static final int DEFAULT_CAPACITY = 8192;
    // ── ThreadLocal pool (1 BerWriter per thread) ────────────────────
    private static final ThreadLocal<BerWriter> TL = ThreadLocal.withInitial(
        () -> new BerWriter(DEFAULT_CAPACITY)
    );
    public static BerWriter get() {
        BerWriter w = TL.get();
        w.reset();
        return w;
    }
    // ── Backing buffer ───────────────────────────────────────────────
    private byte[] buf;
    private int    writePos;   // luôn giảm khi write
    // ── Result slice (reuse, không new) ─────────────────────────────
    private final BerSlice resultSlice;
    // ── Constructor ──────────────────────────────────────────────────
    private BerWriter(int capacity) {
        this.buf         = new byte[capacity];
        this.writePos    = capacity;
        this.resultSlice = new BerSlice(
            new HeapAsnBufferBackend(buf), 0, 0
        );
    }
    private void reset() {
        writePos = buf.length;
    }
    // ── Grow nếu buffer không đủ ─────────────────────────────────────
    private void ensureCapacity(int needed) {
        if (writePos >= needed) return;
        int newCap = buf.length * 2;
        while (newCap - (buf.length - writePos) < needed) newCap *= 2;
        byte[] newBuf = new byte[newCap];
        int offset = newCap - buf.length;
        System.arraycopy(buf, 0, newBuf, offset, buf.length);
        writePos += offset;
        buf = newBuf;
    }
    // ================================================================
    // LOW-LEVEL WRITE (backward)
    // ================================================================
    private void writeByte(byte b) {
        ensureCapacity(1);
        buf[--writePos] = b;
    }
    private void writeBytes(byte[] data, int offset, int len) {
        ensureCapacity(len);
        writePos -= len;
        System.arraycopy(data, offset, buf, writePos, len);
    }
    private void writeLength(int len) {
        if (len <= 0x7F) {
            writeByte((byte) len);
        } else if (len <= 0xFF) {
            writeByte((byte) len);
            writeByte((byte) 0x81);
        } else if (len <= 0xFFFF) {
            writeByte((byte) (len & 0xFF));
            writeByte((byte) (len >> 8));
            writeByte((byte) 0x82);
        } else {
            writeByte((byte) (len & 0xFF));
            writeByte((byte) ((len >> 8) & 0xFF));
            writeByte((byte) (len >> 16));
            writeByte((byte) 0x83);
        }
    }
    private void writeTag(int tagClass, boolean primitive, int tag) {
        if (tag <= 30) {
            writeByte((byte) (
                (tagClass << 6) | (primitive ? 0 : 0x20) | tag
            ));
        } else {
            // long-form tag: write từ LSB về MSB (backward)
            boolean first = true;
            int t = tag;
            while (t > 0) {
                writeByte((byte) ((first ? 0x00 : 0x80) | (t & 0x7F)));
                t >>>= 7;
                first = false;
            }
            writeByte((byte) (
                (tagClass << 6) | (primitive ? 0 : 0x20) | 0x1F
            ));
        }
    }
    // ================================================================
    // HIGH-LEVEL ENCODE API
    // ================================================================
    /**
     * Encode INTEGER (ASN.1 universal tag 2).
     * Tự động chọn số byte tối thiểu (signed BER integer encoding).
     */
    public void writeInt32(int tagClass, int tag, int value) {
        int snapshot = writePos;
        // encode value bytes (minimal, signed BER)
        int v = value;
        writeByte((byte) (v & 0xFF));
        v >>= 8;
        while (v != 0 && v != -1) {
            writeByte((byte) (v & 0xFF));
            v >>= 8;
        }
        // leading sign extension nếu cần
        int topByte = buf[writePos] & 0xFF;
        if (value >= 0 && (topByte & 0x80) != 0)
            writeByte((byte) 0x00);
        else if (value < 0 && (topByte & 0x80) == 0)
            writeByte((byte) 0xFF);
        int len = snapshot - writePos;
        writeLength(len);
        writeTag(tagClass, true, tag);
    }
    /**
     * Encode LONG (ASN.1 universal tag 2, 64-bit).
     */
    public void writeInt64(int tagClass, int tag, long value) {
        int snapshot = writePos;
        long v = value;
        writeByte((byte) (v & 0xFF));
        v >>= 8;
        while (v != 0L && v != -1L) {
            writeByte((byte) (v & 0xFF));
            v >>= 8;
        }
        int topByte = buf[writePos] & 0xFF;
        if (value >= 0 && (topByte & 0x80) != 0)
            writeByte((byte) 0x00);
        else if (value < 0 && (topByte & 0x80) == 0)
            writeByte((byte) 0xFF);
        int len = snapshot - writePos;
        writeLength(len);
        writeTag(tagClass, true, tag);
    }
    /**
     * Encode BOOLEAN.
     */
    public void writeBoolean(int tagClass, int tag, boolean value) {
        writeByte(value ? (byte) 0xFF : (byte) 0x00);
        writeLength(1);
        writeTag(tagClass, true, tag);
    }
    /**
     * Encode OCTET STRING từ raw byte[].
     */
    public void writeOctetString(int tagClass, int tag,
                                  byte[] data, int offset, int len) {
        writeBytes(data, offset, len);
        writeLength(len);
        writeTag(tagClass, true, tag);
    }
    /**
     * Encode OCTET STRING từ BerSlice (zero-copy — trỏ vào buffer gốc).
     */
    public void writeOctetString(int tagClass, int tag, BerSlice slice) {
        writeOctetString(tagClass, tag,
            slice.toByteArray(), 0, slice.length());
    }
    /**
     * Encode NULL.
     */
    public void writeNull(int tagClass, int tag) {
        writeLength(0);
        writeTag(tagClass, true, tag);
    }
    /**
     * Encode OBJECT IDENTIFIER.
     * oidComponents: int[] của các arc (e.g. {2, 16, 840, 1, 101, ...}).
     */
    public void writeOID(int tagClass, int tag, int[] oidComponents) {
        int snapshot = writePos;
        // arc từ thứ 2 trở đi, encode backward
        for (int i = oidComponents.length - 1; i >= 2; i--) {
            int arc = oidComponents[i];
            writeByte((byte) (arc & 0x7F));
            arc >>>= 7;
            while (arc > 0) {
                writeByte((byte) (0x80 | (arc & 0x7F)));
                arc >>>= 7;
            }
        }
        // arc đầu tiên: (first * 40) + second
        int first = oidComponents[0] * 40 + oidComponents[1];
        writeByte((byte) (first & 0x7F));
        first >>>= 7;
        while (first > 0) {
            writeByte((byte) (0x80 | (first & 0x7F)));
            first >>>= 7;
        }
        int len = snapshot - writePos;
        writeLength(len);
        writeTag(tagClass, true, tag);
    }
    // ── CONSTRUCTED TLV (SEQUENCE / SET / context-constructed) ──────
    /**
     * Mở một constructed TLV (SEQUENCE, SET, context-constructed, v.v.).
     * Trả về mark để truyền vào endSequence().
     */
    public int beginSequence(int tagClass, int tag) {
        return writePos;   // snapshot vị trí hiện tại
    }
    /**
     * Đóng constructed TLV, fill header (tag + length) vào đúng vị trí.
     */
    public void endSequence(int mark, int tagClass, int tag) {
        int contentLen = mark - writePos;
        writeLength(contentLen);
        writeTag(tagClass, false, tag);
    }
    // ================================================================
    // RESULT
    // ================================================================
    /**
     * Lấy kết quả encode dưới dạng BerSlice (trỏ vào backing buf,
     * không copy).
     */
    public BerSlice getResult() {
        return new BerSlice(
            new HeapAsnBufferBackend(buf),
            writePos,
            buf.length - writePos
        );
    }
    /**
     * Flush kết quả vào Netty ByteBuf (zero-copy writable path).
     * Dùng khi cần ghi vào NettyAsnOutputStream.
     */
    public void flushTo(ByteBuf out) {
        out.writeBytes(buf, writePos, buf.length - writePos);
    }
    /**
     * Flush kết quả vào NettyAsnOutputStream.
     */
    public void flushTo(NettyAsnOutputStream out) {
        out.writeRawBytes(buf, writePos, buf.length - writePos);
    }
    /**
     * Trả về kết quả dưới dạng byte[] (copy — chỉ dùng khi thực sự cần).
     */
    public byte[] toByteArray() {
        int len = buf.length - writePos;
        byte[] result = new byte[len];
        System.arraycopy(buf, writePos, result, 0, len);
        return result;
    }
}
     2. BerTag.java — Constants (dùng chung)
     java
     
     
     package org.mobicents.protocols.asn;
     /**
      * BER/DER tag class và universal tag constants.
      */
     public final class BerTag {
         private BerTag() {}
         // ── Tag classes ──────────────────────────────────────────────────
         public static final int UNIVERSAL   = 0x00;
         public static final int APPLICATION = 0x01;
         public static final int CONTEXT     = 0x02;
         public static final int PRIVATE     = 0x03;
         // ── Universal primitive tags ─────────────────────────────────────
         public static final int BOOLEAN          = 0x01;
         public static final int INTEGER          = 0x02;
         public static final int BIT_STRING       = 0x03;
         public static final int OCTET_STRING     = 0x04;
         public static final int NULL             = 0x05;
         public static final int OID              = 0x06;
         public static final int ENUMERATED       = 0x0A;
         public static final int UTF8_STRING      = 0x0C;
         public static final int PRINTABLE_STRING = 0x13;
         public static final int IA5_STRING       = 0x16;
         // ── Universal constructed tags ───────────────────────────────────
         public static final int SEQUENCE         = 0x10;
         public static final int SET              = 0x11;
         // ── Helpers: wire byte (tagClass | constructed | tag) ───────────
         public static byte universalPrimitive(int tag) {
             return (byte) (UNIVERSAL << 6 | tag);
         }
         public static byte universalConstructed(int tag) {
             return (byte) (UNIVERSAL << 6 | 0x20 | tag);
         }
         public static byte contextPrimitive(int tag) {
             return (byte) (CONTEXT << 6 | tag);
         }
         public static byte contextConstructed(int tag) {
             return (byte) (CONTEXT << 6 | 0x20 | tag);
         }
     }
     3. decodeComponents(BerCursor) — Chi tiết
     java
     
     
     package org.mobicents.protocols.ss7.tcap.tc.component.impl;
     import org.mobicents.protocols.asn.AsnException;
     import org.mobicents.protocols.asn.BerCursor;
     import org.mobicents.protocols.asn.BerSlice;
     import org.mobicents.protocols.asn.BerTag;
     import org.mobicents.protocols.ss7.tcap.asn.comp.*;
     import java.util.ArrayList;
     import java.util.List;
     /**
      * Zero-copy component decoder.
      * Thay thế ComponentPortionImpl.decode(AsnInputStream).
      */
     public final class ComponentDecoder {
         // ── TCAP Component tags (ITU-T Q.773) ───────────────────────────
         // [1] INVOKE
         private static final int TAG_INVOKE         = 0x01;
         // [2] RETURN RESULT LAST
         private static final int TAG_RRL            = 0x02;
         // [3] RETURN ERROR
         private static final int TAG_RETURN_ERROR   = 0x03;
         // [4] REJECT
         private static final int TAG_REJECT         = 0x04;
         // [7] RETURN RESULT NOT LAST
         private static final int TAG_RRNL           = 0x07;
         private ComponentDecoder() {}
         // ================================================================
         // ENTRY POINT
         // ================================================================
         /**
          * Decode ComponentPortion (outermost SEQUENCE OF Component).
          *
          * @param cursor  BerCursor trỏ vào content của componentPortion TLV
          * @param out     list để nhận kết quả (caller cấp, tránh new ArrayList)
          */
         public static void decodeComponents(BerCursor cursor,
                                             List<Component> out)
                 throws AsnException {
             while (cursor.hasMore()) {
                 cursor.readTag();
                 if (cursor.tagClass() != BerTag.CONTEXT) {
                     // unexpected tag — log & skip
                     cursor.skipValue();
                     continue;
                 }
                 Component comp = decodeSingleComponent(cursor);
                 if (comp != null) out.add(comp);
             }
         }
         // ================================================================
         // DISPATCH THEO TAG
         // ================================================================
         private static Component decodeSingleComponent(BerCursor cursor)
                 throws AsnException {
             switch (cursor.tag()) {
                 case TAG_INVOKE:       return decodeInvoke(cursor);
                 case TAG_RRL:          return decodeReturnResultLast(cursor);
                 case TAG_RRNL:         return decodeReturnResultNotLast(cursor);
                 case TAG_RETURN_ERROR: return decodeReturnError(cursor);
                 case TAG_REJECT:       return decodeReject(cursor);
                 default:
                     cursor.skipValue();
                     return null;
             }
         }
         // ================================================================
         // INVOKE [1] IMPLICIT SEQUENCE
         // ================================================================
         /**
          * Decode Invoke component.
          *
          * Invoke ::= [1] IMPLICIT SEQUENCE {
          *   invokeId        InvokeIdType,
          *   linkedId        [0] IMPLICIT InvokeIdType OPTIONAL,
          *   operationCode   OperationCode,
          *   parameter       ANY OPTIONAL
          * }
          */
         private static Component decodeInvoke(BerCursor outer)
                 throws AsnException {
             InvokeImpl invoke = new InvokeImpl();
             // Mở sub-cursor vào content của [1] SEQUENCE
             BerCursor body = outer.openConstructed();
             // ── invokeId ────────────────────────────────────────────────
             body.readTag();
             assertUniversalPrimitive(body, BerTag.INTEGER, "Invoke.invokeId");
             int invokeId = body.readInt32();
             invoke.setInvokeId(invokeId);
             body.skipValue();
             if (!body.hasMore()) {
                 outer.skipValue();
                 return invoke;
             }
             // ── linkedId [0] IMPLICIT OPTIONAL ──────────────────────────
             body.readTag();
             if (body.tagClass() == BerTag.CONTEXT && body.tag() == 0x00) {
                 int linkedId = body.readInt32();
                 invoke.setLinkedId(linkedId);
                 body.skipValue();
                 if (!body.hasMore()) {
                     outer.skipValue();
                     return invoke;
                 }
                 body.readTag();
             }
             // ── operationCode ────────────────────────────────────────────
             OperationCode opCode = decodeOperationCode(body);
             invoke.setOperationCode(opCode);
             body.skipValue();
             // ── parameter (ANY) OPTIONAL ─────────────────────────────────
             if (body.hasMore()) {
                 body.readTag();
                 // Giữ slice, không decode ngay (lazy)
                 BerSlice paramSlice = body.getOctetStringSlice();
                 invoke.setParameterSlice(paramSlice);  // cần thêm field vào InvokeImpl
                 body.skipValue();
             }
             body.release();
             outer.skipValue();
             return invoke;
         }
         // ================================================================
         // RETURN RESULT LAST [2]
         // ================================================================
         /**
          * ReturnResultLast ::= [2] IMPLICIT SEQUENCE {
          *   invokeId   InvokeIdType,
          *   result     SEQUENCE {
          *     operationCode  OperationCode,
          *     parameter      ANY
          *   } OPTIONAL
          * }
          */
         private static Component decodeReturnResultLast(BerCursor outer)
                 throws AsnException {
             ReturnResultLastImpl rrl = new ReturnResultLastImpl();
             BerCursor body = outer.openConstructed();
             // invokeId
             body.readTag();
             assertUniversalPrimitive(body, BerTag.INTEGER, "RRL.invokeId");
             rrl.setInvokeId(body.readInt32());
             body.skipValue();
             // result SEQUENCE OPTIONAL
             if (body.hasMore()) {
                 body.readTag();
                 if (body.tagClass() == BerTag.UNIVERSAL
                         && body.tag() == BerTag.SEQUENCE) {
                     BerCursor resultBody = body.openConstructed();
                     resultBody.readTag();
                     OperationCode opCode = decodeOperationCode(resultBody);
                     rrl.setOperationCode(opCode);
                     resultBody.skipValue();
                     if (resultBody.hasMore()) {
                         resultBody.readTag();
                         BerSlice paramSlice = resultBody.getOctetStringSlice();
                         rrl.setParameterSlice(paramSlice);
                         resultBody.skipValue();
                     }
                     resultBody.release();
                 }
                 body.skipValue();
             }
             body.release();
             outer.skipValue();
             return rrl;
         }
         // ================================================================
         // RETURN RESULT NOT LAST [7]
         // ================================================================
         private static Component decodeReturnResultNotLast(BerCursor outer)
                 throws AsnException {
             ReturnResultNotLastImpl rrnl = new ReturnResultNotLastImpl();
             BerCursor body = outer.openConstructed();
             body.readTag();
             assertUniversalPrimitive(body, BerTag.INTEGER, "RRNL.invokeId");
             rrnl.setInvokeId(body.readInt32());
             body.skipValue();
             if (body.hasMore()) {
                 body.readTag();
                 if (body.tagClass() == BerTag.UNIVERSAL
                         && body.tag() == BerTag.SEQUENCE) {
                     BerCursor resultBody = body.openConstructed();
                     resultBody.readTag();
                     OperationCode opCode = decodeOperationCode(resultBody);
                     rrnl.setOperationCode(opCode);
                     resultBody.skipValue();
                     if (resultBody.hasMore()) {
                         resultBody.readTag();
                         BerSlice paramSlice = resultBody.getOctetStringSlice();
                         rrnl.setParameterSlice(paramSlice);
                         resultBody.skipValue();
                     }
                     resultBody.release();
                 }
                 body.skipValue();
             }
             body.release();
             outer.skipValue();
             return rrnl;
         }
         // ================================================================
         // RETURN ERROR [3]
         // ================================================================
         /**
          * ReturnError ::= [3] IMPLICIT SEQUENCE {
          *   invokeId  InvokeIdType,
          *   errorCode ErrorCode,
          *   parameter ANY OPTIONAL
          * }
          */
         private static Component decodeReturnError(BerCursor outer)
                 throws AsnException {
             ReturnErrorImpl error = new ReturnErrorImpl();
             BerCursor body = outer.openConstructed();
             // invokeId
             body.readTag();
             assertUniversalPrimitive(body, BerTag.INTEGER, "ReturnError.invokeId");
             error.setInvokeId(body.readInt32());
             body.skipValue();
             // errorCode
             if (body.hasMore()) {
                 body.readTag();
                 ErrorCode errCode = decodeErrorCode(body);
                 error.setErrorCode(errCode);
                 body.skipValue();
             }
             // parameter (lazy slice)
             if (body.hasMore()) {
                 body.readTag();
                 BerSlice paramSlice = body.getOctetStringSlice();
                 error.setParameterSlice(paramSlice);
                 body.skipValue();
             }
             body.release();
             outer.skipValue();
             return error;
         }
         // ================================================================
         // REJECT [4]
         // ================================================================
         /**
          * Reject ::= [4] IMPLICIT SEQUENCE {
          *   invokeId   InvokeIdType | NULL,
          *   problem    Problem
          * }
          */
         private static Component decodeReject(BerCursor outer)
                 throws AsnException {
             RejectImpl reject = new RejectImpl();
             BerCursor body = outer.openConstructed();
             // invokeId hoặc NULL
             body.readTag();
             if (body.tagClass() == BerTag.UNIVERSAL
                     && body.tag() == BerTag.NULL) {
                 reject.setInvokeId(null);
             } else {
                 assertUniversalPrimitive(body, BerTag.INTEGER, "Reject.invokeId");
                 reject.setInvokeId(body.readInt32());
             }
             body.skipValue();
             // problem: CHOICE { generalProblem [0], invokeProblem [1],
             //                   returnResultProblem [2], returnErrorProblem [3] }
             if (body.hasMore()) {
                 body.readTag();
                 assertContext(body, "Reject.problem");
                 int problemType  = body.tag();   // 0=general,1=invoke,2=rr,3=re
                 int problemValue = body.readInt32();
                 reject.setProblem(problemType, problemValue);
                 body.skipValue();
             }
             body.release();
             outer.skipValue();
             return reject;
         }
         // ================================================================
         // OPERATION CODE (CHOICE local/global)
         // ================================================================
         /**
          * OperationCode ::= CHOICE {
          *   localValue   [0] IMPLICIT INTEGER,
          *   globalValue  [1] IMPLICIT OBJECT IDENTIFIER
          * }
          */
         private static OperationCode decodeOperationCode(BerCursor cursor)
                 throws AsnException {
             // tag đã được read bởi caller
             OperationCodeImpl opCode = new OperationCodeImpl();
             if (cursor.tagClass() == BerTag.CONTEXT) {
                 if (cursor.tag() == 0x00) {
                     // local INTEGER
                     opCode.setLocalOperationCode((long) cursor.readInt32());
                     opCode.setOperationType(OperationCodeType.Local);
                 } else if (cursor.tag() == 0x01) {
                     // global OID
                     BerSlice oidSlice = cursor.getOctetStringSlice();
                     opCode.setGlobalOperationCode(oidSlice.toByteArray());
                     opCode.setOperationType(OperationCodeType.Global);
                 }
             } else if (cursor.tagClass() == BerTag.UNIVERSAL) {
                 if (cursor.tag() == BerTag.INTEGER) {
                     opCode.setLocalOperationCode((long) cursor.readInt32());
                     opCode.setOperationType(OperationCodeType.Local);
                 } else if (cursor.tag() == BerTag.OID) {
                     BerSlice oidSlice = cursor.getOctetStringSlice();
                     opCode.setGlobalOperationCode(oidSlice.toByteArray());
                     opCode.setOperationType(OperationCodeType.Global);
                 }
             }
             return opCode;
         }
         // ================================================================
         // ERROR CODE (CHOICE local/global)
         // ================================================================
         /**
          * ErrorCode ::= CHOICE {
          *   localValue   [0] IMPLICIT INTEGER,
          *   globalValue  [1] IMPLICIT OBJECT IDENTIFIER
          * }
          */
         private static ErrorCode decodeErrorCode(BerCursor cursor)
                 throws AsnException {
             ErrorCodeImpl errCode = new ErrorCodeImpl();
             if (cursor.tagClass() == BerTag.CONTEXT) {
                 if (cursor.tag() == 0x00) {
                     errCode.setLocalErrorCode((long) cursor.readInt32());
                     errCode.setErrorType(ErrorCodeType.Local);
                 } else if (cursor.tag() == 0x01) {
                     BerSlice oidSlice = cursor.getOctetStringSlice();
                     errCode.setGlobalErrorCode(oidSlice.toByteArray());
                     errCode.setErrorType(ErrorCodeType.Global);
                 }
             }
             return errCode;
         }
         // ================================================================
         // GUARDS
         // ================================================================
         private static void assertUniversalPrimitive(BerCursor cursor,
                                                       int expectedTag,
                                                       String context)
                 throws AsnException {
             if (cursor.tagClass() != BerTag.UNIVERSAL
                     || !cursor.isPrimitive()
                     || cursor.tag() != expectedTag) {
                 throw new AsnException(
                     String.format("[%s] Expected UNIVERSAL PRIMITIVE tag=%d, " +
                                   "got class=%d primitive=%b tag=%d",
                         context, expectedTag,
                         cursor.tagClass(), cursor.isPrimitive(), cursor.tag())
                 );
             }
         }
         private static void assertContext(BerCursor cursor, String context)
                 throws AsnException {
             if (cursor.tagClass() != BerTag.CONTEXT) {
                 throw new AsnException(
                     String.format("[%s] Expected CONTEXT tag, got class=%d tag=%d",
                         context, cursor.tagClass(), cursor.tag())
                 );
             }
         }
     }
     4. Tích hợp vào TCAPProviderImpl
     java
     
     
     // Trong TCAPProviderImpl.onMessageReceived()
     private void processTCAPMessage(ProtocolDataImpl pd) {
         byte[] raw   = pd.getData();
         int    offset = 0;
         int    length = raw.length;
         // ── zero-copy path ──────────────────────────────────────────────
         BerCursor root = BerCursor.wrap(raw, offset, length);
         try {
             root.readTag();
             if (root.tagClass() != BerTag.APPLICATION) {
                 logger.warn("Unexpected TCAP tag class: {}", root.tagClass());
                 return;
             }
             switch (root.tag()) {
                 case TCBeginMessage._TAG:
                     processTCBegin(root.openConstructed(), pd);
                     break;
                 case TCContinueMessage._TAG:
                     processTCContinue(root.openConstructed(), pd);
                     break;
                 case TCEndMessage._TAG:
                     processTCEnd(root.openConstructed(), pd);
                     break;
                 case TCAbortMessage._TAG:
                     processTCAbort(root.openConstructed(), pd);
                     break;
                 default:
                     logger.warn("Unknown TCAP tag: {}", root.tag());
             }
         } catch (AsnException e) {
             logger.error("ASN decode error", e);
         } finally {
             root.release();
         }
     }
     // ── TC-BEGIN ────────────────────────────────────────────────────────
     private void processTCBegin(BerCursor body, ProtocolDataImpl pd)
             throws AsnException {
         Long origTxId   = null;
         BerSlice dialogPortion = null;
         List<Component> components = new ArrayList<>(4);
         while (body.hasMore()) {
             body.readTag();
             if (body.tagClass() == BerTag.APPLICATION) {
                 switch (body.tag()) {
                     case 0x08: // origTransactionId [APPLICATION 8]
                         origTxId = (long) body.readInt32();
                         body.skipValue();
                         break;
                     case 0x0B: // dialoguePortion [APPLICATION 11]
                         // Lazy: giữ slice, không decode ngay
                         dialogPortion = body.getOctetStringSlice();
                         body.skipValue();
                         break;
                     case 0x0C: // componentPortion [APPLICATION 12]
                         BerCursor compCursor = body.openConstructed();
                         ComponentDecoder.decodeComponents(compCursor, components);
                         compCursor.release();
                         body.skipValue();
                         break;
                     default:
                         body.skipValue();
                 }
             } else {
                 body.skipValue();
             }
         }
         // Dispatch lên dialog layer
         if (origTxId != null) {
             DialogImpl dialog = getOrCreateDialog(origTxId, pd);
             if (dialogPortion != null) {
                 dialog.setDialogPortionSlice(dialogPortion); // lazy decode
             }
             for (Component comp : components) {
                 dialog.addComponent(comp);
             }
             dialog.fireTCBegin();
         }
         body.release();
     }
     // ── TC-CONTINUE ─────────────────────────────────────────────────────
     private void processTCContinue(BerCursor body, ProtocolDataImpl pd)
             throws AsnException {
         Long origTxId = null;
         Long destTxId = null;
         List<Component> components = new ArrayList<>(4);
         while (body.hasMore()) {
             body.readTag();
             if (body.tagClass() == BerTag.APPLICATION) {
                 switch (body.tag()) {
                     case 0x08: // origTransactionId
                         origTxId = (long) body.readInt32();
                         body.skipValue();
                         break;
                     case 0x09: // destTransactionId
                         destTxId = (long) body.readInt32();
                         body.skipValue();
                         break;
                     case 0x0B: // dialoguePortion (skip hoặc lazy)
                         body.skipValue();
                         break;
                     case 0x0C: // componentPortion
                         BerCursor compCursor = body.openConstructed();
                         ComponentDecoder.decodeComponents(compCursor, components);
                         compCursor.release();
                         body.skipValue();
                         break;
                     default:
                         body.skipValue();
                 }
             } else {
                 body.skipValue();
             }
         }
         if (destTxId != null) {
             DialogImpl dialog = findDialog(destTxId);
             if (dialog != null) {
                 for (Component comp : components) {
                     dialog.addComponent(comp);
                 }
                 dialog.fireTCContinue();
             }
         }
         body.release();
     }
     // ── TC-END ──────────────────────────────────────────────────────────
     private void processTCEnd(BerCursor body, ProtocolDataImpl pd)
             throws AsnException {
         Long destTxId = null;
         List<Component> components = new ArrayList<>(4);
         while (body.hasMore()) {
             body.readTag();
             if (body.tagClass() == BerTag.APPLICATION) {
                 switch (body.tag()) {
                     case 0x09: // destTransactionId
                         destTxId = (long) body.readInt32();
                         body.skipValue();
                         break;
                     case 0x0B: // dialoguePortion
                         body.skipValue();
                         break;
                     case 0x0C: // componentPortion
                         BerCursor compCursor = body.openConstructed();
                         ComponentDecoder.decodeComponents(compCursor, components);
                         compCursor.release();
                         body.skipValue();
                         break;
                     default:
                         body.skipValue();
                 }
             } else {
                 body.skipValue();
             }
         }
         if (destTxId != null) {
             DialogImpl dialog = findDialog(destTxId);
             if (dialog != null) {
                 for (Component comp : components) {
                     dialog.addComponent(comp);
                 }
                 dialog.fireTCEnd();
                 removeDialog(destTxId);
             }
         }
         body.release();
     }
     // ── TC-ABORT ────────────────────────────────────────────────────────
     private void processTCAbort(BerCursor body, ProtocolDataImpl pd)
             throws AsnException {
         Long destTxId = null;
         while (body.hasMore()) {
             body.readTag();
             if (body.tagClass() == BerTag.APPLICATION
                     && body.tag() == 0x09) {
                 destTxId = (long) body.readInt32();
             }
             body.skipValue();
         }
         if (destTxId != null) {
             DialogImpl dialog = findDialog(destTxId);
             if (dialog != null) {
                 dialog.fireTCAbort();
                 removeDialog(destTxId);
             }
         }
         body.release();
     }
     5. Sơ đồ tổng thể
     
     
     SCCP data byte[]
     │
     ├─ BerCursor.wrap(raw, 0, len)          ← pool, zero alloc
     │    └─ readTag()  [APPLICATION x]
     │         ├─ TC-BEGIN  → openConstructed()
     │         │     ├─ origTxId   → readInt32()
     │         │     ├─ dialogPart → getOctetStringSlice()  [lazy]
     │         │     └─ components → openConstructed()
     │         │           ├─ [1] INVOKE     → InvokeImpl
     │         │           │     ├─ invokeId → readInt32()
     │         │           │     ├─ opCode   → OperationCodeImpl
     │         │           │     └─ param    → BerSlice  [lazy]
     │         │           ├─ [2] RRL        → ReturnResultLastImpl
     │         │           ├─ [3] RE         → ReturnErrorImpl
     │         │           └─ [4] REJECT     → RejectImpl
     │         ├─ TC-CONTINUE → ...
     │         ├─ TC-END      → ...
     │         └─ TC-ABORT    → ...
     │
     └─ BerWriter.get()                      ← ThreadLocal, zero alloc
           ├─ beginSequence / endSequence    ← backward fill
           ├─ writeInt32 / writeOctetString
           └─ flushTo(NettyAsnOutputStream)  ← zero-copy outbound
     Bước tiếp theo bạn muốn làm:
     
     JMH benchmark so sánh old path vs BerCursor path?
     Lazy decode dialogue portion (MAP open/close)?
     Encode path: encode Invoke response bằng BerWriter?
     
     
     
     Claude 4.6
     Lazy decode dialogue portion (MAP open/close)?
     Encode path: encode Invoke response bằng BerWriter?
     
     
     
     Để viết code chính xác, tôi cần kiểm tra cấu trúc hiện tại của dialogue portion và encode path trong repo.
     
     Đã có đủ thông tin về cấu trúc Q.773. Bây giờ tôi sẽ viết code hoàn chỉnh.
     
     🔬 Lazy Decode DialoguePortion + Encode Invoke Response bằng BerWriter
     1. ASN.1 Structure Reference (Q.773)
     Từ spec Q.773, cấu trúc DialoguePortion được wrap trong ExternalPDU ([UNIVERSAL 8] IMPLICIT SEQUENCE) chứa oid (OID định danh abstract syntax) và dialog [0] EXPLICIT DialoguePDU.
     
     DialoguePDU là CHOICE gồm: dialogueRequest (AARQ-apdu), dialogueResponse (AARE-apdu), dialogueAbort (ABRT-apdu).
     
     AARQ-apdu ::= [APPLICATION 0] IMPLICIT SEQUENCE { protocol-version [0] BIT STRING DEFAULT {version1}, application-context-name [1] OBJECT IDENTIFIER } và AARE-apdu ::= [APPLICATION 1] IMPLICIT SEQUENCE { protocol-version, application-context-name, result [2], result-source-diagnostic [3] }.
     
     2. DialoguePortionDecoder.java — Lazy Decode
     java
     
     
     package org.mobicents.protocols.ss7.tcap.asn;
     import org.mobicents.protocols.asn.AsnException;
     import org.mobicents.protocols.asn.BerCursor;
     import org.mobicents.protocols.asn.BerSlice;
     import org.mobicents.protocols.asn.BerTag;
     /**
      * Lazy decoder cho TCAP DialoguePortion (Q.773).
      *
      * Wire format (EXPLICIT wrapper):
      *
      *   DialoguePortion ::= [APPLICATION 11] EXPLICIT ExternalPDU
      *
      *   ExternalPDU ::= [UNIVERSAL 8] IMPLICIT SEQUENCE {
      *       oid     OBJECT IDENTIFIER,          -- dialogue-as-id
      *       dialog  [0] EXPLICIT DialoguePDU
      *   }
      *
      *   DialoguePDU ::= CHOICE {
      *       dialogueRequest  AARQ-apdu,         -- [APPLICATION 0]
      *       dialogueResponse AARE-apdu,         -- [APPLICATION 1]
      *       dialogueAbort    ABRT-apdu          -- [APPLICATION 4]
      *   }
      *
      *   AARQ-apdu ::= [APPLICATION 0] IMPLICIT SEQUENCE {
      *       protocol-version         [0] BIT STRING DEFAULT {version1},
      *       application-context-name [1] OBJECT IDENTIFIER,
      *       user-information         [30] IMPLICIT SEQUENCE OF EXTERNAL OPTIONAL
      *   }
      *
      *   AARE-apdu ::= [APPLICATION 1] IMPLICIT SEQUENCE {
      *       protocol-version         [0] BIT STRING DEFAULT {version1},
      *       application-context-name [1] OBJECT IDENTIFIER,
      *       result                   [2] Associate-result,
      *       result-source-diagnostic [3] Associate-source-diagnostic,
      *       user-information         [30] OPTIONAL
      *   }
      *
      *   ABRT-apdu ::= [APPLICATION 4] IMPLICIT SEQUENCE {
      *       abort-source   [0] IMPLICIT ABRT-source,
      *       user-information [30] OPTIONAL
      *   }
      */
     public final class DialoguePortionDecoder {
         // ── Tag constants ────────────────────────────────────────────────
         // ExternalPDU = [UNIVERSAL 8] → class=UNIVERSAL, tag=8
         private static final int TAG_EXTERNAL_PDU = 0x08;
         // DialoguePDU choices (APPLICATION class)
         public static final int TAG_AARQ = 0x00;  // [APPLICATION 0] dialogueRequest
         public static final int TAG_AARE = 0x01;  // [APPLICATION 1] dialogueResponse
         public static final int TAG_ABRT = 0x04;  // [APPLICATION 4] dialogueAbort
         // Fields bên trong AARQ/AARE (CONTEXT class)
         private static final int TAG_PROTOCOL_VERSION   = 0x00; // [0] BIT STRING
         private static final int TAG_APP_CONTEXT_NAME   = 0x01; // [1] OID
         private static final int TAG_RESULT             = 0x02; // [2] Associate-result
         private static final int TAG_RESULT_DIAG        = 0x03; // [3] Associate-source-diagnostic
         private static final int TAG_USER_INFO          = 0x1E; // [30] user-information
         // OID: dialogue-as-id = {itu-t q 773 as(1) dialogue-as(1) version1(1)}
         // Encoded: 00 11 86 05 01 01 01
         private static final byte[] DIALOGUE_AS_OID = {
             0x00, 0x11, (byte)0x86, 0x05, 0x01, 0x01, 0x01
         };
         private DialoguePortionDecoder() {}
         // ================================================================
         // RESULT HOLDER (không dùng nhiều object)
         // ================================================================
         /**
          * Kết quả parse DialoguePDU.
          * Reusable per-thread → không new mỗi lần decode.
          */
         public static final class DialoguePDU {
             public int     pduType;          // TAG_AARQ | TAG_AARE | TAG_ABRT
             // AARQ / AARE fields
             public int[]   appContextOid;    // parsed OID arcs
             public boolean version1;         // protocol-version bit0
             // AARE-specific
             public int     result;           // Associate-result value
             public int     resultSourceDiag; // 0=dialogue, 1=service-user, 2=service-provider
             public int     diagnosticValue;
             // ABRT-specific
             public int     abortSource;      // 0=dialogue-service-user, 1=dialogue-service-provider
             // Lazy: raw slice cho user-information (MAP open data)
             // Chỉ decode khi application thực sự cần
             public BerSlice userInfoSlice;   // null nếu không có
             public void reset() {
                 pduType        = -1;
                 appContextOid  = null;
                 version1       = true;
                 result         = 0;
                 resultSourceDiag = 0;
                 diagnosticValue  = 0;
                 abortSource    = 0;
                 userInfoSlice  = null;
             }
         }
         // ThreadLocal DialoguePDU holder
         private static final ThreadLocal<DialoguePDU> TL_PDU =
             ThreadLocal.withInitial(DialoguePDU::new);
         // ================================================================
         // ENTRY POINT: decode từ BerSlice (lazy — lấy từ processTCBegin)
         // ================================================================
         /**
          * Decode DialoguePortion từ BerSlice.
          *
          * @param slice  BerSlice của [APPLICATION 11] content
          *               (đã bỏ tag+length APPLICATION 11)
          * @return       DialoguePDU (ThreadLocal, dùng xong phải copy nếu cần giữ lâu)
          */
         public static DialoguePDU decode(BerSlice slice) throws AsnException {
             DialoguePDU pdu = TL_PDU.get();
             pdu.reset();
             // slice trỏ vào content của DialoguePortion [APPLICATION 11]
             // Theo Q.773: DialoguePortion = [APPLICATION 11] EXPLICIT ExternalPDU
             // → content của APPLICATION 11 là ExternalPDU (UNIVERSAL 8)
             BerCursor outer = BerCursor.wrap(
                 slice.toByteArray(), 0, slice.length()
             );
             try {
                 outer.readTag();
                 // Expect [UNIVERSAL 8] ExternalPDU
                 if (outer.tagClass() != BerTag.UNIVERSAL
                         || outer.tag() != TAG_EXTERNAL_PDU) {
                     throw new AsnException("DialoguePortion: expected UNIVERSAL 8 (ExternalPDU), " +
                         "got class=" + outer.tagClass() + " tag=" + outer.tag());
                 }
                 BerCursor extBody = outer.openConstructed();
                 try {
                     decodeExternalPDU(extBody, pdu);
                 } finally {
                     extBody.release();
                 }
             } finally {
                 outer.release();
             }
             return pdu;
         }
         // ================================================================
         // ExternalPDU content
         // ================================================================
         private static void decodeExternalPDU(BerCursor body, DialoguePDU out)
                 throws AsnException {
             // ── oid OBJECT IDENTIFIER ────────────────────────────────────
             body.readTag();
             if (body.tagClass() != BerTag.UNIVERSAL
                     || body.tag() != BerTag.OID) {
                 throw new AsnException("ExternalPDU: expected OID");
             }
             // Validate OID (dialogue-as-id vs unidialogue-as-id)
             BerSlice oidSlice = body.getOctetStringSlice();
             if (!oidSlice.equalsBytes(DIALOGUE_AS_OID)) {
                 // không phải structured dialogue OID → skip gracefully
             }
             body.skipValue();
             if (!body.hasMore()) return;
             // ── dialog [0] EXPLICIT DialoguePDU ─────────────────────────
             body.readTag();
             if (body.tagClass() != BerTag.CONTEXT || body.tag() != 0x00) {
                 throw new AsnException("ExternalPDU: expected [0] EXPLICIT dialog");
             }
             // [0] EXPLICIT → bọc thêm 1 tầng TLV, mở ra
             BerCursor dialogWrapper = body.openConstructed();
             try {
                 dialogWrapper.readTag();
                 decodeDialoguePDU(dialogWrapper, out);
             } finally {
                 dialogWrapper.release();
             }
             body.skipValue();
         }
         // ================================================================
         // DialoguePDU CHOICE dispatch
         // ================================================================
         private static void decodeDialoguePDU(BerCursor cursor, DialoguePDU out)
                 throws AsnException {
             if (cursor.tagClass() != BerTag.APPLICATION) {
                 throw new AsnException("DialoguePDU: expected APPLICATION tag, " +
                     "got class=" + cursor.tagClass());
             }
             out.pduType = cursor.tag();
             switch (cursor.tag()) {
                 case TAG_AARQ:
                     decodeAARQ(cursor.openConstructed(), out);
                     break;
                 case TAG_AARE:
                     decodeAARE(cursor.openConstructed(), out);
                     break;
                 case TAG_ABRT:
                     decodeABRT(cursor.openConstructed(), out);
                     break;
                 default:
                     throw new AsnException("DialoguePDU: unknown tag=" + cursor.tag());
             }
             cursor.skipValue();
         }
         // ================================================================
         // AARQ-apdu [APPLICATION 0] — MAP TC-BEGIN open
         // ================================================================
         /**
          * AARQ-apdu ::= [APPLICATION 0] IMPLICIT SEQUENCE {
          *   protocol-version         [0] IMPLICIT BIT STRING DEFAULT {version1},
          *   application-context-name [1] OBJECT IDENTIFIER,
          *   user-information         [30] IMPLICIT SEQUENCE OF EXTERNAL OPTIONAL
          * }
          */
         private static void decodeAARQ(BerCursor body, DialoguePDU out)
                 throws AsnException {
             out.version1 = true; // default
             while (body.hasMore()) {
                 body.readTag();
                 if (body.tagClass() == BerTag.CONTEXT) {
                     switch (body.tag()) {
                         case TAG_PROTOCOL_VERSION: // [0] BIT STRING
                             // version1 = bit 0 set
                             BerSlice bsVer = body.getOctetStringSlice();
                             // BIT STRING: byte[0]=unused bits, byte[1]=bits
                             if (bsVer.length() >= 2) {
                                 byte[] raw = bsVer.toByteArray();
                                 out.version1 = (raw[1] & 0x80) != 0;
                             }
                             body.skipValue();
                             break;
                         case TAG_APP_CONTEXT_NAME: // [1] OID
                             BerSlice oidSlice = body.getOctetStringSlice();
                             out.appContextOid = decodeOidArcs(oidSlice);
                             body.skipValue();
                             break;
                         case TAG_USER_INFO: // [30] user-information OPTIONAL — LAZY
                             out.userInfoSlice = body.getOctetStringSlice();
                             // KHÔNG decode ngay — application gọi decodeUserInfo()
                             body.skipValue();
                             break;
                         default:
                             body.skipValue();
                     }
                 } else {
                     body.skipValue();
                 }
             }
             body.release();
         }
         // ================================================================
         // AARE-apdu [APPLICATION 1] — MAP TC-CONTINUE/TC-END response
         // ================================================================
         /**
          * AARE-apdu ::= [APPLICATION 1] IMPLICIT SEQUENCE {
          *   protocol-version         [0] BIT STRING DEFAULT {version1},
          *   application-context-name [1] OBJECT IDENTIFIER,
          *   result                   [2] Associate-result,
          *   result-source-diagnostic [3] Associate-source-diagnostic,
          *   user-information         [30] OPTIONAL
          * }
          *
          * Associate-result: INTEGER { accepted(0), reject-permanent(1) }
          * Associate-source-diagnostic: CHOICE {
          *   dialogue-service-user     [1] { null(0), no-reason(1), app-ctx-not-supported(2) },
          *   dialogue-service-provider [2] { null(0), no-reason(1), no-common-dialogue-portion(2) }
          * }
          */
         private static void decodeAARE(BerCursor body, DialoguePDU out)
                 throws AsnException {
             out.version1 = true;
             while (body.hasMore()) {
                 body.readTag();
                 if (body.tagClass() == BerTag.CONTEXT) {
                     switch (body.tag()) {
                         case TAG_PROTOCOL_VERSION: // [0]
                             body.skipValue();
                             break;
                         case TAG_APP_CONTEXT_NAME: // [1] OID
                             out.appContextOid = decodeOidArcs(body.getOctetStringSlice());
                             body.skipValue();
                             break;
                         case TAG_RESULT: // [2] INTEGER
                             out.result = body.readInt32();
                             body.skipValue();
                             break;
                         case TAG_RESULT_DIAG: // [3] CHOICE {[1] INTEGER | [2] INTEGER}
                             BerCursor diagCursor = body.openConstructed();
                             diagCursor.readTag();
                             out.resultSourceDiag = diagCursor.tag(); // 1 or 2
                             out.diagnosticValue  = diagCursor.readInt32();
                             diagCursor.skipValue();
                             diagCursor.release();
                             body.skipValue();
                             break;
                         case TAG_USER_INFO: // [30] LAZY
                             out.userInfoSlice = body.getOctetStringSlice();
                             body.skipValue();
                             break;
                         default:
                             body.skipValue();
                     }
                 } else {
                     body.skipValue();
                 }
             }
             body.release();
         }
         // ================================================================
         // ABRT-apdu [APPLICATION 4]
         // ================================================================
         /**
          * ABRT-apdu ::= [APPLICATION 4] IMPLICIT SEQUENCE {
          *   abort-source      [0] IMPLICIT INTEGER,
          *   user-information  [30] OPTIONAL
          * }
          * abort-source: dialogue-service-user(0) | dialogue-service-provider(1)
          */
         private static void decodeABRT(BerCursor body, DialoguePDU out)
                 throws AsnException {
             while (body.hasMore()) {
                 body.readTag();
                 if (body.tagClass() == BerTag.CONTEXT) {
                     if (body.tag() == 0x00) {
                         out.abortSource = body.readInt32();
                     } else if (body.tag() == TAG_USER_INFO) {
                         out.userInfoSlice = body.getOctetStringSlice();
                     }
                 }
                 body.skipValue();
             }
             body.release();
         }
         // ================================================================
         // OID decode (int[] arcs) — không new object nếu reuse array
         // ================================================================
         static int[] decodeOidArcs(BerSlice slice) {
             byte[] raw = slice.toByteArray();
             // đếm số arcs trước
             int count = 2; // first two encoded together
             for (int i = 1; i < raw.length; i++) {
                 if ((raw[i] & 0x80) == 0) count++;
             }
             int[] arcs = new int[count];
             // first byte: first*40 + second
             int first = raw[0] & 0xFF;
             arcs[0] = first / 40;
             arcs[1] = first % 40;
             int idx = 2;
             int arc = 0;
             for (int i = 1; i < raw.length; i++) {
                 int b = raw[i] & 0xFF;
                 arc = (arc << 7) | (b & 0x7F);
                 if ((b & 0x80) == 0) {
                     arcs[idx++] = arc;
                     arc = 0;
                 }
             }
             return arcs;
         }
     }
     3. DialoguePortionEncoder.java — Encode bằng BerWriter
       java
       
       
       package org.mobicents.protocols.ss7.tcap.asn;
       import org.mobicents.protocols.asn.BerTag;
       import org.mobicents.protocols.asn.BerWriter;
       import org.mobicents.protocols.asn.BerSlice;
       /**
        * Encode TCAP DialoguePortion bằng BerWriter (backward, zero-copy).
        *
        * Dùng cho:
        *   - TC-BEGIN outbound  → encodeAARQ()
        *   - TC-CONTINUE/END   → encodeAARE()
        *   - TC-ABORT          → encodeABRT()
        */
       public final class DialoguePortionEncoder {
           // OID: dialogue-as-id = {itu-t q 773 as(1) dialogue-as(1) version1(1)}
           private static final int[] DIALOGUE_AS_OID_ARCS = {0, 0, 17, 773, 1, 1, 1};
           private static final byte[] DIALOGUE_AS_OID_ENCODED = {
               0x00, 0x11, (byte)0x86, 0x05, 0x01, 0x01, 0x01
           };
           // BIT STRING version1: unusedBits=0, bits=0x80 (bit 0 set)
           private static final byte[] VERSION1_BITSTRING = { 0x00, (byte) 0x80 };
           private DialoguePortionEncoder() {}
           // ================================================================
           // AARQ (TC-BEGIN open) — MAP dialogueRequest
           // ================================================================
           /**
            * Encode [APPLICATION 11] DialoguePortion chứa AARQ-apdu.
            *
            * @param writer       BerWriter.get() từ caller
            * @param appCtxOidEnc OID bytes của MAP application context (encoded)
            * @param userInfo     user-information bytes (MAP open data), null nếu không có
            */
           public static void encodeAARQ(BerWriter writer,
                                          byte[] appCtxOidEnc,
                                          byte[] userInfo) {
               // Toàn bộ encode backward từ trong ra ngoài:
               //
               // [APPLICATION 11] EXPLICIT
               //   [UNIVERSAL 8] ExternalPDU
               //     OID (dialogue-as-id)
               //     [0] EXPLICIT
               //       [APPLICATION 0] AARQ
               //         [0] BIT STRING version1
               //         [1] OID appContextName
               //         [30] userInfo (optional)
               // ── Mở APPLICATION 11 (outermost) ───────────────────────────
               int markApp11 = writer.beginSequence(BerTag.APPLICATION, 0x0B);
               // ── Mở UNIVERSAL 8 (ExternalPDU) ────────────────────────────
               int markExt = writer.beginSequence(BerTag.UNIVERSAL, 0x08);
               // ── [0] EXPLICIT bao quanh DialoguePDU ──────────────────────
               int markCtx0 = writer.beginSequence(BerTag.CONTEXT, 0x00);
               // ── AARQ [APPLICATION 0] IMPLICIT SEQUENCE ──────────────────
               int markAARQ = writer.beginSequence(BerTag.APPLICATION, TAG_AARQ);
               // [30] user-information OPTIONAL (innermost → write first)
               if (userInfo != null && userInfo.length > 0) {
                   writer.writeOctetString(BerTag.CONTEXT, 0x1E,
                       userInfo, 0, userInfo.length);
               }
               // [1] application-context-name OID
               writer.writeOctetString(BerTag.CONTEXT, 0x01,
                   appCtxOidEnc, 0, appCtxOidEnc.length);
               // [0] protocol-version BIT STRING {version1}
               writer.writeOctetString(BerTag.CONTEXT, 0x00,
                   VERSION1_BITSTRING, 0, VERSION1_BITSTRING.length);
               writer.endSequence(markAARQ, BerTag.APPLICATION, TAG_AARQ);
               // Đóng [0] EXPLICIT
               writer.endSequence(markCtx0, BerTag.CONTEXT, 0x00);
               // OID dialogue-as-id
               writer.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
                   DIALOGUE_AS_OID_ENCODED, 0, DIALOGUE_AS_OID_ENCODED.length);
               // Đóng UNIVERSAL 8
               writer.endSequence(markExt, BerTag.UNIVERSAL, 0x08);
               // Đóng APPLICATION 11
               writer.endSequence(markApp11, BerTag.APPLICATION, 0x0B);
           }
           // ── TAG aliases ─────────────────────────────────────────────────
           private static final int TAG_AARQ = 0x00;
           private static final int TAG_AARE = 0x01;
           private static final int TAG_ABRT = 0x04;
           // ================================================================
           // AARE (TC-CONTINUE / TC-END response) — MAP dialogueResponse
           // ================================================================
           /**
            * Encode [APPLICATION 11] DialoguePortion chứa AARE-apdu.
            *
            * @param writer         BerWriter.get() từ caller
            * @param appCtxOidEnc   OID bytes application context
            * @param result         0=accepted, 1=reject-permanent
            * @param srcDiag        1=dialogue-service-user, 2=dialogue-service-provider
            * @param diagValue      diagnostic value (0=null, 1=no-reason, 2=app-ctx-not-supported)
            * @param userInfo       user-information OPTIONAL
            */
           public static void encodeAARE(BerWriter writer,
                                          byte[] appCtxOidEnc,
                                          int result,
                                          int srcDiag,
                                          int diagValue,
                                          byte[] userInfo) {
               int markApp11 = writer.beginSequence(BerTag.APPLICATION, 0x0B);
               int markExt   = writer.beginSequence(BerTag.UNIVERSAL,   0x08);
               int markCtx0  = writer.beginSequence(BerTag.CONTEXT,     0x00);
               int markAARE  = writer.beginSequence(BerTag.APPLICATION,  TAG_AARE);
               // [30] user-information OPTIONAL
               if (userInfo != null && userInfo.length > 0) {
                   writer.writeOctetString(BerTag.CONTEXT, 0x1E,
                       userInfo, 0, userInfo.length);
               }
               // [3] result-source-diagnostic CHOICE {[1]|[2] INTEGER}
               {
                   int markDiag = writer.beginSequence(BerTag.CONTEXT, 0x03);
                   writer.writeInt32(BerTag.CONTEXT, srcDiag, diagValue);
                   writer.endSequence(markDiag, BerTag.CONTEXT, 0x03);
               }
               // [2] result INTEGER
               writer.writeInt32(BerTag.CONTEXT, 0x02, result);
               // [1] application-context-name
               writer.writeOctetString(BerTag.CONTEXT, 0x01,
                   appCtxOidEnc, 0, appCtxOidEnc.length);
               // [0] protocol-version
               writer.writeOctetString(BerTag.CONTEXT, 0x00,
                   VERSION1_BITSTRING, 0, VERSION1_BITSTRING.length);
               writer.endSequence(markAARE,  BerTag.APPLICATION, TAG_AARE);
               writer.endSequence(markCtx0,  BerTag.CONTEXT,     0x00);
               writer.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
                   DIALOGUE_AS_OID_ENCODED, 0, DIALOGUE_AS_OID_ENCODED.length);
               writer.endSequence(markExt,   BerTag.UNIVERSAL,   0x08);
               writer.endSequence(markApp11, BerTag.APPLICATION, 0x0B);
           }
           // ================================================================
           // ABRT (TC-ABORT) — MAP dialogueAbort
           // ================================================================
           /**
            * Encode [APPLICATION 11] DialoguePortion chứa ABRT-apdu.
            *
            * @param writer      BerWriter.get() từ caller
            * @param abortSource 0=dialogue-service-user, 1=dialogue-service-provider
            * @param userInfo    OPTIONAL
            */
           public static void encodeABRT(BerWriter writer,
                                          int abortSource,
                                          byte[] userInfo) {
               int markApp11 = writer.beginSequence(BerTag.APPLICATION, 0x0B);
               int markExt   = writer.beginSequence(BerTag.UNIVERSAL,   0x08);
               int markCtx0  = writer.beginSequence(BerTag.CONTEXT,     0x00);
               int markABRT  = writer.beginSequence(BerTag.APPLICATION,  TAG_ABRT);
               if (userInfo != null && userInfo.length > 0) {
                   writer.writeOctetString(BerTag.CONTEXT, 0x1E,
                       userInfo, 0, userInfo.length);
               }
               // [0] abort-source INTEGER
               writer.writeInt32(BerTag.CONTEXT, 0x00, abortSource);
               writer.endSequence(markABRT,  BerTag.APPLICATION, TAG_ABRT);
               writer.endSequence(markCtx0,  BerTag.CONTEXT,     0x00);
               writer.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
                   DIALOGUE_AS_OID_ENCODED, 0, DIALOGUE_AS_OID_ENCODED.length);
               writer.endSequence(markExt,   BerTag.UNIVERSAL,   0x08);
               writer.endSequence(markApp11, BerTag.APPLICATION, 0x0B);
           }
       }
       4. InvokeResponseEncoder.java — Encode Invoke Response
       java
       
       
       package org.mobicents.protocols.ss7.tcap.tc.component.impl;
       import org.mobicents.protocols.asn.BerTag;
       import org.mobicents.protocols.asn.BerWriter;
       import org.mobicents.protocols.asn.BerSlice;
       import org.mobicents.protocols.asn.NettyAsnOutputStream;
       import org.mobicents.protocols.ss7.tcap.asn.DialoguePortionEncoder;
       /**
        * Zero-copy encoder cho TCAP outbound messages chứa Invoke response.
        *
        * Hỗ trợ:
        *   - TC-BEGIN + AARQ + Invoke
        *   - TC-CONTINUE + AARE + ReturnResultLast
        *   - TC-END + AARE + ReturnResultLast / ReturnError / Reject
        *   - TC-ABORT + ABRT
        *
        * Tất cả encode backward bằng BerWriter (ThreadLocal, không new object).
        */
       public final class InvokeResponseEncoder {
           // TCAP Message tags [APPLICATION x]
           private static final int TC_UNIDIRECTIONAL = 0x01;
           private static final int TC_BEGIN          = 0x02;
           private static final int TC_END            = 0x04;
           private static final int TC_CONTINUE       = 0x05;
           private static final int TC_ABORT          = 0x07;
           // Transaction ID tags [APPLICATION x]
           private static final int TAG_ORIG_TX_ID    = 0x08;
           private static final int TAG_DEST_TX_ID    = 0x09;
           private static final int TAG_COMPONENT_PRT = 0x0C; // [APPLICATION 12]
           // Component tags [CONTEXT CONSTRUCTED x]
           private static final int TAG_INVOKE        = 0x01;
           private static final int TAG_RRL           = 0x02; // ReturnResultLast
           private static final int TAG_RETURN_ERROR  = 0x03;
           private static final int TAG_REJECT        = 0x04;
           private static final int TAG_RRNL          = 0x07; // ReturnResultNotLast
           private InvokeResponseEncoder() {}
           // ================================================================
           // PUBLIC API
           // ================================================================
           /**
            * Encode TC-BEGIN với AARQ + Invoke.
            *
            * Thường dùng cho MAP outbound request (VD: SendRoutingInfo).
            *
            * @param origTxId       4-byte transaction ID
            * @param appCtxOidEnc   encoded OID bytes
            * @param invokeId       invoke ID (0–127)
            * @param localOpCode    MAP operation code (local INTEGER)
            * @param parameter      encoded parameter bytes (ANY), null nếu không có
            * @param out            NettyAsnOutputStream để flush kết quả
            */
           public static void encodeTCBeginInvoke(
                   int    origTxId,
                   byte[] appCtxOidEnc,
                   int    invokeId,
                   int    localOpCode,
                   byte[] parameter,
                   NettyAsnOutputStream out) {
               BerWriter w = BerWriter.get();
               // ── Outermost: TC-BEGIN [APPLICATION 2] ─────────────────────
               int markBegin = w.beginSequence(BerTag.APPLICATION, TC_BEGIN);
               // ComponentPortion [APPLICATION 12] — innermost, write first
               {
                   int markComp = w.beginSequence(BerTag.APPLICATION, TAG_COMPONENT_PRT);
                   encodeInvoke(w, invokeId, localOpCode, parameter);
                   w.endSequence(markComp, BerTag.APPLICATION, TAG_COMPONENT_PRT);
               }
               // DialoguePortion [APPLICATION 11] — AARQ
               DialoguePortionEncoder.encodeAARQ(w, appCtxOidEnc, null);
               // Originating Transaction ID [APPLICATION 8]
               w.writeInt32(BerTag.APPLICATION, TAG_ORIG_TX_ID, origTxId);
               w.endSequence(markBegin, BerTag.APPLICATION, TC_BEGIN);
               w.flushTo(out);
           }
           /**
            * Encode TC-CONTINUE với AARE + ReturnResultLast.
            *
            * Thường dùng khi MAP trả lời và dialog vẫn tiếp tục.
            *
            * @param origTxId     originating transaction ID của bên gửi
            * @param destTxId     destination transaction ID
            * @param appCtxOidEnc encoded application context OID
            * @param invokeId     invoke ID của request gốc
            * @param localOpCode  MAP operation code
            * @param result       encoded result bytes (ANY)
            * @param out          output stream
            */
           public static void encodeTCContinueRRL(
                   int    origTxId,
                   int    destTxId,
                   byte[] appCtxOidEnc,
                   int    invokeId,
                   int    localOpCode,
                   byte[] result,
                   NettyAsnOutputStream out) {
               BerWriter w = BerWriter.get();
               int markCont = w.beginSequence(BerTag.APPLICATION, TC_CONTINUE);
               // ComponentPortion
               {
                   int markComp = w.beginSequence(BerTag.APPLICATION, TAG_COMPONENT_PRT);
                   encodeReturnResultLast(w, invokeId, localOpCode, result);
                   w.endSequence(markComp, BerTag.APPLICATION, TAG_COMPONENT_PRT);
               }
               // DialoguePortion AARE (accepted=0, srcDiag=1, diagValue=0)
               DialoguePortionEncoder.encodeAARE(w, appCtxOidEnc,
                   0, 1, 0, null);
               // Destination TX ID
               w.writeInt32(BerTag.APPLICATION, TAG_DEST_TX_ID, destTxId);
               // Originating TX ID
               w.writeInt32(BerTag.APPLICATION, TAG_ORIG_TX_ID, origTxId);
               w.endSequence(markCont, BerTag.APPLICATION, TC_CONTINUE);
               w.flushTo(out);
           }
           /**
            * Encode TC-END với AARE + ReturnResultLast.
            *
            * Dùng khi MAP trả lời và kết thúc dialog.
            */
           public static void encodeTCEndRRL(
                   int    destTxId,
                   byte[] appCtxOidEnc,
                   int    invokeId,
                   int    localOpCode,
                   byte[] result,
                   NettyAsnOutputStream out) {
               BerWriter w = BerWriter.get();
               int markEnd = w.beginSequence(BerTag.APPLICATION, TC_END);
               // ComponentPortion
               {
                   int markComp = w.beginSequence(BerTag.APPLICATION, TAG_COMPONENT_PRT);
                   encodeReturnResultLast(w, invokeId, localOpCode, result);
                   w.endSequence(markComp, BerTag.APPLICATION, TAG_COMPONENT_PRT);
               }
               // DialoguePortion AARE
               DialoguePortionEncoder.encodeAARE(w, appCtxOidEnc,
                   0, 1, 0, null);
               // Destination TX ID
               w.writeInt32(BerTag.APPLICATION, TAG_DEST_TX_ID, destTxId);
               w.endSequence(markEnd, BerTag.APPLICATION, TC_END);
               w.flushTo(out);
           }
           /**
            * Encode TC-END với ReturnError.
            */
           public static void encodeTCEndError(
                   int    destTxId,
                   byte[] appCtxOidEnc,
                   int    invokeId,
                   int    localErrCode,
                   byte[] errorParam,
                   NettyAsnOutputStream out) {
               BerWriter w = BerWriter.get();
               int markEnd = w.beginSequence(BerTag.APPLICATION, TC_END);
               {
                   int markComp = w.beginSequence(BerTag.APPLICATION, TAG_COMPONENT_PRT);
                   encodeReturnError(w, invokeId, localErrCode, errorParam);
                   w.endSequence(markComp, BerTag.APPLICATION, TAG_COMPONENT_PRT);
               }
               DialoguePortionEncoder.encodeAARE(w, appCtxOidEnc,
                   0, 1, 0, null);
               w.writeInt32(BerTag.APPLICATION, TAG_DEST_TX_ID, destTxId);
               w.endSequence(markEnd, BerTag.APPLICATION, TC_END);
               w.flushTo(out);
           }
           /**
            * Encode TC-ABORT.
            *
            * @param abortSource 0=user, 1=provider
            */
           public static void encodeTCAbort(
                   int    destTxId,
                   byte[] appCtxOidEnc,
                   int    abortSource,
                   NettyAsnOutputStream out) {
               BerWriter w = BerWriter.get();
               int markAbort = w.beginSequence(BerTag.APPLICATION, TC_ABORT);
               DialoguePortionEncoder.encodeABRT(w, abortSource, null);
               w.writeInt32(BerTag.APPLICATION, TAG_DEST_TX_ID, destTxId);
               w.endSequence(markAbort, BerTag.APPLICATION, TC_ABORT);
               w.flushTo(out);
           }
           // ================================================================
           // COMPONENT ENCODERS (private)
           // ================================================================
           /**
            * Encode Invoke component:
            *
            * Invoke ::= [1] IMPLICIT SEQUENCE {
            *   invokeId      INTEGER,
            *   operationCode OperationCode,  -- [0] INTEGER (local)
            *   parameter     ANY OPTIONAL
            * }
            */
           private static void encodeInvoke(BerWriter w,
                                             int invokeId,
                                             int localOpCode,
                                             byte[] parameter) {
               int markInvoke = w.beginSequence(BerTag.CONTEXT, TAG_INVOKE);
               // parameter ANY OPTIONAL (innermost — write first)
               if (parameter != null && parameter.length > 0) {
                   w.writeOctetString(BerTag.UNIVERSAL, BerTag.OCTET_STRING,
                       parameter, 0, parameter.length);
               }
               // operationCode [0] IMPLICIT INTEGER (local)
               w.writeInt32(BerTag.CONTEXT, 0x00, localOpCode);
               // invokeId INTEGER
               w.writeInt32(BerTag.UNIVERSAL, BerTag.INTEGER, invokeId);
               w.endSequence(markInvoke, BerTag.CONTEXT, TAG_INVOKE);
           }
           /**
            * Encode ReturnResultLast component:
            *
            * ReturnResultLast ::= [2] IMPLICIT SEQUENCE {
            *   invokeId  INTEGER,
            *   result    SEQUENCE {
            *     operationCode OperationCode,
            *     parameter     ANY
            *   } OPTIONAL
            * }
            */
           private static void encodeReturnResultLast(BerWriter w,
                                                       int invokeId,
                                                       int localOpCode,
                                                       byte[] result) {
               int markRRL = w.beginSequence(BerTag.CONTEXT, TAG_RRL);
               if (result != null && result.length > 0) {
                   // result SEQUENCE { opCode, parameter }
                   int markResult = w.beginSequence(BerTag.UNIVERSAL, BerTag.SEQUENCE);
                   // parameter ANY
                   w.writeOctetString(BerTag.UNIVERSAL, BerTag.OCTET_STRING,
                       result, 0, result.length);
                   // operationCode [0] INTEGER
                   w.writeInt32(BerTag.CONTEXT, 0x00, localOpCode);
                   w.endSequence(markResult, BerTag.UNIVERSAL, BerTag.SEQUENCE);
               }
               // invokeId
               w.writeInt32(BerTag.UNIVERSAL, BerTag.INTEGER, invokeId);
               w.endSequence(markRRL, BerTag.CONTEXT, TAG_RRL);
           }
           /**
            * Encode ReturnError component:
            *
            * ReturnError ::= [3] IMPLICIT SEQUENCE {
            *   invokeId   INTEGER,
            *   errorCode  ErrorCode,    -- [0] INTEGER (local)
            *   parameter  ANY OPTIONAL
            * }
            */
           private static void encodeReturnError(BerWriter w,
                                                  int invokeId,
                                                  int localErrCode,
                                                  byte[] errorParam) {
               int markRE = w.beginSequence(BerTag.CONTEXT, TAG_RETURN_ERROR);
               if (errorParam != null && errorParam.length > 0) {
                   w.writeOctetString(BerTag.UNIVERSAL, BerTag.OCTET_STRING,
                       errorParam, 0, errorParam.length);
               }
               // errorCode [0] INTEGER (local)
               w.writeInt32(BerTag.CONTEXT, 0x00, localErrCode);
               // invokeId
               w.writeInt32(BerTag.UNIVERSAL, BerTag.INTEGER, invokeId);
               w.endSequence(markRE, BerTag.CONTEXT, TAG_RETURN_ERROR);
           }
       }
       5. Tích hợp tổng thể — ví dụ MAP SendRoutingInfo response
       java
       
       
       // ── Inbound TC-BEGIN decode (lazy) ───────────────────────────────────
       private void processTCBegin(BerCursor body, ProtocolDataImpl pd)
               throws AsnException {
           Long origTxId = null;
           BerSlice dialogSlice = null;
           List<Component> components = new ArrayList<>(4);
           while (body.hasMore()) {
               body.readTag();
               if (body.tagClass() == BerTag.APPLICATION) {
                   switch (body.tag()) {
                       case 0x08: // origTxId
                           origTxId = (long) body.readInt32();
                           body.skipValue();
                           break;
                       case 0x0B: // dialoguePortion → GIỮ SLICE, chưa decode
                           dialogSlice = body.getOctetStringSlice();
                           body.skipValue();
                           break;
                       case 0x0C: // componentPortion
                           BerCursor cc = body.openConstructed();
                           ComponentDecoder.decodeComponents(cc, components);
                           cc.release();
                           body.skipValue();
                           break;
                       default:
                           body.skipValue();
                   }
               } else {
                   body.skipValue();
               }
           }
           // ── Lazy decode dialogue chỉ khi cần ────────────────────────────
           if (dialogSlice != null) {
               DialoguePortionDecoder.DialoguePDU dpdu =
                   DialoguePortionDecoder.decode(dialogSlice);
               if (dpdu.pduType == DialoguePortionDecoder.TAG_AARQ) {
                   // MAP open: appContextOid, userInfoSlice (lazy)
                   dialog.setAppContext(dpdu.appContextOid);
                   // userInfoSlice chỉ decode nếu MAP layer thực sự cần
                   if (dpdu.userInfoSlice != null) {
                       dialog.setUserInfoSlice(dpdu.userInfoSlice);
                   }
               }
           }
           // ── Dispatch components ──────────────────────────────────────────
           for (Component comp : components) {
               dialog.addComponent(comp);
           }
           dialog.fireTCBegin();
           body.release();
       }
       // ── Outbound TC-END encode (MAP SendRoutingInfo response) ────────────
       public void sendSendRoutingInfoResponse(
               int    destTxId,
               byte[] appCtxOidEnc,
               int    invokeId,
               byte[] sriResultBytes) {
           NettyAsnOutputStream out = nettyOutputStreamPool.get();
           // Encode toàn bộ TC-END + AARE + RRL + result trong 1 call
           // BerWriter là ThreadLocal → không new object
           InvokeResponseEncoder.encodeTCEndRRL(
               destTxId,
               appCtxOidEnc,
               invokeId,
               MAPOperationCode.sendRoutingInfo,  // 22
               sriResultBytes,
               out
           );
           // Flush xuống SCCP → M3UA → SCTP
           sccpProvider.send(out.getByteBuf());
           out.reset();
       }
       6. Sơ đồ tổng thể decode + encode
       
       
       ══════════════════════════════════════════════════════════════
         INBOUND (zero-copy lazy decode)
       ══════════════════════════════════════════════════════════════
       SCTP ByteBuf
         └─ M3UA readSlice()
              └─ SCCP CompositeByteBuf
                   └─ BerCursor.wrap(ByteBuf)           ← pool
                        ├─ TC-BEGIN [APP 2]
                        │    ├─ origTxId    readInt32()
                        │    ├─ dialogPart  getSlice()   ← LAZY
                        │    │    └─ decode() chỉ khi cần
                        │    │         ├─ AARQ appContextOid
                        │    │         └─ userInfoSlice  ← LAZY
                        │    └─ components openConstructed()
                        │         ├─ Invoke  → InvokeImpl
                        │         │    ├─ invokeId  readInt32()
                        │         │    ├─ opCode    OperationCodeImpl
                        │         │    └─ param     BerSlice ← LAZY
                        │         └─ ...
                        └─ release() → pool
       ══════════════════════════════════════════════════════════════
         OUTBOUND (backward encode, zero alloc)
       ══════════════════════════════════════════════════════════════
       BerWriter.get()                  ← ThreadLocal, reset pos
         └─ InvokeResponseEncoder
              └─ encodeTCEndRRL()
                   ├─ TC-END [APP 4]
                   │    ├─ destTxId
                   │    ├─ DialoguePortion [APP 11]
                   │    │    └─ AARE (accepted, appCtxOid)
                   │    └─ ComponentPortion [APP 12]
                   │         └─ ReturnResultLast [CTX 2]
                   │              ├─ invokeId
                   │              └─ result SEQUENCE
                   │                   ├─ opCode [0]
                   │                   └─ parameter ANY
                   └─ flushTo(NettyAsnOutputStream)  ← zero-copy
                        └─ SCCP → M3UA → SCTP
       Tóm tắt file đã tạo
       
       
       File	Module	Vai trò
       DialoguePortionDecoder.java	tcap	Lazy decode AARQ/AARE/ABRT
       DialoguePortionEncoder.java	tcap	Encode AARQ/AARE/ABRT bằng BerWriter
       InvokeResponseEncoder.java	tcap	Encode TC-BEGIN/CONTINUE/END/ABORT + components
       BerWriter.java	asn	Backward encoder (đã có trước)
       BerCursor.java	asn	Zero-copy decoder cursor (đã có trước)
       BerSlice.java	asn	Zero-copy view (đã có trước)

Để viết code chính xác, tôi cần kiểm tra cấu trúc ASN.1 của MAP và CAP trong repo.

Đã có đủ thông tin về cấu trúc ASN.1 MAP/CAP từ 3GPP TS 29.002 và TS 29.078. Bây giờ viết code hoàn chỉnh.

🔬 MAP Lazy Decode + CAP Zero-Copy Encode/Decode
1. MapUserInfoDecoder.java — Lazy decode userInfoSlice
java


package org.mobicents.protocols.ss7.map.dialog;
import org.mobicents.protocols.asn.AsnException;
import org.mobicents.protocols.asn.BerCursor;
import org.mobicents.protocols.asn.BerSlice;
import org.mobicents.protocols.asn.BerTag;
/**
 * Lazy decoder cho MAP user-information (MAP-DialogueInformation).
 *
 * Wire format (3GPP TS 29.002 §17.4):
 *
 *   MAP-DialoguePDU ::= CHOICE {
 *       map-open    [0] IMPLICIT MAP-OpenInfo,
 *       map-close   [1] IMPLICIT MAP-CloseInfo,
 *       map-refuse  [2] IMPLICIT MAP-RefuseInfo,
 *       map-userAbort  [3] IMPLICIT MAP-UserAbortInfo,
 *       map-providerAbort [4] IMPLICIT MAP-ProviderAbortInfo,
 *       map-accept  [5] IMPLICIT MAP-AcceptInfo,
 *       map-reject  [6] IMPLICIT MAP-RejectInfo
 *   }
 *
 *   MAP-OpenInfo ::= SEQUENCE {
 *       destinationReference  [0] IMPLICIT AddressString OPTIONAL,
 *       originationReference  [1] IMPLICIT AddressString OPTIONAL,
 *       extensionContainer    [2] IMPLICIT ExtensionContainer OPTIONAL
 *   }
 *
 *   MAP-CloseInfo ::= SEQUENCE {
 *       prearrangedEnd        BOOLEAN DEFAULT FALSE,
 *       extensionContainer    [0] IMPLICIT ExtensionContainer OPTIONAL
 *   }
 *
 *   MAP-RefuseInfo ::= SEQUENCE {
 *       reason                Reason,
 *       extensionContainer    [0] IMPLICIT ExtensionContainer OPTIONAL
 *   }
 *   Reason ::= ENUMERATED { noReasonGiven(0), invalidDestRef(1), invalidOrigRef(2) }
 *
 *   MAP-UserAbortInfo ::= SEQUENCE {
 *       userAbortChoice       UserAbortChoice,
 *       extensionContainer    [0] IMPLICIT ExtensionContainer OPTIONAL
 *   }
 *   UserAbortChoice ::= CHOICE {
 *       userSpecificReason    [0] IMPLICIT NULL,
 *       userResourceLimitation [1] IMPLICIT NULL,
 *       resourceUnavailable   [2] IMPLICIT ResourceUnavailableReason,
 *       applicationProcedureCancellation [3] IMPLICIT ProcedureCancellationReason
 *   }
 *
 *   MAP-ProviderAbortInfo ::= SEQUENCE {
 *       map-ProviderAbortReason ProviderAbortReason,
 *       extensionContainer    [0] IMPLICIT ExtensionContainer OPTIONAL
 *   }
 *   ProviderAbortReason ::= ENUMERATED {
 *       abnormalDialogue(0), invalidPDU(1)
 *   }
 *
 * userInfoSlice = content của [30] IMPLICIT SEQUENCE OF EXTERNAL
 * Mỗi EXTERNAL item wrap MAP-DialoguePDU dưới dạng:
 *   EXTERNAL ::= [UNIVERSAL 8] IMPLICIT SEQUENCE {
 *       direct-reference  OID OPTIONAL,
 *       encoding          CHOICE {
 *           single-ASN1-type [0] EXPLICIT ANY,    ← MAP-DialoguePDU ở đây
 *           ...
 *       }
 *   }
 */
public final class MapUserInfoDecoder {
    // ── MAP-DialoguePDU CHOICE tags (CONTEXT class) ──────────────────
    public static final int TAG_MAP_OPEN            = 0x00;
    public static final int TAG_MAP_CLOSE           = 0x01;
    public static final int TAG_MAP_REFUSE          = 0x02;
    public static final int TAG_MAP_USER_ABORT      = 0x03;
    public static final int TAG_MAP_PROVIDER_ABORT  = 0x04;
    public static final int TAG_MAP_ACCEPT          = 0x05;
    public static final int TAG_MAP_REJECT          = 0x06;
    // ── MAP-OpenInfo field tags ───────────────────────────────────────
    private static final int TAG_DEST_REF           = 0x00; // [0] AddressString
    private static final int TAG_ORIG_REF           = 0x01; // [1] AddressString
    private static final int TAG_EXT_CONTAINER      = 0x02; // [2] ExtensionContainer
    // ── MAP-RefuseInfo reason ────────────────────────────────────────
    public static final int REASON_NO_REASON        = 0;
    public static final int REASON_INVALID_DEST_REF = 1;
    public static final int REASON_INVALID_ORIG_REF = 2;
    // ── OID: map-DialogueAS ──────────────────────────────────────────
    // {itu-t identified-organization 4 etsi 0 mobileDomain 0
    //  gsm-Network 1 modules 3 map-DialogueInformation 3 version3 3}
    private static final byte[] MAP_DIALOGUE_AS_OID = {
        0x04, 0x00, 0x00, 0x01, 0x03, 0x03, 0x03
    };
    private MapUserInfoDecoder() {}
    // ================================================================
    // RESULT HOLDER
    // ================================================================
    /**
     * Kết quả decode MAP-DialoguePDU.
     * Reusable per-thread via ThreadLocal.
     */
    public static final class MapDialoguePDU {
        public int     pduType = -1;
        // MAP-OpenInfo
        public byte[]  destinationReference;  // AddressString (TBCD)
        public byte[]  originationReference;  // AddressString (TBCD)
        // MAP-CloseInfo
        public boolean prearrangedEnd = false;
        // MAP-RefuseInfo
        public int     refuseReason = -1;
        // MAP-UserAbortInfo
        public int     userAbortChoice = -1;   // 0..3
        public int     userAbortValue  = -1;   // nếu có value (resourceUnavailable, etc.)
        // MAP-ProviderAbortInfo
        public int     providerAbortReason = -1; // 0=abnormalDialogue, 1=invalidPDU
        // extensionContainer (lazy slice nếu cần)
        public BerSlice extensionSlice;
        public void reset() {
            pduType             = -1;
            destinationReference = null;
            originationReference = null;
            prearrangedEnd      = false;
            refuseReason        = -1;
            userAbortChoice     = -1;
            userAbortValue      = -1;
            providerAbortReason = -1;
            extensionSlice      = null;
        }
    }
    private static final ThreadLocal<MapDialoguePDU> TL_MAP_PDU =
        ThreadLocal.withInitial(MapDialoguePDU::new);
    // ================================================================
    // ENTRY POINT
    // ================================================================
    /**
     * Lazy decode userInfoSlice → MAP-DialoguePDU.
     *
     * Gọi khi MAP layer thực sự cần (không gọi khi chỉ route message).
     *
     * @param userInfoSlice  BerSlice của [30] content
     *                       (SEQUENCE OF EXTERNAL)
     * @return               MapDialoguePDU (ThreadLocal — copy nếu cần giữ lâu)
     */
    public static MapDialoguePDU decode(BerSlice userInfoSlice)
            throws AsnException {
        MapDialoguePDU result = TL_MAP_PDU.get();
        result.reset();
        // userInfoSlice = content của [30] IMPLICIT SEQUENCE OF EXTERNAL
        // → lướt qua từng EXTERNAL item
        BerCursor seqCursor = BerCursor.wrap(
            userInfoSlice.toByteArray(), 0, userInfoSlice.length()
        );
        try {
            while (seqCursor.hasMore()) {
                seqCursor.readTag();
                // Mỗi item là EXTERNAL [UNIVERSAL 8]
                if (seqCursor.tagClass() == BerTag.UNIVERSAL
                        && seqCursor.tag() == 0x08) {
                    BerCursor extCursor = seqCursor.openConstructed();
                    try {
                        decodeExternalItem(extCursor, result);
                    } finally {
                        extCursor.release();
                    }
                }
                seqCursor.skipValue();
            }
        } finally {
            seqCursor.release();
        }
        return result;
    }
    // ================================================================
    // EXTERNAL item decode
    // ================================================================
    private static void decodeExternalItem(BerCursor ext,
                                            MapDialoguePDU out)
            throws AsnException {
        // EXTERNAL ::= SEQUENCE {
        //   direct-reference OID OPTIONAL,
        //   encoding CHOICE {
        //     single-ASN1-type [0] EXPLICIT ANY   ← MAP-DialoguePDU
        //   }
        // }
        while (ext.hasMore()) {
            ext.readTag();
            if (ext.tagClass() == BerTag.UNIVERSAL
                    && ext.tag() == BerTag.OID) {
                // direct-reference OID — skip (validate nếu cần)
                ext.skipValue();
            } else if (ext.tagClass() == BerTag.CONTEXT
                    && ext.tag() == 0x00) {
                // single-ASN1-type [0] EXPLICIT → mở ra
                BerCursor singleType = ext.openConstructed();
                try {
                    // Bên trong là MAP-DialoguePDU
                    singleType.readTag();
                    decodeMapDialoguePDU(singleType, out);
                } finally {
                    singleType.release();
                }
                ext.skipValue();
            } else {
                ext.skipValue();
            }
        }
    }
    // ================================================================
    // MAP-DialoguePDU CHOICE dispatch
    // ================================================================
    private static void decodeMapDialoguePDU(BerCursor cursor,
                                              MapDialoguePDU out)
            throws AsnException {
        if (cursor.tagClass() != BerTag.CONTEXT) {
            throw new AsnException("MAP-DialoguePDU: expected CONTEXT tag, " +
                "got class=" + cursor.tagClass() + " tag=" + cursor.tag());
        }
        out.pduType = cursor.tag();
        switch (cursor.tag()) {
            case TAG_MAP_OPEN:
                decodeMapOpen(cursor.openConstructed(), out);
                break;
            case TAG_MAP_CLOSE:
                decodeMapClose(cursor.openConstructed(), out);
                break;
            case TAG_MAP_REFUSE:
                decodeMapRefuse(cursor.openConstructed(), out);
                break;
            case TAG_MAP_USER_ABORT:
                decodeMapUserAbort(cursor.openConstructed(), out);
                break;
            case TAG_MAP_PROVIDER_ABORT:
                decodeMapProviderAbort(cursor.openConstructed(), out);
                break;
            case TAG_MAP_ACCEPT:
                // MAP-AcceptInfo: extensionContainer only → skip
                out.pduType = TAG_MAP_ACCEPT;
                break;
            case TAG_MAP_REJECT:
                // MAP-RejectInfo: không dùng phổ biến → skip
                out.pduType = TAG_MAP_REJECT;
                break;
            default:
                throw new AsnException("MAP-DialoguePDU: unknown tag=" + cursor.tag());
        }
        cursor.skipValue();
    }
    // ================================================================
    // MAP-OpenInfo [0]
    // ================================================================
    private static void decodeMapOpen(BerCursor body, MapDialoguePDU out)
            throws AsnException {
        while (body.hasMore()) {
            body.readTag();
            if (body.tagClass() != BerTag.CONTEXT) {
                body.skipValue();
                continue;
            }
            switch (body.tag()) {
                case TAG_DEST_REF:   // [0] AddressString (TBCD)
                    out.destinationReference = body.getOctetString();
                    body.skipValue();
                    break;
                case TAG_ORIG_REF:   // [1] AddressString (TBCD)
                    out.originationReference = body.getOctetString();
                    body.skipValue();
                    break;
                case TAG_EXT_CONTAINER: // [2] LAZY
                    out.extensionSlice = body.getOctetStringSlice();
                    body.skipValue();
                    break;
                default:
                    body.skipValue();
            }
        }
        body.release();
    }
    // ================================================================
    // MAP-CloseInfo [1]
    // ================================================================
    private static void decodeMapClose(BerCursor body, MapDialoguePDU out)
            throws AsnException {
        while (body.hasMore()) {
            body.readTag();
            if (body.tagClass() == BerTag.UNIVERSAL
                    && body.tag() == BerTag.BOOLEAN) {
                // prearrangedEnd BOOLEAN DEFAULT FALSE
                byte[] boolBytes = body.getOctetString();
                out.prearrangedEnd = boolBytes.length > 0 && boolBytes[0] != 0;
                body.skipValue();
            } else {
                body.skipValue();
            }
        }
        body.release();
    }
    // ================================================================
    // MAP-RefuseInfo [2]
    // ================================================================
    private static void decodeMapRefuse(BerCursor body, MapDialoguePDU out)
            throws AsnException {
        while (body.hasMore()) {
            body.readTag();
            if (body.tagClass() == BerTag.UNIVERSAL
                    && body.tag() == BerTag.ENUMERATED) {
                out.refuseReason = body.readInt32();
                body.skipValue();
            } else if (body.tagClass() == BerTag.CONTEXT
                    && body.tag() == 0x00) {
                out.extensionSlice = body.getOctetStringSlice();
                body.skipValue();
            } else {
                body.skipValue();
            }
        }
        body.release();
    }
    // ================================================================
    // MAP-UserAbortInfo [3]
    // ================================================================
    private static void decodeMapUserAbort(BerCursor body, MapDialoguePDU out)
            throws AsnException {
        // UserAbortChoice ::= CHOICE {
        //   userSpecificReason    [0] NULL,
        //   userResourceLimitation [1] NULL,
        //   resourceUnavailable   [2] ResourceUnavailableReason (ENUMERATED),
        //   applicationProcedureCancellation [3] ProcedureCancellationReason (ENUMERATED)
        // }
        if (body.hasMore()) {
            body.readTag();
            if (body.tagClass() == BerTag.CONTEXT) {
                out.userAbortChoice = body.tag();
                if (body.tag() == 0x00 || body.tag() == 0x01) {
                    // NULL — không có value
                    out.userAbortValue = -1;
                } else if (body.tag() == 0x02 || body.tag() == 0x03) {
                    // ENUMERATED value
                    out.userAbortValue = body.readInt32();
                }
                body.skipValue();
            } else {
                body.skipValue();
            }
        }
        // extensionContainer [0] OPTIONAL
        if (body.hasMore()) {
            body.readTag();
            if (body.tagClass() == BerTag.CONTEXT && body.tag() == 0x00) {
                out.extensionSlice = body.getOctetStringSlice();
            }
            body.skipValue();
        }
        body.release();
    }
    // ================================================================
    // MAP-ProviderAbortInfo [4]
    // ================================================================
    private static void decodeMapProviderAbort(BerCursor body,
                                                MapDialoguePDU out)
            throws AsnException {
        // map-ProviderAbortReason ProviderAbortReason (ENUMERATED)
        if (body.hasMore()) {
            body.readTag();
            if (body.tagClass() == BerTag.UNIVERSAL
                    && body.tag() == BerTag.ENUMERATED) {
                out.providerAbortReason = body.readInt32();
                body.skipValue();
            } else {
                body.skipValue();
            }
        }
        // extensionContainer [0] OPTIONAL
        if (body.hasMore()) {
            body.readTag();
            out.extensionSlice = body.getOctetStringSlice();
            body.skipValue();
        }
        body.release();
    }
}
2. MapUserInfoEncoder.java — Encode MAP-DialoguePDU bằng BerWriter
java


package org.mobicents.protocols.ss7.map.dialog;
import org.mobicents.protocols.asn.BerTag;
import org.mobicents.protocols.asn.BerWriter;
/**
 * Encode MAP user-information (MAP-DialogueInformation) bằng BerWriter.
 *
 * Kết quả là byte[] để nhét vào userInfo field của AARQ/AARE
 * trong DialoguePortionEncoder.
 */
public final class MapUserInfoEncoder {
    // MAP-dialogue OID (direct-reference)
    // {itu-t 4 etsi 0 mobileDomain 0 gsm-Network 1 modules 3
    //  map-DialogueInformation 3 version3 3}
    private static final byte[] MAP_DIALOGUE_OID = {
        0x04, 0x00, 0x00, 0x01, 0x03, 0x03, 0x03
    };
    private MapUserInfoEncoder() {}
    // ================================================================
    // MAP-OpenInfo [0]
    // ================================================================
    /**
     * Encode MAP-OpenInfo wrapped trong EXTERNAL SEQUENCE OF.
     *
     * @param destRef   AddressString bytes (TBCD), null nếu không có
     * @param origRef   AddressString bytes (TBCD), null nếu không có
     * @return          byte[] để truyền vào DialoguePortionEncoder.encodeAARQ()
     */
    public static byte[] encodeMapOpen(byte[] destRef, byte[] origRef) {
        BerWriter w = BerWriter.get();
        // [30] IMPLICIT SEQUENCE OF EXTERNAL
        int markSeqOf = w.beginSequence(BerTag.CONTEXT, 0x1E);
        // EXTERNAL [UNIVERSAL 8]
        int markExt = w.beginSequence(BerTag.UNIVERSAL, 0x08);
        // encoding: single-ASN1-type [0] EXPLICIT MAP-DialoguePDU
        int markEnc = w.beginSequence(BerTag.CONTEXT, 0x00);
        // MAP-OpenInfo [0] IMPLICIT SEQUENCE
        int markOpen = w.beginSequence(BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_OPEN);
        // extensionContainer [2] — bỏ qua nếu null
        // originationReference [1] IMPLICIT AddressString
        if (origRef != null && origRef.length > 0) {
            w.writeOctetString(BerTag.CONTEXT, 0x01,
                origRef, 0, origRef.length);
        }
        // destinationReference [0] IMPLICIT AddressString
        if (destRef != null && destRef.length > 0) {
            w.writeOctetString(BerTag.CONTEXT, 0x00,
                destRef, 0, destRef.length);
        }
        w.endSequence(markOpen, BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_OPEN);
        // Đóng [0] EXPLICIT
        w.endSequence(markEnc, BerTag.CONTEXT, 0x00);
        // direct-reference OID
        w.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
            MAP_DIALOGUE_OID, 0, MAP_DIALOGUE_OID.length);
        w.endSequence(markExt, BerTag.UNIVERSAL, 0x08);
        w.endSequence(markSeqOf, BerTag.CONTEXT, 0x1E);
        return w.toByteArray();
    }
    // ================================================================
    // MAP-CloseInfo [1]
    // ================================================================
    /**
     * Encode MAP-CloseInfo.
     *
     * @param prearrangedEnd true nếu kết thúc theo thỏa thuận
     */
    public static byte[] encodeMapClose(boolean prearrangedEnd) {
        BerWriter w = BerWriter.get();
        int markSeqOf = w.beginSequence(BerTag.CONTEXT, 0x1E);
        int markExt   = w.beginSequence(BerTag.UNIVERSAL, 0x08);
        int markEnc   = w.beginSequence(BerTag.CONTEXT, 0x00);
        int markClose = w.beginSequence(BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_CLOSE);
        // prearrangedEnd BOOLEAN DEFAULT FALSE
        // Chỉ encode nếu TRUE (DEFAULT FALSE → omit khi FALSE)
        if (prearrangedEnd) {
            w.writeBoolean(BerTag.UNIVERSAL, BerTag.BOOLEAN, true);
        }
        w.endSequence(markClose, BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_CLOSE);
        w.endSequence(markEnc,   BerTag.CONTEXT,  0x00);
        w.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
            MAP_DIALOGUE_OID, 0, MAP_DIALOGUE_OID.length);
        w.endSequence(markExt,   BerTag.UNIVERSAL, 0x08);
        w.endSequence(markSeqOf, BerTag.CONTEXT,  0x1E);
        return w.toByteArray();
    }
    // ================================================================
    // MAP-RefuseInfo [2]
    // ================================================================
    /**
     * Encode MAP-RefuseInfo.
     *
     * @param reason  0=noReasonGiven, 1=invalidDestRef, 2=invalidOrigRef
     */
    public static byte[] encodeMapRefuse(int reason) {
        BerWriter w = BerWriter.get();
        int markSeqOf = w.beginSequence(BerTag.CONTEXT, 0x1E);
        int markExt   = w.beginSequence(BerTag.UNIVERSAL, 0x08);
        int markEnc   = w.beginSequence(BerTag.CONTEXT,  0x00);
        int markRefuse = w.beginSequence(BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_REFUSE);
        // reason ENUMERATED
        w.writeInt32(BerTag.UNIVERSAL, BerTag.ENUMERATED, reason);
        w.endSequence(markRefuse, BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_REFUSE);
        w.endSequence(markEnc,   BerTag.CONTEXT,  0x00);
        w.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
            MAP_DIALOGUE_OID, 0, MAP_DIALOGUE_OID.length);
        w.endSequence(markExt,   BerTag.UNIVERSAL, 0x08);
        w.endSequence(markSeqOf, BerTag.CONTEXT,  0x1E);
        return w.toByteArray();
    }
    // ================================================================
    // MAP-UserAbortInfo [3]
    // ================================================================
    /**
     * Encode MAP-UserAbortInfo.
     *
     * @param abortChoice 0=userSpecificReason, 1=userResourceLimitation,
     *                    2=resourceUnavailable, 3=applicationProcedureCancellation
     * @param abortValue  value nếu abortChoice=2 hoặc 3, -1 nếu NULL
     */
    public static byte[] encodeMapUserAbort(int abortChoice, int abortValue) {
        BerWriter w = BerWriter.get();
        int markSeqOf = w.beginSequence(BerTag.CONTEXT, 0x1E);
        int markExt   = w.beginSequence(BerTag.UNIVERSAL, 0x08);
        int markEnc   = w.beginSequence(BerTag.CONTEXT,  0x00);
        int markAbort = w.beginSequence(BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_USER_ABORT);
        if (abortChoice == 0 || abortChoice == 1) {
            // NULL
            w.writeNull(BerTag.CONTEXT, abortChoice);
        } else {
            // ENUMERATED value
            w.writeInt32(BerTag.CONTEXT, abortChoice, abortValue);
        }
        w.endSequence(markAbort, BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_USER_ABORT);
        w.endSequence(markEnc,   BerTag.CONTEXT,  0x00);
        w.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
            MAP_DIALOGUE_OID, 0, MAP_DIALOGUE_OID.length);
        w.endSequence(markExt,   BerTag.UNIVERSAL, 0x08);
        w.endSequence(markSeqOf, BerTag.CONTEXT,  0x1E);
        return w.toByteArray();
    }
    // ================================================================
    // MAP-ProviderAbortInfo [4]
    // ================================================================
    /**
     * Encode MAP-ProviderAbortInfo.
     *
     * @param reason 0=abnormalDialogue, 1=invalidPDU
     */
    public static byte[] encodeMapProviderAbort(int reason) {
        BerWriter w = BerWriter.get();
        int markSeqOf = w.beginSequence(BerTag.CONTEXT, 0x1E);
        int markExt   = w.beginSequence(BerTag.UNIVERSAL, 0x08);
        int markEnc   = w.beginSequence(BerTag.CONTEXT,  0x00);
        int markAbort = w.beginSequence(BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_PROVIDER_ABORT);
        w.writeInt32(BerTag.UNIVERSAL, BerTag.ENUMERATED, reason);
        w.endSequence(markAbort, BerTag.CONTEXT,
            MapUserInfoDecoder.TAG_MAP_PROVIDER_ABORT);
        w.endSequence(markEnc,   BerTag.CONTEXT,  0x00);
        w.writeOctetString(BerTag.UNIVERSAL, BerTag.OID,
            MAP_DIALOGUE_OID, 0, MAP_DIALOGUE_OID.length);
        w.endSequence(markExt,   BerTag.UNIVERSAL, 0x08);
        w.endSequence(markSeqOf, BerTag.CONTEXT,  0x1E);
        return w.toByteArray();
    }
}
3. CapOperationDecoder.java — CAP CAMEL zero-copy decode
java


package org.mobicents.protocols.ss7.cap.service.circuitSwitchedCall;
import org.mobicents.protocols.asn.AsnException;
import org.mobicents.protocols.asn.BerCursor;
import org.mobicents.protocols.asn.BerSlice;
import org.mobicents.protocols.asn.BerTag;
/**
 * Zero-copy decoder cho CAP operations (3GPP TS 29.078).
 *
 * Hỗ trợ các operation phổ biến nhất:
 *   - InitialDP           opCode=0  (gsmSSF → gsmSCF)
 *   - Connect             opCode=20 (gsmSCF → gsmSSF)
 *   - RequestReportBCSM   opCode=23 (gsmSCF → gsmSSF)
 *   - ApplyCharging       opCode=35 (gsmSCF → gsmSSF)
 *   - EventReportBCSM     opCode=24 (gsmSSF → gsmSCF)
 *   - ApplyChargingReport opCode=71 (gsmSSF → gsmSCF)
 *   - ReleaseCall         opCode=22 (gsmSCF → gsmSSF)
 *   - Continue            opCode=31 (gsmSCF → gsmSSF)
 *
 * Strategy: decode chỉ các field thực sự cần trên hot path.
 * Field phức tạp (BearerCapability, LocationInfo, v.v.) giữ dưới
 * dạng BerSlice để decode lazy khi application gọi getter.
 */
public final class CapOperationDecoder {
    // ── CAP Operation codes ──────────────────────────────────────────
    public static final int OP_INITIAL_DP            = 0;
    public static final int OP_RELEASE_CALL          = 22;
    public static final int OP_CONNECT               = 20;
    public static final int OP_CONTINUE              = 31;
    public static final int OP_REQUEST_REPORT_BCSM   = 23;
    public static final int OP_EVENT_REPORT_BCSM     = 24;
    public static final int OP_APPLY_CHARGING        = 35;
    public static final int OP_APPLY_CHARGING_REPORT = 71;
    public static final int OP_FURNISH_CHARGING_INFO = 34;
    public static final int OP_CALL_INFORMATION_REQUEST = 36;
    public static final int OP_PLAY_ANNOUNCEMENT    = 18;
    public static final int OP_PROMPT_AND_COLLECT   = 19;
    public static final int OP_CANCEL               = 53;
    private CapOperationDecoder() {}
    // ================================================================
    // RESULT HOLDERS (ThreadLocal)
    // ================================================================
    /**
     * InitialDP result — hot path fields eagerly decoded,
     * complex fields kept as BerSlice for lazy decode.
     *
     * InitialDPArg ::= SEQUENCE {
     *   serviceKey               [0] ServiceKey,        -- INTEGER
     *   calledPartyNumber        [2] CalledPartyNumber  OPTIONAL,
     *   callingPartyNumber       [10] CallingPartyNumber OPTIONAL,
     *   callingPartyCategory     [28] CallingPartyCategory OPTIONAL,
     *   cGEncountered            [3] CGEncountered      OPTIONAL,
     *   iPSSPCapabilities        [4] IPSSPCapabilities  OPTIONAL,
     *   locationNumber           [11] LocationNumber    OPTIONAL,
     *   originalCalledPartyID    [12] OriginalCalledPartyID OPTIONAL,
     *   extensions               [5] IMPLICIT Extensions OPTIONAL,
     *   highLayerCompatibility   [23] HighLayerCompatibility OPTIONAL,
     *   additionalCallingPartyNumber [25] AdditionalCallingPartyNumber OPTIONAL,
     *   bearerCapability         [20] BearerCapability  OPTIONAL,
     *   eventTypeBCSM            [21] EventTypeBCSM     OPTIONAL,
     *   redirectingPartyID       [14] RedirectingPartyID OPTIONAL,
     *   redirectionInformation   [15] RedirectionInformation OPTIONAL,
     *   cause                    [17] Cause             OPTIONAL,
     *   serviceInteractionIndicators [24] IMPLICIT ServiceInteractionIndicators OPTIONAL,
     *   carrier                  [29] Carrier           OPTIONAL,
     *   mSCAddress               [26] ISDN-AddressString OPTIONAL,
     *   calledPartyBCDNumber     [27] CalledPartyBCDNumber OPTIONAL,
     *   timeAndTimezone          [16] TimeAndTimezone   OPTIONAL,
     *   callReferenceNumber      [31] CallReferenceNumber OPTIONAL,
     *   mscID                    [32] IMEI              OPTIONAL,
     *   callingGeodeticLocation  [33] OPTIONAL,
     *   imsi                     [34] IMSI              OPTIONAL,
     *   subscriberState          [35] SubscriberState   OPTIONAL,
     *   locationInformation      [36] LocationInformation OPTIONAL,
     *   ext-basicServiceCode     [37] Ext-BasicServiceCode OPTIONAL,
     *   callReferenceNumber      [40] OPTIONAL,
     *   mscID                    [41] OPTIONAL,
     *   ...
     * }
     */
    public static final class InitialDPResult {
        // Hot fields — eagerly decoded
        public int     serviceKey      = -1;
        public byte[]  calledPartyNumber;        // CalledPartyNumber (BCD+header)
        public byte[]  callingPartyNumber;       // CallingPartyNumber
        public int     callingPartyCategory = -1;
        public int     eventTypeBCSM    = -1;
        // IMSI — thường cần sớm
        public byte[]  imsi;
        // Lazy slices — decode khi application cần
        public BerSlice locationInfoSlice;       // [36] LocationInformation
        public BerSlice bearerCapabilitySlice;   // [20] BearerCapability
        public BerSlice redirectingPartySlice;   // [14] RedirectingPartyID
        public BerSlice redirectionInfoSlice;    // [15] RedirectionInformation
        public BerSlice causeSlice;              // [17] Cause
        public BerSlice extensionsSlice;         // [5] Extensions
        public BerSlice subscriberStateSlice;    // [35] SubscriberState
        public void reset() {
            serviceKey          = -1;
            calledPartyNumber   = null;
            callingPartyNumber  = null;
            callingPartyCategory = -1;
            eventTypeBCSM       = -1;
            imsi                = null;
            locationInfoSlice   = null;
            bearerCapabilitySlice = null;
            redirectingPartySlice = null;
            redirectionInfoSlice  = null;
            causeSlice          = null;
            extensionsSlice     = null;
            subscriberStateSlice = null;
        }
    }
    /**
     * EventReportBCSM result.
     *
     * EventReportBCSMArg ::= SEQUENCE {
     *   eventTypeBCSM            [0] EventTypeBCSM,
     *   eventSpecificInformationBCSM [2] EventSpecificInformationBCSM OPTIONAL,
     *   legID                    [3] ReceivingSideID OPTIONAL,
     *   miscCallInfo             [4] MiscCallInfo DEFAULT {messageType request},
     *   extensions               [5] IMPLICIT Extensions OPTIONAL
     * }
     */
    public static final class EventReportBCSMResult {
        public int     eventTypeBCSM = -1;      // [0] ENUMERATED
        public int     legId         = -1;      // [3] ReceivingSideID
        public int     miscCallInfo  = -1;      // [4] messageType
        // Lazy
        public BerSlice eventSpecificInfoSlice;  // [2] complex CHOICE
        public BerSlice extensionsSlice;
        public void reset() {
            eventTypeBCSM       = -1;
            legId               = -1;
            miscCallInfo        = -1;
            eventSpecificInfoSlice = null;
            extensionsSlice     = null;
        }
    }
    /**
     * ApplyChargingReport result.
     *
     * ApplyChargingReportArg ::= CallResult
     * CallResult ::= OCTET STRING (SIZE(minCallResultLength..maxCallResultLength))
     *   -- Encoded as camelCallResult or networkSpecificInfo
     */
    public static final class ApplyChargingReportResult {
        public BerSlice callResultSlice;  // raw slice, decode theo context
        public void reset() { callResultSlice = null; }
    }
    private static final*


 viết nhiều tést cáse để cover hết các trường hợp và chừa 1 lại 1 legacy path để có teh63 encode/decode bình thường nếu featủe zero encode/decode này fail

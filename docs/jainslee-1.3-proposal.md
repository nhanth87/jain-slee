 <!--
  micro-jainslee 1.1.0
  Proposal: Session Routing, SBB Lifecycle, ConvergenceKey,
            Hot-Deploy & Modern Event Model
  Target:   Junior Engineers
  Author:   micro-jainslee Architecture Team
  Date:     2026-06-29
  Status:   PROPOSAL — For Review & Implementation
  Repo:     https://github.com/nhanth87/jain-slee/tree/micro-jainslee
-->
# micro-jainslee — Junior Engineer Implementation Proposal
> **Mục đích:** Giải thích **tại sao** và **làm thế nào** implement các tính
> năng mới trong micro-jainslee 1.1.x.  
> **Đối tượng:** Junior engineer mới join project.  
> **Prerequisite:** Đọc `README.md`, `docs/microjainslee-design.md`,
> `docs/run-testcase-100k-sbb.md` trước khi đọc tài liệu này.
---
## Mục lục
1. [Tổng quan kiến trúc hiện tại](#1-tổng-quan-kiến-trúc-hiện-tại)
2. [Vấn đề cốt lõi: Session Routing khi SBB chết](#2-vấn-đề-cốt-lõi-session-routing-khi-sbb-chết)
3. [Proposal A — Modern Event Model: Sealed Hierarchy](#3-proposal-a--modern-event-model-sealed-hierarchy)
4. [Proposal B — ConvergenceKeyFactory: Tự động sinh key](#4-proposal-b--convergencekeyfactory-tự-động-sinh-key)
5. [Proposal C — @ConvergenceKey APT Processor](#5-proposal-c--convergencekey-apt-processor)
6. [Proposal D — SessionRoutingEngine: Routing trung tâm](#6-proposal-d--sessionroutingengine-routing-trung-tâm)
7. [Proposal E — SessionRecoveryService: Rehydration](#7-proposal-e--sessionrecoveryservice-rehydration)
8. [Proposal F — EntityRemovalBus: Observability](#8-proposal-f--entityremovalbus-observability)
9. [Proposal G — Hot-Deploy RA/SBB jar](#9-proposal-g--hot-deploy-rasbb-jar)
10. [Proposal H — Protostream Schema cho Cluster](#10-proposal-h--protostream-schema-cho-cluster)
11. [Implementation Checklist](#11-implementation-checklist)
12. [FAQ cho Junior Engineer](#12-faq-cho-junior-engineer)
---
## 1. Tổng quan kiến trúc hiện tại
### 1.1 Stack công nghệ
Java 25 Virtual Threads → 1 SBB entity = 1 parked virtual thread LMAX Disruptor ring buffer → event queue O(1), ~25 ns/op, low GC APT Annotation Processing → codegen tại build time, zero reflection Quarkus + GraalVM native → fast startup, small footprint Infinispan (cluster mode) → distributed state, REPL_ASYNC jSS7 HashedWheelTimer → timer integration (10 ms tick)



### 1.2 Số liệu đã đạt được (Perfect Core S1–S5)
177 unit tests — tất cả pass trên JDK 25 100K SBBs — stress test 0 errors, ~14 OS threads 387 graph nodes — knowledge graph của toàn bộ kiến trúc



### 1.3 Flow tổng quát (hiện tại)
Network Message │ ▼ Resource Adaptor (RA) ← protocol handler (HTTP, SS7, SIP, gRPC) │ fireEvent(event, aci) ▼ SleeEndpointImpl ← validate state machine │ ▼ EventRouter (LMAX Disruptor) ← serialize all dispatch (1 consumer thread) │ ▼ InitialEventSelectorDispatcher ← IES: convergence key lookup │ ├── HIT → EntitySlot.submit(task) → SBB.onEvent() ✅ └── MISS → ??? drop silently ← VẤN ĐỀ CHÍNH



### 1.4 Các class quan trọng cần biết
| Class | Package | Vai trò |
|-------|---------|---------|
| `MicroSleeContainer` | `core` | God object — quản lý toàn bộ container |
| `EventRouter` | `core` | LMAX Disruptor dispatch engine |
| `VirtualThreadSbbEntityPool` | `core` | Pool quản lý EntitySlot + virtual threads |
| `EntitySlot` | `core` | Virtual thread shell cho 1 SBB entity |
| `InitialEventSelectorDispatcher` | `core/ies` | IES convergence key lookup |
| `SimpleSbbLocalObject` | `core` | Handle của SBB entity |
| `InMemoryActivityContext` | `core` | ACI — session handle |
| `SleeEndpointImpl` | `ra-spi` | RA gọi fireEvent() qua đây |
| `SleeTimerSchedulerBridge` | `core` | jSS7 HashedWheelTimer → EventRouter |
---
## 2. Vấn đề cốt lõi: Session Routing khi SBB chết
### 2.1 Tại sao đây là vấn đề?
Một USSD session hoặc SMS dialog **không phải là 1 message**.
Nó là **nhiều message liên tiếp** trong cùng một dialog:
User gõ 123# → BEGIN message 1 → SBB nhận ProcessUnstructuredSsRequest User chọn "1" → CONTINUE message 2 → SBB nhận UnstructuredSsResponse User chọn "2" → CONTINUE message 3 → SBB nhận UnstructuredSsResponse User chọn "0" → END message 4 → SBB nhận dialog close event



Mỗi message đến → SLEE cần biết **route vào SBB nào**.
**JAIN-SLEE 1.1 §7.5 trả lời bằng convergence key:**
IES dispatcher tra cứu `convergenceIndex` map: `convergenceKey → entityId`.
### 2.2 Vấn đề xảy ra khi SBB chết giữa dialog
Timeline:

T=0s Message 1 (BEGIN) → IES allocate entity "sbb-001" ✅ T=5s Message 2 (CONTINUE) → IES lookup "sbb-001" ✅ deliver T=35s [SBB chết: timer timeout / OutOfMemory / hot-redeploy] T=36s Message 3 (CONTINUE) → IES lookup → ??? ← VẤN ĐỀ

Hiện tại (Perfect Core S1-S5):

Case A: convergenceIndex CÒN entry (GAP-SR-7 leak) → findEntity("sbb-001") → null (slot đã release) → isRemoved() = true → for-loop skip → SILENT DROP 🔴 (không log, không metric, user thấy "session chết")

Case B: convergenceIndex ĐÃ xóa đúng (nếu IES cleanup đúng) → IES miss → allocate "sbb-002" (NEW entity!) → "sbb-002" không có CMP state của session cũ (step=2, msisdn=...) → Business logic sai hoàn toàn 🔴

Kết quả người dùng thấy: "Session bị reset về đầu" hoặc "Session không phản hồi"



### 2.3 Solution overview (S6–S9)
Khi SBB chết (bất kỳ lý do): S6: EntityRemovalBus.publish(EntityRemovalEvent) ├── IesCleanupAdapter → xóa convergenceIndex entry (đóng GAP-SR-7) ├── SessionLifecycleLogger → log structured └── UssdSessionStore → auto-fail session trong RA

S7: Capture RecoverySnapshot TRƯỚC KHI release slot └── recoveryIndex["ss7.ussd:84901234567:12345#a3f2"] = { entityId: "sbb-001", cmpFields: {step:2, msisdn:"84901234567"}, aciNames: ["session:84901234567:12345"], sbbClass: "UssdMenuSbb" }

Khi message tiếp theo đến (T=36s): S6: IES miss → SessionRoutingEngine.handleConvergenceMiss() S7: → recoveryService.hasSnapshot(conv)? YES → recoveryService.rehydrate(conv) → reconstructFromSnapshot(): 1. Class.forName("UssdMenuSbb").newInstance() 2. restore CMP: step=2, msisdn="84901234567" 3. sbbLoad() 4. re-attach ACI "session:84901234567:12345" 5. new EntitySlot → bind virtual thread → re-dispatch event → SBB nhận với ĐÚNG state ✅



### 2.4 Wrong-SBB Routing Prevention
Rủi ro: IES convergenceIndex bị desync với recoveryIndex → IES nói entity "sbb-002" nhưng snapshot có entity "sbb-001"

Prevention trong SessionRoutingEngine.handleDeadEntity():

RecoverySnapshot snap = recoveryService.peekSnapshot(conv); if (!snap.entityId().equals(iesEntityId)) { wrongSbbPrevented.incrementAndGet(); log.error("WRONG-SBB PREVENTED: IES={} snap={}", iesEntityId, snap.entityId()); return DROPPED_WRONG_SBB_PREVENTED; // từ chối, không route sai }



---
## 3. Proposal A — Modern Event Model: Sealed Hierarchy
### 3.1 Tại sao không dùng POJO thuần?
```java
// ❌ POJO cũ — vấn đề thực tế:
public class UssdEvent {
    private String type;    // "BEGIN"? "CONTINUE"? String literal → typo
    private Object payload; // Object → runtime ClassCastException
}
// ❌ Switch trên POJO — compiler không catch được:
if (event.getType().equals("CONTNUE")) { // typo! không compile error
    handleContinue(event);
}
// Nếu thêm event type mới → phải grep toàn bộ codebase để tìm chỗ cần xử lý
// ✅ Sealed hierarchy — compiler enforce exhaustiveness:
switch (event) {
    case SleeEvent.MapUssd.ProcessUnstructuredSsRequest r -> handleBegin(r);
    case SleeEvent.MapUssd.UnstructuredSsResponse r       -> handleContinue(r);
    case SleeEvent.MapUssd.UnstructuredSsNotify r         -> handleNotify(r);
    case SleeEvent.MapUssd.UnstructuredSsRequest r        -> handleNetInit(r);
    case SleeEvent.MapUssd.ProcessUnstructuredSsResponse r -> handleResp(r);
    case SleeEvent.MapUssd.UnstructuredSsNotifyResponse r  -> handleNotifResp(r);
    // ← Compiler ERROR nếu thiếu case
}
Lợi ích thực tế:

Compiler-enforced exhaustiveness → không bao giờ miss event type
Zero runtime overhead → JIT compile switch thành tableswitch O(1)
GraalVM native-image safe → APT generate MethodHandle, không dùng reflection
jSS7 compatible → giữ nguyên jSS7 object trong transient rawRequest field
Human-readable → record constructor tường minh, không magic string
3.2 SS7 MAP Event: Tại sao không dùng Begin/Continue/End?


SS7 MAP hoạt động theo 2 tầng riêng biệt:
  TCAP Dialog (transport layer):
    TC-BEGIN, TC-CONTINUE, TC-END, TC-ABORT
    → Là TCAP primitives, không phải MAP operations
    → Chúng ta handle trong SleeEvent.TcapDialog
  MAP Operations (application layer):
    MAP-PROCESS-UNSTRUCTURED-SS-REQUEST   ← ME gửi *123#
    MAP-PROCESS-UNSTRUCTURED-SS-RESPONSE  ← Platform trả lời menu
    MAP-UNSTRUCTURED-SS-REQUEST           ← Network-initiated
    MAP-UNSTRUCTURED-SS-RESPONSE          ← ME trả lời network-initiated
    MAP-UNSTRUCTURED-SS-NOTIFY            ← Display only, no reply
    → Đây mới là cái chúng ta cần handle trong SleeEvent.MapUssd
Tương tự SMS:
    MAP-SEND-ROUTING-INFO-FOR-SM-REQUEST  ← SRI-for-SM (SMSC hỏi HLR)
    MAP-SEND-ROUTING-INFO-FOR-SM-RESPONSE ← HLR trả lời SMSC
    MAP-MO-FORWARD-SHORT-MESSAGE          ← MO SMS
    MAP-MT-FORWARD-SHORT-MESSAGE          ← MT SMS
    MAP-REPORT-SM-DELIVERY-STATUS         ← Delivery report
3.3 File cần tạo: SleeEvent.java
Path: jainslee-api/src/main/java/com/microjainslee/api/event/SleeEvent.java

```java


/**
 * Root sealed interface cho toàn bộ event hierarchy trong micro-jainslee.
 *
 * HƯỚNG DẪN CHO JUNIOR ENGINEER:
 * ─────────────────────────────────────────────────────────────────
 * 1. KHÔNG cần sửa file này khi viết RA thông thường.
 *    Chỉ sửa khi thêm protocol hoàn toàn mới (CAMEL, Diameter...).
 *
 * 2. Để viết RA mới cho protocol đã có → chỉ cần tạo record
 *    implements interface con tương ứng.
 *
 * 3. Để viết RA custom (SMPP, proprietary) → implement Extension
 *    (non-sealed, không cần sửa file này).
 *
 * 4. KHÔNG tự viết convergenceName() → dùng @ConvergenceKey, APT tự generate.
 *
 * 5. rawRequest fields đánh dấu transient:
 *    - Giữ jSS7 object gốc on-node (zero-copy)
 *    - Không serialize qua Protostream (cluster mode)
 *    - SBB truy cập rawRequest nếu cần jSS7-specific API
 * ─────────────────────────────────────────────────────────────────
 */
public sealed interface SleeEvent
    permits SleeEvent.MapUssd,
            SleeEvent.MapSms,
            SleeEvent.TcapDialog,
            SleeEvent.Http,
            SleeEvent.Timer,
            SleeEvent.Lifecycle,
            SleeEvent.Extension {
    /**
     * Routing key — tự động sinh bởi @ConvergenceKey APT.
     * Ví dụ: "ss7.ussd:84901234567:12345#a3f2"
     * KHÔNG tự implement method này — APT generate.
     */
    String convergenceName();
    /**
     * Sequence number cho ordering/dedup (S8).
     * -1L nếu event không cần ordering (timer, lifecycle).
     */
    long sequenceNumber();
    // ══════════════════════════════════════════════════════════════════
    // MAP USSD SERVICE
    // Spec: 3GPP TS 29.002 §7.3 (MAP-SS operations)
    // jSS7: org.mobicents.protocols.ss7.map.api.service.supplementary.*
    // ══════════════════════════════════════════════════════════════════
    sealed interface MapUssd extends SleeEvent
        permits MapUssd.ProcessUnstructuredSsRequest,
                MapUssd.ProcessUnstructuredSsResponse,
                MapUssd.UnstructuredSsRequest,
                MapUssd.UnstructuredSsResponse,
                MapUssd.UnstructuredSsNotify,
                MapUssd.UnstructuredSsNotifyResponse {
        /**
         * MAP-PROCESS-UNSTRUCTURED-SS-REQUEST
         *
         * Khi nào: Mobile Equipment (ME) gửi USSD string (*123#, *100#...)
         * Flow:    ME → MSC → MAP → USSD Platform (chúng ta nhận event này)
         * jSS7:    ProcessUnstructuredSSRequestIndication
         *
         * convergenceName = "ss7.ussd:{msisdn}:{mapDialogId}#{hash4}"
         * APT generate từ fields = {"msisdn", "mapDialogId"}
         */
        @ConvergenceKey(
            protocol = "ss7.ussd",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record ProcessUnstructuredSsRequest(
            long    sequenceNumber,
            String  msisdn,            // ví dụ: "84901234567"
            String  ussdString,        // ví dụ: "*123#" hoặc "1" (user input)
            byte    dataCodingScheme,  // GSM 7-bit: 0x0F, UCS2: 0x48
            long    mapDialogId,       // TCAP dialog ID (từ jSS7)
            // rawRequest: chỉ dùng on-node, KHÔNG serialize
            transient Object rawRequest
        ) implements MapUssd {
            // convergenceName() được APT generate — KHÔNG tự viết ở đây
        }
        /**
         * MAP-PROCESS-UNSTRUCTURED-SS-RESPONSE
         *
         * Khi nào: Platform gửi menu về cho ME
         * Lưu ý:  Cùng convergence key với Request (cùng SBB xử lý)
         */
        @ConvergenceKey(
            protocol = "ss7.ussd",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record ProcessUnstructuredSsResponse(
            long   sequenceNumber,
            String msisdn,
            String ussdString,
            byte   dataCodingScheme,
            long   mapDialogId
        ) implements MapUssd {}
        /**
         * MAP-UNSTRUCTURED-SS-REQUEST
         *
         * Khi nào: Network-initiated USSD (platform chủ động gửi đến ME)
         * Flow:    USSD Platform → MSC → ME (ngược chiều với ProcessUnstructured)
         */
        @ConvergenceKey(
            protocol = "ss7.ussd.ni",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record UnstructuredSsRequest(
            long   sequenceNumber,
            String msisdn,
            String ussdString,
            byte   dataCodingScheme,
            long   mapDialogId,
            transient Object rawRequest
        ) implements MapUssd {}
        /** MAP-UNSTRUCTURED-SS-RESPONSE — ME trả lời network-initiated */
        @ConvergenceKey(
            protocol = "ss7.ussd.ni",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record UnstructuredSsResponse(
            long   sequenceNumber,
            String msisdn,
            String ussdString,
            long   mapDialogId
        ) implements MapUssd {}
        /**
         * MAP-UNSTRUCTURED-SS-NOTIFY
         *
         * Khi nào: Platform gửi thông báo cho ME (display only, không cần reply)
         * Ví dụ:  "Your balance is 50,000 VND"
         */
        @ConvergenceKey(
            protocol = "ss7.ussd.notify",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record UnstructuredSsNotify(
            long   sequenceNumber,
            String msisdn,
            String notifyString,
            long   mapDialogId,
            transient Object rawRequest
        ) implements MapUssd {}
        /** MAP-UNSTRUCTURED-SS-NOTIFY-RESPONSE — ME acknowledge notify */
        @ConvergenceKey(
            protocol = "ss7.ussd.notify",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record UnstructuredSsNotifyResponse(
            long   sequenceNumber,
            String msisdn,
            long   mapDialogId
        ) implements MapUssd {}
    }
    // ══════════════════════════════════════════════════════════════════
    // MAP SMS SERVICE
    // Spec: 3GPP TS 29.002 §9 (MAP-SM operations)
    // jSS7: org.mobicents.protocols.ss7.map.api.service.sms.*
    // ══════════════════════════════════════════════════════════════════
    sealed interface MapSms extends SleeEvent
        permits MapSms.SriForSmRequest,
                MapSms.SriForSmResponse,
                MapSms.MoForwardSmRequest,
                MapSms.MoForwardSmResponse,
                MapSms.MtForwardSmRequest,
                MapSms.MtForwardSmResponse,
                MapSms.ReportSmDeliveryStatus {
        /**
         * MAP-SEND-ROUTING-INFO-FOR-SM-REQUEST (SRI-for-SM)
         *
         * Khi nào: SMSC hỏi HLR "subscriber msisdn này đang ở MSC nào?"
         * Flow:    SMSC → HLR (chúng ta là HLR, nhận request này)
         *
         * Tại sao correlation = msisdn + smscAddress (không phải dialogId)?
         * SMSC có thể retry SRI với dialogId MỚI nhưng cùng msisdn+smsc combo.
         * Ta muốn cả request + response đi vào CÙNG SBB entity.
         */
        @ConvergenceKey(
            protocol = "ss7.sms.sri",
            fields   = {"msisdn", "smscAddress"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record SriForSmRequest(
            long    sequenceNumber,
            String  msisdn,
            String  smscAddress,
            boolean sm_RP_PRI,              // SM-RP-PRI flag
            String  serviceCentreAddress,
            long    mapDialogId,
            transient Object rawMAPDialog
        ) implements MapSms {}
        /**
         * MAP-SEND-ROUTING-INFO-FOR-SM-RESPONSE
         *
         * Khi nào: HLR trả lời SMSC với IMSI + MSC/SGSN address
         * Cùng convergence key với SriForSmRequest
         */
        @ConvergenceKey(
            protocol = "ss7.sms.sri",
            fields   = {"msisdn", "smscAddress"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record SriForSmResponse(
            long    sequenceNumber,
            String  msisdn,
            String  smscAddress,
            String  imsi,              // null nếu subscriber not found
            String  networkNodeNumber, // MSC address để SMSC gửi MT SMS đến
            boolean gprsSupported,
            Integer mapErrorCode,      // null = success; non-null = error code
            long    mapDialogId,
            transient Object rawMAPDialog
        ) implements MapSms {}
        /**
         * MAP-MO-FORWARD-SHORT-MESSAGE
         *
         * Khi nào: ME gửi SMS → MSC → SMSC (chúng ta là SMSC)
         * tpdu: raw SMS TPDU bytes (TP-Submit format)
         *
         * Correlation: msisdn + smscAddress + dialogId
         * (dialogId thêm vào vì cùng msisdn có thể gửi nhiều SMS đồng thời)
         */
        @ConvergenceKey(
            protocol = "ss7.sms.mo",
            fields   = {"msisdn", "smscAddress", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record MoForwardSmRequest(
            long   sequenceNumber,
            String msisdn,
            String smscAddress,
            byte[] tpdu,               // raw SMS TPDU
            long   mapDialogId,
            transient Object rawRequest
        ) implements MapSms {}
        @ConvergenceKey(
            protocol = "ss7.sms.mo",
            fields   = {"msisdn", "smscAddress", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record MoForwardSmResponse(
            long    sequenceNumber,
            String  msisdn,
            String  smscAddress,
            long    mapDialogId,
            boolean success,
            Integer mapErrorCode
        ) implements MapSms {}
        /**
         * MAP-MT-FORWARD-SHORT-MESSAGE
         *
         * Khi nào: SMSC gửi SMS đến ME (chúng ta là SMSC, gửi đến MSC)
         * tpdu: raw SMS TPDU bytes (TP-Deliver format)
         */
        @ConvergenceKey(
            protocol = "ss7.sms.mt",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record MtForwardSmRequest(
            long    sequenceNumber,
            String  msisdn,
            byte[]  tpdu,
            boolean moreMessagesToSend,
            long    mapDialogId,
            transient Object rawRequest
        ) implements MapSms {}
        @ConvergenceKey(
            protocol = "ss7.sms.mt",
            fields   = {"msisdn", "mapDialogId"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record MtForwardSmResponse(
            long    sequenceNumber,
            String  msisdn,
            long    mapDialogId,
            boolean success,
            Integer mapErrorCode
        ) implements MapSms {}
        /** MAP-REPORT-SM-DELIVERY-STATUS — delivery report từ MSC */
        @ConvergenceKey(
            protocol = "ss7.sms.report",
            fields   = {"msisdn", "serviceCentreAddress"},
            strategy = ConvergenceKey.Strategy.COMPOSITE
        )
        record ReportSmDeliveryStatus(
            long           sequenceNumber,
            String         msisdn,
            String         serviceCentreAddress,
            DeliveryOutcome outcome
        ) implements MapSms {
            public enum DeliveryOutcome {
                SUCCESSFUL_TRANSFER,
                ABSENT_SUBSCRIBER_SM,
                MSISDN_MEMORY_EXCEEDED,
                EQUIPMENT_NOT_SM_EQUIPPED,
                UNKNOWN_SUBSCRIBER
            }
        }
    }
    // ══════════════════════════════════════════════════════════════════
    // TCAP DIALOG EVENTS — transport layer
    // Tách riêng khỏi MAP operations (2 tầng độc lập)
    // jSS7: org.mobicents.protocols.ss7.tcap.api.*
    // ══════════════════════════════════════════════════════════════════
    sealed interface TcapDialog extends SleeEvent
        permits TcapDialog.Opened,
                TcapDialog.Delimiter,
                TcapDialog.Closed,
                TcapDialog.Released,
                TcapDialog.Timeout {
        @ConvergenceKey(
            protocol = "tcap",
            fields   = {"dialogId"},
            strategy = ConvergenceKey.Strategy.PROTOCOL_NATIVE
        )
        record Opened(
            long   sequenceNumber,
            long   dialogId,
            String remoteAddress,
            String localAddress
        ) implements TcapDialog {}
        @ConvergenceKey(protocol = "tcap", fields = {"dialogId"},
                        strategy = ConvergenceKey.Strategy.PROTOCOL_NATIVE)
        record Delimiter(long sequenceNumber, long dialogId) implements TcapDialog {}
        @ConvergenceKey(protocol = "tcap", fields = {"dialogId"},
                        strategy = ConvergenceKey.Strategy.PROTOCOL_NATIVE)
        record Closed(long sequenceNumber, long dialogId,
                      boolean prearrangedEnd) implements TcapDialog {}
        @ConvergenceKey(protocol = "tcap", fields = {"dialogId"},
                        strategy = ConvergenceKey.Strategy.PROTOCOL_NATIVE)
        record Released(long sequenceNumber, long dialogId,
                        byte[] pAbortCause) implements TcapDialog {}
        @ConvergenceKey(protocol = "tcap", fields = {"dialogId"},
                        strategy = ConvergenceKey.Strategy.PROTOCOL_NATIVE)
        record Timeout(long sequenceNumber, long dialogId) implements TcapDialog {}
    }
    // ══════════════════════════════════════════════════════════════════
    // HTTP — USSD over HTTP REST (ví dụ trong example-quarkus)
    // ══════════════════════════════════════════════════════════════════
    sealed interface Http extends SleeEvent
        permits Http.UssdBegin, Http.UssdContinue, Http.UssdEnd {
        /**
         * HTTP USSD Begin — FlakeId vì không có natural correlation key
         * từ HTTP protocol. Platform tự tạo sessionId.
         *
         * ⚠️ QUAN TRỌNG: RA phải lưu convergenceName() trả về và
         * trả về cho client qua X-Session-Id header. Client sẽ dùng
         * sessionId này cho tất cả requests tiếp theo.
         */
        @ConvergenceKey(strategy = ConvergenceKey.Strategy.FLAKE_ID)
        record UssdBegin(
            long   sequenceNumber,
            String msisdn,
            String ussdString,
            String callbackUrl
        ) implements Http {}
        /**
         * HTTP USSD Continue — dùng sessionId từ X-Session-Id header
         * để correlate với Begin event (cùng convergence key).
         */
        @ConvergenceKey(
            protocol = "http.ussd",
            fields   = {"sessionId"},
            strategy = ConvergenceKey.Strategy.PROTOCOL_NATIVE
        )
        record UssdContinue(
            long   sequenceNumber,
            String sessionId,   // từ X-Session-Id HTTP header
            String msisdn,
            String input
        ) implements Http {}
        @ConvergenceKey(
            protocol = "http.ussd",
            fields   = {"sessionId"},
            strategy = ConvergenceKey.Strategy.PROTOCOL_NATIVE
        )
        record UssdEnd(
            long   sequenceNumber,
            String sessionId,
            String msisdn
        ) implements Http {}
    }
    // ══════════════════════════════════════════════════════════════════
    // TIMER EVENTS
    // ══════════════════════════════════════════════════════════════════
    sealed interface Timer extends SleeEvent
        permits Timer.SessionTimeout, Timer.Retry {
        record SessionTimeout(
            String convergenceName,
            long   sequenceNumber,
            String entityId,
            long   timedOutAtMs
        ) implements Timer {}
        record Retry(
            String convergenceName,
            long   sequenceNumber,
            int    attempt,
            String operation
        ) implements Timer {}
    }
    // ══════════════════════════════════════════════════════════════════
    // LIFECYCLE EVENTS — entity created/removed/rehydrated
    // ══════════════════════════════════════════════════════════════════
    sealed interface Lifecycle extends SleeEvent
        permits Lifecycle.EntityRehydrated, Lifecycle.EntityRemoved {
        record EntityRehydrated(
            String convergenceName,
            long   sequenceNumber,
            String entityId,
            long   generation
        ) implements Lifecycle {}
        record EntityRemoved(
            String convergenceName,
            long   sequenceNumber,
            String entityId,
            String reason
        ) implements Lifecycle {}
    }
    // ══════════════════════════════════════════════════════════════════
    // EXTENSION — non-sealed, RA developer tự extend
    //
    // Dùng cho: SMPP, CAMEL, Diameter, SIP, proprietary protocols
    //
    // ✅ KHÔNG cần sửa SleeEvent.java gốc khi dùng Extension!
    // ✅ Chỉ cần implement interface này trong jar RA của bạn
    // ══════════════════════════════════════════════════════════════════
    non-sealed interface Extension extends SleeEvent {
        /**
         * Type identifier — EventRouter dùng để lookup dispatch table.
         * Convention: "protocol.operation" (lowercase, dấu chấm)
         *
         * Ví dụ:
         *   "smpp.deliver_sm"
         *   "camel.initial_dp"
         *   "diameter.ccr"
         *   "proprietary.my_event"
         */
        String eventTypeId();
    }
}
3.4 Ví dụ Extension Event (SMPP RA)
```
```java


// ra-connectors/ra-smpp/src/main/java/com/example/ra/smpp/SmppDeliverSmEvent.java
/**
 * SMPP Deliver SM event — Extension của SleeEvent.
 *
 * ✅ KHÔNG cần sửa SleeEvent.java
 * ✅ Đặt trong jar ra-smpp riêng → drop vào deploy/
 * ✅ convergenceName() tự động từ @ConvergenceKey
 */
@ConvergenceKey(
    protocol = "smpp",
    fields   = {"sourceAddress", "destinationAddress"},
    strategy = ConvergenceKey.Strategy.COMPOSITE
)
public record SmppDeliverSmEvent(
    long   sequenceNumber,
    String sourceAddress,
    String destinationAddress,
    byte[] shortMessage,
    transient Object rawPdu   // jSMPP DeliverSm object — không serialize
) implements SleeEvent.Extension {
    @Override
    public String eventTypeId() {
        return "smpp.deliver_sm";
    }
    // convergenceName() được APT generate tự động
    // = "smpp:{sourceAddress}:{destinationAddress}#{hash4}"
}
3.5 Cách SBB xử lý event với Pattern Matching
```
```java


// sbb-examples/sbb-ussd-menu/src/main/java/com/example/sbb/UssdMenuSbb.java
@Sbb(name = "ussd-menu-sbb", version = "1.0")
public class UssdMenuSbb {
    // CMP fields — lưu state của session (persist qua rehydration)
    private int    step   = 0;
    private String msisdn = null;
    /**
     * Handle tất cả MAP USSD events.
     *
     * @SleeEventHandler(SleeEvent.MapUssd.class) nghĩa là:
     * method này nhận MỌI subtype của MapUssd.
     * APT generate dispatch table (MethodHandle) tại compile time.
     */
    @SleeEventHandler(SleeEvent.MapUssd.class)
    public void onUssdEvent(SleeEvent.MapUssd event, ActivityContext aci) {
        // Java 25 pattern matching — exhaustive switch
        // Compiler ERROR nếu thiếu case → không bao giờ miss event type
        switch (event) {
            case SleeEvent.MapUssd.ProcessUnstructuredSsRequest req -> {
                // BEGIN: ME gửi *123# đến platform
                this.msisdn = req.msisdn();
                this.step   = 1;
                log.info("USSD BEGIN: msisdn={} input={}", req.msisdn(), req.ussdString());
                sendUssdMenu(buildMainMenu(), req.mapDialogId(), aci);
            }
            case SleeEvent.MapUssd.UnstructuredSsResponse resp -> {
                // CONTINUE: ME gửi lựa chọn (1, 2, 0...)
                this.step++;
                String response = switch (resp.ussdString().trim()) {
                    case "1" -> "Số dư: 50,000 VND\n0. Quay lại";
                    case "2" -> "Data: 2GB còn lại\n0. Quay lại";
                    case "0" -> { endSession(aci); yield "Tạm biệt!"; }
                    default  -> "Lựa chọn không hợp lệ.\n" + buildMainMenu();
                };
                sendUssdMenu(response, resp.mapDialogId(), aci);
            }
            case SleeEvent.MapUssd.UnstructuredSsRequest r ->
                // Network-initiated: platform chủ động gửi đến ME
                handleNetworkInitiated(r, aci);
            case SleeEvent.MapUssd.ProcessUnstructuredSsResponse r ->
                log.debug("USSD response delivered to {}", r.msisdn());
            case SleeEvent.MapUssd.UnstructuredSsNotify n ->
                log.info("Notify to {}: {}", n.msisdn(), n.notifyString());
            case SleeEvent.MapUssd.UnstructuredSsNotifyResponse r ->
                log.debug("Notify ack for dialog {}", r.mapDialogId());
            // ← Compiler ERROR nếu thiếu case nào trong MapUssd subtypes
        }
    }
    @SleeEventHandler(SleeEvent.Timer.SessionTimeout.class)
    public void onTimeout(SleeEvent.Timer.SessionTimeout event, ActivityContext aci) {
        log.warn("Session timeout: msisdn={} entity={}", msisdn, event.entityId());
        sendUssdMenu("Phiên làm việc hết thời gian. Vui lòng thử lại.", -1, aci);
        endSession(aci);
    }
    private String buildMainMenu() {
        return "Menu chính:\n1. Số dư\n2. Gói data\n0. Thoát";
    }
}
4. Proposal B — ConvergenceKeyFactory: Tự động sinh key
4.1 Vấn đề hiện tại
```
```java


// ❌ Hiện tại: developer tự nghĩ formula
String conv = "msisdn:" + msisdn + ":" + dialogId;  // ai nhớ format này?
String conv = UUID.randomUUID().toString();           // mất session continuity!
String conv = sessionId;                              // ok nhưng vẫn thủ công
4.2 4 Strategies — khi nào dùng cái gì?


PROTOCOL_NATIVE   "ss7.ussd:84901234567:12345"
  ✅ Khi: SIP callId (đã unique), HTTP sessionId
  ✅ Ưu điểm: human-readable, dễ grep trong logs
  ❌ Nhược: có thể collision nếu field values không đủ unique
HASH_STABLE       "3f2a9b1c4e5d7f8a"
  ✅ Khi: fields dài/chứa ký tự đặc biệt, cần fixed-length cho DB index
  ✅ Ưu điểm: luôn 16 chars, collision-resistant (64-bit SHA-256 prefix)
  ❌ Nhược: không human-readable
FLAKE_ID          "1K4M2XPQRST"
  ✅ Khi: HTTP Begin (session hoàn toàn mới), one-shot events
  ✅ Ưu điểm: globally unique, monotonic (sortable), no coordinator
  ❌ Nhược: không correlate được (mỗi instance là unique key riêng)
COMPOSITE         "ss7.ussd:84901234567:12345#a3f2"  ← DEFAULT
  ✅ Khi: SS7 USSD/SMS, SIP (production telecom) — RECOMMENDED
  ✅ Ưu điểm: = PROTOCOL_NATIVE + 4-char hash suffix
              = human-readable PREFIX + collision-proof SUFFIX
  ✅ Format: dễ đọc trong logs, tìm kiếm bằng grep prefix
4.3 File cần tạo: ConvergenceKeyFactory.java
Path: jainslee-api/src/main/java/com/microjainslee/api/convergence/ConvergenceKeyFactory.java
```

```java


/**
 * ConvergenceKeyFactory — sinh convergence key tự động.
 *
 * HƯỚNG DẪN CHO JUNIOR:
 * ─────────────────────────────────────────────────────────────────
 * Trong HẦUẾT trường hợp, bạn KHÔNG gọi class này trực tiếp.
 * @ConvergenceKey + APT sẽ tự gọi nó.
 *
 * Chỉ gọi trực tiếp khi:
 *   - Viết unit test cần generate key thủ công
 *   - Viết Extension event không dùng APT
 *   - Debug/verify key format
 * ─────────────────────────────────────────────────────────────────
 */
public final class ConvergenceKeyFactory {
    // ── FlakeId state (per JVM) ──────────────────────────────────────
    private static final long EPOCH_MS    = 1577836800000L; // 2020-01-01 UTC
    private static final long NODE_ID;    // 10 bits: từ env var hoặc MAC
    private static final AtomicLong LAST_TS  = new AtomicLong(0);
    private static final AtomicLong SEQUENCE = new AtomicLong(0);
    static {
        String envNodeId = System.getenv("JAINSLEE_NODE_ID");
        NODE_ID = envNodeId != null
                ? Long.parseLong(envNodeId) & 0x3FFL
                : deriveNodeIdFromMac();
    }
    // ── Public API ───────────────────────────────────────────────────
    /**
     * PROTOCOL_NATIVE strategy.
     *
     * Ví dụ:
     *   protocolNative("ss7.ussd", "84901234567", "12345")
     *   → "ss7.ussd:84901234567:12345"
     *
     * @param protocol  protocol prefix (dùng dấu chấm, ví dụ "ss7.ussd")
     * @param fields    các giá trị để correlate (phải đủ unique)
     * @throws IllegalArgumentException nếu fields rỗng
     */
    public static String protocolNative(String protocol, String... fields) {
        if (fields == null || fields.length == 0) {
            throw new IllegalArgumentException(
                "protocolNative requires at least one field. " +
                "Use flakeId() for stateless/one-shot events.");
        }
        var sb = new StringBuilder(64);
        sb.append(protocol);
        for (String f : fields) {
            sb.append(':');
            // Sanitize: replace ':' (separator) và ' ' (log parsing)
            sb.append(f != null ? f.replace(':', '_').replace(' ', '_') : "null");
        }
        return sb.toString();
    }
    /**
     * HASH_STABLE strategy.
     *
     * Ví dụ:
     *   hashStable("84901234567", "12345")
     *   → "3f2a9b1c4e5d7f8a"  (luôn 16 hex chars)
     *
     * Deterministic: cùng fields → cùng output (mọi lần, mọi node).
     * SHA-256 của (fields joined by "|") → take first 8 bytes → hex.
     */
    public static String hashStable(String... fields) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < fields.length; i++) {
                if (i > 0) md.update((byte) '|');
                if (fields[i] != null) {
                    md.update(fields[i].getBytes(StandardCharsets.UTF_8));
                }
            }
            byte[] digest = md.digest();
            var hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new RuntimeException("SHA-256 not available", ex);
        }
    }
    /**
     * FLAKE_ID strategy.
     *
     * Cấu trúc 64-bit:
     *   [63..22] = 42 bits timestamp (ms từ 2020-01-01) → ~139 năm
     *   [21..12] = 10 bits nodeId → 1024 nodes
     *   [11..0]  = 12 bits sequence → 4096 IDs/ms/node
     *
     * Output: base36 string, khoảng 11 ký tự, ví dụ "1K4M2XPQRST"
     * Globally unique, monotonically increasing (sortable by time).
     */
    public static String flakeId() {
        return Long.toUnsignedString(generateFlakeId(), 36).toUpperCase();
    }
    /**
     * COMPOSITE strategy — RECOMMENDED DEFAULT cho telecom.
     *
     * Ví dụ:
     *   composite("ss7.ussd", "84901234567", "12345")
     *   → "ss7.ussd:84901234567:12345#a3f2"
     *
     * Format: "{protocolNative}#{hashSuffix4chars}"
     * Lý do: prefix dễ đọc trong logs, suffix prevent collision edge cases.
     */
    public static String composite(String protocol, String... fields) {
        String native_    = protocolNative(protocol, fields);
        String hashSuffix = hashStable(fields).substring(0, 4); // first 4 hex chars
        return native_ + '#' + hashSuffix;
    }
    // ── FlakeId internals ────────────────────────────────────────────
    private static long generateFlakeId() {
        long now  = System.currentTimeMillis() - EPOCH_MS;
        long last = LAST_TS.get();
        long seq;
        if (now == last) {
            seq = SEQUENCE.incrementAndGet() & 0xFFFL; // 12 bits max = 4095
            if (seq == 0) {
                // Sequence exhausted trong 1ms → đợi ms tiếp theo
                while ((now = System.currentTimeMillis() - EPOCH_MS) <= last) {
                    Thread.onSpinWait(); // hint cho CPU spin-wait
                }
                SEQUENCE.set(0);
                seq = 0;
            }
        } else {
            LAST_TS.set(now);
            SEQUENCE.set(0);
            seq = 0;
        }
        // Pack vào 64-bit long
        return (now << 22) | (NODE_ID << 12) | seq;
    }
    private static long deriveNodeIdFromMac() {
        try {
            var nics = NetworkInterface.getNetworkInterfaces();
            while (nics != null && nics.hasMoreElements()) {
                byte[] mac = nics.nextElement().getHardwareAddress();
                if (mac != null && mac.length >= 2) {
                    return ((mac[mac.length - 2] & 0xFFL) << 2
                          | (mac[mac.length - 1] & 0x3L)) & 0x3FFL;
                }
            }
        } catch (Exception ignored) {}
        return (long) (Math.random() * 1024); // fallback: random 10 bits
    }
    private ConvergenceKeyFactory() {} // utility class, không instantiate
}
5. Proposal C — @ConvergenceKey APT Processor
5.1 Annotation
Path: jainslee-api/src/main/java/com/microjainslee/api/convergence/ConvergenceKey.java
```

```java


/**
 * @ConvergenceKey — đặt trên SleeEvent record.
 * APT processor sinh convergenceName() method tự động tại compile time.
 *
 * CÁCH DÙNG:
 * ─────────────────────────────────────────────────────────────────
 * Bước 1: Đặt annotation trên record
 *
 *   @ConvergenceKey(
 *       protocol = "ss7.ussd",
 *       fields   = {"msisdn", "mapDialogId"},
 *       strategy = ConvergenceKey.Strategy.COMPOSITE
 *   )
 *   record ProcessUnstructuredSsRequest(
 *       long   sequenceNumber,
 *       String msisdn,
 *       long   mapDialogId,
 *       ...
 *   ) implements SleeEvent.MapUssd {}
 *
 * Bước 2: mvn compile
 *   APT tự generate: ProcessUnstructuredSsRequest$Convergence.java
 *
 * Bước 3: EventRouter gọi $Convergence.convergenceName(event)
 *   Developer không cần làm gì thêm.
 * ─────────────────────────────────────────────────────────────────
 *
 * LỖI COMPILE THƯỜNG GẶP:
 *   "[R1] protocol required"               → thêm protocol = "..."
 *   "[R2] no fields found"                 → thêm fields = {"fieldName"}
 *   "[R3] field 'xxx' does not exist"      → kiểm tra chính xác tên field trong record
 *   "[R4] field 'rawXxx' is transient"     → dùng field non-transient khác
 *   "[R7] protocol contains ':'"           → dùng dấu chấm thay vì dấu hai chấm
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE) // chỉ cần tại compile time, không vào bytecode
@Repeatable(ConvergenceKeys.class)
public @interface ConvergenceKey {
    enum Strategy {
        /** "{protocol}:{field1}:{field2}" — human-readable */
        PROTOCOL_NATIVE,
        /** SHA-256(fields) → 16 hex chars — fixed length */
        HASH_STABLE,
        /** FlakeId — unique per instance, không correlate */
        FLAKE_ID,
        /** "{protocol_native}#{hash4}" — DEFAULT, recommended */
        COMPOSITE
    }
    /**
     * Protocol prefix cho PROTOCOL_NATIVE và COMPOSITE.
     * Bắt buộc với COMPOSITE/PROTOCOL_NATIVE.
     * Convention: lowercase, dấu chấm. Ví dụ: "ss7.ussd", "ss7.sms.sri"
     */
    String protocol() default "";
    /**
     * Tên các record components dùng làm correlation fields.
     *
     * Để trống → APT auto-detect tất cả non-transient components
     * (trừ sequenceNumber).
     *
     * ⚠️ Thứ tự quan trọng: ["msisdn", "dialogId"] ≠ ["dialogId", "msisdn"]
     * (khác hash, khác key)
     */
    String[] fields() default {};
    /**
     * Strategy sinh key.
     * Default: COMPOSITE (recommended cho telecom production).
     */
    Strategy strategy() default Strategy.COMPOSITE;
    /**
     * Tên field chứa sequence number.
     * Default: "sequenceNumber" (tên chuẩn trong hierarchy).
     * Chỉ cần đổi nếu record dùng tên khác.
     */
    String sequenceField() default "sequenceNumber";
}
// Container annotation cho @Repeatable (Java requires này)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
@interface ConvergenceKeys {
    ConvergenceKey[] value();
}
5.2 APT Processor — Module Structure


jainslee-apt/src/main/java/com/microjainslee/apt/
├── ConvergenceKeyProcessor.java        ← main AbstractProcessor
│   ├── init(): lấy Elements, Types, Messager, Filer
│   ├── process(): validate + generate per round
│   └── writeConvergenceRegistry(): final round → .properties
│
├── model/
│   ├── ConvergenceKeyModel.java        ← parsed data từ annotation + TypeElement
│   │   ├── parse(TypeElement, ConvergenceKey, Elements) → ConvergenceKeyModel
│   │   ├── getComponentType(fieldName) → "java.lang.String" / "long" / ...
│   │   └── isStringType(fieldName)     → true/false
│   └── RecordComponentInfo.java        ← metadata 1 record component
│       ├── name()        → "msisdn"
│       ├── typeName()    → "java.lang.String"
│       ├── isTransient() → true/false
│       └── asStringAccessor() → "msisdn()" hoặc "String.valueOf(mapDialogId())"
│
├── generator/
│   ├── ConvergenceBodyGenerator.java   ← generate method body per strategy
│   │   └── generate(model) → String (method body code)
│   └── HelperClassGenerator.java       ← generate full companion .java source
│       └── generate(model) → String (complete Java source file)
│
└── validator/
    └── ConvergenceKeyValidator.java    ← 8 validation rules
        ├── R1: COMPOSITE/NATIVE phải có protocol non-empty
        ├── R2: COMPOSITE/NATIVE/HASH phải có fields non-empty (hoặc auto-detect)
        ├── R3: Mỗi field phải là record component tồn tại
        ├── R4: Mỗi field không được là transient
        ├── R5: sequenceField phải tồn tại trong record
        ├── R6: FLAKE_ID + fields → WARNING (fields bị ignore)
        ├── R7: Protocol không chứa ':' → WARNING
        └── R8: Single boolean/byte/int field → WARNING (may not be unique)
META-INF/services/javax.annotation.processing.Processor:
  com.microjainslee.apt.ConvergenceKeyProcessor
5.3 Ví dụ output APT generate
```
```java


// AUTO-GENERATED — DO NOT EDIT
// Source: @ConvergenceKey trên MapUssd.ProcessUnstructuredSsRequest
// Strategy: COMPOSITE | Protocol: "ss7.ussd" | Fields: [msisdn, mapDialogId]
package com.microjainslee.api.event;
@Generated(value = "com.microjainslee.apt.ConvergenceKeyProcessor",
           date  = "2026-06-29T00:00:00Z")
public final class ProcessUnstructuredSsRequest$Convergence {
    private ProcessUnstructuredSsRequest$Convergence() {}
    /**
     * Returns: "ss7.ussd:{msisdn}:{mapDialogId}#{hash4}"
     * Ví dụ:   "ss7.ussd:84901234567:12345#a3f2"
     */
    public static String convergenceName(
            SleeEvent.MapUssd.ProcessUnstructuredSsRequest self) {
        return com.microjainslee.api.convergence.ConvergenceKeyFactory
            .composite(
                "ss7.ussd",
                self.msisdn(),
                String.valueOf(self.mapDialogId())
            );
    }
    public static long sequenceNumber(
            SleeEvent.MapUssd.ProcessUnstructuredSsRequest self) {
        return self.sequenceNumber();
    }
}
5.4 pom.xml cho module dùng APT
xml


<!-- Trong pom.xml của module chứa SleeEvent records -->
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <source>25</source>
                <target>25</target>
                <annotationProcessorPaths>
                    <path>
                        <groupId>com.microjainslee</groupId>
                        <artifactId>jainslee-apt</artifactId>
                        <version>${project.version}</version>
                    </path>
                </annotationProcessorPaths>
                <!-- Enable APT debug output (optional) -->
                <compilerArgs>
                    <arg>-Ajainslee.convergence.debug=true</arg>
                </compilerArgs>
            </configuration>
        </plugin>
    </plugins>
</build>
6. Proposal D — SessionRoutingEngine: Routing trung tâm
6.1 Vấn đề hiện tại
Routing logic đang scattered trong nhiều class:

MicroSleeContainer.attachRootSbbViaInitialEventSelector() — legacy fallback
EventRouter.deliverEvent() — entity lookup + delivery
InitialEventSelectorDispatcher.resolveTarget() — convergence lookup
Mọi routing decision phải tập trung vào 1 class để:

Dễ test (mock dependencies, assert RoutingDecision)
Dễ debug (1 chỗ để đặt breakpoint)
Dễ thêm rule mới (không phải sửa nhiều file)
6.2 File cần tạo: SessionRoutingEngine.java
Path: jainslee-core/src/main/java/com/microjainslee/core/routing/SessionRoutingEngine.java
```

```java


/**
 * SessionRoutingEngine — SINGLE AUTHORITY cho routing decisions.
 *
 * 5 ROUTING RULES (JAIN-SLEE 1.1 §7.5 + micro-jainslee extensions):
 *
 *   Rule 1: isInitial=true
 *           → IES đã allocate new entity → drain OOB buffer → deliver
 *
 *   Rule 2: convergence HIT + entity ALIVE
 *           → deliver trực tiếp đến EntitySlot ✅ (happy path)
 *
 *   Rule 3: convergence MISS + non-initial
 *           → check recovery index → rehydrate nếu có snapshot
 *           → hoặc buffer vào OutOfOrderBuffer
 *           → hoặc drop (spec §7.5.5)
 *
 *   Rule 4: convergence HIT + entity DEAD (slot released)
 *           → WRONG-SBB check (entityId mismatch prevention)
 *           → rehydrate từ RecoverySnapshot
 *
 *   Rule 5: duplicate event (same convergence + seqNum trong dedup window)
 *           → DROP (handled bởi SleeEndpointImpl trước khi vào đây)
 *
 * THREADING: called từ Disruptor consumer thread (SINGLE thread).
 * Không cần lock trên hot path (convergenceIndex, recoveryIndex).
 *
 * METRICS (có thể đọc bởi Quarkus metrics, JMX, logs):
 *   getRoutedToLive()      → events delivered bình thường
 *   getRoutedToNew()       → initial events (new entity created)
 *   getRehydrated()        → entities rehydrated từ snapshot
 *   getOobBuffered()       → events buffered (out-of-order)
 *   getDropped()           → events dropped (no entity, no snapshot)
 *   getWrongSbbPrevented() → attempted routing đến wrong SBB (nên = 0)
 */
public final class SessionRoutingEngine {
    private static final Logger log = LogManager.getLogger(SessionRoutingEngine.class);
    private final InitialEventSelectorDispatcher iesDispatcher;
    private final VirtualThreadSbbEntityPool     entityPool;
    private final SessionRecoveryService         recoveryService;
    private final OutOfOrderBuffer               oobBuffer;
    private final EventDeliveryPort              deliveryPort;
    // Metrics
    private final AtomicLong routedToLive      = new AtomicLong();
    private final AtomicLong routedToNew       = new AtomicLong();
    private final AtomicLong rehydrated        = new AtomicLong();
    private final AtomicLong oobBuffered       = new AtomicLong();
    private final AtomicLong dropped           = new AtomicLong();
    private final AtomicLong wrongSbbPrevented = new AtomicLong();
    public RoutingDecision route(SleeEvent event,
                                  ActivityContext aci,
                                  Class<?> sbbClass) {
        // Step 1: IES resolution
        IesResolution ies = iesDispatcher.resolveWithDetails(event, aci, sbbClass);
        // Rule 1: Initial event → new entity
        if (ies.isNewAllocation()) {
            return handleNewEntity(ies, event, aci);
        }
        // Rule 3: Convergence MISS
        if (ies.entityId() == null) {
            return handleConvergenceMiss(ies, event, aci);
        }
        // Step 2: Find entity trong pool
        SbbEntity entity = entityPool.findEntity(ies.entityId());
        // Rule 2: Entity alive → deliver (happy path)
        if (entity != null && !entity.getLocalObject().isRemoved()) {
            routedToLive.incrementAndGet();
            deliveryPort.deliverToEntity(entity, event, aci);
            return RoutingDecision.DELIVERED_TO_LIVE;
        }
        // Rule 4: Entity dead → rehydrate
        return handleDeadEntity(ies, event, aci);
    }
    private RoutingDecision handleNewEntity(IesResolution ies,
                                             SleeEvent event,
                                             ActivityContext aci) {
        routedToNew.incrementAndGet();
        SbbEntity newEntity = entityPool.findEntity(ies.entityId());
        if (newEntity == null) {
            // IES allocate entity nhưng pool không có → desync bug
            log.error("[Routing] Pool/IES DESYNC: entityId={} convergence={}",
                      ies.entityId(), ies.convergenceName());
            dropped.incrementAndGet();
            return RoutingDecision.DROPPED_POOL_DESYNC;
        }
        // Drain OOB buffer TRƯỚC khi deliver trigger event
        // (messages đến trước BEGIN phải được xử lý theo đúng thứ tự)
        if (ies.convergenceName() != null) {
            drainOobBuffer(ies.convergenceName(), aci);
        }
        deliveryPort.deliverToEntity(newEntity, event, aci);
        return RoutingDecision.DELIVERED_NEW_ENTITY;
    }
    private RoutingDecision handleConvergenceMiss(IesResolution ies,
                                                   SleeEvent event,
                                                   ActivityContext aci) {
        String conv = ies.convergenceName();
        // Check recovery index: entity vừa chết?
        if (conv != null && recoveryService.hasSnapshot(conv)) {
            log.info("[Routing] Conv MISS + snapshot found: {} → rehydrate", conv);
            return doRehydrate(ies, event, aci);
        }
        // Buffer out-of-order events (CONTINUE đến trước BEGIN)
        if (conv != null && oobBuffer != null) {
            if (oobBuffer.enqueue(conv, event, aci)) {
                oobBuffered.incrementAndGet();
                log.debug("[Routing] OOB buffered: conv={} event={}",
                          conv, event.getClass().getSimpleName());
                return RoutingDecision.OOB_BUFFERED;
            }
        }
        // Spec §7.5.5: drop
        dropped.incrementAndGet();
        log.warn("[Routing] DROPPED (no entity, no snapshot): conv={} event={}",
                 conv, event.getClass().getSimpleName());
        return RoutingDecision.DROPPED_NO_ENTITY;
    }
    private RoutingDecision handleDeadEntity(IesResolution ies,
                                              SleeEvent event,
                                              ActivityContext aci) {
        String conv     = ies.convergenceName();
        String entityId = ies.entityId();
        log.warn("[Routing] Entity DEAD: entityId={} conv={}", entityId, conv);
        if (conv != null && recoveryService.hasSnapshot(conv)) {
            // WRONG-SBB PREVENTION
            // IES và recoveryIndex phải agree về entityId
            // Nếu không match → possible convergenceIndex corruption
            RecoverySnapshot snap = recoveryService.peekSnapshot(conv);
            if (!snap.entityId().equals(entityId)) {
                wrongSbbPrevented.incrementAndGet();
                log.error(
                    "[Routing] WRONG-SBB PREVENTED! " +
                    "IES entityId={} ≠ snapshot entityId={} for conv={}. " +
                    "Possible convergenceIndex corruption. Refusing to route.",
                    entityId, snap.entityId(), conv);
                return RoutingDecision.DROPPED_WRONG_SBB_PREVENTED;
            }
            return doRehydrate(ies, event, aci);
        }
        dropped.incrementAndGet();
        log.warn("[Routing] Entity dead + no snapshot: entityId={} conv={}",
                 entityId, conv);
        return RoutingDecision.DROPPED_ENTITY_DEAD_NO_SNAPSHOT;
    }
    private RoutingDecision doRehydrate(IesResolution ies,
                                         SleeEvent event,
                                         ActivityContext aci) {
        String conv = ies.convergenceName();
        try {
            SbbEntity entity = recoveryService.rehydrate(conv);
            if (entity == null) {
                dropped.incrementAndGet();
                return RoutingDecision.DROPPED_REHYDRATION_FAILED;
            }
            // Re-register convergence → new entityId trong IES
            iesDispatcher.reregisterConvergence(conv, entity.getId());
            // Drain OOB buffer
            drainOobBuffer(conv, aci);
            // Deliver trigger event
            rehydrated.incrementAndGet();
            deliveryPort.deliverToEntity(entity, event, aci);
            log.info("[Routing] Rehydration SUCCESS: conv={} newEntity={}",
                     conv, entity.getId());
            return RoutingDecision.REHYDRATED_AND_DELIVERED;
        } catch (Exception ex) {
            dropped.incrementAndGet();
            log.error("[Routing] Rehydration EXCEPTION: conv={} err={}",
                      conv, ex.getMessage(), ex);
            return RoutingDecision.DROPPED_REHYDRATION_EXCEPTION;
        }
    }
    private void drainOobBuffer(String conv, ActivityContext triggerAci) {
        if (oobBuffer == null) return;
        var buffered = oobBuffer.drain(conv);
        if (buffered.isEmpty()) return;
        log.info("[Routing] Draining {} OOB events for conv={}", buffered.size(), conv);
        for (var buf : buffered) {
            // Re-route: entity đã tồn tại sau rehydration/allocation
            route((SleeEvent) buf.event(),
                  (ActivityContext) buf.activityContext(),
                  buf.sbbClass());
        }
    }
    /**
     * RoutingDecision enum — kết quả routing.
     *
     * DÙNG TRONG TEST:
     *   assertThat(engine.route(event, aci, sbbClass))
     *       .isEqualTo(RoutingDecision.REHYDRATED_AND_DELIVERED);
     *
     * DÙNG TRONG METRICS:
     *   if (decision == DROPPED_WRONG_SBB_PREVENTED) alertOps();
     */
    public enum RoutingDecision {
        DELIVERED_TO_LIVE,               // entity alive, happy path ✅
        DELIVERED_NEW_ENTITY,            // initial event, new entity created ✅
        REHYDRATED_AND_DELIVERED,        // entity was dead, rehydrated ✅
        OOB_BUFFERED,                    // out-of-order, buffered for later ℹ️
        DROPPED_NO_ENTITY,               // spec §7.5.5: no entity found ⚠️
        DROPPED_ENTITY_DEAD_NO_SNAPSHOT, // entity dead, no recovery data ⚠️
        DROPPED_REHYDRATION_FAILED,      // snapshot found but recon failed ❌
        DROPPED_REHYDRATION_EXCEPTION,   // unexpected exception ❌
        DROPPED_POOL_DESYNC,             // IES/pool out of sync → BUG 🐛
        DROPPED_WRONG_SBB_PREVENTED      // attempted wrong SBB routing → ALERT 🚨
    }
    // Getters cho metrics
    public long getRoutedToLive()      { return routedToLive.get(); }
    public long getRoutedToNew()       { return routedToNew.get(); }
    public long getRehydrated()        { return rehydrated.get(); }
    public long getOobBuffered()       { return oobBuffered.get(); }
    public long getDropped()           { return dropped.get(); }
    public long getWrongSbbPrevented() { return wrongSbbPrevented.get(); }
}
7. Proposal E — SessionRecoveryService: Rehydration
7.1 Kiến trúc dual-index


convergenceIndex (IES, LIVE):
  "ss7.ussd:84901234567:12345#a3f2" → "sbb-001"   ← entity đang alive
recoveryIndex (TOMBSTONE, SessionRecoveryService):
  "ss7.ussd:84901234567:12345#a3f2" → RecoverySnapshot {
      entityId:    "sbb-001",          ← để verify wrong-SBB prevention
      sbbClassName: "com.example.UssdMenuSbb",
      cmpFields:   { step:2, msisdn:"84901234567" },  ← STATE ĐỂ RESTORE
      aciNames:    ["session:84901234567:12345"],      ← ACIs để re-attach
      generation:  42,                 ← monotonic counter
      capturedAtMs: 1234567890,        ← để TTL check
      reason:      TIMER_EXPIRED       ← tại sao entity chết
  }
Khi entity chết:
  convergenceIndex: "ss7.ussd:..." → REMOVE (IesCleanupAdapter)
  recoveryIndex:    "ss7.ussd:..." → ADD (SessionRecoveryService)
Khi message tiếp theo đến:
  IES: convergenceIndex miss → SessionRoutingEngine
  Recovery: recoveryIndex hit → rehydrate → IES re-register new entity
7.2 File cần tạo: SessionRecoveryService.java
Path: jainslee-core/src/main/java/com/microjainslee/core/recovery/SessionRecoveryService.java
```

```java


/**
 * SessionRecoveryService — tombstone index + rehydration service.
 *
 * LIFECYCLE:
 *   1. Entity chết → RemovalListener:
 *      recoveryService.registerSnapshot(convergence, entityId, sbbClass,
 *                                        cmpFields, aciNames, reason)
 *
 *   2. Message đến, entity không còn → SessionRoutingEngine:
 *      recoveryService.hasSnapshot(convergence) → true
 *      recoveryService.rehydrate(convergence)   → SbbEntity (mới)
 *
 * CAPACITY:
 *   Max 64K entries (LRU eviction khi full)
 *   TTL 5 phút (snapshot expired sau thời gian này)
 *   Max 3 rehydrate attempts per convergence (anti-loop)
 *
 * THREAD SAFETY:
 *   registerSnapshot() — called on virtual thread của dead entity
 *   rehydrate()        — called on Disruptor consumer thread
 *   → Both use synchronized LinkedHashMap (LRU)
 */
public final class SessionRecoveryService
        implements Consumer<EntityRemovalEvent> {
    private static final Logger log =
            LogManager.getLogger(SessionRecoveryService.class);
    private static final int DEFAULT_MAX_SIZE  = 64 * 1024; // 64K entries
    private static final long DEFAULT_TTL_MS   = 5 * 60 * 1000L; // 5 phút
    private static final int MAX_REHYDRATE_ATTEMPTS = 3;
    private final Map<String, RecoverySnapshot> recoveryIndex;
    private final RehydrationFactory            factory;
    private final long                          snapshotTtlMs;
    private final AtomicLong                    generationCounter = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicInteger> rehydrateAttempts
            = new ConcurrentHashMap<>();
    // Metrics
    private final AtomicLong snapshotsRegistered = new AtomicLong();
    private final AtomicLong rehydrateSuccess    = new AtomicLong();
    private final AtomicLong rehydrateFailed     = new AtomicLong();
    private final AtomicLong snapshotsExpired    = new AtomicLong();
    /**
     * Factory interface — implemented bởi MicroSleeContainer.
     * Decouples recovery service khỏi container internals.
     */
    public interface RehydrationFactory {
        /**
         * Reconstruct SBB entity từ snapshot.
         * MicroSleeContainer sẽ implement method này.
         *
         * Contract:
         *   - Tạo mới SBB POJO từ sbbClassName
         *   - Restore CMP fields từ snapshot.cmpFields()
         *   - Gọi sbbLoad() (JAIN-SLEE 1.1 §8.5)
         *   - Re-attach đến ACIs trong snapshot.attachedAciNames()
         *   - Register vào VirtualThreadSbbEntityPool
         *   - Return null nếu không thể reconstruct (pool exhausted, ...)
         */
        SbbEntity reconstructFromSnapshot(RecoverySnapshot snapshot);
    }
    public SessionRecoveryService(RehydrationFactory factory) {
        this(DEFAULT_MAX_SIZE, DEFAULT_TTL_MS, factory);
    }
    public SessionRecoveryService(int maxSize, long snapshotTtlMs,
                                   RehydrationFactory factory) {
        this.snapshotTtlMs = snapshotTtlMs;
        this.factory       = factory;
        // LRU LinkedHashMap: access-order=true, removeEldestEntry khi > maxSize
        var lru = new LinkedHashMap<String, RecoverySnapshot>(
                maxSize, 0.75f, /* accessOrder= */ true) {
            @Override
            protected boolean removeEldestEntry(
                    Map.Entry<String, RecoverySnapshot> eldest) {
                return size() > maxSize;
            }
        };
        this.recoveryIndex = Collections.synchronizedMap(lru);
    }
    // ── Consumer<EntityRemovalEvent> (từ EntityRemovalBus) ──────────
    @Override
    public void accept(EntityRemovalEvent event) {
        // Bus wiring point — actual snapshot được register
        // bởi MicroSleeContainer.RemovalListener trực tiếp
        // (vì cần access CMP fields trước khi pool.release())
    }
    // ── Write path ───────────────────────────────────────────────────
    /**
     * Register snapshot khi entity bị remove.
     *
     * ⚠️ PHẢI gọi TRƯỚC sbbEntityPool.release() — sau release() thì
     * CMP fields bị xóa, không còn state để capture.
     */
    public void registerSnapshot(
            String convergence,
            String entityId,
            Class<?> sbbClass,
            Map<String, Object> cmpFields,
            Set<String> aciNames,
            EntityRemovalEvent.RemovalReason reason) {
        if (convergence == null) {
            // Không có convergence key → không thể recover
            log.debug("[Recovery] No convergence for entity={} — skip snapshot", entityId);
            return;
        }
        long gen = generationCounter.incrementAndGet();
        var snap = new RecoverySnapshot(
                convergence, entityId, sbbClass.getName(),
                Collections.unmodifiableMap(new LinkedHashMap<>(cmpFields)),
                Collections.unmodifiableSet(new LinkedHashSet<>(aciNames)),
                gen, System.currentTimeMillis(), reason);
        recoveryIndex.put(convergence, snap);
        snapshotsRegistered.incrementAndGet();
        log.info("[Recovery] Snapshot registered: conv={} entity={} " +
                 "cmpKeys={} aciCount={} reason={} gen={}",
                 convergence, entityId,
                 cmpFields.keySet(), aciNames.size(), reason, gen);
    }
    // ── Read path ─────────────────────────────────────────────────────
    /** Check xem có snapshot hay không (không consume). */
    public boolean hasSnapshot(String convergence) {
        RecoverySnapshot snap = recoveryIndex.get(convergence);
        if (snap == null) return false;
        if (isExpired(snap)) {
            recoveryIndex.remove(convergence);
            snapshotsExpired.incrementAndGet();
            log.debug("[Recovery] Snapshot expired for conv={}", convergence);
            return false;
        }
        return true;
    }
    /** Peek snapshot để verify entityId (wrong-SBB prevention). Không consume. */
    public RecoverySnapshot peekSnapshot(String convergence) {
        return recoveryIndex.get(convergence);
    }
    /**
     * Rehydrate entity từ snapshot.
     *
     * Consume-once: snapshot bị xóa sau khi gọi (thành công hay thất bại).
     * Anti-loop: max MAX_REHYDRATE_ATTEMPTS lần per convergence.
     *
     * @return SbbEntity mới (đã attach ACIs, đã register pool)
     *         null nếu thất bại (caller xử lý drop)
     */
    public SbbEntity rehydrate(String convergence) {
        // Anti-loop: prevent infinite rehydration nếu SBB cứ chết ngay sau khi tạo
        int attempts = rehydrateAttempts
                .computeIfAbsent(convergence, k -> new AtomicInteger(0))
                .incrementAndGet();
        if (attempts > MAX_REHYDRATE_ATTEMPTS) {
            log.error("[Recovery] MAX_ATTEMPTS ({}) exceeded for conv={} — hard drop",
                      MAX_REHYDRATE_ATTEMPTS, convergence);
            rehydrateAttempts.remove(convergence);
            recoveryIndex.remove(convergence);
            rehydrateFailed.incrementAndGet();
            return null;
        }
        // Consume snapshot (remove từ index)
        RecoverySnapshot snap = recoveryIndex.remove(convergence);
        if (snap == null) {
            log.warn("[Recovery] rehydrate() called but no snapshot: conv={}", convergence);
            rehydrateFailed.incrementAndGet();
            return null;
        }
        // TTL check
        if (isExpired(snap)) {
            snapshotsExpired.incrementAndGet();
            log.warn("[Recovery] Snapshot expired: conv={} age={}ms ttl={}ms",
                     convergence,
                     System.currentTimeMillis() - snap.capturedAtMs(),
                     snapshotTtlMs);
            rehydrateFailed.incrementAndGet();
            return null;
        }
        // Reconstruct via factory (MicroSleeContainer)
        try {
            SbbEntity entity = factory.reconstructFromSnapshot(snap);
            if (entity != null) {
                rehydrateAttempts.remove(convergence); // reset on success
                rehydrateSuccess.incrementAndGet();
            } else {
                rehydrateFailed.incrementAndGet();
                log.error("[Recovery] reconstructFromSnapshot returned null: conv={}",
                          convergence);
            }
            return entity;
        } catch (Exception ex) {
            rehydrateFailed.incrementAndGet();
            log.error("[Recovery] Exception in reconstructFromSnapshot: conv={} err={}",
                      convergence, ex.getMessage(), ex);
            return null;
        }
    }
    private boolean isExpired(RecoverySnapshot snap) {
        return System.currentTimeMillis() - snap.capturedAtMs() > snapshotTtlMs;
    }
    // Metrics getters
    public long getSnapshotsRegistered() { return snapshotsRegistered.get(); }
    public long getRehydrateSuccess()    { return rehydrateSuccess.get(); }
    public long getRehydrateFailed()     { return rehydrateFailed.get(); }
    public long getSnapshotsExpired()    { return snapshotsExpired.get(); }
    public int  getActiveSnapshotCount() { return recoveryIndex.size(); }
}
7.3 RecoverySnapshot.java
Path: jainslee-core/src/main/java/com/microjainslee/core/recovery/RecoverySnapshot.java
```

```java


/**
 * Immutable snapshot của SBB entity captured tại thời điểm removal.
 *
 * FIELDS:
 *   convergence    — routing key (also recoveryIndex key)
 *   entityId       — original entity ID (for wrong-SBB prevention)
 *   sbbClassName   — fully-qualified class name (for reconstruction)
 *   cmpFields      — defensive copy of CMP state (key → value)
 *   attachedAciNames — ACNF names for re-attachment
 *   generation     — monotonic counter (newer = more recent state)
 *   capturedAtMs   — wall-clock capture time (for TTL)
 *   reason         — why entity died
 *
 * SERIALIZATION: intentionally NOT Serializable.
 * Cluster mode sử dụng ClusterRecoverySnapshot (jainslee-cluster module)
 * với Protostream schema.
 */
public record RecoverySnapshot(
        String              convergence,
        String              entityId,
        String              sbbClassName,
        Map<String, Object> cmpFields,        // Collections.unmodifiableMap
        Set<String>         attachedAciNames, // Collections.unmodifiableSet
        long                generation,
        long                capturedAtMs,
        EntityRemovalEvent.RemovalReason reason
) {
    /**
     * Resolve conflict khi 2 snapshots tồn tại cho cùng convergence.
     * Higher generation = newer = wins.
     * Dùng trong cluster mode khi 2 nodes race to write snapshot.
     */
    public static RecoverySnapshot newerOf(RecoverySnapshot a, RecoverySnapshot b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.generation() >= b.generation() ? a : b;
    }
}
8. Proposal F — EntityRemovalBus: Observability
8.1 Vấn đề hiện tại


Khi entity bị remove (bất kỳ lý do gì):
  ❌ RA không biết → UssdSessionStore ở trạng thái PROCESSING mãi mãi
  ❌ convergenceIndex không được xóa → memory leak (GAP-SR-7)
  ❌ Không có log structured → debug khó
  ❌ Không có metric → monitoring không có gì
8.2 Files cần tạo
EntityRemovalEvent.java:
```

```java


// jainslee-core/src/main/java/com/microjainslee/core/removal/EntityRemovalEvent.java
/**
 * Immutable event published khi SBB entity bị remove.
 *
 * Published bởi: MicroSleeContainer.RemovalListener.onRemoved()
 * Consumed bởi: EntityRemovalBus subscribers
 *
 * TIMING: published SAU detachFromAllActivityContexts(), TRƯỚC pool.release()
 * → subscribers còn có thể dùng entityId để lookup data
 */
@Proto // Protostream annotation — để cluster mode serialize
public record EntityRemovalEvent(
        @ProtoField(number = 1) String entityId,
        @ProtoField(number = 2) String convergenceKey,  // null nếu không có IES
        @ProtoField(number = 3) RemovalReason reason,
        @ProtoField(number = 4) long timestampMs
) {
    @Proto
    public enum RemovalReason {
        @ProtoEnumValue(number = 0) TIMER_EXPIRED,
        @ProtoEnumValue(number = 1) SBB_SELF_REMOVE,
        @ProtoEnumValue(number = 2) CASCADE_CHILD,
        @ProtoEnumValue(number = 3) OPERATOR,
        @ProtoEnumValue(number = 4) EXCEPTION_ROLLBACK,
        @ProtoEnumValue(number = 5) HOT_REDEPLOY
    }
}
EntityRemovalBus.java:
```

```java


// jainslee-core/src/main/java/com/microjainslee/core/removal/EntityRemovalBus.java
/**
 * Lightweight synchronous pub-sub bus cho EntityRemovalEvent.
 *
 * SUBSCRIBERS được đăng ký khi startup (MicroSleeContainer.start()):
 *
 *   bus.subscribe(new IesCleanupAdapter(iesDispatcher));
 *   bus.subscribe(new SessionLifecycleLogger());
 *   bus.subscribe(sessionRecoveryService);  // capture snapshot
 *   bus.subscribe(metricsCounter::increment);
 *   // RA-side (đăng ký từ Quarkus app):
 *   bus.subscribe(ussdSessionStore::onEntityRemoval);
 *
 * THREADING: synchronous, called trên virtual thread của entity.
 * → Subscribers PHẢI fast (< 1µs).
 * → Subscribers KHÔNG ĐƯỢC block hoặc gọi lại MicroSleeContainer.
 *
 * FAILURE ISOLATION: một subscriber throw exception
 * → log error → tiếp tục gọi subscribers còn lại (defensive fan-out).
 */
public final class EntityRemovalBus {
    private final List<Consumer<EntityRemovalEvent>> subscribers =
            new CopyOnWriteArrayList<>();
    // Thread-safe reads (CopyOnWriteArrayList), writes chỉ tại startup
    public void subscribe(Consumer<EntityRemovalEvent> subscriber) {
        subscribers.add(subscriber);
    }
    public void unsubscribe(Consumer<EntityRemovalEvent> subscriber) {
        subscribers.remove(subscriber);
    }
    /** Publish to all subscribers. Never throws. */
    public void publish(EntityRemovalEvent event) {
        for (Consumer<EntityRemovalEvent> s : subscribers) {
            try {
                s.accept(event);
            } catch (Exception ex) {
                System.err.printf(
                    "[EntityRemovalBus] Subscriber %s threw on entity %s: %s%n",
                    s.getClass().getSimpleName(), event.entityId(), ex.getMessage());
            }
        }
    }
}
SessionLifecycleLogger.java:
```

```java


// jainslee-core/src/main/java/com/microjainslee/core/removal/SessionLifecycleLogger.java
/**
 * Subscriber cho EntityRemovalBus — emit structured log line.
 *
 * LOG FORMAT (one line, easily grep-able):
 *   SLEE_SESSION | action=REMOVED | entityId=xxx | convergence=yyy |
 *                  reason=TIMER_EXPIRED | ts=1234567890
 *
 * MONITORING:
 *   grep "SLEE_SESSION | action=REMOVED | reason=TIMER_EXPIRED" app.log
 *   grep "SLEE_SESSION | action=REMOVED | reason=EXCEPTION_ROLLBACK" app.log
 */
public final class SessionLifecycleLogger
        implements Consumer<EntityRemovalEvent> {
    private static final Logger log =
            LogManager.getLogger(SessionLifecycleLogger.class);
    @Override
    public void accept(EntityRemovalEvent event) {
        log.info("SLEE_SESSION | action=REMOVED | entityId={} | convergence={} | " +
                 "reason={} | ts={}",
                 event.entityId(),
                 event.convergenceKey() != null ? event.convergenceKey() : "n/a",
                 event.reason(),
                 event.timestampMs());
    }
}
8.3 Wire RemovalListener trong MicroSleeContainer
```
```java


// MicroSleeContainer.java — buildLocalObject() RemovalListener lambda
// THỨ TỰ QUAN TRỌNG: capture → detach → remove → publish → release
Consumer<SimpleSbbLocalObject> removalListener = (removedObject) -> {
    String id = removedObject.getSbbID().getId();
    // ① Resolve removal reason
    RemovalReason reason = resolveRemovalReason(removedObject);
    // ② Get convergence key (trước khi IES cleanup)
    String convKey = iesDispatcher != null
            ? iesDispatcher.getConvergenceKeyFor(id)
            : null;
    // ③ Capture CMP state (TRƯỚC release — sau release thì mất)
    Map<String, Object> cmpSnapshot = new LinkedHashMap<>();
    if (removedObject.getSbb() instanceof CmpBackedSbb cmp) {
        cmp.cmpPersist(); // flush lazy writes to store
        cmpSnapshot.putAll(cmpFieldStore.getFieldsCopy(id));
    }
    // ④ Get attached ACI names (TRƯỚC detach)
    Set<String> aciNames = getAttachedAciNames(id);
    // ⑤ Register recovery snapshot (TRƯỚC release — state còn valid)
    if (sessionRecoveryService != null && convKey != null) {
        sessionRecoveryService.registerSnapshot(
                convKey, id,
                removedObject.getSbb().getClass(),
                cmpSnapshot, aciNames, reason);
    }
    // ⑥ Detach từ ACIs
    detachFromAllActivityContexts(removedObject);
    // ⑦ Remove từ container maps
    sbbs.remove(id);
    entityTypesById.remove(id);
    // ⑧ Publish EntityRemovalEvent (bus subscribers tự xử lý phần còn lại)
    entityRemovalBus.publish(
            new EntityRemovalEvent(id, convKey, reason, System.currentTimeMillis()));
    // → IesCleanupAdapter.accept():  convergenceIndex.remove(id)  ← đóng GAP-SR-7
    // → SessionLifecycleLogger.accept(): log structured line
    // → UssdSessionStore.accept():   session.status = FAILED
    // ⑨ Release pool + CMP store (CUỐI CÙNG)
    sbbEntityPool.release(entity);
    cmpFieldStore.remove(id);
};
8.4 Đăng ký subscribers khi startup
```
```java


// MicroSleeContainer.java — trong start() hoặc builder
// Core subscribers
entityRemovalBus.subscribe(new SessionLifecycleLogger());
entityRemovalBus.subscribe(event -> entityRemovalCounter.incrementAndGet());
// IES cleanup (đóng GAP-SR-7) — đăng ký trong setInitialEventSelectorDispatcher()
public void setInitialEventSelectorDispatcher(InitialEventSelectorDispatcher d) {
    this.iesDispatcher = d;
    // Subscriber: khi entity removed → xóa convergence khỏi IES index
    entityRemovalBus.subscribe(
        event -> d.removeConvergencesFor(event.entityId())
    );
}
// RA-side subscriber (đăng ký từ UssdSbbWiring hoặc Quarkus @PostConstruct)
public void bindToContainer(MicroSleeContainer container) {
    this.removalListener = event -> {
        String sessionId = stripSuffix(event.entityId(), "/http");
        if (sessionId != null) {
            // Idempotent: COMPLETED không bị override bởi FAILED (Risk R6)
            sessions.computeIfPresent(sessionId, (k, existing) -> {
                if (existing.getStatus() == SessionStatus.PROCESSING) {
                    return existing
                            .withStatus(SessionStatus.FAILED)
                            .withMessage("Entity removed: " + event.reason());
                }
                return existing; // COMPLETED stays COMPLETED
            });
        }
    };
    container.addEntityRemovalListener(this.removalListener);
}
9. Proposal G — Hot-Deploy RA/SBB jar
9.1 Mục tiêu


Trước (phải rebuild toàn bộ):
  sửa UssdMenuSbb.java
  → mvn package (compile toàn bộ project)
  → kill Quarkus
  → java -jar quarkus-run.jar (restart)
  → downtime: 30-60 giây
Sau (hot-deploy):
  sửa UssdMenuSbb.java
  → mvn package -pl sbb-ussd-menu
  → cp target/sbb-ussd-menu-2.0.jar $JAINSLEE_HOME/deploy/
  → auto-undeploy 1.0, deploy 2.0
  → downtime: ~0 (sessions drain gracefully trong 30s)
9.2 Directory Layout


$JAINSLEE_HOME/
├── lib/                              ← immutable core (không thay đổi)
│   ├── jainslee-api-1.1.jar
│   ├── jainslee-core-1.1.jar
│   └── adapter-quarkus-1.1.jar
│
├── deploy/                           ← DROP JAR VÀO ĐÂY
│   ├── ra-ss7-ussd-1.0.jar          ← auto-loaded khi startup
│   ├── ra-ss7-sms-1.0.jar
│   ├── ra-http-ingress-1.0.jar
│   ├── sbb-ussd-menu-1.0.jar
│   └── sbb-sms-routing-1.0.jar
│
└── quarkus-app/
    └── application.properties
        # Hot-deploy config:
        quarkus.jainslee.deploy-dir=../deploy
        quarkus.jainslee.hot-deploy-enabled=true
        quarkus.jainslee.drain-timeout-ms=30000
# Update workflow (zero-downtime):
cp sbb-ussd-menu-2.0.jar $JAINSLEE_HOME/deploy/
# → WatchService detect ENTRY_MODIFY
# → Undeploy 1.0: stop RA → drain sessions (30s) → unregister SBB class
# → Deploy 2.0: load jar → register SBB class → start RA
# → Done! New sessions use 2.0, old sessions drain gracefully
9.3 Jar Structure (RA/SBB jar)


ra-ss7-ussd-1.0.jar
├── com/example/ra/ss7/
│   ├── Ss7UssdResourceAdaptor.class
│   └── Ss7UssdRaDescriptor.class       ← APT generated từ @ResourceAdaptor
├── META-INF/
│   ├── jainslee/
│   │   └── ra-descriptor.properties    ← APT generated
│   └── services/
│       └── com.microjainslee.api.deploy.RaDescriptor
│           (com.example.ra.ss7.Ss7UssdRaDescriptor)
└── (bundled dependencies: jSS7 classes, etc.)
9.4 Annotation để APT auto-generate descriptor
```
```java


// @ResourceAdaptor annotation — đặt trên RA class
@ResourceAdaptor(
    name    = "ss7-ussd-ra",
    version = "1.0.0",
    vendor  = "micro-jainslee",
    firedEvents = {
        "map.ussd.process_unstructured_ss_request",
        "map.ussd.unstructured_ss_response",
        "map.ussd.unstructured_ss_notify"
    }
)
public class Ss7UssdResourceAdaptor implements MAPServiceSupplementaryListener {
    private ResourceAdaptorContext context;
    // jSS7 callback → sealed event → fireEvent
    @Override
    public void onProcessUnstructuredSSRequest(
            ProcessUnstructuredSSRequestIndication req) {
        // ① Tạo sealed event record
        //    convergenceName() tự động từ @ConvergenceKey + APT
        var event = new SleeEvent.MapUssd.ProcessUnstructuredSsRequest(
            nextSeq(req),                           // sequenceNumber
            req.getMSISDN().getAddress(),           // msisdn
            req.getUSSDString().getString(null),    // ussdString
            req.getDataCodingScheme().getCode(),    // dataCodingScheme
            req.getMAPDialog().getLocalDialogId(),  // mapDialogId
            req                                     // rawRequest (transient)
        );
        // ② Lookup hoặc tạo ACI bằng convergenceName() (tự động)
        ActivityContext aci = context
            .getActivityContextNamingFacility()
            .lookupOrCreate(event.convergenceName());
        // ③ Fire event → SessionRoutingEngine sẽ route đúng SBB
        context.getSleeEndpoint().fireEvent(event, aci);
    }
    @Override
    public void onUnstructuredSSResponse(UnstructuredSSResponseIndication resp) {
        var event = new SleeEvent.MapUssd.UnstructuredSsResponse(
            nextSeq(resp),
            resp.getMSISDN() != null ? resp.getMSISDN().getAddress() : "unknown",
            resp.getUSSDString().getString(null),
            resp.getMAPDialog().getLocalDialogId()
        );
        ActivityContext aci = context
            .getActivityContextNamingFacility()
            .lookup(event.convergenceName()); // existing ACI (session đã tồn tại)
        if (aci == null) {
            // Session không tồn tại → SessionRoutingEngine sẽ check recovery
            // Tạo ACI tạm để fire event (routing engine sẽ quyết định)
            aci = context.getActivityContextNamingFacility()
                         .lookupOrCreate(event.convergenceName());
        }
        context.getSleeEndpoint().fireEvent(event, aci);
    }
}
10. Proposal H — Protostream Schema cho Cluster
10.1 Tại sao cần Protostream?


Java default serialization:
  ❌ Không efficient (verbose binary format)
  ❌ Java-only (không interop)
  ❌ Không GraalVM native-image friendly
  ❌ Brittle (serialVersionUID issues)
Protostream (Protocol Buffers-based, của Infinispan):
  ✅ Compact binary format
  ✅ Schema-first → forward/backward compatible
  ✅ GraalVM native-image safe
  ✅ Infinispan native → tối ưu hóa cross-node transfer
10.2 Proto Schema File
Path: jainslee-cluster/src/main/resources/proto/jainslee-cluster.proto

protobuf


syntax = "proto3";
package com.microjainslee.cluster;
option java_package = "com.microjainslee.cluster";
option java_outer_classname = "JainsleeClusterProto";
// ────────────────────────────────────────────────────────────────────
// SBB ENTITY SNAPSHOT
// Persist state của 1 SBB entity để restore khi failover
// ────────────────────────────────────────────────────────────────────
message SbbEntitySnapshot {
    string sbb_id                      = 1;
    string sbb_class_name              = 2;
    map<string, bytes> cmp_fields      = 3;  // Object serialized as bytes
    repeated string attached_aci_names = 4;
    int64  snapshot_timestamp          = 5;
}
// ────────────────────────────────────────────────────────────────────
// ENTITY REMOVAL EVENT
// Broadcast khi SBB entity bị remove (cross-node notification)
// ────────────────────────────────────────────────────────────────────
message EntityRemovalEvent {
    string entity_id       = 1;
    string convergence_key = 2;
    RemovalReason reason   = 3;
    int64  timestamp_ms    = 4;
}
enum RemovalReason {
    TIMER_EXPIRED      = 0;
    SBB_SELF_REMOVE    = 1;
    CASCADE_CHILD      = 2;
    OPERATOR           = 3;
    EXCEPTION_ROLLBACK = 4;
    HOT_REDEPLOY       = 5;
}
// ────────────────────────────────────────────────────────────────────
// CLUSTER RECOVERY SNAPSHOT
// Extended version của RecoverySnapshot cho cluster mode
// Thêm: generation counter, origin node, snapshot version
// ────────────────────────────────────────────────────────────────────
message ClusterRecoverySnapshot {
    string convergence                 = 1;
    string entity_id                   = 2;
    string sbb_class_name              = 3;
    map<string, bytes> cmp_fields      = 4;
    repeated string attached_aci_names = 5;
    int64  generation                  = 6;  // monotonic per JVM
    int64  captured_at_ms              = 7;
    string origin_node_id              = 8;  // JGroups node address
    int64  snapshot_version            = 9;  // for optimistic lock
}
// ────────────────────────────────────────────────────────────────────
// SLEE EVENT ENVELOPE
// Serialize SleeEvent qua cluster (chỉ serializable fields)
// rawRequest (transient) KHÔNG có trong schema
// ────────────────────────────────────────────────────────────────────
message SleeEventEnvelope {
    string convergence_name = 1;
    int64  sequence_number  = 2;
    oneof payload {
        // MAP USSD
        UssdProcessRequest   ussd_process_request   = 10;
        UssdProcessResponse  ussd_process_response  = 11;
        UssdUnstructuredReq  ussd_unstructured_req  = 12;
        UssdUnstructuredResp ussd_unstructured_resp = 13;
        UssdNotify           ussd_notify            = 14;
        UssdNotifyResponse   ussd_notify_response   = 15;
        // MAP SMS
        SmsOriForSmRequest   sms_sri_request        = 20;
        SmsOriForSmResponse  sms_sri_response       = 21;
        SmsMoForwardRequest  sms_mo_request         = 22;
        SmsMoForwardResponse sms_mo_response        = 23;
        SmsMtForwardRequest  sms_mt_request         = 24;
        SmsMtForwardResponse sms_mt_response        = 25;
        // Timer
        SessionTimeout       session_timeout        = 30;
        RetryTimer           retry_timer            = 31;
    }
}
// ── USSD messages ─────────────────────────────────────────────────
message UssdProcessRequest {
    string msisdn              = 1;
    string ussd_string         = 2;
    int32  data_coding_scheme  = 3;
    int64  map_dialog_id       = 4;
    // rawRequest: KHÔNG serialize (transient — chỉ dùng on-node)
}
message UssdProcessResponse {
    string msisdn             = 1;
    string ussd_string        = 2;
    int32  data_coding_scheme = 3;
    int64  map_dialog_id      = 4;
}
message UssdUnstructuredReq {
    string msisdn             = 1;
    string ussd_string        = 2;
    int32  data_coding_scheme = 3;
    int64  map_dialog_id      = 4;
}
message UssdUnstructuredResp {
    string msisdn        = 1;
    string ussd_string   = 2;
    int64  map_dialog_id = 3;
}
message UssdNotify {
    string msisdn        = 1;
    string notify_string = 2;
    int64  map_dialog_id = 3;
}
message UssdNotifyResponse {
    string msisdn        = 1;
    int64  map_dialog_id = 2;
}
// ── SMS messages ──────────────────────────────────────────────────
message SmsOriForSmRequest {
    string msisdn                  = 1;
    string smsc_address            = 2;
    bool   sm_rp_pri               = 3;
    string service_centre_address  = 4;
    int64  map_dialog_id           = 5;
}
message SmsOriForSmResponse {
    string msisdn               = 1;
    string smsc_address         = 2;
    string imsi                 = 3;
    string network_node_number  = 4;
    bool   gprs_supported       = 5;
    int32  map_error_code       = 6;  // 0 = success
    int64  map_dialog_id        = 7;
}
message SmsMoForwardRequest {
    string msisdn        = 1;
    string smsc_address  = 2;
    bytes  tpdu          = 3;
    int64  map_dialog_id = 4;
}
message SmsMoForwardResponse {
    string msisdn        = 1;
    string smsc_address  = 2;
    int64  map_dialog_id = 3;
    bool   success       = 4;
    int32  map_error_code = 5;
}
message SmsMtForwardRequest {
    string msisdn                = 1;
    bytes  tpdu                  = 2;
    bool   more_messages_to_send = 3;
    int64  map_dialog_id         = 4;
}
message SmsMtForwardResponse {
    string msisdn         = 1;
    int64  map_dialog_id  = 2;
    bool   success        = 3;
    int32  map_error_code = 4;
}
// ── Timer messages ────────────────────────────────────────────────
message SessionTimeout {
    string entity_id      = 1;
    int64  timed_out_at_ms = 2;
}
message RetryTimer {
    int32  attempt    = 1;
    string operation  = 2;
}
10.3 Đăng ký schema trong ClusterManager
// jainslee-cluster/src/main/java/com/microjainslee/cluster/ClusterManager.java
public void start() throws Exception {
    // ... JGroups channel init, Infinispan CacheManager init ...
    SerializationContext ctx = cacheManager.getSerializationContext();
    // Đăng ký tất cả @Proto annotated classes
    new ProtoSchemaBuilder()
            .fileName("jainslee-cluster.proto")
            .packageName("com.microjainslee.cluster")
            .addClass(SbbEntitySnapshot.class)
            .addClass(EntityRemovalEvent.class)
            .addClass(EntityRemovalEvent.RemovalReason.class)
            .addClass(ClusterRecoverySnapshot.class)
            .addClass(SleeEventEnvelope.class)
            .build(ctx);
    // Custom marshaller cho Map<String, Object> (CMP fields)
    ctx.registerMarshaller(new CmpFieldMapMarshaller());
    log.info("[Cluster] Protostream schemas registered for all cluster types");
}
10.4 Infinispan Cache Configuration
xml


<!-- infinispan.xml -->
<infinispan>
    <!-- Recovery snapshots: REPL_ASYNC, all nodes have copy -->
    <replicated-cache name="sbb-entity-state" mode="ASYNC" statistics="true">
        <encoding media-type="application/x-protostream"/>
        <memory max-count="100000"/>
        <expiration lifespan="300000"/> <!-- 5 phút TTL -->
    </replicated-cache>
    <!-- Entity removal events: REPL_SYNC, immediate consistency -->
    <replicated-cache name="entity-removal-events" mode="SYNC">
        <encoding media-type="application/x-protostream"/>
        <expiration lifespan="60000"/> <!-- 1 phút -->
    </replicated-cache>
    <!-- ACNF (Activity Context Naming Facility): DIST_SYNC -->
    <distributed-cache name="acnf" owners="2" mode="SYNC">
        <encoding media-type="application/x-protostream"/>
    </distributed-cache>
</infinispan>
11. Implementation Checklist
⚡ Ưu tiên: làm theo thứ tự Sprint S6 → S7 → S8 → S9
S7, S8, S9 đều depend vào S6.

🟥 Sprint S6 — Observability & Removal Notification (~1 tuần)


PRIORITY 0 (làm NGAY — 5 phút):
□ Fix GAP-SR-11: UssdSbbWiring.java lines 22-23
  BEFORE: import com.microjainslee.ra.grpc.GrpcMenuResourceAdaptor;
          import com.microjainslee.ra.http.HttpIngressResourceAdaptor;
  AFTER:  import com.example.ussddemo.quarkus.ra.GrpcMenuResourceAdaptor;
          import com.example.ussddemo.quarkus.ra.HttpIngressResourceAdaptor;
CORE TASKS:
□ Tạo EntityRemovalEvent.java (record + RemovalReason enum)
□ Tạo EntityRemovalBus.java (CopyOnWriteArrayList, defensive fan-out)
□ Tạo SessionLifecycleLogger.java (subscribe bus, log "SLEE_SESSION | ...")
□ Sửa MicroSleeContainer.buildLocalObject() RemovalListener:
    □ Capture CMP fields (cmpPersist() + getFieldsCopy())
    □ Capture ACI names (getAttachedAciNames())
    □ Resolve removal reason
    □ Register RecoverySnapshot (TRƯỚC release)
    □ Publish EntityRemovalEvent (TRƯỚC release)
□ Thêm getAttachedAciNames(String sbbId) vào MicroSleeContainer
□ Thêm entries() vào InMemoryActivityContextNamingFacility
□ Tạo VirtualThreadSbbEntityPool.IesCleanupAdapter
□ Sửa setInitialEventSelectorDispatcher() → đăng ký IesCleanupAdapter
□ Thêm missingEntityCount AtomicLong vào EventRouter
□ Sửa UssdSessionStore.bindToContainer() + idempotent FAILED guard
□ Thêm getConvergenceKeyFor(String entityId) vào InitialEventSelectorDispatcher
TESTS (8 unit tests):
□ EntityRemovalBus: publish notifies all subscribers
□ EntityRemovalBus: one subscriber throws → others still receive
□ EntityRemovalBus: unsubscribe removes listener
□ EntityRemovalBus: missingEntityCount increments on null entity
□ UssdSessionStore: auto-fails on removal event
□ UssdSessionStore: COMPLETED not overridden by FAILED
□ IesCleanupAdapter: convergence removed after entity release
□ SessionLifecycleLogger: log contains "SLEE_SESSION | action=REMOVED"
ACCEPTANCE:
  releaseEntity(sid) từ BẤT KỲ nguồn nào
  → UssdSessionStore.get(sid).status == FAILED trong 100ms
  → entityRemovalCounter tăng 1
  → convergenceIndex không còn entry cho session đó
🟧 Sprint S7 — Re-creation & Replay (~1 tuần)


□ Tạo RecoverySnapshot.java (record + newerOf() static method)
□ Tạo SessionRecoveryService.java:
    □ Bounded LRU cache (synchronized LinkedHashMap, max 64K)
    □ registerSnapshot() — gọi từ RemovalListener trước release
    □ hasSnapshot() + peekSnapshot() (không consume)
    □ rehydrate() — consume-once, anti-loop (max 3 attempts)
    □ isExpired() — TTL 5 phút
□ Tạo IesResolution.java (record thay cho bare String return)
□ Thêm resolveWithDetails() vào InitialEventSelectorDispatcher
□ Thêm reregisterConvergence() vào InitialEventSelectorDispatcher
□ Implement MicroSleeContainer.reconstructFromSnapshot():
    □ Class.forName(sbbClassName) → newInstance()
    □ Restore CMP fields via cmpFieldStore.set()
    □ Build SbbContext + setSbbContext()
    □ sbbLoad() (JAIN-SLEE 1.1 §8.5)
    □ buildLocalObject() → register RemovalListener
    □ slotPool.borrow() → slot.bind() → SbbEntity
    □ sbbEntityPool.register() + sbbs.put() + entityTypesById.put()
    □ Re-attach ACIs: aci.attachImmediate() + bridge.bindActivityContext()
    □ Tạo fresh ACI nếu ACI đã expire
□ Tạo SessionRoutingEngine.java (xem Proposal D)
□ Sửa EventRouter.deliverEvent():
    □ Khi entity null → increment missingEntityCount
    □ Gọi sessionRecoveryService.tryRehydrateAndDeliver()
TESTS (11 unit tests):
□ SessionRecoveryService: registerSnapshot → getSnapshot found
□ SessionRecoveryService: rehydrate consumes snapshot (second call = null)
□ SessionRecoveryService: expired snapshot → not found
□ SessionRecoveryService: max 3 attempts → hard drop
□ SessionRecoveryService: LRU eviction at maxSize
□ EventRouter: null entity → missingEntityCount increments
□ EventRouter: null entity + snapshot → rehydrated count increments
□ SessionRoutingEngine: dead entity → REHYDRATED_AND_DELIVERED
□ SessionRoutingEngine: dead entity + no snapshot → DROPPED
□ SessionRoutingEngine: wrong entityId match → DROPPED_WRONG_SBB_PREVENTED
□ SessionRoutingEngine: OOB buffered → drained after entity allocated
ACCEPTANCE:
  CONTINUE sau khi SBB chết (5ms delay)
  → SBB mới nhận event với CMP state đúng (step=2, msisdn=...)
  → convergenceName() mapping updated trong IES
  → No wrong-SBB routing (wrongSbbPrevented metric = 0)
🟨 Sprint S8 — Ordering & Dedup (~1 tuần)


□ Tạo OutOfOrderBuffer.java:
    □ ConcurrentHashMap<String, Deque<BufferedEvent>>
    □ enqueue() — bounded (max 16/convergence), TTL 30s
    □ drain() — FIFO order
    □ evictExpired() — ScheduledExecutorService, sweep mỗi TTL/2
□ Sửa IES.resolveTarget():
    □ MISS + non-initial → oobBuffer.enqueue() thay vì drop
    □ allocateNew() → drain oobBuffer trước
□ Tạo DedupWindow.java:
    □ LRU LinkedHashMap<(convergence,seqNum), expiryMs>
    □ isDuplicate() — records + returns true if within window
    □ evictExpired() — periodic cleanup
□ Sửa SleeEndpointImpl.fireEvent():
    □ Check dedupWindow.isDuplicate() trước khi route
    □ Drop duplicate + log debug
□ Implement SequencedEvent (optional) trên event records cần dedup
TESTS (9 unit tests):
□ OutOfOrderBuffer: enqueue → drain returns in FIFO order
□ OutOfOrderBuffer: capacity overflow → oldest dropped
□ OutOfOrderBuffer: TTL expiry → evicted
□ OutOfOrderBuffer: drain returns empty for unknown convergence
□ DedupWindow: first event → not duplicate
□ DedupWindow: same (conv,seq) within window → duplicate
□ DedupWindow: same (conv,seq) after window expires → not duplicate
□ SleeEndpoint: duplicate event → handler called only once
□ Integration: CONTINUE before BEGIN → buffered → delivered in order
ACCEPTANCE:
  CONTINUE đến trước BEGIN → buffered
  → Sau khi BEGIN → drain OOB → CONTINUE delivered trước BEGIN event
  Duplicate CONTINUE → dropped với debug log
  → dedupHitCount metric increments
🟦 Sprint S9 — Event Model + APT + Cluster + Hot-Deploy (~2 tuần)


EVENT MODEL:
□ Tạo SleeEvent.java sealed hierarchy (xem Proposal A)
□ Tạo ConvergenceKey.java annotation
□ Tạo ConvergenceKeys.java container annotation
APT PROCESSOR:
□ Tạo ConvergenceKeyProcessor.java (AbstractProcessor)
□ Tạo ConvergenceKeyModel.java
□ Tạo RecordComponentInfo.java
□ Tạo ConvergenceBodyGenerator.java (4 strategy cases)
□ Tạo HelperClassGenerator.java (generate $Convergence.java)
□ Tạo ConvergenceKeyValidator.java (rules R1-R8)
□ Tạo META-INF/services/javax.annotation.processing.Processor
□ Sửa EventRouter: gọi $Convergence.convergenceName() thay vì field trực tiếp
CONVERGENCE KEY FACTORY:
□ Tạo ConvergenceKeyFactory.java:
    □ protocolNative()
    □ hashStable() — SHA-256, first 8 bytes → 16 hex
    □ flakeId() — 42+10+12 bit layout
    □ composite()
    □ deriveNodeIdFromMac()
CLUSTER FIXES:
□ Sửa DistributedSbbEntityPool.takeSnapshot():
    □ Populate attachedAciNames qua container.getAttachedAciNames()
    □ Thêm generation + originNodeId vào ClusterRecoverySnapshot
□ Sửa DistributedSbbEntityPool.applySnapshot():
    □ Re-attach SBB to listed ACIs sau khi restore CMP
□ Tạo jainslee-cluster.proto (xem Proposal H)
□ Đăng ký Protostream schema trong ClusterManager.start()
□ Tạo CmpFieldMapMarshaller.java
HOT-DEPLOY:
□ Tạo RaDescriptor.java interface
□ Tạo SbbDescriptor.java interface
□ Tạo DeployableUnitLoader.java (WatchService monitor deploy/)
□ Tạo IsolatedClassLoader.java (child-first loading)
□ Tạo DeployedUnit.java (activate/deactivate graceful)
□ Tạo @ResourceAdaptor + @Sbb annotations
□ APT generate RaDescriptor/SbbDescriptor + META-INF/services
□ Sửa JainsleeDeployLoader.java (Quarkus @ApplicationScoped)
□ Thêm deployResourceAdaptor/undeployResourceAdaptor vào container
TESTS (15+ unit + integration tests):
□ ConvergenceKeyProcessor: compile-time validation R1-R8
□ ConvergenceKeyProcessor: generate correct convergenceName() body
□ ConvergenceKeyProcessor: write convergence-registry.properties
□ ConvergenceKeyFactory: protocolNative, hashStable, flakeId, composite
□ ConvergenceKeyFactory: flakeId monotonic và unique
□ DistributedSbbEntityPool: snapshot includes attachedAciNames
□ Cluster failover: kill node A → node B rehydrates with correct CMP
□ DeployableUnitLoader: deploy jar → RA/SBB registered
□ DeployableUnitLoader: replace jar → old undeploy, new deploy
□ DeployableUnitLoader: remove jar → graceful undeploy
□ Integration: full USSD dialog với sealed events end-to-end
ACCEPTANCE:
  mvn package -pl ra-ss7-ussd
  cp target/ra-ss7-ussd-2.0.jar deploy/
  → WatchService detects trong < 500ms
  → Old RA stops accepting initial events
  → Old sessions drain trong 30s
  → New RA starts, new sessions use new code
  → Zero wrong-SBB routing throughout
12. FAQ cho Junior Engineer
Q1: Convergence key là gì? Tôi có cần tự tạo không?
Không cần tự tạo. Đặt @ConvergenceKey trên record → APT tự generate.

Để hiểu khái niệm: Convergence key là string duy nhất định danh một session. Ví dụ: "ss7.ussd:84901234567:12345#a3f2" = protocol + msisdn + dialogId + hash. SLEE dùng key này để biết "event này thuộc session nào → SBB nào xử lý".

Chỉ tự gọi ConvergenceKeyFactory trực tiếp khi:

Viết unit test cần key thủ công
Viết Extension event không dùng APT
Debug/verify key format
Q2: Tôi muốn viết RA mới. Bắt đầu từ đâu?


Bước 1: Tạo Maven module ra-my-protocol/
Bước 2: Đặt @ResourceAdaptor trên class
Bước 3: Implement protocol callbacks (jSS7, jSMPP, SIP, ...)
Bước 4: Trong callback: tạo sealed event record (với @ConvergenceKey)
         → fireEvent(event, aci)
Bước 5: mvn package → jar → drop vào deploy/
Xem ví dụ: Ss7UssdResourceAdaptor.java trong ra-connectors/ra-ss7-ussd/
Q3: SBB chết giữa USSD session. User thấy gì?
Với Sprint S7 implemented:

Session được rehydrate từ snapshot (CMP state đầy đủ)
User tiếp tục dialog từ đúng bước trước đó
Latency thêm: ~5-20ms (rehydration overhead)
User không thấy gì khác biệt
Nếu S7 chưa implemented (hiện tại):

User thấy "session không phản hồi" hoặc "session reset về đầu"
Đây là GAP-SR-1 + GAP-SR-2 trong investigation report
Q4: Có thể route nhầm vào SBB khác không?
Hai case có thể xảy ra (cả hai đã được phòng ngừa):

Case 1 — Convergence index leak (GAP-SR-7):

IES map giữ stale entry sau khi entity chết
Fix: IesCleanupAdapter (S6) xóa entry ngay khi entity remove
Case 2 — Entity ID mismatch:

convergenceIndex và recoveryIndex disagree về entityId
Fix: SessionRoutingEngine.handleDeadEntity() check:
```
```java


if (!snap.entityId().equals(iesEntityId)) {
    return DROPPED_WRONG_SBB_PREVENTED; // từ chối route
}
Metric để monitor: engine.getWrongSbbPrevented() — luôn phải = 0 trong production.

Q5: FlakeId vs COMPOSITE — khi nào dùng cái nào?


FLAKE_ID	COMPOSITE
Dùng khi	Event không cần correlate với events khác	Events cùng session (req + resp, multi-step)
Ví dụ	HTTP UssdBegin (session mới, chưa có ID)	SS7 USSD, SRI-for-SM, SIP call
Output	"1K4M2XPQRST" (unique per instance)	"ss7.ussd:84901234567:12345#a3f2"
Correlation	❌ Mỗi instance khác nhau	✅ Cùng fields → cùng key
Q6: Sealed interface vs POJO — performance khác nhau không?


switch (event) {  // sealed → JIT compile thành tableswitch
    case Begin b -> ...    // O(1) lookup
    case Continue c -> ...
    case End e -> ...
}
// Tương đương với bytecode:
// tableswitch { 0: begin, 1: continue, 2: end }
// → O(1), không phụ thuộc số case
// So sánh với instanceof chain (POJO cũ):
if (event instanceof BeginEvent b) ...           // O(1) nhưng...
else if (event instanceof ContinueEvent c) ...   // phải check tuần tự
else if (event instanceof EndEvent e) ...        // O(n) worst case
// Kết quả thực đo (Java 25 JIT):
// sealed switch:   ~1-3 ns/op
// instanceof chain: ~3-8 ns/op (tệ hơn khi nhiều case)
// → sealed nhanh hơn, AND compiler enforce exhaustiveness
Q7: Hot-deploy có work với GraalVM native image không?
Không. GraalVM native image là closed-world compilation. Dynamic class loading KHÔNG được hỗ trợ tại runtime.

Configuration:

properties


# JVM mode (dev, test, staging):
quarkus.jainslee.hot-deploy-enabled=true
# Native image mode (production):
quarkus.jainslee.hot-deploy-enabled=false
# (default false trong native profile)
Với native image: tất cả RA/SBB phải compile vào image tĩnh. Chỉ dùng hot-deploy trong development và staging.

Q8: Viết unit test cho SessionRoutingEngine như thế nào?
```
```java


// Pattern chuẩn cho SessionRoutingEngine tests
class SessionRoutingEngineTest {
    MockIesDispatcher     ies      = new MockIesDispatcher();
    MockEntityPool        pool     = new MockEntityPool();
    SessionRecoveryService recovery;
    OutOfOrderBuffer      oob      = new OutOfOrderBuffer(16, 30_000);
    MockDeliveryPort      delivery = new MockDeliveryPort();
    SessionRoutingEngine  engine;
    @BeforeEach
    void setUp() {
        // Factory: tạo entity từ snapshot với entityId mới
        recovery = new SessionRecoveryService(1000, 300_000,
                snap -> pool.createEntityFromSnapshot(snap));
        engine = new SessionRoutingEngine(ies, pool, recovery, oob, delivery);
    }
    // TEST 1: Happy path (Rule 2)
    @Test
    void happyPath_entityAlive_deliveredToLive() {
        pool.createEntity("e1");
        ies.setupHit("ss7.ussd:111:1#ab12", "e1", false);
        var decision = engine.route(
            new TestEvent(), new MockAci("a1"), TestSbb.class);
        assertThat(decision).isEqualTo(DELIVERED_TO_LIVE);
        assertThat(engine.getRoutedToLive()).isEqualTo(1);
    }
    // TEST 2: Rehydration (Rule 4)
    @Test
    void deadEntity_rehydratesWithCorrectCmpState() {
        // Giả lập: entity e1 vừa chết, snapshot được capture
        recovery.registerSnapshot(
            "ss7.ussd:84901234567:12345#a3f2", // convergence
            "e1-dead",                          // entityId
            UssdMenuSbb.class,                  // sbbClass
            Map.of("step", 2, "msisdn", "84901234567"), // CMP state
            Set.of("session:84901234567:12345"),          // ACI names
            TIMER_EXPIRED                       // reason
        );
        // IES biết entityId = "e1-dead" nhưng pool không có (đã release)
        ies.setupHit("ss7.ussd:84901234567:12345#a3f2", "e1-dead", false);
        pool.markRemoved("e1-dead");
        // Route event → phải rehydrate
        var decision = engine.route(
            new TestEvent(), new MockAci("a1"), UssdMenuSbb.class);
        assertThat(decision).isEqualTo(REHYDRATED_AND_DELIVERED);
        assertThat(engine.getRehydrated()).isEqualTo(1);
        // Verify CMP state được restore đúng
        SbbEntity restored = pool.getLastCreatedEntity();
        assertThat(restored.getCmpField("step")).isEqualTo(2);
        assertThat(restored.getCmpField("msisdn")).isEqualTo("84901234567");
    }
    // TEST 3: Wrong-SBB prevention
    @Test
    void wrongSbbPrevented_whenEntityIdMismatch() {
        // Snapshot nói entity là "e1-a" nhưng IES nói "e1-b"
        // (simulating convergenceIndex corruption)
        recovery.registerSnapshot(
            "conv:111", "e1-a", TestSbb.class,
            Map.of(), Set.of(), TIMER_EXPIRED);
        ies.setupHit("conv:111", "e1-b", false); // e1-b ≠ e1-a !
        pool.markRemoved("e1-b");
        var decision = engine.route(
            new TestEvent(), new MockAci("a1"), TestSbb.class);
        assertThat(decision).isEqualTo(DROPPED_WRONG_SBB_PREVENTED);
        assertThat(engine.getWrongSbbPrevented()).isEqualTo(1);
        assertThat(delivery.getDeliveredEvents()).isEmpty(); // không deliver
    }
    // TEST 4: Out-of-order buffer
    @Test
    void oobBuffer_continueBeforeBegin_deliveredInOrder() {
        String conv = "ss7.ussd:222:1#cd34";
        ies.setupMiss(conv); // BEGIN chưa đến
        // CONTINUE đến trước → buffered
        var d1 = engine.route(new TestEvent("continue"), new MockAci("a1"),
                               TestSbb.class);
        assertThat(d1).isEqualTo(OOB_BUFFERED);
        // BEGIN đến → allocate entity + drain OOB
        pool.prepareAllocation("e2");
        ies.setupInitial(conv, "e2");
        var d2 = engine.route(new TestEvent("begin"), new MockAci("a1"),
                               TestSbb.class);
        assertThat(d2).isEqualTo(DELIVERED_NEW_ENTITY);
        // Verify thứ tự: OOB "continue" phải delivered TRƯỚC "begin"
        assertThat(delivery.getDeliveredEventNames())
            .containsExactly("continue", "begin");
    }
}
Q9: Làm thế nào để verify APT processor hoạt động?
bash
```


# Cách 1: Build và xem generated sources
mvn compile
ls target/generated-sources/annotations/
# → Thấy: ProcessUnstructuredSsRequest$Convergence.java
# Cách 2: Đọc generated file
cat target/generated-sources/annotations/com/microjainslee/api/event/\
    ProcessUnstructuredSsRequest\$Convergence.java
# Cách 3: Unit test APT processor
# Xem ConvergenceKeyProcessorTest.java trong jainslee-apt/src/test/
# Cách 4: Verify convergence-registry.properties
cat target/classes/META-INF/jainslee/convergence-registry.properties
# → com.microjainslee.api.event.SleeEvent$MapUssd$ProcessUnstructuredSsRequest
#   =COMPOSITE:ss7.ussd:msisdn,mapDialogId
# Cách 5: Compile error test (sai field name)
# Thêm @ConvergenceKey(fields = {"nonExistent"}) vào record
# → mvn compile phải báo lỗi: "[R3] field 'nonExistent' does not exist"
Q10: Cluster mode — khi nào cần lo về Protostream?
Chỉ khi deploy multi-node (cluster mode).

Single-node deployment (development, small scale):

Không cần Protostream
MicroSleeConfiguration.clusterEnabled = false (default)
Recovery hoạt động qua in-memory SessionRecoveryService
Multi-node deployment (production, HA):

Cần Protostream để serialize cross-node
MicroSleeConfiguration.clusterEnabled = true
ClusterManager.start() đăng ký schema
DistributedSbbEntityPool dùng Infinispan thay vì local map
Tài liệu tham khảo


Tài liệu	Mô tả
README.md	Project overview, quick start
docs/microjainslee-design.md	Architecture chi tiết
docs/run-testcase-100k-sbb.md	Stress test 100K SBBs
docs/micro-jainslee-cmp-production-roadmap.md	CMP production gaps
JAIN-SLEE 1.1 Spec (JSR-240)	§6.3 SBB Lifecycle, §7.5 IES, §8.3 ACI
jainslee-core/.../ies/InitialEventSelectorDispatcherTest.java	IES test examples
example-quarkus/.../sbbs/HttpServerSbb.java	Ví dụ SBB đơn giản
example-quarkus/.../service/UssdSbbWiring.java	Wiring example
Proposal version 1.0 — micro-jainslee 1.1.x
Tác giả: micro-jainslee Architecture Team
Ngày: 2026-06-29
Status: PROPOSAL — Pending Junior Engineer Review & Implementation
Repo: https://github.com/nhanth87/jain-slee/tree/micro-jainslee





# micro-jainslee — Core Gap Analysis (Jakarta + Quarkus + Java 25)
# Updated: 2026-06-24

## Trạng thái hiện tại

### ✅ ĐÃ CÓ PLAN CHI TIẾT
- EventRouter (Virtual Threads + ReentrantLock per AC)
- SbbEntity (POJO wrapper + FSM lifecycle)
- StateMachine (sealed interfaces + pattern matching)
- ActivityContextRegistry (ConcurrentHashMap)
- CoreTimerFacility (ScheduledExecutorService + VT dispatch)
- SleeContainerFactory (builder pattern)
- SbbAnnotationProcessor (APT - build-time)
- SbbRegistry (ServiceLoader)
- jainslee-api ports: EventPublisherPort, TimerPort, RaEndpointPort, RaCommandPort
- adapter-quarkus bootstrap (@ApplicationScoped + CDI)

### ❌ CHƯA CÓ / CÒN THIẾU (Gap list đầy đủ)

#### GAP-01: ProfileFacility (spec §10)
- JAIN-SLEE 1.1 §10 yêu cầu ProfileFacility đầy đủ
- ProfileTable, ProfileSpec, ProfileID
- Port: ProfileTablePort (interface đã mention nhưng chưa define chi tiết)
- Cần: ProfileTablePort interface + CDI/JPA adapter impl trong adapter-quarkus

#### GAP-02: ServiceManagement (spec §13)
- Service lifecycle: INACTIVE → ACTIVE → STOPPING → INACTIVE
- ServiceID, ServiceComponent
- DeploymentUnit concept (SBB + RA bundled)
- SleeManagementMBean (cho operator interface)
- Chưa có bất kỳ class nào cho phần này

#### GAP-03: NamingFacility (spec §14)
- JAIN-SLEE 1.1 §14: global namespace cho SBB lookup objects
- Thay JNDI bằng CDI-based naming trong micro-jainslee
- NamingPort interface chưa được define

#### GAP-04: AlarmFacility (spec §15)
- §15: AlarmFacility cho operator alerting
- AlarmLevel (WARNING, MINOR, MAJOR, CRITICAL, CLEARED)
- AlarmPort interface chưa có

#### GAP-05: TraceFacility (spec §16)
- §16: TraceFacility cho SBB runtime tracing (khác với logging thông thường)
- TraceLevel, TraceMessageEvent
- Cần tích hợp với Quarkus OpenTelemetry (otel) → TraceFacilityQuarkusAdapter
- Hiện tại chỉ mention "Logger" chung chung

#### GAP-06: UsageFacility (spec §12)
- §12: SBB Usage Parameters (counter, sample, average)
- UsageParameterSet, UsageNotificationManager
- Quan trọng cho telecom KPIs/metrics → tích hợp với Quarkus Micrometer
- Chưa có gì

#### GAP-07: SbbLocalObject (spec §8.5)
- SbbLocalObject là interface SBB dùng để call lẫn nhau (local invocation)
- Phải implement đầy đủ: getSbbPriority, sbbRollback, v.v.
- Hiện tại chỉ mention tên interface, chưa có implementation plan

#### GAP-08: ActivityContextNamingFacility (spec §6.2)
- §6.2: bind/lookup ActivityContext bằng tên
- ActivityContextNamingFacilityPort chưa define
- Quan trọng: cho phép SBB lookup AC theo tên (cross-SBB communication)

#### GAP-09: Transaction (spec §§ 5.14, 6.8)
- JAIN-SLEE spec yêu cầu transactional semantics cho SBB invocation
- rollback, commit khi sbbExceptionThrown
- micro-jainslee dùng SLEE Transaction Model hay bỏ qua?
- Quyết định: dùng "logical transaction" không cần JTA/XA

#### GAP-10: EventContextInterface (spec §7.4)
- EventContext: suspend/resume event processing
- suspend(): tạm dừng event processing trên AC
- resume(): tiếp tục
- Cần tích hợp với Virtual Thread (park/unpark)
- Chưa có plan

#### GAP-11: SbbContext (spec §8.6)
- SbbContext inject vào SBB khi sbbCreate()
- Cung cấp: getSbbLocalObject(), getActivityContextNamingFacility()
- SbbContextPort đã mention nhưng chưa đầy đủ methods theo spec

#### GAP-12: RaBootstrapContext (spec §11.2)
- ResourceAdaptorContext (spec name) cho RA lifecycle
- createActivityContextHandle(), getActivityContextHandle()
- Đã define RaBootstrapContext trong gap-analysis nhưng chưa trong architecture-master

#### GAP-13: EventTypeRepository (compile-time)
- EventTypeId phải được resolve tại compile time (APT)
- Hiện tại EventTypeId.of() là runtime string
- Cần: @EventTypeDefinition annotation + APT generation

#### GAP-14: DeployableUnit (spec §13.2)
- Package/unpackage SBB + RA + Profile + Event definitions
- Trong micro-jainslee: Maven module = DeployableUnit
- Cần: annotation-based manifest thay XML deployment descriptor

#### GAP-15: ConcurrencyControl (spec §6.3.4)
- SBB ConcurrencyControl: TRANSACTION, EVENT_TYPE_INDEPENDENT, EVENT_TYPE
- micro-jainslee dùng ReentrantLock → map như thế nào sang 3 modes này?
- Chưa có plan cụ thể

#### GAP-16: Jakarta EE namespace strategy (toàn bộ project)
- Quyết định: core dùng com.microjainslee.* (không phụ thuộc javax.* hay jakarta.*)
- Adapter-quarkus: dùng jakarta.* (CDI, injection, etc.) → ✅ đã quyết định
- JAIN-SIP vẫn dùng javax.sip.* → giữ nguyên trong RA layer
- Cần document rõ: mỗi module được phép dùng namespace nào

#### GAP-17: Error handling & Rollback strategy
- JAIN-SLEE §5.14: nếu SBB throw exception → rollback activity
- micro-jainslee: rollback strategy chưa được define rõ
- Cần: ErrorHandlingPolicy interface + default impl

#### GAP-18: SBB Priority (spec §7.2.2)
- Event delivery priority: SBB có priority 0-9
- EventRouter phải sort attached SBBs by priority trước khi dispatch
- Hiện tại EventRouter không có priority sorting

#### GAP-19: InitialEventSelector (spec §7.1)
- IES: quyết định SBB nào là "root" xử lý event mới (không có AC trước)
- InitialEventSelectorPort + InitialEventSelectorMethod
- Chưa có gì

#### GAP-20: quarkus pom.xml + build pipeline chưa có
- Chi tiết pom.xml cho tất cả modules chưa được viết
- Build profile: native vs JVM mode
- Test profile: unit + integration + native-test





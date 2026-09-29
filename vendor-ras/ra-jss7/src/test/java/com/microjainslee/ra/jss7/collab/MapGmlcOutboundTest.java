/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.jss7.collab;

import com.microjainslee.ra.jss7.Ss7Address;
import com.microjainslee.ra.jss7.cluster.StickyRaCommandRouter;
import com.microjainslee.ra.jss7.command.Ss7Command;

import org.junit.Test;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContext;
import org.restcomm.protocols.ss7.map.api.MAPApplicationContextName;
import org.restcomm.protocols.ss7.map.api.MAPDialog;
import org.restcomm.protocols.ss7.map.api.MAPException;
import org.restcomm.protocols.ss7.map.api.MAPParameterFactory;
import org.restcomm.protocols.ss7.map.api.MAPProvider;
import org.restcomm.protocols.ss7.map.api.service.callhandling.MAPDialogCallHandling;
import org.restcomm.protocols.ss7.map.api.service.callhandling.MAPServiceCallHandling;
import org.restcomm.protocols.ss7.map.api.service.lsm.LCSPrivacyCheck;
import org.restcomm.protocols.ss7.map.api.service.lsm.MAPDialogLsm;
import org.restcomm.protocols.ss7.map.api.service.lsm.MAPServiceLsm;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPDialogMobility;
import org.restcomm.protocols.ss7.map.api.service.mobility.MAPServiceMobility;
import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.restcomm.protocols.ss7.map.api.service.lsm.Area;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaDefinition;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaEventInfo;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaIdentification;
import org.restcomm.protocols.ss7.map.api.service.lsm.AreaType;
import org.restcomm.protocols.ss7.map.api.service.lsm.DeferredLocationEventType;
import org.restcomm.protocols.ss7.map.api.service.lsm.LocationEstimateType;
import org.restcomm.protocols.ss7.map.api.service.lsm.LocationType;
import org.restcomm.protocols.ss7.map.api.service.lsm.OccurrenceInfo;
import org.restcomm.protocols.ss7.map.api.service.lsm.PeriodicLDRInfo;
import org.restcomm.protocols.ss7.map.api.service.lsm.ReportingOptionMilliseconds;
import org.restcomm.protocols.ss7.map.api.service.mobility.subscriberInformation.RequestedNodes;
import org.restcomm.protocols.ss7.map.api.service.lsm.SupportedGADShapes;
import org.restcomm.protocols.ss7.map.service.mobility.subscriberInformation.RequestedInfoImpl;
import org.restcomm.protocols.ss7.map.api.primitives.GSNAddress;
import org.restcomm.protocols.ss7.map.api.primitives.GSNAddressAddressType;
import org.restcomm.protocols.ss7.map.primitives.GSNAddressImpl;
import org.restcomm.protocols.ss7.map.service.lsm.AreaDefinitionImpl;
import org.restcomm.protocols.ss7.map.service.lsm.AreaEventInfoImpl;
import org.restcomm.protocols.ss7.map.service.lsm.AreaIdentificationImpl;
import org.restcomm.protocols.ss7.map.service.lsm.AreaImpl;
import org.restcomm.protocols.ss7.map.service.lsm.DeferredLocationEventTypeImpl;
import org.restcomm.protocols.ss7.map.service.lsm.LocationTypeImpl;
import org.restcomm.protocols.ss7.map.service.lsm.PeriodicLDRInfoImpl;
import org.restcomm.protocols.ss7.map.service.lsm.SupportedGADShapesImpl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MapGmlcOutboundTest {

    private static final long LOCAL_ID = 8801L;
    private static final Ss7Address HLR = Ss7Address.of("251900000001", 6);
    private static final Ss7Address GMLC = Ss7Address.of("251900000002", 145);

    @Test
    public void commandsAreSerializableAndRouteAsDialogCreating() {
        List<Ss7Command> creating = List.of(
                ati(),
                sri(),
                psi(),
                sriLcs(),
                psl());

        for (Ss7Command command : creating) {
            assertTrue(command instanceof Serializable);
            assertTrue(command.getClass().getSimpleName(),
                    StickyRaCommandRouter.isDialogCreating(command));
        }
        assertFalse(StickyRaCommandRouter.isDialogCreating(slrResponse()));
    }

    @Test
    public void selectsProtocolApplicationContexts() {
        assertContext(ati(), MAPApplicationContextName.anyTimeEnquiryContext);
        assertContext(sri(), MAPApplicationContextName.locationInfoRetrievalContext);
        assertContext(psi(), MAPApplicationContextName.subscriberInfoEnquiryContext);
        assertContext(sriLcs(), MAPApplicationContextName.locationSvcGatewayContext);
        assertContext(psl(), MAPApplicationContextName.locationSvcEnquiryContext);
    }

    @Test
    public void sriV2UsesLocationInfoRetrievalContextVersion2() {
        MAPApplicationContext context = MapGmlcOutbound.applicationContextFor(sriV2());
        assertSame(MAPApplicationContextName.locationInfoRetrievalContext,
                context.getApplicationContextName());
        assertEquals(2, context.getApplicationContextVersion().getVersion());
    }

    @Test
    public void correlatesSentDialogAndForgetsIt() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);

        assertTrue(outbound.send(ati()));
        assertEquals(1, dialog.sendCount.get());
        assertEquals(0, dialog.releaseCount.get());
        assertEquals("corr-ati", outbound.correlate(LOCAL_ID, "fallback"));

        outbound.forget(LOCAL_ID);
        assertEquals("fallback", outbound.correlate(LOCAL_ID, "fallback"));
    }

    @Test
    public void failedSendReleasesExactlyOnceAndCleansCorrelation() {
        DialogStub dialog = new DialogStub(new MAPException("route down"));
        MapGmlcOutbound outbound = outbound(dialog);

        try {
            outbound.send(ati());
            fail("expected failed MAP send");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof MAPException);
        }

        assertEquals(1, dialog.sendCount.get());
        assertEquals(1, dialog.releaseCount.get());
        assertEquals("fallback", outbound.correlate(LOCAL_ID, "fallback"));
    }

    @Test
    public void invalidLcsIdentityFailsClosedAndCleansCreatedDialog() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);
        Ss7Command.MapSendRoutingInfoForLcs invalid =
                new Ss7Command.MapSendRoutingInfoForLcs(
                        "corr-invalid", HLR, GMLC, "251900000002",
                        "636010000000001", "251911000001", 0, null, -1);

        try {
            outbound.send(invalid);
            fail("expected ambiguous identity rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }

        assertEquals(0, dialog.sendCount.get());
        assertEquals(1, dialog.releaseCount.get());
        assertEquals("fallback", outbound.correlate(LOCAL_ID, "fallback"));
    }

    @Test
    public void slrResponseAddsResultAndEndsExistingDialogExactlyOnce() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);

        assertTrue(outbound.send(slrResponse()));

        assertEquals(1, dialog.slrResponseCount.get());
        assertEquals(1, dialog.closeCount.get());
        assertEquals(0, dialog.sendCount.get());
        assertEquals(0, dialog.releaseCount.get());
    }

    @Test
    public void pslCarriesLcsPrivacyCheckForP2AndP3AndOmitsItWithoutPrivacy() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);

        // P1 (allowedWithoutNotification) + P2 (allowedWithNotification)
        assertTrue(outbound.send(pslWithPrivacy("allowedWithoutNotification", "allowedWithNotification")));
        assertTrue(dialog.pslArgs[15] instanceof LCSPrivacyCheck);

        // P3 (notAllowed) on callSessionUnrelated, no related element
        dialog.pslArgs = null;
        assertTrue(outbound.send(pslWithPrivacy("notAllowed", null)));
        assertTrue(dialog.pslArgs[15] instanceof LCSPrivacyCheck);

        // No privacy profile -> LCS-PrivacyCheck IE omitted entirely
        dialog.pslArgs = null;
        assertTrue(outbound.send(pslWithPrivacy(null, null)));
        assertNull(dialog.pslArgs[15]);
    }

    // ── 4G / 5G request flags and deferred MT-LR IEs ───────────────────────────

    @Test
    public void atiRequestsEpsLocationAndCarriesRequestedNodesOnlyWhenAsked() {
        DialogStub dialog = new DialogStub(null);
        Wired wired = outboundWithProvider(dialog);

        // No requestedNodes -> the IE stays off the wire (legacy behaviour).
        assertTrue(wired.outbound().send(ati(Ss7Command.MapRequestedNodes.NONE)));
        assertTrue(boolAt(wired.provider().requestedInfoArgs(), 8));
        assertNull(wired.provider().requestedInfoArgs()[10]);

        // mme(0) bit set -> the HLR is asked to interrogate the EPS core.
        assertTrue(wired.outbound().send(ati(Ss7Command.MapRequestedNodes.MME)));
        Object nodes = wired.provider().requestedInfoArgs()[10];
        assertTrue(nodes instanceof RequestedNodes);
        assertTrue(((RequestedNodes) nodes).getMme());
        assertFalse(((RequestedNodes) nodes).getSgsn());

        assertTrue(wired.outbound().send(ati(Ss7Command.MapRequestedNodes.MME_SGSN)));
        nodes = wired.provider().requestedInfoArgs()[10];
        assertTrue(((RequestedNodes) nodes).getMme());
        assertTrue(((RequestedNodes) nodes).getSgsn());
    }

    @Test
    public void psiCarriesEpsFlagAndRequestedNodes() {
        DialogStub dialog = new DialogStub(null);
        Wired wired = outboundWithProvider(dialog);
        assertTrue(wired.outbound().send(psi(Ss7Command.MapRequestedNodes.MME)));
        assertTrue(boolAt(wired.provider().requestedInfoArgs(), 8));
        assertTrue(((RequestedNodes) wired.provider().requestedInfoArgs()[10]).getMme());
    }

    @Test
    public void pslBuildsPeriodicMtLrWithHgmlcAndNoAreaEvent() {
        DialogStub dialog = new DialogStub(null);
        assertTrue(outbound(dialog).send(pslMtLr(new Ss7Command.MtLrRequest(
                "periodic", 300, 3600, null, null, null, "10.20.30.40", true, null))));

        LocationType type = (LocationType) dialog.pslArgs[0];
        assertEquals(LocationEstimateType.activateDeferredLocation, type.getLocationEstimateType());
        assertNotNull(type.getDeferredLocationEventType());
        assertTrue(type.getDeferredLocationEventType().getPeriodicLDR());
        assertFalse(type.getDeferredLocationEventType().getMsAvailable());

        assertNull("no area event for a periodic trigger", dialog.pslArgs[16]);
        assertNotNull("H-GMLC is mandatory for a deferred MT-LR", dialog.pslArgs[17]);
        // GSN-Address: first octet = type IPv4 (0) << 6 | length 4, then the address.
        assertArrayEquals(new byte[] {0x04, 10, 20, 30, 40},
                ((GSNAddress) dialog.pslArgs[17]).getData());
        assertTrue("MoLrShortCircuitIndicator", Boolean.TRUE.equals(dialog.pslArgs[18]));
        PeriodicLDRInfo info = (PeriodicLDRInfo) dialog.pslArgs[19];
        assertNotNull(info);
        assertEquals(300, info.getReportingInterval());
        // reportingAmount is a COUNT of reports: 3600 s / 300 s = 12.
        assertEquals(12, info.getReportingAmount());
    }

    @Test
    public void pslBuildsAreaMtLrWithEnteringBitAndAreaIdentification() {
        DialogStub dialog = new DialogStub(null);
        assertTrue(outbound(dialog).send(pslMtLr(new Ss7Command.MtLrRequest(
                "areaEntering", null, null, "locationAreaId", "6301", null,
                "10.20.30.40", false, null))));

        LocationType type = (LocationType) dialog.pslArgs[0];
        assertTrue(type.getDeferredLocationEventType().getEnteringIntoArea());
        assertFalse(type.getDeferredLocationEventType().getPeriodicLDR());

        AreaEventInfo area = (AreaEventInfo) dialog.pslArgs[16];
        assertNotNull(area);
        assertEquals(OccurrenceInfo.oneTimeEvent, area.getOccurrenceInfo());
        AreaDefinition definition = (AreaDefinition) area.getAreaDefinition();
        assertEquals(1, definition.getAreaList().size());
        assertEquals(AreaType.locationAreaId, definition.getAreaList().get(0).getAreaType());
        // "6301" is 2 octets of AreaIdentification, carried verbatim.
        assertEquals(2, definition.getAreaList().get(0).getAreaIdentification().getData().length);
        assertEquals(0x63, definition.getAreaList().get(0).getAreaIdentification().getData()[0] & 0xFF);
        assertEquals(0x01, definition.getAreaList().get(0).getAreaIdentification().getData()[1] & 0xFF);
        assertNull("no periodic info for an area trigger", dialog.pslArgs[19]);
    }

    @Test
    public void pslRejectsPeriodicOutsideTheOmaMlpProductLimit() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);
        try {
            outbound.send(pslMtLr(new Ss7Command.MtLrRequest(
                    "periodic", 5_000_000, 10_000_000, null, null, null, "10.20.30.40", false,
                    null)));
            fail("expected interval x amount limit rejection");
        } catch (IllegalStateException expected) {
            assertTrue(String.valueOf(expected.getCause())
                    .contains("8639999"));
        }
    }

    @Test
    public void pslRejectsDeferredWithoutHgmlc() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);
        try {
            outbound.send(pslMtLr(new Ss7Command.MtLrRequest(
                    "ueAvailable", null, null, null, null, null, null, false, null)));
            fail("expected missing H-GMLC rejection");
        } catch (IllegalStateException expected) {
            assertTrue(String.valueOf(expected.getCause()).contains("H-GMLC"));
        }
    }

    @Test
    public void pslAreaInsideIsMultipleTimeAndArcShapeSetsTheArcBit() {
        DialogStub dialog = new DialogStub(null);
        assertTrue(outbound(dialog).send(pslMtLr(new Ss7Command.MtLrRequest(
                "areaEvent", null, null, "cellGlobalId", "36f01000010001", "inside",
                "10.20.30.40", false, "ellipsoidArc"))));

        LocationType type = (LocationType) dialog.pslArgs[0];
        assertTrue(type.getDeferredLocationEventType().getBeingInsideArea());
        assertEquals(OccurrenceInfo.multipleTimeEvent,
                ((AreaEventInfo) dialog.pslArgs[16]).getOccurrenceInfo());
        SupportedGADShapes shapes = (SupportedGADShapes) dialog.pslArgs[11];
        assertTrue(shapes.getEllipsoidArc());
        assertFalse("arc must not be encoded as polygon", shapes.getPolygon());
    }

    @Test
    public void pslRejectsNonIpHgmlc() {
        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);
        try {
            outbound.send(pslMtLr(new Ss7Command.MtLrRequest(
                    "ueAvailable", null, null, null, null, null, "251900000099", false, null)));
            fail("expected GSN-Address rejection");
        } catch (IllegalStateException expected) {
            assertTrue(String.valueOf(expected.getCause()).contains("IPv4/IPv6"));
        }
    }

    private static boolean boolAt(Object[] args, int index) {
        return Boolean.TRUE.equals(args[index]);
    }

    @Test
    public void pslRejectsUnknownPrivacyActionAndCleansDialog() {        DialogStub dialog = new DialogStub(null);
        MapGmlcOutbound outbound = outbound(dialog);

        try {
            outbound.send(pslWithPrivacy("P2", null));
            fail("expected unknown PrivacyCheckRelatedAction rejection");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }

        assertEquals(0, dialog.sendCount.get());
        assertEquals(1, dialog.releaseCount.get());
    }

    @Test
    public void slrCloseFailureReleasesExistingDialogExactlyOnce() {
        DialogStub dialog = new DialogStub(null, new MAPException("close failed"));
        MapGmlcOutbound outbound = outbound(dialog);

        try {
            outbound.send(slrResponse());
            fail("expected failed MAP close");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getCause() instanceof MAPException);
        }

        assertEquals(1, dialog.slrResponseCount.get());
        assertEquals(1, dialog.closeCount.get());
        assertEquals(1, dialog.releaseCount.get());
    }

    private static void assertContext(Ss7Command command, MAPApplicationContextName expected) {
        MAPApplicationContext context = MapGmlcOutbound.applicationContextFor(command);
        assertSame(expected, context.getApplicationContextName());
        assertEquals(3, context.getApplicationContextVersion().getVersion());
    }

    private static MapGmlcOutbound outbound(DialogStub dialog) {
        return outboundWithProvider(dialog).outbound();
    }

    private record Wired(MapGmlcOutbound outbound, ProviderStub provider) { }

    private static Wired outboundWithProvider(DialogStub dialog) {
        MAPDialog dialogProxy = combinedDialog(dialog);
        ProviderStub provider = new ProviderStub(dialogProxy);
        MAPProvider mapProvider = proxy(MAPProvider.class, provider);
        ParameterFactory sccp = proxy(ParameterFactory.class, MapGmlcOutboundTest::defaultValue);
        return new Wired(new MapGmlcOutbound(mapProvider, sccp), provider);
    }

    private static MAPDialog combinedDialog(DialogStub handler) {
        return (MAPDialog) Proxy.newProxyInstance(
                MapGmlcOutboundTest.class.getClassLoader(),
                new Class<?>[] {
                        MAPDialogMobility.class,
                        MAPDialogCallHandling.class,
                        MAPDialogLsm.class
                },
                handler);
    }

    private static Ss7Command.MapAtiRequest ati() {
        return ati(Ss7Command.MapRequestedNodes.NONE);
    }

    private static Ss7Command.MapAtiRequest ati(Ss7Command.MapRequestedNodes nodes) {
        return new Ss7Command.MapAtiRequest(
                "corr-ati", HLR, GMLC, "251911000001", "251900000002",
                "csDomain", true, true, true, true, true, true, true,
                nodes, 0, null, -1);
    }

    private static Ss7Command.MapSendRoutingInformation sri() {
        return new Ss7Command.MapSendRoutingInformation(
                "corr-sri", HLR, GMLC, "251911000001", 0, null, -1);
    }

    private static Ss7Command.MapSendRoutingInformation sriV2() {
        return new Ss7Command.MapSendRoutingInformation(
                "corr-sri-v2", HLR, GMLC, "251911000001", 0, null, -1, 2);
    }

    private static Ss7Command.MapProvideSubscriberInfo psi() {
        return psi(Ss7Command.MapRequestedNodes.NONE);
    }

    private static Ss7Command.MapProvideSubscriberInfo psi(Ss7Command.MapRequestedNodes nodes) {
        return new Ss7Command.MapProvideSubscriberInfo(
                "corr-psi", Ss7Address.of("251900000003", 7), GMLC,
                "636010000000001", null, "csDomain",
                true, true, true, true, true, true, true,
                nodes, 0, null, -1);
    }

    /** Deferred MT-LR PSL (TS 29.002 §8.5.3): the trigger replaces the current-location estimate. */
    private static Ss7Command.MapProvideSubscriberLocation pslMtLr(Ss7Command.MtLrRequest mtLr) {
        return new Ss7Command.MapProvideSubscriberLocation(
                "corr-psl", Ss7Address.of("251900000003", 8), GMLC,
                "activateDeferredLocation", "251900000002", "valueAddedServices",
                false, "636010000000001", null, null, null,
                "normalPriority", null, null, false,
                "delaytolerant", false, null, 42, null,
                null, null, mtLr, 0, null, -1);
    }

    private static Ss7Command.MapSendRoutingInfoForLcs sriLcs() {
        return new Ss7Command.MapSendRoutingInfoForLcs(
                "corr-sri-lcs", HLR, GMLC, "251900000002",
                null, "251911000001", 0, null, -1);
    }

    private static Ss7Command.MapProvideSubscriberLocation psl() {
        return pslWithPrivacy(null, null);
    }

    /** Privacy actions per TS 23.271: allowedWithoutNotification=P1, allowedWithNotification=P2, notAllowed=P3. */
    private static Ss7Command.MapProvideSubscriberLocation pslWithPrivacy(
            String callSessionUnrelated, String callSessionRelated) {
        return new Ss7Command.MapProvideSubscriberLocation(
                "corr-psl", Ss7Address.of("251900000003", 8), GMLC,
                "currentLocation", "251900000002", "plmnOperatorServices",
                false, "636010000000001", null, null, null,
                "normalPriority", 100, null, false,
                "delaytolerant", false, "bestEffort", 7, 1,
                callSessionUnrelated, callSessionRelated,
                null, 0, null, -1);
    }

    private static Ss7Command.MapSubscriberLocationReportResponse slrResponse() {
        return new Ss7Command.MapSubscriberLocationReportResponse(
                Long.toString(LOCAL_ID), Ss7Address.of("251900000003", 8),
                41L, null, null, 7, 0);
    }

    private static final class DialogStub implements InvocationHandler {
        private final Throwable sendFailure;
        private final Throwable closeFailure;
        private final AtomicInteger sendCount = new AtomicInteger();
        private final AtomicInteger closeCount = new AtomicInteger();
        private final AtomicInteger releaseCount = new AtomicInteger();
        private final AtomicInteger slrResponseCount = new AtomicInteger();
        private volatile Object[] pslArgs;

        private DialogStub(Throwable sendFailure) {
            this(sendFailure, null);
        }

        private DialogStub(Throwable sendFailure, Throwable closeFailure) {
            this.sendFailure = sendFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            return switch (method.getName()) {
                case "getLocalDialogId" -> LOCAL_ID;
                case "send" -> {
                    sendCount.incrementAndGet();
                    if (sendFailure != null) {
                        throw sendFailure;
                    }
                    yield null;
                }
                case "close" -> {
                    closeCount.incrementAndGet();
                    if (closeFailure != null) {
                        throw closeFailure;
                    }
                    yield null;
                }
                case "release" -> {
                    releaseCount.incrementAndGet();
                    yield null;
                }
                case "addSubscriberLocationReportResponse" -> {
                    slrResponseCount.incrementAndGet();
                    yield null;
                }
                case "addProvideSubscriberLocationRequest" -> {
                    pslArgs = args;
                    yield null;
                }
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static final class ProviderStub implements InvocationHandler {
        private final MAPDialog dialog;
        private final MAPServiceMobility mobility;
        private final MAPServiceCallHandling callHandling;
        private final MAPServiceLsm lsm;
        private final MAPParameterFactory parameters;
        private volatile Object[] requestedInfoArgs;

        private ProviderStub(MAPDialog dialog) {
            this.dialog = dialog;
            InvocationHandler service = (proxy, method, args) ->
                    "createNewDialog".equals(method.getName())
                            ? dialog
                            : defaultValue(proxy, method, args);
            mobility = proxy(MAPServiceMobility.class, service);
            callHandling = proxy(MAPServiceCallHandling.class, service);
            lsm = proxy(MAPServiceLsm.class, service);
            parameters = proxy(MAPParameterFactory.class,
                    (proxy, method, args) -> {
                        // Real objects for the IEs the new tests inspect: a stub proxy
                        // would answer false/null for every bit and prove nothing.
                        switch (method.getName()) {
                            case "createRequestedInfo":
                                // Only the arguments matter here (eps flag at [8],
                                // requestedNodes at [10]); the returned IE is unused
                                // by the production code, and jSS7's *constructor*
                                // argument order differs from its factory order.
                                requestedInfoArgs = args;
                                return defaultValue(proxy, method, args);
                            case "createLocationType":
                                return new LocationTypeImpl((LocationEstimateType) args[0],
                                        (DeferredLocationEventType) args[1]);
                            case "createDeferredLocationEventType":
                                return new DeferredLocationEventTypeImpl(bool(args[0]),
                                        bool(args[1]), bool(args[2]), bool(args[3]), bool(args[4]));
                            case "createPeriodicLDRInfo":
                                return new PeriodicLDRInfoImpl((Integer) args[0], (Integer) args[1],
                                        (ReportingOptionMilliseconds) args[2]);
                            case "createAreaEventInfo":
                                return new AreaEventInfoImpl((AreaDefinition) args[0],
                                        (OccurrenceInfo) args[1], (Integer) args[2]);
                            case "createAreaDefinition":
                                return new AreaDefinitionImpl(castAreas(args[0]));
                            case "createArea":
                                return new AreaImpl((AreaType) args[0], (AreaIdentification) args[1]);
                            case "createAreaIdentification":
                                return new AreaIdentificationImpl((byte[]) args[0]);
                            case "createGSNAddress":
                                if (args.length == 2) {
                                    return new GSNAddressImpl((GSNAddressAddressType) args[0],
                                            (byte[]) args[1]);
                                }
                                return new GSNAddressImpl((byte[]) args[0]);
                            case "createSupportedGADShapes":
                                return new SupportedGADShapesImpl(bool(args[0]), bool(args[1]),
                                        bool(args[2]), bool(args[3]), bool(args[4]), bool(args[5]),
                                        bool(args[6]));
                            default:
                                return defaultValue(proxy, method, args);
                        }
                    });
        }

        private static boolean bool(Object value) {
            return Boolean.TRUE.equals(value);
        }

        @SuppressWarnings("unchecked")
        private static ArrayList<Area> castAreas(Object value) {
            return (ArrayList<Area>) value;
        }

        private Object[] requestedInfoArgs() {
            return requestedInfoArgs;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getMAPDialog" -> dialog;
                case "getMAPServiceMobility" -> mobility;
                case "getMAPServiceCallHandling" -> callHandling;
                case "getMAPServiceLsm" -> lsm;
                case "getMAPParameterFactory" -> parameters;
                default -> defaultValue(proxy, method, args);
            };
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                MapGmlcOutboundTest.class.getClassLoader(),
                new Class<?>[] {type},
                handler));
    }

    private static Object defaultValue(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "stub:" + method.getDeclaringClass().getSimpleName();
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> defaultForType(method.getReturnType());
        };
    }

    private static Object defaultForType(Class<?> type) {
        if (type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type.isInterface()) {
            return Proxy.newProxyInstance(
                    MapGmlcOutboundTest.class.getClassLoader(),
                    new Class<?>[] {type},
                    MapGmlcOutboundTest::defaultValue);
        }
        return null;
    }
}

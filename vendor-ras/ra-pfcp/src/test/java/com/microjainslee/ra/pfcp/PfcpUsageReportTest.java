package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TS 29.244 usage reporting: Usage Report codec, Create URR thresholds, Query URR. */
class PfcpUsageReportTest {

    @Test
    void usageReportsRoundTripWithUlDlSplitAndTriggerBits() {
        List<PfcpUsageReport> reports = List.of(
                new PfcpUsageReport(1, PfcpIe.UT_PERIO, 100L, 200L, 30),
                new PfcpUsageReport(2, PfcpIe.UT_VOLTH, 0L, 5_000_000_000L, 0),
                new PfcpUsageReport(3, PfcpIe.UT_TIMTH | PfcpIe.UT_QUHTI, 4096L, 0L, 600));

        List<PfcpUsageReport> back = PfcpCodec.decodeUsageReports(
                PfcpCodec.encodeUsageReports(reports));

        assertEquals(reports, back, "reports survive round-trip");
        assertEquals(5_000_000_000L, back.get(1).downlinkVolume(), "u64 DL volume survives");
        assertEquals(PfcpIe.UT_TIMTH | PfcpIe.UT_QUHTI, back.get(2).usageReportTrigger());
    }

    @Test
    void usageReportWireLayoutIsGroupedSrr() {
        byte[] payload = PfcpCodec.encodeUsageReports(List.of(
                new PfcpUsageReport(1, PfcpIe.UT_VOLTH, 1L, 2L, 3)));

        List<PfcpIe> outer = PfcpCodec.decodeIes(payload);
        assertEquals(1, outer.size());
        assertEquals(PfcpIe.USAGE_REPORT_SRR, outer.get(0).type());

        List<PfcpIe> group = PfcpIes.children(outer.get(0));
        assertEquals(4, group.size());
        assertEquals(PfcpIe.URR_ID, group.get(0).type());
        assertEquals(PfcpIe.USAGE_REPORT_TRIGGER, group.get(1).type());
        assertEquals(PfcpIe.VOLUME_MEASUREMENT, group.get(2).type());
        assertEquals(PfcpIe.DURATION_MEASUREMENT, group.get(3).type());

        byte[] volume = group.get(2).value();
        assertEquals((byte) (PfcpIe.VM_ULVOL | PfcpIe.VM_DLVOL), volume[0], "ULVOL|DLVOL flags");
        assertEquals(17, volume.length, "flags + UL u64 + DL u64");
    }

    @Test
    void decodeUsageReportsIgnoresForeignIesAndEmpty() {
        PfcpUsageReport report = new PfcpUsageReport(4, PfcpIe.UT_PERIO, 10L, 20L, 5);
        List<PfcpIe> mixed = new ArrayList<>();
        mixed.add(PfcpIes.cause(PfcpIe.CAUSE_REQUEST_ACCEPTED));
        mixed.addAll(PfcpCodec.decodeIes(PfcpCodec.encodeUsageReports(List.of(report))));

        assertEquals(List.of(report), PfcpCodec.decodeUsageReports(PfcpCodec.encodeIes(mixed)));
        assertTrue(PfcpCodec.decodeUsageReports(new byte[0]).isEmpty());
        assertTrue(PfcpCodec.decodeUsageReports(null).isEmpty());
    }

    @Test
    void hasQueryUrrTrueAndFalse() {
        byte[] withQuery = PfcpCodec.encodeIes(List.of(PfcpIes.queryUrr(2)));
        assertTrue(PfcpCodec.hasQueryUrr(withQuery));
        List<PfcpIe> queryChildren = PfcpIes.children(PfcpCodec.decodeIes(withQuery).get(0));
        assertEquals(PfcpIe.URR_ID, queryChildren.get(0).type(), "Query URR carries URR ID");

        byte[] reportsOnly = PfcpCodec.encodeUsageReports(List.of(
                new PfcpUsageReport(1, PfcpIe.UT_PERIO, 1L, 1L, 1)));
        assertFalse(PfcpCodec.hasQueryUrr(reportsOnly));
        assertFalse(PfcpCodec.hasQueryUrr(new byte[0]));
        assertFalse(PfcpCodec.hasQueryUrr(null));
    }

    @Test
    void createUrrThresholdsRoundTrip() {
        PfcpUrr urr = new PfcpUrr(5, PfcpIe.MM_VOLUME | PfcpIe.MM_DURATION,
                PfcpIe.UT_VOLTH | PfcpIe.UT_TIMTH, 2_000_000_000L, 7200);
        assertEquals(urr, PfcpIes.parseUrr(PfcpIes.createUrr(urr)));

        PfcpUrr noThresholds = PfcpIes.parseUrr(PfcpIes.createUrr(new PfcpUrr(6)));
        assertEquals(0, noThresholds.volumeThresholdBytes(), "absent threshold = 0");
        assertEquals(0, noThresholds.timeThresholdSeconds(), "absent threshold = 0");
    }

    @Test
    void urrBackCompatConstructors() {
        PfcpUrr simple = new PfcpUrr(5);
        assertEquals(5, simple.urrId());
        assertEquals(PfcpIe.MM_VOLUME, simple.measurementMethod());
        assertEquals(0, simple.reportingTriggers());
        assertEquals(0, simple.volumeThresholdBytes());
        assertEquals(0, simple.timeThresholdSeconds());

        PfcpUrr legacy = new PfcpUrr(6, PfcpIe.MM_DURATION, PfcpIe.UT_PERIO);
        assertEquals(PfcpIe.MM_DURATION, legacy.measurementMethod());
        assertEquals(PfcpIe.UT_PERIO, legacy.reportingTriggers());
        assertEquals(0, legacy.volumeThresholdBytes());
        assertEquals(0, legacy.timeThresholdSeconds());
    }

    @Test
    void usageReportRejectsNegativeFields() {
        assertThrows(IllegalArgumentException.class, () -> new PfcpUsageReport(-1, 0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PfcpUsageReport(1, -1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PfcpUsageReport(1, 0, -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new PfcpUsageReport(1, 0, 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new PfcpUsageReport(1, 0, 0, 0, -1));
    }
}
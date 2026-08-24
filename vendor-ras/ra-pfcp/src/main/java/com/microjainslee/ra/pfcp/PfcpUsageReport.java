package com.microjainslee.ra.pfcp;

/**
 * One Usage Report (TS 29.244 Usage Report within a Session Report Request):
 * URR ID, fired Reporting Trigger bits ({@link PfcpIe#UT_PERIO},
 * {@link PfcpIe#UT_VOLTH}, {@link PfcpIe#UT_TIMTH}, {@link PfcpIe#UT_QUHTI}),
 * uplink/downlink volume in bytes and the measured duration in seconds.
 */
public record PfcpUsageReport(int urrId, int usageReportTrigger, long uplinkVolume,
        long downlinkVolume, int durationSeconds) {
    public PfcpUsageReport {
        if (urrId < 0) {
            throw new IllegalArgumentException("urrId must be >= 0");
        }
        if (usageReportTrigger < 0) {
            throw new IllegalArgumentException("usageReportTrigger must be >= 0");
        }
        if (uplinkVolume < 0) {
            throw new IllegalArgumentException("uplinkVolume must be >= 0");
        }
        if (downlinkVolume < 0) {
            throw new IllegalArgumentException("downlinkVolume must be >= 0");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("durationSeconds must be >= 0");
        }
    }
}
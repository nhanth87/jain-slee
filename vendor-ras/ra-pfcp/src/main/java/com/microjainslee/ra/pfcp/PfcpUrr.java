package com.microjainslee.ra.pfcp;

/**
 * Usage Reporting Rule (TS 29.244 Create URR). Measurement Method bits are
 * {@link PfcpIe#MM_DURATION} / {@link PfcpIe#MM_VOLUME} / {@link PfcpIe#MM_EVENT};
 * Reporting Trigger bits are {@link PfcpIe#UT_PERIO} / {@link PfcpIe#UT_VOLTH} /
 * {@link PfcpIe#UT_TIMTH} / {@link PfcpIe#UT_QUHTI}. Thresholds of {@code 0}
 * mean the threshold IE is absent on the wire.
 */
public record PfcpUrr(int urrId, int measurementMethod, int reportingTriggers,
        long volumeThresholdBytes, int timeThresholdSeconds) {

    public PfcpUrr(int urrId) {
        this(urrId, PfcpIe.MM_VOLUME, 0, 0, 0);
    }

    /** Back-compat constructor: no thresholds. */
    public PfcpUrr(int urrId, int measurementMethod, int reportingTriggers) {
        this(urrId, measurementMethod, reportingTriggers, 0, 0);
    }
}
package com.microjainslee.ra.pfcp;

/**
 * QoS Enforcement Rule (TS 29.244 Create QER). Gate Status: 1 = UL open,
 * 2 = DL open, 3 = both open.
 */
public record PfcpQer(int qerId, int gateStatus) {
    public PfcpQer(int qerId) {
        this(qerId, 3);
    }
}
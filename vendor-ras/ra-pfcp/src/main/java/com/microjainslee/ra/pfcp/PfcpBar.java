package com.microjainslee.ra.pfcp;

/**
 * Buffering Action Rule (TS 29.244 Create BAR): BAR id plus the downlink-data
 * notification delay applied while buffering.
 */
public record PfcpBar(int barId, int downlinkDataNotificationDelay) {
    public PfcpBar(int barId) {
        this(barId, 0);
    }
}
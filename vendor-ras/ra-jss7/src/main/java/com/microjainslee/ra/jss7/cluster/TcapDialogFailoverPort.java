package com.microjainslee.ra.jss7.cluster;

import java.util.Optional;

/**
 * SPIKE seam for TCAP dialog CONTINUE takeover after RA ownership move.
 * <p>
 * jSS7 j25 (coral-valley) now exposes {@code TCAPProvider.exportDialog} /
 * {@code importDialog} + {@code TcapDialogSnapshot}. This port is the RA-side
 * hook; it is <strong>not</strong> wired until {@code ra-jss7}'s
 * {@code ss7.version} artifact includes those methods (local {@code mvn install}
 * of jSS7 j25 or a published build). Calling the jSS7 API against an older jar
 * would break compile for ota-sim-push / CI.
 * <p>
 * <b>Not production HA:</b> even after wiring, multi-ASP routing, invoke/MAP
 * state, and timer completeness remain open (see ADR 0001).
 */
public interface TcapDialogFailoverPort {

    /**
     * @return empty until jSS7 export/import is wired into this RA
     */
    Optional<Object> exportDialogSnapshot(long localOtid);

    /**
     * @return false until jSS7 import is wired into this RA
     */
    boolean importDialogSnapshot(Object snapshot);

    /**
     * Default no-op port — sticky P1 path only.
     */
    static TcapDialogFailoverPort unsupported() {
        return new TcapDialogFailoverPort() {
            @Override
            public Optional<Object> exportDialogSnapshot(long localOtid) {
                return Optional.empty();
            }

            @Override
            public boolean importDialogSnapshot(Object snapshot) {
                return false;
            }
        };
    }
}

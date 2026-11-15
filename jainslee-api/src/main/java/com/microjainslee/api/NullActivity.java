/*
 * micro-jainslee
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 */
package com.microjainslee.api;

/**
 * JAIN SLEE 1.1 (JSR 240) §7.10 — a Null Activity: an activity owned by the SLEE, not by a
 * resource adaptor, used by applications to raise their own events (an AS pull, an NI push).
 *
 * <p>micro-jainslee mapping: {@link NullActivityFactory#createNullActivity(String)} combines the
 * JSR steps {@code NullActivityFactory.createNullActivity()} +
 * {@code NullActivityContextInterfaceFactory.getActivityContextInterface(activity)} +
 * {@code ActivityContextNamingFacility.bind(aci, name)}; the returned context's
 * {@link ActivityContextInterface#getActivity()} is this object.</p>
 */
public interface NullActivity {

    /**
     * JSR 240 §7.10.1 — end this activity. An {@link ActivityEndedEvent} is delivered to the
     * attached SBBs after every event already fired on the activity; the SLEE then unbinds the
     * activity context's names from the ACNF (§7.8.3) and removes SLEE-created root SBB
     * entities whose attachment count reached zero (§8.5). Ending an ended activity is a no-op.
     */
    void endActivity();
}

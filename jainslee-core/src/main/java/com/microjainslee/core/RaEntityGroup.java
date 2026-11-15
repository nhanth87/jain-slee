/*
 * micro-jainslee
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 */
package com.microjainslee.core;

import com.microjainslee.api.OutboundCommand;
import com.microjainslee.api.RaCommandPort;
import com.microjainslee.api.SessionKeyedCommand;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Several entities of one resource adaptor behind a single link name.
 *
 * <p>JAIN SLEE 1.1 lets a resource adaptor type have many entities, and an SBB binds to one of
 * them through a link name. This class is the link: SBBs keep sending to the link name, and each
 * command is passed to one member entity. Commands of the same session ({@link
 * SessionKeyedCommand}) always reach the same member; commands without a session key are spread
 * round-robin.</p>
 *
 * <p>Why: one entity means one I/O stack (one Vert.x instance, one connection pool per host),
 * and that single pipe — not the CPU — capped HTTP throughput. N entities give N pipes.</p>
 */
public final class RaEntityGroup implements RaCommandPort {

    private final String linkName;
    private final RaCommandPort[] members;
    private final AtomicInteger roundRobin = new AtomicInteger();

    public RaEntityGroup(String linkName, List<RaCommandPort> members) {
        if (members == null || members.isEmpty()) {
            throw new IllegalArgumentException("RA entity group '" + linkName + "' needs at least one member");
        }
        this.linkName = linkName;
        this.members = members.toArray(new RaCommandPort[0]);
    }

    @Override
    public void sendCommand(OutboundCommand command) {
        memberFor(command).sendCommand(command);
    }

    /** The member entity that handles {@code command}. */
    RaCommandPort memberFor(OutboundCommand command) {
        if (members.length == 1) {
            return members[0];
        }
        String key = command instanceof SessionKeyedCommand keyed ? keyed.sessionKey() : null;
        int index = key != null
                ? Math.floorMod(key.hashCode(), members.length)
                : Math.floorMod(roundRobin.getAndIncrement(), members.length);
        return members[index];
    }

    public String linkName() {
        return linkName;
    }

    public int size() {
        return members.length;
    }
}

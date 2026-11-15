/*
 * micro-jainslee
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 */
package com.microjainslee.api;

/**
 * A command that belongs to one session (a dialog, a call, an HTTP exchange).
 *
 * <p>When several entities of the same resource adaptor serve one link name (see
 * {@code MicroSleeContainer.registerRaEntityGroup}), commands with the same session key always
 * go to the same entity, so a session's traffic stays on one connection pool and one owner.</p>
 */
public interface SessionKeyedCommand extends OutboundCommand {

    /** The session this command belongs to; {@code null} means "any entity will do". */
    String sessionKey();
}

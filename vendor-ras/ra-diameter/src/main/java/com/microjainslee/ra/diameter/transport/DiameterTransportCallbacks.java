/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.diameter.transport;

import org.jdiameter.api.Message;

/**
 * Transport callbacks for Diameter peer plane + messages.
 *
 * <p>TCP accept alone is not peer UP — the RA tracks CER/CEA via
 * {@link com.microjainslee.ra.diameter.collab.DiameterPeerTracker}.</p>
 */
public interface DiameterTransportCallbacks {
    void onPeerConnected(String peerId);

    void onPeerDisconnected(String peerId);

    /**
     * @param replyWriter encode+write a Diameter answer on the same channel;
     *                    may be a no-op sink in tests
     */
    void onMessage(String peerId, Message msg, MessageReplyWriter replyWriter);

    /** Writes an encoded Diameter message back on the peer channel. */
    @FunctionalInterface
    interface MessageReplyWriter {
        void write(Message answer);
    }
}

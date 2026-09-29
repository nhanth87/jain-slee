/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ra.jss7.cluster;

import java.io.Serializable;
import java.util.Objects;

import com.microjainslee.cluster.TcapDialogSnapshotPayload.PortableSccpAddress;

import org.restcomm.protocols.ss7.sccp.parameter.ParameterFactory;
import org.restcomm.protocols.ss7.tcap.api.TcapForeignPdu;

/**
 * Wire form of {@link TcapForeignPdu} between cluster nodes (ADR 0007 D11).
 *
 * <p>
 * The TCAP bytes travel untouched; only the SCCP addresses are flattened. No
 * decoded MAP object crosses the cluster, so nothing here depends on the MAP
 * stack version of the two nodes.
 *
 * @param localOtid    DTID of the message — the owner's local dialog id
 * @param sourceNodeId node that received the PDU from the STP
 * @param raName       the RA instance the PDU belongs to
 */
public record ForeignTcapPduPayload(long localOtid, String sourceNodeId, String raName, byte[] data,
        PortableSccpAddress calledParty, PortableSccpAddress callingParty, int sls, int networkId,
        int incomingOpc, String preferredAspName) implements Serializable {

    private static final long serialVersionUID = 1L;

    public ForeignTcapPduPayload {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(calledParty, "calledParty");
        Objects.requireNonNull(callingParty, "callingParty");
    }

    public static ForeignTcapPduPayload of(long localOtid, String sourceNodeId, String raName, TcapForeignPdu pdu) {
        return new ForeignTcapPduPayload(localOtid, sourceNodeId, raName, pdu.data(),
                SccpAddressCodec.toPortable(pdu.calledParty()), SccpAddressCodec.toPortable(pdu.callingParty()),
                pdu.sls(), pdu.networkId(), pdu.incomingOpc(), pdu.preferredAspName());
    }

    public TcapForeignPdu toPdu(ParameterFactory factory) {
        return new TcapForeignPdu(data, SccpAddressCodec.toSccp(calledParty, factory),
                SccpAddressCodec.toSccp(callingParty, factory),
                sls, networkId, incomingOpc, preferredAspName);
    }
}

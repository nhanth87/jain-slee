package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.pfcp.command.PfcpAssociateCommand;
import com.microjainslee.ra.pfcp.event.PfcpAssociationEvent;
import com.microjainslee.ra.pfcp.event.PfcpHeartbeatEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Association/heartbeat/recovery FSM: peer-ready, timeout recovery, recovery-change. */
class PfcpAssociationFsmTest {

    private static final InetSocketAddress UPF = new InetSocketAddress("192.0.2.20", 8805);

    @Test
    void associateCommandFiresAssociationEventOnResponse() throws Exception {
        List<SleeEvent> events = new ArrayList<>();
        List<byte[]> wire = new ArrayList<>();
        PfcpRaEndpoint ra = ra(wire, events);

        assertFalse(ra.peer(UPF).live());
        ra.sendCommand(new PfcpAssociateCommand(UPF));
        assertEquals(1, wire.size());
        PfcpMessage req = PfcpCodec.decode(wire.get(0));
        assertEquals(PfcpMessageType.ASSOCIATION_SETUP_REQUEST, req.type());
        assertEquals(100, req.recoveryTimeStamp());
        assertFalse(ra.peer(UPF).live());

        ra.receive(PfcpCodec.encode(PfcpMessage.associationSetupResponse(req.sequence(),
                PfcpNodeId.of(InetAddress.getByName("192.0.2.20")), 200,
                PfcpIe.CAUSE_REQUEST_ACCEPTED)), UPF);

        assertTrue(ra.peer(UPF).live());
        assertTrue(events.stream().anyMatch(PfcpAssociationEvent.class::isInstance));
        // Association starts the heartbeat loop: its first request already went out.
        assertEquals(2, wire.size());
        assertEquals(PfcpMessageType.HEARTBEAT_REQUEST, PfcpCodec.decode(wire.get(1)).type());
    }

    @Test
    void heartbeatTimeoutMarksPeerDownAndReassociates() throws Exception {
        List<SleeEvent> events = new ArrayList<>();
        List<byte[]> wire = new ArrayList<>();
        PfcpRaEndpoint ra = ra(wire, events);

        ra.sendCommand(new PfcpAssociateCommand(UPF));
        PfcpMessage req = PfcpCodec.decode(wire.get(0));
        ra.receive(PfcpCodec.encode(PfcpMessage.associationSetupResponse(req.sequence(),
                PfcpNodeId.of(InetAddress.getByName("192.0.2.20")), 200,
                PfcpIe.CAUSE_REQUEST_ACCEPTED)), UPF);
        assertTrue(ra.peer(UPF).live());
        int afterAssoc = wire.size(); // assoc + first heartbeat

        ra.heartbeatTimeout(UPF);

        assertFalse(ra.peer(UPF).live());
        assertEquals(afterAssoc + 1, wire.size());
        assertEquals(PfcpMessageType.ASSOCIATION_SETUP_REQUEST,
                PfcpCodec.decode(wire.get(afterAssoc)).type());
        assertTrue(events.stream().anyMatch(e -> e instanceof PfcpHeartbeatEvent hb && !hb.response()));
        assertTrue(events.stream().anyMatch(PfcpAssociationEvent.class::isInstance));
    }

    @Test
    void recoveryTimestampChangeReassociates() throws Exception {
        List<SleeEvent> events = new ArrayList<>();
        List<byte[]> wire = new ArrayList<>();
        PfcpRaEndpoint ra = ra(wire, events);

        ra.sendCommand(new PfcpAssociateCommand(UPF));
        PfcpMessage assocReq = PfcpCodec.decode(wire.get(0));
        ra.receive(PfcpCodec.encode(PfcpMessage.associationSetupResponse(assocReq.sequence(),
                PfcpNodeId.of(InetAddress.getByName("192.0.2.20")), 200,
                PfcpIe.CAUSE_REQUEST_ACCEPTED)), UPF);
        assertTrue(ra.peer(UPF).live());
        int before = wire.size();

        // Peer restarted: heartbeat response carries a newer Recovery Time Stamp.
        PfcpMessage hbReq = PfcpCodec.decode(wire.get(before - 1));
        ra.receive(PfcpCodec.encode(PfcpMessage.heartbeatResponse(hbReq.sequence(), 300)), UPF);

        assertEquals(before + 1, wire.size());
        assertEquals(PfcpMessageType.ASSOCIATION_SETUP_REQUEST,
                PfcpCodec.decode(wire.get(before)).type());
    }

    private static PfcpRaEndpoint ra(List<byte[]> wire, List<SleeEvent> events) throws Exception {
        PfcpRaEndpoint ra = new PfcpRaEndpoint();
        ra.setSink((bytes, peer) -> wire.add(bytes));
        ra.setNodeId(PfcpNodeId.of(InetAddress.getByName("127.0.0.1")));
        ra.setRecoverySeconds(100);
        ra.activate(recording(events));
        return ra;
    }

    private static RaBootstrapPort recording(List<SleeEvent> events) {
        return new RaBootstrapPort() {
            @Override
            public ActivityHandle createActivityHandle(String id) {
                return () -> id;
            }

            @Override
            public void fireEvent(SleeEvent event, ActivityHandle handle, Address address) {
                events.add(event);
            }
        };
    }
}
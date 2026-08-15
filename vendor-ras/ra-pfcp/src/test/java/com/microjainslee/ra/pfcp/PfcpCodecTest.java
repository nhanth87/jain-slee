package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.microjainslee.api.ActivityHandle;
import com.microjainslee.api.Address;
import com.microjainslee.api.RaBootstrapPort;
import com.microjainslee.api.SleeEvent;
import com.microjainslee.ra.pfcp.event.PfcpAssociationEvent;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PfcpCodecTest {

    @Test
    void roundTripAssociation() {
        PfcpMessage msg = PfcpMessage.associationSetupRequest(9);
        PfcpMessage back = PfcpCodec.decode(PfcpCodec.encode(msg));
        assertEquals(PfcpMessageType.ASSOCIATION_SETUP_REQUEST, back.type());
        assertEquals(9, back.sequence());
    }

    @Test
    void listenIsNotLiveUntilAssociationResponse() {
        PfcpRaEndpoint ra = new PfcpRaEndpoint();
        List<SleeEvent> events = new ArrayList<>();
        ra.activate(recording(events));
        InetSocketAddress up = new InetSocketAddress("192.0.2.20", 8805);
        assertTrue(ra.listening());
        assertFalse(ra.peer(up).live());
        ra.receive(PfcpCodec.encode(PfcpMessage.associationSetupResponse(1)), up);
        assertTrue(ra.peer(up).live());
        assertTrue(events.stream().anyMatch(PfcpAssociationEvent.class::isInstance));
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

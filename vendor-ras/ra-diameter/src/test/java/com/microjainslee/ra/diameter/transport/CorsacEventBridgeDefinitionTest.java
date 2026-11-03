package com.microjainslee.ra.diameter.transport;

import com.mobius.software.telco.protocols.diameter.annotations.DiameterCommandDefinition;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Corsac decodes into {@code ...Impl} classes; the command definition is on the interface.
 * Without walking interfaces every inbound application message was dropped.
 */
public class CorsacEventBridgeDefinitionTest {

    @Test
    public void definitionIsFoundThroughTheCommandInterface() {
        DiameterCommandDefinition uar = CorsacEventBridge.definitionOf(
                com.mobius.software.telco.protocols.diameter.impl.commands.cxdx.UserAuthorizationRequestImpl.class);
        assertNotNull("Cx UAR impl must resolve its definition", uar);
        assertEquals(300, uar.commandCode());
        assertEquals(16_777_216L, uar.applicationId());

        DiameterCommandDefinition air = CorsacEventBridge.definitionOf(
                com.mobius.software.telco.protocols.diameter.impl.commands.s6a.AuthenticationInformationRequestImpl.class);
        assertNotNull(air);
        assertEquals(318, air.commandCode());
        assertEquals(16_777_251L, air.applicationId());

        DiameterCommandDefinition aar = CorsacEventBridge.definitionOf(
                com.mobius.software.telco.protocols.diameter.impl.commands.rx.AARequestImpl.class);
        assertNotNull(aar);
        assertEquals(265, aar.commandCode());
    }

    @Test
    public void unrelatedClassHasNoDefinition() {
        assertNull(CorsacEventBridge.definitionOf(String.class));
        assertNull(CorsacEventBridge.definitionOf(null));
    }
}

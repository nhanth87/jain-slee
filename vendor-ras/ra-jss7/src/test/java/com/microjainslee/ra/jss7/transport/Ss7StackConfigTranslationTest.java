/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */
package com.microjainslee.ra.jss7.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import com.microjainslee.ra.jss7.Ss7RaConfig;

import org.restcomm.protocols.ss7.config.Ss7Config;

/**
 * Flat {@link Ss7RaConfig} → neutral {@link Ss7Config} translation round-trip.
 * DESIGN §10.2/P4: the single AS traffic mode must be driven by
 * {@link Ss7RaConfig#defaultTrafficMode()}, never hardcoded.
 */
public class Ss7StackConfigTranslationTest {

    @Test
    public void defaultTrafficModeIsLoadshare() {
        Ss7Config cfg = Ss7Stack.toSs7Config(new Ss7RaConfig());
        assertNotNull(cfg.m3ua());
        assertEquals(1, cfg.m3ua().as().size());
        assertEquals("loadshare", cfg.m3ua().as().get(0).mode());
    }

    @Test
    public void overrideTrafficModeFlowsIntoAsCreation() {
        Ss7Config cfg = Ss7Stack.toSs7Config(
                new Ss7RaConfig().defaultTrafficMode(Ss7RaConfig.TRAFFIC_MODE_OVERRIDE));
        assertEquals("override", cfg.m3ua().as().get(0).mode());
    }

    @Test
    public void asTopologyUnchangedByTrafficMode() {
        Ss7RaConfig flat = new Ss7RaConfig().defaultTrafficMode("override");
        Ss7Config cfg = Ss7Stack.toSs7Config(flat);
        Ss7Config.As as = cfg.m3ua().as().get(0);
        assertEquals("AS1", as.name());
        assertEquals(Long.valueOf(flat.routingContext()), as.routingContext());
        assertEquals(Long.valueOf(flat.networkAppearance()), as.networkAppearance());
        assertEquals(flat.ipspClient() ? "ipsp" : "as", as.functionality());
    }

    /**
     * Every flat-config RA failed activation on jSS7 9.2.11-j25 ("unsupported gtType 'null'", then an
     * NPE on the numbering plan): a programmatic Ss7Config skips Ss7ConfigLoader's GT defaults.
     */
    @Test
    public void everyGlobalTitleRuleNamesItsType() {
        Ss7Config cfg = Ss7Stack.toSs7Config(new Ss7RaConfig());
        for (Ss7Config.Rule rule : cfg.sccp().routing()) {
            for (Ss7Config.Addr a : new Ss7Config.Addr[] {rule.match(), rule.to(), rule.backup()}) {
                if (a != null && a.gt() != null) {
                    assertEquals("GT0100", a.gtType());
                    assertNotNull("encoding", a.encoding());
                    assertNotNull("numbering plan", a.plan());
                    assertNotNull("nature of address", a.nature());
                }
            }
        }
    }
}
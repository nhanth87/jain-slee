package com.microjainslee.ra.pfcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

class PfcpCodecRulesTest {

    private static PfcpRule rule(long seid, String pdr, int localTeid, int remoteTeid) throws Exception {
        PfcpFteid local = new PfcpFteid(localTeid, InetAddress.getByName("192.0.2.1"), 1);
        PfcpFteid remote = new PfcpFteid(remoteTeid, InetAddress.getByName("192.0.2.2"), 2);
        return new PfcpRule(seid, pdr, local, remote, "qer-" + pdr, true);
    }

    @Test
    void rulesAndUeRoundTrip() throws Exception {
        List<PfcpRule> rules = List.of(rule(9L, "pdr-1", 0x80000001, 7));
        PfcpRules back = PfcpCodec.decodeRules(
                PfcpCodec.encodeRules(rules, InetAddress.getByName("10.64.0.2")));

        assertEquals(1, back.rules().size(), "rules survive");
        PfcpRule r = back.rules().get(0);
        assertEquals(9L, r.seid());
        assertEquals("pdr-1", r.pdrId());
        assertEquals(0x80000001, r.local().teid(), "TEID bits preserved as raw int");
        assertEquals(7, r.remote().teid());
        assertEquals("192.0.2.1", r.local().address().getHostAddress());
        assertEquals("192.0.2.2", r.remote().address().getHostAddress());
        assertEquals("qer-pdr-1", r.qerId());
        assertTrue(r.uplink());
        assertNotNull(back.ueIpv4());
        assertEquals("10.64.0.2", back.ueIpv4().getHostAddress());
    }

    @Test
    void emptyRulesRoundTrip() {
        PfcpRules back = PfcpCodec.decodeRules(PfcpCodec.encodeRules(List.of(), null));
        assertTrue(back.rules().isEmpty());
        assertNull(back.ueIpv4());
    }

    @Test
    void unknownVersionYieldsEmpty() {
        assertTrue(PfcpCodec.decodeRules(new byte[] {99, 0}).rules().isEmpty());
        assertTrue(PfcpCodec.decodeRules(null).rules().isEmpty());
    }

    @Test
    void rulesCarryUrrThresholdsRoundTrip() throws Exception {
        PfcpUrr urr = new PfcpUrr(7, PfcpIe.MM_VOLUME | PfcpIe.MM_DURATION,
                PfcpIe.UT_PERIO | PfcpIe.UT_VOLTH, 1_000_000L, 3600);
        PfcpRule r = new PfcpRule(9L, "pdr-1",
                new PfcpFteid(1, InetAddress.getByName("192.0.2.1"), 1),
                new PfcpFteid(2, InetAddress.getByName("192.0.2.2"), 2),
                "qer-pdr-1", true, urr);

        PfcpRules back = PfcpCodec.decodeRules(PfcpCodec.encodeRules(List.of(r), null));

        assertEquals(1, back.rules().size());
        assertEquals(r, back.rules().get(0));
        assertEquals(urr, back.rules().get(0).urr(), "URR with thresholds survives");
        assertEquals(1_000_000L, back.rules().get(0).urr().volumeThresholdBytes());
        assertEquals(3600, back.rules().get(0).urr().timeThresholdSeconds());
    }

    @Test
    void legacyRuleWithoutUrrStillRoundTrips() throws Exception {
        List<PfcpRule> rules = List.of(rule(9L, "pdr-1", 1, 2));
        PfcpRules back = PfcpCodec.decodeRules(PfcpCodec.encodeRules(rules, null));
        assertEquals(rules, back.rules());
        assertNull(back.rules().get(0).urr());
    }
}
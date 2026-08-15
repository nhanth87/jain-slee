package com.microjainslee.ra.freeswitch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FreeSwitchRaEndpointTest {

    @Test
    void raNameIsFreeswitchRa() {
        assertEquals("freeswitch-ra", new FreeSwitchRaEndpoint().getRaName());
    }

    @Test
    void apiSyncSoftFailsWhenFsDown() {
        FreeSwitchResourceAdaptor ra = new FreeSwitchResourceAdaptor();
        FreeSwitchRaConfig cfg = new FreeSwitchRaConfig();
        cfg.setHost("127.0.0.1");
        cfg.setPort(1); // nothing listening
        cfg.setConnectTimeoutMs(200);
        cfg.setReadTimeoutMs(200);
        cfg.setAutoSubscribe(false);
        ra.setConfig(cfg);
        String body = ra.apiSync("status");
        assertTrue(body.startsWith("-ERR ESL unavailable"));
        assertFalse(ra.live());
    }

    @Test
    void activityIdsFollowGrillC() {
        assertEquals("fs-link", FreeSwitchResourceAdaptor.LINK_ACTIVITY_ID);
        assertEquals("fs-ch-abc-123", FreeSwitchResourceAdaptor.channelActivityId("abc-123"));
        assertTrue(FreeSwitchResourceAdaptor.isChannelTerminal("CHANNEL_HANGUP"));
        assertTrue(FreeSwitchResourceAdaptor.isChannelTerminal("CHANNEL_DESTROY"));
        assertFalse(FreeSwitchResourceAdaptor.isChannelTerminal("CHANNEL_CREATE"));
    }
}

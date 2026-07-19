package com.example.helloworld.quarkus.profile;

import com.example.helloworld.quarkus.sbbs.HelloWorldSbb;
import com.example.helloworld.quarkus.telemetry.EndpointHitStore;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.ProfileAttachment;
import com.microjainslee.ra.httpserver.events.HttpWebRequestEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Profile CMP contract for HelloWorld:
 * <ol>
 *   <li>SBB entity A writes {@code SessionProfile} CMP ({@code checkpointJson.hits});</li>
 *   <li>A is passivated / discarded (SBB "dies");</li>
 *   <li>Brand-new SBB entity B for the same HTTP session id reloads the
 *       <em>same</em> profile row from {@link com.microjainslee.api.ProfileFacility}
 *       — hits continue, proving recovery is from Profile CMP, not SBB heap.</li>
 * </ol>
 *
 * <p>Infinispan durability is orthogonal (write-behind behind the facility).
 * This test locks the hot-store survival path that production depends on
 * between flushes and across entity recycling.</p>
 */
class HelloWorldProfileRecoveryTest {

    private MicroSleeContainer container;
    private HelloWorldProfileManager profiles;

    @BeforeEach
    void setUp() {
        container = new MicroSleeContainer();
        container.start();
        profiles = new HelloWorldProfileManager(container.getProfileFacility());
        profiles.provisionTables();
    }

    @AfterEach
    void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    void sessionProfileCmpSurvivesSbbDeathAndIsReloadedByNewEntity() throws Exception {
        final String sessionId = "sess-recovery-1";

        EndpointHitStore hits = new EndpointHitStore();
        ProfileAttachment attachment = new ProfileAttachment(container.getProfileFacility());

        // ── Entity A: first hit ─────────────────────────────────────────
        HelloWorldSbb entityA = new HelloWorldSbb(null, profiles, hits, attachment);
        entityA.sbbActivate();
        entityA.onEvent(getRequest(sessionId, "/"), null);

        SessionProfile afterA = profiles.getSession(sessionId).orElse(null);
        assertNotNull(afterA, "SessionProfile CMP row must exist after first request");
        assertEquals(sessionId, afterA.getProfileKey());
        assertTrue(afterA.getCheckpointJson().contains("\"hits\":1"),
                "checkpoint after A: " + afterA.getCheckpointJson());

        // SBB dies — heap of entityA is gone; only ProfileFacility retains state.
        entityA.sbbPassivate();
        entityA = null;

        SessionProfile stillThere = profiles.getSession(sessionId).orElse(null);
        assertNotNull(stillThere, "Profile row must survive SBB passivate");
        assertTrue(stillThere.getCheckpointJson().contains("\"hits\":1"));
        assertTrue(stillThere.getCheckpointJson().contains("\"passivateTs\":"),
                "passivate should rewrite checkpoint with passivateTs, got: "
                        + stillThere.getCheckpointJson());
        assertEquals("/", stillThere.getLastActivityId());

        // ── Entity B: new SBB instance, same session id ─────────────────
        HelloWorldSbb entityB = new HelloWorldSbb(null, profiles, hits, attachment);
        entityB.sbbActivate();
        entityB.onEvent(getRequest(sessionId, "/"), null);

        SessionProfile afterB = profiles.getSession(sessionId).orElseThrow();
        assertTrue(afterB.getCheckpointJson().contains("\"hits\":2"),
                "new SBB must reload Profile CMP and bump hits — got: "
                        + afterB.getCheckpointJson());
    }

    private static HttpWebRequestEvent getRequest(String sessionId, String path) {
        return new HttpWebRequestEvent(
                sessionId, "GET", path,
                Map.of("User-Agent", "HelloWorldProfileRecoveryTest"),
                null);
    }
}

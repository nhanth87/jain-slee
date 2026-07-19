/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-sip)
 *
 * Phase 3 — SIP dialog checkpoint & recovery test (Goal G3).
 */

package com.example.sipgateway.profile;

import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.core.MicroSleeContainer;
import com.microjainslee.core.ProfileAttachment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 — SIP dialog checkpoint recovery test (Goal G3).
 *
 * <p>Simulates the core G3 scenario from PROFILE-IMPLEMENTATION-PLAN.md:
 * <blockquote>
 * "SBB chết → hot reload từ profile" — SBB entity dies, a new entity resumes
 * the same SIP dialog state from the session checkpoint written before death.
 * </blockquote>
 *
 * <h3>Sequence</h3>
 * <pre>
 *  SBB-entity-A handles SIP INVITE:
 *    → creates SipDialogSessionProfile[callId]
 *    → sets dialogState="EARLY"
 *    → calls ProfileAttachment.checkpoint(callId, jsonSnapshot)
 *
 *  SBB-entity-A is "killed" (simulated by letting go of all references):
 *    → SBB heap fields gone (callId, localState, etc.)
 *    → ProfileFacility row SURVIVES (in-memory / durable)
 *
 *  New SBB-entity-B activates for the same callId:
 *    → calls ProfileAttachment.restoreCheckpoint(callId) → Optional.of(json)
 *    → parses json, reconstructs dialogState = "EARLY"
 *    → continues the SIP transaction state machine without JWT re-auth
 * </pre>
 *
 * <p>This test does NOT break the SIP transaction state machine because it
 * only tests the Profile/checkpoint layer in isolation — no real SIP RA is
 * involved. SIP protocol state is represented as a String field for simplicity.
 */
public class SipCheckpointRecoveryTest {

    private MicroSleeContainer container;
    private ProfileFacility facility;
    private ProfileAttachment attachment;

    @BeforeEach
    public void setUp() {
        container = new MicroSleeContainer();
        container.start();
        facility = container.getProfileFacility();
        facility.createProfileTable(SipDialogSessionProfile.TABLE_NAME);
        // No AlarmFacility needed for this test — attachment warns instead of alarming.
        attachment = new ProfileAttachment(facility);
    }

    @AfterEach
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    // -----------------------------------------------------------------------
    // G3 scenario: kill entity → restore
    // -----------------------------------------------------------------------

    @Test
    public void sipDialogCheckpointSurvivesEntityDeath() throws Exception {
        final String callId = "call-abc-1234@sip.example.com";
        final String fromUri = "sip:alice@example.com";
        final String toUri = "sip:bob@example.com";

        // ---- Entity A: handles INVITE, checkpoints ----
        {
            // Simulate SBB-entity-A: create profile row for this dialog.
            ProfileLocalObject plo = facility.createProfile(
                    SipDialogSessionProfile.TABLE_NAME, callId, SipDialogSessionProfile.class);
            SipDialogSessionProfile dialog = (SipDialogSessionProfile) plo.getProfile();
            dialog.setCallId(callId);
            dialog.setFromUri(fromUri);
            dialog.setToUri(toUri);
            dialog.setDialogState("EARLY");

            // Build checkpoint JSON (simulating what RegistrationSbb would write).
            String json = buildDialogCheckpoint(callId, "EARLY", fromUri, toUri);
            attachment.checkpoint(SipDialogSessionProfile.TABLE_NAME, callId, json);
        }
        // Entity A goes out of scope — all SBB heap variables are GC'd.
        // But the ProfileFacility row for callId STILL EXISTS.

        // ---- Entity B: activated for same callId ----
        {
            // Simulate SBB-entity-B: restoreCheckpoint.
            Optional<String> restored =
                    attachment.restoreCheckpoint(SipDialogSessionProfile.TABLE_NAME, callId);
            assertTrue(restored.isPresent(),
                    "checkpoint must be recoverable after entity A dies");

            // Parse checkpoint — in a real SBB this would be JSON deserialisation.
            String json = restored.get();
            String recoveredState = parseDialogState(json);
            assertEquals("EARLY", recoveredState,
                    "G3: dialogState must survive entity death");

            // Verify the full profile row is also accessible (Profile CMP path).
            ProfileLocalObject plo = facility.getProfile(
                    new com.microjainslee.api.ProfileID(SipDialogSessionProfile.TABLE_NAME, callId));
            assertNotNull(plo, "profile row must survive entity A death");
            SipDialogSessionProfile dialog = (SipDialogSessionProfile) plo.getProfile();
            assertEquals("EARLY", dialog.getDialogState(),
                    "G3: CMP dialogState field must survive entity death");
            assertEquals(fromUri, dialog.getFromUri(),
                    "G3: fromUri must survive");
            assertEquals(toUri, dialog.getToUri(),
                    "G3: toUri must survive");
        }
    }

    @Test
    public void sipDialogCheckpointStateTransition() throws Exception {
        final String callId = "call-transition-9999@sip.test";

        // Create dialog in CALLING state.
        ProfileLocalObject plo = facility.createProfile(
                SipDialogSessionProfile.TABLE_NAME, callId, SipDialogSessionProfile.class);
        SipDialogSessionProfile dialog = (SipDialogSessionProfile) plo.getProfile();
        dialog.setCallId(callId);
        dialog.setDialogState("CALLING");
        attachment.checkpoint(SipDialogSessionProfile.TABLE_NAME, callId,
                buildDialogCheckpoint(callId, "CALLING", "sip:a@test", "sip:b@test"));

        // Simulate state transition: CALLING → CONFIRMED (200 OK received).
        dialog.setDialogState("CONFIRMED");
        attachment.checkpoint(SipDialogSessionProfile.TABLE_NAME, callId,
                buildDialogCheckpoint(callId, "CONFIRMED", "sip:a@test", "sip:b@test"));

        // Entity dies. New entity restores CONFIRMED state.
        Optional<String> restored = attachment.restoreCheckpoint(
                SipDialogSessionProfile.TABLE_NAME, callId);
        assertTrue(restored.isPresent(), "restored checkpoint must be present");
        assertEquals("CONFIRMED", parseDialogState(restored.get()),
                "G3: last checkpoint must reflect CONFIRMED state");
    }

    @Test
    public void noCheckpointReturnsEmpty() {
        Optional<String> result = attachment.restoreCheckpoint(
                SipDialogSessionProfile.TABLE_NAME, "nonexistent-call-id");
        assertTrue(result.isEmpty(), "restoreCheckpoint for unknown key must return empty");
    }

    @Test
    public void getOrCreateFromDefaultCreatesRowIfAbsent() throws Exception {
        final String callId = "call-new-from-default@sip.test";
        ProfileLocalObject plo = attachment.getOrCreateFromDefault(
                SipDialogSessionProfile.TABLE_NAME, callId, SipDialogSessionProfile.class);
        assertNotNull(plo, "getOrCreateFromDefault must return a non-null PLO");
        assertEquals(SipDialogSessionProfile.TABLE_NAME, plo.getProfileTableName(),
                "new row must be in SipDialogSession table");
    }

    @Test
    public void getOrCreateFromDefaultReturnsExistingRow() throws Exception {
        final String callId = "call-existing-row@sip.test";
        // Pre-create.
        facility.createProfile(SipDialogSessionProfile.TABLE_NAME, callId,
                SipDialogSessionProfile.class);
        // getOrCreateFromDefault must return the existing row, not throw.
        ProfileLocalObject plo = attachment.getOrCreateFromDefault(
                SipDialogSessionProfile.TABLE_NAME, callId, SipDialogSessionProfile.class);
        assertNotNull(plo);
        assertEquals(callId, plo.getProfileID().getProfileName());
    }

    // -----------------------------------------------------------------------
    // Private helpers — minimal JSON helpers to avoid adding a JSON lib dependency
    // -----------------------------------------------------------------------

    private static String buildDialogCheckpoint(String callId, String state,
                                                String fromUri, String toUri) {
        return "{\"callId\":\"" + escapeJson(callId) + "\""
                + ",\"dialogState\":\"" + escapeJson(state) + "\""
                + ",\"fromUri\":\"" + escapeJson(fromUri) + "\""
                + ",\"toUri\":\"" + escapeJson(toUri) + "\""
                + ",\"ts\":" + System.currentTimeMillis()
                + "}";
    }

    /**
     * Extract "dialogState" value from the minimal JSON checkpoint produced by
     * {@link #buildDialogCheckpoint}. Not a general-purpose JSON parser.
     */
    private static String parseDialogState(String json) {
        String key = "\"dialogState\":\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int start = i + key.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}

/*
 * micro-jainslee 1.2.0 — example application (example-quarkus-helloworld-web)
 */

package com.example.helloworld.quarkus.profile;

import com.microjainslee.api.Profile;
import com.microjainslee.api.ProfileAlreadyExistsException;
import com.microjainslee.api.ProfileFacility;
import com.microjainslee.api.ProfileID;
import com.microjainslee.api.ProfileLocalObject;
import com.microjainslee.api.SLEEException;
import com.microjainslee.api.UnrecognizedProfileTableNameException;

import java.util.Optional;

/**
 * Example-local façade over {@link ProfileFacility} — provisions HelloWorld
 * profile tables and exposes typed session/user accessors. Not part of core.
 *
 * <p>Telecom NE stubs ({@code TelecomSubscriber}, {@code HlrElement},
 * {@code MscElement}) live under {@code example-quarkus-sip/.../profile/}.</p>
 */
public final class HelloWorldProfileManager {

    private final ProfileFacility facility;

    public HelloWorldProfileManager(ProfileFacility facility) {
        this.facility = facility;
    }

    /** Idempotent table provisioning for the HelloWorld reference app. */
    public void provisionTables() {
        facility.createProfileTable(SessionProfile.TABLE_NAME);
        facility.createProfileTable(AppUserProfile.TABLE_NAME);
        facility.registerIndex(AppUserProfile.TABLE_NAME, "msisdn");
    }

    public SessionProfile getOrCreateSession(String profileKey)
            throws UnrecognizedProfileTableNameException, ProfileAlreadyExistsException, SLEEException {
        ProfileLocalObject existing = facility.getProfile(
                new ProfileID(SessionProfile.TABLE_NAME, profileKey));
        if (existing != null && existing.getProfile() instanceof SessionProfile session) {
            return session;
        }
        ProfileLocalObject plo = facility.createProfile(
                SessionProfile.TABLE_NAME, profileKey, SessionProfile.class);
        SessionProfile session = (SessionProfile) plo.getProfile();
        session.setProfileKey(profileKey);
        return session;
    }

    public Optional<SessionProfile> getSession(String profileKey) {
        ProfileLocalObject plo = facility.getProfile(new ProfileID(SessionProfile.TABLE_NAME, profileKey));
        if (plo == null) {
            return Optional.empty();
        }
        Profile profile = plo.getProfile();
        return profile instanceof SessionProfile session ? Optional.of(session) : Optional.empty();
    }

    public AppUserProfile getOrCreateAppUser(String userId)
            throws UnrecognizedProfileTableNameException, ProfileAlreadyExistsException, SLEEException {
        ProfileLocalObject existing = facility.getProfile(
                new ProfileID(AppUserProfile.TABLE_NAME, userId));
        if (existing != null && existing.getProfile() instanceof AppUserProfile user) {
            return user;
        }
        ProfileLocalObject plo = facility.createProfile(
                AppUserProfile.TABLE_NAME, userId, AppUserProfile.class);
        AppUserProfile user = (AppUserProfile) plo.getProfile();
        user.setUserId(userId);
        return user;
    }

    public Optional<AppUserProfile> getAppUser(String userId) {
        ProfileLocalObject plo = facility.getProfile(new ProfileID(AppUserProfile.TABLE_NAME, userId));
        if (plo == null) {
            return Optional.empty();
        }
        Profile profile = plo.getProfile();
        return profile instanceof AppUserProfile user ? Optional.of(user) : Optional.empty();
    }
}

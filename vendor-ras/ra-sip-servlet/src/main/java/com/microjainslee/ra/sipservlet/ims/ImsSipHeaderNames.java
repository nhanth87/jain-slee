/*
 * micro-jainslee 1.2.0
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 */

package com.microjainslee.ra.sipservlet.ims;

import java.util.List;

/**
 * 3GPP / IMS SIP private headers (TS 24.229) plus VoLTE/VoNR negotiation
 * headers that must survive SIP edge hops (signaling-only, not SBC) for 4G→5G→6G.
 *
 * <p>Whitelist only — never forward arbitrary headers (open redirect / spoof).</p>
 */
public final class ImsSipHeaderNames {

    /** Identity / charging / access (TS 24.229). */
    public static final String P_ASSERTED_IDENTITY = "P-Asserted-Identity";
    public static final String P_PREFERRED_IDENTITY = "P-Preferred-Identity";
    public static final String P_ACCESS_NETWORK_INFO = "P-Access-Network-Info";
    public static final String P_CHARGING_VECTOR = "P-Charging-Vector";
    public static final String P_CHARGING_FUNCTION_ADDRESSES = "P-Charging-Function-Addresses";
    public static final String P_VISITED_NETWORK_ID = "P-Visited-Network-ID";
    public static final String P_CALLED_PARTY_ID = "P-Called-Party-ID";
    public static final String P_ASSOCIATED_URI = "P-Associated-URI";
    public static final String P_EARLY_MEDIA = "P-Early-Media";

    /** Feature / cellular (5G VoNR and beyond). */
    public static final String FEATURE_CAPS = "Feature-Caps";
    public static final String CELLULAR_NETWORK_INFO = "Cellular-Network-Info";

    /** IMS security agreement (RFC 3329) — AKA / IPsec toward UE. */
    public static final String SECURITY_CLIENT = "Security-Client";
    public static final String SECURITY_SERVER = "Security-Server";
    public static final String SECURITY_VERIFY = "Security-Verify";

    /** Option tags critical for VoLTE (100rel, precondition, sec-agree). */
    public static final String REQUIRE = "Require";
    public static final String SUPPORTED = "Supported";
    public static final String PROXY_REQUIRE = "Proxy-Require";

    /**
     * Ordered whitelist extracted from inbound INVITE and eligible for
     * outbound {@code SendInvite} extension headers.
     */
    public static final List<String> INVITE_PRESERVE = List.of(
            P_ASSERTED_IDENTITY,
            P_PREFERRED_IDENTITY,
            P_ACCESS_NETWORK_INFO,
            P_CHARGING_VECTOR,
            P_CHARGING_FUNCTION_ADDRESSES,
            P_VISITED_NETWORK_ID,
            P_CALLED_PARTY_ID,
            P_ASSOCIATED_URI,
            P_EARLY_MEDIA,
            FEATURE_CAPS,
            CELLULAR_NETWORK_INFO,
            SECURITY_CLIENT,
            SECURITY_SERVER,
            SECURITY_VERIFY,
            REQUIRE,
            SUPPORTED
            // Proxy-Require intentionally omitted — proxy must 420 if unsatisfied (RFC 3261 §16.3)
    );

    private ImsSipHeaderNames() {
    }
}

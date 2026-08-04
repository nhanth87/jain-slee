/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.admin;

/**
 * Declares one RA admin pack for the jainslee-monitor tab hub.
 *
 * <p>Default classpath convention for fragments:</p>
 * <pre>
 *   META-INF/resources/jainslee-admin/{raName}/panel.html
 *   META-INF/resources/jainslee-admin/{raName}/panel.js
 *   META-INF/resources/jainslee-admin/{raName}/panel.css   (optional)
 * </pre>
 *
 * @param raName       RA identity ({@code getRaName()}); also the URL segment
 * @param tabId        short tab key used in {@code ?tab=} and DOM ids
 * @param title        human tab label
 * @param order        sort key (lower first); telemetry/autonomous/AI stay built-in
 * @param fragmentPath classpath resource for the HTML fragment (nullable → convention)
 * @param scriptPath   classpath resource for panel.js (nullable → convention)
 * @param stylePath    classpath resource for panel.css (nullable)
 * @param apiBase      API prefix, typically {@code /api/ra/{raName}}
 */
public record RaAdminManifest(
        String raName,
        String tabId,
        String title,
        int order,
        String fragmentPath,
        String scriptPath,
        String stylePath,
        String apiBase
) {
    public static final String RESOURCE_ROOT = "META-INF/resources/jainslee-admin";

    /** Build a manifest with default fragment/script paths under the convention root. */
    public static RaAdminManifest of(String raName, String tabId, String title, int order) {
        String base = RESOURCE_ROOT + "/" + raName;
        return new RaAdminManifest(
                raName,
                tabId,
                title,
                order,
                base + "/panel.html",
                base + "/panel.js",
                base + "/panel.css",
                "/api/ra/" + raName);
    }

    public String resolvedFragmentPath() {
        if (fragmentPath != null && !fragmentPath.isBlank()) {
            return fragmentPath;
        }
        return RESOURCE_ROOT + "/" + raName + "/panel.html";
    }

    public String resolvedScriptPath() {
        if (scriptPath != null && !scriptPath.isBlank()) {
            return scriptPath;
        }
        return RESOURCE_ROOT + "/" + raName + "/panel.js";
    }

    /** Nullable style path; blank means no stylesheet. */
    public String resolvedStylePath() {
        if (stylePath == null || stylePath.isBlank()) {
            return null;
        }
        return stylePath;
    }
}

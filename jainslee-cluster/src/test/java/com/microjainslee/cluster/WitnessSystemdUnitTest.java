/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Gap (7): the witness systemd unit template is syntactically valid and uses
 * only valid directives/values; when systemd-analyze is available it verifies
 * the file without a parse error.
 */
class WitnessSystemdUnitTest {

    private static final Path DIST = Path.of("dist");
    private static final Path UNIT = DIST.resolve("cluster-witness@.service");

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("unit has valid sections, keys and values")
    void unitHasValidSectionsKeysAndValues() throws Exception {
        assumeTrue(Files.isRegularFile(UNIT), "dist/cluster-witness@.service not present");
        String text = Files.readString(UNIT, StandardCharsets.UTF_8);
        Map<String, Map<String, String>> sections = parse(text);

        assertThat(sections).containsKeys("Unit", "Service", "Install");
        assertThat(sections.get("Unit").get("Description")).isNotBlank();
        assertThat(sections.get("Unit").get("After")).contains("network-online.target");
        assertThat(sections.get("Unit").get("Wants")).contains("network-online.target");

        Map<String, String> service = sections.get("Service");
        assertThat(service.get("Type")).isEqualTo("simple");
        assertThat(service.get("Restart")).isEqualTo("on-failure");
        assertThat(service.get("RestartSec")).matches("\\d+");
        assertThat(service.get("WorkingDirectory")).startsWith("/");
        assertThat(service.get("ExecStart")).startsWith("/").endsWith("run.sh");
        assertThat(service.get("Environment")).contains("JAVA_HOME=");
        assertThat(service.get("EnvironmentFile")).startsWith("-");
        assertThat(sections.get("Install").get("WantedBy")).isEqualTo("multi-user.target");

        // Only known systemd keys; a typo would silently degrade the witness.
        assertThat(service.keySet()).allSatisfy(key ->
                assertThat(key).isIn("Type", "WorkingDirectory", "ExecStart", "Restart",
                        "RestartSec", "EnvironmentFile", "Environment", "NoNewPrivileges"));
    }

    @Test
    @DisplayName("systemd-analyze verify reports no syntax/parse error when available")
    void systemdAnalyzeVerifyHasNoParseError() throws Exception {
        assumeTrue(Files.isRegularFile(UNIT), "dist/cluster-witness@.service not present");
        Path analyzer = findOnPath("systemd-analyze");
        assumeTrue(analyzer != null, "systemd-analyze not on PATH");

        ProcessBuilder pb = new ProcessBuilder(
                analyzer.toString(), "verify", UNIT.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile(tempDir, "systemd-verify-", ".log");
        pb.redirectOutput(out.toFile());
        Process process = pb.start();
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("systemd-analyze verify did not finish within 30s");
        }
        String output = Files.readString(out, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        assertThat(output).doesNotContain("failed to parse")
                .doesNotContain("unknown key")
                .doesNotContain("unknown lvalue")
                .doesNotContain("syntax error")
                .doesNotContain("failed to load unit file");
    }

    private static Map<String, Map<String, String>> parse(String unit) {
        Map<String, Map<String, String>> out = new HashMap<>();
        String section = null;
        for (String raw : unit.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                out.computeIfAbsent(section, k -> new HashMap<>());
                continue;
            }
            int eq = line.indexOf('=');
            assertThat(eq).as("malformed unit line: " + line).isPositive();
            assertThat(section).as("directive outside a section: " + line).isNotNull();
            out.get(section).put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return out;
    }

    private static Path findOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isEmpty()) {
            return null;
        }
        for (String dir : path.split(File.pathSeparator)) {
            Path candidate = Path.of(dir, command);
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}

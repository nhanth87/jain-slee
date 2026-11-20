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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Witness dist packaging (ADR 0007 D12).
 *
 * <p>Pins the JDK 25 guard in {@code dist/run.sh}, the missing-jar failure
 * after a passing guard, and the systemd unit's required sections. The shell
 * guard is exercised with a fake {@code java} executable so the test never
 * depends on a non-25 JDK being installed.
 */
class WitnessDistTest {

    private static final Path DIST = Path.of("dist");
    private static final Path RUN_SH = DIST.resolve("run.sh");
    private static final Path UNIT = DIST.resolve("cluster-witness@.service");
    private static final long PROCESS_TIMEOUT_SECONDS = 30L;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("run.sh refuses a non-25 JAVA_HOME")
    void runShRefusesNon25JavaHome() throws Exception {
        assumeTrue(Files.isRegularFile(RUN_SH), "dist/run.sh not present");

        writeFakeJava(tempDir, "openjdk version \"17.0.0\" 2026-01-01");
        ProcessResult result = runRunSh();

        assertThat(result.exitCode).isNotEqualTo(0);
        assertThat(result.output).contains("JDK 25");
    }

    @Test
    @DisplayName("run.sh passes the JDK 25 guard then fails on the missing cluster-witness.jar")
    void runShWith25JavaFailsOnMissingJar() throws Exception {
        assumeTrue(Files.isRegularFile(RUN_SH), "dist/run.sh not present");

        writeFakeJava(tempDir, "openjdk version \"25.0.0\" 2026-01-01");
        ProcessResult result = runRunSh();

        assertThat(result.exitCode).isNotEqualTo(0);
        assertThat(result.output).contains("cluster-witness.jar");
    }

    @Test
    @DisplayName("witness unit declares [Unit], [Service], [Install] and an ExecStart")
    void witnessUnitHasRequiredSections() throws Exception {
        assumeTrue(Files.isRegularFile(UNIT), "dist/cluster-witness@.service not present");

        String unit = Files.readString(UNIT, StandardCharsets.UTF_8);
        assertThat(unit).contains("[Unit]");
        assertThat(unit).contains("[Service]");
        assertThat(unit).contains("[Install]");
        assertThat(unit).contains("ExecStart=");
    }

    @Test
    @DisplayName("systemd-analyze verify reports no unit-file syntax/parse error")
    void witnessUnitPassesSystemdAnalyzeVerify() throws Exception {
        assumeTrue(Files.isRegularFile(UNIT), "dist/cluster-witness@.service not present");
        Path systemdAnalyze = findOnPath("systemd-analyze");
        assumeTrue(systemdAnalyze != null, "systemd-analyze not on PATH");

        ProcessBuilder pb = new ProcessBuilder(
                systemdAnalyze.toString(), "verify", UNIT.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Path outFile = Files.createTempFile(tempDir, "systemd-analyze-", ".log");
        pb.redirectOutput(outFile.toFile());
        Process process = pb.start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("systemd-analyze verify did not finish within "
                    + PROCESS_TIMEOUT_SECONDS + "s");
        }

        String output = Files.readString(outFile, StandardCharsets.UTF_8);
        String lower = output.toLowerCase(Locale.ROOT);
        // A missing ExecStart target is acceptable (the dist is not installed here);
        // a unit-file syntax/parse failure is not.
        assertThat(lower).doesNotContain("failed to parse");
        assertThat(lower).doesNotContain("unknown key");
        assertThat(lower).doesNotContain("unknown lvalue");
        assertThat(lower).doesNotContain("syntax error");
        assertThat(lower).doesNotContain("failed to load");
    }

    // ----- helpers -----

    private void writeFakeJava(Path root, String versionLine) throws IOException {
        Path bin = Files.createDirectories(root.resolve("bin"));
        Path java = bin.resolve("java");
        Files.writeString(java, "#!/bin/sh\necho '" + versionLine + "'\n", StandardCharsets.UTF_8);
        assertThat(java.toFile().setExecutable(true))
                .as("fake java must be executable")
                .isTrue();
    }

    private ProcessResult runRunSh() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", RUN_SH.toAbsolutePath().toString());
        pb.directory(DIST.toAbsolutePath().toFile());
        pb.environment().put("JAVA_HOME", tempDir.toAbsolutePath().toString());
        pb.redirectErrorStream(true);
        Path outFile = Files.createTempFile(tempDir, "runsh-", ".log");
        pb.redirectOutput(outFile.toFile());

        Process process = pb.start();
        boolean finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new AssertionError("run.sh did not finish within " + PROCESS_TIMEOUT_SECONDS + "s");
        }
        String output = Files.readString(outFile, StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
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

    private record ProcessResult(int exitCode, String output) {
    }
}

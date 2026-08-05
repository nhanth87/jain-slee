/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */
package com.microjainslee.ra.sbi.openapi.gen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI entry for Rel-18 (or any) OpenAPI → {@code catalog.json} generation.
 *
 * <pre>
 * java … SbiCatalogGeneratorMain \
 *   --input tools/sbi-openapi-cache/Rel-18 \
 *   --output src/main/resources/sbi-openapi/catalog.json
 * </pre>
 */
public final class SbiCatalogGeneratorMain {

    private SbiCatalogGeneratorMain() {}

    public static void main(String[] args) throws Exception {
        Path input = null;
        Path output = null;
        boolean continueOnError = false;
        boolean synthesize = true;
        String title = null;
        String specBase = null;

        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--input", "-i" -> input = Path.of(requireValue(args, ++i, a));
                case "--output", "-o" -> output = Path.of(requireValue(args, ++i, a));
                case "--continue-on-error" -> continueOnError = true;
                case "--no-synthesize-options-head" -> synthesize = false;
                case "--title" -> title = requireValue(args, ++i, a);
                case "--spec-base" -> specBase = requireValue(args, ++i, a);
                case "--help", "-h" -> {
                    printHelp();
                    return;
                }
                default -> rest.add(a);
            }
        }
        if (input == null && !rest.isEmpty()) {
            input = Path.of(rest.get(0));
        }
        if (output == null && rest.size() >= 2) {
            output = Path.of(rest.get(1));
        }
        if (input == null || output == null) {
            printHelp();
            System.err.println("ERROR: --input and --output are required");
            System.exit(2);
            return;
        }

        SbiCatalogGenerator gen = new SbiCatalogGenerator(synthesize, continueOnError);
        List<OperationDescriptor> ops = gen.generate(input);
        gen.writeCatalog(ops, output, title, specBase);
        SbiCatalogGenerator.CatalogStats stats = gen.stats(ops);
        System.out.printf(
                "Wrote %s (%d operations, %d APIs)%n",
                output.toAbsolutePath().normalize(),
                stats.operations(),
                stats.apis());
    }

    private static String requireValue(String[] args, int idx, String flag) {
        if (idx >= args.length) {
            throw new IllegalArgumentException("Missing value for " + flag);
        }
        return args[idx];
    }

    private static void printHelp() {
        System.out.println("""
                SbiCatalogGeneratorMain — Rel-18 OpenAPI → catalog.json

                Usage:
                  SbiCatalogGeneratorMain --input <dir> --output <catalog.json> [options]

                Options:
                  --input, -i DIR              Directory of OpenAPI 3 YAML/JSON (walked recursively)
                  --output, -o FILE            Output catalog.json path
                  --continue-on-error          Skip individual bad files (default: fail-fast)
                  --no-synthesize-options-head Do not add synthetic OPTIONS/HEAD entries
                  --title TEXT                 catalog.json title
                  --spec-base TEXT             catalog.json specBase
                  --help, -h                   This help

                Maven:
                  mvn -pl vendor-ras/ra-openapi -Pgenerate-sbi-catalog \\
                    -Dsbi.catalog.input=tools/sbi-openapi-cache/Rel-18 \\
                    -Dsbi.catalog.output=src/main/resources/sbi-openapi/catalog.json \\
                    exec:java

                Fetch Rel-18 sources first:
                  ./tools/fetch-rel18-openapi.sh
                """);
    }
}

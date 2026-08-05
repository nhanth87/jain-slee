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

import com.microjainslee.ra.sbi.openapi.SbiOpenApiCatalog;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SbiCatalogGeneratorTest {

    private static final int SEED_BASELINE_OPS = 4; // fixture YAML ops before synthesis
    private static final int SEED_WITH_SYNTH_MIN = 8; // + OPTIONS×2 paths + HEAD×2 GETs

    @Test
    public void generatesFromFixtureAndRoundTripsThroughCatalog() throws Exception {
        Path fixtureDir = Path.of("src/test/resources/sbi-openapi-fixtures").toAbsolutePath().normalize();
        if (!Files.isDirectory(fixtureDir)) {
            // surefire cwd is module root
            fixtureDir = Path.of("vendor-ras/ra-openapi/src/test/resources/sbi-openapi-fixtures")
                    .toAbsolutePath().normalize();
        }
        assertTrue("fixture dir missing: " + fixtureDir, Files.isDirectory(fixtureDir));

        SbiCatalogGenerator gen = new SbiCatalogGenerator(true, false);
        List<OperationDescriptor> ops = gen.generate(fixtureDir);
        assertTrue("expected ops >= " + SEED_WITH_SYNTH_MIN + " got " + ops.size(),
                ops.size() >= SEED_WITH_SYNTH_MIN);
        assertTrue(ops.stream().anyMatch(o -> "ListItems".equals(o.operationId())));
        assertTrue(ops.stream().anyMatch(o -> "OPTIONS".equals(o.method())));
        assertTrue(ops.stream().anyMatch(o -> "HEAD".equals(o.method())));
        assertTrue(ops.stream().anyMatch(o -> "Nfixture_Mini".equals(o.apiName())));
        assertTrue(ops.stream().anyMatch(o -> o.path().startsWith("/nfixture/v1/")));

        // Deterministic sort
        List<OperationDescriptor> again = gen.generate(fixtureDir);
        assertEquals(ops, again);

        Path out = Files.createTempFile("sbi-catalog-", ".json");
        try {
            gen.writeCatalog(ops, out, "test catalog", "fixture");
            String json = Files.readString(out, StandardCharsets.UTF_8);
            SbiOpenApiCatalog cat = new SbiOpenApiCatalog();
            cat.loadCatalogJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
            // loadCatalogJson does not rebuild routes — size still works
            assertTrue(cat.size() >= SEED_WITH_SYNTH_MIN);
            assertTrue(cat.byOperationId("CreateItem").isPresent());
            assertEquals("Nfixture_Mini", cat.byOperationId("CreateItem").get().apiName());
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    public void generatesFromCheckedInSeedYaml() throws Exception {
        Path seed = Path.of("src/main/resources/sbi-openapi").toAbsolutePath().normalize();
        if (!Files.isDirectory(seed)) {
            seed = Path.of("vendor-ras/ra-openapi/src/main/resources/sbi-openapi")
                    .toAbsolutePath().normalize();
        }
        assertTrue(Files.isDirectory(seed));

        SbiCatalogGenerator gen = new SbiCatalogGenerator(true, false);
        List<OperationDescriptor> ops = gen.generate(seed);
        // seed YAML alone: 5 methods + synth OPTIONS/HEAD extras beyond those already in YAML
        assertTrue("seed baseline ops", ops.size() >= SEED_BASELINE_OPS);
        assertTrue(ops.stream().anyMatch(o -> o.operationId().contains("GetNFInstances")
                || "GetNFInstancesYaml".equals(o.operationId())));

        Path out = Files.createTempFile("sbi-seed-catalog-", ".json");
        try {
            gen.writeCatalog(ops, out, "seed", "seed");
            byte[] bytes = Files.readAllBytes(out);
            SbiOpenApiCatalog cat = new SbiOpenApiCatalog();
            cat.loadCatalogJson(new ByteArrayInputStream(bytes));
            assertTrue(cat.size() >= SEED_BASELINE_OPS);
        } finally {
            Files.deleteIfExists(out);
        }
    }

    @Test
    public void serverPrefixAndApiNameFromTsFilename() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        var servers = root.putArray("servers");
        servers.addObject().put("url", "{apiRoot}/nnrf-nfm/v1");
        assertEquals("/nnrf-nfm/v1", SbiCatalogGenerator.resolveServerPathPrefix(root));
        assertEquals("/nnrf-nfm/v1/nf-instances",
                SbiCatalogGenerator.joinPath("/nnrf-nfm/v1", "/nf-instances"));
        assertEquals("Nnrf_NFManagement",
                SbiCatalogGenerator.resolveApiName(
                        Path.of("TS29510_Nnrf_NFManagement.yaml"), mapper.createObjectNode()));
    }
}

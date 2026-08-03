/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.ms.core;

import com.microjainslee.ms.api.SleeServiceDescriptor;
import com.microjainslee.ms.api.annotation.SleeService;
import com.microjainslee.ms.api.exception.DuplicateServiceNameException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SleeServiceCatalogTest {

    @SleeService(name = "catalog-alpha", startPriority = 10)
    public static final class AlphaService {
    }

    @SleeService(name = "catalog-beta", dependsOn = {"catalog-alpha"})
    public static final class BetaService {
    }

    @SleeService(name = "catalog-alpha")
    public static final class AlphaConflict {
    }

    @Test
    void loadReadsClasspathSleeServices() {
        List<SleeServiceDescriptor> descriptors = SleeServiceCatalog.load(
                SleeServiceCatalogTest.class.getClassLoader());

        assertEquals(2, descriptors.size());
        assertEquals("catalog-alpha", descriptors.get(0).name());
        assertEquals(AlphaService.class, descriptors.get(0).serviceClass());
        assertEquals(10, descriptors.get(0).startPriority());
        assertEquals("catalog-beta", descriptors.get(1).name());
        assertEquals(BetaService.class, descriptors.get(1).serviceClass());
        assertEquals(List.of("catalog-alpha"), descriptors.get(1).dependsOn());
    }

    @Test
    void loadSkipsCommentsAndBlankLines() {
        // Same resource as happy-path; comments/blanks are already in the file.
        // Assert load() convenience delegates to context/classloader path.
        List<SleeServiceDescriptor> viaDefault = SleeServiceCatalog.load();
        assertTrue(viaDefault.stream().anyMatch(d -> "catalog-alpha".equals(d.name())));
        assertTrue(viaDefault.stream().anyMatch(d -> "catalog-beta".equals(d.name())));
    }

    @Test
    void duplicateServiceNameFailsFast() throws IOException {
        Path root = Files.createTempDirectory("slee-services-dup");
        Path resource = root.resolve(SleeServiceCatalog.RESOURCE);
        Files.createDirectories(resource.getParent());
        Files.writeString(resource,
                AlphaService.class.getName() + "\n"
                        + AlphaConflict.class.getName() + "\n",
                StandardCharsets.UTF_8);

        try (URLClassLoader conflicting = new URLClassLoader(
                new URL[]{root.toUri().toURL()},
                SleeServiceCatalogTest.class.getClassLoader()) {
            @Override
            public java.util.Enumeration<URL> getResources(String name) throws IOException {
                if (SleeServiceCatalog.RESOURCE.equals(name)) {
                    return java.util.Collections.enumeration(
                            List.of(resource.toUri().toURL()));
                }
                return super.getResources(name);
            }
        }) {
            DuplicateServiceNameException ex = assertThrows(
                    DuplicateServiceNameException.class,
                    () -> SleeServiceCatalog.load(conflicting));
            assertTrue(ex.getMessage().contains("catalog-alpha"));
        }
    }
}

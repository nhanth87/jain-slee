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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Classpath catalog of {@code @SleeService} types listed in
 * {@code META-INF/jainslee/slee-services} (one FQCN per line).
 *
 * <p>All matching resources on the classloader are read. Blank lines and
 * {@code #} comments are skipped. Duplicate service names fail fast with
 * {@link DuplicateServiceNameException}.
 */
public final class SleeServiceCatalog {

    /** Classpath resource path scanned by {@link #load(ClassLoader)}. */
    public static final String RESOURCE = "META-INF/jainslee/slee-services";

    private SleeServiceCatalog() {
    }

    /**
     * Load descriptors using the current thread context classloader
     * (falling back to this class's loader when the context loader is null).
     */
    public static List<SleeServiceDescriptor> load() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SleeServiceCatalog.class.getClassLoader();
        }
        return load(cl);
    }

    /**
     * Read every {@value #RESOURCE} on {@code classLoader}, resolve each
     * FQCN, require {@link SleeService}, and build descriptors.
     *
     * @param classLoader classloader that owns the catalog resources
     * @return immutable list in discovery order (first resource, then line order)
     * @throws DuplicateServiceNameException if two entries share {@code @SleeService.name()}
     */
    public static List<SleeServiceDescriptor> load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        Map<String, SleeServiceDescriptor> byName = new LinkedHashMap<>();
        Enumeration<URL> resources;
        try {
            resources = classLoader.getResources(RESOURCE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to enumerate " + RESOURCE, e);
        }
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            try (InputStream in = url.openStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                int lineNo = 0;
                while ((line = reader.readLine()) != null) {
                    lineNo++;
                    String fqcn = stripComment(line).trim();
                    if (fqcn.isEmpty()) {
                        continue;
                    }
                    Class<?> type = loadClass(classLoader, fqcn, url, lineNo);
                    if (type.getAnnotation(SleeService.class) == null) {
                        throw new IllegalArgumentException(
                                type.getName() + " lacks @SleeService (from " + url + ":" + lineNo + ")");
                    }
                    SleeServiceDescriptor descriptor = SleeServiceDescriptor.fromAnnotation(type);
                    SleeServiceDescriptor prior = byName.putIfAbsent(descriptor.name(), descriptor);
                    if (prior != null) {
                        throw new DuplicateServiceNameException(
                                "@SleeService name conflict: " + descriptor.name()
                                        + " (" + prior.serviceClass().getName()
                                        + " vs " + type.getName() + ")");
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read " + RESOURCE + " from " + url, e);
            }
        }
        return List.copyOf(new ArrayList<>(byName.values()));
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    private static Class<?> loadClass(ClassLoader classLoader, String fqcn, URL url, int lineNo) {
        try {
            return Class.forName(fqcn, true, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Catalog entry not found: " + fqcn + " (" + url + ":" + lineNo + ")", e);
        }
    }
}

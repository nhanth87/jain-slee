/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.apt;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Writes/merges {@code META-INF/jainslee/slee-services} for types annotated
 * with {@code com.microjainslee.ms.api.annotation.SleeService}.
 *
 * <p>One fully-qualified class name per line. Merges with any existing
 * resource already present under {@link StandardLocation#CLASS_OUTPUT}
 * (manual catalog files + multi-round APT), so apps can keep a hand-written
 * index while APT appends newly discovered services.</p>
 *
 * <p>Uses the annotation by name only — no compile dependency on
 * {@code ms-api}.</p>
 */
@SupportedAnnotationTypes("com.microjainslee.ms.api.annotation.SleeService")
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public class SleeServiceAnnotationProcessor extends AbstractProcessor {

    static final String OUTPUT_RESOURCE = "META-INF/jainslee/slee-services";
    private static final String ANNOTATION_FQCN =
            "com.microjainslee.ms.api.annotation.SleeService";

    /** Accumulates across rounds; written once on {@code processingOver()}. */
    private final Set<String> discovered = new LinkedHashSet<String>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        TypeElement sleeService = processingEnv.getElementUtils().getTypeElement(ANNOTATION_FQCN);
        if (sleeService != null) {
            for (Element e : roundEnv.getElementsAnnotatedWith(sleeService)) {
                if (e.getKind() != ElementKind.CLASS && e.getKind() != ElementKind.INTERFACE) {
                    continue;
                }
                if (!(e instanceof TypeElement)) {
                    continue;
                }
                String fqn = ((TypeElement) e).getQualifiedName().toString();
                if (discovered.add(fqn)) {
                    note("Discovered @SleeService " + fqn);
                }
            }
        }

        if (roundEnv.processingOver()) {
            try {
                writeSleeServicesIndex();
            } catch (IOException ioe) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Failed to write " + OUTPUT_RESOURCE + ": " + ioe.getMessage());
                throw new RuntimeException("Could not write " + OUTPUT_RESOURCE, ioe);
            }
        }
        // Don't claim — other processors may observe @SleeService too.
        return false;
    }

    private void writeSleeServicesIndex() throws IOException {
        if (discovered.isEmpty()) {
            // Nothing new this compilation — leave any manual catalog alone.
            return;
        }

        Filer filer = processingEnv.getFiler();
        LinkedHashSet<String> merged = new LinkedHashSet<String>();

        try {
            FileObject existing = filer.getResource(StandardLocation.CLASS_OUTPUT, "", OUTPUT_RESOURCE);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(existing.openInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    merged.add(trimmed);
                }
            }
        } catch (Exception notFound) {
            // First write in this output location.
        }

        int before = merged.size();
        merged.addAll(discovered);

        FileObject out = filer.createResource(StandardLocation.CLASS_OUTPUT, "", OUTPUT_RESOURCE);
        try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(out.openOutputStream(), StandardCharsets.UTF_8))) {
            writer.write("# micro-jainslee slee-services — generated/merged by SleeServiceAnnotationProcessor");
            writer.newLine();
            for (String fqn : merged) {
                writer.write(fqn);
                writer.newLine();
            }
        }
        note("Wrote " + OUTPUT_RESOURCE + ": " + discovered.size()
                + " discovered, " + (merged.size() - before) + " new, "
                + merged.size() + " total");
    }

    private void note(String message) {
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.NOTE, "[micro-jainslee] " + message);
    }
}

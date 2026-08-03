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

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * In-process unit test for {@link SleeServiceAnnotationProcessor}.
 *
 * <p>Compiles stub {@code @SleeService} sources with a temporary annotation
 * definition (same FQCN as ms-api) and asserts
 * {@code META-INF/jainslee/slee-services} is written and merge-safe.</p>
 */
public class SleeServiceAnnotationProcessorTest {

    private Path workDir;

    @Before
    public void setUp() throws IOException {
        workDir = Files.createTempDirectory("slee-service-apt-test-");
    }

    @After
    public void tearDown() throws IOException {
        if (workDir != null) {
            Files.walk(workDir)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        }
    }

    @Test
    public void processor_writes_slee_services_index() throws IOException {
        Path srcDir = workDir.resolve("src");
        writeStubAnnotation(srcDir);
        writeFile(srcDir.resolve("com/example/Signaling.java"),
                "package com.example;\n"
                        + "import com.microjainslee.ms.api.annotation.SleeService;\n"
                        + "@SleeService(name = \"signaling\")\n"
                        + "public class Signaling { }\n");
        writeFile(srcDir.resolve("com/example/App.java"),
                "package com.example;\n"
                        + "import com.microjainslee.ms.api.annotation.SleeService;\n"
                        + "@SleeService(name = \"app\")\n"
                        + "public class App { }\n");

        Path outDir = workDir.resolve("out");
        Files.createDirectories(outDir);
        compileAll(srcDir, outDir);

        Path index = outDir.resolve(SleeServiceAnnotationProcessor.OUTPUT_RESOURCE);
        assertTrue("missing " + index, Files.isRegularFile(index));
        String body = Files.readString(index, StandardCharsets.UTF_8);
        assertTrue(body.contains("com.example.Signaling"));
        assertTrue(body.contains("com.example.App"));
    }

    @Test
    public void processor_merges_with_existing_manual_catalog() throws IOException {
        Path srcDir = workDir.resolve("src");
        writeStubAnnotation(srcDir);
        writeFile(srcDir.resolve("com/example/NewService.java"),
                "package com.example;\n"
                        + "import com.microjainslee.ms.api.annotation.SleeService;\n"
                        + "@SleeService(name = \"new-svc\")\n"
                        + "public class NewService { }\n");

        Path outDir = workDir.resolve("out");
        Path catalog = outDir.resolve(SleeServiceAnnotationProcessor.OUTPUT_RESOURCE);
        Files.createDirectories(catalog.getParent());
        Files.writeString(catalog,
                "# manual\ncom.example.ManualService\n",
                StandardCharsets.UTF_8);

        compileAll(srcDir, outDir);

        String body = Files.readString(catalog, StandardCharsets.UTF_8);
        assertTrue(body.contains("com.example.ManualService"));
        assertTrue(body.contains("com.example.NewService"));
        // Dedup: ManualService appears once
        long manualCount = Arrays.stream(body.split("\n"))
                .map(String::trim)
                .filter(l -> l.equals("com.example.ManualService"))
                .count();
        assertTrue("ManualService should appear once, was " + manualCount, manualCount == 1L);
    }

    private void compileAll(Path srcDir, Path outDir) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK JavaCompiler unavailable", compiler);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);

        List<JavaFileObject> sources;
        try (var walk = Files.walk(srcDir)) {
            sources = walk
                    .filter(p -> p.toString().endsWith(".java"))
                    .map(p -> {
                        try {
                            return (JavaFileObject) new PathJavaFile(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());
        }

        Iterable<String> options = Arrays.asList(
                "-d", outDir.toString(),
                "-processorpath", processorClasses(),
                "-processor", "com.microjainslee.apt.SleeServiceAnnotationProcessor");
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources);
        boolean ok = task.call();
        fm.close();
        if (!ok) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append("  ").append(d.getKind()).append(": ")
                        .append(d.getMessage(null)).append("\n");
            }
            fail(sb.toString());
        }
    }

    private static void writeStubAnnotation(Path srcDir) throws IOException {
        writeFile(srcDir.resolve(
                        "com/microjainslee/ms/api/annotation/SleeService.java"),
                "package com.microjainslee.ms.api.annotation;\n"
                        + "import java.lang.annotation.*;\n"
                        + "@Retention(RetentionPolicy.RUNTIME)\n"
                        + "@Target(ElementType.TYPE)\n"
                        + "public @interface SleeService {\n"
                        + "    String name();\n"
                        + "}\n");
    }

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    /**
     * Prefer this module's freshly compiled classes so the test exercises
     * the processor under development (not a stale installed jar).
     */
    private static String processorClasses() {
        Path classes = Path.of("target/classes").toAbsolutePath().normalize();
        if (Files.isDirectory(classes)) {
            return classes.toString();
        }
        String userHome = System.getProperty("user.home");
        return userHome + "/.m2/repository/com/microjainslee/jainslee-apt/1.2.0-SNAPSHOT/jainslee-apt-1.2.0-SNAPSHOT.jar";
    }

    private static final class PathJavaFile extends SimpleJavaFileObject {
        private final String cachedSource;

        PathJavaFile(Path path) throws IOException {
            super(path.toUri(), Kind.SOURCE);
            this.cachedSource = Files.readString(path);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return cachedSource;
        }
    }
}

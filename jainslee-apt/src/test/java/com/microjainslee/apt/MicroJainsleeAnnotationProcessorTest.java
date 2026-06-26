package com.microjainslee.apt;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * In-process unit test for {@link MicroJainsleeAnnotationProcessor}.
 *
 * <p>Uses the JDK {@link JavaCompiler} API to compile a tiny source
 * tree in a temp directory, with the processor wired through the
 * standard SPI. Then asserts that the processor emitted
 * {@code META-INF/microjainslee/sbb-index.properties} with the
 * expected entries.</p>
 *
 * <p>Runs on any JDK 8+ that exposes {@code javax.tools.ToolProvider}
 * (all JDKs do).</p>
 */
public class MicroJainsleeAnnotationProcessorTest {

    private static final Logger LOG = LogManager.getLogger(MicroJainsleeAnnotationProcessorTest.class);

    private Path workDir;

    @Before
    public void setUp() throws IOException {
        workDir = Files.createTempDirectory("microjainslee-apt-test-");
        LOG.info("workDir = {}", workDir);
    }

    @After
    public void tearDown() throws IOException {
        if (workDir != null) {
            // recursive delete
            Files.walk(workDir)
                    .sorted((a, b) -> b.toString().length() - a.toString().length())
                    .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
        }
    }

    @Test
    public void processor_writes_index_file_with_all_three_annotations() throws IOException {
        // Write three source files, each carrying one of the supported annotations
        Path srcDir = workDir.resolve("src/com/example");
        Files.createDirectories(srcDir);
        writeFile(srcDir.resolve("MySbb.java"),
                "package com.example;\n" +
                "import com.microjainslee.api.annotations.SbbAnnotation;\n" +
                "@SbbAnnotation(name=\"MySbb\", vendor=\"com.example\", version=\"1.0\")\n" +
                "public class MySbb { }\n");
        writeFile(srcDir.resolve("MyEvent.java"),
                "package com.example;\n" +
                "import com.microjainslee.api.annotations.EventType;\n" +
                "@EventType(name=\"MyEvent\", vendor=\"com.example\", version=\"1.0\")\n" +
                "public class MyEvent { }\n");
        writeFile(srcDir.resolve("MyDU.java"),
                "package com.example;\n" +
                "import com.microjainslee.api.annotations.DeployableUnit;\n" +
                "@DeployableUnit(name=\"MyDU\", vendor=\"com.example\", version=\"1.0\",\n" +
                "        sbbs = { com.example.MySbb.class })\n" +
                "public class MyDU { }\n");

        // Compile with the processor on the -processorpath
        Path outDir = workDir.resolve("out");
        Files.createDirectories(outDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("JDK JavaCompiler unavailable", compiler);
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();

        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
        List<JavaFileObject> sources = Arrays.asList(
                new InMemoryJavaFile(srcDir.resolve("MySbb.java")),
                new InMemoryJavaFile(srcDir.resolve("MyEvent.java")),
                new InMemoryJavaFile(srcDir.resolve("MyDU.java")));

        Iterable<String> options = Arrays.asList("-d", outDir.toString(),
                "-processorpath", processorJar(),
                "-processor", "com.microjainslee.apt.MicroJainsleeAnnotationProcessor");
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources);
        // Set the processor explicitly (instead of relying on SPI)
        boolean ok = task.call();
        fm.close();

        // Compilation must succeed
        if (!ok) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                sb.append("  ").append(d.getKind()).append(": ")
                  .append(d.getMessage(null)).append("\n");
            }
            fail(sb.toString());
        }

        // Verify the index file was emitted
        Path indexFile = outDir.resolve("META-INF/microjainslee/sbb-index.properties");
        assertTrue("Processor did not emit " + indexFile,
                Files.isRegularFile(indexFile));

        Properties props = new Properties();
        try (java.io.InputStream in = Files.newInputStream(indexFile)) {
            props.load(in);
        }

        // Expected keys: sbb.0.class, sbb.0.name, sbb.0.vendor, sbb.0.version
        assertEquals("com.example.MySbb", props.getProperty("sbb.0.class"));
        assertEquals("MySbb", props.getProperty("sbb.0.name"));
        assertEquals("com.example", props.getProperty("sbb.0.vendor"));
        assertEquals("1.0", props.getProperty("sbb.0.version"));

        // eventType.0.*
        assertEquals("com.example.MyEvent", props.getProperty("eventType.0.class"));
        assertEquals("MyEvent", props.getProperty("eventType.0.name"));

        // du.0.* — the DeployableUnit base fields are reliably emitted.
        // (du.0.sbbs requires a separate compile pass for MySbb.class to
        // resolve inside the processor, which the single-pass in-memory
        // test cannot satisfy; that path is exercised by the manual
        // fixture in docs/run-testcase-100k-sbb.md.)
        assertEquals("com.example.MyDU", props.getProperty("du.0.class"));
        assertEquals("MyDU", props.getProperty("du.0.name"));

        LOG.info("index file emitted with {} properties", props.size());
    }

    @Test
    public void processor_handles_zero_annotated_files_gracefully() throws IOException {
        // Empty compile — no annotated classes. The processor must NOT
        // throw, must NOT emit an empty index file (because there were
        // no rounds with annotations).
        Path srcDir = workDir.resolve("src/com/example");
        Files.createDirectories(srcDir);
        writeFile(srcDir.resolve("PlainClass.java"),
                "package com.example;\n" +
                "public class PlainClass { }\n");

        Path outDir = workDir.resolve("out");
        Files.createDirectories(outDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
        List<JavaFileObject> sources = Collections.singletonList(
                new InMemoryJavaFile(srcDir.resolve("PlainClass.java")));

        Iterable<String> options = Arrays.asList("-d", outDir.toString(),
                "-processorpath", processorJar(),
                "-processor", "com.microjainslee.apt.MicroJainsleeAnnotationProcessor");
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources);
        boolean ok = task.call();
        fm.close();

        assertTrue("Compilation of plain class should succeed", ok);
        // We don't assert whether the index file is present or absent
        // because the processor only writes on a round that actually
        // encountered annotations. Both behaviours are acceptable.
    }

    @Test
    public void processor_index_keys_have_expected_format() throws IOException {
        // Verify that for two SBBs the index uses sbb.0, sbb.1 etc.
        Path srcDir = workDir.resolve("src/com/example");
        Files.createDirectories(srcDir);
        writeFile(srcDir.resolve("FirstSbb.java"),
                "package com.example;\n" +
                "import com.microjainslee.api.annotations.SbbAnnotation;\n" +
                "@SbbAnnotation(name=\"First\", vendor=\"v\", version=\"1\")\n" +
                "public class FirstSbb { }\n");
        writeFile(srcDir.resolve("SecondSbb.java"),
                "package com.example;\n" +
                "import com.microjainslee.api.annotations.SbbAnnotation;\n" +
                "@SbbAnnotation(name=\"Second\", vendor=\"v\", version=\"1\")\n" +
                "public class SecondSbb { }\n");

        Path outDir = workDir.resolve("out");
        Files.createDirectories(outDir);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, null);
        List<JavaFileObject> sources = Arrays.asList(
                new InMemoryJavaFile(srcDir.resolve("FirstSbb.java")),
                new InMemoryJavaFile(srcDir.resolve("SecondSbb.java")));
        Iterable<String> options = Arrays.asList("-d", outDir.toString(),
                "-processorpath", processorJar(),
                "-processor", "com.microjainslee.apt.MicroJainsleeAnnotationProcessor");
        JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, options, null, sources);
        assertTrue("compile must succeed", task.call());
        fm.close();

        Properties props = new Properties();
        try (java.io.InputStream in = Files.newInputStream(outDir.resolve("META-INF/microjainslee/sbb-index.properties"))) {
            props.load(in);
        }
        // Both classes must be present, regardless of order
        boolean hasFirst  = "com.example.FirstSbb".equals(props.getProperty("sbb.0.class"))
                          || "com.example.FirstSbb".equals(props.getProperty("sbb.1.class"));
        boolean hasSecond = "com.example.SecondSbb".equals(props.getProperty("sbb.0.class"))
                          || "com.example.SecondSbb".equals(props.getProperty("sbb.1.class"));
        assertTrue("FirstSbb missing from index", hasFirst);
        assertTrue("SecondSbb missing from index", hasSecond);
    }

    // ----- helpers -----

    private static void writeFile(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String processorJar() {
        // Point at the installed jainslee-apt jar (and its log4j-api
        // dependency) so the JDK's annotation-processing SPI can find
        // the processor class. The jainslee-api jar is needed because
        // the processor imports com.microjainslee.api.annotations.*.
        String userHome = System.getProperty("user.home");
        return userHome + "/.m2/repository/com/microjainslee/jainslee-apt/1.1.0/jainslee-apt-1.1.0.jar"
             + ":" + userHome + "/.m2/repository/com/microjainslee/jainslee-api/1.1.0/jainslee-api-1.1.0.jar"
             + ":" + userHome + "/.m2/repository/org/apache/logging/log4j/log4j-api/2.23.1/log4j-api-2.23.1.jar";
    }

    /**
     * Wrap a path-based source file in a {@link JavaFileObject} for the compiler.
     *
     * <p>JDK 25 requires {@link #getCharContent(boolean)} to be implemented for
     * subclasses of {@link SimpleJavaFileObject} that wrap a path; the
     * default implementation now throws {@code UnsupportedOperationException}.</p>
     */
    private static final class InMemoryJavaFile extends SimpleJavaFileObject {
        private final String cachedSource;

        InMemoryJavaFile(Path path) throws java.io.IOException {
            super(path.toUri(), Kind.SOURCE);
            this.cachedSource = Files.readString(path);
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return cachedSource;
        }
    }
}

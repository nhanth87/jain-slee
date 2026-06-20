package org.mobicents.slee.container.deployment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.Test;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtMethod;

public class JavassistDeployTimeCodegenTest {

    @Test
    public void persistAndLoadWritesClassFileWithoutToClass() throws Exception {
        File outputDir = new File("target/test-generated-classes");
        deleteRecursively(outputDir);
        outputDir.mkdirs();

        String previous = System.getProperty(JavassistDeployTimeCodegen.OUTPUT_DIR_PROPERTY);
        System.setProperty(JavassistDeployTimeCodegen.OUTPUT_DIR_PROPERTY, outputDir.getAbsolutePath());

        ClassPool pool = new ClassPool(true);
        CtClass generated = pool.makeClass("com.example.deploytime.GeneratedSbbImpl");
        try {
            generated.addMethod(CtMethod.make("public int answer() { return 42; }", generated));

            URLClassLoader loader = new URLClassLoader(new URL[] { outputDir.toURI().toURL() },
                    Thread.currentThread().getContextClassLoader());
            Class<?> loaded = JavassistDeployTimeCodegen.persistAndLoad(generated, outputDir.getAbsolutePath(), loader);

            assertEquals("com.example.deploytime.GeneratedSbbImpl", loaded.getName());
            assertEquals(42, loaded.getMethod("answer").invoke(loaded.newInstance()));
            assertTrue(new File(outputDir, "com/example/deploytime/GeneratedSbbImpl.class").isFile());
        } finally {
            if (previous == null) {
                System.clearProperty(JavassistDeployTimeCodegen.OUTPUT_DIR_PROPERTY);
            } else {
                System.setProperty(JavassistDeployTimeCodegen.OUTPUT_DIR_PROPERTY, previous);
            }
            generated.defrost();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }
}

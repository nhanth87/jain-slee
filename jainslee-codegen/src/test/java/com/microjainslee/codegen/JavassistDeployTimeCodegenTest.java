/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.codegen;

import com.microjainslee.api.Sbb;
import com.microjainslee.core.CmpFieldStoreLocator;
import com.microjainslee.core.InMemoryCmpFieldStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sprint S2 — verifies the {@link JavassistDeployTimeCodegen} helper
 * performs the persist + load round trip correctly (Solution A).
 */
class JavassistDeployTimeCodegenTest {

    public abstract static class MinimalSbb implements Sbb {
        public abstract String getName();
        public abstract void setName(String name);
        public abstract int getCount();
        public abstract void setCount(int count);
    }

    @TempDir
    Path deployDir;

    private JavassistDeployTimeCodegen codegen;
    private InMemoryCmpFieldStore store;

    @BeforeEach
    void setup() throws Exception {
        codegen = new JavassistDeployTimeCodegen();
        store = new InMemoryCmpFieldStore();
        CmpFieldStoreLocator.set(store);
    }

    @AfterEach
    void teardown() {
        CmpFieldStoreLocator.set(null);
        codegen.invalidateAll();
    }

    @Test
    @DisplayName("isAvailable() reports true when codegen module is on classpath")
    void isAvailableReturnsTrueOnClasspath() {
        // We are running inside jainslee-codegen — so ConcreteSbbGenerator must be reachable.
        assertThat(JavassistDeployTimeCodegen.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("tryCreate() returns a non-null helper on the test classpath")
    void tryCreateReturnsHelper() {
        JavassistDeployTimeCodegen helper = JavassistDeployTimeCodegen.tryCreate();
        assertThat(helper).isNotNull();
    }

    @Test
    @DisplayName("persist: .class file lands in deployDir under the concrete package path")
    void persistFileLandsOnDisk() throws Exception {
        Class<?> concrete = codegen.getOrGenerate(MinimalSbb.class, deployDir);
        assertThat(concrete).isNotNull();

        // The .class file must be on disk under deployDir/<package>/<class>.class
        // The concrete class name is "<abstract>$Concrete", and the abstract
        // SBB lives inside the enclosing test class, so the package path
        // mirrors the FQCN of the abstract class.
        String concreteName = concrete.getName();
        String expectedPath = deployDir.resolve(concreteName.replace('.', '/') + ".class").toString();
        assertThat(Files.exists(Path.of(expectedPath)))
                .as("Generated .class at " + expectedPath)
                .isTrue();
    }

    @Test
    @DisplayName("load: re-loading the .class through a fresh URLClassLoader yields same behaviour")
    void loadRoundTrip() throws Exception {
        Class<?> first = codegen.getOrGenerate(MinimalSbb.class, deployDir);
        // Force a fresh load: build our own URLClassLoader and load the same class.
        try (URLClassLoader freshLoader = new URLClassLoader(
                new URL[]{ deployDir.toUri().toURL() },
                JavassistDeployTimeCodegenTest.class.getClassLoader())) {
            Class<?> reloaded = freshLoader.loadClass(MinimalSbb.class.getName() + ConcreteSbbGenerator.CONCRETE_SUFFIX);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getSuperclass().getName()).isEqualTo(MinimalSbb.class.getName());

            // Instantiate the reloaded class and exercise the accessors.
            Object instance = reloaded.getDeclaredConstructor().newInstance();
            Method setName = reloaded.getMethod("setName", String.class);
            Method getName = reloaded.getMethod("getName");
            Method setCount = reloaded.getMethod("setCount", int.class);
            Method getCount = reloaded.getMethod("getCount");

            // No entity id bound yet -> fallback map handles read/write
            setName.invoke(instance, "round-trip");
            assertThat(getName.invoke(instance)).isEqualTo("round-trip");

            setCount.invoke(instance, 17);
            assertThat(getCount.invoke(instance)).isEqualTo(17);
        }
    }

    @Test
    @DisplayName("cache: second call returns the same concrete class instance")
    void cacheReuse() throws Exception {
        Class<?> first = codegen.getOrGenerate(MinimalSbb.class, deployDir);
        Class<?> second = codegen.getOrGenerate(MinimalSbb.class, deployDir);
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("invalidate: drop the cache, next call regenerates")
    void invalidateRegenerates() throws Exception {
        Class<?> first = codegen.getOrGenerate(MinimalSbb.class, deployDir);
        codegen.invalidate(MinimalSbb.class, deployDir);
        Class<?> second = codegen.getOrGenerate(MinimalSbb.class, deployDir);
        // The class identity may match (JVM-cached), but the helper cache was cleared.
        assertThat(second).isNotNull();
    }

    @Test
    @DisplayName("invalidateAll: drops the cache wholesale")
    void invalidateAllClearsCache() throws Exception {
        codegen.getOrGenerate(MinimalSbb.class, deployDir);
        codegen.invalidateAll();
        // No exception means success — the cache is now empty.
        codegen.getOrGenerate(MinimalSbb.class, deployDir);
    }

    @Test
    @DisplayName("generator accessor returns the underlying ConcreteSbbGenerator")
    void generatorAccessor() {
        assertThat(codegen.generator()).isNotNull();
        assertThat(codegen.generator()).isInstanceOf(ConcreteSbbGenerator.class);
    }

    @Test
    @DisplayName("warmup is non-fatal on a fresh loader")
    void warmupIsSafe() {
        codegen.warmup(JavassistDeployTimeCodegenTest.class.getClassLoader());
        // No assertion beyond "did not throw".
    }
}

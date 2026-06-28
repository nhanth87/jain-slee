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
import javassist.ClassPool;
import javassist.CtClass;
import javassist.LoaderClassPath;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Deploy-time helper that wires the {@link ConcreteSbbGenerator} into
 * the {@code VirtualThreadSbbEntityPool} flow.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Lazy-resolve {@link ConcreteSbbGenerator} via reflection so the
 *       kernel still runs when the codegen module is NOT on the classpath
 *       (R&amp;D mode, single-JVM).</li>
 *   <li>Persist every generated class to the configured {@code deployDir}
 *       so the JVM metaspace is GC-friendly across redeploys
 *       (Solution A from the production roadmap).</li>
 *   <li>Re-load the generated class through a child {@link URLClassLoader}
 *       so the running JVM treats it like any .class on disk.</li>
 *   <li>Cache by abstract class name + deployDir combination, never
 *       re-generating on the hot path.</li>
 * </ul>
 *
 * <p>This class is intentionally side-effect free; it does not poke at
 * the SLEE container directly. The container's pool decides whether to
 * call {@link #getOrGenerate(Class, Path)} (codegen-enabled build) or
 * fall back to the legacy reflection-based {@code CmpAccessorInvoker}
 * (codegen-disabled or codegen module absent).
 *
 * @author Tran Nhan (nhanth87)
 */
public final class JavassistDeployTimeCodegen {

    private static final Logger LOG = LogManager.getLogger(JavassistDeployTimeCodegen.class);

    /** Optional generator instance. {@code null} when codegen is disabled. */
    private final ConcreteSbbGenerator generator;

    /**
     * Cache: (abstract class name + deployDir) -> generated concrete class.
     * Keyed by a {@link CacheKey} value object so a single generator can
     * serve multiple deploy dirs (e.g. parallel container instances).
     */
    private final ConcurrentMap<CacheKey, Class<?>> cache = new ConcurrentHashMap<CacheKey, Class<?>>();

    /** Optional override of the parent ClassLoader for the URLClassLoader. */
    private final ClassLoader parentLoader;

    /**
     * Construct with codegen enabled. Throws if {@link ConcreteSbbGenerator}
     * is not on the runtime classpath.
     */
    public JavassistDeployTimeCodegen() {
        this(new ConcreteSbbGenerator(), JavassistDeployTimeCodegen.class.getClassLoader());
    }

    /**
     * Construct with an explicit generator and parent ClassLoader. Visible
     * for tests.
     */
    public JavassistDeployTimeCodegen(ConcreteSbbGenerator generator, ClassLoader parentLoader) {
        this.generator = Objects.requireNonNull(generator, "generator is required");
        this.parentLoader = parentLoader == null ? JavassistDeployTimeCodegen.class.getClassLoader() : parentLoader;
    }

    /**
     * Reflectively probe whether {@code ConcreteSbbGenerator} is reachable
     * from the current thread's classloader. Use this at container-start
     * time to decide between the codegen path and the reflection fallback.
     *
     * @return {@code true} when {@code com.microjainslee.codegen.ConcreteSbbGenerator}
     *         can be loaded
     */
    public static boolean isAvailable() {
        try {
            Class.forName("com.microjainslee.codegen.ConcreteSbbGenerator",
                    false,
                    Thread.currentThread().getContextClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Build a {@link JavassistDeployTimeCodegen} if the codegen module is
     * available on the runtime classpath, otherwise return {@code null}.
     *
     * <p>Callers use the {@code null} return value as the signal to fall
     * back to the reflection-based {@code CmpAccessorInvoker} path.
     *
     * @return helper instance or {@code null}
     */
    public static JavassistDeployTimeCodegen tryCreate() {
        if (!isAvailable()) {
            LOG.info("Javassist CMP codegen not on classpath; falling back to reflection-based CmpAccessorInvoker");
            return null;
        }
        try {
            return new JavassistDeployTimeCodegen();
        } catch (Throwable t) {
            LOG.warn("Failed to initialise Javassist CMP codegen, falling back to reflection", t);
            return null;
        }
    }

    /**
     * Return the concrete class for {@code sbbAbstractClass}, generating
     * it on first call and caching for subsequent calls.
     *
     * @param sbbAbstractClass abstract SBB class
     * @param deployDir        directory for the generated .class file
     * @return concrete class
     * @throws IOException when generation or load fails
     */
    public Class<?> getOrGenerate(Class<? extends Sbb> sbbAbstractClass, Path deployDir) throws IOException {
        Objects.requireNonNull(sbbAbstractClass, "sbbAbstractClass is required");
        Objects.requireNonNull(deployDir, "deployDir is required");
        CacheKey key = new CacheKey(sbbAbstractClass.getName(), deployDir);
        Class<?> existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        Class<?> generated = generator.getOrGenerate(sbbAbstractClass, deployDir);
        cache.put(key, generated);
        return generated;
    }

    /**
     * Drop a concrete class from the cache (e.g. on undeployment). The
     * next {@link #getOrGenerate(Class, Path)} call will re-generate.
     *
     * @param sbbAbstractClass abstract SBB class
     * @param deployDir        directory the .class was generated into
     */
    public void invalidate(Class<? extends Sbb> sbbAbstractClass, Path deployDir) {
        cache.remove(new CacheKey(sbbAbstractClass.getName(), deployDir));
    }

    /**
     * Clear all cached entries. Useful for tests and for the
     * {@code redeploy} hook.
     */
    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Return the underlying {@link ConcreteSbbGenerator} (visible for
     * tests that need to assert the field-naming contract directly).
     */
    public ConcreteSbbGenerator generator() {
        return generator;
    }

    /**
     * Manually persist a {@link CtClass} to {@code deployDir} and load it
     * through a fresh {@link URLClassLoader}. Provided for advanced
     * scenarios (e.g. the S4 child-relation work emits additional
     * classes into the same package).
     *
     * @param concrete   the in-memory Javassist class
     * @param deployDir  output directory
     * @return the loaded {@link Class}
     */
    public Class<?> persistAndLoad(CtClass concrete, Path deployDir) throws IOException {
        Objects.requireNonNull(concrete, "concrete is required");
        Objects.requireNonNull(deployDir, "deployDir is required");
        Files.createDirectories(deployDir);
        try {
            concrete.writeFile(deployDir.toString());
        } catch (javassist.CannotCompileException e) {
            throw new IOException("Failed to write generated class to " + deployDir, e);
        }
        concrete.detach();
        String name = concrete.getName();
        URLClassLoader loader = new URLClassLoader(
                new URL[]{ deployDir.toUri().toURL() },
                parentLoader);
        try {
            return loader.loadClass(name);
        } catch (ClassNotFoundException e) {
            throw new IOException("Generated class not found in deployDir: " + name, e);
        }
    }

    /**
     * Pre-warm the {@link ClassPool} with the supplied loader. No-op in
     * the current implementation but exposed so deployers can eagerly
     * resolve abstract SBB classes ahead of the first event.
     */
    public void warmup(ClassLoader loader) {
        try {
            ClassPool pool = ClassPool.getDefault();
            pool.appendClassPath(new LoaderClassPath(loader == null ? parentLoader : loader));
        } catch (Throwable t) {
            LOG.debug("ClassPool warmup failed (non-fatal)", t);
        }
    }

    /**
     * Composite key used for the cache. Identity-equality is sufficient
     * because both fields are immutable for the lifetime of the entry.
     */
    private static final class CacheKey {
        private final String sbbName;
        private final Path deployDir;

        CacheKey(String sbbName, Path deployDir) {
            this.sbbName = sbbName;
            this.deployDir = deployDir;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey)) return false;
            CacheKey that = (CacheKey) o;
            return sbbName.equals(that.sbbName) && deployDir.equals(that.deployDir);
        }

        @Override
        public int hashCode() {
            return sbbName.hashCode() * 31 + deployDir.hashCode();
        }
    }
}

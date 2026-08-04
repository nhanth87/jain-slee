/*
 * micro-jainslee 1.1.0 -- example application (example-spring-helloworld-web)
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.example.helloworld.spring.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves Digicom-standard {@code html/} directory (dist deploy) ahead of any
 * classpath static leftovers. Set {@code hello.html.dir} (absolute or relative)
 * via {@code dist/run.sh}; falls back to {@code ./html} when present.
 */
@Configuration
public class HtmlDirWebConfig implements WebMvcConfigurer {

    private static final Logger LOG = LogManager.getLogger(HtmlDirWebConfig.class);

    @Value("${hello.html.dir:}")
    private String htmlDirProp;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dir = resolveHtmlDir();
        if (dir == null) {
            LOG.warn("No html/ directory — UI not served from disk (set hello.html.dir)");
            return;
        }
        String location = dir.toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/**")
                .addResourceLocations(location)
                .setCachePeriod(0);
        LOG.info("Serving UI from directory {}", dir);
    }

    private Path resolveHtmlDir() {
        if (htmlDirProp != null && !htmlDirProp.isBlank()) {
            Path p = Path.of(htmlDirProp.trim()).toAbsolutePath().normalize();
            if (Files.isDirectory(p)) {
                return p;
            }
            LOG.warn("hello.html.dir={} is not a directory", p);
        }
        Path cwd = Path.of("html").toAbsolutePath().normalize();
        return Files.isDirectory(cwd) ? cwd : null;
    }
}

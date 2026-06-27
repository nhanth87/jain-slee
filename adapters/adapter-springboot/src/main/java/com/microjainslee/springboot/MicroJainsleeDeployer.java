/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.springboot;

import com.microjainslee.core.MicroSleeContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MicroJainsleeDeployer implements ApplicationRunner {

    private static final Logger LOG = LogManager.getLogger(MicroJainsleeDeployer.class);

    private final MicroSleeContainer container;
    private final MicroJainsleeProperties props;

    public MicroJainsleeDeployer(MicroSleeContainer container, MicroJainsleeProperties props) {
        this.container = container;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.getDeployment().isAutoRegister()) {
            LOG.info("Auto-deploy disabled (microjainslee.deployment.auto-register=false); skipping");
            return;
        }
        String paths = props.getDeployment().getScanPaths();
        LOG.info("Auto-deploy requested; scanPaths='{}' (Phase 3.5 will implement the full scanner)", paths);
        // Phase 3.5: scan props.getDeployment().getScanPaths() and auto-register @Sbb classes.
    }
}
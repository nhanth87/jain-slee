/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.jakartaee;

import com.microjainslee.core.MicroSleeContainer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import javax.naming.InitialContext;
import javax.naming.NamingException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * CDI producer for {@link MicroSleeContainer} after
 * {@link MicroSleeContainerStartup} has bound it under
 * {@link JndiNames#CONTAINER}.
 *
 * <p>Use only from beans that start <em>after</em> the startup singleton
 * (e.g. {@code @DependsOn("MicroSleeContainerStartup")}). Looking up
 * before bind throws {@link IllegalStateException}.
 */
@ApplicationScoped
public class MicroSleeContainerProducer {

    private static final Logger LOG = LogManager.getLogger(MicroSleeContainerProducer.class);

    @Produces
    @ApplicationScoped
    public MicroSleeContainer produceContainer() {
        try {
            InitialContext ctx = new InitialContext();
            try {
                Object bound = ctx.lookup(JndiNames.CONTAINER);
                if (bound instanceof MicroSleeContainer container) {
                    return container;
                }
                throw new IllegalStateException(
                        "JNDI " + JndiNames.CONTAINER + " is not a MicroSleeContainer: "
                                + (bound == null ? "null" : bound.getClass().getName()));
            } finally {
                ctx.close();
            }
        } catch (NamingException e) {
            LOG.error("MicroSleeContainer JNDI lookup failed at {}", JndiNames.CONTAINER, e);
            throw new IllegalStateException(
                    "MicroSleeContainer not bound — ensure MicroSleeContainerStartup has run", e);
        }
    }
}

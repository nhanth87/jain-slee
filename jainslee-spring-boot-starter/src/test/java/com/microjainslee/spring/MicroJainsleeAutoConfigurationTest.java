/*
 * micro-jainslee 1.1.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.spring;

import com.microjainslee.core.MicroSleeContainer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MicroJainsleeAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MicroJainsleeAutoConfiguration.class));

    @Test
    void containerBeanIsCreated() {
        contextRunner.withPropertyValues("microjainslee.event-router.buffer-size=2048")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MicroSleeContainer.class);
                    assertThat(ctx).hasSingleBean(MicroJainsleeProperties.class);
                    assertThat(ctx).hasSingleBean(MicroJainsleeLifecycle.class);
                    assertThat(ctx.getBean(MicroSleeContainer.class).getState())
                            .isEqualTo(MicroSleeContainer.State.STARTED);
                });
    }
}
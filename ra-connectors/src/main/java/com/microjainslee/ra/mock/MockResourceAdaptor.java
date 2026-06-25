package com.microjainslee.ra.mock;

import com.microjainslee.api.*;

/**
 * Mock Resource Adaptor for testing.
 */
public class MockResourceAdaptor implements ResourceAdaptor {
    private ResourceAdaptorContext context;

    @Override
    public void setResourceAdaptorContext(ResourceAdaptorContext context) {
        this.context = context;
    }

    @Override
    public void raConfigure() {}

    @Override
    public void raActive() {}

    @Override
    public void raStopping() {}

    @Override
    public void raInactive() {}

    @Override
    public void raUnconfigure() {}

    public void sendEvent(SleeEvent event) {
        // Mock event sending
    }
}
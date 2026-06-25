package com.microjainslee.ra.mock;

import com.microjainslee.api.*;

/**
 * Mock Activity Context for testing.
 */
public class MockActivityContext implements ActivityContextInterface {
    @Override
    public String getActivityContextName() {
        return "MockActivityContext";
    }

    @Override
    public void attach(SbbLocalObject sbbLocalObject) {}

    @Override
    public void detach(SbbLocalObject sbbLocalObject) {}
}
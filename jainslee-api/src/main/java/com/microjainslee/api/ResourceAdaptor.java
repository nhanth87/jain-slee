package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §11 — Resource Adaptor interface.
 * All Resource Adaptors must implement this interface.
 */
public interface ResourceAdaptor {
    void setResourceAdaptorContext(ResourceAdaptorContext context);
    void raConfigure();
    void raActive();
    void raStopping();
    void raInactive();
    void raUnconfigure();
}
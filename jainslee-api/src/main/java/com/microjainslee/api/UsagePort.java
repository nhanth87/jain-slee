package com.microjainslee.api;

/**
 * JAIN-SLEE 1.1 §12 — Usage Port interface.
 * Provides usage monitoring facilities.
 */
public interface UsagePort {
    void incrementCounter(String counterName);
}
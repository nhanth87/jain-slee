package com.microjainslee.api;

/**
 * Optional micro-container dispatch contract for SBBs that receive routed events directly.
 */
public interface SleeEventHandler {
    void onEvent(SleeEvent event, ActivityContextInterface aci) throws Exception;
}

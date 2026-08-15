package com.microjainslee.ra.freeswitch;

/** Mutable ESL connection settings for {@link FreeSwitchResourceAdaptor}. */
public final class FreeSwitchRaConfig {

    private String host = "127.0.0.1";
    private int port = 8021;
    private String password = "ClueCon";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 2000;
    private boolean autoSubscribe = true;
    private String subscribeEvents = "HEARTBEAT CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_HANGUP CHANNEL_DESTROY";

    public String host() { return host; }
    public void setHost(String host) { this.host = host; }

    public int port() { return port; }
    public void setPort(int port) { this.port = port; }

    public String password() { return password; }
    public void setPassword(String password) { this.password = password; }

    public int connectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }

    public int readTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }

    public boolean autoSubscribe() { return autoSubscribe; }
    public void setAutoSubscribe(boolean v) { this.autoSubscribe = v; }

    public String subscribeEvents() { return subscribeEvents; }
    public void setSubscribeEvents(String v) { this.subscribeEvents = v; }
}

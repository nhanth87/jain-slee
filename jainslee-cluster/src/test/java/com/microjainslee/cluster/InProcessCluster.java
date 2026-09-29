/*
 * micro-jainslee 1.2.0
 *
 * Dual-licensed: GPLv3 (Section A) OR Commercial License (Section B).
 * See the LICENSE file at the root of this repository for the full text.
 *
 * Copyright (c) 2026 Tran Nhan (nhanth87). All rights reserved.
 * Contact: nhanth87@gmail.com
 */

package com.microjainslee.cluster;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.infinispan.commons.marshall.JavaSerializationMarshaller;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;

/**
 * N real Infinispan/JGroups members in one JVM: TCP on loopback, FILE_PING
 * discovery in a private directory, the production Java-serialization
 * marshaller and allow-list. Each member is wrapped with
 * {@link ClusterManager#adopt} so code under test sees a normal cluster.
 *
 * <p>
 * Use this for anything that claims cross-node behaviour; single-manager tests
 * cannot see a forward that goes nowhere.
 */
public final class InProcessCluster implements AutoCloseable {

    private final List<ClusterManager> nodes = new ArrayList<>();
    private final List<DefaultCacheManager> managers = new ArrayList<>();
    private final String previousPingDir;

    public InProcessCluster(Path pingDir, String clusterName, String... nodeIds) throws Exception {
        Files.createDirectories(pingDir);
        this.previousPingDir = System.getProperty("jgroups.file_ping.dir");
        System.setProperty("jgroups.file_ping.dir", pingDir.toString());
        URL stack = InProcessCluster.class.getClassLoader().getResource("jgroups-tcp-file-ping.xml");
        if (stack == null) {
            throw new IllegalStateException("jgroups-tcp-file-ping.xml missing from the test classpath");
        }
        for (String nodeId : nodeIds) {
            GlobalConfigurationBuilder global = GlobalConfigurationBuilder.defaultClusteredBuilder();
            global.transport().clusterName(clusterName).nodeName(nodeId)
                    .addProperty("configurationFile", "jgroups-tcp-file-ping.xml");
            global.cacheManagerName("inproc-" + nodeId);
            var allow = global.serialization().marshaller(new JavaSerializationMarshaller()).allowList();
            for (String regexp : MarshallingAllowList.REGEXPS) {
                allow.addRegexp(regexp);
            }
            DefaultCacheManager manager = new DefaultCacheManager(global.build(), true);
            managers.add(manager);
            nodes.add(ClusterManager.adopt(manager, nodeId, true));
        }
        awaitView(nodeIds.length, 20);
    }

    public ClusterManager node(int index) {
        return nodes.get(index);
    }

    /** Stop one member abruptly, as a crash would. The others see a view change. */
    public void kill(int index) {
        managers.get(index).stop();
    }

    /**
     * Partition member {@code index} from everyone else, process still alive —
     * scenario C. A JGroups {@code DISCARD} above the transport drops all its
     * traffic both ways; failure detection then splits the view.
     */
    public void isolate(int index) throws Exception {
        org.jgroups.JChannel channel = channel(index);
        org.jgroups.protocols.DISCARD discard = new org.jgroups.protocols.DISCARD();
        discard.discardAll(true);
        channel.getProtocolStack().insertProtocol(discard, org.jgroups.stack.ProtocolStack.Position.ABOVE,
                org.jgroups.protocols.TP.class);
    }

    /** Undo {@link #isolate}; MERGE3 then merges the views back. */
    public void heal(int index) {
        channel(index).getProtocolStack().removeProtocol(org.jgroups.protocols.DISCARD.class);
    }

    /** Members of {@code index}'s current view. */
    public int viewSize(int index) {
        var members = managers.get(index).getMembers();
        return members == null ? 0 : members.size();
    }

    private org.jgroups.JChannel channel(int index) {
        var transport = org.infinispan.factories.GlobalComponentRegistry.componentOf(managers.get(index),
                org.infinispan.remoting.transport.Transport.class);
        return ((org.infinispan.remoting.transport.jgroups.JGroupsTransport) transport).getChannel();
    }

    public void awaitView(int members, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            boolean all = true;
            for (DefaultCacheManager m : managers) {
                if (m.getStatus().allowInvocations()
                        && (m.getMembers() == null || m.getMembers().size() != members)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("cluster view did not converge to " + members + " members");
    }

    @Override
    public void close() {
        for (int i = managers.size() - 1; i >= 0; i--) {
            try {
                managers.get(i).stop();
            } catch (RuntimeException ignored) {
                // best effort
            }
        }
        if (previousPingDir == null) {
            System.clearProperty("jgroups.file_ping.dir");
        } else {
            System.setProperty("jgroups.file_ping.dir", previousPingDir);
        }
    }
}

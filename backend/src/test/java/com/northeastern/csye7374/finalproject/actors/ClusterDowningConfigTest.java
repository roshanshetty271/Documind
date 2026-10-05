package com.northeastern.csye7374.finalproject.actors;

import akka.actor.testkit.typed.javadsl.ActorTestKit;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.MemberStatus;
import akka.cluster.typed.Cluster;
import akka.cluster.typed.Join;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The cluster must use the Split Brain Resolver (Akka 2.6 ignores
 * auto-down-unreachable-after), and a node must start with that config.
 */
class ClusterDowningConfigTest {

    @Test
    void applicationConfUsesSplitBrainResolver() {
        Config config = ConfigFactory.load();
        assertEquals("akka.cluster.sbr.SplitBrainResolverProvider",
            config.getString("akka.cluster.downing-provider-class"));
        assertEquals("keep-oldest", config.getString("akka.cluster.split-brain-resolver.active-strategy"));
        assertTrue(config.getBoolean("akka.cluster.split-brain-resolver.keep-oldest.down-if-alone"));
        // akka-cluster's reference.conf no longer defines the old key, so it is only present if we set it
        assertFalse(config.hasPath("akka.cluster.auto-down-unreachable-after"));
    }

    @Test
    void clusterNodeStartsWithSplitBrainResolver() {
        Config config = ConfigFactory.parseString(
                "akka.remote.artery.canonical.port = 0\n"
                + "akka.cluster.seed-nodes = []\n"
                + "akka.coordinated-shutdown.exit-jvm = off\n"
                + "akka.coordinated-shutdown.run-by-jvm-shutdown-hook = off\n")
            .withFallback(ConfigFactory.load());
        ActorTestKit kit = ActorTestKit.create("DistributedRAGSystem", config);
        try {
            Cluster cluster = Cluster.get(kit.system());
            assertEquals("akka.cluster.sbr.SplitBrainResolverProvider",
                akka.cluster.Cluster.get(akka.actor.typed.javadsl.Adapter.toClassic(kit.system()))
                    .settings().DowningProviderClassName());

            cluster.manager().tell(Join.create(cluster.selfMember().address()));
            kit.createTestProbe().awaitAssert(Duration.ofSeconds(10), () -> {
                assertEquals(MemberStatus.up(), cluster.selfMember().status());
                return null;
            });
        } finally {
            kit.shutdownTestKit();
        }
    }
}

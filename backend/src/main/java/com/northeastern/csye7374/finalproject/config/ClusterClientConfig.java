package com.northeastern.csye7374.finalproject.config;

import akka.actor.typed.ActorSystem;
import com.northeastern.csye7374.finalproject.actors.ClusterClientActor;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * ClusterClientConfig - Spring config for Akka cluster connection
 * 
 * Creates ActorSystem that joins cluster on port 2553
 * Connects to seed nodes 2551 and 2552
 */
@Configuration
public class ClusterClientConfig {
    
    private static final Logger log = LoggerFactory.getLogger(ClusterClientConfig.class);
    
    private ActorSystem<ClusterClientActor.Command> actorSystem;
    
    // Create ActorSystem and join cluster
    @Bean
    public ActorSystem<ClusterClientActor.Command> clusterClientSystem() {
        log.info("---");
        log.info("[REST-CLUSTER] Creating ActorSystem to join cluster...");
        log.info("---");
        
        System.out.println();
        System.out.println("---");
        System.out.println("[REST-CLUSTER] Creating ActorSystem to join cluster...");
        System.out.println("---");
        
        // Configuration for joining cluster
        String configString = 
            "akka {\n" +
            "  actor {\n" +
            "    provider = cluster\n" +
            "    \n" +
            "    # Use Java serialization\n" +
            "    allow-java-serialization = on\n" +
            "    warn-about-java-serializer-usage = off\n" +
            "    \n" +
            "    serialization-bindings {\n" +
            "      \"com.northeastern.csye7374.finalproject.actors.OrchestratorActor$Command\" = java\n" +
            "      \"com.northeastern.csye7374.finalproject.actors.OrchestratorActor$ProcessQuery\" = java\n" +
            "      \"com.northeastern.csye7374.finalproject.actors.OrchestratorActor$QueryResult\" = java\n" +
            "      \"com.northeastern.csye7374.finalproject.actors.ClusterClientActor$Command\" = java\n" +
            "      \"com.northeastern.csye7374.finalproject.actors.ClusterClientActor$GetOrchestrator\" = java\n" +
            "      \"com.northeastern.csye7374.finalproject.actors.ClusterClientActor$OrchestratorResponse\" = java\n" +
            "    }\n" +
            "  }\n" +
            "  \n" +
            "  remote.artery {\n" +
            "    canonical {\n" +
            "      hostname = \"127.0.0.1\"\n" +
            "      port = 2553\n" +
            "    }\n" +
            "  }\n" +
            "  \n" +
            "  cluster {\n" +
            "    # Seed nodes\n" +
            "    seed-nodes = [\n" +
            "      \"akka://DistributedRAGSystem@127.0.0.1:2551\",\n" +
            "      \"akka://DistributedRAGSystem@127.0.0.1:2552\"\n" +
            "    ]\n" +
            "    \n" +
            "    # Role\n" +
            "    roles = [\"rest-api\"]\n" +
            "    \n" +
            "    # Minimum nodes\n" +
            "    min-nr-of-members = 1\n" +
            "    \n" +
            "    # Auto-down unreachable nodes\n" +
            "    auto-down-unreachable-after = 10s\n" +
            "  }\n" +
            "  \n" +
            "  # Logging\n" +
            "  loglevel = \"INFO\"\n" +
            "  stdout-loglevel = \"INFO\"\n" +
            "}\n";
        
        Config config = ConfigFactory.parseString(configString)
            .withFallback(ConfigFactory.load());
        
        // Create ActorSystem
        actorSystem = ActorSystem.create(
            ClusterClientActor.create(),
            "DistributedRAGSystem",
            config
        );
        
        log.info("---");
        log.info("[REST-CLUSTER]  ActorSystem created!");
        log.info("[REST-CLUSTER] System name: DistributedRAGSystem");
        log.info("[REST-CLUSTER] Node address: 127.0.0.1:2553");
        log.info("[REST-CLUSTER] Role: rest-api");
        log.info("[REST-CLUSTER] Seed nodes: 2551, 2552");
        log.info("---");
        
        System.out.println("---");
        System.out.println("[REST-CLUSTER]  Joined cluster as REST API client!");
        System.out.println("[REST-CLUSTER] Node: 127.0.0.1:2553");
        System.out.println("[REST-CLUSTER] Seeds: 127.0.0.1:2551, 127.0.0.1:2552");
        System.out.println("[REST-CLUSTER] Waiting for orchestrator discovery...");
        System.out.println("---");
        System.out.println();
        
        return actorSystem;
    }
    
    // Graceful shutdown
    @PreDestroy
    public void shutdown() {
        if (actorSystem != null) {
            log.info("[REST-CLUSTER] Shutting down ActorSystem...");
            System.out.println("[REST-CLUSTER] Shutting down ActorSystem...");
            actorSystem.terminate();
        }
    }
}


package com.northeastern.csye7374.finalproject.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ClusterClientActor - Gateway for REST API to Akka Cluster
 * 
 * Discovers orchestrators via Receptionist
 * Provides round-robin load balancing
 */
public class ClusterClientActor extends AbstractBehavior<ClusterClientActor.Command> {
    
    private static final Logger log = LoggerFactory.getLogger(ClusterClientActor.class);
    
    // Messages
    public interface Command extends Serializable {}
    
    // Internal message from Receptionist
    private static final class OrchestratorListing implements Command {
        public final Set<ActorRef<OrchestratorActor.Command>> orchestrators;
        
        public OrchestratorListing(Set<ActorRef<OrchestratorActor.Command>> orchestrators) {
            this.orchestrators = orchestrators;
        }
    }
    
    // Request orchestrator (ASK pattern)
    public static final class GetOrchestrator implements Command {
        private static final long serialVersionUID = 1L;
        
        public final ActorRef<OrchestratorResponse> replyTo;
        
        public GetOrchestrator(ActorRef<OrchestratorResponse> replyTo) {
            this.replyTo = replyTo;
        }
    }
    
    // Response with orchestrator reference
    public static final class OrchestratorResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final ActorRef<OrchestratorActor.Command> orchestrator;
        public final boolean available;
        public final int totalOrchestrators;
        
        public OrchestratorResponse(ActorRef<OrchestratorActor.Command> orchestrator,
                                    boolean available, int totalOrchestrators) {
            this.orchestrator = orchestrator;
            this.available = available;
            this.totalOrchestrators = totalOrchestrators;
        }
    }
    
    // State
    private final List<ActorRef<OrchestratorActor.Command>> orchestrators = new CopyOnWriteArrayList<>();
    private final AtomicInteger roundRobinIndex = new AtomicInteger(0);
    
    // Factory method
    public static Behavior<Command> create() {
        return Behaviors.setup(ClusterClientActor::new);
    }
    
    // Constructor - subscribe to Receptionist
    private ClusterClientActor(ActorContext<Command> context) {
        super(context);
        
        log.info("---");
        log.info("[CLUSTER-CLIENT] Starting ClusterClientActor...");
        log.info("[CLUSTER-CLIENT] Subscribing to discover OrchestratorActors...");
        log.info("---");
        
        System.out.println("---");
        System.out.println("[CLUSTER-CLIENT] Starting ClusterClientActor...");
        System.out.println("[CLUSTER-CLIENT] Subscribing to discover OrchestratorActors...");
        System.out.println("---");
        
        // Create adapter
        ActorRef<Receptionist.Listing> listingAdapter = context.messageAdapter(
            Receptionist.Listing.class,
            listing -> new OrchestratorListing(
                listing.getServiceInstances(OrchestratorActor.ORCHESTRATOR_KEY)
            )
        );
        
        // Subscribe to Receptionist
        context.getSystem().receptionist().tell(
            Receptionist.subscribe(OrchestratorActor.ORCHESTRATOR_KEY, listingAdapter)
        );
        
        log.info("[CLUSTER-CLIENT] Subscribed to OrchestratorActor.ORCHESTRATOR_KEY");
    }
    
    // Message handling
    
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(OrchestratorListing.class, this::onOrchestratorListing)
            .onMessage(GetOrchestrator.class, this::onGetOrchestrator)
            .build();
    }
    
    // Update orchestrators list
    private Behavior<Command> onOrchestratorListing(OrchestratorListing msg) {
        orchestrators.clear();
        orchestrators.addAll(msg.orchestrators);
        
        log.info("---");
        log.info("[CLUSTER-CLIENT] OrchestratorActors updated!");
        log.info("[CLUSTER-CLIENT] Found {} orchestrator(s):", orchestrators.size());
        
        System.out.println("---");
        System.out.println("[CLUSTER-CLIENT] OrchestratorActors updated!");
        System.out.println("[CLUSTER-CLIENT] Found " + orchestrators.size() + " orchestrator(s):");
        
        for (ActorRef<OrchestratorActor.Command> orch : orchestrators) {
            log.info("[CLUSTER-CLIENT]   → {}", orch.path());
            System.out.println("[CLUSTER-CLIENT]   → " + orch.path());
        }
        
        if (orchestrators.isEmpty()) {
            System.out.println("[CLUSTER-CLIENT]  No orchestrators available!");
            System.out.println("[CLUSTER-CLIENT]    Make sure cluster nodes (2551, 2552) are running");
        } else {
            System.out.println("[CLUSTER-CLIENT] ✅ Ready to route queries to cluster!");
        }
        
        log.info("---");
        System.out.println("---");
        
        return this;
    }
    
    // Get orchestrator (round-robin)
    private Behavior<Command> onGetOrchestrator(GetOrchestrator msg) {
        if (orchestrators.isEmpty()) {
            log.warn("[CLUSTER-CLIENT] GetOrchestrator called but no orchestrators available");
            msg.replyTo.tell(new OrchestratorResponse(null, false, 0));
        } else {
            // Round-robin selection
            int index = roundRobinIndex.getAndIncrement() % orchestrators.size();
            ActorRef<OrchestratorActor.Command> selected = orchestrators.get(index);
            
            log.info("[CLUSTER-CLIENT] Returning orchestrator: {} (index {})", selected.path(), index);
            msg.replyTo.tell(new OrchestratorResponse(selected, true, orchestrators.size()));
        }
        return this;
    }
}


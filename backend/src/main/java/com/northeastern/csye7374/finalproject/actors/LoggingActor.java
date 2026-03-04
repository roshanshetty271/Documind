package com.northeastern.csye7374.finalproject.actors;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * LoggingActor - Centralized logging
 * 
 * Demonstrates TELL pattern (fire-and-forget):
 * - Receives log messages
 * - Never replies
 * - Provides audit trail
 */
public class LoggingActor extends AbstractBehavior<LoggingActor.Command> {
    
    private static final Logger log = LoggerFactory.getLogger(LoggingActor.class);
    
    // Register with Receptionist
    public static final ServiceKey<Command> SERVICE_KEY = 
        ServiceKey.create(Command.class, "logging-actor");
    
    // All messages are fire-and-forget (no replyTo)
    public interface Command extends Serializable {}
    
    // Log when query is processed
    public static final class LogQuery implements Command {
        private static final long serialVersionUID = 1L;
        
        public final String query;
        public final String responsePreview;
        public final long timestamp;
        public final String nodeId;
        
        public LogQuery(String query, String responsePreview, long timestamp, String nodeId) {
            this.query = query;
            this.responsePreview = responsePreview;
            this.timestamp = timestamp;
            this.nodeId = nodeId;
        }
    }
    
    // Log when search completes
    public static final class LogSearch implements Command {
        private static final long serialVersionUID = 1L;
        
        public final String query;
        public final int chunksFound;
        public final long timestamp;
        public final String nodeId;
        
        public LogSearch(String query, int chunksFound, long timestamp, String nodeId) {
            this.query = query;
            this.chunksFound = chunksFound;
            this.timestamp = timestamp;
            this.nodeId = nodeId;
        }
    }
    
    // Generic log entry
    public static final class LogEntry implements Command {
        private static final long serialVersionUID = 1L;
        
        public final String type;
        public final String message;
        public final long timestamp;
        public final String nodeId;
        
        public LogEntry(String type, String message, long timestamp, String nodeId) {
            this.type = type;
            this.message = message;
            this.timestamp = timestamp;
            this.nodeId = nodeId;
        }
    }
    
    private final String nodeId;
    private final DateTimeFormatter formatter = 
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private LoggingActor(ActorContext<Command> context, String nodeId) {
        super(context);
        this.nodeId = nodeId;
    }
    
    // Factory method
    public static Behavior<Command> create(String nodeId) {
        return Behaviors.setup(context -> {
            // Register with Receptionist
            context.getSystem().receptionist().tell(
                Receptionist.register(SERVICE_KEY, context.getSelf())
            );
            
            log.info("---");
            log.info("[LOGGER] [{}] Started and registered with Receptionist", nodeId);
            log.info("[LOGGER] Path: {}", context.getSelf().path());
            log.info("[LOGGER] Pattern: TELL (fire-and-forget) - NO REPLIES", nodeId);
            log.info("---");
            
            // Console output
            System.out.println("---");
            System.out.println("[LOGGER] [" + nodeId + "] Started and registered with Receptionist");
            System.out.println("[LOGGER] Pattern: TELL (fire-and-forget) - NO REPLIES");
            System.out.println("---");
            
            return new LoggingActor(context, nodeId);
        });
    }
    
    // Message handler (no replies)
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(LogQuery.class, this::onLogQuery)
            .onMessage(LogSearch.class, this::onLogSearch)
            .onMessage(LogEntry.class, this::onLogEntry)
            .build();
    }
    
    // Log query (fire-and-forget)
    private Behavior<Command> onLogQuery(LogQuery logMsg) {
        String time = formatTime(logMsg.timestamp);
        String queryPreview = logMsg.query.length() > 50 
            ? logMsg.query.substring(0, 50) + "..." 
            : logMsg.query;
        
        log.info("---");
        log.info("[LOGGER] [{}] Received via TELL (fire-and-forget)", nodeId);
        log.info("[LOGGER] Type: QUERY_RESPONSE");
        log.info("[LOGGER] Time: {}", time);
        log.info("[LOGGER] From Node: {}", logMsg.nodeId);
        log.info("[LOGGER] Query: \"{}\"", queryPreview);
        log.info("[LOGGER] Response preview: \"{}\"", logMsg.responsePreview);
        log.info("[LOGGER] ✓ Logged (NO REPLY - fire-and-forget pattern)");
        log.info("---");
        
        // Console output for demo
        System.out.println("---");
        System.out.println("[LOGGER] [" + nodeId + "] Received via TELL (fire-and-forget)");
        System.out.println("[LOGGER] Type: QUERY_RESPONSE");
        System.out.println("[LOGGER] Time: " + time);
        System.out.println("[LOGGER] From Node: " + logMsg.nodeId);
        System.out.println("[LOGGER] Query: \"" + queryPreview + "\"");
        System.out.println("[LOGGER] Response: \"" + logMsg.responsePreview + "\"");
        System.out.println("[LOGGER] ✓ Logged (NO REPLY - fire-and-forget pattern)");
        System.out.println("---");
        
        return this;
    }
    
    // Log search (fire-and-forget)
    private Behavior<Command> onLogSearch(LogSearch logMsg) {
        String time = formatTime(logMsg.timestamp);
        String queryPreview = logMsg.query.length() > 50 
            ? logMsg.query.substring(0, 50) + "..." 
            : logMsg.query;
        
        log.info("---");
        log.info("[LOGGER] [{}] Received via TELL (fire-and-forget)", nodeId);
        log.info("[LOGGER] Type: SEARCH_COMPLETED");
        log.info("[LOGGER] Time: {}", time);
        log.info("[LOGGER] From Node: {}", logMsg.nodeId);
        log.info("[LOGGER] Query: \"{}\"", queryPreview);
        log.info("[LOGGER] Chunks Found: {}", logMsg.chunksFound);
        log.info("[LOGGER] ✓ Logged (NO REPLY - fire-and-forget pattern)");
        log.info("---");
        
        // Console output for demo
        System.out.println("---");
        System.out.println("[LOGGER] [" + nodeId + "] Received via TELL (fire-and-forget)");
        System.out.println("[LOGGER] Type: SEARCH_COMPLETED");
        System.out.println("[LOGGER] Time: " + time);
        System.out.println("[LOGGER] From Node: " + logMsg.nodeId);
        System.out.println("[LOGGER] Query: \"" + queryPreview + "\"");
        System.out.println("[LOGGER] Chunks Found: " + logMsg.chunksFound);
        System.out.println("[LOGGER] ✓ Logged (NO REPLY - fire-and-forget pattern)");
        System.out.println("---");
        
        return this;
    }
    
    // Log entry (fire-and-forget)
    private Behavior<Command> onLogEntry(LogEntry logMsg) {
        String time = formatTime(logMsg.timestamp);
        
        log.info("---");
        log.info("[LOGGER] [{}] Received via TELL (fire-and-forget)", nodeId);
        log.info("[LOGGER] Type: {}", logMsg.type);
        log.info("[LOGGER] Time: {}", time);
        log.info("[LOGGER] From Node: {}", logMsg.nodeId);
        log.info("[LOGGER] Message: {}", logMsg.message);
        log.info("[LOGGER] ✓ Logged (NO REPLY - fire-and-forget pattern)");
        log.info("---");
        
        // Console output for demo
        System.out.println("---");
        System.out.println("[LOGGER] [" + nodeId + "] Received via TELL (fire-and-forget)");
        System.out.println("[LOGGER] Type: " + logMsg.type);
        System.out.println("[LOGGER] Time: " + time);
        System.out.println("[LOGGER] From Node: " + logMsg.nodeId);
        System.out.println("[LOGGER] Message: " + logMsg.message);
        System.out.println("[LOGGER] ✓ Logged (NO REPLY - fire-and-forget pattern)");
        System.out.println("---");
        
        return this;
    }
    
    // Format timestamp
    private String formatTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(formatter);
    }
}


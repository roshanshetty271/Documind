package com.northeastern.csye7374.finalproject.actors;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.DispatcherSelector;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.receptionist.Receptionist;
import akka.actor.typed.receptionist.ServiceKey;
import com.northeastern.csye7374.finalproject.services.LLMService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * LLMActor - Handles LLM communication
 * 
 * Wraps LLMService and demonstrates communication patterns:
 * - TELL to LoggingActor (fire-and-forget)
 * - Replies to original sender (FORWARD pattern)
 */
public class LLMActor extends AbstractBehavior<LLMActor.Command> {
    
    private static final Logger log = LoggerFactory.getLogger(LLMActor.class);
    
    // Register with Receptionist for cluster discovery
    public static final ServiceKey<Command> SERVICE_KEY = 
        ServiceKey.create(Command.class, "llm-actor");
    
    // All messages must be serializable
    public interface Command extends Serializable {}
    
    // Request to generate answer
    public static final class GenerateAnswer implements Command {
        private static final long serialVersionUID = 1L;
        
        public final String query;
        public final List<String> contextChunks;
        public final ActorRef<LLMResponse> replyTo;
        public final ActorRef<LoggingActor.Command> loggingActor;
        
        public GenerateAnswer(String query, List<String> contextChunks,
                             ActorRef<LLMResponse> replyTo,
                             ActorRef<LoggingActor.Command> loggingActor) {
            this.query = query;
            this.contextChunks = contextChunks;
            this.replyTo = replyTo;
            this.loggingActor = loggingActor;
        }
    }
    
    // Response with generated answer
    public static final class LLMResponse implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String answer;
        public final boolean success;
        public final String errorMessage;
        public final double responseTimeSeconds;
        public final String processedByNode;
        
        // Success
        public LLMResponse(String answer, double responseTimeSeconds, String processedByNode) {
            this.answer = answer;
            this.success = true;
            this.errorMessage = null;
            this.responseTimeSeconds = responseTimeSeconds;
            this.processedByNode = processedByNode;
        }
        
        // Error
        public LLMResponse(String errorMessage, String processedByNode) {
            this.answer = null;
            this.success = false;
            this.errorMessage = errorMessage;
            this.responseTimeSeconds = 0;
            this.processedByNode = processedByNode;
        }
    }
    
    // Internal: OpenAI call finished on the blocking-io dispatcher
    private static final class AnswerReady implements Command {
        public final GenerateAnswer request;
        public final String answer;        // null on failure
        public final Throwable failure;    // null on success
        public final long startTimeMs;
        
        public AnswerReady(GenerateAnswer request, String answer, Throwable failure, long startTimeMs) {
            this.request = request;
            this.answer = answer;
            this.failure = failure;
            this.startTimeMs = startTimeMs;
        }
    }
    
    private final LLMService llmService;
    private final String nodeId;
    private final Executor blockingExecutor;
    private LLMActor(ActorContext<Command> context, LLMService llmService, String nodeId) {
        super(context);
        this.llmService = llmService;
        this.nodeId = nodeId;
        this.blockingExecutor = context.getSystem().dispatchers()
            .lookup(DispatcherSelector.fromConfig(SearchWorkerActor.BLOCKING_DISPATCHER));
    }
    
    // Factory method - registers with Receptionist
    public static Behavior<Command> create(LLMService llmService, String nodeId) {
        return Behaviors.setup(context -> {
            // Register with Receptionist
            context.getSystem().receptionist().tell(
                Receptionist.register(SERVICE_KEY, context.getSelf())
            );
            
            log.info("---");
            log.info("[LLM-ACTOR] [{}] Started and registered with Receptionist", nodeId);
            log.info("[LLM-ACTOR] Path: {}", context.getSelf().path());
            log.info("---");
            
            // Console output
            System.out.println("---");
            System.out.println("[LLM-ACTOR] [" + nodeId + "] Started and registered with Receptionist");
            System.out.println("---");
            
            return new LLMActor(context, llmService, nodeId);
        });
    }
    
    // Message handler
    @Override
    public Receive<Command> createReceive() {
        return newReceiveBuilder()
            .onMessage(GenerateAnswer.class, this::onGenerateAnswer)
            .onMessage(AnswerReady.class, this::onAnswerReady)
            .build();
    }
    
    // Generate LLM answer
    private Behavior<Command> onGenerateAnswer(GenerateAnswer cmd) {
        log.info("---");
        log.info("[LLM-ACTOR] [{}] Received GenerateAnswer", nodeId);
        log.info("[LLM-ACTOR] Query length: {} chars", cmd.query.length());
        log.info("[LLM-ACTOR] Context chunks: {}", cmd.contextChunks.size());
        
        // Console output
        System.out.println("---");
        System.out.println("[LLM-ACTOR] [" + nodeId + "] Received GenerateAnswer");
        System.out.println("[LLM-ACTOR] Query length: " + cmd.query.length() + " chars");
        System.out.println("[LLM-ACTOR] Context chunks: " + cmd.contextChunks.size());
        
        long startTime = System.currentTimeMillis();
        
        // Call LLM service on the blocking-io dispatcher; the actor stays free
        // to accept the next request while OpenAI is working
        System.out.println("[LLM-ACTOR] Calling OpenAI API...");
        log.info("[LLM-ACTOR] Calling LLMService.generateAnswer()...");
        
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
            () -> llmService.generateAnswer(cmd.query, cmd.contextChunks), blockingExecutor);
        getContext().pipeToSelf(future, (answer, failure) -> new AnswerReady(cmd, answer, failure, startTime));
        
        return this;
    }
    
    // Reply once the OpenAI call has finished
    private Behavior<Command> onAnswerReady(AnswerReady ready) {
        GenerateAnswer cmd = ready.request;
        
        try {
            if (ready.failure != null) {
                // supplyAsync wraps the real error in a CompletionException
                Throwable cause = ready.failure instanceof CompletionException && ready.failure.getCause() != null
                    ? ready.failure.getCause() : ready.failure;
                throw new RuntimeException(cause.getMessage(), cause);
            }
            String answer = ready.answer;
            double responseTimeSeconds = (System.currentTimeMillis() - ready.startTimeMs) / 1000.0;
            
            log.info("[LLM-ACTOR] ✅ Response generated in {}s", responseTimeSeconds);
            System.out.println("[LLM-ACTOR] ✅ Response generated in " + 
                String.format("%.2f", responseTimeSeconds) + "s");
            
            // TELL to logger (fire-and-forget)
            if (cmd.loggingActor != null) {
                log.info("[LLM-ACTOR] TELL → LoggingActor (fire-and-forget)");
                System.out.println("[LLM-ACTOR] TELL → LoggingActor (fire-and-forget)");
                
                cmd.loggingActor.tell(new LoggingActor.LogQuery(
                    cmd.query,
                    "(" + answer.length() + " chars)",  // never log the answer text
                    System.currentTimeMillis(),
                    nodeId
                ));
            }
            
            // Reply to original sender (FORWARD pattern)
            log.info("[LLM-ACTOR] Replying to original sender (FORWARD pattern)...");
            System.out.println("[LLM-ACTOR] Replying to original sender (FORWARD pattern)...");
            
            cmd.replyTo.tell(new LLMResponse(answer, responseTimeSeconds, nodeId));
            
            log.info("[LLM-ACTOR] ✅ Response sent to original sender");
            System.out.println("[LLM-ACTOR] ✅ Response sent to original sender");
            System.out.println("---");
            
        } catch (Exception e) {
            log.error("[LLM-ACTOR] ❌ Error: {}", e.getMessage(), e);
            System.out.println("[LLM-ACTOR] ❌ Error: " + e.getMessage());
            
            // Log error via TELL
            if (cmd.loggingActor != null) {
                cmd.loggingActor.tell(new LoggingActor.LogEntry(
                    "LLM_ERROR",
                    "Failed to generate answer: " + e.getMessage(),
                    System.currentTimeMillis(),
                    nodeId
                ));
            }
            
            // OpenAI still failing after LLMService's retries: fall back to the
            // retrieved passages so the user still gets something useful
            double responseTimeSeconds = (System.currentTimeMillis() - ready.startTimeMs) / 1000.0;
            cmd.replyTo.tell(new LLMResponse(
                LLMService.fallbackAnswer(cmd.contextChunks), responseTimeSeconds, nodeId));
            System.out.println("[LLM-ACTOR] Replied with retrieved passages (fallback)");
            System.out.println("---");
        }
        
        return this;
    }
}


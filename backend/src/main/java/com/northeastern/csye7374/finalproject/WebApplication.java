package com.northeastern.csye7374.finalproject;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spring Boot Application for REST API
 * 
 * Starts web server on port 8080
 * Usage: mvn spring-boot:run
 * 
 * @author Roshan Shetty & Rithwik
 */
@SpringBootApplication
public class WebApplication {

    private static final Logger log = LoggerFactory.getLogger(WebApplication.class);

    public static void main(String[] args) {
        log.info("===");
        log.info("       COURSE Q&A SYSTEM - WEB API                            ");
        log.info("       Starting Spring Boot Server...                         ");
        log.info("===");
        
        SpringApplication.run(WebApplication.class, args);
        
        log.info("");
        log.info("===");
        log.info("   SERVER READY!                                            ");
        log.info("                                                              ");
        log.info("  API Endpoints:                                              ");
        log.info("    POST http://localhost:8080/api/ask                        ");
        log.info("    GET  http://localhost:8080/api/health                     ");
        log.info("    GET  http://localhost:8080/api/info                       ");
        log.info("                                                              ");
        log.info("  Frontend: http://localhost:3000 (after npm run dev)         ");
        log.info("===");
    }
}

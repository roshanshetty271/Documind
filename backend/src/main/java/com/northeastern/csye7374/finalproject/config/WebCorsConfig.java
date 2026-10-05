package com.northeastern.csye7374.finalproject.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for the REST API: only the configured origins may call /api/**
 * from a browser (default: the local Vite dev server).
 *
 * Set documind.cors.allowed-origins (or the DOCUMIND_CORS_ALLOWED_ORIGINS
 * environment variable) to a comma-separated list to change it.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public WebCorsConfig(
            @Value("${documind.cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}")
            String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins)
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("Content-Type");
    }
}

package com.northeastern.csye7374.finalproject.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CORS must only allow the configured origins, never "*".
 */
class WebCorsConfigTest {

    /** Exposes the registry's protected result. */
    static class InspectableRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configs() {
            return getCorsConfigurations();
        }
    }

    private static CorsConfiguration apiCors(String... origins) {
        InspectableRegistry registry = new InspectableRegistry();
        new WebCorsConfig(origins).addCorsMappings(registry);
        CorsConfiguration config = registry.configs().get("/api/**");
        assertNotNull(config, "no CORS mapping for /api/**");
        return config;
    }

    @Test
    void allowsOnlyConfiguredOrigins() {
        CorsConfiguration config = apiCors("http://localhost:3000", "http://127.0.0.1:3000");

        assertEquals("http://localhost:3000", config.checkOrigin("http://localhost:3000"));
        assertEquals("http://127.0.0.1:3000", config.checkOrigin("http://127.0.0.1:3000"));
        assertNull(config.checkOrigin("https://evil.example"));
        assertNull(config.checkOrigin("http://localhost:8081"));
        assertFalse(config.getAllowedOrigins().contains("*"));
    }

    @Test
    void defaultOriginsInPropertiesAreTheLocalDevServer() throws Exception {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            props.load(in);
        }
        String value = props.getProperty("documind.cors.allowed-origins");
        assertNotNull(value);
        assertTrue(value.contains("http://localhost:3000"), value);
        assertFalse(value.contains("*"), value);
    }
}

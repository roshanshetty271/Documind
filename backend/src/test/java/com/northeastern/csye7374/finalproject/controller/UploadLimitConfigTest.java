package com.northeastern.csye7374.finalproject.controller;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring's multipart limits must not be smaller than the controller's own
 * 10MB check, otherwise files the controller accepts are rejected first.
 */
class UploadLimitConfigTest {

    private static Properties applicationProperties() throws Exception {
        Properties props = new Properties();
        try (InputStream in = UploadLimitConfigTest.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            assertNotNull(in, "application.properties not on classpath");
            props.load(in);
        }
        return props;
    }

    @Test
    void multipartLimitsAreTenMegabytes() throws Exception {
        Properties props = applicationProperties();

        DataSize maxFile = DataSize.parse(props.getProperty("spring.servlet.multipart.max-file-size"));
        DataSize maxRequest = DataSize.parse(props.getProperty("spring.servlet.multipart.max-request-size"));

        assertEquals(FileUploadController.MAX_FILE_SIZE, maxFile.toBytes());
        assertEquals(FileUploadController.MAX_FILE_SIZE, maxRequest.toBytes());
    }
}

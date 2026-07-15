package org.json_kula.valem.api;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only Spring Boot bootstrap for {@code valem-api}.
 *
 * <p>{@code valem-api} is a headless library — it ships no {@code main} and no
 * {@code @SpringBootApplication} (the runnable entry point lives in {@code valem-web}). Its
 * slice/integration tests use bare {@code @SpringBootTest}, which searches the package tree for a
 * {@code @SpringBootConfiguration}; this class provides one, scanning the {@code ...api} package
 * exactly as the former production {@code ValemApplication} did. It is compiled only into test
 * classes and never shipped.
 */
@SpringBootApplication
public class ApiTestApplication {
    // No main(): this exists only to anchor @SpringBootTest's configuration search.
}

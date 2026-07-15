package org.json_kula.valem.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The runnable open web deployable: the Spring Boot entry point that serves the Valem REST +
 * WebSocket API (and, once the UI is extracted in Phase 4, the bundled management SPA at {@code /}).
 *
 * <p>All controllers, config, and beans live in the headless {@code valem-api} library; this
 * class only owns {@code main()} and the executable-jar packaging. It component-scans the whole
 * {@code org.json_kula.valem} tree so every {@code @Configuration} in {@code valem-api}
 * (and any effect-kind / persistence adapter on the classpath) is picked up without per-jar imports.
 *
 * <p>Persistence is a-la-carte (ADR-0011): memory + filesystem work out of the box; adding a backend
 * is "drop the adapter jar on the classpath + set {@code valem.storage.*}".
 */
@SpringBootApplication(scanBasePackages = "org.json_kula.valem")
public class ValemWebApplication {

    public static void main(String[] args) {
        SpringApplication.run(ValemWebApplication.class, args);
    }
}

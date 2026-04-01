package ai.kompile.e2e;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Lightweight Spring Boot application for E2E tests.
 * Avoids MainApplication's heavy ND4J bootstrap while still
 * scanning all ai.kompile packages for conditional beans.
 */
@SpringBootApplication(scanBasePackages = "ai.kompile")
public class E2eTestApplication {
}

package ai.kompile.staging.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingSecurityPropertiesTest {

    @Test
    void loopbackDefaultsDoNotRequireLanCredentials() {
        StagingSecurityProperties properties =
                new StagingSecurityProperties("127.0.0.1");

        assertDoesNotThrow(properties::afterPropertiesSet);
        assertFalse(properties.isLanMode());
    }

    @Test
    void nonLoopbackBindingAutomaticallyRequiresLanSecurity() {
        StagingSecurityProperties properties =
                new StagingSecurityProperties("0.0.0.0");

        assertTrue(properties.isLanMode());
        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void explicitLanModeRejectsShortTokens() {
        StagingSecurityProperties properties =
                new StagingSecurityProperties("127.0.0.1");
        properties.setLanEnabled(true);
        properties.setApiToken("too-short");
        properties.setAllowedOrigins(List.of("http://192.168.1.20:8090"));

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void lanModeRequiresAtLeastOneExactOrigin() {
        StagingSecurityProperties properties =
                new StagingSecurityProperties("0.0.0.0");
        properties.setApiToken("a".repeat(StagingSecurityProperties.MINIMUM_TOKEN_BYTES));

        assertThrows(IllegalStateException.class, properties::afterPropertiesSet);
    }

    @Test
    void rejectsOriginsWithPathsQueriesOrCredentials() {
        for (String origin : List.of(
                "http://host.example/app",
                "http://host.example?next=app",
                "http://user@host.example")) {
            StagingSecurityProperties properties =
                    new StagingSecurityProperties("0.0.0.0");
            properties.setApiToken(
                    "a".repeat(StagingSecurityProperties.MINIMUM_TOKEN_BYTES));
            properties.setAllowedOrigins(List.of(origin));

            assertThrows(
                    IllegalArgumentException.class,
                    properties::afterPropertiesSet,
                    origin);
        }
    }

    @Test
    void normalizesExactOriginsAndMatchesOnlyHeaderTokenValue() {
        String token = "0123456789abcdef".repeat(2);
        StagingSecurityProperties properties =
                new StagingSecurityProperties("0.0.0.0");
        properties.setApiToken(token);
        properties.setAllowedOrigins(List.of("HTTPS://Example.COM:443"));

        assertDoesNotThrow(properties::afterPropertiesSet);
        assertTrue(properties.isAllowedOrigin("https://example.com"));
        assertTrue(properties.matchesApiToken(token));
        assertFalse(properties.matchesApiToken(token + "x"));
        assertFalse(properties.matchesApiToken(null));
    }
}

package ai.kompile.cli.common.auth;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OAuthCredentialLifecycleTest {
    @Test
    void refreshesExpiringCredentialAndPreservesProviderResult() throws Exception {
        ManagedCredential current = ManagedCredential.oauth("old", "refresh", 1_000L);

        ManagedCredential refreshed = OAuthCredentialLifecycle.resolve(
                current,
                500L,
                750L,
                ignored -> ManagedCredential.oauth(
                        "new",
                        "rotated",
                        5_000L,
                        Map.of("accountId", "account-1")));

        assertEquals("new", refreshed.getAccess());
        assertEquals("rotated", refreshed.getRefresh());
        assertEquals("account-1", refreshed.getMetadata("accountId"));
    }

    @Test
    void doesNotRefreshValidOrNonRefreshableCredentials() throws Exception {
        AtomicInteger refreshes = new AtomicInteger();
        var refresher = (OAuthCredentialLifecycle.OAuthRefresher) current -> {
            refreshes.incrementAndGet();
            return current;
        };

        ManagedCredential valid = ManagedCredential.oauth("valid", "refresh", 5_000L);
        ManagedCredential permanent = ManagedCredential.oauth("permanent", "", Long.MAX_VALUE);

        assertSame(valid, OAuthCredentialLifecycle.resolve(valid, 500L, 1_000L, refresher));
        assertSame(permanent,
                OAuthCredentialLifecycle.resolve(permanent, Long.MAX_VALUE, 1_000L, refresher));
        assertEquals(0, refreshes.get());
    }

    @Test
    void rejectsInvalidRefreshResult() {
        ManagedCredential current = ManagedCredential.oauth("old", "refresh", 1_000L);

        IOException error = assertThrows(IOException.class, () ->
                OAuthCredentialLifecycle.refresh(current, ignored -> ManagedCredential.apiKey("key")));

        assertTrue(error.getMessage().contains("OAuth credential"));
    }

    @Test
    void normalizesBlankRefreshTokenForRevocation() throws Exception {
        ManagedCredential current = ManagedCredential.oauth("access", "", Long.MAX_VALUE);

        boolean revoked = OAuthCredentialLifecycle.revoke(current, (access, refresh) -> {
            assertEquals("access", access);
            assertNull(refresh);
            return true;
        });

        assertTrue(revoked);
    }
}

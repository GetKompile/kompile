package ai.kompile.cli.main.auth;

import ai.kompile.cli.common.auth.ManagedCredential;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CredentialStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void storesListsAndDeletesCredentialsWithoutExposingSecrets() throws Exception {
        Path authPath = tempDir.resolve(".kompile").resolve("auth.json");
        CredentialStore store = new CredentialStore(authPath);

        store.putApiKey("OpenAI", "sk-test-secret");
        store.putOAuth("anthropic", "access-secret", "refresh-secret",
                System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));

        assertEquals("sk-test-secret", store.read("openai").getKey());
        assertEquals(ManagedCredential.OAUTH, store.read("ANTHROPIC").getType());
        assertEquals(2, store.list().size());
        assertTrue(store.list().stream().noneMatch(info ->
                info.toString().contains("sk-test-secret")
                        || info.toString().contains("access-secret")
                        || info.toString().contains("refresh-secret")));

        String json = Files.readString(authPath);
        assertTrue(json.contains("\"type\" : \"api_key\""));
        assertTrue(json.contains("\"type\" : \"oauth\""));

        if (Files.getFileStore(authPath).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(authPath));
            assertEquals(Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(authPath.getParent()));
        }

        assertTrue(store.delete("openai"));
        assertFalse(store.delete("openai"));
        assertNull(store.read("openai"));
        assertNotNull(store.read("anthropic"));
    }

    @Test
    void resolvesEnvironmentReferencesWithoutExecutingCommands() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("auth.json"));
        store.putApiKey("openai", "$OPENAI_API_KEY");

        assertEquals("from-env", store.resolveApiKey("openai",
                name -> "OPENAI_API_KEY".equals(name) ? "from-env" : null));

        store.putApiKey("anthropic", "!security find-generic-password");
        assertEquals("!security find-generic-password",
                store.resolveApiKey("anthropic", name -> "should-not-run"));
    }

    @Test
    void expiredOAuthRefreshIsSerializedAcrossStoreInstances() throws Exception {
        Path authPath = tempDir.resolve("refresh").resolve("auth.json");
        CredentialStore first = new CredentialStore(authPath);
        CredentialStore second = new CredentialStore(authPath);
        first.putOAuth("openai", "old-access", "old-refresh", System.currentTimeMillis() - 1_000L);

        AtomicInteger refreshes = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var refresh = (CredentialStore.OAuthRefresher) current -> {
                refreshes.incrementAndGet();
                try {
                    Thread.sleep(1_250L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", e);
                }
                return ManagedCredential.oauth(
                        "new-access",
                        "new-refresh",
                        System.currentTimeMillis() + TimeUnit.HOURS.toMillis(1));
            };
            var one = executor.submit(() -> {
                start.await();
                return first.resolveOAuth("openai", TimeUnit.MINUTES.toMillis(5), refresh);
            });
            var two = executor.submit(() -> {
                start.await();
                return second.resolveOAuth("openai", TimeUnit.MINUTES.toMillis(5), refresh);
            });

            start.countDown();
            assertEquals("new-access", one.get(5, TimeUnit.SECONDS).getAccess());
            assertEquals("new-access", two.get(5, TimeUnit.SECONDS).getAccess());
            assertEquals(1, refreshes.get(), "only one locked refresh should run");
            assertEquals("new-refresh", first.read("openai").getRefresh());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void preservesOauthMetadataAndAllowsPermanentOauthMintedKeys() throws Exception {
        Path authPath = tempDir.resolve("metadata").resolve("auth.json");
        CredentialStore store = new CredentialStore(authPath);
        ManagedCredential credential = ManagedCredential.oauth(
                "permanent-access",
                "",
                Long.MAX_VALUE,
                java.util.Map.of(
                        "accountId", "account-123",
                        "gateway", "https://gateway.test"));

        store.put("openrouter", credential);

        ManagedCredential loaded = store.read("openrouter");
        assertEquals("", loaded.getRefresh());
        assertEquals("account-123", loaded.getMetadata("accountId"));
        assertEquals("https://gateway.test", loaded.getMetadata("gateway"));
        assertEquals(credential, loaded);
    }

    @Test
    void storesMultipleNamedCredentialsAndSwitchesTheActiveCredential() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("profiles").resolve("auth.json"));

        store.putApiKey("OpenAI", "personal", "personal-secret", true);
        store.putApiKey("openai", "work", "work-secret", false);

        assertEquals("personal", store.activeCredentialName("OPENAI"));
        assertEquals("personal-secret", store.read("openai").getKey());
        assertEquals("work-secret", store.read("openai", "WORK").getKey());
        assertEquals(2, store.list("openai").size());
        assertTrue(store.list("openai").stream()
                .filter(CredentialStore.CredentialInfo::active)
                .allMatch(info -> "personal".equals(info.credentialName())));

        assertTrue(store.switchCredential("openai", "work"));
        assertEquals("work", store.activeCredentialName("openai"));
        assertEquals("work-secret", store.resolveApiKey("openai", name -> null));

        assertTrue(store.deleteCredential("openai", "work"));
        assertEquals("personal", store.activeCredentialName("openai"));
        assertEquals("personal-secret", store.read("openai").getKey());
        assertFalse(store.switchCredential("openai", "missing"));
    }

    @Test
    void legacyProviderCredentialsMigrateToVersionedNamedProfilesOnMutation() throws Exception {
        Path authPath = tempDir.resolve("legacy").resolve("auth.json");
        Files.createDirectories(authPath.getParent());
        Files.writeString(authPath, """
                {
                  "openai": {"type": "api_key", "key": "legacy-secret"}
                }
                """);
        CredentialStore store = new CredentialStore(authPath);

        assertEquals("legacy-secret", store.read("openai").getKey());
        assertEquals("default", store.activeCredentialName("openai"));

        store.putApiKey("openai", "work", "work-secret", false);

        String migrated = Files.readString(authPath);
        assertTrue(migrated.contains("\"version\" : 2"));
        assertTrue(migrated.contains("\"providers\""));
        assertTrue(migrated.contains("\"credentials\""));
        assertEquals("legacy-secret", store.read("openai", "default").getKey());
        assertEquals("work-secret", store.read("openai", "work").getKey());
        assertEquals("default", store.activeCredentialName("openai"));
    }

    @Test
    void deletesOneProviderOrEveryProviderWithoutLeavingInvalidActivePointers() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("logout").resolve("auth.json"));
        store.putApiKey("openai", "personal", "one", true);
        store.putApiKey("openai", "work", "two", false);
        store.putApiKey("anthropic", "default", "three", true);

        assertTrue(store.delete("openai"));
        assertNull(store.read("openai"));
        assertEquals("three", store.read("anthropic").getKey());
        assertEquals(1, store.deleteAll());
        assertTrue(store.list().isEmpty());
        assertEquals(0, store.deleteAll());
    }

    @Test
    void repeatedLoginReusesIdentityAndNameWithoutChangingAnotherActiveAccount() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("identity.json"));
        var alice = java.util.Map.of("accountId", "alice", "organizationId", "org");
        var bob = java.util.Map.of("accountId", "bob", "organizationId", "org");
        store.put("anthropic", "personal", ManagedCredential.oauth("old", "r1", 1L, alice), true);
        store.put("anthropic", "work", ManagedCredential.oauth("bob", "rb", Long.MAX_VALUE, bob), true);
        ManagedCredential latest = store.put("anthropic", "account-3",
                ManagedCredential.oauth("new", "r2", Long.MAX_VALUE, alice), false);
        assertEquals(2, store.list("anthropic").size());
        assertEquals("work", store.activeCredentialName("anthropic"));
        assertEquals("new", store.read("anthropic", "personal").getAccess());
        assertNull(store.read("anthropic", "account-3"));
        assertEquals("personal", store.credentialName("anthropic", latest));
        store.put("anthropic", latest);
        assertEquals("personal", store.activeCredentialName("anthropic"));
        assertEquals(2, store.list("anthropic").size());
    }

    @Test
    void differentUsersTenantsAndUnknownOpaqueTokensStaySeparate() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("separate.json"));
        for (int i = 0; i < 3; i++) {
            store.put("openai-codex", ManagedCredential.oauth("token-" + i, "r-" + i, Long.MAX_VALUE,
                    java.util.Map.of("accountId", i == 2 ? "other-workspace" : "workspace",
                            "subject", i == 1 ? "other-user" : "user")));
        }
        assertEquals(3, store.list("openai-codex").size());
        store.put("openrouter", ManagedCredential.oauth("key-1", "", Long.MAX_VALUE));
        store.put("openrouter", ManagedCredential.oauth("key-2", "", Long.MAX_VALUE));
        store.put("openrouter", "alias", ManagedCredential.oauth("key-2", "", Long.MAX_VALUE), true);
        assertEquals(2, store.list("openrouter").size(), "blank refresh tokens are not identities");
        for (String issuer : java.util.List.of("https://issuer-a", "https://issuer-b")) {
            store.put("custom-oauth", ManagedCredential.oauth(issuer, issuer, Long.MAX_VALUE,
                    java.util.Map.of("subject", "shared-subject", "issuer", issuer)));
        }
        assertEquals(2, store.list("custom-oauth").size());
        for (String token : java.util.List.of("opaque-a", "opaque-b")) {
            store.put("openai-codex", ManagedCredential.oauth(token, token, Long.MAX_VALUE,
                    java.util.Map.of("accountId", "workspace-with-unknown-users")));
        }
        assertEquals(5, store.list("openai-codex").size(), "workspace alone is not a user identity");
    }

    @Test
    void reconcilesLegacyDuplicateJwtIdentitiesOnlyAfterSuccessfulRefresh() throws Exception {
        Path path = tempDir.resolve("old-duplicates.json");
        String old = jwt("user", "workspace", 1L);
        long liveSeconds = System.currentTimeMillis() / 1000L + 3600L;
        String live = jwt("user", "workspace", liveSeconds);
        Files.writeString(path, """
                {"version":2,"providers":{"openai-codex":{"active":"old-label","credentials":{
                  "old-label":{"type":"oauth","access":"%s","refresh":"old-r","expires":%d},
                  "account-2":{"type":"oauth","access":"%s","refresh":"new-r","expires":%d}
                }}}}
                """.formatted(old, Long.MAX_VALUE, live, Long.MAX_VALUE));
        CredentialStore store = new CredentialStore(path);
        assertEquals(2, store.list("openai-codex").size(),
                "expiry does not prove which refresh grant is newest");
        assertEquals("old-label", store.activeCredentialName("openai-codex"));
        assertEquals("old-r", store.read("openai-codex").getRefresh());
        store.resolveOAuth("openai-codex", 0L, current -> ManagedCredential.oauth(
                live, "confirmed-rotation", liveSeconds * 1000L, current.getMetadata()));
        assertEquals(1, store.list("openai-codex").size());
        assertEquals("confirmed-rotation", store.read("openai-codex").getRefresh());
        assertEquals(liveSeconds * 1000L, store.read("openai-codex").getExpires());
        assertFalse(Files.readString(path).contains("account-2"));
        assertEquals(1, new CredentialStore(path).list().size());
    }

    @Test
    void preservesDifferentAtlassianSitesEvenWithTheSameTokens() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("sites.json"));
        for (String site : java.util.List.of("site-a", "site-b")) {
            store.put("atlassian", site, ManagedCredential.oauth("access", "refresh", Long.MAX_VALUE,
                    java.util.Map.of("subject", "user", "cloudId", site)), true);
        }
        assertEquals(2, store.list("atlassian").size());
    }

    @Test
    void expiredIdentityAppearsInStatusWithoutTokenMaterial() throws Exception {
        CredentialStore store = new CredentialStore(tempDir.resolve("status.json"));
        store.put("anthropic", ManagedCredential.oauth("secret-access", "", 1L,
                java.util.Map.of("accountId", "account", "email", "alice@example.test\n\u001b")));
        var info = store.list().get(0);
        assertTrue(info.status().contains("expired; sign in again"));
        assertEquals("alice@example.test", info.identity());
        assertFalse(info.toString().contains("secret-access"));
        assertFalse(info.displayLabel().contains("\n"));
    }

    private static String jwt(String user, String account, long expiry) {
        String payload = """
                {"sub":"%s","iss":"https://auth.openai.com","exp":%d,
                 "https://api.openai.com/auth":{"chatgpt_account_id":"%s"}}
                """.formatted(user, expiry, account);
        return "e30." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".signature";
    }

    @Test
    void rejectsMalformedCredentialFiles() throws Exception {
        Path authPath = tempDir.resolve("broken").resolve("auth.json");
        Files.createDirectories(authPath.getParent());
        Files.writeString(authPath, "{\"openai\": {\"type\": \"oauth\", \"access\": \"only\"}}");

        CredentialStore store = new CredentialStore(authPath);

        IOException error = assertThrows(IOException.class, () -> store.read("openai"));
        assertTrue(error.getMessage().contains("provider openai"));
    }
}

package ai.kompile.oauth.service;

import org.junit.jupiter.api.*;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenEncryptionService")
class TokenEncryptionServiceTest {

    @org.junit.jupiter.api.io.TempDir
    Path tempDir;

    private TokenEncryptionService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new TokenEncryptionService();

        // Generate a test AES-256 key and inject via reflection (bypasses @Value)
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256, new SecureRandom());
        SecretKey testKey = keyGen.generateKey();
        String testKeyBase64 = Base64.getEncoder().encodeToString(testKey.getEncoded());

        Field configuredKeyField = TokenEncryptionService.class.getDeclaredField("configuredKey");
        configuredKeyField.setAccessible(true);
        configuredKeyField.set(service, testKeyBase64);

        service.init();
    }

    @Nested
    @DisplayName("Encrypt/Decrypt round-trip")
    class EncryptDecrypt {

        @Test
        @DisplayName("should round-trip a normal OAuth token")
        void roundTrip() {
            String original = "ya29.a0AfB_byC-test-access-token-12345";
            String encrypted = service.encrypt(original);

            assertNotNull(encrypted);
            assertNotEquals(original, encrypted);

            String decrypted = service.decrypt(encrypted);
            assertEquals(original, decrypted);
        }

        @Test
        @DisplayName("should handle long tokens")
        void longToken() {
            String original = "a".repeat(4096);
            String encrypted = service.encrypt(original);
            assertEquals(original, service.decrypt(encrypted));
        }

        @Test
        @DisplayName("should handle tokens with special characters")
        void specialCharacters() {
            String original = "token/with+special=chars&more!@#$%^&*()";
            String encrypted = service.encrypt(original);
            assertEquals(original, service.decrypt(encrypted));
        }

        @Test
        @DisplayName("should handle unicode content")
        void unicode() {
            String original = "token-with-unicode-\u00e9\u00f1\u00fc-and-\u65e5\u672c\u8a9e";
            String encrypted = service.encrypt(original);
            assertEquals(original, service.decrypt(encrypted));
        }
    }

    @Nested
    @DisplayName("Null and empty handling")
    class NullHandling {

        @Test
        @DisplayName("encrypt null returns null")
        void encryptNull() {
            assertNull(service.encrypt(null));
        }

        @Test
        @DisplayName("encrypt empty returns null")
        void encryptEmpty() {
            assertNull(service.encrypt(""));
        }

        @Test
        @DisplayName("decrypt null returns null")
        void decryptNull() {
            assertNull(service.decrypt(null));
        }

        @Test
        @DisplayName("decrypt empty returns null")
        void decryptEmpty() {
            assertNull(service.decrypt(""));
        }
    }

    @Nested
    @DisplayName("Security properties")
    class SecurityProperties {

        @Test
        @DisplayName("same plaintext produces different ciphertexts (random IV)")
        void randomIV() {
            String original = "test-token";
            String encrypted1 = service.encrypt(original);
            String encrypted2 = service.encrypt(original);

            assertNotEquals(encrypted1, encrypted2,
                    "Each encryption must use a unique random IV");

            assertEquals(original, service.decrypt(encrypted1));
            assertEquals(original, service.decrypt(encrypted2));
        }

        @Test
        @DisplayName("tampered ciphertext is rejected (GCM auth tag)")
        void tamperedCiphertext() {
            String encrypted = service.encrypt("test-token");

            byte[] decoded = Base64.getDecoder().decode(encrypted);
            decoded[decoded.length - 1] ^= 0xFF;
            String tampered = Base64.getEncoder().encodeToString(decoded);

            assertThrows(RuntimeException.class, () -> service.decrypt(tampered));
        }

        @Test
        @DisplayName("tampered IV is rejected")
        void tamperedIV() {
            String encrypted = service.encrypt("test-token");

            byte[] decoded = Base64.getDecoder().decode(encrypted);
            decoded[0] ^= 0xFF; // flip first byte of IV
            String tampered = Base64.getEncoder().encodeToString(decoded);

            assertThrows(RuntimeException.class, () -> service.decrypt(tampered));
        }

        @Test
        @DisplayName("wrong key cannot decrypt")
        void wrongKey() throws Exception {
            String encrypted = service.encrypt("test-token");

            TokenEncryptionService otherService = new TokenEncryptionService();
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, new SecureRandom());
            SecretKey otherKey = keyGen.generateKey();
            String otherKeyBase64 = Base64.getEncoder().encodeToString(otherKey.getEncoded());

            Field configuredKeyField = TokenEncryptionService.class.getDeclaredField("configuredKey");
            configuredKeyField.setAccessible(true);
            configuredKeyField.set(otherService, otherKeyBase64);
            otherService.init();

            assertThrows(RuntimeException.class, () -> otherService.decrypt(encrypted));
        }

        @Test
        @DisplayName("invalid base64 ciphertext is rejected")
        void invalidBase64() {
            assertThrows(RuntimeException.class, () -> service.decrypt("not-valid-base64!!!"));
        }

        @Test
        @DisplayName("truncated ciphertext is rejected")
        void truncatedCiphertext() {
            String encrypted = service.encrypt("test-token");
            String truncated = encrypted.substring(0, 10);

            assertThrows(RuntimeException.class, () -> service.decrypt(truncated));
        }
    }

    @Test
    @DisplayName("isInitialized returns true after init")
    void isInitialized() {
        assertTrue(service.isInitialized());
    }

    @Test
    @DisplayName("isInitialized returns false before init")
    void notInitialized() throws Exception {
        TokenEncryptionService uninitializedService = new TokenEncryptionService();
        assertFalse(uninitializedService.isInitialized());
    }

    @Test
    @DisplayName("separate processes reuse the atomically created file key")
    void fileKeyIsStableAcrossInitializers() throws Exception {
        TokenEncryptionService first = fileBackedService();
        TokenEncryptionService second = fileBackedService();

        first.init();
        String ciphertext = first.encrypt("shared-secret");
        second.init();

        assertEquals("shared-secret", second.decrypt(ciphertext));
    }

    @Test
    @DisplayName("existing file keys are repaired to owner-only permissions before reading")
    void existingKeyPermissionsAreRepaired() throws Exception {
        Path keyPath = tempDir.resolve("config/oauth-encryption.key");
        Files.createDirectories(keyPath.getParent());
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        Files.writeString(keyPath, Base64.getEncoder().encodeToString(keyBytes));
        try {
            Files.setPosixFilePermissions(keyPath, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException unsupported) {
            Assumptions.abort("POSIX permission assertions are not supported on this filesystem");
        }

        fileBackedService().init();

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(keyPath));
    }

    @Test
    @DisplayName("concurrent persona initialization publishes one shared file key")
    void concurrentInitializersShareOneKey() throws Exception {
        TokenEncryptionService first = fileBackedService();
        TokenEncryptionService second = fileBackedService();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var firstInit = executor.submit(() -> {
                start.await();
                first.init();
                return first;
            });
            var secondInit = executor.submit(() -> {
                start.await();
                second.init();
                return second;
            });
            start.countDown();

            TokenEncryptionService initializedFirst = firstInit.get(10, TimeUnit.SECONDS);
            TokenEncryptionService initializedSecond = secondInit.get(10, TimeUnit.SECONDS);
            assertEquals("shared-secret",
                    initializedSecond.decrypt(initializedFirst.encrypt("shared-secret")));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("forked personas converge on one file key")
    void forkedInitializersShareOneKey() throws Exception {
        Path signal = tempDir.resolve("start.signal");
        Path firstCiphertext = tempDir.resolve("first.token");
        Path secondCiphertext = tempDir.resolve("second.token");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty(
                "surefire.test.class.path", System.getProperty("java.class.path"));
        Process first = new ProcessBuilder(java, "-cp", classpath,
                ProcessProbe.class.getName(), tempDir.toString(), signal.toString(),
                firstCiphertext.toString(), "first-secret")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Process second = new ProcessBuilder(java, "-cp", classpath,
                ProcessProbe.class.getName(), tempDir.toString(), signal.toString(),
                secondCiphertext.toString(), "second-secret")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Files.createFile(signal);

        assertTrue(first.waitFor(20, TimeUnit.SECONDS));
        assertTrue(second.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, first.exitValue());
        assertEquals(0, second.exitValue());
        TokenEncryptionService reader = fileBackedService();
        reader.init();
        assertEquals("first-secret", reader.decrypt(Files.readString(firstCiphertext)));
        assertEquals("second-secret", reader.decrypt(Files.readString(secondCiphertext)));
    }

    public static final class ProcessProbe {
        private ProcessProbe() {
        }

        public static void main(String[] args) throws Exception {
            Path dataDir = Path.of(args[0]);
            Path signal = Path.of(args[1]);
            Path output = Path.of(args[2]);
            while (!Files.exists(signal)) Thread.sleep(5L);
            TokenEncryptionService service = new TokenEncryptionService();
            Field configured = TokenEncryptionService.class.getDeclaredField("configuredKey");
            configured.setAccessible(true);
            configured.set(service, "");
            Field configuredDataDir = TokenEncryptionService.class.getDeclaredField("kompileDataDir");
            configuredDataDir.setAccessible(true);
            configuredDataDir.set(service, dataDir.toString());
            service.init();
            Files.writeString(output, service.encrypt(args[3]), StandardCharsets.UTF_8);
        }
    }

    private TokenEncryptionService fileBackedService() throws Exception {
        TokenEncryptionService result = new TokenEncryptionService();
        Field configured = TokenEncryptionService.class.getDeclaredField("configuredKey");
        configured.setAccessible(true);
        configured.set(result, "");
        Field dataDir = TokenEncryptionService.class.getDeclaredField("kompileDataDir");
        dataDir.setAccessible(true);
        dataDir.set(result, tempDir.toString());
        return result;
    }
}

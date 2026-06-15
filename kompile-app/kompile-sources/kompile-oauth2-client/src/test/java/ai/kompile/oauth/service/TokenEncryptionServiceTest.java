package ai.kompile.oauth.service;

import org.junit.jupiter.api.*;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenEncryptionService")
class TokenEncryptionServiceTest {

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
}

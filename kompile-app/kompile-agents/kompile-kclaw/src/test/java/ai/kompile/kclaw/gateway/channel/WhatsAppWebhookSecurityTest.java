package ai.kompile.kclaw.gateway.channel;

import ai.kompile.cli.common.util.JsonUtils;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class WhatsAppWebhookSecurityTest {

    @Test
    void verifiesMetaHmacAgainstTheExactRawPayload() throws Exception {
        DefaultWhatsAppApiClient client = new DefaultWhatsAppApiClient(
                mock(HttpClient.class), JsonUtils.standardMapper());
        client.start("access", "phone", "verify", "app-secret");
        byte[] payload = "{\"entry\":[]}".getBytes(StandardCharsets.UTF_8);

        assertTrue(client.verifyWebhookSignature(payload, signature(payload, "app-secret")));
        assertFalse(client.verifyWebhookSignature(payload, signature(payload, "other-secret")));
        assertFalse(client.verifyWebhookSignature("{}".getBytes(StandardCharsets.UTF_8),
                signature(payload, "app-secret")));
        assertFalse(client.verifyWebhookSignature(payload, null));
    }

    private static String signature(byte[] payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
    }
}

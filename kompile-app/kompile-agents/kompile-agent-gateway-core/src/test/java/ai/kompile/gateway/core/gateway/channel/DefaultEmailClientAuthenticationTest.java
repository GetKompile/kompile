package ai.kompile.gateway.core.gateway.channel;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultEmailClientAuthenticationTest {

    private final DefaultEmailClient client = new DefaultEmailClient();

    @Test
    void acceptsOnlyAlignedDmarcAuthenticationResults() throws Exception {
        MimeMessage aligned = message("operator@example.com");
        aligned.setHeader("Authentication-Results",
                "mx.example.net; dkim=pass; spf=pass; dmarc=pass header.from=example.com");
        MimeMessage mismatch = message("operator@example.com");
        mismatch.setHeader("Authentication-Results",
                "mx.example.net; dmarc=pass header.from=attacker.example");
        MimeMessage mixedClauses = message("operator@example.com");
        mixedClauses.setHeader("Authentication-Results",
                "mx.example.net; dmarc=fail header.from=example.com; "
                        + "dmarc=pass header.from=attacker.example");

        assertTrue(client.authenticatedSender(aligned, "operator@example.com", "mx.example.net"));
        assertFalse(client.authenticatedSender(aligned, "operator@example.com", "other.example.net"));
        assertFalse(client.authenticatedSender(mismatch, "operator@example.com", "mx.example.net"));
        assertFalse(client.authenticatedSender(
                mixedClauses, "operator@example.com", "mx.example.net"));
        assertFalse(client.authenticatedSender(
                message("operator@example.com"), "operator@example.com", "mx.example.net"));
    }

    private static MimeMessage message(String from) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress(from));
        return message;
    }
}

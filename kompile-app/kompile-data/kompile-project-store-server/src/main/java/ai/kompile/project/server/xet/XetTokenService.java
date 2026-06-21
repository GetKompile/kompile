package ai.kompile.project.server.xet;

import ai.kompile.project.server.ProjectStoreServerProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;

/**
 * Mints and verifies short-lived, repo+ref+scope-scoped CAS tokens, HMAC-SHA256 signed with a server
 * secret. A lightweight stand-in for the Hugging Face Hub's Xet token: no external identity provider,
 * appropriate for a self-hosted single-realm server (kompile has no Spring Security).
 */
@Service
public class XetTokenService {

    private final ProjectStoreServerProperties props;

    public XetTokenService(ProjectStoreServerProperties props) {
        this.props = props;
    }

    /** Verified token claims. */
    public static final class Claims {
        public final String repo;
        public final String ref;
        public final String scope;
        public final long exp;

        Claims(String repo, String ref, String scope, long exp) {
            this.repo = repo;
            this.ref = ref;
            this.scope = scope;
            this.exp = exp;
        }

        public boolean isWrite() {
            return "write".equalsIgnoreCase(scope);
        }
    }

    public long ttlSeconds() {
        return props.getTokenTtlSeconds();
    }

    /** Mint a token expiring at {@code expEpochSec}. */
    public String mint(String repo, String ref, String scope, long expEpochSec) {
        byte[] payload = (repo + "\n" + ref + "\n" + scope + "\n" + expEpochSec).getBytes(StandardCharsets.UTF_8);
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        String s = Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(payload));
        return p + "." + s;
    }

    /** Verify a token (with or without a {@code Bearer } prefix), returning its claims if valid + unexpired. */
    public Optional<Claims> verify(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String t = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();
        int dot = t.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(t.substring(0, dot));
            byte[] sig = Base64.getUrlDecoder().decode(t.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, hmac(payload))) {
                return Optional.empty();
            }
            String[] parts = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            if (parts.length != 4) {
                return Optional.empty();
            }
            long exp = Long.parseLong(parts[3]);
            if (exp < System.currentTimeMillis() / 1000L) {
                return Optional.empty();
            }
            return Optional.of(new Claims(parts[0], parts[1], parts[2], exp));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC computation failed", e);
        }
    }
}

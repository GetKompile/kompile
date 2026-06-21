package ai.kompile.project.server;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration for the project store server (prefix {@code kompile.project-store-server}).
 */
@Component
@ConfigurationProperties(prefix = "kompile.project-store-server")
@Getter
@Setter
public class ProjectStoreServerProperties {

    /** Filesystem root for bare git repos and Xet blobs. */
    private String dataDir = System.getProperty("user.home") + "/.kompile/project-store-server";

    /** Base URL advertised to Xet clients in the Hub token response; clients append {@code /v1/...}. */
    private String casUrl = "http://localhost:8088/xet/cas";

    /** Base URL used to build git clone URLs in project responses. */
    private String gitBase = "http://localhost:8088/git";

    /** Reject shard uploads whose term verification hashes don't match the claimed chunk hashes. */
    private boolean verifyUploads = true;

    /** Lifetime (seconds) of a minted CAS token. */
    private long tokenTtlSeconds = 3600;

    /** Secret used to HMAC-sign CAS tokens. */
    private String tokenSecret = "kompile-project-store-dev-secret";
}

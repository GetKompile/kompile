/*
 * Copyright 2026 Kompile Inc.
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.auth.oauth;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves the GitHub Copilot device-flow client id at runtime instead of shipping it.
 *
 * <p>GitHub's device flow cannot start without a client id (an unknown id is rejected
 * with HTTP 404 by {@code github.com/login/device/code}), and only Copilot-entitled
 * OAuth applications work with the Copilot token exchange — ids a subscriber cannot
 * register themselves. GitHub publishes the working id inside its own open-source
 * Copilot client (the language-server bundle vendored in {@code github/copilot.vim}),
 * so the resolution chain treats GitHub itself as the source of record:</p>
 *
 * <ol>
 *   <li>an explicitly configured id (test seam or pinned override);</li>
 *   <li>the {@code KOMPILE_GITHUB_COPILOT_CLIENT_ID} environment;</li>
 *   <li>the {@code oauth-clients.json} store (a previous pull or a manual entry) — its id
 *       is validated against the device endpoint first, and a stale id that GitHub no
 *       longer accepts is purged so the next rung self-heals;</li>
 *   <li>GitHub's own published client, fetched at run time and validated the same way
 *       (a hit is cached to the store so later logins work offline);</li>
 *   <li>the documented fallback id — used only when it validates, never persisted;</li>
 *   <li>prompting the user through the login's interaction; the answer is validated
 *       before a doomed pairing can start and then persisted.</li>
 * </ol>
 *
 * <p>Validation probes {@code POST <domain>/login/device/code} and discards the issued
 * device code: VALID means GitHub answered a real device-code JSON response, INVALID
 * means HTTP 404 (the id names no registered application), INCONCLUSIVE means the
 * transport refused or answered anything else — the flow's own device start decides
 * those. No probe sends secrets, and no pairing happens during a probe.</p>
 */
public final class CopilotClientIdResolver {

    /**
     * The last-resort default: the same id GitHub's own published client uses. Never
     * written to the store; a hit keeps this login moving when the artifact could not
     * be fetched, while later logins re-attempt the artifact first.
     */
    public static final String DOCUMENTED_FALLBACK_CLIENT_ID = "Iv1.b507a08c87ecfe98";

    /**
     * Candidate artifacts that publish the integration id. The release branch ships the
     * copilot-language-server bundle; main covers the window around branch flips.
     */
    private static final List<String> ARTIFACT_URLS = List.of(
            "https://raw.githubusercontent.com/github/copilot.vim/release/copilot-language-server/dist/main.js",
            "https://raw.githubusercontent.com/github/copilot.vim/main/copilot-language-server/dist/main.js");

    /** GitHub's OAuth-app id shape: {@code Iv1.} + base-62 body (observed length 16). */
    private static final Pattern CLIENT_ID = Pattern.compile(
            "(?<![A-Za-z0-9])Iv1\\.[0-9A-Za-z]{12,24}(?![0-9A-Za-z])");

    /** A cap so a hostile or rewritten artifact cannot blow the heap. */
    private static final int MAX_ARTIFACT_BYTES = 8 * 1024 * 1024;
    /** Bound probing when an artifact would name several app ids. */
    private static final int MAX_CANDIDATES_PER_ARTIFACT = 3;

    private final OAuthSupport.HttpTransport http;

    public CopilotClientIdResolver(OAuthSupport.HttpTransport http) {
        this.http = http == null ? OAuthSupport.defaultTransport() : http;
    }

    /** Default transport for callers outside this package (AuthCommand pre-resolve). */
    public static CopilotClientIdResolver create() {
        return new CopilotClientIdResolver(OAuthSupport.defaultTransport());
    }

    enum Source { INJECTED, ENVIRONMENT, STORE, GITHUB_ARTIFACT, DOCUMENTED_FALLBACK, PROMPTED }

    record Resolved(String clientId, Source source) {
    }

    /**
     * Full chain. {@code interaction == null} keeps the chain non-interactive (env,
     * store, artifact; the final rung reports what is missing). Returns the client id
     * and the rung that produced it, or throws with guidance naming the remaining
     * sources.
     */
    Resolved resolve(
            String configuredClientId,
            String providerId,
            String envVar,
            String displayName,
            String domain,
            OAuthProviderFlow.Interaction interaction) throws IOException {
        URI deviceStart = deviceStartUri(domain);
        StringBuilder missed = new StringBuilder();

        // 1. Explicitly configured value (test seam or pinned override).
        String configured = trim(configuredClientId);
        if (configured != null) {
            if (probe(configured, deviceStart) == Probe.INVALID) {
                throw new IOException("The configured GitHub Copilot client id '" + configured
                        + "' was rejected by the GitHub device endpoint (HTTP 404): it does "
                        + "not name a registered OAuth application. Check the value, then "
                        + "re-run `kompile auth login " + providerId + "`.");
            }
            return new Resolved(configured, Source.INJECTED);
        }

        // 2. The environment's pin (KOMPILE_GITHUB_COPILOT_CLIENT_ID).
        String envValue = trim(System.getenv(envVar));
        if (envValue != null) {
            if (probe(envValue, deviceStart) == Probe.INVALID) {
                throw new IOException("The " + envVar + " client id '" + envValue
                        + "' was rejected by the GitHub device endpoint (HTTP 404): it does "
                        + "not name a registered OAuth application. Check the value or "
                        + "clear the variable.");
            }
            return new Resolved(envValue, Source.ENVIRONMENT);
        }

        try {
            OAuthClientSettings settings = OAuthClientSettings.create();
            // 3. A stored id: validated here so a stale one is purged, not paired.
            OAuthClientSettings.ClientCredentials stored = settings.read(providerId);
            if (stored != null && stored.clientId() != null && !stored.clientId().isBlank()) {
                if (probe(stored.clientId(), deviceStart) != Probe.INVALID) {
                    return new Resolved(stored.clientId().trim(), Source.STORE);
                }
                removeQuietly(settings, providerId);
                missed.append("the stored client id (").append(stored.clientId())
                        .append(") no longer exists at GitHub; ");
            }

            // 4. GitHub's own published client: fetch, extract, validate, cache.
            String fetched = fromGitHubArtifacts(deviceStart, missed);
            if (fetched != null) {
                cacheQuietly(settings, providerId, fetched);
                return new Resolved(fetched, Source.GITHUB_ARTIFACT);
            }

            // 5. The documented default, only when GitHub still accepts it. Not
            // persisted: later logins must re-attempt the artifact rather than
            // shadow a reversal.
            Probe fallback = probe(DOCUMENTED_FALLBACK_CLIENT_ID, deviceStart);
            if (fallback == Probe.VALID) {
                return new Resolved(DOCUMENTED_FALLBACK_CLIENT_ID, Source.DOCUMENTED_FALLBACK);
            }
            if (fallback == Probe.INVALID) {
                missed.append("the documented Copilot client id was rejected by GitHub; ");
            }
        } catch (IOException unreadable) {
            // An unreadable store must not block the artifact and prompt rungs.
            missed.append("the oauth-clients store was unreadable (")
                    .append(unreadable.getMessage()).append("); ");
        }

        // 6. The user's own id, validated before the browser pairing, then persisted.
        if (interaction == null) {
            throw new IOException(displayName + " login needs an OAuth client id. "
                    + "Run `kompile auth login " + providerId + "` and follow the prompt, or set "
                    + envVar + "."
                    + (missed.isEmpty()
                    ? "" : " Automated resolution did not apply: " + missed.toString().strip()));
        }
        String entered = trim(interaction.prompt(displayName
                + " OAuth client id (public client, no secret; Enter cancels): "));
        if (entered == null) {
            throw new IOException(displayName + " login was cancelled without a client id. "
                    + "Set " + envVar + " or run `kompile auth login " + providerId + "` later."
                    + (missed.isEmpty()
                    ? "" : " Automated resolution did not apply: " + missed.toString().strip()));
        }
        if (probe(entered, deviceStart) == Probe.INVALID) {
            throw new IOException(displayName + " client id '" + entered
                    + "' was rejected by the GitHub device endpoint (HTTP 404): it does "
                    + "not name a registered OAuth application, so no pairing can start. "
                    + "Check the entry or leave it blank to cancel.");
        }
        try {
            OAuthClientSettings.create().put(providerId,
                    new OAuthClientSettings.ClientCredentials(entered, null, null));
        } catch (IOException persistFailed) {
            // The entered id still works for this login; the next one prompts again.
        }
        return new Resolved(entered, Source.PROMPTED);
    }

    /**
     * Non-interactive attempt for {@code kompile auth login}: resolves and caches the
     * id when an automatic rung applies (true), or reports nothing usable (false)
     * leaving the command's own prompt in charge.
     */
    public static boolean preResolveForCommand(String providerId, String envVar) {
        StringBuilder missed = new StringBuilder();
        try {
            OAuthClientSettings settings = OAuthClientSettings.create();
            OAuthClientSettings.ClientCredentials stored = settings.read(providerId);
            if (stored != null && stored.clientId() != null && !stored.clientId().isBlank()) {
                return true;
            }
            if (trim(System.getenv(envVar)) != null) {
                return true;
            }
            String fetched = new CopilotClientIdResolver(OAuthSupport.defaultTransport())
                    .fromGitHubArtifacts(deviceStartUri("github.com"), missed);
            if (fetched == null) {
                return false;
            }
            settings.put(providerId, new OAuthClientSettings.ClientCredentials(fetched, null, null));
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    /** Best-effort removal of a stored client id (the flow's pairing-time self-heal). */
    void purgeStored(String providerId) {
        try {
            OAuthClientSettings.create().remove(providerId);
        } catch (IOException ignored) {
            // A stale entry that survives removal still fails fast at the device start.
        }
    }

    private static URI deviceStartUri(String domain) throws IOException {
        return OAuthSupport.trustedHttpUri(
                "https://" + domain + "/login/device/code", true, "GitHub device authorization");
    }

    /**
     * Fetch and validate the id that GitHub publishes in its own client. One automatic
     * device-authorize probe per candidate; extraction is structural ({@code Iv1.}
     * literals in a minified bundle), not name-dependent.
     */
    private String fromGitHubArtifacts(URI deviceStart, StringBuilder missed) throws IOException {
        for (String url : ARTIFACT_URLS) {
            String bundle = fetch(url, missed);
            if (bundle == null) {
                continue;
            }
            Set<String> candidates = extract(bundle);
            if (candidates.isEmpty()) {
                missed.append("the published GitHub client (").append(url)
                        .append(") no longer names a Copilot app id; ");
                continue;
            }
            int probed = 0;
            for (String candidate : candidates) {
                if (probed >= MAX_CANDIDATES_PER_ARTIFACT) {
                    break;
                }
                probed++;
                if (probe(candidate, deviceStart) == Probe.VALID) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private String fetch(String url, StringBuilder missed) {
        try {
            OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                    "GET", URI.create(url),
                    Map.of("User-Agent", GitHubCopilotOAuthFlow.USER_AGENT), null));
            if (!response.success()
                    || response.body() == null
                    || response.body().length() > MAX_ARTIFACT_BYTES) {
                missed.append("the published GitHub client (").append(url)
                        .append(") answered HTTP ").append(response.status()).append("; ");
                return null;
            }
            return response.body();
        } catch (IOException failure) {
            missed.append("the published GitHub client (").append(url)
                    .append(") was unreachable (").append(failure.getMessage()).append("); ");
            return null;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            missed.append("the published GitHub client (").append(url)
                    .append(") fetch was interrupted; ");
            return null;
        }
    }

    private Set<String> extract(String bundle) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = CLIENT_ID.matcher(bundle);
        while (matcher.find() && found.size() < MAX_CANDIDATES_PER_ARTIFACT * 4) {
            found.add(matcher.group());
        }
        return found;
    }

    private enum Probe { VALID, INVALID, INCONCLUSIVE }

    private Probe probe(String clientId, URI deviceStart) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("User-Agent", GitHubCopilotOAuthFlow.USER_AGENT);
        try {
            OAuthSupport.Response response = http.send(new OAuthSupport.Request(
                    "POST", deviceStart, headers,
                    OAuthSupport.form(Map.of("client_id", clientId, "scope", "read:user"))));
            if (response.status() == 404) {
                return Probe.INVALID;
            }
            if (!response.success()) {
                return Probe.INCONCLUSIVE;
            }
            try {
                var body = OAuthSupport.json(response, "GitHub device probe");
                return body.hasNonNull("user_code") && body.hasNonNull("device_code")
                        ? Probe.VALID : Probe.INCONCLUSIVE;
            } catch (IOException malformed) {
                return Probe.INCONCLUSIVE;
            }
        } catch (IOException failure) {
            return Probe.INCONCLUSIVE;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return Probe.INCONCLUSIVE;
        }
    }

    private void cacheQuietly(OAuthClientSettings settings, String providerId, String clientId) {
        try {
            settings.put(providerId, new OAuthClientSettings.ClientCredentials(clientId, null, null));
        } catch (IOException ignored) {
            // This login proceeds; the next one re-pulls.
        }
    }

    private static void removeQuietly(OAuthClientSettings settings, String providerId) {
        try {
            settings.remove(providerId);
        } catch (IOException ignored) {
            // A stale entry that survives removal still fails fast at the device start.
        }
    }

    private static String trim(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.crawler.remote;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Abstract base for all OAuth-backed crawl clients.
 *
 * <p>Provides shared infrastructure that concrete implementations (Google Drive,
 * OneDrive, SharePoint, Dropbox, etc.) would otherwise duplicate:</p>
 * <ul>
 *   <li>Secure token storage with volatile visibility</li>
 *   <li>A single reusable {@link HttpClient} with configurable connect/request timeouts</li>
 *   <li>Pre-built {@link #httpGet(String)} and {@link #httpGetBytes(String)} helpers that
 *       attach the {@code Authorization: Bearer} header automatically</li>
 *   <li>A {@link #executeWithTokenRefresh} wrapper that catches HTTP 401/403 responses,
 *       invokes the caller-supplied token refresher, and retries the action once</li>
 *   <li>Connection-health tracking: last-successful-call timestamp and a consecutive-failure
 *       counter, exposed via {@link #isHealthy()}</li>
 * </ul>
 *
 * <h3>Subclass responsibilities</h3>
 * <p>Subclasses must implement {@link #getProviderId()}, {@link #connect}, {@link #listItems},
 * {@link #downloadFile}, and {@link #close}. They should call {@link #recordSuccess()} after
 * each successful remote call and {@link #recordFailure()} after each failed one so that
 * health tracking remains accurate.</p>
 */
@Slf4j
public abstract class AbstractOAuthCrawlClient implements OAuthAwareCrawlClient {

    // ----- Health thresholds -----

    /** Maximum age of the last successful call before the client is considered unhealthy. */
    private static final Duration HEALTHY_STALENESS_THRESHOLD = Duration.ofMinutes(5);

    /** Number of consecutive failures that causes {@link #isHealthy()} to return {@code false}. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    // ----- Shared HTTP client -----

    /** Reusable HTTP client shared by all subclass methods. */
    protected final HttpClient httpClient;

    // ----- Token & connection state -----

    private volatile String accessToken;
    private volatile boolean connected = false;

    // ----- Health tracking -----

    /** Epoch millis of the last successful remote call (0 = never succeeded). */
    private final AtomicLong lastSuccessMs = new AtomicLong(0);

    /** Number of consecutive failures since the last successful call. */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    // ----- Constructor -----

    /**
     * Creates the shared {@link HttpClient} with sensible defaults.
     * Subclasses that need a custom client configuration should override
     * {@link #buildHttpClient()} before calling {@code super()}.
     */
    protected AbstractOAuthCrawlClient() {
        this.httpClient = buildHttpClient();
    }

    /**
     * Factory method for the shared {@link HttpClient}.
     * Override to customise timeouts, proxy settings, SSL context, etc.
     */
    protected HttpClient buildHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ----- OAuthAwareCrawlClient: token & connection -----

    @Override
    public void refreshConnection(String newAccessToken) throws IOException {
        if (newAccessToken == null || newAccessToken.isBlank()) {
            throw new IOException("Cannot refresh connection: new access token is blank");
        }
        log.debug("[{}] Refreshing access token", getProviderId());
        this.accessToken = newAccessToken;
        this.connected = true;
        // Reset failure count — caller has obtained a fresh token
        consecutiveFailures.set(0);
    }

    @Override
    public boolean isConnected() {
        return connected && accessToken != null && !accessToken.isBlank();
    }

    // ----- Token accessor for subclasses -----

    /**
     * Returns the current access token for use in provider-specific API calls.
     * Will be {@code null} until {@link #connect} has been called.
     */
    protected String getAccessToken() {
        return accessToken;
    }

    /**
     * Stores the access token and marks the client as connected.
     * Subclasses should call this from their {@link #connect} implementations.
     *
     * @param token the OAuth Bearer token obtained during the connection handshake
     */
    protected void setAccessToken(String token) {
        this.accessToken = token;
        this.connected = token != null && !token.isBlank();
    }

    // ----- Retry-with-refresh helper -----

    /**
     * Executes {@code action} and, if the provider responds with HTTP 401 or 403,
     * refreshes the access token using {@code tokenRefresher} and retries exactly once.
     *
     * <p>The action supplier is expected to throw an {@link UnauthorizedException} (or an
     * {@link IOException} whose message contains "401" or "403") when the server rejects
     * the current token. The refresher supplier should return a new valid token; it may
     * itself throw {@link IOException} if a new token cannot be obtained.</p>
     *
     * <p>Usage example in a subclass:</p>
     * <pre>{@code
     * return executeWithTokenRefresh(
     *     () -> callDriveApi(folderId),
     *     () -> oauthService.refreshAccessToken(refreshToken)
     * );
     * }</pre>
     *
     * @param action        the remote call to execute
     * @param tokenRefresher returns a fresh access token on demand
     * @param <T>           return type of the action
     * @return the result of {@code action} (original or retry)
     * @throws IOException if both attempts fail or the token cannot be refreshed
     */
    protected <T> T executeWithTokenRefresh(IOAction<T> action,
                                             Supplier<String> tokenRefresher) throws IOException {
        try {
            T result = action.execute();
            recordSuccess();
            return result;
        } catch (UnauthorizedException e) {
            log.warn("[{}] Token rejected ({}); attempting refresh", getProviderId(), e.getMessage());
            String newToken = tokenRefresher.get();
            if (newToken == null || newToken.isBlank()) {
                recordFailure();
                throw new IOException("[" + getProviderId() + "] Token refresher returned blank token", e);
            }
            refreshConnection(newToken);
            try {
                T result = action.execute();
                recordSuccess();
                return result;
            } catch (Exception retryEx) {
                recordFailure();
                throw new IOException("[" + getProviderId() + "] Action failed after token refresh", retryEx);
            }
        } catch (IOException e) {
            recordFailure();
            throw e;
        } catch (Exception e) {
            recordFailure();
            throw new IOException("[" + getProviderId() + "] Unexpected error during remote action", e);
        }
    }

    // ----- Health tracking -----

    /**
     * Records a successful remote call. Resets the consecutive-failure counter and
     * updates {@link #lastSuccessMs}.
     */
    protected void recordSuccess() {
        lastSuccessMs.set(System.currentTimeMillis());
        consecutiveFailures.set(0);
    }

    /**
     * Records a failed remote call. Increments the consecutive-failure counter.
     */
    protected void recordFailure() {
        consecutiveFailures.incrementAndGet();
    }

    /**
     * Returns {@code true} when:
     * <ol>
     *   <li>At least one successful call has been made, AND</li>
     *   <li>The last successful call was within {@link #HEALTHY_STALENESS_THRESHOLD}, AND</li>
     *   <li>Consecutive failures are fewer than {@link #MAX_CONSECUTIVE_FAILURES}.</li>
     * </ol>
     */
    public boolean isHealthy() {
        long lastMs = lastSuccessMs.get();
        if (lastMs == 0) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - lastMs;
        return ageMs <= HEALTHY_STALENESS_THRESHOLD.toMillis()
                && consecutiveFailures.get() < MAX_CONSECUTIVE_FAILURES;
    }

    /**
     * Returns the number of consecutive failures recorded since the last successful call.
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * Returns the {@link Instant} of the last successful remote call,
     * or {@link Instant#EPOCH} if no call has yet succeeded.
     */
    public Instant getLastSuccessTime() {
        long ms = lastSuccessMs.get();
        return ms == 0 ? Instant.EPOCH : Instant.ofEpochMilli(ms);
    }

    // ----- HTTP helpers -----

    /**
     * Executes an authenticated HTTP GET against {@code url} and returns the response body
     * as a UTF-8 string.
     *
     * <p>Automatically adds {@code Authorization: Bearer <token>}. Throws
     * {@link UnauthorizedException} on HTTP 401/403 so that {@link #executeWithTokenRefresh}
     * can intercept and retry.</p>
     *
     * @param url the fully-qualified URL to fetch
     * @return response body as a string
     * @throws IOException if the request fails or the server returns a non-2xx status
     */
    protected String httpGet(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            checkStatus(url, response.statusCode(), response.body());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP GET interrupted: " + url, e);
        }
    }

    /**
     * Executes an authenticated HTTP GET against {@code url} and returns the raw response
     * bytes — useful for binary file downloads.
     *
     * <p>Automatically adds {@code Authorization: Bearer <token>}. Throws
     * {@link UnauthorizedException} on HTTP 401/403.</p>
     *
     * @param url the fully-qualified URL to fetch
     * @return response body as a byte array
     * @throws IOException if the request fails or the server returns a non-2xx status
     */
    protected byte[] httpGetBytes(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();

        try {
            HttpResponse<byte[]> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofByteArray());
            checkStatus(url, response.statusCode(), "(binary body)");
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("HTTP GET (bytes) interrupted: " + url, e);
        }
    }

    /**
     * Inspects the HTTP status code and throws an appropriate exception for non-2xx responses.
     *
     * @param url        the request URL (for error messages)
     * @param statusCode HTTP response status code
     * @param body       response body snippet (for error messages; may be "(binary body)")
     * @throws UnauthorizedException if the status is 401 or 403
     * @throws IOException           for any other non-2xx status
     */
    private void checkStatus(String url, int statusCode, String body) throws IOException {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        String snippet = body != null && body.length() > 300
                ? body.substring(0, 300) + "..."
                : body;
        if (statusCode == 401 || statusCode == 403) {
            throw new UnauthorizedException("[" + getProviderId() + "] HTTP " + statusCode
                    + " for " + url + ": " + snippet);
        }
        throw new IOException("[" + getProviderId() + "] HTTP " + statusCode
                + " for " + url + ": " + snippet);
    }

    // ----- Functional interface for IO-throwing actions -----

    /**
     * A {@link Supplier}-like interface whose {@code get()} method may throw {@link IOException}.
     * Used by {@link #executeWithTokenRefresh} so the action can propagate checked exceptions.
     */
    @FunctionalInterface
    public interface IOAction<T> {
        T execute() throws IOException;
    }

    // ----- Checked exception types -----

    /**
     * Thrown when the server returns HTTP 401 or 403, signalling that the current
     * access token is invalid or expired. {@link #executeWithTokenRefresh} catches this
     * to trigger a token refresh and retry.
     */
    public static class UnauthorizedException extends IOException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    /**
     * Convenience alias for token-expiry scenarios where the caller wants to be
     * explicit about the reason for the 401.
     */
    public static class TokenExpiredException extends UnauthorizedException {
        public TokenExpiredException(String message) {
            super(message);
        }
    }
}

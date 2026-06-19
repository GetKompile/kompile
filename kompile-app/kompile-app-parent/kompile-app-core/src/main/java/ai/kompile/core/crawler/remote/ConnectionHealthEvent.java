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

import org.springframework.context.ApplicationEvent;

import java.time.Instant;

/**
 * Spring {@link ApplicationEvent} published by OAuth crawl clients when their connection
 * status changes. Listeners (e.g., a WebSocket broadcaster, a monitoring dashboard, or an
 * auto-reconnect service) can subscribe to this event to react to token expiry,
 * rate limiting, and other health transitions.
 *
 * <h3>Publishing example</h3>
 * <pre>{@code
 * applicationEventPublisher.publishEvent(
 *     ConnectionHealthEvent.connected(this, "google", "Drive connection established"));
 * }</pre>
 *
 * <h3>Listening example</h3>
 * <pre>{@code
 * @EventListener
 * public void onConnectionHealth(ConnectionHealthEvent event) {
 *     if (event.getStatus() == ConnectionHealthEvent.Status.TOKEN_EXPIRED) {
 *         oauthService.scheduleRefresh(event.getProviderId());
 *     }
 * }
 * }</pre>
 */
public class ConnectionHealthEvent extends ApplicationEvent {

    /**
     * The health status that triggered this event.
     */
    public enum Status {
        /** A new connection has been successfully established. */
        CONNECTED,
        /** The access token was refreshed and the connection is healthy again. */
        TOKEN_REFRESHED,
        /** The access token has expired and no automatic refresh is possible. */
        TOKEN_EXPIRED,
        /** The client has disconnected (either explicitly or due to unrecoverable error). */
        DISCONNECTED,
        /** The remote provider is rate-limiting requests; the client is backing off. */
        RATE_LIMITED,
        /** A general error occurred that does not fit a more specific status. */
        ERROR
    }

    private final String providerId;
    private final Status status;
    private final String message;
    private final Instant timestamp;

    /**
     * Creates a new {@code ConnectionHealthEvent}.
     *
     * @param source     the object that published this event (typically the crawl client instance)
     * @param providerId OAuth provider identifier (e.g., {@code "google"}, {@code "microsoft"})
     * @param status     the health status that triggered the event
     * @param message    human-readable description of what happened
     * @param timestamp  when the event occurred
     */
    public ConnectionHealthEvent(Object source, String providerId, Status status,
                                  String message, Instant timestamp) {
        super(source);
        this.providerId = providerId;
        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
    }

    // ----- Getters -----

    /** The OAuth provider ID this event relates to (e.g., {@code "google"}, {@code "microsoft"}). */
    public String getProviderId() {
        return providerId;
    }

    /** The connection health status that triggered this event. */
    public Status getStatus() {
        return status;
    }

    /** A human-readable description of what happened. May be {@code null}. */
    public String getMessage() {
        return message;
    }

    /** The exact instant at which the health transition was detected. */
    public Instant getEventInstant() {
        return timestamp;
    }

    // ----- Factory methods -----

    /** Convenience factory: connection established. */
    public static ConnectionHealthEvent connected(Object source, String providerId, String message) {
        return new ConnectionHealthEvent(source, providerId, Status.CONNECTED, message, Instant.now());
    }

    /** Convenience factory: token was refreshed successfully. */
    public static ConnectionHealthEvent tokenRefreshed(Object source, String providerId, String message) {
        return new ConnectionHealthEvent(source, providerId, Status.TOKEN_REFRESHED, message, Instant.now());
    }

    /** Convenience factory: token has expired and cannot be refreshed automatically. */
    public static ConnectionHealthEvent tokenExpired(Object source, String providerId, String message) {
        return new ConnectionHealthEvent(source, providerId, Status.TOKEN_EXPIRED, message, Instant.now());
    }

    /** Convenience factory: client disconnected. */
    public static ConnectionHealthEvent disconnected(Object source, String providerId, String message) {
        return new ConnectionHealthEvent(source, providerId, Status.DISCONNECTED, message, Instant.now());
    }

    /** Convenience factory: provider is rate-limiting. */
    public static ConnectionHealthEvent rateLimited(Object source, String providerId, String message) {
        return new ConnectionHealthEvent(source, providerId, Status.RATE_LIMITED, message, Instant.now());
    }

    /** Convenience factory: a general error occurred. */
    public static ConnectionHealthEvent error(Object source, String providerId, String message) {
        return new ConnectionHealthEvent(source, providerId, Status.ERROR, message, Instant.now());
    }

    @Override
    public String toString() {
        return "ConnectionHealthEvent{providerId='" + providerId + "', status=" + status
                + ", timestamp=" + timestamp + ", message='" + message + "'}";
    }
}

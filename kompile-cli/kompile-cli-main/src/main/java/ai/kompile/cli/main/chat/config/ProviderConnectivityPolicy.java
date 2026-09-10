/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.config;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider-owned connectivity limits for Kompile Chat.
 *
 * <p>Connection establishment, request lifetime, streaming silence and retry cadence are
 * deliberately separate. An HTTP request timeout does not protect an SSE body after
 * {@code BodyHandlers.ofInputStream()} has returned, so callers must also apply
 * {@link #streamIdleTimeout()} while consuming the body.</p>
 */
public record ProviderConnectivityPolicy(
        Duration connectTimeout,
        Duration requestTimeout,
        Duration streamIdleTimeout,
        Duration subprocessIdleTimeout,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff) {

    public ProviderConnectivityPolicy {
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(requestTimeout, "requestTimeout");
        requirePositive(streamIdleTimeout, "streamIdleTimeout");
        requirePositive(subprocessIdleTimeout, "subprocessIdleTimeout");
        requirePositive(initialBackoff, "initialBackoff");
        requirePositive(maxBackoff, "maxBackoff");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
    }

    /** Resolve the defaults for one configured provider or native provider adapter. */
    public static ProviderConnectivityPolicy forProvider(String providerId) {
        String provider = providerId == null ? "" : providerId.trim().toLowerCase(Locale.ROOT);
        return switch (provider) {
            // ChatGPT's Responses path has a reliable event stream and benefits from one
            // extra recovery attempt for transient edge/proxy resets.
            case "openai-codex" -> remote(20, 90, 5, 1_000);
            case "openai", "github-copilot", "xai" -> remote(20, 120, 4, 1_000);
            case "anthropic", "gemini", "deepseek", "openrouter", "radius" ->
                    remote(20, 180, 4, 1_000);
            case "groq" -> remote(10, 60, 4, 500);

            // Local inference may spend minutes in model load or prefill before producing
            // a token. Keep its TCP failure detection tight without treating compute as a
            // dead connection.
            case "kompile-local" -> local(3, 30, 10, 3);
            case "ollama" -> local(2, 30, 5, 3);
            case "opencode" -> nativeProcess(2, 10, 3);

            // The Kompile app is a local or LAN service with a persistent MCP/SSE lane.
            case "kompile" -> new ProviderConnectivityPolicy(
                    Duration.ofSeconds(10), Duration.ofMinutes(10), Duration.ofMinutes(2),
                    Duration.ofMinutes(10), 4, Duration.ofMillis(500), Duration.ofSeconds(5));
            default -> new ProviderConnectivityPolicy(
                    Duration.ofSeconds(30), Duration.ofMinutes(10), Duration.ofMinutes(3),
                    Duration.ofMinutes(10), 3, Duration.ofSeconds(1), Duration.ofSeconds(8));
        };
    }

    /**
     * Return the same transport limits with a different replay budget. Utility callers such as
     * real-time judges have their own outer deadline and must not nest a multi-attempt provider
     * retry loop inside it.
     */
    public ProviderConnectivityPolicy withMaxAttempts(int attempts) {
        if (attempts == maxAttempts) {
            return this;
        }
        return new ProviderConnectivityPolicy(
                connectTimeout, requestTimeout, streamIdleTimeout, subprocessIdleTimeout,
                attempts, initialBackoff, maxBackoff);
    }

    private static ProviderConnectivityPolicy remote(
            long connectSeconds, long idleSeconds, int attempts, long backoffMillis) {
        return new ProviderConnectivityPolicy(
                Duration.ofSeconds(connectSeconds), Duration.ofMinutes(10),
                Duration.ofSeconds(idleSeconds), Duration.ofMinutes(10), attempts,
                Duration.ofMillis(backoffMillis), Duration.ofSeconds(10));
    }

    private static ProviderConnectivityPolicy local(
            long connectSeconds, long requestMinutes, long idleMinutes, int attempts) {
        return new ProviderConnectivityPolicy(
                Duration.ofSeconds(connectSeconds), Duration.ofMinutes(requestMinutes),
                Duration.ofMinutes(idleMinutes), Duration.ofMinutes(10), attempts,
                Duration.ofMillis(250), Duration.ofSeconds(2));
    }

    private static ProviderConnectivityPolicy nativeProcess(
            long connectSeconds, long idleMinutes, int attempts) {
        return new ProviderConnectivityPolicy(
                Duration.ofSeconds(connectSeconds), Duration.ofMinutes(30),
                Duration.ofMinutes(idleMinutes), Duration.ofMinutes(idleMinutes), attempts,
                Duration.ofMillis(500), Duration.ofSeconds(4));
    }

    /** HTTP responses that are safe candidates for a bounded replay. */
    public boolean isRetryableStatus(int statusCode) {
        return statusCode == 408 || statusCode == 409 || statusCode == 425
                || statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504
                // 529 is Anthropic's overloaded_error: the provider (or its edge) is
                // temporarily at capacity, not permanently broken.
                || statusCode == 529;
    }

    /**
     * Classify transport failures and provider overload/rate-limit signals without
     * retrying authentication or TLS configuration errors.
     */
    public boolean isRetryableFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InterruptedException || current instanceof SSLHandshakeException) {
                return false;
            }
            if (current instanceof IdleTimeoutInputStream.StreamIdleTimeoutException
                    || current instanceof HttpConnectTimeoutException
                    || current instanceof HttpTimeoutException
                    || current instanceof ConnectException
                    || current instanceof UnknownHostException
                    || current instanceof EOFException) {
                return true;
            }
            if (current instanceof SocketException) {
                return true;
            }
            if (current instanceof IOException) {
                String message = normalizedMessage(current);
                if (message.equals("closed") || message.contains("connection")
                        || message.contains("stream closed")
                        || message.contains("stream ended") || message.contains("stream reset")
                        || message.contains("goaway")
                        || message.contains("premature eof") || message.contains("no bytes")) {
                    return true;
                }
            }
            String message = normalizedMessage(current);
            if (message.contains("connection timed out") || message.contains("connect timeout")
                    || message.contains("turn timed out") || message.contains("provider timed out")
                    || message.contains("connection reset") || message.contains("connection refused")
                    || message.contains("upstream connect error") || message.contains("disconnect/reset")
                    // In-band provider failure text: mid-stream "Overloaded" events and
                    // rate-limit envelopes delivered inside a 200 SSE stream.
                    || message.contains("overloaded")
                    || message.contains("rate limit") || message.contains("rate_limit")
                    // OpenAI-compatible servers signal transient server-side failures
                    // with these typed envelopes ("type":"server_error" is their 5xx
                    // equivalent), so classify on the type token, not the human
                    // message.
                    || message.contains("\"server_error\"")
                    || message.contains("\"api_error\"")
                    || message.contains("\"internal_error\"")
                    // An envelope that only says "Unknown error" is the provider
                    // admitting a server-side failure it cannot describe; it is
                    // never a request-shape problem worth dying on immediately.
                    || message.contains("unknown error")) {
                return true;
            }
        }
        return false;
    }

    /** Exponential backoff, honoring a provider Retry-After response when present. */
    public Duration retryDelay(int failedAttempt, HttpHeaders headers) {
        Optional<Duration> retryAfter = retryAfter(headers, Instant.now());
        if (retryAfter.isPresent()) {
            return min(maxBackoff, retryAfter.get());
        }
        int exponent = Math.max(0, Math.min(30, failedAttempt - 1));
        long multiplier = 1L << exponent;
        long initialMillis = initialBackoff.toMillis();
        long delayMillis;
        try {
            delayMillis = Math.multiplyExact(initialMillis, multiplier);
        } catch (ArithmeticException ignored) {
            delayMillis = Long.MAX_VALUE;
        }
        return min(maxBackoff, Duration.ofMillis(delayMillis));
    }

    static Optional<Duration> retryAfter(HttpHeaders headers, Instant now) {
        if (headers == null) return Optional.empty();
        Optional<String> value = headers.firstValue("Retry-After");
        if (value.isEmpty() || value.get().isBlank()) return Optional.empty();
        String raw = value.get().trim();
        try {
            long seconds = Long.parseLong(raw);
            return Optional.of(Duration.ofSeconds(Math.max(0L, seconds)));
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                return Optional.of(Duration.between(now, retryAt).isNegative()
                        ? Duration.ZERO : Duration.between(now, retryAt));
            } catch (DateTimeParseException ignoredDate) {
                return Optional.empty();
            }
        }
    }

    private static Duration min(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static String normalizedMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null ? "" : message.toLowerCase(Locale.ROOT);
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}

/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.channel.api;

/** Exchanges one CLI-minted code for an HttpOnly browser session. */
public record ChannelBrowserSessionRequest(String code) {
}

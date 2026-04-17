/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.cli.main.graph;

import ai.kompile.cli.common.http.KompileHttpClient;
import ai.kompile.cli.main.app.AppClientMixin;

/**
 * Test-only AppClientMixin that bypasses URL resolution and returns a stub client.
 */
final class StubAppClientMixin extends AppClientMixin {

    private final KompileHttpClient client;
    private final boolean json;

    StubAppClientMixin(KompileHttpClient client, boolean json) {
        this.client = client;
        this.json = json;
    }

    @Override
    public KompileHttpClient requireClient() {
        return client;
    }

    @Override
    public boolean isJsonOutput() {
        return json;
    }
}

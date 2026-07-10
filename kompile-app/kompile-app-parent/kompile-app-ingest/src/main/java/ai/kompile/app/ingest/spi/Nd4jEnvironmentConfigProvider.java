/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.app.ingest.spi;

import ai.kompile.app.config.Nd4jEnvironmentConfig;

/**
 * SPI for reading the resolved ND4J environment configuration.
 *
 * <p>Declaring this interface here — in the ingest module — inverts what used to be a direct
 * {@code ingest → kompile-app-main.services.Nd4jEnvironmentConfigService} dependency (the only edge
 * from ingest up into app-main's service layer). {@code IngestEventService} depends only on this
 * interface (optionally injected); the concrete implementation
 * ({@code ai.kompile.app.services.Nd4jEnvironmentConfigService}) stays in kompile-app-main and is
 * wired in by Spring at runtime. The return type {@link Nd4jEnvironmentConfig} is an app-core type,
 * so this interface carries no compile-time coupling back into app-main. When no implementation is
 * present the optional injection is {@code null} and callers skip config reporting.</p>
 */
public interface Nd4jEnvironmentConfigProvider {

    /** The actual, resolved ND4J environment configuration currently in effect. */
    Nd4jEnvironmentConfig getActualConfiguration();
}

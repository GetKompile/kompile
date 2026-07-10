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
package ai.kompile.app.ingest.service;

/**
 * Bean-name constants for the transaction managers the ingest persistence layer routes to.
 *
 * <p>This is the single source of truth for the ingest-event transaction manager name. The manager
 * bean itself is still declared by {@code ai.kompile.app.config.PrimaryDataSourceConfig} in
 * kompile-app-main (which references this constant); ingest's own {@code @Transactional} annotations
 * reference it here. Owning the constant in the ingest module removes ingest's former compile-time
 * dependency on that app-main config class — the value is unchanged, so bean resolution is
 * identical at runtime.</p>
 */
public final class IngestTransactionManagers {

    private IngestTransactionManagers() {
    }

    /** Bean name of the transaction manager bound to the ingest-event datasource. */
    public static final String INGEST_EVENT_TRANSACTION_MANAGER = "ingestEventTransactionManager";
}

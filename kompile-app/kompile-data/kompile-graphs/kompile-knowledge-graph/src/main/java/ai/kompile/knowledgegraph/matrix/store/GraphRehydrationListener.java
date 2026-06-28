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
package ai.kompile.knowledgegraph.matrix.store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Triggers graph rehydration asynchronously once the application context is fully ready.
 *
 * <p>The actual rehydration logic lives in {@link VectorStoreMatrixGraphStore#rehydrateGraphsOnStartup()}.
 * We cannot put {@code @EventListener} + {@code @Async} directly on that bean because Spring
 * creates a JDK interface proxy for it (via {@link MatrixGraphStore}) and the JDK proxy cannot
 * dispatch {@code @EventListener} callbacks to methods that are absent from the proxied interface.
 * Moving the annotations here — where the concrete class is injected directly — avoids the proxy
 * restriction while keeping the listener non-blocking at startup.</p>
 *
 * <p><b>Disabled in subprocess mode.</b> When {@code kompile.graph.subprocess.enabled=true} the
 * matrix lives in the {@code graph-matrix} subprocess (which rehydrates there), so the MAIN app must
 * NOT rehydrate the 1.27M-edge matrix into its own heap — that is the OOM this whole refactor fixes.
 * {@code matchIfMissing=true} preserves the default in-process behaviour.</p>
 */
@Component
@ConditionalOnProperty(
        name = "kompile.graph.subprocess.enabled",
        havingValue = "false",
        matchIfMissing = true)
public class GraphRehydrationListener {

    @Autowired
    private VectorStoreMatrixGraphStore store;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void onApplicationReady() {
        store.rehydrateGraphsOnStartup();
    }
}

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

package ai.kompile.embedding.anserini;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces and fixes a silent retry loop: a permanently-missing embedding model (configured
 * model id not registered anywhere the staging service can see, e.g. {@code multilingual-e5-small}
 * when only {@code bge-*} models are installed) used to relaunch a full GPU embedding subprocess
 * roughly once a minute forever, because:
 *
 * <ol>
 *   <li>{@code AnseriniEmbeddingModelImpl}'s own retry classifier did naive substring matching
 *       ("UNAVAILABLE", "TIMEOUT", ...) instead of reusing {@link RetryableErrorClassifier}. The
 *       model-not-found error message includes troubleshooting guidance text ("...if staging is
 *       unavailable") whose incidental "unavailable" made the classifier call a PERMANENT error
 *       RETRIABLE.</li>
 *   <li>Because the error was (mis)classified retriable, nothing ever engaged the existing
 *       restart-governor circuit breaker ({@code restartsPaused} / {@link
 *       AnseriniEmbeddingModelImpl#isRestartDeferred()}), so {@code
 *       ModelAutoInitializationService}'s scheduled poll and the {@code
 *       AnseriniEmbeddingStartupInitializer} staging-model-poller both kept believing the failure
 *       might clear up and kept respawning the subprocess.</li>
 * </ol>
 *
 * <p>The fix: classification is delegated to {@link RetryableErrorClassifier} (whose
 * NON_RETRIABLE patterns, e.g. "failed to load", take precedence over incidental retriable-looking
 * words), and a permanent failure now pauses restarts through the SAME governor used for
 * native-crash/stall loops — so every existing caller that already respects {@code restartsPaused}
 * stops respawning without any new framework.
 */
@DisplayName("AnseriniEmbeddingModelImpl permanent load-failure deferral")
class PermanentLoadFailureDefersAutoInitTest {

    /**
     * The exact shape of the production error: {@code AnseriniEncoderFactory.createModelNotFoundError}
     * wraps a "model not found" summary together with multi-line troubleshooting guidance that
     * happens to contain the word "unavailable".
     */
    private static final String MODEL_NOT_FOUND_MESSAGE =
            "Subprocess failed to load model: Model not found: multilingual-e5-small\n\n"
                    + "Staging service is configured (http://localhost:9090) but the model is not "
                    + "available after 3 attempts.\n"
                    + "Possible causes:\n"
                    + "  - The staging service may be offline or unreachable\n"
                    + "  - The model may not be registered in the staging service\n"
                    + "  - Network connectivity issues\n\n"
                    + "To resolve:\n"
                    + "  - Check the staging service connection in the UI\n"
                    + "  - Verify the model is registered in the staging service\n"
                    + "  - Import a model archive directly if staging is unavailable\n\n"
                    + "Available models: [bge-base-model, bge-m3, bge-base-en-v1.5]";

    private static AnseriniEmbeddingModelImpl newModel() {
        AnseriniEmbeddingModelImpl model =
                new AnseriniEmbeddingModelImpl("multilingual-e5-small", 4, 8, null);
        model.resetRestartGovernorForTest();
        return model;
    }

    @Test
    @DisplayName("the word 'unavailable' in model-not-found guidance text no longer forces a retriable classification")
    void modelNotFoundGuidanceTextDoesNotOverrideClassification() {
        AnseriniEmbeddingModelImpl model = newModel();

        assertFalse(model.isRetriableError(new IOException(MODEL_NOT_FOUND_MESSAGE)),
                "'failed to load' / model-not-found must win over the incidental 'unavailable' in "
                        + "the troubleshooting guidance");
    }

    @Test
    @DisplayName("a permanent load failure defers auto-init after the first failure and stays deferred on later polls")
    void permanentFailureDefersAfterFirstAttempt() {
        AnseriniEmbeddingModelImpl model = newModel();

        assertFalse(model.isRestartDeferred(), "clean model must not start deferred");

        model.recordInitializationFailure(new IOException(MODEL_NOT_FOUND_MESSAGE));

        assertFalse(model.isInitializationErrorRetriable(), "model-not-found is a permanent error");
        assertTrue(model.isRestartDeferred(), "a permanent failure must defer like the restart governor");
        String reason = model.getRestartDeferredReason();
        assertNotNull(reason);
        assertTrue(reason.contains("multilingual-e5-small"), reason);
        assertTrue(reason.contains("bge-base-en-v1.5") || reason.contains("Available models"), reason);
        assertFalse(model.shouldContinuePolling(), "the staging-model-poller must stop polling");
        assertNull(model.subprocessLauncher, "the dead launcher must not be left around");

        // Simulate several more scheduled polls (ModelAutoInitializationService.checkAndInitializeModels
        // -> tryInitializeEmbedding -> initializeIfNeeded, every poll interval): none of them may spawn
        // a subprocess once deferred.
        for (int i = 0; i < 3; i++) {
            assertFalse(model.initializeIfNeeded(), "must stay uninitialized while deferred");
            assertNull(model.subprocessLauncher, "a deferred model must never spawn a subprocess (poll " + i + ")");
        }
    }

    @Test
    @DisplayName("resume clears the deferred state through the existing restart-governor resume path")
    void resumeClearsDeferredState() {
        AnseriniEmbeddingModelImpl model = newModel();
        model.recordInitializationFailure(new IOException(MODEL_NOT_FOUND_MESSAGE));
        assertTrue(model.isRestartDeferred());

        // Simulate the model having become available since (e.g. the registered model id was
        // corrected, or the staging registry picked up the model) by leaving no launcher around
        // and marking the model loaded, the same way a real load would leave it; this keeps
        // resumeRestarts()'s internal ensureInitialized() call on its fast "already initialized"
        // path instead of starting a real GPU subprocess from this unit test.
        model.modelSource = AnseriniEmbeddingModelImpl.ModelSource.REGISTRY;
        model.initialized = true;

        assertTrue(model.resumeRestarts(), "resume must bring the model back up");
        assertFalse(model.isRestartDeferred(), "resume must clear the restart-governor deferral");
        assertNull(model.getRestartDeferredReason());
    }

    @Test
    @DisplayName("a transient failure does not defer and the poller keeps retrying")
    void transientFailureKeepsRetrying() {
        AnseriniEmbeddingModelImpl model = newModel();

        model.recordInitializationFailure(new IOException("Connection refused while contacting staging service"));

        assertTrue(model.isInitializationErrorRetriable(), "connection-refused is transient");
        assertFalse(model.isRestartDeferred(), "a transient failure must not trip the circuit breaker");
        assertNull(model.getRestartDeferredReason());
        assertTrue(model.shouldContinuePolling(), "the staging-model-poller must keep retrying a transient error");
    }

    /**
     * Regression coverage: delegating {@code isRetriableError} straight to {@link
     * RetryableErrorClassifier} fixed the model-not-found bug but, unguarded, would also make a
     * live subprocess crash / transport hiccup PERMANENT, because the shared classifier (a) has no
     * message pattern at all for these markers and defaults unmatched messages to non-retriable
     * (fail-safe), and (b) always treats {@link IllegalStateException} as non-retriable by type
     * alone ({@code RetryableErrorClassifier.NON_RETRIABLE_EXCEPTION_TYPES}) regardless of what the
     * message says. A native crash or broken pipe during init must keep going through the existing
     * crash restart governor (StallCircuitBreaker / restart policy), not this deferral — so these
     * markers must stay retriable no matter what exception type wraps them.
     */
    @Test
    @DisplayName("subprocess crash/transport markers stay retriable regardless of exception type and do not defer")
    void subprocessCrashAndTransportMarkersStayRetriable() {
        Exception[] crashExceptions = {
                new IllegalStateException("Embedding subprocess crashed: native process exited unexpectedly"),
                new RuntimeException("Stream closed"),
                new RuntimeException("Subprocess not alive"),
                new RuntimeException("Crash handling triggered"),
        };

        for (Exception crashException : crashExceptions) {
            AnseriniEmbeddingModelImpl model = newModel();

            model.recordInitializationFailure(crashException);

            assertTrue(model.isInitializationErrorRetriable(),
                    "must stay retriable: " + crashException);
            assertFalse(model.isRestartDeferred(),
                    "a crash/transport marker must not trip the permanent-load-failure circuit "
                            + "breaker: " + crashException);
            assertNull(model.getRestartDeferredReason());
            assertTrue(model.shouldContinuePolling(),
                    "the staging-model-poller must keep retrying a crash/transport error: " + crashException);
        }
    }

    @Test
    @DisplayName("the real production model-not-found message (full troubleshooting guidance) is permanent and defers")
    void realProductionModelNotFoundMessageDefers() {
        AnseriniEmbeddingModelImpl model = newModel();

        model.recordInitializationFailure(new IOException(MODEL_NOT_FOUND_MESSAGE));

        assertFalse(model.isInitializationErrorRetriable(),
                "the real AnseriniEncoderFactory.createModelNotFoundError text — including "
                        + "'Staging service is configured ... not available after 3 attempts' and "
                        + "'unavailable' — must classify permanent");
        assertTrue(model.isRestartDeferred(), "a permanent failure must defer like the restart governor");
        assertFalse(model.shouldContinuePolling(), "the staging-model-poller must stop polling");
    }
}

/*
 * Copyright 2025 Kompile Inc.
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.kompile.staging.download;

import java.util.concurrent.CancellationException;

/**
 * Cooperative cancellation signal shared by download, conversion, compilation, and publication.
 */
@FunctionalInterface
public interface StagingCancellation {
    StagingCancellation NONE = () -> false;

    boolean isCancellationRequested();

    default void checkpoint() {
        if (isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Staging operation was cancelled");
        }
    }
}

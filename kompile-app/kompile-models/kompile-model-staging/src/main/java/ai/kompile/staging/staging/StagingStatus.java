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
package ai.kompile.staging.staging;

/**
 * Staging-package re-export of {@link ai.kompile.core.staging.StagingStatus}.
 *
 * <p>Provides constants of type {@link ai.kompile.core.staging.StagingStatus} under
 * this package-local name so that tests in the {@code ai.kompile.staging.staging}
 * package can reference enum values (e.g. {@code StagingStatus.FAILED}) without an
 * explicit import, while still comparing correctly against values returned by
 * {@link StagingService} and {@link StagingModelInfo#getStatus()}.
 */
public final class StagingStatus {

    private StagingStatus() {}

    public static final ai.kompile.core.staging.StagingStatus PENDING =
            ai.kompile.core.staging.StagingStatus.PENDING;

    public static final ai.kompile.core.staging.StagingStatus DOWNLOADING =
            ai.kompile.core.staging.StagingStatus.DOWNLOADING;

    public static final ai.kompile.core.staging.StagingStatus CONVERTING =
            ai.kompile.core.staging.StagingStatus.CONVERTING;

    public static final ai.kompile.core.staging.StagingStatus VALIDATING =
            ai.kompile.core.staging.StagingStatus.VALIDATING;

    public static final ai.kompile.core.staging.StagingStatus OPTIMIZING =
            ai.kompile.core.staging.StagingStatus.OPTIMIZING;

    public static final ai.kompile.core.staging.StagingStatus READY =
            ai.kompile.core.staging.StagingStatus.READY;

    public static final ai.kompile.core.staging.StagingStatus PROMOTING =
            ai.kompile.core.staging.StagingStatus.PROMOTING;

    public static final ai.kompile.core.staging.StagingStatus COMPLETED =
            ai.kompile.core.staging.StagingStatus.COMPLETED;

    public static final ai.kompile.core.staging.StagingStatus FAILED =
            ai.kompile.core.staging.StagingStatus.FAILED;
}

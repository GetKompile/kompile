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
 * Staging-package alias for {@link ai.kompile.core.staging.StagingModelInfo}.
 *
 * <p>Placing this class in the {@code ai.kompile.staging.staging} package allows
 * tests and other classes in that package to reference it without a fully-qualified
 * name or an explicit import, matching the package-private visibility expectations
 * of the integration tests.
 *
 * <p>All behaviour is inherited from the core class.
 */
public class StagingModelInfo extends ai.kompile.core.staging.StagingModelInfo {

    public StagingModelInfo() {
        super();
    }

    /**
     * Factory method that creates a {@code StagingModelInfo} in PENDING status,
     * returned as this package-local type so callers in the same package can use
     * it without an import.
     */
    public static StagingModelInfo create(String modelId, String source, Object type) {
        StagingModelInfo info = new StagingModelInfo();
        info.setModelId(modelId);
        info.setSource(source);
        info.setType(type);
        info.setStatus(ai.kompile.core.staging.StagingStatus.PENDING);
        info.setStartedAt(java.time.Instant.now().toString());
        info.setTotalBytes(-1);
        return info;
    }

    /**
     * Mark as failed and return this (covariant override so callers in this package
     * get back the local type without an explicit cast).
     */
    @Override
    public StagingModelInfo failed(String error) {
        super.failed(error);
        return this;
    }
}

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
package ai.kompile.core.staging;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Status of a model in the staging pipeline.
 * Shared between kompile-app-main and kompile-model-staging.
 */
public enum StagingStatus {
    PENDING("pending"),
    DOWNLOADING("downloading"),
    CONVERTING("converting"),
    VALIDATING("validating"),
    OPTIMIZING("optimizing"),
    READY("ready"),
    PROMOTING("promoting"),
    COMPLETED("completed"),
    FAILED("failed");

    private final String value;

    StagingStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public static StagingStatus fromValue(String value) {
        for (StagingStatus s : values()) {
            if (s.value.equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown staging status: " + value);
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    public boolean isActive() {
        return this == DOWNLOADING || this == CONVERTING || this == VALIDATING
                || this == OPTIMIZING || this == PROMOTING;
    }
}

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

package ai.kompile.modelmanager.registry;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Enum representing the status of a model in the registry.
 */
public enum ModelStatus {
    ACTIVE("active"),
    STAGED("staged"),
    PENDING("pending"),
    FAILED("failed"),
    DEPRECATED("deprecated");

    private final String value;

    ModelStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ModelStatus fromValue(String value) {
        if (value == null) {
            return ACTIVE;
        }
        // Handle legacy "available" status as ACTIVE
        if ("available".equalsIgnoreCase(value)) {
            return ACTIVE;
        }
        for (ModelStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown model status: " + value);
    }

    public boolean isUsable() {
        return this == ACTIVE;
    }
}

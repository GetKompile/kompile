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

package ai.kompile.app.ingest.domain;

import ai.kompile.app.ingest.domain.IndexingJobHistory.FailureReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter for FailureReason enum.
 *
 * This converter stores the enum as a plain VARCHAR string without H2's
 * automatic check constraint, allowing new enum values to be added without
 * requiring database migrations.
 *
 * When reading unknown values from the database, it falls back to UNKNOWN
 * to prevent data loss.
 */
@Converter(autoApply = false)
public class FailureReasonConverter implements AttributeConverter<FailureReason, String> {

    @Override
    public String convertToDatabaseColumn(FailureReason attribute) {
        if (attribute == null) {
            return null;
        }
        return attribute.name();
    }

    @Override
    public FailureReason convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        try {
            return FailureReason.valueOf(dbData);
        } catch (IllegalArgumentException e) {
            // Unknown value - return UNKNOWN to prevent data loss
            return FailureReason.UNKNOWN;
        }
    }
}

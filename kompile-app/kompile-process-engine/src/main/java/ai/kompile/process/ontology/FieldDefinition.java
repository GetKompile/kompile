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

package ai.kompile.process.ontology;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A typed field within an entity type definition.
 * Supports constraints: required, enum values, min/max, regex, FK references, immutability.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class FieldDefinition {

    private String name;
    private FieldType type;
    /** Maximum character length, applicable to STRING fields. */
    private Integer maxLength;
    private boolean required;
    private boolean immutable;
    private boolean primaryKey;
    /** Validation regex pattern. */
    private String regex;
    /** Foreign key reference, e.g., "CurrencyRegistry.code". */
    private String fkReference;
    /** Allowed values for ENUM and ENUM_ARRAY fields. */
    private List<String> enumValues;
    private Double min;
    private Double max;
    private String defaultValue;
    private String description;
}

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

package ai.kompile.core.filter;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Records a mutation made by a filter to the context.
 * Used for tracking and debugging what each filter changed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilterMutation {

    /**
     * The ID of the filter that made the mutation.
     */
    private String filterId;

    /**
     * The name of the field that was mutated.
     */
    private String field;

    /**
     * The previous value (may be null).
     */
    private Object previousValue;

    /**
     * The new value after mutation.
     */
    private Object newValue;

    /**
     * When the mutation occurred.
     */
    @Builder.Default
    private Instant timestamp = Instant.now();

    /**
     * Optional description of why the mutation was made.
     */
    private String reason;

    /**
     * Create a mutation record.
     *
     * @param filterId The filter that made the change
     * @param field The field that was changed
     * @param previousValue The old value
     * @param newValue The new value
     * @return A new FilterMutation
     */
    public static FilterMutation of(String filterId, String field, Object previousValue, Object newValue) {
        return FilterMutation.builder()
                .filterId(filterId)
                .field(field)
                .previousValue(previousValue)
                .newValue(newValue)
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Create a mutation record with a reason.
     *
     * @param filterId The filter that made the change
     * @param field The field that was changed
     * @param previousValue The old value
     * @param newValue The new value
     * @param reason Why the mutation was made
     * @return A new FilterMutation
     */
    public static FilterMutation of(String filterId, String field, Object previousValue, Object newValue, String reason) {
        return FilterMutation.builder()
                .filterId(filterId)
                .field(field)
                .previousValue(previousValue)
                .newValue(newValue)
                .reason(reason)
                .timestamp(Instant.now())
                .build();
    }
}

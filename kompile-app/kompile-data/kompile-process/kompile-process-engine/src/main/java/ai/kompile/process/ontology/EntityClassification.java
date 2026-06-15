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

/**
 * Classifies an entity type's role in the domain model.
 */
public enum EntityClassification {
    /** Static reference data (currency, taxonomy, SKU master). */
    REFERENCE,
    /** Live transactional records (forecasts, actuals, adjustments). */
    TRANSACTIONAL,
    /** Detected pattern or anomaly type. */
    PATTERN,
    /** Control assertion or compliance gate. */
    CONTROL,
    /** Computed metric or KPI. */
    METRIC,
    /** Human or system actor (approver, submitter, agent). */
    ACTOR
}

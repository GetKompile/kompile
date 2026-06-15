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

package ai.kompile.process.ingest;

/**
 * Classifies the type of pre-processing normalization applied by a {@link NormalizationRule}.
 */
public enum NormalizationType {
    /** Scale numeric values between units, e.g., JPY thousands to actuals. */
    MAGNITUDE_SCALING,
    /** Map a regional fiscal calendar period to the corporate calendar, e.g., APAC FY27Q1 to Jun-Aug. */
    FISCAL_CALENDAR_MAP,
    /** Net out VAT from gross figures using country-specific rates. */
    VAT_NETTING,
    /** Canonicalize channel labels: "DTC" / "Direct-to-Consumer" / "ecom" to a single value. */
    CHANNEL_TAXONOMY_MAP,
    /** Remap regional SKU codes to global master codes. */
    SKU_REMAP,
    /** Normalize currency symbols: "¥" / "JPY" to a canonical identifier. */
    CURRENCY_TAG_NORMALIZE,
    /** Override an FX rate with a treasury-locked rate for a specific period. */
    FX_RATE_OVERRIDE,
    /** Fetch an external reference file and join it to the submission data. */
    EXTERNAL_REF_FETCH,
    /** Custom or composite normalization not covered by the standard types. */
    CUSTOM
}

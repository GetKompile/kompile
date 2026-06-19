/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.resolution;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * {@link IdentifierScheme} for product barcodes — the GTIN kind. A thin adapter over the existing
 * {@link BarcodeNormalizer} (GTIN-14 canonicalization, check digits, UPC-E expansion, restricted
 * prefixes); all the barcode-specific logic stays in the normalizer.
 */
@Component
public class BarcodeIdentifierScheme implements IdentifierScheme {

    @Override
    public String kind() {
        return "GTIN";
    }

    @Override
    public boolean handlesKey(String metadataKey) {
        if (metadataKey == null) {
            return false;
        }
        if (BarcodeNormalizer.isBarcodeAttributeKey(metadataKey)) {
            return true;
        }
        // Canonical pre-resolved list written by Stage-1 entity resolution (CSV of GTIN-14s).
        return "observedgtins".equals(metadataKey.trim().toLowerCase(Locale.ROOT));
    }

    @Override
    public Optional<String> canonicalize(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        BarcodeNormalizer.BarcodeId id = BarcodeNormalizer.parse(rawValue);
        return id.usableAsGlobalIdentity() ? Optional.of(id.gtin14()) : Optional.empty();
    }
}

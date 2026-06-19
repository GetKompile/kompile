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

import java.util.Optional;

/**
 * A pluggable kind of external/global identifier that can resolve to an entity — e.g. a product
 * barcode (GTIN), an ISBN, a DOI, an email address, a phone number, a DUNS number.
 *
 * <p>This is the one kind-specific seam in the otherwise generic identifier-resolution machinery
 * ({@link IdentityGraphService}): a scheme says which metadata keys carry its identifiers and how to
 * canonicalize a raw value into a stable global form. Everything else — collecting observations,
 * voting, collision detection, and materializing IDENTIFIER nodes + RESOLVES_TO edges — is generic.
 *
 * <p>Each implementation is a Spring bean; adding a new identifier kind is adding a bean (no enum to
 * edit, no central switch). {@link BarcodeIdentifierScheme} is the GTIN implementation;
 * {@link EmailIdentifierScheme} is a minimal second kind.
 */
public interface IdentifierScheme {

    /** Stable, uppercase kind label, e.g. {@code "GTIN"}, {@code "EMAIL"}, {@code "ISBN"}. */
    String kind();

    /** Whether a node-metadata key carries identifiers of this kind (e.g. {@code "upc"}, {@code "email"}). */
    boolean handlesKey(String metadataKey);

    /**
     * Canonicalize a raw identifier value into the stable global form used as identity (e.g. a
     * GTIN-14, a lower-cased email). Returns empty when the value is not a valid/usable identifier
     * of this kind (failed check digit, malformed address, …).
     */
    Optional<String> canonicalize(String rawValue);
}

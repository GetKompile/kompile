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

package ai.kompile.staging.archive;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the publisher of a Kompile archive.
 * Contains metadata about who created and signed the archive.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ArchivePublisher {

    /**
     * Publisher name (e.g., "Kompile Inc.").
     */
    @JsonProperty("name")
    private String name;

    /**
     * Publisher website URL.
     */
    @JsonProperty("url")
    private String url;

    /**
     * Publisher email contact.
     */
    @JsonProperty("email")
    private String email;

    /**
     * Optional signature for archive verification.
     * Format: "algorithm:hex_signature" (e.g., "sha256:abc123...")
     */
    @JsonProperty("signature")
    private String signature;

    /**
     * Public key ID or fingerprint for signature verification.
     */
    @JsonProperty("public_key_id")
    private String publicKeyId;

    /**
     * Creates the default Kompile publisher.
     */
    public static ArchivePublisher kompile() {
        return ArchivePublisher.builder()
                .name("Kompile Inc.")
                .url("https://kompile.ai")
                .email("support@kompile.ai")
                .build();
    }

    /**
     * Creates a publisher with just a name.
     */
    public static ArchivePublisher of(String name) {
        return ArchivePublisher.builder()
                .name(name)
                .build();
    }

    /**
     * Creates a publisher with name and URL.
     */
    public static ArchivePublisher of(String name, String url) {
        return ArchivePublisher.builder()
                .name(name)
                .url(url)
                .build();
    }

    /**
     * Returns true if this publisher has a signature.
     */
    public boolean hasSignature() {
        return signature != null && !signature.isEmpty();
    }

    /**
     * Returns true if this publisher has a public key ID.
     */
    public boolean hasPublicKey() {
        return publicKeyId != null && !publicKeyId.isEmpty();
    }
}

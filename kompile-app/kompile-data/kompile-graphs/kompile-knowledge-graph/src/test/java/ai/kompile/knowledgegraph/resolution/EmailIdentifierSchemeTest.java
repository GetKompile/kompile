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

package ai.kompile.knowledgegraph.resolution;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link EmailIdentifierScheme}: kind label, key handling, and email canonicalization.
 */
class EmailIdentifierSchemeTest {

    private final EmailIdentifierScheme scheme = new EmailIdentifierScheme();

    @Test
    void kind_isEmail() {
        assertEquals("EMAIL", scheme.kind());
    }

    @Test
    void handlesKey_trueForEmailKeys() {
        assertTrue(scheme.handlesKey("email"));
        assertTrue(scheme.handlesKey("email_address"));
    }

    @Test
    void handlesKey_falseForNonEmailKeys() {
        assertFalse(scheme.handlesKey("name"));
        assertFalse(scheme.handlesKey("upc"));
        assertFalse(scheme.handlesKey(null));
    }

    @Test
    void canonicalize_trimsAndLowercases() {
        Optional<String> result = scheme.canonicalize("  Bob@X.COM  ");
        assertTrue(result.isPresent());
        assertEquals("bob@x.com", result.get());
    }

    @Test
    void canonicalize_invalidEmailReturnsEmpty() {
        assertTrue(scheme.canonicalize("notanemail").isEmpty());
        assertTrue(scheme.canonicalize("missing@tld").isEmpty());
        assertTrue(scheme.canonicalize("@nodomain.com").isEmpty());
        assertTrue(scheme.canonicalize("noatsign.com").isEmpty());
    }

    @Test
    void canonicalize_nullReturnsEmpty() {
        assertTrue(scheme.canonicalize(null).isEmpty());
    }

    @Test
    void canonicalize_blankReturnsEmpty() {
        assertTrue(scheme.canonicalize("").isEmpty());
        assertTrue(scheme.canonicalize("   ").isEmpty());
    }
}

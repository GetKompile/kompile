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

package ai.kompile.crawl.graph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for the two risk-bearing helpers behind the live email-path fixes:
 * {@code asString} (List-typed recipient coercion) and {@code emailMessageNodeId}
 * (Message-ID-keyed threading so replies resolve to the real message node).
 */
class EmailGraphExtractorHelpersTest {

    // ── asString: IMAP emits to/cc/bcc as List<String>; reading as String dropped recipients ──

    @Test
    void asStringPassesThroughPlainString() {
        assertEquals("alice@acme.com", EmailGraphExtractor.asString("alice@acme.com"));
    }

    @Test
    void asStringJoinsListOfRecipients() {
        assertEquals("alice@acme.com, bob@acme.com",
                EmailGraphExtractor.asString(List.of("alice@acme.com", "bob@acme.com")));
    }

    @Test
    void asStringJoinsArrayOfRecipients() {
        assertEquals("a@x.com, b@y.com",
                EmailGraphExtractor.asString(new Object[]{"a@x.com", "b@y.com"}));
    }

    @Test
    void asStringSkipsBlankAndNullElements() {
        List<String> withGaps = java.util.Arrays.asList("a@x.com", "", null, "  ", "b@y.com");
        assertEquals("a@x.com, b@y.com", EmailGraphExtractor.asString(withGaps));
    }

    @Test
    void asStringReturnsNullForNullBlankOrEmpty() {
        assertNull(EmailGraphExtractor.asString(null));
        assertNull(EmailGraphExtractor.asString(""));
        assertNull(EmailGraphExtractor.asString("   "));
        assertNull(EmailGraphExtractor.asString(List.of()));
    }

    // ── emailMessageNodeId: threading must resolve regardless of angle brackets / case ──

    @Test
    void messageNodeIdIsBracketAndCaseInsensitive() {
        String bare = EmailGraphExtractor.emailMessageNodeId("abc123@mail.acme.com");
        String bracketed = EmailGraphExtractor.emailMessageNodeId("<abc123@mail.acme.com>");
        String upper = EmailGraphExtractor.emailMessageNodeId("<ABC123@MAIL.ACME.COM>");
        assertEquals(bare, bracketed, "bracketed In-Reply-To must resolve to the bare Message-ID node");
        assertEquals(bare, upper, "Message-ID matching must be case-insensitive");
        assertTrue(bare.startsWith("email-msg:"));
    }

    @Test
    void distinctMessageIdsProduceDistinctNodes() {
        assertNotEquals(EmailGraphExtractor.emailMessageNodeId("<m1@h>"),
                EmailGraphExtractor.emailMessageNodeId("<m2@h>"));
    }

    @Test
    void fallbackNodeIdIsDeterministicAndDistinctFromMessageIdNodes() {
        String a = EmailGraphExtractor.emailFallbackNodeId("alice@acme.com", "Q3 Report");
        String b = EmailGraphExtractor.emailFallbackNodeId("alice@acme.com", "Q3 Report");
        assertEquals(a, b, "fallback id must be deterministic");
        assertTrue(a.startsWith("email-msg:"));
        assertNotEquals(a, EmailGraphExtractor.emailMessageNodeId("alice@acme.com|Q3 Report"),
                "message-id-keyed and from|subject-keyed ids must not collide");
    }
}

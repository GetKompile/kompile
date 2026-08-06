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
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.core.graphrag.passes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceGroundedIdentityVariantsTest {

    @Test
    void extractsOnlyAliasesImmediatelyAttachedToTheExactMention() {
        PassContext context = PassContext.forChunk("chunk", "doc",
                "田中綾子 (Ayako Tanaka, A. Tanaka, ayako.tanaka@northstargoods.com) "
                        + "submitted the APAC June Forecast.");

        assertEquals(List.of("Ayako Tanaka", "A. Tanaka",
                        "ayako.tanaka@northstargoods.com"),
                SourceGroundedIdentityVariants.forMention(context, "田中綾子"));
    }

    @Test
    void supportsFullwidthParenthesesAndDelimitersWithNfkcNormalization() {
        PassContext context = PassContext.forChunk("chunk", "doc",
                "ＡＰＡＣ予測（APAC Forecast、ＡＰＡＣ June Forecast；forecast-apac@example.com） was approved.");

        assertEquals(List.of("APAC Forecast", "APAC June Forecast",
                        "forecast-apac@example.com"),
                SourceGroundedIdentityVariants.forMention(context, "ＡＰＡＣ予測"));
    }

    @Test
    void rejectsUnclosedAndNonAdjacentParentheticalText() {
        assertTrue(SourceGroundedIdentityVariants.forMention(
                PassContext.forChunk("chunk", "doc", "田中綾子 (Ayako Tanaka submitted a report."),
                "田中綾子").isEmpty());
        assertTrue(SourceGroundedIdentityVariants.forMention(
                PassContext.forChunk("chunk", "doc", "田中綾子 submitted a report (Ayako Tanaka)."),
                "田中綾子").isEmpty());
    }

    @Test
    void deduplicatesEquivalentFormsAndBoundsTheAliasBallot() {
        PassContext context = PassContext.forChunk("chunk", "doc",
                "Name (Ｎａｍｅ, Name, alias-1, alias-2, alias-3, alias-4, alias-5, alias-6, "
                        + "alias-7, alias-8, alias-9) appeared.");

        List<String> variants = SourceGroundedIdentityVariants.forMention(context, "Name");

        assertEquals(8, variants.size());
        assertEquals(List.of("alias-1", "alias-2", "alias-3", "alias-4", "alias-5",
                "alias-6", "alias-7", "alias-8"), variants);
    }
}

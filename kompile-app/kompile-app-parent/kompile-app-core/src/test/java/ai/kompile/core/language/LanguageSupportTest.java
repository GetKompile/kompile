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
package ai.kompile.core.language;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageSupportTest {

    @Test
    void wildcardAndMultilingualAliasesSupportAnyDeterminedLanguage() {
        assertTrue(LanguageSupport.supportsLanguage(List.of("*"), "ja"));
        assertTrue(LanguageSupport.supportsLanguage(List.of("multi"), "ja"));
        assertTrue(LanguageSupport.supportsLanguage(List.of("multilingual"), "ja-JP"));
        assertTrue(LanguageSupport.isUniversal(List.of("en", "multilingual")));
    }

    @Test
    void specificLanguagesMatchExactAndRegionVariants() {
        assertTrue(LanguageSupport.supportsLanguage(List.of("en"), "en-US"));
        assertTrue(LanguageSupport.supportsLanguage(List.of("ja-JP"), "ja_jp"));
        assertFalse(LanguageSupport.supportsLanguage(List.of("en"), "ja"));
    }

    @Test
    void undeterminedLanguageIsAllowedButNormalized() {
        assertTrue(LanguageSupport.supportsLanguage(List.of("en"), null));
        assertTrue(LanguageSupport.supportsLanguage(List.of("en"), "und"));
        assertEquals("ja-jp", LanguageSupport.normalizeLanguageCode("JA_JP"));
    }
}

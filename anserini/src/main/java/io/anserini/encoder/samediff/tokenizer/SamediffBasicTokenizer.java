/*
 *  Copyright 2025 Kompile Inc.
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  * http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 */

package io.anserini.encoder.samediff.tokenizer;

import org.apache.commons.lang3.StringUtils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class SamediffBasicTokenizer {
    private final boolean doLowerCase;
    private final boolean stripAccents;

    private static final Pattern CJK_CHARS_PATTERN = Pattern.compile("([\\u4E00-\\u9FFF\\u3040-\\u309F\\u30A0-\\u30FF\\u31F0-\\u31FF])");
    private static final Pattern PUNCTUATION_SPLIT_PATTERN = Pattern.compile("((?U)\\p{Punct})");


    public SamediffBasicTokenizer(boolean doLowerCase, boolean stripAccents) {
        this.doLowerCase = doLowerCase;
        this.stripAccents = stripAccents;
    }

    public SamediffBasicTokenizer(boolean doLowerCase) {
        this(doLowerCase, true);
    }


    public List<String> tokenize(String text) {
        if (text == null) {
            return Collections.emptyList();
        }

        String cleanedText = cleanText(text);
        cleanedText = tokenizeCjkChars(cleanedText);

        if (doLowerCase) {
            cleanedText = cleanedText.toLowerCase();
            if (stripAccents) {
                cleanedText = stripAccents(cleanedText);
            }
        }

        String[] whitespaceTokens = StringUtils.split(cleanedText, null);
        if (whitespaceTokens == null) {
            return Collections.emptyList();
        }

        List<String> outputTokens = new ArrayList<>();
        for (String token : whitespaceTokens) {
            outputTokens.addAll(splitOnPunctuation(token));
        }
        return outputTokens.stream().filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }

    private String cleanText(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isControl(c) || c == 0 || c == 0xFFFD) {
                continue;
            }
            if (isWhitespace(c)) {
                sb.append(" ");
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private String stripAccents(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (Character.getType(c) != Character.NON_SPACING_MARK) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isControl(char c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;
        }
        return Character.isISOControl(c);
    }

    private boolean isWhitespace(char c) {
        return Character.isWhitespace(c);
    }

    private String tokenizeCjkChars(String text) {
        StringBuilder sb = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (isCjkCharacter(c)) {
                sb.append(" ").append(c).append(" ");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private boolean isCjkCharacter(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) ||
                (c >= 0x3400 && c <= 0x4DBF) ||
                (c >= 0x20000 && c <= 0x2A6DF) ||
                (c >= 0x2A700 && c <= 0x2B73F) ||
                (c >= 0x2B740 && c <= 0x2B81F) ||
                (c >= 0x2B820 && c <= 0x2CEAF) ||
                (c >= 0xF900 && c <= 0xFAFF) ||
                (c >= 0x2F800 && c <= 0x2FA1F);
    }

    private List<String> splitOnPunctuation(String token) {
        if (token == null || token.isEmpty()) return Collections.emptyList();

        List<String> tokens = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        for (char c : token.toCharArray()) {
            if (isPunctuation(c)) {
                if (currentChunk.length() > 0) {
                    tokens.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                tokens.add(String.valueOf(c));
            } else {
                currentChunk.append(c);
            }
        }
        if (currentChunk.length() > 0) {
            tokens.add(currentChunk.toString());
        }
        return tokens;
    }

    private boolean isPunctuation(char c) {
        int cp = (int) c;
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) ||
                (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        return Character.isISOControl(c) || Character.isWhitespace(c) ? false :
                Character.isIdentifierIgnorable(c) ? false :
                        Character.isLetterOrDigit(c) ? false :
                                Character.getType(c) >= Character.CONNECTOR_PUNCTUATION && Character.getType(c) <= Character.OTHER_PUNCTUATION;
    }
}
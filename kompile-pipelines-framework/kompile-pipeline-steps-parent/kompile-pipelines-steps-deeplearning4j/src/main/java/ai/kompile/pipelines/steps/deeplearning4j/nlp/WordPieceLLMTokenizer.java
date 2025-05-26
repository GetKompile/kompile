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

// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-deeplearning4j/src/main/java/ai/kompile/pipelines/steps/deeplearning4j/nlp/
package ai.kompile.pipelines.steps.deeplearning4j.nlp;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;
// ND4J Pair is not typically used this way; using a simple custom Pair or java.util.AbstractMap.SimpleEntry if needed
// For sorting probabilities with indices, a custom class or direct index manipulation is better.

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern; // Not used in current simple whitespace split
import java.util.stream.Collectors;

@Slf4j
public class WordPieceLLMTokenizer implements DL4JLLMTokenizer {

    public static final String DEFAULT_UNK_TOKEN = "[UNK]";
    public static final String DEFAULT_CLS_TOKEN = "[CLS]"; // Often used as BOS for BERT-like models
    public static final String DEFAULT_SEP_TOKEN = "[SEP]"; // Often used as EOS for BERT-like models
    public static final String DEFAULT_PAD_TOKEN = "[PAD]";
    public static final String DEFAULT_SUBWORD_PREFIX = "##";

    private Map<String, Integer> vocabLookup = new LinkedHashMap<>();
    private Map<Integer, String> invVocabLookup = new LinkedHashMap<>();
    private String configUnkToken = DEFAULT_UNK_TOKEN;
    private String configClsToken = DEFAULT_CLS_TOKEN;
    private String configSepToken = DEFAULT_SEP_TOKEN;
    private String configPadToken = DEFAULT_PAD_TOKEN;
    private String configSubwordPrefix = DEFAULT_SUBWORD_PREFIX;
    private int configMaxTokenLength = 200;

    private int unkTokenIdValue = 0; // Default to 0 if not found, but should be in vocab
    private int clsTokenIdValue = 1; // Common defaults
    private int sepTokenIdValue = 2;
    private int padTokenIdValue = 0; // Often 0


    @Override
    public void initialize(String vocabUri, Map<String, String> config) throws Exception {
        if (config != null) {
            this.configUnkToken = config.getOrDefault("unkToken", DEFAULT_UNK_TOKEN);
            this.configClsToken = config.getOrDefault("clsToken", DEFAULT_CLS_TOKEN);
            this.configSepToken = config.getOrDefault("sepToken", DEFAULT_SEP_TOKEN);
            this.configPadToken = config.getOrDefault("padToken", DEFAULT_PAD_TOKEN);
            this.configSubwordPrefix = config.getOrDefault("subwordPrefix", DEFAULT_SUBWORD_PREFIX);
            this.configMaxTokenLength = Integer.parseInt(config.getOrDefault("maxTokenLength", "200"));
        }

        if (vocabUri == null || vocabUri.isEmpty()) {
            // Allow initialization without vocabUri for dummy/testing if necessary,
            // but log a warning as it won't be a functional WordPiece tokenizer.
            log.warn("Vocabulary URI is null or empty for WordPieceLLMTokenizer. " +
                    "Tokenizer will only know about default special tokens if they are not overridden by config.");
            // Ensure special tokens are in vocab even if no file, using their default strings
            // and assigning arbitrary low IDs if they are not already configured.
            // This part is tricky if vocab is truly empty. A real WordPiece needs a vocab file.
            // For a truly dummy tokenizer for special tokens only:
            addSpecialTokenToVocab(this.configPadToken, 0); // Pad often ID 0
            addSpecialTokenToVocab(this.configUnkToken, 1);
            addSpecialTokenToVocab(this.configClsToken, 2);
            addSpecialTokenToVocab(this.configSepToken, 3);

        } else {
            List<String> lines;
            try {
                File vocabFile = new File(new URI(vocabUri));
                lines = FileUtils.readLines(vocabFile, StandardCharsets.UTF_8);
            } catch (Exception e) {
                log.error("Failed to read vocabulary file from URI: {}", vocabUri, e);
                throw new IOException("Could not load vocabulary from: " + vocabUri, e);
            }

            for (int i = 0; i < lines.size(); i++) {
                String token = lines.get(i).trim();
                if (!token.isEmpty()) { // Ensure no empty lines are added as tokens
                    vocabLookup.put(token, i);
                    invVocabLookup.put(i, token);
                }
            }
        }

        // After loading vocab (or not), resolve the IDs for configured special tokens
        this.unkTokenIdValue = vocabLookup.getOrDefault(this.configUnkToken, 0); // Defaulting UNK to ID 0 if not found
        this.clsTokenIdValue = vocabLookup.getOrDefault(this.configClsToken, this.unkTokenIdValue);
        this.sepTokenIdValue = vocabLookup.getOrDefault(this.configSepToken, this.unkTokenIdValue);
        this.padTokenIdValue = vocabLookup.getOrDefault(this.configPadToken, 0); // Pad is often 0

        log.info("WordPieceLLMTokenizer initialized. Vocab size: {}, UNK: '{}' ({}), CLS/BOS: '{}' ({}), SEP/EOS: '{}' ({}), PAD: '{}' ({})",
                vocabLookup.size(), this.configUnkToken, this.unkTokenIdValue,
                this.configClsToken, this.clsTokenIdValue, this.configSepToken, this.sepTokenIdValue,
                this.configPadToken, this.padTokenIdValue);
    }

    private void addSpecialTokenToVocab(String token, int defaultId) {
        if (!vocabLookup.containsKey(token)) {
            int idToUse = vocabLookup.values().stream().max(Integer::compareTo).orElse(-1) + 1;
            if (idToUse < defaultId) idToUse = defaultId; // Try to use common default IDs if slot is free

            // Avoid ID collision if defaultId is already taken by another special token during this dummy init
            while(invVocabLookup.containsKey(idToUse)) {
                idToUse++;
            }
            vocabLookup.put(token, idToUse);
            invVocabLookup.put(idToUse, token);
        }
    }


    @Override
    public INDArray encode(String text) {
        List<String> tokens = tokenizeInternal(text);
        long[] ids = tokens.stream().mapToLong(token -> vocabLookup.getOrDefault(token, unkTokenIdValue)).toArray();
        return Nd4j.createFromArray(ids).reshape(1, ids.length); // batch_size = 1
    }

    @Override
    public Map<String, INDArray> batchEncode(List<String> texts, boolean addSpecialTokens) {
        List<List<Long>> allTokenIdLists = new ArrayList<>();
        int maxSequenceLength = 0;

        for (String text : texts) {
            List<String> wordPieceTokens = tokenizeInternal(text);
            List<Long> ids = new ArrayList<>();
            if (addSpecialTokens) {
                ids.add(getBosTokenId());
            }
            for (String token : wordPieceTokens) {
                ids.add((long) vocabLookup.getOrDefault(token, unkTokenIdValue));
            }
            if (addSpecialTokens) {
                ids.add(getEosTokenId());
            }
            allTokenIdLists.add(ids);
            if (ids.size() > maxSequenceLength) {
                maxSequenceLength = ids.size();
            }
        }

        long[][] inputIdsArray = new long[texts.size()][maxSequenceLength];
        long[][] attentionMaskArray = new long[texts.size()][maxSequenceLength];

        for (int i = 0; i < texts.size(); i++) {
            List<Long> currentIds = allTokenIdLists.get(i);
            int currentLength = currentIds.size();
            for (int j = 0; j < currentLength; j++) {
                inputIdsArray[i][j] = currentIds.get(j);
                attentionMaskArray[i][j] = 1; // Mark as not padded
            }
            // Pad the rest of the sequence
            for (int j = currentLength; j < maxSequenceLength; j++) {
                inputIdsArray[i][j] = getPadTokenId();
                attentionMaskArray[i][j] = 0; // Mark as padded
            }
        }

        Map<String, INDArray> result = new HashMap<>();
        result.put("input_ids", Nd4j.createFromArray(inputIdsArray));
        result.put("attention_mask", Nd4j.createFromArray(attentionMaskArray));
        return result;
    }


    private List<String> tokenizeInternal(String text) {
        if (text == null) return Collections.emptyList();
        // Basic text cleaning: lowercase, remove control characters.
        // Specific cleaning depends on the model the vocab was trained for.
        String cleanedText = text.toLowerCase().replaceAll("\\p{Cntrl}", "").trim();

        List<String> outputTokens = new ArrayList<>();
        for (String token : whitespaceTokenize(cleanedText)) {
            if (token.length() > configMaxTokenLength) {
                outputTokens.add(this.configUnkToken);
                continue;
            }

            // WordPiece algorithm (simplified greedy longest-match-first)
            List<String> subTokens = new ArrayList<>();
            int start = 0;
            while (start < token.length()) {
                int end = token.length();
                String bestSubToken = null;
                while (end > start) { // Find longest possible match
                    String currentSub = token.substring(start, end);
                    if (start > 0) { // Not the beginning of the word
                        currentSub = configSubwordPrefix + currentSub;
                    }
                    if (vocabLookup.containsKey(currentSub)) {
                        bestSubToken = currentSub;
                        break;
                    }
                    end--;
                }

                if (bestSubToken != null) {
                    subTokens.add(bestSubToken);
                    start += (bestSubToken.startsWith(configSubwordPrefix) ?
                            bestSubToken.length() - configSubwordPrefix.length() :
                            bestSubToken.length());
                } else {
                    // No subtoken found, treat the remaining part of the original token as UNK
                    // Or, if we want to be robust, break it character by character if individual chars are in vocab
                    subTokens.add(this.configUnkToken); // Fallback
                    break; // Cannot tokenize further
                }
            }
            outputTokens.addAll(subTokens);
        }
        return outputTokens;
    }

    private List<String> whitespaceTokenize(String text) {
        text = text.trim();
        if (StringUtils.isEmpty(text)) {
            return Collections.emptyList();
        }
        // Split by whitespace. More sophisticated pre-tokenization (e.g. punctuation splitting)
        // is common in BERT-like tokenizers but omitted here for simplicity.
        return Arrays.asList(text.split("\\s+"));
    }

    @Override
    public String decode(long[] tokenIds, boolean skipSpecialTokens) {
        StringBuilder sb = new StringBuilder();
        for (long id : tokenIds) {
            if (skipSpecialTokens && (id == clsTokenIdValue || id == sepTokenIdValue || id == padTokenIdValue)) {
                continue;
            }
            String token = invVocabLookup.getOrDefault((int) id, configUnkToken);
            if (token.startsWith(configSubwordPrefix)) {
                sb.append(token.substring(configSubwordPrefix.length()));
            } else {
                if (sb.length() > 0) { // Add space if not first token and not a subword continuation
                    sb.append(" ");
                }
                sb.append(token);
            }
        }
        // Post-processing: clean up extra spaces around punctuation, etc.
        // This is a simplified version.
        return sb.toString()
                .replace(" ##", "") // BERT specific cleanup for ## if not handled perfectly by logic above
                .replace("##", "")
                .replaceAll(" (?=[.,'!?;:])", "") // Remove space before punctuation
                .trim();
    }

    @Override
    public List<String> batchDecode(INDArray batchTokenIds, boolean skipSpecialTokens) {
        List<String> decodedTexts = new ArrayList<>();
        for (int i = 0; i < batchTokenIds.rows(); i++) {
            INDArray singleSequence = batchTokenIds.getRow(i);
            long[] ids = new long[(int)singleSequence.length()];
            for(int j = 0; j < singleSequence.length(); j++) {
                ids[j] = singleSequence.getLong(j); // Assuming long is fine, or use getInt if vocab IDs are int
            }
            decodedTexts.add(decode(ids, skipSpecialTokens));
        }
        return decodedTexts;
    }

    @Override
    public int getVocabSize() { return vocabLookup.size(); }
    @Override
    public long getEosTokenId() { return sepTokenIdValue; }
    @Override
    public long getBosTokenId() { return clsTokenIdValue; }
    @Override
    public long getPadTokenId() { return padTokenIdValue; }
    @Override
    public long getUnkTokenId() { return unkTokenIdValue; }

    @Override
    public String getEosToken() { return configSepToken; }
    @Override
    public String getBosToken() { return configClsToken; }
    @Override
    public String getPadToken() { return configPadToken; }
    @Override
    public String getUnkToken() { return configUnkToken; }
}
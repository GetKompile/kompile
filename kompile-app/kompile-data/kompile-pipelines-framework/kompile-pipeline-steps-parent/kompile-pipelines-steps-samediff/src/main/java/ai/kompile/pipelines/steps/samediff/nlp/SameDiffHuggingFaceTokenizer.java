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

package ai.kompile.pipelines.steps.samediff.nlp;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.deeplearning4j.llm.tokenizer.Encoding;
import org.eclipse.deeplearning4j.llm.tokenizer.HuggingFaceTokenizer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapter that exposes DL4J's {@link HuggingFaceTokenizer} (Rust-backed BPE/SentencePiece/WordPiece)
 * via the {@link SameDiffLLMTokenizer} interface used by {@link
 * ai.kompile.pipelines.steps.samediff.llm.SameDiffLanguageModelStepRunner}.
 *
 * <p>This makes the SameDiff LLM step runner usable with full HuggingFace tokenizer
 * compatibility (Qwen, GPT-2, LLaMA, etc.) without changing the existing runner
 * abstraction.</p>
 *
 * <p>Tokenizer is loaded from a {@code tokenizer.json} path supplied as either
 * a file URI (file://...) or a plain filesystem path, or alternatively from a
 * directory containing a {@code tokenizer.json}.</p>
 */
@Slf4j
public class SameDiffHuggingFaceTokenizer implements SameDiffLLMTokenizer {

    private HuggingFaceTokenizer delegate;

    /** Optional explicit special-token IDs (overrides delegate values when >= 0). */
    private int padTokenId = -1;
    private int bosTokenId = -1;
    private int eosTokenId = -1;
    private int unkTokenId = -1;

    private String padToken = "[PAD]";
    private String bosToken = "<|im_start|>";
    private String eosToken = "<|im_end|>";
    private String unkToken = "[UNK]";

    @Override
    public void initialize(String vocabUri, Map<String, String> config) throws Exception {
        if (vocabUri == null || vocabUri.isEmpty()) {
            throw new IllegalArgumentException(
                "SameDiffHuggingFaceTokenizer requires a tokenizer.json path (vocabUri).");
        }

        File tokenizerFile = resolveTokenizerFile(vocabUri);
        if (!tokenizerFile.exists()) {
            throw new java.io.IOException("Tokenizer file not found: " + tokenizerFile.getAbsolutePath());
        }

        if (tokenizerFile.isDirectory()) {
            this.delegate = HuggingFaceTokenizer.fromDirectory(tokenizerFile);
        } else {
            this.delegate = HuggingFaceTokenizer.fromFile(tokenizerFile);
        }

        if (config != null) {
            // Optional explicit overrides for special token strings/ids
            if (config.containsKey("padToken"))  this.padToken = config.get("padToken");
            if (config.containsKey("bosToken"))  this.bosToken = config.get("bosToken");
            if (config.containsKey("eosToken"))  this.eosToken = config.get("eosToken");
            if (config.containsKey("unkToken"))  this.unkToken = config.get("unkToken");

            if (config.containsKey("padTokenId")) this.padTokenId = parseIntOrDefault(config.get("padTokenId"), -1);
            if (config.containsKey("bosTokenId")) this.bosTokenId = parseIntOrDefault(config.get("bosTokenId"), -1);
            if (config.containsKey("eosTokenId")) this.eosTokenId = parseIntOrDefault(config.get("eosTokenId"), -1);
            if (config.containsKey("unkTokenId")) this.unkTokenId = parseIntOrDefault(config.get("unkTokenId"), -1);
        }

        // Defaults from the underlying native tokenizer when overrides not supplied
        if (this.padTokenId < 0) this.padTokenId = delegate.getPadTokenId();
        if (this.bosTokenId < 0) this.bosTokenId = delegate.getBosTokenId();
        if (this.eosTokenId < 0) this.eosTokenId = delegate.getEosTokenId();
        if (this.unkTokenId < 0) this.unkTokenId = delegate.getUnkTokenId();

        log.info("Initialized HuggingFace tokenizer from {} (vocab size: {}, pad/bos/eos/unk: {}/{}/{}/{})",
                tokenizerFile.getAbsolutePath(), delegate.getVocabSize(),
                padTokenId, bosTokenId, eosTokenId, unkTokenId);
    }

    public static File resolveTokenizerFile(String vocabUri) {
        // Accept file:// URIs and plain filesystem paths
        try {
            if (vocabUri.startsWith("file:")) {
                return new File(new URI(vocabUri));
            }
        } catch (Exception e) {
            // fall through to plain-path handling
        }
        return new File(vocabUri);
    }

    private static int parseIntOrDefault(String s, int defaultValue) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void requireInitialized() {
        if (delegate == null) {
            throw new IllegalStateException("SameDiffHuggingFaceTokenizer not initialized.");
        }
    }

    @Override
    public INDArray encode(String text) {
        requireInitialized();
        Encoding enc = delegate.encode(text, true);
        int[] ids = enc.getIds();
        long[] longIds = new long[ids.length];
        for (int i = 0; i < ids.length; i++) longIds[i] = ids[i];
        return Nd4j.createFromArray(longIds).reshape(1, longIds.length);
    }

    @Override
    public Map<String, INDArray> batchEncode(List<String> texts, boolean addSpecialTokens) {
        requireInitialized();
        List<int[]> idsList = new ArrayList<>(texts.size());
        int maxLen = 0;
        for (String text : texts) {
            Encoding enc = delegate.encode(text, addSpecialTokens);
            int[] ids = enc.getIds() != null ? enc.getIds() : new int[0];
            idsList.add(ids);
            if (ids.length > maxLen) maxLen = ids.length;
        }

        long padId = getPadTokenId();
        long[][] inputIds = new long[texts.size()][maxLen];
        long[][] attentionMask = new long[texts.size()][maxLen];

        for (int i = 0; i < texts.size(); i++) {
            int[] ids = idsList.get(i);
            for (int j = 0; j < ids.length; j++) {
                inputIds[i][j] = ids[j];
                attentionMask[i][j] = 1L;
            }
            for (int j = ids.length; j < maxLen; j++) {
                inputIds[i][j] = padId;
                attentionMask[i][j] = 0L;
            }
        }

        Map<String, INDArray> result = new HashMap<>();
        result.put("input_ids", Nd4j.createFromArray(inputIds));
        result.put("attention_mask", Nd4j.createFromArray(attentionMask));
        return result;
    }

    @Override
    public String decode(long[] tokenIds, boolean skipSpecialTokens) {
        requireInitialized();
        int[] ids = new int[tokenIds.length];
        for (int i = 0; i < tokenIds.length; i++) {
            ids[i] = (int) tokenIds[i];
        }
        return delegate.decode(ids, skipSpecialTokens);
    }

    @Override
    public List<String> batchDecode(INDArray batchTokenIds, boolean skipSpecialTokens) {
        requireInitialized();
        if (batchTokenIds == null) return Collections.emptyList();
        int rows = (int) batchTokenIds.rows();
        List<String> out = new ArrayList<>(rows);
        for (int r = 0; r < rows; r++) {
            INDArray row = batchTokenIds.getRow(r);
            long[] longIds = new long[(int) row.length()];
            for (int j = 0; j < longIds.length; j++) {
                longIds[j] = row.getLong(j);
            }
            out.add(decode(longIds, skipSpecialTokens));
        }
        return out;
    }

    @Override
    public int getVocabSize() {
        requireInitialized();
        return delegate.getVocabSize();
    }

    @Override public long getEosTokenId() { return eosTokenId; }
    @Override public long getBosTokenId() { return bosTokenId; }
    @Override public long getPadTokenId() { return padTokenId >= 0 ? padTokenId : 0L; }
    @Override public long getUnkTokenId() { return unkTokenId >= 0 ? unkTokenId : 0L; }
    @Override public String getEosToken() { return eosToken; }
    @Override public String getBosToken() { return bosToken; }
    @Override public String getPadToken() { return padToken; }
    @Override public String getUnkToken() { return unkToken; }

    /** Close the underlying native tokenizer. */
    public void close() {
        if (delegate != null) {
            try {
                delegate.close();
            } catch (Exception ignored) {
            } finally {
                delegate = null;
            }
        }
    }
}

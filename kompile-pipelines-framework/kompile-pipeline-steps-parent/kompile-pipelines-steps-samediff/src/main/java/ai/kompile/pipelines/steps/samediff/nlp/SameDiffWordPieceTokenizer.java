// Location: kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-samediff/src/main/java/ai/kompile/pipelines/steps/samediff/nlp/
package ai.kompile.pipelines.steps.samediff.nlp;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.factory.Nd4j;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

public class SameDiffWordPieceTokenizer implements SameDiffLLMTokenizer {

    public static final String DEFAULT_UNK_TOKEN = "[UNK]";
    public static final String DEFAULT_CLS_TOKEN = "[CLS]"; // BOS for BERT-like
    public static final String DEFAULT_SEP_TOKEN = "[SEP]"; // EOS for BERT-like
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

    private int unkTokenIdValue = 0;
    private int clsTokenIdValue = 1;
    private int sepTokenIdValue = 2;
    private int padTokenIdValue = 0;


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
            // Initialize with default special tokens if no vocab file is provided
            addSpecialTokenToVocab(this.configPadToken, 0);
            addSpecialTokenToVocab(this.configUnkToken, 1);
            addSpecialTokenToVocab(this.configClsToken, 2);
            addSpecialTokenToVocab(this.configSepToken, 3);
        } else {
            List<String> lines;
            try {
                File vocabFile = new File(new URI(vocabUri));
                lines = FileUtils.readLines(vocabFile, StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IOException("Could not load vocabulary from: " + vocabUri, e);
            }

            for (int i = 0; i < lines.size(); i++) {
                String token = lines.get(i).trim();
                if (!token.isEmpty()) {
                    vocabLookup.put(token, i);
                    invVocabLookup.put(i, token);
                }
            }
        }

        this.unkTokenIdValue = vocabLookup.getOrDefault(this.configUnkToken, 0);
        this.clsTokenIdValue = vocabLookup.getOrDefault(this.configClsToken, this.unkTokenIdValue); // Fallback to UNK if not in vocab
        this.sepTokenIdValue = vocabLookup.getOrDefault(this.configSepToken, this.unkTokenIdValue);
        this.padTokenIdValue = vocabLookup.getOrDefault(this.configPadToken, 0); // Pad is often 0 and should be in vocab
    }

    private void addSpecialTokenToVocab(String token, int preferredId) {
        if (!vocabLookup.containsKey(token)) {
            int idToUse = preferredId;
            // If preferredId is taken, find next available. This is a simple approach.
            // A more robust way would be to reserve IDs or ensure vocab file contains them.
            while(invVocabLookup.containsKey(idToUse) && invVocabLookup.size() < Integer.MAX_VALUE) {
                idToUse = vocabLookup.values().stream().max(Integer::compareTo).orElse(-1) + 1;
                if(idToUse < preferredId) idToUse = preferredId; // Try to stick to common IDs if possible
                // Safety break for very small or full vocabs during this dummy init
                if (idToUse > preferredId + 10 && vocabLookup.size() < 10) break;
            }
            vocabLookup.put(token, idToUse);
            invVocabLookup.put(idToUse, token);
        }
    }

    @Override
    public INDArray encode(String text) {
        List<String> tokens = tokenizeInternal(text);
        long[] ids = tokens.stream().mapToLong(token -> vocabLookup.getOrDefault(token, unkTokenIdValue)).toArray();
        return Nd4j.createFromArray(ids).reshape(1, ids.length);
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
                attentionMaskArray[i][j] = 1;
            }
            for (int j = currentLength; j < maxSequenceLength; j++) {
                inputIdsArray[i][j] = getPadTokenId();
                attentionMaskArray[i][j] = 0;
            }
        }

        Map<String, INDArray> result = new HashMap<>();
        result.put("input_ids", Nd4j.createFromArray(inputIdsArray));
        result.put("attention_mask", Nd4j.createFromArray(attentionMaskArray));
        return result;
    }

    private List<String> tokenizeInternal(String text) {
        if (text == null) return Collections.emptyList();
        String cleanedText = text.toLowerCase().replaceAll("\\p{Cntrl}", "").trim();
        List<String> outputTokens = new ArrayList<>();

        for (String token : whitespaceTokenize(cleanedText)) {
            if (token.length() > configMaxTokenLength) {
                outputTokens.add(this.configUnkToken);
                continue;
            }
            List<String> subTokens = new ArrayList<>();
            int start = 0;
            while (start < token.length()) {
                int end = token.length();
                String bestSubToken = null;
                while (end > start) {
                    String currentSub = token.substring(start, end);
                    if (start > 0) {
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
                    subTokens.add(this.configUnkToken);
                    break;
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
                if (sb.length() > 0 && !isPunctuation(token) && !token.startsWith("'")) {
                    sb.append(" ");
                }
                sb.append(token);
            }
        }
        return sb.toString().replace(" ##", "").replace("##", "").replaceAll(" (?=[.,'!?;:])", "").trim();
    }

    private boolean isPunctuation(String token) {
        return token.length() == 1 && !Character.isLetterOrDigit(token.charAt(0)) && !Character.isWhitespace(token.charAt(0));
    }

    @Override
    public List<String> batchDecode(INDArray batchTokenIds, boolean skipSpecialTokens) {
        List<String> decodedTexts = new ArrayList<>();
        for (int i = 0; i < batchTokenIds.rows(); i++) {
            INDArray singleSequence = batchTokenIds.getRow(i);
            long[] ids = new long[(int)singleSequence.length()];
            for(int j = 0; j < singleSequence.length(); j++) {
                ids[j] = singleSequence.getLong(j);
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

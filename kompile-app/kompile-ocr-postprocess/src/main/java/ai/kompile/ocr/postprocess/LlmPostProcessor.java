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

package ai.kompile.ocr.postprocess;

import ai.kompile.ocr.OcrRegion;
import ai.kompile.ocr.OcrResult;
import ai.kompile.ocr.structured.ExtractedField;
import ai.kompile.ocr.structured.FieldType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM-based post-processor for OCR output.
 * Uses configured LLM (OpenAI, Anthropic, etc.) for:
 * - Error correction
 * - Format normalization
 * - Handwriting interpretation
 * - Field extraction
 */
@Component
@ConditionalOnBean(ChatModel.class)
public class LlmPostProcessor implements OcrPostProcessor {

    private static final Logger logger = LoggerFactory.getLogger(LlmPostProcessor.class);

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    private static final String CORRECTION_SYSTEM_PROMPT = """
        You are an OCR correction assistant. Your task is to fix OCR errors in text while preserving the original meaning and structure.

        Common OCR errors to fix:
        - Character substitutions (0/O, 1/l/I, rn/m, etc.)
        - Missing or extra spaces
        - Broken words
        - Incorrect punctuation

        Rules:
        - Only fix obvious OCR errors
        - Preserve original formatting (line breaks, spacing)
        - Do not add or remove content
        - Return ONLY the corrected text, no explanations
        """;

    private static final String FIELD_EXTRACTION_PROMPT = """
        You are a document field extraction assistant. Extract the requested fields from the OCR text.

        Return a JSON object with the following structure:
        {
          "fields": [
            {"label": "field_name", "value": "extracted_value", "confidence": 0.95}
          ]
        }

        Only extract fields that are clearly present in the text.
        Use null for fields that cannot be found.
        """;

    private static final String HANDWRITING_PROMPT = """
        You are a handwriting interpretation assistant. The following text was extracted from handwritten content using OCR.
        The OCR confidence is %f%%. Please interpret what was likely written.

        Consider:
        - Common letter confusions in handwriting
        - Context clues from surrounding text
        - Likely intended words/phrases

        Return only the interpreted text, no explanations.
        """;

    @Autowired
    public LlmPostProcessor(ChatModel chatModel) {
        this.chatModel = chatModel;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "LLM Post-Processor";
    }

    @Override
    public String getDescription() {
        return "Uses LLM for OCR error correction, field extraction, and handwriting interpretation";
    }

    @Override
    public boolean isAvailable() {
        return chatModel != null;
    }

    @Override
    public OcrResult process(OcrResult ocrResult, PostProcessConfig config) {
        if (!isAvailable()) {
            logger.warn("LLM post-processor not available, returning original result");
            return ocrResult;
        }

        try {
            List<OcrRegion> processedRegions = new ArrayList<>();

            for (OcrRegion region : ocrResult.getRegions()) {
                OcrRegion processed = processRegion(region, config);
                processedRegions.add(processed);
            }

            // Rebuild full text from processed regions
            StringBuilder fullText = new StringBuilder();
            for (OcrRegion region : processedRegions) {
                if (fullText.length() > 0) {
                    fullText.append("\n");
                }
                fullText.append(region.getText());
            }

            return OcrResult.builder()
                    .id(ocrResult.getId())
                    .sourceId(ocrResult.getSourceId())
                    .pageNumber(ocrResult.getPageNumber())
                    .totalPages(ocrResult.getTotalPages())
                    .imageWidth(ocrResult.getImageWidth())
                    .imageHeight(ocrResult.getImageHeight())
                    .regions(processedRegions)
                    .fullText(fullText.toString())
                    .overallConfidence(ocrResult.getOverallConfidence())
                    .detectionModelId(ocrResult.getDetectionModelId())
                    .recognitionModelId(ocrResult.getRecognitionModelId())
                    .processingTimeMs(ocrResult.getProcessingTimeMs())
                    .timestamp(ocrResult.getTimestamp())
                    .success(true)
                    .build();

        } catch (Exception e) {
            logger.error("LLM post-processing failed: {}", e.getMessage(), e);
            return ocrResult;
        }
    }

    @Override
    public String correctText(String text, String context) {
        if (!isAvailable() || text == null || text.isEmpty()) {
            return text;
        }

        try {
            String systemPrompt = CORRECTION_SYSTEM_PROMPT;
            if (context != null && !context.isEmpty()) {
                systemPrompt += "\n\nDocument context: " + context;
            }

            ChatClient chatClient = ChatClient.create(chatModel);
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user("Correct the following OCR text:\n\n" + text)
                    .call()
                    .content();

            return response != null ? response.trim() : text;

        } catch (Exception e) {
            logger.error("Text correction failed: {}", e.getMessage());
            return text;
        }
    }

    @Override
    public List<ExtractedField> extractFields(String text, List<String> fieldTypes) {
        List<ExtractedField> fields = new ArrayList<>();

        if (!isAvailable() || text == null || text.isEmpty() || fieldTypes.isEmpty()) {
            return fields;
        }

        try {
            String prompt = String.format(
                    "Extract the following fields from this text: %s\n\nText:\n%s",
                    String.join(", ", fieldTypes),
                    text
            );

            ChatClient chatClient = ChatClient.create(chatModel);
            String response = chatClient.prompt()
                    .system(FIELD_EXTRACTION_PROMPT)
                    .user(prompt)
                    .call()
                    .content();

            // Parse JSON response
            JsonNode root = objectMapper.readTree(response);
            JsonNode fieldsNode = root.get("fields");

            if (fieldsNode != null && fieldsNode.isArray()) {
                for (JsonNode fieldNode : fieldsNode) {
                    String label = fieldNode.get("label").asText();
                    String value = fieldNode.get("value").asText();
                    double confidence = fieldNode.has("confidence") ?
                            fieldNode.get("confidence").asDouble() : 0.9;

                    if (value != null && !value.equals("null")) {
                        fields.add(ExtractedField.builder()
                                .label(label)
                                .value(value)
                                .fieldType(mapFieldType(label))
                                .confidence(confidence)
                                .extractedBy(getName())
                                .build());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Field extraction failed: {}", e.getMessage());
        }

        return fields;
    }

    @Override
    public String interpretHandwriting(String text, double confidence) {
        if (!isAvailable() || text == null || text.isEmpty()) {
            return text;
        }

        try {
            String systemPrompt = String.format(HANDWRITING_PROMPT, confidence * 100);

            ChatClient chatClient = ChatClient.create(chatModel);
            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user("Interpret this handwritten text:\n\n" + text)
                    .call()
                    .content();

            return response != null ? response.trim() : text;

        } catch (Exception e) {
            logger.error("Handwriting interpretation failed: {}", e.getMessage());
            return text;
        }
    }

    /**
     * Processes a single OCR region.
     */
    private OcrRegion processRegion(OcrRegion region, PostProcessConfig config) {
        String text = region.getText();

        // Apply correction if enabled and confidence is low
        if (config.enableCorrection() &&
            region.getCombinedConfidence() < config.minConfidenceThreshold()) {
            text = correctText(text, config.documentContext());
        }

        // Interpret handwriting if enabled
        if (config.enableHandwritingInterpretation() && region.isHandwriting()) {
            text = interpretHandwriting(text, region.getCombinedConfidence());
        }

        return OcrRegion.builder()
                .index(region.getIndex())
                .text(text)
                .boundingBox(region.getBoundingBox())
                .detectionConfidence(region.getDetectionConfidence())
                .recognitionConfidence(region.getRecognitionConfidence())
                .regionType(region.getRegionType())
                .handwriting(region.isHandwriting())
                .language(region.getLanguage())
                .readingOrder(region.getReadingOrder())
                .parentIndex(region.getParentIndex())
                .build();
    }

    /**
     * Maps field label to FieldType.
     */
    private FieldType mapFieldType(String label) {
        String lower = label.toLowerCase();

        if (lower.contains("name")) return FieldType.NAME;
        if (lower.contains("email")) return FieldType.EMAIL;
        if (lower.contains("phone")) return FieldType.PHONE;
        if (lower.contains("address")) return FieldType.ADDRESS;
        if (lower.contains("date")) return FieldType.DATE;
        if (lower.contains("amount") || lower.contains("total")) return FieldType.AMOUNT;
        if (lower.contains("invoice")) return FieldType.INVOICE_NUMBER;
        if (lower.contains("ssn") || lower.contains("social security")) return FieldType.SSN;

        return FieldType.CUSTOM;
    }
}

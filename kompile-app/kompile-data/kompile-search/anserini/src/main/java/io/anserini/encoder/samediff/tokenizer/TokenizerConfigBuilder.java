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

package io.anserini.encoder.samediff.tokenizer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Helper class to build tokenizer configurations for the Rust tokenizers library
 * from existing vocabulary files and settings.
 */
public class TokenizerConfigBuilder {
    
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    
    /**
     * Create a BERT-compatible tokenizer configuration from a vocabulary file
     * @param vocabFile Vocabulary file (one token per line)
     * @param doLowerCase Whether to apply lowercase normalization
     * @param stripAccents Whether to strip accents
     * @param unkToken Unknown token (default: "[UNK]")
     * @return JSON configuration string
     * @throws IOException if vocabulary file cannot be read
     */
    public static String createBertConfig(File vocabFile, 
                                         boolean doLowerCase, 
                                         boolean stripAccents,
                                         String unkToken) throws IOException {
        if (unkToken == null) {
            unkToken = "[UNK]";
        }
        
        List<String> vocab = Files.readAllLines(vocabFile.toPath());
        
        JsonObject config = new JsonObject();
        config.addProperty("version", "1.0");
        
        // Model configuration (WordPiece)
        JsonObject model = new JsonObject();
        model.addProperty("type", "WordPiece");
        model.add("vocab", createVocabObject(vocab));
        model.addProperty("unk_token", unkToken);
        model.addProperty("continuing_subword_prefix", "##");
        model.addProperty("max_input_chars_per_word", 100);
        config.add("model", model);
        
        // Normalizer configuration
        config.add("normalizer", createNormalizer(doLowerCase, stripAccents));
        
        // Pre-tokenizer configuration
        config.add("pre_tokenizer", createBertPreTokenizer());
        
        // Post-processor configuration
        config.add("post_processor", createBertPostProcessor());
        
        // Decoder configuration
        config.add("decoder", createWordPieceDecoder());
        
        return GSON.toJson(config);
    }
    
    /**
     * Create a BERT-compatible tokenizer configuration with default settings
     */
    public static String createBertConfig(File vocabFile) throws IOException {
        return createBertConfig(vocabFile, true, true, "[UNK]");
    }
    
    /**
     * Create vocabulary object from token list
     */
    private static JsonObject createVocabObject(List<String> vocab) {
        JsonObject vocabObj = new JsonObject();
        for (int i = 0; i < vocab.size(); i++) {
            vocabObj.addProperty(vocab.get(i), i);
        }
        return vocabObj;
    }
    
    /**
     * Create normalizer configuration
     */
    private static JsonObject createNormalizer(boolean doLowerCase, boolean stripAccents) {
        if (!doLowerCase && !stripAccents) {
            return null; // No normalization
        }
        
        JsonArray sequence = new JsonArray();
        
        // Always start with NFD normalization for proper Unicode handling
        JsonObject nfd = new JsonObject();
        nfd.addProperty("type", "NFD");
        sequence.add(nfd);
        
        if (stripAccents) {
            JsonObject stripAccentsNorm = new JsonObject();
            stripAccentsNorm.addProperty("type", "StripAccents");
            sequence.add(stripAccentsNorm);
        }
        
        if (doLowerCase) {
            JsonObject lowercase = new JsonObject();
            lowercase.addProperty("type", "Lowercase");
            sequence.add(lowercase);
        }
        
        // Add NFC normalization at the end
        JsonObject nfc = new JsonObject();
        nfc.addProperty("type", "NFC");
        sequence.add(nfc);
        
        JsonObject normalizer = new JsonObject();
        normalizer.addProperty("type", "Sequence");
        normalizer.add("normalizers", sequence);
        
        return normalizer;
    }
    
    /**
     * Create BERT pre-tokenizer configuration
     */
    private static JsonObject createBertPreTokenizer() {
        JsonObject preTokenizer = new JsonObject();
        preTokenizer.addProperty("type", "BertPreTokenizer");
        return preTokenizer;
    }
    
    /**
     * Create BERT post-processor configuration
     */
    private static JsonObject createBertPostProcessor() {
        JsonObject postProcessor = new JsonObject();
        postProcessor.addProperty("type", "BertProcessing");
        
        // SEP token configuration
        JsonArray sep = new JsonArray();
        sep.add("[SEP]");
        sep.add(102); // Standard BERT SEP token ID
        postProcessor.add("sep", sep);
        
        // CLS token configuration  
        JsonArray cls = new JsonArray();
        cls.add("[CLS]");
        cls.add(101); // Standard BERT CLS token ID
        postProcessor.add("cls", cls);
        
        return postProcessor;
    }
    
    /**
     * Create WordPiece decoder configuration
     */
    private static JsonObject createWordPieceDecoder() {
        JsonObject decoder = new JsonObject();
        decoder.addProperty("type", "WordPiece");
        decoder.addProperty("prefix", "##");
        decoder.addProperty("cleanup", true);
        return decoder;
    }
    
    /**
     * Create a simple tokenizer configuration for testing
     */
    public static String createSimpleConfig() {
        JsonObject config = new JsonObject();
        config.addProperty("version", "1.0");
        
        // Simple model with basic vocabulary
        JsonObject model = new JsonObject();
        model.addProperty("type", "WordPiece");
        
        JsonObject vocab = new JsonObject();
        String[] basicTokens = {
            "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]",
            "hello", "world", "this", "is", "a", "test"
        };
        for (int i = 0; i < basicTokens.length; i++) {
            vocab.addProperty(basicTokens[i], i);
        }
        model.add("vocab", vocab);
        model.addProperty("unk_token", "[UNK]");
        config.add("model", model);
        
        return GSON.toJson(config);
    }
    
    /**
     * Validate a tokenizer configuration by checking required fields
     */
    public static boolean isValidConfig(String jsonConfig) {
        try {
            JsonObject config = GSON.fromJson(jsonConfig, JsonObject.class);
            
            // Check required top-level fields
            if (!config.has("model")) {
                return false;
            }
            
            JsonObject model = config.getAsJsonObject("model");
            if (!model.has("type") || !model.has("vocab")) {
                return false;
            }
            
            // Basic validation passed
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Create a configuration with custom special tokens
     */
    public static String createCustomBertConfig(File vocabFile,
                                               String clsToken,
                                               String sepToken, 
                                               String padToken,
                                               String unkToken,
                                               String maskToken,
                                               boolean doLowerCase) throws IOException {
        
        List<String> vocab = Files.readAllLines(vocabFile.toPath());
        
        // Find token IDs
        int clsId = findTokenId(vocab, clsToken);
        int sepId = findTokenId(vocab, sepToken);
        int padId = findTokenId(vocab, padToken);
        int unkId = findTokenId(vocab, unkToken);
        int maskId = findTokenId(vocab, maskToken);
        
        JsonObject config = new JsonObject();
        config.addProperty("version", "1.0");
        
        // Model
        JsonObject model = new JsonObject();
        model.addProperty("type", "WordPiece");
        model.add("vocab", createVocabObject(vocab));
        model.addProperty("unk_token", unkToken);
        config.add("model", model);
        
        // Normalizer
        if (doLowerCase) {
            config.add("normalizer", createNormalizer(true, true));
        }
        
        // Pre-tokenizer
        config.add("pre_tokenizer", createBertPreTokenizer());
        
        // Custom post-processor
        JsonObject postProcessor = new JsonObject();
        postProcessor.addProperty("type", "BertProcessing");
        
        JsonArray sep = new JsonArray();
        sep.add(sepToken);
        sep.add(sepId);
        postProcessor.add("sep", sep);
        
        JsonArray cls = new JsonArray();
        cls.add(clsToken);
        cls.add(clsId);
        postProcessor.add("cls", cls);
        
        config.add("post_processor", postProcessor);
        
        // Decoder
        config.add("decoder", createWordPieceDecoder());
        
        return GSON.toJson(config);
    }
    
    /**
     * Find token ID in vocabulary list
     */
    private static int findTokenId(List<String> vocab, String token) {
        int index = vocab.indexOf(token);
        if (index == -1) {
            throw new IllegalArgumentException("Token '" + token + "' not found in vocabulary");
        }
        return index;
    }
    
    /**
     * Save tokenizer configuration to file
     */
    public static void saveConfig(String jsonConfig, File outputFile) throws IOException {
        Files.write(outputFile.toPath(), jsonConfig.getBytes());
    }
    
    /**
     * Create a migration helper that converts old SameDiff vocabulary to new format
     */
    public static class MigrationHelper {
        
        /**
         * Convert existing SameDiff vocabulary file to Rust tokenizer format
         */
        public static String migrateSameDiffVocab(File vocabFile, 
                                                boolean doLowerCase,
                                                boolean stripAccents) throws IOException {
            
            System.out.println("Migrating vocabulary from: " + vocabFile.getAbsolutePath());
            
            // Validate vocabulary file
            if (!vocabFile.exists()) {
                throw new IOException("Vocabulary file not found: " + vocabFile.getAbsolutePath());
            }
            
            List<String> vocab = Files.readAllLines(vocabFile.toPath());
            if (vocab.isEmpty()) {
                throw new IOException("Vocabulary file is empty");
            }
            
            System.out.println("Loaded " + vocab.size() + " tokens from vocabulary");
            
            // Check for required BERT tokens
            validateBertTokens(vocab);
            
            // Create configuration
            String config = createBertConfig(vocabFile, doLowerCase, stripAccents, "[UNK]");
            
            System.out.println("Migration completed successfully");
            return config;
        }
        
        /**
         * Validate that vocabulary contains required BERT tokens
         */
        private static void validateBertTokens(List<String> vocab) {
            String[] requiredTokens = {"[PAD]", "[UNK]", "[CLS]", "[SEP]"};
            
            for (String token : requiredTokens) {
                if (!vocab.contains(token)) {
                    System.err.println("Warning: Required BERT token '" + token + "' not found in vocabulary");
                }
            }
        }
        
        /**
         * Create a test configuration to verify the migration
         */
        public static boolean testMigration(String jsonConfig, String testText) {
            try {
                // Try to create a tokenizer with the configuration
                SamediffBertTokenizerPreProcessor tokenizer = 
                    SamediffBertTokenizerPreProcessor.fromJson(jsonConfig, true, 512);
                
                try {
                    // Test encoding
                    SamediffBertTokenizerPreProcessor.BertEncoding encoding = tokenizer.encode(testText);
                    
                    // Test decoding
                    int[] ids = new int[encoding.inputIds.length];
                    for (int i = 0; i < ids.length; i++) {
                        ids[i] = (int) encoding.inputIds[i];
                    }
                    String decoded = tokenizer.decode(ids);
                    
                    System.out.println("Migration test successful:");
                    System.out.println("  Input: " + testText);
                    System.out.println("  Tokens: " + ids.length);
                    System.out.println("  Decoded: " + decoded);
                    
                    return true;
                } finally {
                    tokenizer.close();
                }
            } catch (Exception e) {
                System.err.println("Migration test failed: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }
    }
    
    /**
     * Example usage and testing
     */
    public static void main(String[] args) {
        try {
            // Example 1: Create a simple test configuration
            String simpleConfig = createSimpleConfig();
            System.out.println("Simple config created:");
            System.out.println(simpleConfig);
            
            if (args.length > 0) {
                // Example 2: Migrate existing vocabulary file
                File vocabFile = new File(args[0]);
                String migratedConfig = MigrationHelper.migrateSameDiffVocab(vocabFile, true, true);
                
                // Save migrated configuration
                File outputFile = new File(vocabFile.getParent(), "tokenizer.json");
                saveConfig(migratedConfig, outputFile);
                System.out.println("Migrated configuration saved to: " + outputFile.getAbsolutePath());
                
                // Test the migration
                boolean testPassed = MigrationHelper.testMigration(migratedConfig, "Hello world! This is a test.");
                System.out.println("Migration test " + (testPassed ? "PASSED" : "FAILED"));
            }
            
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

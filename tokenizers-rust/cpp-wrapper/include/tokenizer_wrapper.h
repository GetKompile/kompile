#ifndef TOKENIZER_WRAPPER_H
#define TOKENIZER_WRAPPER_H

#include "tokenizers_api.h"
#include "model_manager.h"

#ifdef __cplusplus
extern "C" {
#endif

// Tokenizer creation and management
TOKENIZERS_API OpaqueTokenizer* create_tokenizer(OpaqueModelManager* manager);
TOKENIZERS_API OpaqueTokenizer* create_tokenizer_from_file(const char* model_path);
TOKENIZERS_API void free_tokenizer(OpaqueTokenizer* tokenizer);

// Encoding operations
TOKENIZERS_API TokenizerResult encode_text(
    OpaqueTokenizer* tokenizer,
    const char* text,
    bool add_special_tokens
);

TOKENIZERS_API TokenizerResult encode_batch(
    OpaqueTokenizer* tokenizer,
    const char** texts,
    size_t num_texts,
    bool add_special_tokens
);

// Decoding operations
TOKENIZERS_API TokenizerResult decode_tokens(
    OpaqueTokenizer* tokenizer,
    const uint32_t* token_ids,
    size_t num_tokens,
    bool skip_special_tokens
);

TOKENIZERS_API TokenizerResult decode_batch(
    OpaqueTokenizer* tokenizer,
    const uint32_t** token_ids_batch,
    const size_t* lengths,
    size_t batch_size,
    bool skip_special_tokens
);

// Token operations
TOKENIZERS_API uint32_t get_token_id(OpaqueTokenizer* tokenizer, const char* token);
TOKENIZERS_API const char* get_token_string(OpaqueTokenizer* tokenizer, uint32_t token_id);
TOKENIZERS_API bool is_special_token(OpaqueTokenizer* tokenizer, uint32_t token_id);

// Vocabulary operations
TOKENIZERS_API size_t get_tokenizer_vocab_size(OpaqueTokenizer* tokenizer);
TOKENIZERS_API const char** get_vocab_tokens(OpaqueTokenizer* tokenizer, size_t* num_tokens);
TOKENIZERS_API void free_vocab_tokens(const char** tokens, size_t num_tokens);

// Special tokens
TOKENIZERS_API uint32_t get_pad_token_id(OpaqueTokenizer* tokenizer);
TOKENIZERS_API uint32_t get_unk_token_id(OpaqueTokenizer* tokenizer);
TOKENIZERS_API uint32_t get_cls_token_id(OpaqueTokenizer* tokenizer);
TOKENIZERS_API uint32_t get_sep_token_id(OpaqueTokenizer* tokenizer);
TOKENIZERS_API uint32_t get_mask_token_id(OpaqueTokenizer* tokenizer);

TOKENIZERS_API const char* get_pad_token(OpaqueTokenizer* tokenizer);
TOKENIZERS_API const char* get_unk_token(OpaqueTokenizer* tokenizer);
TOKENIZERS_API const char* get_cls_token(OpaqueTokenizer* tokenizer);
TOKENIZERS_API const char* get_sep_token(OpaqueTokenizer* tokenizer);
TOKENIZERS_API const char* get_mask_token(OpaqueTokenizer* tokenizer);

// Configuration
TOKENIZERS_API TokenizerResult set_truncation(OpaqueTokenizer* tokenizer, bool enable, size_t max_length);
TOKENIZERS_API TokenizerResult set_padding(OpaqueTokenizer* tokenizer, bool enable, const char* direction);
TOKENIZERS_API bool get_truncation_enabled(OpaqueTokenizer* tokenizer);
TOKENIZERS_API bool get_padding_enabled(OpaqueTokenizer* tokenizer);
TOKENIZERS_API size_t get_max_length_setting(OpaqueTokenizer* tokenizer);

#ifdef __cplusplus
}
#endif

#endif // TOKENIZER_WRAPPER_H

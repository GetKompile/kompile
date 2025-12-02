#ifndef ENCODING_WRAPPER_H
#define ENCODING_WRAPPER_H

#include "tokenizers_api.h"

#ifdef __cplusplus
extern "C" {
#endif

// Encoding operations
TOKENIZERS_API void free_encoding(OpaqueEncoding* encoding);

// Token access
TOKENIZERS_API const uint32_t* get_encoding_ids(OpaqueEncoding* encoding, size_t* length);
TOKENIZERS_API const char** get_encoding_tokens(OpaqueEncoding* encoding, size_t* length);
TOKENIZERS_API const uint32_t* get_encoding_type_ids(OpaqueEncoding* encoding, size_t* length);
TOKENIZERS_API const uint32_t* get_encoding_attention_mask(OpaqueEncoding* encoding, size_t* length);
TOKENIZERS_API const uint32_t* get_encoding_special_tokens_mask(OpaqueEncoding* encoding, size_t* length);

// Offsets and spans
typedef struct {
    size_t start;
    size_t end;
} TokenSpan;

TOKENIZERS_API const TokenSpan* get_encoding_offsets(OpaqueEncoding* encoding, size_t* length);
TOKENIZERS_API const uint32_t* get_encoding_word_ids(OpaqueEncoding* encoding, size_t* length);
TOKENIZERS_API const uint32_t* get_encoding_sequence_ids(OpaqueEncoding* encoding, size_t* length);

// Encoding properties
TOKENIZERS_API size_t get_encoding_length(OpaqueEncoding* encoding);
TOKENIZERS_API bool encoding_is_empty(OpaqueEncoding* encoding);
TOKENIZERS_API const char* get_encoding_original_text(OpaqueEncoding* encoding);

// Token-to-character mapping
TOKENIZERS_API TokenSpan get_token_span(OpaqueEncoding* encoding, size_t token_index);
TOKENIZERS_API size_t get_token_at_position(OpaqueEncoding* encoding, size_t char_position);

// Sequence operations (for multi-sequence inputs)
TOKENIZERS_API size_t get_num_sequences(OpaqueEncoding* encoding);
TOKENIZERS_API TokenSpan get_sequence_span(OpaqueEncoding* encoding, size_t sequence_index);

// Conversion utilities
TOKENIZERS_API TokenizerResult encoding_to_string(OpaqueEncoding* encoding);
TOKENIZERS_API TokenizerResult tokens_to_string(
    const char** tokens,
    size_t num_tokens,
    bool skip_special_tokens
);

// Batch encoding utilities
typedef struct {
    OpaqueEncoding** encodings;
    size_t num_encodings;
} EncodingBatch;

TOKENIZERS_API void free_encoding_batch(EncodingBatch* batch);
TOKENIZERS_API const uint32_t** get_batch_ids(EncodingBatch* batch, size_t** lengths);
TOKENIZERS_API const uint32_t** get_batch_attention_masks(EncodingBatch* batch, size_t** lengths);
TOKENIZERS_API const uint32_t** get_batch_type_ids(EncodingBatch* batch, size_t** lengths);

// Padding and truncation for batches
TOKENIZERS_API TokenizerResult pad_encoding_batch(
    EncodingBatch* batch,
    size_t max_length,
    uint32_t pad_token_id,
    const char* direction  // "left" or "right"
);

TOKENIZERS_API TokenizerResult truncate_encoding_batch(
    EncodingBatch* batch,
    size_t max_length,
    const char* strategy  // "longest_first", "only_first", "only_second"
);

#ifdef __cplusplus
}
#endif

#endif // ENCODING_WRAPPER_H

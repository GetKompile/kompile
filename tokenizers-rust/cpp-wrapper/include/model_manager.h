#ifndef MODEL_MANAGER_H
#define MODEL_MANAGER_H

#include "tokenizers_api.h"

#ifdef __cplusplus
extern "C" {
#endif

// Model configuration
typedef struct {
    const char* model_path;
    const char* vocab_path;
    const char* merges_path;
    const char* tokenizer_type;  // "BPE", "WordPiece", "SentencePiece", etc.
    bool use_cache;
    size_t max_length;
    bool truncation;
    bool padding;
} TokenizerConfig;

// Model Manager API
TOKENIZERS_API OpaqueModelManager* create_model_manager(void);
TOKENIZERS_API void free_model_manager(OpaqueModelManager* manager);

TOKENIZERS_API TokenizerResult load_model_from_file(
    OpaqueModelManager* manager,
    const char* model_path
);

TOKENIZERS_API TokenizerResult load_model_from_config(
    OpaqueModelManager* manager,
    const TokenizerConfig* config
);

TOKENIZERS_API TokenizerResult save_model(
    OpaqueModelManager* manager,
    const char* output_path
);

TOKENIZERS_API bool is_model_loaded(OpaqueModelManager* manager);
TOKENIZERS_API const char* get_model_info(OpaqueModelManager* manager);
TOKENIZERS_API size_t get_vocab_size(OpaqueModelManager* manager);

// Model metadata
TOKENIZERS_API const char* get_model_type(OpaqueModelManager* manager);
TOKENIZERS_API const char* get_model_version(OpaqueModelManager* manager);
TOKENIZERS_API size_t get_max_length(OpaqueModelManager* manager);

// Configuration helpers
TOKENIZERS_API OpaqueTokenizerConfig* create_default_config(void);
TOKENIZERS_API void free_tokenizer_config(OpaqueTokenizerConfig* config);
TOKENIZERS_API void set_config_model_path(OpaqueTokenizerConfig* config, const char* path);
TOKENIZERS_API void set_config_max_length(OpaqueTokenizerConfig* config, size_t max_length);
TOKENIZERS_API void set_config_truncation(OpaqueTokenizerConfig* config, bool enable);
TOKENIZERS_API void set_config_padding(OpaqueTokenizerConfig* config, bool enable);

#ifdef __cplusplus
}
#endif

#endif // MODEL_MANAGER_H

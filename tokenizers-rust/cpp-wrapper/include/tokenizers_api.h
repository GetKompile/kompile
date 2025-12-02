#ifndef TOKENIZERS_API_H
#define TOKENIZERS_API_H

#ifdef __cplusplus
extern "C" {
#endif

#include <stdint.h>
#include <stddef.h>
#include <stdbool.h>

// Platform-specific export macros
#ifdef _WIN32
    #ifdef TOKENIZERS_EXPORTS
        #define TOKENIZERS_API __declspec(dllexport)
    #else
        #define TOKENIZERS_API __declspec(dllimport)
    #endif
#else
    #define TOKENIZERS_API __attribute__((visibility("default")))
#endif

// Error handling
typedef enum {
    TOKENIZER_OK = 0,
    TOKENIZER_ERROR_NULL_POINTER = -1,
    TOKENIZER_ERROR_INVALID_INPUT = -2,
    TOKENIZER_ERROR_MODEL_LOAD_FAILED = -3,
    TOKENIZER_ERROR_ENCODING_FAILED = -4,
    TOKENIZER_ERROR_DECODING_FAILED = -5,
    TOKENIZER_ERROR_OUT_OF_MEMORY = -6,
    TOKENIZER_ERROR_UNKNOWN = -99
} TokenizerError;

// Opaque types
typedef struct OpaqueTokenizer OpaqueTokenizer;
typedef struct OpaqueEncoding OpaqueEncoding;
typedef struct OpaqueModelManager OpaqueModelManager;
typedef struct OpaqueTokenizerConfig OpaqueTokenizerConfig;

// Result wrapper
typedef struct {
    TokenizerError error_code;
    char* error_message;
    void* data;
} TokenizerResult;

// Basic API functions
TOKENIZERS_API const char* get_version(void);
TOKENIZERS_API const char* get_build_info(void);
TOKENIZERS_API bool self_test(void);
TOKENIZERS_API bool initialize_library(void);
TOKENIZERS_API void cleanup_library(void);

// Error handling
TOKENIZERS_API const char* get_error_message(TokenizerError error_code);
TOKENIZERS_API void free_error_message(char* message);

// Memory management
TOKENIZERS_API void free_result(TokenizerResult* result);
TOKENIZERS_API void free_string(char* str);

#ifdef __cplusplus
}
#endif

#endif // TOKENIZERS_API_H

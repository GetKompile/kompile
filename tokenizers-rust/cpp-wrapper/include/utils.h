#ifndef UTILS_H
#define UTILS_H

#include "tokenizers_api.h"

#ifdef __cplusplus
extern "C" {
#endif

// String utilities
TOKENIZERS_API char* create_string_copy(const char* source);
TOKENIZERS_API void free_string_array(char** strings, size_t count);
TOKENIZERS_API char** create_string_array(size_t count);

// Array utilities
TOKENIZERS_API uint32_t* create_uint32_array(size_t count);
TOKENIZERS_API void free_uint32_array(uint32_t* array);
TOKENIZERS_API uint32_t* copy_uint32_array(const uint32_t* source, size_t count);

// File utilities
TOKENIZERS_API bool file_exists(const char* path);
TOKENIZERS_API bool is_directory(const char* path);
TOKENIZERS_API size_t get_file_size(const char* path);
TOKENIZERS_API char* read_file_to_string(const char* path);

// JSON utilities
TOKENIZERS_API bool is_valid_json(const char* json_string);
TOKENIZERS_API char* extract_json_field(const char* json_string, const char* field_name);

// Platform utilities
TOKENIZERS_API const char* get_platform_name(void);
TOKENIZERS_API const char* get_architecture(void);
TOKENIZERS_API bool is_little_endian(void);
TOKENIZERS_API size_t get_system_page_size(void);

// Performance utilities
TOKENIZERS_API uint64_t get_timestamp_microseconds(void);
TOKENIZERS_API double get_elapsed_seconds(uint64_t start_time, uint64_t end_time);

// Logging utilities
typedef enum {
    LOG_LEVEL_ERROR = 0,
    LOG_LEVEL_WARN = 1,
    LOG_LEVEL_INFO = 2,
    LOG_LEVEL_DEBUG = 3,
    LOG_LEVEL_TRACE = 4
} LogLevel;

TOKENIZERS_API void set_log_level(LogLevel level);
TOKENIZERS_API LogLevel get_log_level(void);
TOKENIZERS_API void log_message(LogLevel level, const char* message);

// Memory utilities
TOKENIZERS_API void* safe_malloc(size_t size);
TOKENIZERS_API void* safe_calloc(size_t count, size_t size);
TOKENIZERS_API void* safe_realloc(void* ptr, size_t new_size);
TOKENIZERS_API void safe_free(void* ptr);
TOKENIZERS_API size_t get_memory_usage(void);

// Thread safety utilities
TOKENIZERS_API bool is_thread_safe(void);
TOKENIZERS_API void set_thread_count(size_t count);
TOKENIZERS_API size_t get_thread_count(void);

// Validation utilities
TOKENIZERS_API bool validate_token_id(uint32_t token_id, size_t vocab_size);
TOKENIZERS_API bool validate_text_utf8(const char* text);
TOKENIZERS_API bool validate_model_path(const char* path);

// Conversion utilities
TOKENIZERS_API char* uint32_array_to_string(const uint32_t* array, size_t length);
TOKENIZERS_API uint32_t* string_to_uint32_array(const char* str, size_t* length);

// Hash utilities
TOKENIZERS_API uint64_t hash_string(const char* str);
TOKENIZERS_API uint64_t hash_uint32_array(const uint32_t* array, size_t length);

#ifdef __cplusplus
}
#endif

#endif // UTILS_H

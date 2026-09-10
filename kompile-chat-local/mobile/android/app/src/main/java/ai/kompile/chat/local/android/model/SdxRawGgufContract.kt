package ai.kompile.chat.local.android.model

/**
 * Stable one-time preparation contract shared by Android and libsdx_llm.
 * Keep field names synchronized with sdx_llm_c.h and SdxGgufModelPreparer.
 * Generation deliberately is not represented here: prepared models execute through
 * the normal flavor-specific PlatformLocalChatSession.
 */
internal object SdxRawGgufContract {
    const val TARGET_PROFILE_FIELD = "targetProfile"
    const val PREPARED_SCHEMA_FIELD = "schema"
    const val PREPARED_SCHEMA = "sdx-prepared-text-model-v6"
    const val CACHE_HIT_FIELD = "cacheHit"
    const val SOURCE_SHA256_FIELD = "sourceSha256"
    const val SOURCE_BYTES_FIELD = "sourceBytes"
    const val CANONICAL_SDZ_LOGICAL_SHA256_FIELD = "canonicalSdzLogicalSha256"
    const val CANONICAL_SDZ_LOGICAL_BYTES_FIELD = "canonicalSdzLogicalBytes"
    const val CANONICAL_SDZ_PATH_FIELD = "canonicalSdzPath"
    const val CANONICAL_SDZ_BYTES_FIELD = "canonicalSdzBytes"
    const val MODEL_PATH_FIELD = "modelPath"
    const val TOKENIZER_PATH_FIELD = "tokenizerPath"
    const val COMPILE_KEY_FIELD = "compileKey"
    const val TARGET_SOC_FIELD = "targetSoc"
    const val CONTEXT_LENGTH_FIELD = "contextLength"
    const val MAX_PREFILL_LENGTH_FIELD = "maxPrefillLength"
    const val EXECUTION_PROVIDER_FIELD = "executionProvider"
    const val CONVERSION_PROFILE_SHA256_FIELD = "conversionProfileSha256"
    const val DIAGNOSTIC_MODE_FIELD = "diagnosticMode"
    const val OPTIMIZED_SOURCE_PATH_FIELD = "optimizedSourcePath"
    const val OPTIMIZED_SOURCE_BYTES_FIELD = "optimizedSourceBytes"
    const val IMPORT_RESOURCES_RELEASED_FIELD = "importResourcesReleased"

    fun preparationOptionsJson(
        verifiedSourceSha256: String?,
        verifiedSourceBytes: Long?,
        options: ModelPreparationOptions,
    ): String = options.optionsJson(verifiedSourceSha256, verifiedSourceBytes)
}

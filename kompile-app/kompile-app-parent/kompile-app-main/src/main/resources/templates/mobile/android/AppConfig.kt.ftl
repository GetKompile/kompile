package {{packageName}}.config

object AppConfig {
    /** Inference mode: "local", "hybrid", or "remote" */
    const val inferenceMode: String = "{{inferenceMode}}"

    /** Model identifier for local inference */
    const val modelId: String = "{{modelId}}"

    /** Model file name in assets or app storage */
    const val modelFileName: String = "{{modelFileName}}"

    /** API key for remote LLM services */
    const val apiKey: String = "{{apiKeyPlaceholder}}"

    /** Base URL for remote API (OpenAI-compatible) */
    const val remoteApiBaseUrl: String = "https://api.openai.com/v1/"

    /** SDK version */
    const val sdkVersion: String = "{{sdkVersion}}"

    /** Maximum tokens for generation */
    const val maxGenerationTokens: Int = 512

    /** Temperature for sampling */
    const val defaultTemperature: Float = 0.7f

    /** Top-k for vector search retrieval */
    const val ragTopK: Int = 5

    /** Chunk size for document splitting */
    const val chunkSize: Int = 512

    /** Chunk overlap for document splitting */
    const val chunkOverlap: Int = 64

    /** Embedding dimension for local vector search */
    const val embeddingDimension: Int = 384

    fun isLocalInference(): Boolean = inferenceMode == "local" || inferenceMode == "hybrid"
    fun isRemoteInference(): Boolean = inferenceMode == "remote" || inferenceMode == "hybrid"
}

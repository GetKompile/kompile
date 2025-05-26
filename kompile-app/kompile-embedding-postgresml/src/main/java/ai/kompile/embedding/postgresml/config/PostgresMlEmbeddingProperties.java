/*
 * Copyright 2025 Kompile Inc.
 * *
 * * Licensed under the Apache License, Version 2.0 (the "License");
 * * you may not use this file except in compliance with the License.
 * * You may obtain a copy of the License at
 * *
 * * http://www.apache.org/licenses/LICENSE-2.0
 * *
 * * Unless required by applicable law or agreed to in writing, software
 * * distributed under the License is distributed on an "AS IS" BASIS,
 * * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * * See the License for the specific language governing permissions and
 * * limitations under the License.
 */

package ai.kompile.embedding.postgresml.config;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.postgresml.PostgresMlEmbeddingModel;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for PostgresML embedding model.
 * These properties allow customization of the PostgresML embedding behavior.
 */
@ConfigurationProperties(prefix = "spring.ai.postgresml.embedding")
public class PostgresMlEmbeddingProperties {

    /**
     * Whether PostgresML embedding is enabled.
     */
    private boolean enabled = false;

    /**
     * The Huggingface transformer model to use for the embedding.
     * Default is PostgresML's default transformer model.
     * 
     * Popular alternatives:
     * - sentence-transformers/all-MiniLM-L6-v2 (lightweight, fast)
     * - sentence-transformers/all-mpnet-base-v2 (high quality)
     * - sentence-transformers/multi-qa-MiniLM-L6-cos-v1 (optimized for Q&A)
     * - distilbert-base-uncased (general purpose)
     */
    private String transformer = PostgresMlEmbeddingModel.DEFAULT_TRANSFORMER_MODEL;

    /**
     * PostgresML vector type to use for the embedding.
     * Two options are supported: PG_ARRAY and PG_VECTOR.
     * Default is PG_ARRAY.
     */
    private PostgresMlEmbeddingModel.VectorType vectorType = PostgresMlEmbeddingModel.VectorType.PG_ARRAY;

    /**
     * Additional transformer specific options as a JSON string.
     * This should be a JSON string containing any model-specific parameters.
     * 
     * Examples:
     * - "{}" (empty, use defaults)
     * - "{\"normalize\": true}" (L2 normalize embeddings)
     * - "{\"pooling\": \"mean\"}" (specify pooling strategy)
     */
    private String kwargs = "{}";

    /**
     * The Document metadata aggregation mode.
     * Controls how document metadata is handled during embedding.
     */
    private MetadataMode metadataMode = MetadataMode.EMBED;

    /**
     * Whether to automatically create the PostgresML extension if it doesn't exist.
     * Requires superuser privileges on the database.
     */
    private boolean autoCreateExtension = true;

    /**
     * Whether to verify PostgresML installation on application startup.
     */
    private boolean verifyInstallation = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTransformer() {
        return transformer;
    }

    public void setTransformer(String transformer) {
        this.transformer = transformer;
    }

    public PostgresMlEmbeddingModel.VectorType getVectorType() {
        return vectorType;
    }

    public void setVectorType(PostgresMlEmbeddingModel.VectorType vectorType) {
        this.vectorType = vectorType;
    }

    public String getKwargs() {
        return kwargs;
    }

    public void setKwargs(String kwargs) {
        this.kwargs = kwargs;
    }

    public MetadataMode getMetadataMode() {
        return metadataMode;
    }

    public void setMetadataMode(MetadataMode metadataMode) {
        this.metadataMode = metadataMode;
    }

    public boolean isAutoCreateExtension() {
        return autoCreateExtension;
    }

    public void setAutoCreateExtension(boolean autoCreateExtension) {
        this.autoCreateExtension = autoCreateExtension;
    }

    public boolean isVerifyInstallation() {
        return verifyInstallation;
    }

    public void setVerifyInstallation(boolean verifyInstallation) {
        this.verifyInstallation = verifyInstallation;
    }

    @Override
    public String toString() {
        return "PostgresMlEmbeddingProperties{" +
                "enabled=" + enabled +
                ", transformer='" + transformer + '\'' +
                ", vectorType=" + vectorType +
                ", kwargs='" + kwargs + '\'' +
                ", metadataMode=" + metadataMode +
                ", autoCreateExtension=" + autoCreateExtension +
                ", verifyInstallation=" + verifyInstallation +
                '}';
    }
}

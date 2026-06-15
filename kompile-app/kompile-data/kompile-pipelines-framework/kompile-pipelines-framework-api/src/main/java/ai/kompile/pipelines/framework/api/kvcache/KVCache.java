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

package ai.kompile.pipelines.framework.api.kvcache;

import ai.kompile.pipelines.framework.api.data.NDArray;

/**
 * Represents a key-value cache used in autoregressive transformer decoding.
 * Each layer of the transformer decoder maintains separate key and value tensors
 * that grow along the sequence dimension as new tokens are generated.
 *
 * Implementations must manage the lifecycle of underlying tensor resources.
 * Callers should use {@link #close()} to release native memory when done.
 */
public interface KVCache extends AutoCloseable {

    /**
     * Returns the number of transformer layers in this cache.
     */
    int getNumLayers();

    /**
     * Returns the number of attention heads per layer.
     */
    int getNumHeads();

    /**
     * Returns the dimension of each attention head.
     */
    int getHeadDim();

    /**
     * Returns the current sequence length stored in the cache.
     * This increases as new tokens are generated and cache is updated.
     */
    int getCurrentSequenceLength();

    /**
     * Gets the key tensor for a specific layer.
     *
     * @param layer The layer index (0-based).
     * @return The key NDArray for the specified layer, shape typically [batch, heads, seq_len, head_dim].
     */
    NDArray getKey(int layer);

    /**
     * Gets the value tensor for a specific layer.
     *
     * @param layer The layer index (0-based).
     * @return The value NDArray for the specified layer, shape typically [batch, heads, seq_len, head_dim].
     */
    NDArray getValue(int layer);

    /**
     * Updates the cache for a specific layer by concatenating new key/value tensors
     * along the sequence dimension.
     *
     * @param layer The layer index (0-based).
     * @param newKey The new key tensor to append.
     * @param newValue The new value tensor to append.
     */
    void update(int layer, NDArray newKey, NDArray newValue);

    /**
     * Creates a deep copy of this cache. All underlying tensors are duplicated.
     * This is required before re-executing SameDiff models, as execution may
     * invalidate tensor references.
     *
     * @return A new KVCache instance with independent tensor copies.
     */
    KVCache dup();

    /**
     * Releases all native resources held by this cache.
     * After calling close(), this cache instance should not be used.
     */
    @Override
    void close();
}

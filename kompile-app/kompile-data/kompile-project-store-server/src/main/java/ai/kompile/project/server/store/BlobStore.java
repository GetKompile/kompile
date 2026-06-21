package ai.kompile.project.server.store;

/**
 * Minimal content store for Xet xorbs and shards. The reference implementation is local-filesystem
 * ({@link LocalBlobStore}); a different backing store (S3, etc.) can implement this interface.
 */
public interface BlobStore {

    boolean exists(String key);

    void put(String key, byte[] data);

    long size(String key);

    /** Read the inclusive byte range {@code [start, endInclusive]} of the stored object. */
    byte[] readRange(String key, long start, long endInclusive);

    byte[] readAll(String key);
}

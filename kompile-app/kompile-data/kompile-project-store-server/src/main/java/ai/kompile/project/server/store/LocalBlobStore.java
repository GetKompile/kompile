package ai.kompile.project.server.store;

import ai.kompile.project.server.ProjectStoreServerProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Local-filesystem {@link BlobStore}. Objects are stored under {@code <dataDir>/blobs/<key>}; keys
 * are caller-namespaced (e.g. {@code xorbs/<hash>}, {@code shards/<fileId>.shard}).
 */
@Component
public class LocalBlobStore implements BlobStore {

    private final Path baseDir;

    public LocalBlobStore(ProjectStoreServerProperties props) {
        this.baseDir = Paths.get(props.getDataDir(), "blobs");
    }

    private Path pathFor(String key) {
        return baseDir.resolve(key).normalize();
    }

    @Override
    public boolean exists(String key) {
        return Files.isRegularFile(pathFor(key));
    }

    @Override
    public void put(String key, byte[] data) {
        Path p = pathFor(key);
        try {
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new RuntimeException("Failed to store blob " + key, e);
        }
    }

    @Override
    public long size(String key) {
        try {
            return Files.size(pathFor(key));
        } catch (IOException e) {
            throw new RuntimeException("Failed to stat blob " + key, e);
        }
    }

    @Override
    public byte[] readRange(String key, long start, long endInclusive) {
        long len = endInclusive - start + 1;
        if (len < 0) {
            return new byte[0];
        }
        try (RandomAccessFile raf = new RandomAccessFile(pathFor(key).toFile(), "r")) {
            long fileLen = raf.length();
            long effEnd = Math.min(endInclusive, fileLen - 1);
            int effLen = (int) Math.max(0, effEnd - start + 1);
            byte[] out = new byte[effLen];
            if (effLen > 0) {
                raf.seek(start);
                raf.readFully(out);
            }
            return out;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read blob range " + key, e);
        }
    }

    @Override
    public byte[] readAll(String key) {
        try {
            return Files.readAllBytes(pathFor(key));
        } catch (IOException e) {
            throw new RuntimeException("Failed to read blob " + key, e);
        }
    }
}

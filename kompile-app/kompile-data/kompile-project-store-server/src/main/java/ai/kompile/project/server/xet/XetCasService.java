package ai.kompile.project.server.xet;

import ai.kompile.project.server.ProjectStoreServerProperties;
import ai.kompile.project.server.model.FileReconstructionRecord;
import ai.kompile.project.server.model.FileReconstructionRepository;
import ai.kompile.project.server.model.Project;
import ai.kompile.project.server.model.ProjectRepository;
import ai.kompile.project.server.model.XetChunkRef;
import ai.kompile.project.server.model.XetChunkRefRepository;
import ai.kompile.project.server.model.XorbRecord;
import ai.kompile.project.server.model.XorbRecordRepository;
import ai.kompile.project.server.store.BlobStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-side implementation of the Hugging Face Xet CAS protocol. Stores client-produced xorbs and
 * shards in a {@link BlobStore}, records their layout in the metadata DB, validates upload integrity
 * via {@link XetHash}, and answers reconstruction queries with fetch URLs pointing at this server's
 * Range-serving xorb endpoint.
 */
@Service
public class XetCasService {

    private static final Logger LOG = LoggerFactory.getLogger(XetCasService.class);
    private static final String XORB_PREFIX = "xorbs/";
    private static final String SHARD_PREFIX = "shards/";

    private final BlobStore blobStore;
    private final XorbRecordRepository xorbRepo;
    private final FileReconstructionRepository fileRepo;
    private final XetChunkRefRepository chunkRefRepo;
    private final ProjectRepository projectRepo;
    private final ProjectStoreServerProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public XetCasService(BlobStore blobStore, XorbRecordRepository xorbRepo,
                         FileReconstructionRepository fileRepo, XetChunkRefRepository chunkRefRepo,
                         ProjectRepository projectRepo, ProjectStoreServerProperties props) {
        this.blobStore = blobStore;
        this.xorbRepo = xorbRepo;
        this.fileRepo = fileRepo;
        this.chunkRefRepo = chunkRefRepo;
        this.projectRepo = projectRepo;
        this.props = props;
    }

    // ---- xorb storage + serving ----

    @Transactional
    public boolean storeXorb(String xorbHash, byte[] body) {
        String key = XORB_PREFIX + xorbHash;
        boolean existed = blobStore.exists(key);
        if (!existed) {
            blobStore.put(key, body);
        }
        if (xorbRepo.findByXorbHash(xorbHash).isEmpty()) {
            long[] lengths = XorbCodec.serializedChunkLengths(body);
            xorbRepo.save(XorbRecord.builder()
                    .xorbHash(xorbHash)
                    .serializedLength(XorbCodec.totalSerializedLength(lengths))
                    .chunkCount(lengths.length)
                    .chunkLengthsCsv(XorbRecord.toChunkLengthsCsv(lengths))
                    .build());
        }
        return !existed;
    }

    public boolean xorbExists(String xorbHash) {
        return blobStore.exists(XORB_PREFIX + xorbHash);
    }

    public long xorbSize(String xorbHash) {
        return blobStore.size(XORB_PREFIX + xorbHash);
    }

    public byte[] readXorbRange(String xorbHash, long start, long endInclusive) {
        return blobStore.readRange(XORB_PREFIX + xorbHash, start, endInclusive);
    }

    public byte[] readXorb(String xorbHash) {
        return blobStore.readAll(XORB_PREFIX + xorbHash);
    }

    // ---- shard upload ----

    @Transactional
    public int storeShard(byte[] body, String repoId) {
        ShardCodec.ParsedShard shard = ShardCodec.parse(body);
        if (props.isVerifyUploads()) {
            verifyTermHashes(shard);
        }
        UUID projectId = resolveProjectId(repoId);
        int registered = 0;

        // Capture each xorb's CAS-info (for global dedupe) + index chunk -> xorb.
        for (ShardCodec.XorbInfo xi : shard.xorbs) {
            String casB64 = xi.casInfoBlockBytes != null
                    ? Base64.getEncoder().encodeToString(xi.casInfoBlockBytes) : null;
            Optional<XorbRecord> existing = xorbRepo.findByXorbHash(xi.xorbHash);
            if (existing.isPresent()) {
                XorbRecord rec = existing.get();
                if (rec.getCasInfoB64() == null && casB64 != null) {
                    rec.setCasInfoB64(casB64);
                    xorbRepo.save(rec);
                }
            } else {
                xorbRepo.save(XorbRecord.builder()
                        .xorbHash(xi.xorbHash)
                        .serializedLength(xi.numBytesOnDisk)
                        .chunkCount(xi.chunks.size())
                        .casInfoB64(casB64)
                        .build());
            }
            for (ShardCodec.ChunkEntry ce : xi.chunks) {
                if (!chunkRefRepo.existsByChunkHash(ce.chunkHash)) {
                    chunkRefRepo.save(XetChunkRef.builder().chunkHash(ce.chunkHash).xorbHash(xi.xorbHash).build());
                }
            }
        }

        // Register file reconstructions.
        for (ShardCodec.FileInfo fi : shard.files) {
            if (fileRepo.existsByFileId(fi.fileId)) {
                continue;
            }
            long totalUnpacked = 0;
            List<StoredTerm> rows = new ArrayList<>();
            for (ShardCodec.Term t : fi.terms) {
                totalUnpacked += t.unpackedSegmentBytes;
                rows.add(new StoredTerm(t.xorbHash, t.unpackedSegmentBytes, t.chunkIndexStart, t.chunkIndexEnd));
            }
            String termsJson;
            try {
                termsJson = mapper.writeValueAsString(rows);
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to serialize reconstruction terms");
            }
            String shardKey = SHARD_PREFIX + fi.fileId + ".shard";
            try {
                blobStore.put(shardKey, body);
            } catch (RuntimeException e) {
                LOG.warn("Could not persist raw shard for {}: {}", fi.fileId, e.getMessage());
                shardKey = null;
            }
            fileRepo.save(FileReconstructionRecord.builder()
                    .projectId(projectId)
                    .fileId(fi.fileId)
                    .termsJson(termsJson)
                    .totalUnpackedLength(totalUnpacked)
                    .shardStorageKey(shardKey)
                    .build());
            registered++;
        }
        return registered > 0 ? 1 : 0;
    }

    private void verifyTermHashes(ShardCodec.ParsedShard shard) {
        Map<String, ShardCodec.XorbInfo> byHash = new HashMap<>();
        for (ShardCodec.XorbInfo xi : shard.xorbs) {
            byHash.put(xi.xorbHash, xi);
        }
        for (ShardCodec.FileInfo fi : shard.files) {
            for (ShardCodec.Term t : fi.terms) {
                if (t.rangeVerificationHex == null) {
                    continue;
                }
                ShardCodec.XorbInfo xi = byHash.get(t.xorbHash);
                if (xi == null) {
                    continue; // xorb not described in this shard; cannot recompute here
                }
                List<byte[]> raws = new ArrayList<>();
                for (int i = t.chunkIndexStart; i < t.chunkIndexEnd && i < xi.chunks.size(); i++) {
                    raws.add(ShardCodec.hexToHash(xi.chunks.get(i).chunkHash));
                }
                String computed = ShardCodec.hashToHex(XetHash.termVerificationHashRaw(raws));
                if (!computed.equals(t.rangeVerificationHex)) {
                    LOG.warn("Rejecting shard: term verification mismatch for file {} (xorb {} [{},{}))",
                            fi.fileId, t.xorbHash, t.chunkIndexStart, t.chunkIndexEnd);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Shard verification failed: term range hash mismatch for file " + fi.fileId);
                }
            }
        }
    }

    // ---- reconstruction ----

    @Transactional(readOnly = true)
    public ReconstructionResponse buildReconstruction(String fileId, String rangeHeader) {
        FileReconstructionRecord rec = fileRepo.findByFileId(fileId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown file id: " + fileId));
        List<StoredTerm> all;
        try {
            all = new ArrayList<>(Arrays.asList(mapper.readValue(rec.getTermsJson(), StoredTerm[].class)));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Corrupt reconstruction record");
        }

        long rangeStart = -1;
        long rangeEnd = -1;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String[] parts = rangeHeader.substring("bytes=".length()).split("-", 2);
                rangeStart = Long.parseLong(parts[0].trim());
                if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                    rangeEnd = Long.parseLong(parts[1].trim());
                }
            } catch (NumberFormatException nfe) {
                rangeStart = -1;
                rangeEnd = -1;
            }
        }
        if (rangeStart >= 0 && rangeStart >= rec.getTotalUnpackedLength()) {
            throw new ResponseStatusException(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, "Range not satisfiable");
        }

        ReconstructionResponse resp = new ReconstructionResponse();
        List<StoredTerm> included = new ArrayList<>();
        long offset = 0;
        if (rangeStart >= 0) {
            long cursor = 0;
            boolean started = false;
            for (StoredTerm t : all) {
                long termEnd = cursor + t.unpacked_length;
                if (!started && termEnd > rangeStart) {
                    started = true;
                    offset = rangeStart - cursor;
                }
                if (started) {
                    included.add(t);
                    if (rangeEnd >= 0 && termEnd > rangeEnd) {
                        break;
                    }
                }
                cursor = termEnd;
            }
        } else {
            included.addAll(all);
        }
        resp.offsetIntoFirstRange = offset;

        String base = props.getCasUrl();
        for (StoredTerm t : included) {
            XorbRecord xorb = xorbRepo.findByXorbHash(t.hash)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "Reconstruction references unknown xorb " + t.hash));
            long[] byteRange = xorb.byteRangeForChunks((int) t.start, (int) t.end);
            String url = base + "/v1/xorbs/default/" + t.hash;
            resp.terms.add(new ReconstructionResponse.Term(
                    t.hash, t.unpacked_length, new ReconstructionResponse.Range(t.start, t.end)));
            resp.fetchInfo.computeIfAbsent(t.hash, k -> new ArrayList<>())
                    .add(new ReconstructionResponse.FetchInfo(
                            new ReconstructionResponse.Range(t.start, t.end),
                            url,
                            new ReconstructionResponse.Range(byteRange[0], byteRange[1])));
        }
        return resp;
    }

    // ---- global dedupe ----

    @Transactional(readOnly = true)
    public byte[] globalDedupe(String chunkHash) {
        Optional<XetChunkRef> ref = chunkRefRepo.findFirstByChunkHash(chunkHash);
        if (ref.isEmpty()) {
            return null;
        }
        Optional<XorbRecord> xorb = xorbRepo.findByXorbHash(ref.get().getXorbHash());
        if (xorb.isEmpty() || xorb.get().getCasInfoB64() == null) {
            return null;
        }
        byte[] casBlock = Base64.getDecoder().decode(xorb.get().getCasInfoB64());
        long now = System.currentTimeMillis() / 1000L;
        return ShardCodec.serializeGlobalDedupe(casBlock, now, now + 7L * 24 * 3600);
    }

    private UUID resolveProjectId(String repoId) {
        if (repoId == null || !repoId.contains("/")) {
            return null;
        }
        String[] p = repoId.split("/", 2);
        return projectRepo.findByNamespaceAndSlug(p[0], p[1]).map(Project::getId).orElse(null);
    }

    /** JSON shape persisted in {@link FileReconstructionRecord#getTermsJson()}. */
    public static class StoredTerm {
        public String hash;
        public long unpacked_length;
        public long start;
        public long end;

        public StoredTerm() {
        }

        public StoredTerm(String hash, long unpackedLength, long start, long end) {
            this.hash = hash;
            this.unpacked_length = unpackedLength;
            this.start = start;
            this.end = end;
        }
    }
}

package ai.kompile.project.server.web;

import ai.kompile.project.server.xet.ReconstructionResponse;
import ai.kompile.project.server.xet.XetCasService;
import ai.kompile.project.server.xet.XetTokenService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * Xet CAS service endpoints (the {@code casUrl} returned by {@link XetTokenController}). Implements the
 * four v1 CAS APIs plus a Range-serving xorb download endpoint (in place of the cloud presigned URLs):
 * <ul>
 *   <li>{@code GET  /xet/cas/v1/reconstructions/{fileId}}</li>
 *   <li>{@code GET  /xet/cas/v1/chunks/default-merkledb/{hash}} — global dedupe</li>
 *   <li>{@code POST /xet/cas/v1/xorbs/default/{hash}} — upload a serialized xorb</li>
 *   <li>{@code GET  /xet/cas/v1/xorbs/default/{hash}} — download a xorb (honors Range)</li>
 *   <li>{@code POST /xet/cas/v1/shards} — upload a shard (registers files; verifies term hashes)</li>
 * </ul>
 */
@RestController
@RequestMapping("/xet/cas")
public class XetCasController {

    private final XetCasService casService;
    private final XetTokenService tokenService;

    public XetCasController(XetCasService casService, XetTokenService tokenService) {
        this.casService = casService;
        this.tokenService = tokenService;
    }

    @GetMapping("/v1/reconstructions/{fileId}")
    public ReconstructionResponse reconstruct(@PathVariable String fileId,
                                              @RequestHeader(value = "Range", required = false) String range) {
        return casService.buildReconstruction(fileId, range);
    }

    @GetMapping("/v1/chunks/default-merkledb/{chunkHash}")
    public ResponseEntity<byte[]> globalDedup(@PathVariable String chunkHash) {
        byte[] shard = casService.globalDedupe(chunkHash);
        if (shard == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(shard);
    }

    @PostMapping(value = "/v1/xorbs/default/{xorbHash}", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public XetResponses.UploadXorbResponse uploadXorb(@PathVariable String xorbHash,
                                                      @RequestBody byte[] body,
                                                      @RequestHeader(value = "Authorization", required = false) String auth) {
        requireWriteScope(auth);
        return new XetResponses.UploadXorbResponse(casService.storeXorb(xorbHash, body));
    }

    @GetMapping("/v1/xorbs/default/{xorbHash}")
    public ResponseEntity<byte[]> downloadXorb(@PathVariable String xorbHash,
                                               @RequestHeader(value = "Range", required = false) String range) {
        if (!casService.xorbExists(xorbHash)) {
            return ResponseEntity.notFound().build();
        }
        long size = casService.xorbSize(xorbHash);
        if (range != null && range.startsWith("bytes=")) {
            long[] r = parseRange(range, size);
            byte[] slice = casService.readXorbRange(xorbHash, r[0], r[1]);
            return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes " + r[0] + "-" + r[1] + "/" + size)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(slice);
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(casService.readXorb(xorbHash));
    }

    @PostMapping(value = "/v1/shards", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public XetResponses.UploadShardResponse uploadShard(@RequestBody byte[] body,
                                                        @RequestHeader(value = "Authorization", required = false) String auth) {
        requireWriteScope(auth);
        String repoId = tokenService.verify(auth).map(c -> c.repo).orElse(null);
        return new XetResponses.UploadShardResponse(casService.storeShard(body, repoId));
    }

    /** Permissive: only rejects when a token IS presented and it is read-scope (mirrors HF behavior). */
    private void requireWriteScope(String auth) {
        if (auth != null && !auth.isBlank()) {
            Optional<XetTokenService.Claims> claims = tokenService.verify(auth);
            if (claims.isPresent() && !claims.get().isWrite()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "write scope required");
            }
        }
    }

    private static long[] parseRange(String header, long size) {
        String spec = header.substring("bytes=".length());
        String[] parts = spec.split("-", 2);
        long start = Long.parseLong(parts[0].trim());
        long end = (parts.length > 1 && !parts[1].trim().isEmpty()) ? Long.parseLong(parts[1].trim()) : size - 1;
        if (start < 0) {
            start = 0;
        }
        if (end > size - 1) {
            end = size - 1;
        }
        return new long[]{start, end};
    }
}

package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ProductChatSessionService;
import ai.kompile.cli.common.ProductChatPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.Map;

/** Read-only product discovery/panels and explicit persistent-session lifecycle. */
@RestController
@RequestMapping("/api/agents/chat/product")
public class ProductChatController {
    private static final int MAX_ASSET_BYTES = 8_388_608; // Includes the linked, full-AOT panels bundle.
    private final ProductChatSessionService products;
    public ProductChatController(ProductChatSessionService products) { this.products = products; }

    @GetMapping public Map<String, Object> product() { return products.metadata(); }
    @PostMapping("/session") public Map<String, Object> open() { return products.openSession(); }
    @DeleteMapping("/session") public Map<String, Object> close() { return products.closeSession(); }
    @GetMapping("/connection") public JsonNode connection() { return products.readCapability("connection"); }
    @GetMapping("/dashboard") public JsonNode dashboard() { return products.readCapability("dashboard"); }
    @GetMapping("/content-approvals") public JsonNode contentApprovals() { return products.pendingApprovals(); }
    @PostMapping("/content-approvals/{approvalId}")
    public JsonNode contentApproval(@PathVariable("approvalId") String approvalId, @RequestBody JsonNode body) {
        return products.resolveApproval(approvalId, body);
    }

    @GetMapping("/assets/{filename}")
    public ResponseEntity<byte[]> asset(@PathVariable String filename) {
        ProductChatPolicy policy = products.policy();
        try {
            byte[] bytes = assetBytes(policy.manifestPath().getParent(), policy.assetsDirectory(), filename);
            return ResponseEntity.ok().contentType(assetType(filename))
                    .header("X-Content-Type-Options", "nosniff")
                    .cacheControl(CacheControl.noStore()).body(bytes);
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product asset unavailable");
        }
    }

    /**
     * No generic reverse proxy exists. The parent integration must supply the exact SDK
     * tactical-board/worldsim routes and captured runtimeId schema before opening this surface.
     * In particular neither a browser URL nor a connection tool's arbitrary URL is forwarded.
     */
    @RequestMapping("/domain/**")
    public void domainUnavailable() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product domain route not enabled"); }

    static MediaType assetType(String filename) {
        if (filename.endsWith(".html")) return MediaType.TEXT_HTML;
        if (filename.endsWith(".js")) return MediaType.parseMediaType("text/javascript");
        if (filename.endsWith(".css")) return MediaType.parseMediaType("text/css");
        if (filename.endsWith(".json")) return MediaType.APPLICATION_JSON;
        if (filename.endsWith(".png")) return MediaType.IMAGE_PNG;
        throw new IllegalArgumentException("Unsupported product asset type");
    }

    static byte[] assetBytes(Path artifact, Path assets, String filename) throws IOException {
        if (filename == null || !filename.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}") || filename.contains(".."))
            throw new IOException("Invalid asset name");
        assetType(filename);
        Path root = artifact.toRealPath();
        Path directory = assets.toAbsolutePath().normalize();
        if (!directory.startsWith(root)) throw new IOException("Asset directory outside product artifact");
        Path current = root;
        for (Path segment : root.relativize(directory)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid product asset directory");
        }
        Path file = directory.resolve(filename);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || !file.toRealPath().getParent().equals(directory.toRealPath())) throw new IOException("Invalid product asset");
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size > MAX_ASSET_BYTES) throw new IOException("Asset too large");
            ByteBuffer content = ByteBuffer.allocate((int) size + 1);
            while (channel.read(content) > 0) { if (content.position() > size) throw new IOException("Asset changed while being served"); }
            content.flip(); byte[] bytes = new byte[content.remaining()]; content.get(bytes); return bytes;
        }
    }
}

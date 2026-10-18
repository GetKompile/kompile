package ai.kompile.app.web.controllers;

import ai.kompile.app.services.agent.ProductChatSessionService;
import ai.kompile.cli.common.chat.sources.ChatTurn;
import ai.kompile.cli.common.chat.sources.ProductDisplayTurn;
import com.fasterxml.jackson.annotation.JsonInclude;
import ai.kompile.cli.common.chat.sources.adapters.KompileAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Only the server-owned conversation; never registers folders or imports native history. */
@RestController
@RequestMapping("/api/agents/chat/product")
public class ProductTranscriptController {
    private final ProductChatSessionService products;
    private final KompileAdapter transcripts;
    @Autowired
    public ProductTranscriptController(ProductChatSessionService products) { this(products, new KompileAdapter()); }
    ProductTranscriptController(ProductChatSessionService products, KompileAdapter transcripts) {
        this.products = products; this.transcripts = transcripts;
    }
    public record DisplayTurn(String role, String content,
                              @JsonInclude(JsonInclude.Include.NON_NULL) String productTurnId,
                              @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ProductDisplayTurn.ToolPresentation> toolPresentations) {
        public DisplayTurn(String role, String content) { this(role, content, null, List.of()); }
    }
    public record Transcript(String sessionId, List<DisplayTurn> turns, boolean displayHistoryLimited) { }

    @GetMapping("/transcript")
    public Transcript transcript(@RequestParam MultiValueMap<String, String> query) {
        if (!query.keySet().equals(Set.of("sessionId", "workingDirectory"))
                || query.values().stream().anyMatch(values -> values.size() != 1))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Exact product transcript identity required");
        products.validateIdentity(query.getFirst("sessionId"), query.getFirst("workingDirectory"), null);
        try {
            return displayProduct(products.sessionId(), transcripts.readProductDisplayTurns(products.sessionId()));
        } catch (IOException invalid) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Product transcript unavailable");
        }
    }

    static Transcript display(String sessionId, List<ChatTurn> source) {
        return displayProduct(sessionId, source.stream()
                .map(turn -> new ProductDisplayTurn(turn.role(), turn.content(), null, List.of())).toList());
    }

    static Transcript displayProduct(String sessionId, List<ProductDisplayTurn> source) {
        final int maxTurns = 200, maxText = 32768, maxTotal = 1048576;
        List<DisplayTurn> result = new ArrayList<>();
        int characters = 0, consumed = 0;
        boolean limited = false;
        Set<String> seenPresentations = new java.util.HashSet<>();
        for (int index = source.size() - 1; index >= 0 && result.size() < maxTurns; index--) {
            ProductDisplayTurn turn = source.get(index);
            String content = turn.content() == null ? "" : turn.content();
            if (content.length() > maxText) {
                content = content.substring(0, maxText) + "\n[Display text shortened; canonical transcript is unchanged.]";
                limited = true;
            }
            if (characters + content.length() > maxTotal) { limited = true; break; }
            // Reapply the same bounded scope/identity projection used for live collection.
            var metadata = ProductDisplayTurn.metadata(sessionId, turn.productTurnId(), turn.toolPresentations());
            ProductDisplayTurn projected = ProductDisplayTurn.project(sessionId, turn.role(), content, metadata);
            var presentations = projected.toolPresentations().stream()
                    .filter(item -> seenPresentations.add(projected.productTurnId() + "\n" + item.callId())).toList();
            int displaySize = metadata == null ? 0 : metadata.toString().length();
            if (characters + content.length() + displaySize > maxTotal) { limited = true; break; }
            characters += content.length() + displaySize; consumed++;
            result.add(new DisplayTurn(projected.role() == null ? "SYSTEM" : projected.role(), content,
                    projected.productTurnId(), presentations));
        }
        limited |= consumed < source.size();
        Collections.reverse(result);
        if (limited) result.add(0, new DisplayTurn("SYSTEM", "Earlier or long display history was omitted. The harness retains the canonical transcript on disk."));
        return new Transcript(sessionId, List.copyOf(result), limited);
    }
}

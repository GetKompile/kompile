package ai.kompile.crawl.graph;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * A single source-supported relationship observation from one discovery window.
 * Discovery-only: a witness records what the text says, not an admitted graph fact.
 *
 * <p>Provenance fields are host-anchored (the quote must be an exact substring of the
 * cited window). Semantic fields ({@code subject}, {@code predicateText}, {@code object},
 * {@code qualifier}) are model observations and are never truth verdicts. A qualified
 * observation ("did not approve", "would have") is retained with its qualifier so later
 * stages can distinguish asserted facts from negated, hypothetical, or uncertain text.</p>
 *
 * @param witnessId stable host-assigned identity, unique across the whole discovery run
 * @param windowId host-assigned window identity, globally scoped (never prompt-local "s1")
 * @param sourceId the corpus chunk the window came from
 * @param subject observed source-side mention, exactly as the text expresses it
 * @param predicateText observed relationship wording from the text
 * @param object observed target-side mention, exactly as the text expresses it
 * @param quote exact window substring containing the relationship expression
 * @param qualifier empty when asserted plainly; otherwise the negation/hypothetical marker
 */
record RelationshipWitness(
        String witnessId,
        String windowId,
        String sourceId,
        String subject,
        String predicateText,
        String object,
        String quote,
        String qualifier) {

    RelationshipWitness {
        witnessId = requireText(witnessId, "witnessId");
        windowId = requireText(windowId, "windowId");
        sourceId = requireText(sourceId, "sourceId");
        subject = requireText(subject, "subject");
        predicateText = requireText(predicateText, "predicateText");
        object = requireText(object, "object");
        quote = requireText(quote, "quote");
        qualifier = qualifier == null ? "" : qualifier.trim();
        if (qualifier.length() > MAX_QUALIFIER_CHARS) {
            throw new IllegalArgumentException("qualifier must be at most "
                    + MAX_QUALIFIER_CHARS + " characters");
        }
    }

    static final int MAX_QUALIFIER_CHARS = 128;

    /** Provenance identity: two witnesses of the same window/quote/roles are one observation. */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof RelationshipWitness that)) return false;
        return windowId.equals(that.windowId)
                && quote.equals(that.quote)
                && normalize(subject).equals(normalize(that.subject))
                && normalize(predicateText).equals(normalize(that.predicateText))
                && normalize(object).equals(normalize(that.object))
                && qualifier.equalsIgnoreCase(that.qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(windowId, quote,
                normalize(subject), normalize(predicateText), normalize(object),
                qualifier.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return "witness[" + witnessId + " " + normalize(subject) + " -"
                + normalize(predicateText) + "-> " + normalize(object)
                + (qualifier.isEmpty() ? "" : " q=" + qualifier) + "]";
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must be a nonblank string");
        }
        return value.trim();
    }

    /** Stable deterministic ordering for prompts, reports, and replay parity. */
    static Map<String, Object> promptView(RelationshipWitness witness) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("witnessId", witness.witnessId());
        view.put("sourceId", witness.windowId());
        view.put("subject", witness.subject());
        view.put("predicateText", witness.predicateText());
        view.put("object", witness.object());
        view.put("quote", witness.quote());
        if (!witness.qualifier().isEmpty()) {
            view.put("qualifier", witness.qualifier());
        }
        return view;
    }
}

package ai.kompile.cli.main.chat.activity;

/** Explicit caps for metadata/detail reads. */
public record ActivityReadBudget(int maxBytes, int maxRecords, int maxTurns, int maxItems) {
    public static final ActivityReadBudget DEFAULT = new ActivityReadBudget(
            256 * 1024, 512, 100, 100);

    public ActivityReadBudget {
        maxBytes = positive(maxBytes, "maxBytes");
        maxRecords = positive(maxRecords, "maxRecords");
        maxTurns = positive(maxTurns, "maxTurns");
        maxItems = positive(maxItems, "maxItems");
    }

    public ActivityReadBudget(int maxBytes, int maxRecords) {
        this(maxBytes, maxRecords, 100, 100);
    }

    private static int positive(int value, String name) {
        if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}

package ai.kompile.cli.main.chat.activity;

/** A presence-aware numeric observation. {@code known=false} means unknown, not zero. */
public record ActivityMetric(long value, boolean known, boolean estimated, String source) {
    public ActivityMetric {
        value = Math.max(0L, value);
        source = clean(source, 120);
        if (!known) {
            value = 0L;
        }
    }

    public static ActivityMetric unknown(String source) {
        return new ActivityMetric(0L, false, false, source);
    }

    public static ActivityMetric observed(long value, String source) {
        return new ActivityMetric(value, true, false, source);
    }

    public static ActivityMetric estimated(long value, String source) {
        return new ActivityMetric(value, true, true, source);
    }

    public ActivityMetric prefer(ActivityMetric other) {
        if (other == null || !other.known()) return this;
        if (!known()) return other;
        if (estimated() && !other.estimated()) return other;
        return this;
    }

    private static String clean(String value, int max) {
        if (value == null) return "";
        String normalized = value.replace('\n', ' ').replace('\r', ' ').strip();
        return normalized.length() <= max ? normalized : normalized.substring(0, max - 1) + "…";
    }
}

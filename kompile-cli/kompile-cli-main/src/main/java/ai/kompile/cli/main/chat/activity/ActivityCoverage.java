package ai.kompile.cli.main.chat.activity;

/** Availability of an activity section; absence is never represented as a zero value. */
public enum ActivityCoverage {
    COMPLETE,
    PARTIAL,
    UNAVAILABLE,
    ERROR
}

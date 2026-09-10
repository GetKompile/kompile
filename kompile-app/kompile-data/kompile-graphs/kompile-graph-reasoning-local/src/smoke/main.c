/*
 * Kompile Graph Reasoning C Smoke Test
 *
 * Exercises the kgr_* C ABI exported by libkompile_reasoning.so.
 * Exit 0 only if ALL checks pass.
 *
 * Usage (see run-smoke.sh):
 *   gcc -I<headers> -L<lib> -lkompile_reasoning -o smoke src/smoke/main.c
 *   LD_LIBRARY_PATH=<lib> ./smoke <fixture.kgraph> <roundtrip.kgraph>
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/* ── VmRSS helper (Linux /proc/self/status) ────────────────────────────────── */
/* Returns current RSS in kB, or -1 on failure (non-Linux). */
static long read_vmrss_kb(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return -1;
    char line[256];
    long rss = -1;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "VmRSS:", 6) == 0) {
            sscanf(line + 6, "%ld", &rss);
            break;
        }
    }
    fclose(f);
    return rss;
}

/* Public C ABI — canonical checked-in header (no graal_isolate.h needed) */
#include "kompile_reasoning.h"

/* ── ANSI colours ──────────────────────────────────────────────────────────── */
#define GREEN  "\033[32m"
#define RED    "\033[31m"
#define RESET  "\033[0m"

/* ── Check macro ───────────────────────────────────────────────────────────── */
static int g_pass = 0;
static int g_fail = 0;

#define CHECK(label, cond)                                                  \
    do {                                                                    \
        if (cond) {                                                         \
            printf(GREEN "  PASS" RESET ": %s\n", label);                  \
            g_pass++;                                                       \
        } else {                                                            \
            printf(RED   "  FAIL" RESET ": %s\n", label);                  \
            g_fail++;                                                       \
        }                                                                   \
    } while (0)

/* ── Helpers ───────────────────────────────────────────────────────────────── */
static int json_contains(const char *json, const char *needle) {
    return json != NULL && strstr(json, needle) != NULL;
}

/* ── Main ──────────────────────────────────────────────────────────────────── */
int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "Usage: %s <fixture.kgraph> <roundtrip.kgraph> <retract.kgraph>\n", argv[0]);
        return 2;
    }
    const char *fixture_path   = argv[1];
    const char *roundtrip_path = argv[2];
    const char *retract_path   = argv[3];

    printf("=== Kompile Graph Reasoning C Smoke Test ===\n");
    printf("  fixture:   %s\n", fixture_path);
    printf("  roundtrip: %s\n", roundtrip_path);
    printf("  retract:   %s\n", retract_path);
    printf("\n");

    /* ── 1. Create isolate (kgr_create_isolate builtin: no args, returns thread) ─
     * Note: kgr_create_isolate() is a GraalVM CREATE_ISOLATE builtin with a
     * simplified signature — no params, returns the thread handle directly.
     * This is different from graal_create_isolate() which uses out-params. */
    kgr_thread_t *thread = kgr_create_isolate();
    CHECK("kgr_create_isolate returns non-NULL thread", thread != NULL);
    if (thread == NULL) {
        fprintf(stderr, "Cannot create GraalVM isolate via kgr_create_isolate — aborting\n");
        return 1;
    }

    /* ── 2. ABI version ────────────────────────────────────────────────────── */
    int abi = kgr_abi_version(thread);
    printf("  kgr_abi_version = %d\n", abi);
    CHECK("kgr_abi_version == 1", abi == 1);

    /* ── 3. Open fixture ───────────────────────────────────────────────────── */
    long long session = kgr_open(thread, fixture_path);
    printf("  kgr_open session = %lld\n", session);
    if (session == 0) {
        const char *open_error = kgr_last_error(thread);
        fprintf(stderr, "  kgr_open error: %s\n", open_error ? open_error : "(null)");
        kgr_free(thread, open_error);
    }
    CHECK("kgr_open returns non-zero handle", session > 0);

    /* ── 4. Catalog — non-empty, contains ask_graph_verify ────────────────── */
    const char *catalog = kgr_tools(thread);
    printf("  kgr_tools length = %zu\n", catalog ? strlen(catalog) : 0);
    CHECK("kgr_tools returns non-null", catalog != NULL);
    CHECK("catalog is non-empty JSON array", catalog != NULL && catalog[0] == '[');
    CHECK("catalog contains 'ask_graph_verify'",
          json_contains(catalog, "ask_graph_verify"));
    CHECK("catalog contains 'graph_reasoning_query'",
          json_contains(catalog, "graph_reasoning_query"));
    kgr_free(thread, catalog);

    /* ── 5. OVERVIEW query ─────────────────────────────────────────────────── */
    const char *overview = kgr_dispatch(thread, session,
                                        "graph_reasoning_query",
                                        "{\"operation\":\"OVERVIEW\"}");
    printf("  OVERVIEW: %s\n", overview ? overview : "(null)");
    CHECK("OVERVIEW returns non-null", overview != NULL);
    CHECK("OVERVIEW does not contain ERROR status",
          !json_contains(overview, "\"ERROR\""));
    kgr_free(thread, overview);

    /* ── 6. ask_graph_verify on a known atom ──────────────────────────────── */
    /* The fixture has WORKS_AT(alice, acme) at weight 0.9 — after KB priming
       this should be SUPPORTED. UnifiedGraph.facts() generates atom keys with a
       space after the comma: "WORKS_AT(alice, acme)". */
    const char *verify1 = kgr_dispatch(thread, session,
                                        "ask_graph_verify",
                                        "{\"atom\":\"WORKS_AT(alice, acme)\"}");
    printf("  verify WORKS_AT: %s\n", verify1 ? verify1 : "(null)");
    CHECK("ask_graph_verify WORKS_AT returns non-null", verify1 != NULL);
    CHECK("ask_graph_verify WORKS_AT is SUPPORTED",
          json_contains(verify1, "SUPPORTED"));
    kgr_free(thread, verify1);

    /* ── 7. ask_graph_assert — assert a new atom ───────────────────────────── */
    /* Atom format must match what UnifiedGraph.facts() generates on reload:
       "PREDICATE(src, tgt)" with a space after the comma.
       The assert writes-through to the graph so it survives save/reload. */
    const char *assert1 = kgr_dispatch(thread, session,
                                        "ask_graph_assert",
                                        "{\"atom\":\"ASSOCIATED(alice, acme)\","
                                        "\"value\":0.8}");
    printf("  assert ASSOCIATED: %s\n", assert1 ? assert1 : "(null)");
    CHECK("ask_graph_assert returns non-null", assert1 != NULL);
    CHECK("ask_graph_assert does not return ERROR",
          !json_contains(assert1, "\"ERROR\""));
    kgr_free(thread, assert1);

    /* ── 8. kgr_save — persist to roundtrip path ───────────────────────────── */
    int save_rc = kgr_save(thread, session, roundtrip_path);
    printf("  kgr_save rc = %d\n", save_rc);
    CHECK("kgr_save returns 0", save_rc == 0);

    /* ── 9. Close original session ──────────────────────────────────────────── */
    kgr_close(thread, session);

    /* ── 10. Reload round-trip file ─────────────────────────────────────────── */
    long long session2 = kgr_open(thread, roundtrip_path);
    printf("  kgr_open(roundtrip) session = %lld\n", session2);
    CHECK("kgr_open(roundtrip) returns non-zero handle", session2 > 0);

    /* ── 11. Verify asserted atom survived round-trip ───────────────────────── */
    /* ASSOCIATED(alice, acme) was asserted and write-through added to graph topology
       before save. After reload, UnifiedGraph.facts() re-primes the KB from graph
       topology with atom key "ASSOCIATED(alice, acme)" (space after comma). */
    const char *verify2 = kgr_dispatch(thread, session2,
                                        "ask_graph_verify",
                                        "{\"atom\":\"ASSOCIATED(alice, acme)\"}");
    printf("  verify ASSOCIATED after reload: %s\n", verify2 ? verify2 : "(null)");
    CHECK("ask_graph_verify ASSOCIATED after reload is non-null", verify2 != NULL);
    CHECK("ask_graph_verify ASSOCIATED after reload is SUPPORTED",
          json_contains(verify2, "SUPPORTED"));
    kgr_free(thread, verify2);

    /* ── 12. graph_centrality pagerank ──────────────────────────────────────── */
    const char *pr = kgr_dispatch(thread, session2,
                                   "graph_centrality",
                                   "{\"algorithm\":\"pagerank\"}");
    printf("  pagerank: %s\n", pr ? pr : "(null)");
    CHECK("graph_centrality pagerank returns non-null", pr != NULL);
    CHECK("graph_centrality pagerank does not return ERROR",
          !json_contains(pr, "\"ERROR\""));
    CHECK("graph_centrality pagerank result contains 'scores'",
          json_contains(pr, "scores") || json_contains(pr, "pagerank") ||
          json_contains(pr, "alice"));
    kgr_free(thread, pr);

    /* ── 13-20. Reserved for future topology checks (placeholder) ───────────── */

    /* ── 21. ask_graph_retract — remove the previously asserted atom ─────────── */
    /* session2 currently has ASSOCIATED(alice, acme) from step 7 + round-trip.
       Retract it using ask_graph_retract (which also does topology write-through
       via removeRelation so the removal survives save/reload). */
    const char *retract1 = kgr_dispatch(thread, session2,
                                         "ask_graph_retract",
                                         "{\"atomKey\":\"ASSOCIATED(alice, acme)\"}");
    printf("  retract ASSOCIATED: %s\n", retract1 ? retract1 : "(null)");
    CHECK("ask_graph_retract returns non-null", retract1 != NULL);
    CHECK("ask_graph_retract does not return ERROR",
          !json_contains(retract1, "\"ERROR\""));
    CHECK("ask_graph_retract result shows status OK",
          json_contains(retract1, "\"status\":\"OK\""));
    CHECK("ask_graph_retract result topologyRemoved=true",
          json_contains(retract1, "\"topologyRemoved\":true"));
    kgr_free(thread, retract1);

    /* ── 22. Immediately verify retracted atom is now UNKNOWN ────────────────── */
    const char *verify3 = kgr_dispatch(thread, session2,
                                        "ask_graph_verify",
                                        "{\"atom\":\"ASSOCIATED(alice, acme)\"}");
    printf("  verify after retract (in-memory): %s\n", verify3 ? verify3 : "(null)");
    CHECK("ask_graph_verify after in-memory retract returns non-null", verify3 != NULL);
    CHECK("ask_graph_verify after in-memory retract is UNKNOWN",
          json_contains(verify3, "UNKNOWN"));
    kgr_free(thread, verify3);

    /* ── 23. kgr_save — persist the retracted state ──────────────────────────── */
    int save_rc2 = kgr_save(thread, session2, retract_path);
    printf("  kgr_save(retracted) rc = %d\n", save_rc2);
    CHECK("kgr_save(retracted) returns 0", save_rc2 == 0);
    kgr_close(thread, session2);

    /* ── 24. Reload the retracted graph ─────────────────────────────────────── */
    long long session3 = kgr_open(thread, retract_path);
    printf("  kgr_open(retracted) session = %lld\n", session3);
    CHECK("kgr_open(retracted) returns non-zero handle", session3 > 0);

    /* ── 25. Verify retracted atom is UNKNOWN after reload ──────────────────── */
    /* The topology write-through in ask_graph_retract removed the relation from
       the UnifiedGraph before save. On reload, UnifiedGraph.facts() no longer
       produces the atom "ASSOCIATED(alice, acme)", so the KB never sees it. */
    const char *verify4 = kgr_dispatch(thread, session3,
                                        "ask_graph_verify",
                                        "{\"atom\":\"ASSOCIATED(alice, acme)\"}");
    printf("  verify ASSOCIATED after retract+reload: %s\n", verify4 ? verify4 : "(null)");
    CHECK("ask_graph_verify ASSOCIATED after retract+reload returns non-null", verify4 != NULL);
    CHECK("ask_graph_verify ASSOCIATED after retract+reload is UNKNOWN",
          json_contains(verify4, "UNKNOWN"));
    kgr_free(thread, verify4);

    /* ── 26. Verify original WORKS_AT atom was NOT affected by retract ───────── */
    const char *verify5 = kgr_dispatch(thread, session3,
                                        "ask_graph_verify",
                                        "{\"atom\":\"WORKS_AT(alice, acme)\"}");
    printf("  verify WORKS_AT still present after retract+reload: %s\n",
           verify5 ? verify5 : "(null)");
    CHECK("ask_graph_verify WORKS_AT after retract+reload returns non-null", verify5 != NULL);
    CHECK("ask_graph_verify WORKS_AT is still SUPPORTED after retract",
          json_contains(verify5, "SUPPORTED"));
    kgr_free(thread, verify5);

    /* ── Cleanup ────────────────────────────────────────────────────────────── */
    kgr_close(thread, session3);

    /* ── 27. SOAK check: 5000 OVERVIEW dispatch/free cycles, bounded RSS ──────
     *
     * Purpose: verify serial GC reclaims between calls so a long-lived embedded
     * session (mobile chat app — unbounded tool calls per isolate lifetime) does
     * not exhaust heap. --gc=epsilon would guarantee OOM here; serial GC should
     * plateau well below the 512 MB cap.
     *
     * Method: read VmRSS from /proc/self/status before and after the loop.
     * All 5000 calls must return non-NULL AND RSS growth must be < 512 MB.
     * On non-Linux the RSS check is skipped (rss_before == -1).
     */
    printf("\n--- SOAK check (5000 OVERVIEW dispatch/free cycles) ---\n");
    long long soak_session = kgr_open(thread, fixture_path);
    CHECK("SOAK: kgr_open for soak session returns valid handle", soak_session > 0);

    int soak_all_nonnull = 1;
    long rss_before = read_vmrss_kb();

    if (soak_session > 0) {
        for (int i = 0; i < 5000; i++) {
            const char *r = kgr_dispatch(thread, soak_session,
                                         "graph_reasoning_query",
                                         "{\"operation\":\"OVERVIEW\"}");
            if (r == NULL) {
                soak_all_nonnull = 0;
                fprintf(stderr, "  SOAK: kgr_dispatch returned NULL at iteration %d\n", i);
            }
            kgr_free(thread, r);
        }
    }

    long rss_after = read_vmrss_kb();

    printf("  SOAK RSS before: %ld kB\n", rss_before);
    printf("  SOAK RSS after:  %ld kB\n", rss_after);

    long rss_growth_mb = (rss_before >= 0 && rss_after >= 0)
                         ? (rss_after - rss_before) / 1024
                         : 0;
    int rss_bounded = (rss_before < 0) /* non-Linux: skip RSS assertion */
                      || (rss_growth_mb < 512);

    printf("  SOAK RSS growth: %ld MB%s\n",
           rss_growth_mb,
           (rss_before < 0) ? " (skipped — /proc not available)" : "");

    CHECK("SOAK: all 5000 kgr_dispatch calls returned non-NULL", soak_all_nonnull);
    CHECK("SOAK: RSS growth < 512 MB (serial GC reclaims between calls)", rss_bounded);

    kgr_close(thread, soak_session);

    kgr_tear_down_isolate(thread);

    /* ── Summary ─────────────────────────────────────────────────────────────── */
    printf("\n=== Results: %d passed, %d failed ===\n", g_pass, g_fail);
    return (g_fail == 0) ? 0 : 1;
}

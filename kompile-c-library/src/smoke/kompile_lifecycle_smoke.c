/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * kompile_lifecycle_smoke.c
 *
 * Acceptance test for the self-contained kompile.h lifecycle API (PATH A).
 *
 * This file intentionally includes ONLY kompile.h (and stdio.h for printf).
 * It must compile and link without any GraalVM headers on the include path.
 *
 * What it tests:
 *   1. kompileCreateIsolate  — builtin CREATE_ISOLATE path (no-arg, returns
 *                              thread directly — same pattern as kgr_* library)
 *   2. kompileAbiVersion     — must equal KOMPILE_ABI_VERSION (1)
 *   3. kompileTearDownIsolate — clean shutdown
 *
 * It does NOT call initPipeline / runPipeline because those require a real
 * pipeline JSON file that is not shipped with the source tree.  The lifecycle
 * round-trip is the minimal acceptance criterion for the header directive.
 *
 * Exit codes: 0 = all checks passed; non-zero = first failure code.
 */

#include "kompile.h"   /* the ONLY external header — self-contained, no graal */
#include <stdio.h>

int main(void) {
    kompile_thread_t *thread = NULL;
    int ret;

    /* ── 1. Create isolate (PATH A builtin, no-arg form) ─────────────────── */
    printf("[smoke] kompileCreateIsolate() ... ");
    thread = kompileCreateIsolate();
    if (thread == NULL) {
        printf("FAIL (returned NULL)\n");
        return 2;
    }
    printf("OK (thread=%p)\n", (void*)thread);

    /* ── 2. ABI version check ────────────────────────────────────────────── */
    printf("[smoke] kompileAbiVersion() ... ");
    int abi = kompileAbiVersion(thread);
    if (abi != KOMPILE_ABI_VERSION) {
        printf("FAIL (got %d, expected %d)\n", abi, KOMPILE_ABI_VERSION);
        kompileTearDownIsolate(thread);
        return 3;
    }
    printf("OK (version=%d)\n", abi);

    /* ── 3. Tear down ────────────────────────────────────────────────────── */
    printf("[smoke] kompileTearDownIsolate() ... ");
    ret = kompileTearDownIsolate(thread);
    if (ret != 0) {
        printf("FAIL (ret=%d)\n", ret);
        return 4;
    }
    printf("OK\n");

    printf("[smoke] PASS — lifecycle round-trip complete (PATH A, zero graal headers)\n");
    return 0;
}

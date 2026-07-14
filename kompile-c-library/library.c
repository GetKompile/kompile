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
 * library.c — thin C wrapper over libkompile_pipelines.
 *
 * Lifecycle: uses the new kompile* builtin API (PATH A) — no graal_isolate.h
 * needed.  The isolate_thread and isolate fields in the handles struct are
 * stored as void* so they remain opaque to Python/ctypes consumers.
 */

#include "include/library.h"
#include <stdlib.h>
#include <stdio.h>


void initPipelineWrapper(char *pipelinePath, handles *h) {
    int ret = 0;
    kompile_thread_t *isolate_thread = NULL;

    printf("[kompile] About to create GraalVM isolate\n");
    isolate_thread = kompileCreateIsolate();
    if (isolate_thread == NULL) {
        printf("[kompile] Failed to create GraalVM isolate\n");
        return;
    }
    printf("[kompile] Created GraalVM isolate\n");

    /* Store isolate and thread in handles for use with subsequent calls.
     * The isolate field is left NULL here; for multi-thread use, retrieve
     * it via graal_get_isolate() from graal_isolate.h if needed. */
    h->isolate_thread = (void *) isolate_thread;
    h->isolate        = NULL;

    ret = initPipeline(isolate_thread, h, pipelinePath);
    if (ret != 0) {
        printf("[kompile] Pipeline initialization failed. Exit code: %d\n", ret);
    } else {
        printf("[kompile] Pipeline initialized successfully\n");
    }
}

int shutdown(kompile_thread_t *isolate_thread) {
    int ret = kompileDetachThread(isolate_thread);
    if (ret != 0) {
        printf("[kompile] Failed to detach from GraalVM isolate. Exit code: %d\n", ret);
    } else {
        printf("[kompile] Detached thread successfully\n");
    }
    return ret;
}


void runPipelineWrapper(handles *h, numpy_struct *input, numpy_struct *result) {
    kompile_thread_t  *isolate_thread = (kompile_thread_t  *) h->isolate_thread;
    kompile_isolate_t *isolate        = (kompile_isolate_t *) h->isolate;
    if (isolate_thread != NULL && isolate != NULL) {
        int ret = runPipeline(isolate_thread, h, input, result);
        if (ret != 0) {
            printf("[kompile] Pipeline execution failed. Exit code: %d\n", ret);
        }
    } else {
        if (isolate == NULL) {
            printf("[kompile] Error: isolate is null\n");
        }
        if (isolate_thread == NULL) {
            printf("[kompile] Error: isolate thread is null\n");
        }
    }
}

void checkMetricsWrapper(handles *h) {
    kompile_thread_t  *isolate_thread = (kompile_thread_t  *) h->isolate_thread;
    kompile_isolate_t *isolate        = (kompile_isolate_t *) h->isolate;
    if (isolate_thread != NULL && isolate != NULL) {
        printMetrics(isolate_thread);
    } else {
        if (isolate == NULL) {
            printf("[kompile] Error: isolate is null\n");
        }
        if (isolate_thread == NULL) {
            printf("[kompile] Error: isolate thread is null\n");
        }
    }
}

/* ==================== Kompile Lite API ==================== */

/*
 * Forward declarations for Lite API @CEntryPoint symbols.
 * These are not yet exported by PipelineNativeEntryPoints; they are declared
 * here so this wrapper compiles cleanly.  They will link at runtime once the
 * corresponding @CEntryPoint methods are added to the Java class.
 */
int   initLite(kompile_thread_t *, handles *, char *);
char* liteChat(kompile_thread_t *, handles *, char *, char *);
int   liteIngestDocument(kompile_thread_t *, handles *, char *);
char* liteRagQuery(kompile_thread_t *, handles *, char *, int);
char* liteGraphQuery(kompile_thread_t *, handles *, char *, int);
int   liteBuildGraph(kompile_thread_t *, handles *);

void initLiteWrapper(char *configPath, handles *h) {
    int ret = 0;
    kompile_thread_t *isolate_thread = NULL;
    printf("[kompile-lite] Creating GraalVM isolate\n");
    isolate_thread = kompileCreateIsolate();
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Failed to create GraalVM isolate\n");
        return;
    }

    h->isolate_thread = (void *) isolate_thread;
    h->isolate        = NULL;

    /* initLite is expected to be a @CEntryPoint in the native image */
    ret = initLite(isolate_thread, h, configPath);
    if (ret != 0) {
        printf("[kompile-lite] Lite initialization failed. Exit code: %d\n", ret);
    } else {
        printf("[kompile-lite] Initialized successfully\n");
    }
}

char* chatWrapper(handles *h, char *message, char *sessionId) {
    kompile_thread_t *isolate_thread = (kompile_thread_t *) h->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return NULL;
    }
    return liteChat(isolate_thread, h, message, sessionId);
}

int ingestDocumentWrapper(handles *h, char *filePath) {
    kompile_thread_t *isolate_thread = (kompile_thread_t *) h->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return -1;
    }
    return liteIngestDocument(isolate_thread, h, filePath);
}

char* ragQueryWrapper(handles *h, char *query, int maxResults) {
    kompile_thread_t *isolate_thread = (kompile_thread_t *) h->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return NULL;
    }
    return liteRagQuery(isolate_thread, h, query, maxResults);
}

char* graphQueryWrapper(handles *h, char *query, int k) {
    kompile_thread_t *isolate_thread = (kompile_thread_t *) h->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return NULL;
    }
    return liteGraphQuery(isolate_thread, h, query, k);
}

int buildGraphWrapper(handles *h) {
    kompile_thread_t *isolate_thread = (kompile_thread_t *) h->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return -1;
    }
    return liteBuildGraph(isolate_thread, h);
}

void freeCStringWrapper(char *str) {
    if (str != NULL) {
        free(str);
    }
}

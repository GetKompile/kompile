/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

#include "include/library.h"
#include <stdlib.h>
#include <stdio.h>


void initPipelineWrapper(char *pipelinePath, handles *handles) {
    int ret = 0;
    graal_isolatethread_t *isolate_thread = NULL;
    graal_isolate_t *isolate = NULL;
    printf("[kompile] About to create GraalVM isolate\n");
    ret = graal_create_isolate(NULL, &isolate, &isolate_thread);
    if (ret != 0) {
        printf("[kompile] Failed to create GraalVM isolate. Exit code: %d\n", ret);
        return;
    }
    printf("[kompile] Created GraalVM isolate\n");

    // Store isolate and thread in handles for use with subsequent calls
    handles->isolate_thread = isolate_thread;
    handles->isolate = isolate;

    ret = initPipeline(isolate_thread, handles, pipelinePath);
    if (ret != 0) {
        printf("[kompile] Pipeline initialization failed. Exit code: %d\n", ret);
    } else {
        printf("[kompile] Pipeline initialized successfully\n");
    }
}

int shutdown(graal_isolatethread_t *isolate_thread) {
    int ret = graal_detach_thread(isolate_thread);
    if (ret != 0) {
        printf("[kompile] Failed to detach from GraalVM isolate. Exit code: %d\n", ret);
    } else {
        printf("[kompile] Detached thread successfully\n");
    }
    return ret;
}


void runPipelineWrapper(handles *handles, numpy_struct *input, numpy_struct *result) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    graal_isolate_t *isolate = (graal_isolate_t *) handles->isolate;
    if (isolate_thread != NULL && isolate != NULL) {
        int ret = runPipeline(isolate_thread, handles, input, result);
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

void checkMetricsWrapper(handles *handles) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    graal_isolate_t *isolate = (graal_isolate_t *) handles->isolate;
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

void initLiteWrapper(char *configPath, handles *handles) {
    int ret = 0;
    graal_isolatethread_t *isolate_thread = NULL;
    graal_isolate_t *isolate = NULL;
    printf("[kompile-lite] Creating GraalVM isolate\n");
    ret = graal_create_isolate(NULL, &isolate, &isolate_thread);
    if (ret != 0) {
        printf("[kompile-lite] Failed to create GraalVM isolate. Exit code: %d\n", ret);
        return;
    }

    handles->isolate_thread = isolate_thread;
    handles->isolate = isolate;

    /* initLite is expected to be a @CEntryPoint in the native image */
    ret = initLite(isolate_thread, handles, configPath);
    if (ret != 0) {
        printf("[kompile-lite] Lite initialization failed. Exit code: %d\n", ret);
    } else {
        printf("[kompile-lite] Initialized successfully\n");
    }
}

char* chatWrapper(handles *handles, char *message, char *sessionId) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return NULL;
    }
    return liteChat(isolate_thread, handles, message, sessionId);
}

int ingestDocumentWrapper(handles *handles, char *filePath) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return -1;
    }
    return liteIngestDocument(isolate_thread, handles, filePath);
}

char* ragQueryWrapper(handles *handles, char *query, int maxResults) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return NULL;
    }
    return liteRagQuery(isolate_thread, handles, query, maxResults);
}

char* graphQueryWrapper(handles *handles, char *query, int k) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return NULL;
    }
    return liteGraphQuery(isolate_thread, handles, query, k);
}

int buildGraphWrapper(handles *handles) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    if (isolate_thread == NULL) {
        printf("[kompile-lite] Error: isolate thread is null\n");
        return -1;
    }
    return liteBuildGraph(isolate_thread, handles);
}

void freeCStringWrapper(char *str) {
    if (str != NULL) {
        free(str);
    }
}

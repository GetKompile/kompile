#ifndef __LIBKOMPILE_PIPELINES_H
#define __LIBKOMPILE_PIPELINES_H

#include <graal_isolate_dynamic.h>


#if defined(__cplusplus)
extern "C" {
#endif

typedef graal_isolatethread_t* (*kompileCreateIsolate_fn_t)();

typedef int (*kompileDetachThread_fn_t)(graal_isolatethread_t* thread);

typedef int (*kompileTearDownIsolate_fn_t)(graal_isolatethread_t* thread);

typedef graal_isolatethread_t* (*kompileAttachThread_fn_t)(graal_isolate_t* isolate);

typedef int (*kompileAbiVersion_fn_t)(graal_isolatethread_t* thread);

typedef int (*initPipeline_fn_t)(graal_isolatethread_t* thread, void * handlesPtr, char* pipelinePathPtr);

typedef int (*runPipeline_fn_t)(graal_isolatethread_t* thread, void * handlesPtr, void * inputPtr, void * resultPtr);

typedef void (*printMetrics_fn_t)(graal_isolatethread_t* thread);

#if defined(__cplusplus)
}
#endif
#endif

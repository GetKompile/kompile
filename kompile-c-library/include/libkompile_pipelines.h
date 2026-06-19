#ifndef __LIBKOMPILE_PIPELINES_H
#define __LIBKOMPILE_PIPELINES_H

#include <graal_isolate.h>


#if defined(__cplusplus)
extern "C" {
#endif

int initPipeline(graal_isolatethread_t* thread, void * handlesPtr, char* pipelinePathPtr);

int runPipeline(graal_isolatethread_t* thread, void * handlesPtr, void * inputPtr, void * resultPtr);

void printMetrics(graal_isolatethread_t* thread);

#if defined(__cplusplus)
}
#endif
#endif

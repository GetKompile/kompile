#ifndef __KONDUIT_SERVING_H
#define __KONDUIT_SERVING_H

#include <graal_isolate.h>


#if defined(__cplusplus)
extern "C" {
#endif

int run_main(int argc, char** argv);

int initPipeline(graal_isolatethread_t*, char*, numpy_struct*, numpy_struct*);

int runPipeline(graal_isolatethread_t*, char*, numpy_struct*, numpy_struct*);

void vmLocatorSymbol(graal_isolatethread_t* thread);

#if defined(__cplusplus)
}
#endif
#endif

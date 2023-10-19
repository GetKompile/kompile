#ifndef __KONDUIT_SERVING_H
#define __KONDUIT_SERVING_H

#include <graal_isolate_dynamic.h>


#if defined(__cplusplus)
extern "C" {
#endif

typedef int (*run_main_fn_t)(int argc, char** argv);

typedef int (*runPipeline_fn_t)(graal_isolatethread_t*, char*, int, char**, long long**, numpy_output*);

typedef void (*vmLocatorSymbol_fn_t)(graal_isolatethread_t* thread);

#if defined(__cplusplus)
}
#endif
#endif

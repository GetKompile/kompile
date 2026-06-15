#include "include/library.h"
#include <stdlib.h>
#include <stdio.h>


void initPipelineWrapper(char *pipelinePath,handles *handles) {
    int ret = 0;
    graal_isolatethread_t *isolate_thread = NULL;
    graal_isolate_t* isolate = NULL;
    printf("About to create context\n");
    ret = graal_create_isolate(NULL, &isolate, &isolate_thread);
    if(ret != 0) {
        printf("Failed to create graal context. Exit code was %d\n",ret);
    } else {
        printf("Created isolate\n");
    }


    //ensure we pass along the isolate and isolate thread for use with other methods
    //to preserve context
    handles->isolate_thread = isolate_thread;
    handles->isolate = isolate;
    initPipeline(isolate_thread,handles,pipelinePath);
    printf("After init pipeline in java\n");

}

int shutdown(graal_isolatethread_t *isolate_thread) {
    int ret = graal_detach_thread(isolate_thread);
    if(ret != 0) {
        printf("Failed to detach from  graal context. Exit code was %d\n",ret);
    } else {
        printf("Detached thread successfully\n");
    }

    if(ret != 0) {
        printf("Failed to tear down  graal context. Exit code was %d\n",ret);
    }

    return ret;
}


void runPipelineWrapper(handles *handles,numpy_struct *input,numpy_struct *result) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    graal_isolate_t* isolate = (graal_isolate_t*) handles->isolate;
    if(isolate_thread != NULL && isolate != NULL) {
        printf("In wrapper: about to run pipeline.\n");
        runPipeline(isolate_thread,handles,input,result);
        printf("Ran pipeline\n");
    } else {
        if(isolate == NULL) {
            printf("Isolate was null!\n");
        }
        if(isolate_thread == NULL) {
            printf("Isolated thread was null!");
        }
    }
}

void checkMetricsWrapper(handles *handles) {
    graal_isolatethread_t *isolate_thread = (graal_isolatethread_t *) handles->isolate_thread;
    graal_isolate_t* isolate = (graal_isolate_t*) handles->isolate;
    if(isolate_thread != NULL && isolate != NULL) {
        printf("In wrapper: about to run pipeline.\n");
        printMetrics(isolate_thread);
        printf("Ran pipeline\n");
    } else {
        if(isolate == NULL) {
            printf("Isolate was null!\n");
        }
        if(isolate_thread == NULL) {
            printf("Isolated thread was null!");
        }
    }
}


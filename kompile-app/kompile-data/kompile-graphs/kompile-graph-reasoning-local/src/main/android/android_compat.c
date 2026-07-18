/*
 * Narrow bionic compatibility layer for a stock GraalVM Native Image runtime.
 *
 * Keep every symbol weak: when Android or a newer LabsJDK supplies a native
 * implementation, that implementation wins. The exact public export map hides
 * all helpers from the application ABI.
 */
#include "android_compat.h"

#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>

#define KGR_WEAK __attribute__((weak))

long kgr_android_syscall(long number, ...) {
#if defined(__aarch64__)
    if (number != __NR_gettid) {
        errno = ENOSYS;
        return -1;
    }

    register long x8 __asm__("x8") = number;
    register long x0 __asm__("x0");
    __asm__ volatile("svc 0" : "=r"(x0) : "r"(x8) : "memory", "cc");

    if ((unsigned long) x0 >= (unsigned long) -4095) {
        errno = (int) -x0;
        return -1;
    }
    return x0;
#else
#error "Kompile Android Graal compatibility currently supports AArch64 only"
#endif
}

/* LabsJDK Unix sources reference glibc's POSIX strerror spelling. */
KGR_WEAK int __xpg_strerror_r(int error, char *buffer, size_t length) {
    if (buffer == NULL || length == 0) {
        return ERANGE;
    }
    const char *message = strerror(error);
    if (message == NULL) {
        buffer[0] = '\0';
        return EINVAL;
    }
    size_t required = strlen(message);
    if (required >= length) {
        memcpy(buffer, message, length - 1);
        buffer[length - 1] = '\0';
        return ERANGE;
    }
    memcpy(buffer, message, required + 1);
    return 0;
}

/* Android has no HotSpot container mode; accelerator isolation stays native. */
KGR_WEAK unsigned char JVM_IsUseContainerSupport(void) {
    return 0;
}

/* Unix paths are already native on Android/bionic. */
KGR_WEAK char *JVM_NativePath(char *path) {
    return path;
}

/* Zip's JDK helper only needs an opaque process-local monitor. */
KGR_WEAK void *JVM_RawMonitorCreate(void) {
    pthread_mutex_t *monitor = (pthread_mutex_t *) malloc(sizeof(*monitor));
    if (monitor == NULL) {
        return NULL;
    }
    if (pthread_mutex_init(monitor, NULL) != 0) {
        free(monitor);
        return NULL;
    }
    return monitor;
}

KGR_WEAK void JVM_RawMonitorDestroy(void *monitor) {
    if (monitor != NULL) {
        pthread_mutex_destroy((pthread_mutex_t *) monitor);
        free(monitor);
    }
}

KGR_WEAK int JVM_RawMonitorEnter(void *monitor) {
    if (monitor == NULL) {
        return EINVAL;
    }
    return pthread_mutex_lock((pthread_mutex_t *) monitor);
}

KGR_WEAK void JVM_RawMonitorExit(void *monitor) {
    if (monitor != NULL) {
        (void) pthread_mutex_unlock((pthread_mutex_t *) monitor);
    }
}

/*
 * Native Image registers only this initializer from the management component.
 * The upstream implementation caches a page size for native methods that are
 * not linked into this graph image, so the Android initializer is a no-op.
 */
KGR_WEAK void Java_com_sun_management_internal_OperatingSystemImpl_initialize0(
        void *environment, void *operating_system_class) {
    (void) environment;
    (void) operating_system_class;
}

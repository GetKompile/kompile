/*
 * Android/bionic compatibility declarations for the stock GraalVM Native
 * Image support sources. This file is force-included while compiling the
 * pinned upstream C sources; no upstream checkout is modified.
 */
#ifndef KGR_GRAAL_ANDROID_COMPAT_H
#define KGR_GRAAL_ANDROID_COMPAT_H

#include <ctype.h>
#include <unistd.h>

#ifdef __ANDROID__
long kgr_android_syscall(long number, ...);
#define syscall kgr_android_syscall
#endif

#endif

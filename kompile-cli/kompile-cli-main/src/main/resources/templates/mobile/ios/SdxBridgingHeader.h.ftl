//
//  {{projectName}}-Bridging-Header.h
//  {{projectName}}
//
//  Bridging header for SameDiff C runtime interop.
//  This header imports the native C API used by SdxInferenceService
//  for on-device model loading and inference.
//

#ifndef {{projectName}}_Bridging_Header_h
#define {{projectName}}_Bridging_Header_h

#ifdef __cplusplus
extern "C" {
#endif

// Import the SameDiff C runtime header.
// This provides functions for model loading, inference, and embedding generation.
// The actual library must be linked in the Xcode project build settings.
#if __has_include("dsp_runtime_c.h")
#include "dsp_runtime_c.h"
#endif

#ifdef __cplusplus
}
#endif

#endif /* {{projectName}}_Bridging_Header_h */

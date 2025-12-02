cmake_minimum_required(VERSION 3.16)
project(tokenizers-cpp-wrapper VERSION 1.0.0 LANGUAGES C CXX)

# Include ExternalProject module
include(ExternalProject)
include(GNUInstallDirs)

# C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# Build configuration
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

# Platform detection
if(NOT DEFINED PLATFORM)
    if(WIN32)
        set(PLATFORM "windows")
    elseif(APPLE)
        set(PLATFORM "darwin")
    else()
        set(PLATFORM "linux")
    endif()
endif()

if(NOT DEFINED ARCH)
    if(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64")
        set(ARCH "x86_64")
    elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
        set(ARCH "arm64")
    else()
        set(ARCH "${CMAKE_SYSTEM_PROCESSOR}")
    endif()
endif()

message(STATUS "Building for platform: ${PLATFORM}-${ARCH}")
message(STATUS "Build type: ${CMAKE_BUILD_TYPE}")

# Find Rust and Cargo
find_program(CARGO_EXECUTABLE cargo REQUIRED)
find_program(RUSTC_EXECUTABLE rustc REQUIRED)

message(STATUS "Found Cargo: ${CARGO_EXECUTABLE}")
message(STATUS "Found Rustc: ${RUSTC_EXECUTABLE}")

# Set external project directories
set(EXTERNAL_PROJECT_DIR "${CMAKE_CURRENT_BINARY_DIR}/external")
set(TOKENIZERS_SOURCE_DIR "${EXTERNAL_PROJECT_DIR}/src/tokenizers-external")
set(TOKENIZERS_LIB_DIR "${TOKENIZERS_SOURCE_DIR}/tokenizers")
set(TOKENIZERS_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/tokenizers-build")

# Platform-specific library names and extensions
if(PLATFORM STREQUAL "windows")
    set(WRAPPER_LIB_NAME "tokenizers_wrapper")
    set(LIB_SUFFIX ".dll")
    set(STATIC_LIB_SUFFIX ".lib")
elseif(PLATFORM STREQUAL "darwin")
    set(WRAPPER_LIB_NAME "tokenizers_wrapper")
    set(LIB_SUFFIX ".dylib")
    set(STATIC_LIB_SUFFIX ".a")
else()
    set(WRAPPER_LIB_NAME "tokenizers_wrapper")
    set(LIB_SUFFIX ".so")
    set(STATIC_LIB_SUFFIX ".a")
endif()

# Set build profile for Rust
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CARGO_BUILD_TYPE "debug")
    set(CARGO_BUILD_FLAG "")
else()
    set(CARGO_BUILD_TYPE "release")
    set(CARGO_BUILD_FLAG "--release")
endif()

# External project: Clone HuggingFace tokenizers
ExternalProject_Add(tokenizers-external
    GIT_REPOSITORY https://github.com/huggingface/tokenizers.git
    GIT_TAG main
    GIT_SHALLOW TRUE
    PREFIX ${EXTERNAL_PROJECT_DIR}
    CONFIGURE_COMMAND ""
    BUILD_COMMAND ""
    INSTALL_COMMAND ""
    UPDATE_COMMAND ""
    PATCH_COMMAND ""
)

# Build the tokenizers library as-is (it should already be a lib)
ExternalProject_Add(tokenizers-rust-build
    DEPENDS tokenizers-external
    PREFIX ${EXTERNAL_PROJECT_DIR}
    DOWNLOAD_COMMAND ""
    SOURCE_DIR ${TOKENIZERS_LIB_DIR}
    BINARY_DIR ${TOKENIZERS_BINARY_DIR}/main
    CONFIGURE_COMMAND ""
    BUILD_COMMAND 
        ${CMAKE_COMMAND} -E env CARGO_TARGET_DIR=${TOKENIZERS_BINARY_DIR}/main
        ${CARGO_EXECUTABLE} build ${CARGO_BUILD_FLAG} --manifest-path=${TOKENIZERS_LIB_DIR}/Cargo.toml --lib
    INSTALL_COMMAND ""
    BUILD_ALWAYS TRUE
    BUILD_BYPRODUCTS 
        ${TOKENIZERS_BINARY_DIR}/main/${CARGO_BUILD_TYPE}/libtokenizers.rlib
)

# Generate C bindings directly from the tokenizers library
ExternalProject_Add(tokenizers-bindings
    DEPENDS tokenizers-rust-build
    PREFIX ${EXTERNAL_PROJECT_DIR}
    DOWNLOAD_COMMAND ""
    SOURCE_DIR ${TOKENIZERS_LIB_DIR}
    BINARY_DIR ${TOKENIZERS_BINARY_DIR}/bindings
    CONFIGURE_COMMAND ""
    BUILD_COMMAND 
        ${CMAKE_COMMAND} -E make_directory ${TOKENIZERS_BINARY_DIR}/bindings/include &&
        ${CARGO_EXECUTABLE} install cbindgen --quiet --force &&
        cbindgen --lang c --output ${TOKENIZERS_BINARY_DIR}/bindings/include/tokenizers.h ${TOKENIZERS_LIB_DIR}
    INSTALL_COMMAND ""
    BUILD_ALWAYS TRUE
    BUILD_BYPRODUCTS 
        ${TOKENIZERS_BINARY_DIR}/bindings/include/tokenizers.h
)

# Source files for the C++ wrapper (including the new C FFI layer)
set(WRAPPER_SOURCES
    src/model_manager.cpp
    src/tokenizer_wrapper.cpp
    src/tokenizers_c.cpp
)

# Header files
set(WRAPPER_HEADERS
    include/model_manager.h
    include/tokenizer_wrapper.h
    include/tokenizers_c.h
)

# Create the wrapper library
add_library(${WRAPPER_LIB_NAME} SHARED ${WRAPPER_SOURCES} ${WRAPPER_HEADERS})

# Add dependency to ensure external projects are built first
add_dependencies(${WRAPPER_LIB_NAME} tokenizers-rust-build tokenizers-bindings)

# Include directories
target_include_directories(${WRAPPER_LIB_NAME} PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${TOKENIZERS_BINARY_DIR}/bindings/include
)

# Compiler-specific options
if(MSVC)
    target_compile_options(${WRAPPER_LIB_NAME} PRIVATE /W3 /EHsc)
    target_compile_definitions(${WRAPPER_LIB_NAME} PRIVATE _CRT_SECURE_NO_WARNINGS)
else()
    target_compile_options(${WRAPPER_LIB_NAME} PRIVATE -Wall -Wextra -fPIC)
    if(CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_compile_options(${WRAPPER_LIB_NAME} PRIVATE -g -O0)
    else()
        target_compile_options(${WRAPPER_LIB_NAME} PRIVATE -O3 -DNDEBUG)
    endif()
endif()

# For now, don't try to link the Rust library since it's not a shared lib
# target_link_libraries(${WRAPPER_LIB_NAME} PRIVATE
#     ${TOKENIZERS_BINARY_DIR}/main/${CARGO_BUILD_TYPE}/libtokenizers${LIB_SUFFIX}
# )

# Platform-specific linking options
if(PLATFORM STREQUAL "linux")
    target_link_libraries(${WRAPPER_LIB_NAME} PRIVATE dl pthread)
    set_target_properties(${WRAPPER_LIB_NAME} PROPERTIES
        LINK_FLAGS "-Wl,-rpath,$ORIGIN"
    )
elseif(PLATFORM STREQUAL "darwin")
    target_link_libraries(${WRAPPER_LIB_NAME} PRIVATE dl pthread)
    set_target_properties(${WRAPPER_LIB_NAME} PROPERTIES
        INSTALL_RPATH "@loader_path"
        BUILD_WITH_INSTALL_RPATH TRUE
    )
elseif(PLATFORM STREQUAL "windows")
    # Windows-specific settings
    set_target_properties(${WRAPPER_LIB_NAME} PROPERTIES
        WINDOWS_EXPORT_ALL_SYMBOLS TRUE
    )
endif()

# Output library naming
set_target_properties(${WRAPPER_LIB_NAME} PROPERTIES
    OUTPUT_NAME "${WRAPPER_LIB_NAME}"
    VERSION ${PROJECT_VERSION}
    SOVERSION 1
)

# Custom target to copy generated headers to our include directory
add_custom_target(copy-headers
    DEPENDS tokenizers-bindings
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_CURRENT_SOURCE_DIR}/include
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${TOKENIZERS_BINARY_DIR}/bindings/include/tokenizers.h
        ${CMAKE_CURRENT_SOURCE_DIR}/include/tokenizers_rust.h
    COMMENT "Copying generated C headers"
)

# Make sure headers are copied before building the wrapper
add_dependencies(${WRAPPER_LIB_NAME} copy-headers)

# Installation
install(TARGETS ${WRAPPER_LIB_NAME}
    LIBRARY DESTINATION ${CMAKE_INSTALL_LIBDIR}
    ARCHIVE DESTINATION ${CMAKE_INSTALL_LIBDIR}
    RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR}
)

install(FILES ${WRAPPER_HEADERS}
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/tokenizers
)

# Install the generated C header if it exists
install(FILES ${CMAKE_CURRENT_SOURCE_DIR}/include/tokenizers_rust.h
    DESTINATION ${CMAKE_INSTALL_INCLUDEDIR}/tokenizers
    OPTIONAL
)

# Create a pkg-config file
configure_file(
    ${CMAKE_CURRENT_SOURCE_DIR}/tokenizers.pc.in
    ${CMAKE_CURRENT_BINARY_DIR}/tokenizers.pc
    @ONLY
)

install(FILES ${CMAKE_CURRENT_BINARY_DIR}/tokenizers.pc
    DESTINATION ${CMAKE_INSTALL_LIBDIR}/pkgconfig
)

# Custom target for cleaning external projects
add_custom_target(clean-external
    COMMAND ${CMAKE_COMMAND} -E remove_directory ${EXTERNAL_PROJECT_DIR}
    COMMAND ${CMAKE_COMMAND} -E remove_directory ${TOKENIZERS_BINARY_DIR}
    COMMENT "Cleaning external project directories"
)

# Print build summary
message(STATUS "")
message(STATUS "=== Build Configuration Summary ===")
message(STATUS "Platform: ${PLATFORM}")
message(STATUS "Architecture: ${ARCH}")
message(STATUS "Build Type: ${CMAKE_BUILD_TYPE}")
message(STATUS "Cargo Build Type: ${CARGO_BUILD_TYPE}")
message(STATUS "Wrapper Library: ${WRAPPER_LIB_NAME}${LIB_SUFFIX}")
message(STATUS "Tokenizers Source: ${TOKENIZERS_SOURCE_DIR}")
message(STATUS "Tokenizers Library: ${TOKENIZERS_LIB_DIR}")
message(STATUS "Tokenizers Binary: ${TOKENIZERS_BINARY_DIR}")
message(STATUS "Generated Headers: ${TOKENIZERS_BINARY_DIR}/bindings/include")
message(STATUS "Install Prefix: ${CMAKE_INSTALL_PREFIX}")
message(STATUS "===================================")

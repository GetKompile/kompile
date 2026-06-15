cmake_minimum_required(VERSION 3.19)
project(kompile_c_library C)

if(DEFINED  $ENV{INCLUDE_PATH})
    message("Adding include output path $ENV{INCLUDE_PATH}")
    include_directories(${INCLUDE_PATH})
endif()

if(DEFINED $ENV{LIB_OUTPUT_PATH})
    message("Adding library output path $ENV{LIB_OUTPUT_PATH}")
    link_directories($ENV{LIB_OUTPUT_PATH})
endif()

set(CMAKE_C_STANDARD 99)
include_directories(include)
link_directories(lib)
add_library(kompile_c_library SHARED library.c include/library.h)

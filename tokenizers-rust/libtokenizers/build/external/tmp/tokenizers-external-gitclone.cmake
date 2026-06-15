# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.5)

if(EXISTS "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitclone-lastrun.txt" AND EXISTS "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitinfo.txt" AND
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitclone-lastrun.txt" IS_NEWER_THAN "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitinfo.txt")
  message(STATUS
    "Avoiding repeated git clone, stamp file is up to date: "
    "'/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitclone-lastrun.txt'"
  )
  return()
endif()

execute_process(
  COMMAND ${CMAKE_COMMAND} -E rm -rf "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external"
  RESULT_VARIABLE error_code
)
if(error_code)
  message(FATAL_ERROR "Failed to remove directory: '/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external'")
endif()

# try the clone 3 times in case there is an odd git clone issue
set(error_code 1)
set(number_of_tries 0)
while(error_code AND number_of_tries LESS 3)
  execute_process(
    COMMAND "/usr/bin/git" 
            clone --no-checkout --depth 1 --no-single-branch --config "advice.detachedHead=false" "https://github.com/huggingface/tokenizers.git" "tokenizers-external"
    WORKING_DIRECTORY "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src"
    RESULT_VARIABLE error_code
  )
  math(EXPR number_of_tries "${number_of_tries} + 1")
endwhile()
if(number_of_tries GREATER 1)
  message(STATUS "Had to git clone more than once: ${number_of_tries} times.")
endif()
if(error_code)
  message(FATAL_ERROR "Failed to clone repository: 'https://github.com/huggingface/tokenizers.git'")
endif()

execute_process(
  COMMAND "/usr/bin/git" 
          checkout "main" --
  WORKING_DIRECTORY "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external"
  RESULT_VARIABLE error_code
)
if(error_code)
  message(FATAL_ERROR "Failed to checkout tag: 'main'")
endif()

set(init_submodules TRUE)
if(init_submodules)
  execute_process(
    COMMAND "/usr/bin/git" 
            submodule update --recursive --init 
    WORKING_DIRECTORY "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external"
    RESULT_VARIABLE error_code
  )
endif()
if(error_code)
  message(FATAL_ERROR "Failed to update submodules in: '/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external'")
endif()

# Complete success, update the script-last-run stamp file:
#
execute_process(
  COMMAND ${CMAKE_COMMAND} -E copy "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitinfo.txt" "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitclone-lastrun.txt"
  RESULT_VARIABLE error_code
)
if(error_code)
  message(FATAL_ERROR "Failed to copy script-last-run stamp file: '/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/tokenizers-external-gitclone-lastrun.txt'")
endif()

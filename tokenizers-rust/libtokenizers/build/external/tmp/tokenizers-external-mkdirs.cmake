# Distributed under the OSI-approved BSD 3-Clause License.  See accompanying
# file Copyright.txt or https://cmake.org/licensing for details.

cmake_minimum_required(VERSION 3.5)

file(MAKE_DIRECTORY
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external"
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-build"
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external"
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/tmp"
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp"
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src"
  "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp"
)

set(configSubDirs )
foreach(subDir IN LISTS configSubDirs)
    file(MAKE_DIRECTORY "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp/${subDir}")
endforeach()
if(cfgdir)
  file(MAKE_DIRECTORY "/home/agibsonccc/Documents/GitHub/kompile/tokenizers-rust/libtokenizers/build/external/src/tokenizers-external-stamp${cfgdir}") # cfgdir has leading slash
endif()

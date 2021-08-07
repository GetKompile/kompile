#!/bin/bash
cp ../konduit-serving/target/*.h include/
cp ../konduit-serving/target/*.so lib/
cmake .
make
cp libkompile_c_library.so ../kompile-python/lib/

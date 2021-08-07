#!/bin/bash
cd kompile-c-library/
./rebuild-and-link.sh
cd ..
cd kompile-python/
./rebuild_lib.sh
./run_test.sh

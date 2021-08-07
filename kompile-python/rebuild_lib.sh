#!/bin/bash
./delete_kompile_install.sh
cp ../konduit-serving/target/konduit-serving.so lib/libkonduit-serving.so
python setup.py build_ext --inplace install
python setup.py install


#!/bin/bash
./pipeline step-create python --fileFormat=json --pythonConfig="pythonCodePath=hello_world.py,ioInput=input numpy.ndarray NDARRAY,ioOutput=output numpy.ndarray NDARRAY" >> python-step-test.json
./pipeline sequence-pipeline-creator --pipeline=python-step-test.json >> python-test-pipeline.json

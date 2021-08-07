#!/bin/bash
cp -rf ./src/main/java/ai/konduit/pipelinegenerator/main/NumpyEntryPoint.java ./src/main/resources
mvn -Djavacpp.platform=linux-x86_64 clean install -DskipTests

#!/bin/bash
export PATH="/c:/Program Files/LLVM/bin:$PATH"
#export JAVA_HOME="/mnt/c/Users/agibs/graalvm-ce-java8-windows-amd64-21.0.0.2/graalvm-ce-java8-21.0.0.2"
export MAVEN_OPTS="-agentlib:native-image-agent=config-output-dir=META-INF/native-image "
mvn exec:java -Dexec.mainClass=ai.konduit.pipelinegenerator.main.NumpyEntryPoint

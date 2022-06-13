#!/bin/bash
#export PATH="/c:/Program Files/LLVM/bin:$PATH"
export JAVA_HOME="/home/agibsonccc/graalvm-ce-java17-22.1.0/"
export MAVEN_OPTS="-agentlib:native-image-agent=config-output-dir=META-INF/native-image"
mvn clean compile
mvn exec:java -Dexec.mainClass=ai.konduit.pipelinegenerator.main.MainCommand

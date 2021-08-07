#!/bin/bash
cp ../../pipeline .
cp -rf ../../target .
./pipeline pom-generate  --pipelinePath=/mnt/c/Users/agibs/Documents/GitHub/pipeline-generator/kompile-python/test_data/python-test-pipeline.json   --cli=true --onnx=true --python=true --imageName=konduit-serving --mainClass=ai.konduit.serving.cli.launcher.KonduitServingLauncher --outputFile=pom3.xml  --server=true
./pipeline native-image-generate --pipelinePath=/mnt/c/Users/agibs/Documents/GitHub/pipeline-generator/kompile-python/test_data/python-test-pipeline.json   --python=true --imageName=konduit-serving --mainClass=ai.konduit.serving.cli.launcher.KonduitServingLauncher --outputFile=pom3.xml --javacppPlatform=linux-x86_64 --server=true --mavenHome=/mnt/c/ProgramData/chocolatey/lib/maven/apache-maven-3.6.3/
cp konduit-serving/target/konduit-serving ./konduit-serving-server


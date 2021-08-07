#!/bin/bash
cp -rf ./src/main/java/ai/konduit/pipelinegenerator/main/NumpyEntryPoint.java ./src/main/resources
./pipeline pom-generate  --pipelinePath=/mnt/c/Users/agibs/Documents/GitHub/pipeline-generator/kompile-python/test_data/python-test-pipeline.json  --numpySharedLibrary=true --cli=true --onnx=true --python=true --imageName=konduit-serving --mainClass=ai.konduit.serving.cli.launcher.KonduitServingLauncher --outputFile=pom3.xml  --server=true
./pipeline native-image-generate --pipelinePath=/mnt/c/Users/agibs/Documents/GitHub/pipeline-generator/kompile-python/test_data/python-test-pipeline.json --numpySharedLibrary=true  --python=true --imageName=konduit-serving --mainClass=ai.konduit.serving.cli.launcher.KonduitServingLauncher --outputFile=pom3.xml --javacppPlatform=linux-x86_64 --server=true --mavenHome=/mnt/c/ProgramData/chocolatey/lib/maven/apache-maven-3.6.3/
cp konduit-serving/target/*.h kompile-c-library/include/
cp konduit-serving/target/*.so kompile-c-library/lib

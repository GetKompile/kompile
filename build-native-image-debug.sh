#!/bin/bash
./pipeline pom-generate --numpySharedLibrary=true --cli=true --imageName=konduit-serving --mainClass=ai.konduit.serving.cli.launcher.KonduitServingLauncher --outputFile=pom3.xml  --server=true --debug=true --debugPort=8000
./pipeline native-image-generate --numpySharedLibrary=true  --imageName=konduit-serving --mainClass=ai.konduit.serving.cli.launcher.KonduitServingLauncher --outputFile=pom3.xml --javacppPlatform=linux-x86_64 --server=true --mavenHome=/mnt/c/ProgramData/chocolatey/lib/maven/apache-maven-3.6.3/
cp konduit-serving/target/*.h kompile-c-library/include/
cp konduit-serving/target/*.so kompile-c-library/lib

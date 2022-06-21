#!/bin/bash
 rm -f python-step.json
 rm -f sequence-pipeline-python.json
 rm -f sequence-pipeline-python-server.json
../pipeline step-create python "--pythonConfig=pythonCodePath=hello_world.py,returnAllInputs=true,pythonConfigType=JAVACPP,pythonLibrariesPath=C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\site-packages;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\python3.9;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\python3.9\lib-dynload;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\python3.9\site-packages;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\numpy\windows-x86_64\python" --fileFormat=json >> python-step.json
../pipeline sequence-pipeline-creator --pipeline=python-step.json >> sequence-pipeline-python.json
../pipeline inference-server-create --pipeline=sequence-pipeline-python.json --port=9990 --protocol=http  >> sequence-pipeline-python-server.json
../pipeline serve  --pipeline=sequence-pipeline-python-server.json



./kompile generate-python-config --inputVariables="`./kompile generate-python-variable-config --variableName=input --pythonType=numpy.ndarray --valueType=NDARRAY`" --inputVariables="`./kompile generate-python-variable-config --variableName=input2 --pythonType=numpy.ndarray --valueType=NDARRAY`"
./kompile step-create python "--pythonConfig=`/kompile generate-python-config --inputVariables="`./kompile generate-python-variable-config --variableName=input --pythonType=numpy.ndarray --valueType=NDARRAY`" --inputVariables="`./kompile generate-python-variable-config --variableName=input2 --pythonType=numpy.ndarray --valueType=NDARRAY`"`" --fileFormat=json >> python-step.json

./kompile step-create python --pythonConfig="`./kompile generate-python-config --inputVariables=\\"`./kompile generate-python-variable-config --variableName=input --pythonType=numpy.ndarray --valueType=NDARRAY`\\" --inputVariables=\\"`./kompile generate-python-variable-config --variableName=input2 --pythonType=numpy.ndarray --valueType=NDARRAY`"


./kompile config generate-python-variable-config --variableName=input --pythonType=numpy.ndarray --valueType=NDARRAY >> input1.txt
 ./kompile config generate-python-variable-config --variableName=input2 --pythonType=numpy.ndarray --valueType=NDARRAY >> input2.txt
./kompile config generate-python-config --inputVariable=input1.txt --inputVariable=input2.txt >> python-config.json

./kompile exec step-create python --pythonConfig="`./kompile generate-python-config --inputVariable=input1.txt --inputVariable=input2.txt`" --fileFormat=json >> python-step.json

./kompile helper ndarray-helper --inputName=input1 --inputName=input1 --outputName=output1 >> python-config.json
./kompile step-create python --pythonConfig=python-config.json  --fileFormat=json >> python-step.json
echo 'import numpy as np; output1=input1 + input2;' >> output.py


echo "print('hello world')" >> hello_world.py
./kompile step-create python "--pythonConfig=pythonCodePath=hello_world.py" --fileFormat=json >> python-step.json
./kompile sequence-pipeline-creator --pipeline=python-step.json >> sequence-pipeline-python.json
./kompile inference-server-create --pipeline=sequence-pipeline-python.json --port=9990 --protocol=http  >> sequence-pipeline-python-server.json
# TODO: test NATIVE_IMAGE_FILE_PATH and --nativeImageFilePath= arg - the main problem was wrong resource args
# preventing instantiation of nd4j cpu and other device ops via reflection
./kompile generate-image-and-sdk --pipelineFile=sequence-pipeline-python.json --mavenHome=/kompile/mvn --kompilePythonPath=./kompile-python --kompileCPath=./kompile-c-library --nativeImageFilesPath=/kompile/src/main/resources/META-INF/native-image
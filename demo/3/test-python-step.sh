#!/bin/bash
 rm -f python-step.json
 rm -f sequence-pipeline-python.json
 rm -f sequence-pipeline-python-server.json
../pipeline step-create python "--pythonConfig=pythonCodePath=hello_world.py,returnAllInputs=true,pythonConfigType=JAVACPP,pythonLibrariesPath=C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\site-packages;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\python3.9;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\python3.9\lib-dynload;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\cpython\windows-x86_64\lib\python3.9\site-packages;C:\Users\agibs\.javacpp\cache\pipeline-generator-1.0-SNAPSHOT-shaded.jar\org\bytedeco\numpy\windows-x86_64\python" --fileFormat=json >> python-step.json
../pipeline sequence-pipeline-creator --pipeline=python-step.json >> sequence-pipeline-python.json
../pipeline inference-server-create --pipeline=sequence-pipeline-python.json --port=9990 --protocol=http  >> sequence-pipeline-python-server.json
../pipeline serve  --pipeline=sequence-pipeline-python-server.json


./kompile config generate-python-variable-config --variableName=input1 --pythonType=numpy.ndarray --valueType=NDARRAY >> input1.txt
 ./kompile config generate-python-variable-config --variableName=input2 --pythonType=numpy.ndarray --valueType=NDARRAY >> input2.txt
 ./kompile config generate-python-variable-config --variableName=output1 --pythonType=numpy.ndarray --valueType=NDARRAY >> output1.txt
./kompile config generate-python-config  --pythonCodePath=add.py --inputVariable=input1.txt --inputVariable=input2.txt --outputVariable=output1.txt >> python-config.json
./kompile exec step-create python --pythonConfig=python-config.json  --fileFormat=json >> python-step.json
./kompile exec sequence-pipeline-creator --pipeline=python-step.json >> sequence-pipeline-python.json

echo "import numpy as np; output1= input1 + input2" >> add.py
./kompile build generate-image-and-sdk  --pipelineFile=sequence-pipeline-python.json --mavenHome=/kompile/mvn --kompilePythonPath=./kompile-python --kompileCPath=./kompile-c-library --nativeImageFilesPath=/kompile/src/main/resources/META-INF/native-image
./kompile install sdk-install  --pathToWheel=./kompile-image-bundle/kompile-0.0.1-cp39-cp39-linux_x86_64.whl

wget https://github.com/onnx/models/blob/main/vision/classification/alexnet/model/bvlcalexnet-3.tar.gz?raw=true
./kompile convert --inputFile=bvlc_alexnet/model.onnx --format=onnx

https://github.com/KonduitAI/dl4j-test-resources/blob/master/src/main/resources/tf_graphs/max_lstm.pb?raw=true
 mv max_lstm.pb\?raw\=true max_lstm.pb
./kompile convert --inputFile=max_lstm.pb --format=tensorflow

wget https://github.com/KonduitAI/dl4j-test-resources/blob/master/src/main/resources/modelimport/keras/tfkeras/act_0.h5?raw=true
mv act_0.h5\?raw\=true act_0.h5
./kompile convert --inputFile=act_0.h5 --format=keras --kerasNetworkType=functional



 sudo docker  build  --ulimit nofile=98304:98304  .



./kompile exec step-create python --pythonConfig="`./kompile generate-python-config --inputVariable=input1.txt --inputVariable=input2.txt`" --fileFormat=json >> python-step.json

./kompile helper ndarray-helper --inputName=input1 --inputName=input1 --outputName=output1 >> python-config.json
echo 'import numpy as np; output1=input1 + input2;' >> output.py


echo "print('hello world')" >> hello_world.py
./kompile step-create python "--pythonConfig=pythonCodePath=hello_world.py" --fileFormat=json >> python-step.json
./kompile sequence-pipeline-creator --pipeline=python-step.json >> sequence-pipeline-python.json
./kompile inference-server-create --pipeline=sequence-pipeline-python.json --port=9990 --protocol=http  >> sequence-pipeline-python-server.json
# TODO: test NATIVE_IMAGE_FILE_PATH and --nativeImageFilePath= arg - the main problem was wrong resource args
# preventing instantiation of nd4j cpu and other device ops via reflection
./kompile generate-image-and-sdk --pipelineFile=sequence-pipeline-python.json --mavenHome=/kompile/mvn --kompilePythonPath=./kompile-python --kompileCPath=./kompile-c-library --nativeImageFilesPath=/kompile/src/main/resources/META-INF/native-image
~~~~# Kompile (README is WIP, for questions come to: https://community.konduit.ai)

Kompile  is a command line interface for interacting with 
the [eclipse deeplearning4j/nd4j ecosystems](https://github.com/deeplearning4j/deeplearning4j) and [konduit serving](https://github.com/KonduitAI/konduit-serving).


Building container:
```bash
docker  build -f Dockerfile.rockylinux8 --ulimit nofile=98304:98304 -t konduitai/kompile:latest  .
```

Running container cli command:
```bash
docker run --rm -it  konduitai/kompile
```

Running container interactively:
```bash
docker run --ulimit nofile=98304:98304 --rm -it  -v $(pwd):/mnt/:Z --entrypoint /bin/bash konduitai/kompile
```


Usage with docker:
```bash
docker pull ghcr.io/konduitai/kompile
```

Overview
--------------

1. kompile-python: The python SDK for use with generated models. The main entry point is a PipelineRunner that allows the execution of konduit serving pipelines.
2. kompile-c-library: The c library shim for use with a generated model in combination with the kompile-python SDK.


In order to build the CLI it is recommended to have 10g of RAM to build the whole CLI.

In order to avoid this, please use docker pull.

The CLI is separated by a number of namespaces.
The namespaces encapsulate various functionality
separated by categories. Categories include:

1. Execution: Execution related steps for konduit serving pipeline creation.
2. Build: Building graalvm native images and python sdks
3. Config: Generate various configuration objects needed in the exec namespace
4. Helper: Higher level helpers encapsulating common usage in the API
5. Info: Information regarding the local kompile install
6. Install: Install various components used by the CLI. These include components like graalvm, maven and anaconda.
7. Uninstall: Uninstall various components
8. Bootstrap: Setup SDK for building images.
9. Model: Model related functionality including conversion of models to dl4j formats and model debugging utilities.


Note that many of these commands may be used in conjunction with each other.
This is especially true of the exec namespace.

For more comprehensive docs, see the html pages in the [docs directory](./docs)
or for any command just pass -h or no flags to see help output.


Kompile CLI workflow
-----------------------

1. Create pipeline steps: Using the exec namespace in conjunction with the [step-create](./docs/step-create.html) namespace create any number of json files that represent steps in your pipeline. This can be anything from running image pre processing to running a model.
2. Create a pipeline: Using the exec namespace again, pass in any number of pipeline steps to [sequence-pipeline-create](./docs/sequence-pipeline-creator.html)
3. Generate a [python SDK](./docs/generate-image-and-sdk.html) for your use case that you can pip install as a wheel to any python environment.


Kompile Generate SDK setup
-----------------------------

Kompile has a number of components that are needed in order to setup and use it properly.
You can manage this with the [kompile install namespace](./docs/kompile-install.html)
Simply run:
```bash
/kompile/kompile install all
```
This will install graalvm, maven, and anaconda under $USER/.kompile.
All necessary components are under there.
In order to remove these components a user may run:
```bash
/kompile/kompile uninstall all
```

Optionally a user may also then take an output wheel and install it to their local directory using [sdk-install](./docs/sdk-install.html)
Using the output wheel, this will install the sdk to the locally managed anaconda under $USER/.kompile/python.

Kompile Model utilities
---------------------------
A common use case with the eclipse deeplearning4j ecosystem
is model import to deploy models in production or finetune them in a
java environment. Kompile also allows users to just run a conversion process
from the command line interface without writing any code.

Simply specify an input model path, the framework and the model output path.
For more information see [the model convert command page](./docs/kompile-model-convert.html)

For quick troubleshooting, other utilities for rendering a model file as text are also added.
This includes printing summaries for [dl4j](./docs/kompile-model-dl4j-summary.html) and [samediff](./docs/samediff-summary.html)
as well as [tensorflow](./docs/)

Memory management
---------------------------

When running a build such as for android, 
more memory maybe needed. There are a few potential options
the user has to combat memory issues when executing a build.
1. Ensure your docker container has a good amount of memory for running builds.
```
docker run --ulimit nofile=98304:98304 --memory="16g" --rm -it  --entrypoint /bin/bash konduitai/kompile

```

Change 16g to whatever you think your memory needed might be. Some builds require > 4g of RAM to run. We recommend
a large amount of memory for certain builds (especially the SDK builds or ones generating an nd4j backend)

2. Ensure that the CLI has enough heap space to run.
```
./kompile build generate-nd4j-backend --nd4jBackend=nd4j-native --nd4jClassifier=android-arm64 --buildPlatform=android-arm64 -Xmx10g -Xms10g

```
This can be done as above where we are generating an android build and specifying the heap space on the end.



3. Ensure that builds that involve a generated projects also have enough heap for running.

Finally, set:
```
MIN_RAM_MEGS=2000
MAX_RAM_MEGS=2000
```

where these are numbers sized in megabytes for SDK builds.


Main Use Cases
--------------

1. Run a python script:
A python script execution pipeline involves setting up
input and output variables. Each variable will generally have a name
and a type associated with it. This manifests itself in the form of a python config with the command:
```bash
/kompile/kompile config generate-python-variable-config --variableName=test --pythonType=numpy.ndarray  --valueType=NDARRAY >> input_1.json
/kompile/kompile config generate-python-variable-config --variableName=test2 --pythonType=numpy.ndarray  --valueType=NDARRAY >> input_2.json
```

For defining a python configuration we can use:
```bash
/kompile/kompile config generate-python-config --inputVariable=input_1.json --inputVariable=input_2.json --pythonCode="out = test + test2" --returnAllInputs >> pythonConfig.json
```
The above configuration generates a python configuration with 2 input variables that runs the embedded python code adding the 2 variables together.
The variables are read from files generated in the previous step.
There are other settings such as python path which allows users to incorporate external python libraries as needed.

For more information on the parameters please see the relevant [documentation](./docs/generate-python-config.html)

Next we need to incorporate the python configuration in to a python step.
An example following the 2 previous steps:
```bash
/kompile/kompile exec step-create python --fileFormat=json --pythonConfig=pythonConfig.json >> python-step.json
```

Finally, we need to create a pipeline using the above pipeline step.
An example:
```bash
/kompile/kompile exec sequence-pipeline-creator --pipeline=python-step.json >> python-pipeline.json
```

This creates a pipeline using the above pipeline step. Note that you can chain any number of pipeline steps together.
In this case, since it's sequence oriented ordering of the pipeline specified does matter.

More information can be found [here](./docs/sequence-pipeline-creator.html)

2. Run an imported model pipeline in python using the SDK

Imported models can either be of the dl4j zip or samediff flatbuffers format.
In order to import a custom model, a user should use the convert command first.
This can be done as follows:

```bash
/kompile/kompile model convert --inputFile=path/to/model.pb --outputFile=path/to/outputmodel.fb
```
From here we can figure out this is a tensorflow model. The same is true for onnx.

For importing the keras .h5 format in to the dl4j zip file format, do the following:
```bash
/kompile/kompile model convert --inputFile=path/to/model.h5 --outputFile=path/to/outputmodel.zip --kerasNetworkType=sequential (or functional)
```
The reason for the extra parameter is keras models can be either of the two types
and aren't always just a graph. Thusly they have slightly different structures.

After a user converts their model, you will want to configure either a dl4j step or a samediff step
depending on the input framework.

For dl4j do:
```bash
/kompile/kompile  --fileFormat=json  --inputNames=... --modelUri=path/to/model.zip --outputNames=...  >> model-step.json
```
For samediff do:
```bash
/kompile/kompile  --fileFormat=json  --inputNames=... --modelUri=path/to/model.fb --outputNames=...  >> model-step.json
```

Afterwards, create a sequential step similar to the above python:
```bash
/kompile/kompile exec sequence-pipeline-creator --pipeline=model-step.json
```

The final output will be a valid json file you can pass to the SDK for execution.
```bash
docker  build  -t ghcr.io/konduitai/kompile --ulimit nofile=98304:98304   .
docker run -it --ulimit nofile=98304:98304  --rm  --entrypoint /bin/bash ghcr.io/konduitai/kompile

/kompile/kompile build generate-image-and-sdk --kompilePrefix=/kompile --nativeImageFilesPath=/kompile/native-image/ --kompileCPath=/kompile/kompile-c-library/ --kompilePythonPath=/kompile/kompile-python --pythonExecutable=/root/.kompile/python/bin/python
docker run -it --ulimit nofile=98304:98304    --entrypoint /bin/bash ghcr.io/konduitai/kompile

```

3. Serve a model to communicate over REST

Firstly build a binary: 
```bash
/kompile/kompile build generate-serving-binary --protocol=http --kompilePrefix=/kompile --nativeImageFilesPath=/kompile/native-image/ --mainClass=ai.konduit.pipelinegenerator.main.ServingMain --pipelineFile=./inference-server.json
```

This will produce a binary under /kompile/kompile-image that can serve a pipeline.
This binary can now serve pipelines via http. In order to serve a pipeline, we need to create an inference configuration.
Let's reuse the pipeline from step  and create an inference server to go with it:
```bash
/kompile/kompile config generate-python-variable-config --variableName=test --pythonType=numpy.ndarray  --valueType=NDARRAY >> input_1.json
/kompile/kompile config generate-python-variable-config --variableName=test2 --pythonType=numpy.ndarray  --valueType=NDARRAY >> input_2.json
/kompile/kompile config generate-python-config --inputVariable=input_1.json --inputVariable=input_2.json --pythonCode="out = test + test2" --returnAllInputs >> pythonConfig.json
/kompile/kompile exec step-create python --fileFormat=json --pythonConfig=pythonConfig.json >> python-step.json
/kompile/kompile exec sequence-pipeline-creator --pipeline=python-step.json >> python-pipeline.json
/kompile/kompile exec inference-server-create --protocol=http --port=8080 --pipeline=python-pipeline.json >> inference-server.json
/kompile-image  --configFile=/kompile/inference-server.json --autoConfigurePythonPath=true
```
This will setup the configuration to use the inference-server.json generated earlier.
We use autoconfigurePath to automatically use the local kompile install's python.
When using the python runner, we need to handle setting the python path up.

The user should configure this themselves using one of:
1. --pythonPath: manually specify the python path. Required when user has custom python path requirements
outside a standard distribution like anaconda or pip.

2. autoConfigurePythonPath: uses the local kompile installs python at $USER/.kompile/python.
The python path is obtained from the python executable found in the kompile install's python directory.

3. --pythonExecutableForConfigure: configure a custom python executable. A user can either specify an absolute path
or a python binary found on the user's path with just python.


Lastly, depending on the python configuration and python path
please note that the user may need to also add conda to their path.
Our python step runner looks up certain metadata when loading the relevant dependencies
from the python path for execution.

It is recommended the user use the self contained miniconda install that comes with kompile.
In order to circumvent any issues, a user may use the following formula for python path execution:
```bash
./kompile install python
export PATH=$HOME/.kompile/python/bin:$PATH
```

Following these steps allows a user to serve a model that runs a python script.



sudo docker run -it  -v $(pwd):/local --ulimit nofile=98304:98304  --rm  --entrypoint /bin/bash ghcr.io/konduitai/kompile~~~~


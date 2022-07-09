# Kompile (README is WIP, for questions come to: https://community.konduit.ai)

Kompile  is a command line interface for interacting with 
the [eclipse deeplearning4j/nd4j ecosystems](https://github.com/eclipse/deeplearning4j) and [konduit serving](https://github.com/KonduitAI/konduit-serving).


Building container:
```bash
docker  build -t konduitai/kompile:latest  .
```

Running container cli command:
```bash
docker run --rm -it  konduitai/kompile
```

Running container interactively:
```bash
docker run --rm -it  --entrypoint /bin/bash konduitai/kompile
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
./kompile install all
```
This will install graalvm, maven, and anaconda under $USER/.kompile.
All necessary components are under there.
In order to remove these components a user may run:
```bash
./kompile uninstall all
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


Main Use Cases
--------------

1. Run a python script:
A python script execution pipeline involves setting up
input and output variables. Each variable will generally have a name
and a type associated with it. This manifests itself in the form of a python config with the command:
```bash
./kompile config python-variable-config --variableName=test --pythonType=list --secondaryType=numpy.ndarray --valueType=NDARRAY
```

3. Run an imported model pipeline in python using the SDK

4. Serve a model to communicate over REST
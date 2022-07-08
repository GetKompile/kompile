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
2. kompile-c-library: The c library shim for use with a generated model.


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

#!/bin/bash
rm -f gray-scale-step.json
rm -f vending-machine-onnx.json
rm -f image_to_ndarray_config.json
rm -f  vending-machine-pipeline.json
../pipeline step-create gray_scale --imageName=image  --fileFormat=json >> gray-scale-step.json
../pipeline step-create onnx --modelUri=/mnt/c/Users/Documents/Github/pipeline-generator/demo/vending-machines/vending-machines-code/agerace_v2.onnx --inputNames=input --outputNames=output,519,520 --fileFormat=json >> vending-machine-onnx.json
../pipeline step-create image_to_ndarray --fileFormat=json --config="height=256,width=256,channelLayout=RGB,normalization=SCALE 0.4435 0.4435 0.4435 0.2712 0.2712 0.2717 255" >> image_to_ndarray_config.json
../pipeline sequence-pipeline-creator    --pipeline=gray-scale-step.json --file-format=json --pipeline=image_to_ndarray_config.json --pipeline=vending-machine-onnx.json >> vending-machine-pipeline.json

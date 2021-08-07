#!/bin/bash
./pipeline pipeline-command-generate --imageName=konduit-serving --mainClass=ai..konduit.serving.vertx.api.DeployKonduitServing --outputFile=pom3.xml --pipelineFile=./sequence-pipeline-server.json --protocol=http --server=true

#!/bin/bash
export KOMPILE_MODEL_STORAGE_DIR=/mnt/c/Users/agibs/Documents/GitHub/pipeline-generator/native-image-scripts/serving/models
export KOMPILE_EXECUTABLE=/mnt/c/Users/agibs/Documents/GitHub/pipeline-generator/native-image-scripts/serving/konduit-serving-server
export PATH=/home/agibsonccc/miniconda3/bin/:$PATH
export MLFLOW_TRACKING_URI=sqlite:///konduit-serving-tracking.db
#export _MLFLOW_SERVER_FILE_STORE="${MLFLOW_TRACKING_URI}"
mlflow server --default-artifact-root ./artifacts --backend-store-uri sqlite:///konduit-serving-tracking.db  --host 0.0.0.0 --gunicorn-opts  "--log-level debug --error-logfile ./mlflow.log"

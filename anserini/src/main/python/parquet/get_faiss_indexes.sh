#!/bin/bash

#
#   Copyright 2025 Kompile Inc.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#

# Create the collections folder if it doesn't exist
mkdir -p collections

# Array of URLs to download
urls=(
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-arguana.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-bioasq.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-climate-fever.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-android.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-english.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-gaming.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-gis.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-mathematica.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-physics.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-programmers.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-stats.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-tex.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-unix.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-webmasters.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-cqadupstack-wordpress.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-dbpedia-entity.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-fever.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-fiqa.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-hotpotqa.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-nfcorpus.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-nq.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-quora.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-robust04.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-scifact.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-scidocs.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-signal1m.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-trec-covid.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-trec-news.bge-base-en-v1.5.20240107.tar.gz"
    "https://rgw.cs.uwaterloo.ca/pyserini/indexes/faiss/faiss-flat.beir-v1.0.0-webis-touche2020.bge-base-en-v1.5.20240107.tar.gz"
)

# Change to the collections directory
cd collections

# Download each file using wget in parallel
for url in "${urls[@]}"; do
    wget "$url" &
done

# Wait for all downloads to complete
wait

# Extract each tar.gz file in parallel
for file in *.tar.gz; do
    tar -xvzf "$file" &
done

# Wait for all extractions to complete
wait


#!/usr/bin/env bash
set -euo pipefail

# Remove only the eight previously audited old JAR backups.
# Current JARs, models, and build caches are not targeted.
rm -- \
  /home/agibsonccc/.kompile/lib/kompile-model-serving.jar.bak-pre-kv-maxalloc \
  /home/agibsonccc/.kompile/lib/kompile-model-serving.jar.bak-pre-final-native \
  /home/agibsonccc/.kompile/lib/kompile-model-serving.jar.bak-pre-restored \
  /home/agibsonccc/.kompile/lib/kompile-model-serving.jar.bak-pre-lease-lru \
  /home/agibsonccc/.kompile/lib/kompile-model-serving.jar.bak-0831 \
  /home/agibsonccc/.kompile/lib/kompile-cli.jar.bak-local-deploy \
  /home/agibsonccc/.kompile/lib/kompile-cli.jar.bak-0901 \
  /home/agibsonccc/.kompile/lib/kompile-cli.jar.bak-pre-oneshot

df -h /home/agibsonccc

#!/bin/bash
set -e

cd "$(dirname "$0")/.."

if [ ! -f "target/helixdb.jar" ]; then
    echo "JAR file not found. Building..."
    ./scripts/build.sh
fi

echo "Running HelixDB..."
java -jar target/helixdb.jar "$@"

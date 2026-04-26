#!/bin/bash
set -e

cd "$(dirname "$0")/.."

echo "Building HelixDB..."
mvn clean package -DskipTests

echo "Build complete. JAR file: target/helixdb.jar"

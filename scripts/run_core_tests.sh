#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "$0")/.." && pwd)"
classes_dir="${TMPDIR:-/tmp}/g10-companion-core-tests"

mkdir -p "$classes_dir"
java com.sun.tools.javac.Main \
  -d "$classes_dir" \
  "$project_dir/app/src/main/java/com/g10blelab/app/TripAnalysisEngine.java" \
  "$project_dir/app/src/main/java/com/g10blelab/app/RouteEnergyEstimator.java" \
  "$project_dir/core-tests/com/g10blelab/app/TripAnalysisEngineTest.java" \
  "$project_dir/core-tests/com/g10blelab/app/RouteEnergyEstimatorTest.java"

java -cp "$classes_dir" com.g10blelab.app.TripAnalysisEngineTest
java -cp "$classes_dir" com.g10blelab.app.RouteEnergyEstimatorTest

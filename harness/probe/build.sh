#!/usr/bin/env bash
# Build the standalone NMS probe with the pinned Folia paperweight dev bundle.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
case "$#" in
    1) OUTPUT_JAR="$1" ;;
    3) OUTPUT_JAR="$3" ;;
    *)
    echo "usage: build.sh [ignored-server-jar ignored-jdk-bin] <output.jar>" >&2
    exit 2
        ;;
esac

"$REPO_DIR/gradlew" -p "$SCRIPT_DIR" --no-configure-on-demand clean build

PROBE_JAR="$SCRIPT_DIR/build/libs/FAWEHarnessProbe-0.2.0.jar"
[[ -f "$PROBE_JAR" ]] || {
    echo "build.sh: expected Gradle output missing: $PROBE_JAR" >&2
    exit 1
}
mkdir -p "$(dirname "$OUTPUT_JAR")"
cp -f "$PROBE_JAR" "$OUTPUT_JAR"
echo "probe jar ready: $OUTPUT_JAR" >&2

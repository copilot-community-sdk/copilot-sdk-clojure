#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

case "$(uname -s)/$(uname -m)" in
  Darwin/arm64) platform=native-darwin-arm64 ;;
  Linux/x86_64) platform=native-linux-x64 ;;
  *) echo "Examples require macOS arm64 or glibc Linux x64." >&2; exit 2 ;;
esac
if (( $# > 1 )); then
  echo "Usage: $0 [example-name] (see examples/README.md)" >&2; exit 2
fi

mkdir -p target/example-work
exec clojure -J-Dclojure.main.report=stderr "-J-Djava.io.tmpdir=$PWD/target/example-work" \
  "-M:test:examples:$platform" -m offline-examples "$@"

#!/usr/bin/env bash

set -euo pipefail

if (( $# != 0 )); then
  echo "usage: $0" >&2
  exit 2
fi

common_git_dir="$(git rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" || {
  echo "error: run this script from the copilot-sdk-clojure repository" >&2
  exit 1
}

if [[ -n "${COPILOT_SDK_UPSTREAM:-}" ]]; then
  candidate="${COPILOT_SDK_UPSTREAM}"
else
  primary_checkout="$(dirname "${common_git_dir}")"
  candidate="$(dirname "${primary_checkout}")/copilot-sdk"
fi

upstream_root="$(git -C "${candidate}" rev-parse --show-toplevel 2>/dev/null)" || {
  echo "error: upstream github/copilot-sdk checkout not found at ${candidate}" >&2
  echo "clone it beside the primary copilot-sdk-clojure checkout or set COPILOT_SDK_UPSTREAM" >&2
  exit 1
}

origin_url="$(git -C "${upstream_root}" remote get-url origin 2>/dev/null)" || {
  echo "error: upstream checkout has no origin remote: ${upstream_root}" >&2
  exit 1
}

case "${origin_url}" in
  git@github.com:github/copilot-sdk.git | \
  ssh://git@github.com/github/copilot-sdk.git | \
  https://github.com/github/copilot-sdk | \
  https://github.com/github/copilot-sdk.git)
    ;;
  *)
    echo "error: expected origin to be github/copilot-sdk, found ${origin_url}" >&2
    echo "set COPILOT_SDK_UPSTREAM to a checkout with the canonical origin remote" >&2
    exit 1
    ;;
esac

printf '%s\n' "${upstream_root}"

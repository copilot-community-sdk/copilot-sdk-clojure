#!/usr/bin/env bash
# Trigger the Release workflow via GitHub CLI.
#
# Usage:
#   script/release.sh                              # release current version in build.clj
#   script/release.sh --sync-upstream 0.1.33       # sync to upstream then release
#   script/release.sh --bump                        # bump clj-patch then release
#   script/release.sh --set-version 0.1.33.1       # set explicit version then release
#   script/release.sh --snapshot --sync-upstream 0.1.33  # snapshot release
#
# Requires: gh CLI authenticated with workflow dispatch permissions.

set -euo pipefail

REPO="copilot-community-sdk/copilot-sdk-clojure"

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Trigger the Release workflow on GitHub Actions.

Version strategies (pick one, default: none):
  --sync-upstream VERSION   Sync to upstream 3-segment version (e.g., 0.1.33)
  --bump                    Bump the Clojure patch segment (e.g., 0.1.32.0 → 0.1.32.1)
  --set-version VERSION     Set an explicit 4-segment version (e.g., 0.1.33.1)
  (no flag)                 Release the current version in build.clj as-is

Options:
  --snapshot                Append -SNAPSHOT (for --sync-upstream or --bump)
  --dry-run                 Show the gh command without executing
  -h, --help                Show this help
EOF
  exit 0
}

strategy="none"
upstream_version=""
explicit_version=""
snapshot="false"
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sync-upstream)
      strategy="sync-upstream"
      upstream_version="${2:?--sync-upstream requires a 3-segment version (e.g., 0.1.33)}"
      shift 2
      ;;
    --bump)
      strategy="bump-clj-patch"
      shift
      ;;
    --set-version)
      strategy="set-version"
      explicit_version="${2:?--set-version requires a 4-segment version (e.g., 0.1.33.1)}"
      shift 2
      ;;
    --snapshot)
      snapshot="true"
      shift
      ;;
    --dry-run)
      dry_run=true
      shift
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      ;;
  esac
done

# Build the gh command
cmd=(gh workflow run release.yml
  --repo "$REPO"
  -f "version_strategy=$strategy"
  -f "snapshot=$snapshot"
)

[[ -n "$upstream_version" ]] && cmd+=(-f "upstream_version=$upstream_version")
[[ -n "$explicit_version" ]] && cmd+=(-f "explicit_version=$explicit_version")

# Show what will run
echo "Strategy:  $strategy"
[[ -n "$upstream_version" ]] && echo "Upstream:  $upstream_version"
[[ -n "$explicit_version" ]] && echo "Version:   $explicit_version"
[[ "$snapshot" == "true" ]]  && echo "Snapshot:  yes"
echo ""
echo "Command:"
echo "  ${cmd[*]}"
echo ""

if $dry_run; then
  echo "(dry run — not dispatched)"
  exit 0
fi

read -r -p "Dispatch release workflow? [y/N] " confirm
if [[ "$confirm" != [yY] ]]; then
  echo "Aborted."
  exit 1
fi

"${cmd[@]}"
echo "✓ Release workflow dispatched. Watch progress at:"
echo "  https://github.com/$REPO/actions/workflows/release.yml"

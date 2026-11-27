# Publishing

This page describes community SDK release operations. For the unpublished
native 2.x checkout and its separate platform runtime dependency, use the
[installation guide](doc/getting-started.md).

## Verify a candidate without publishing

```bash
bb test
bb test:native native-darwin-arm64
./run-all-examples.sh
bb jar
bb test:artifact native-darwin-arm64
```

Use `native-linux-x64` on glibc Linux x64. The artifact test runs from the
community JAR and its POM in a fresh consumer, not from `src/`. The consumer
chooses the upstream `com.microsoft/copilot-sdk-runtime` classifier. Do not
copy a platform binary into the community JAR or include the Java SDK.
These commands do not sign, deploy, or authenticate a model turn.

Publication is a separate maintainer decision. Use the protected Release
workflow, not a local deploy command to bypass its acceptance checks.

## Versioning

This project follows the upstream [Microsoft Copilot SDK](https://github.com/microsoft/copilot-sdk) release versioning with a 4-segment scheme:

```
UPSTREAM_MAJOR.UPSTREAM_MINOR.UPSTREAM_PATCH.CLJ_PATCH
```

- The first 3 segments match the upstream copilot-sdk release version (e.g., `2.0.3`)
- The 4th segment is a Clojure-specific patch counter (starts at `0`)
- Example: `2.0.3.0-SNAPSHOT` is the community development version. Its upstream
  classifier version is `2.0.3`; the native engine reports `1.0.116` and uses SDK protocol `4`.

### Syncing to a new upstream release

```bash
# Set version to match upstream release (resets clj-patch to 0)
clj -T:build sync-version :upstream '"2.0.3"' :snapshot true
```

### Bumping for Clojure-specific changes

```bash
# Increment the Clojure-specific patch
clj -T:build bump-version

# With SNAPSHOT suffix
clj -T:build bump-version :snapshot true
```

## Coordinates

The `copilot.sdk` import name does not change the community publisher.
Microsoft supplies the separate, platform-specific native runtime artifact.

| Repository | Group ID | Artifact ID |
|------------|----------|-------------|
| Maven Central | `io.github.copilot-community-sdk` | `copilot-sdk-clojure` |

> **Note:** The Clojars artifact `net.clojars.krukow/copilot-sdk` is deprecated.
> Use the Maven Central coordinates above.

## Build Commands

| Command | Description |
|---------|-------------|
| `clj -T:build jar` | Build JAR |
| `clj -T:build install` | Install to local Maven repo |
| `clj -T:build deploy-central` | Publish to Maven Central (release workflow step, not a local verification command) |
| `clj -T:build bundle` | Create bundle zip (manual upload) |

## Deploy to Maven Central

### Prerequisites

1. **Sonatype Central Portal account**: https://central.sonatype.com/ (sign in with GitHub)
2. **User token**: Generate at https://central.sonatype.com/account
3. **GPG key**: For signing artifacts

### Configure Credentials

Add to `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>YOUR_TOKEN_USERNAME</username>
      <password>YOUR_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

Or use environment variables: `CENTRAL_USERNAME` and `CENTRAL_PASSWORD`.

### Configure GPG

```bash
gpg --gen-key
gpg --list-secret-keys --keyid-format LONG
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

## GitHub Actions release workflow

Trigger the **Release** workflow on `main` (restricted to the repository
maintainer). Even if no release PR is needed, the workflow requires a
successful `ci.yml` push run on the exact candidate commit. That run includes
both native platform jobs, examples, and the pinned upstream comparison.

### Using `script/release.sh` (recommended)

The `script/release.sh` wrapper dispatches the workflow via `gh` CLI with a friendlier interface:

```bash
# Release the current version in build.clj (version_strategy=none)
script/release.sh

# Sync to an upstream release (version_strategy=sync-upstream)
script/release.sh --sync-upstream 2.0.3

# Bump the Clojure patch segment (version_strategy=bump-clj-patch)
script/release.sh --bump

# Set an explicit version (version_strategy=set-version)
script/release.sh --set-version 2.0.3.1

# Append -SNAPSHOT suffix
script/release.sh --snapshot --sync-upstream 2.0.3
script/release.sh --snapshot --bump

# Preview the gh command without dispatching
script/release.sh --dry-run

# Attribute an assistant-dispatched release to Copilot
script/release.sh --sync-upstream 2.0.3 --copilot-attribution
```

Run `script/release.sh --help` for full usage.

### Using the GitHub UI

Go to **Actions → Release → Run workflow** and fill in the inputs below.

### Workflow inputs

| Input | Type | Description |
|-------|------|-------------|
| `version_strategy` | choice | `none` (use current), `sync-upstream`, `bump-clj-patch`, or `set-version` |
| `upstream_version` | string | Required for `sync-upstream`; upstream SDK version, e.g., `2.0.3` |
| `explicit_version` | string | Required for `set-version`; full community version, e.g., `2.0.3.1` or `2.0.3.1-SNAPSHOT` |
| `snapshot` | boolean | Append `-SNAPSHOT` for `sync-upstream`/`bump-clj-patch` |
| `copilot_attribution` | boolean | Default `false`; prefix the release PR body and GitHub release notes with Copilot attribution on behalf of the dispatch actor |

For assistant-dispatched releases, set `copilot_attribution=true` (or use
`--copilot-attribution`). Both GitHub bodies start with the attribution header
and a blank line; the release commit also receives a `Co-authored-by` trailer.
The trailer is not added to either GitHub body. Manual runs leave attribution
disabled by default.

The workflow:

1. Applies the selected community version to the build and installation guides, without changing the pinned runtime classifier
2. Stamps the changelog
3. Regenerates API documentation with `bb docs` and stages it with the canonical release edits
4. Opens a PR to `main` if any staged release files changed, including generated documentation
5. Waits for CI and auto-merges the PR with a merge commit, preserving commit history
6. Tests the candidate: hermetic checks, real Linux native acceptance, and a standalone JAR consumer
7. Runs an authenticated model turn from the candidate JAR using the protected
   `COPILOT_NATIVE_SMOKE_TOKEN`. A missing or blank token fails the release;
   offline success does not skip or replace this gate.
8. Signs and deploys to Maven Central, tags the release (`vX.Y.Z.N`), and creates a [GitHub release](https://github.com/copilot-community-sdk/copilot-sdk-clojure/releases) with attached artifacts

SNAPSHOT versions are marked as pre-release.

### Prerequisites

- Enable **Allow auto-merge** in repository settings (Settings → General → Pull Requests)
- Required status check `ci` must be configured in branch protection rules

### Required secrets

Secrets should be configured as **environment secrets** on the `release` environment
(Settings → Environments → `release`), not as repository-level secrets.
This ensures they are only available to the release workflow.

| Secret | Description |
|--------|-------------|
| `RELEASE_TOKEN` | Fine-grained PAT with `contents: write` and `pull-requests: write` scopes — used to create the release PR (so CI triggers) and the GitHub release |
| `COPILOT_NATIVE_SMOKE_TOKEN` | Credential authorized for the release-only, no-tools model turn. Never log it or substitute a developer login. |
| `CENTRAL_USERNAME` | Sonatype Central Portal token username |
| `CENTRAL_PASSWORD` | Sonatype Central Portal token password |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key for artifact signing (required by Maven Central) |
| `GPG_PASSPHRASE` | Passphrase for the GPG key (optional — omit if key has no passphrase) |

#### Environment setup

1. Go to **Settings → Environments → New environment**, name it `release`
2. Add the secrets listed above as environment secrets
3. Optionally add protection rules:
   - **Required reviewers** — require approval before the release job runs
   - **Deployment branches** — restrict to `main` only

## Build Attestation

Release artifacts are attested with [SLSA build provenance](https://slsa.dev/) using [`actions/attest-build-provenance`](https://github.com/actions/attest-build-provenance). Both the JAR and bundle zip receive attestations.

View attestations: https://github.com/copilot-community-sdk/copilot-sdk-clojure/attestations

### Verifying an attestation

Use the [GitHub CLI](https://cli.github.com/) to verify a downloaded artifact against its attestation:

```bash
# Example using a published historical release
gh attestation verify copilot-sdk-clojure-0.1.23.0.jar \
  --repo copilot-community-sdk/copilot-sdk-clojure

# Verify a JAR from your local Maven cache
gh attestation verify ~/.m2/repository/io/github/copilot-community-sdk/copilot-sdk-clojure/0.1.23.0/copilot-sdk-clojure-0.1.23.0.jar \
  --repo copilot-community-sdk/copilot-sdk-clojure
```

A successful verification confirms the artifact was built by the GitHub Actions release workflow in this repository and has not been tampered with.

## Local Testing

```bash
clj -T:build install
```

# Upstream Copilot CLI JSON Schemas

These files are fetched verbatim from the `github-copilot-1.0.114-linux-x64.tgz` GitHub Release asset for Copilot CLI version `1.0.114` and verified against the release `SHA256SUMS.txt`.

Archive SHA-256: `e9687515f6db23c05e5403fbe1f7f5283dfa58f77240bb61ac935bed39f31727`

**Do not edit by hand.** To reproduce in a new directory, run:

```sh
COPILOT_CLI_SCHEMA_OUTPUT=./fresh-schemas bb script/codegen/fetch_schemas.clj --version 1.0.114
```

Schema version: `1.0.114`

# Upstream Copilot CLI JSON Schemas

These files are fetched verbatim from the `github-copilot-1.0.116-linux-x64.tgz` GitHub Release asset for Copilot CLI version `1.0.116` and verified against the release `SHA256SUMS.txt`.

Archive SHA-256: `5248c9f792885ebbeacf003ec4620c568c9b47e5ff6813c64d423d20dcff2bdf`

**Do not edit by hand.** To reproduce in a new directory, run:

```sh
COPILOT_CLI_SCHEMA_OUTPUT=./fresh-schemas bb script/codegen/fetch_schemas.clj --version 1.0.116
```

Schema version: `1.0.116`

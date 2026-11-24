# Documentation

Use the native Clojure SDK to run Copilot inside your JVM.

| Task | Guide |
|---|---|
| Configure a platform dependency and send a model request | [Getting started](getting-started.md) |
| Update a CLI-based application | [Migration from 1.x](guides/native-2x.md) |
| Choose an operation, handle errors, and release resources | [Public API](reference/API.md) |
| Try real-model chat, structured output, hooks and tools, or run offline smoke tests | From the checkout use `./run-all-examples.sh --live basic-chat` for a signed-in model, or `./run-all-examples.sh` without an account. See `examples/README.md`. |
| Understand deployment and ownership | [Native runtime decision](adr/2026-11-24-native-only-runtime.md) |
| Maintain event schemas or documentation | [Code generation](codegen.md), [documentation style](style.md) |

`bb docs` generates the public namespace reference: `github.copilot-sdk`,
`github.copilot-sdk.specs`, `github.copilot-sdk.instrument`, and
`github.copilot-sdk.tool-set`. The protocol transport and generated wire
namespaces are internal, not application APIs.

The native decision defines the current scope. Earlier decisions in `doc/adr/`
record why 1.x worked as it did; they are not transport or feature promises for
2.x. See the [1.x documentation at its source revision](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934/doc)
for those APIs and the [historical benchmark evidence](https://github.com/copilot-community-sdk/copilot-sdk-clojure/tree/01246672fc5fb8e8f2b50437eea6a55145e54934/benchmarks)
for that implementation. No 2.x performance comparison follows from those measurements.

In `test/resources/`, `native_2_0_1_contracts.edn` is the frozen upstream and
preview inventory. `native_2_0_1_rc_overlay.edn` maps that inventory to the
candidate's source, tests, and outstanding gaps. A mapped symbol or a passing
example is not a certificate of complete upstream parity.

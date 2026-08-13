import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const {
  fixtureRequiredArgs,
  parseArgs,
  requireArgs,
  steadyDriverRequiredArgs,
  validateDriverArgs,
} = require("../node/args.cjs");

const commonDriver = [
  "--mode=steady",
  "--uri=127.0.0.1:1",
  "--corpus=corpus.json",
  "--output=observations.ndjson",
  "--run-id=run",
  "--node-sdk-root=node-sdk",
];
const steadyDriver = steadyDriverRequiredArgs.map((name) => `--${name}=1`);

test("Node driver rejects an argument without -- prefix", () => {
  assert.throws(() => validateDriverArgs(["foo=bar"]), /Expected --name=value/);
});

test("Node steady driver requires every steady-only argument", () => {
  for (const missing of steadyDriverRequiredArgs) {
    const argv = [
      ...commonDriver,
      ...steadyDriver.filter((arg) => !arg.startsWith(`--${missing}=`)),
    ];
    assert.throws(() => validateDriverArgs(argv), new RegExp(`Missing --${missing}`));
  }
});

test("Node cold driver accepts the minimal common arguments", () => {
  const argv = commonDriver.map((arg) =>
    arg === "--mode=steady" ? "--mode=cold" : arg,
  );
  assert.equal(validateDriverArgs(argv).mode, "cold");
});

test("fixture rejects bad prefixes and requires every fixture argument", () => {
  assert.throws(() => parseArgs(["foo=bar"]), /Expected --name=value/);
  const complete = fixtureRequiredArgs.map((name) => `--${name}=value`);
  for (const missing of fixtureRequiredArgs) {
    const args = parseArgs(
      complete.filter((arg) => !arg.startsWith(`--${missing}=`)),
    );
    assert.throws(() => requireArgs(args, fixtureRequiredArgs),
      new RegExp(`Missing --${missing}`));
  }
});

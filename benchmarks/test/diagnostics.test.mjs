import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const {
  capture,
  diagnosticKeys,
  nullableNonnegativeNumber,
} = require("../node/diagnostics.cjs");

test("optional Node counters serialize unavailable values as null", () => {
  assert.equal(nullableNonnegativeNumber(undefined), null);
  assert.equal(nullableNonnegativeNumber(Number.NaN), null);
  assert.equal(nullableNonnegativeNumber(-1), null);
  assert.equal(nullableNonnegativeNumber(0), 0);
  assert.equal(nullableNonnegativeNumber(42), 42);
});

test("Node runtime diagnostics use the shared exact record shape", () => {
  const record = capture({
    runId: "run",
    workload: "ping",
    replicate: 0,
    pairOrderIndex: 1,
    workloadOrderIndex: 0,
    checkpoint: "pre-warmup",
    windowIndex: null,
    timedOperationCount: 0,
    validationOperationCount: 1,
    startedNs: process.hrtime.bigint(),
    rssBytes: 1024,
  });

  assert.deepEqual(Object.keys(record).sort(), [...diagnosticKeys].sort());
  assert.equal(record.implementation, "node");
  assert.equal(record["rss-bytes"], 1024);
  assert.ok(record["available-processors"] > 0);
  assert.ok(Number.isFinite(record["process-cpu-ms"]));
});

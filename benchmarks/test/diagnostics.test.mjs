import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const { capture, diagnosticKeys } = require("../node/diagnostics.cjs");

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

import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const { sampleOperations, validatePreflight } = require("../node/measurement.cjs");

test("validation fails before sampled evidence", async () => {
  const calls = [];
  const operation = async () => {
    calls.push("operation");
    return { invalid: true };
  };
  const validate = () => {
    calls.push("validate");
    throw new Error("invalid deterministic result");
  };

  await assert.rejects(
    validatePreflight(operation, validate),
    /invalid deterministic result/,
  );
  assert.deepEqual(calls, ["operation", "validate"]);

  calls.length = 0;
  const samples = await sampleOperations(3, operation, () => 1);
  assert.deepEqual(samples, [1, 1, 1]);
  assert.deepEqual(calls, ["operation", "operation", "operation"]);
});

import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { test } from "node:test";

const require = createRequire(import.meta.url);
const { parseRssResult } = require("../node/rss.cjs");

test("Node RSS parser accepts nonnegative integer kibibytes", () => {
  assert.equal(
    parseRssResult({ status: 0, stdout: " 42\n", stderr: "" }),
    42 * 1024,
  );
});

test("Node RSS parser rejects garbage blank and negative output", () => {
  for (const stdout of ["garbage", "   ", "-1"]) {
    assert.throws(
      () => parseRssResult({ status: 0, stdout, stderr: "diagnostic" }),
      (error) => {
        assert.match(error.message, /Invalid ps output/);
        assert.deepEqual(error.details, {
          stdout,
          stderr: "diagnostic",
          exit: 0,
        });
        return true;
      },
    );
  }
});

import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { test } from "node:test";
import {
  spawnWithReadiness,
  terminateAndWait,
  waitForExit,
} from "./spawn_readiness.mjs";

function assertExited(error) {
  assert.ok(error.child.exitCode !== null || error.child.signalCode !== null);
  return true;
}

test("spawn readiness succeeds and cleanup can be awaited", async () => {
  const result = await spawnWithReadiness("node", [
    "-e",
    'console.log(JSON.stringify({ready:true})); setInterval(()=>{},1000)',
  ]);
  assert.deepEqual(result.readiness, { ready: true });
  await terminateAndWait(result.child);
  assert.ok(result.child.signalCode !== null);
});

test("spawn readiness kills a hung child on timeout", async () => {
  await assert.rejects(
    spawnWithReadiness("node", ["-e", "setInterval(()=>{},1000)"], {
      timeoutMs: 50,
    }),
    (error) => /Timed out/.test(error.message) && assertExited(error),
  );
});

test("spawn readiness kills a child after malformed readiness", async () => {
  await assert.rejects(
    spawnWithReadiness("node", [
      "-e",
      'console.log("not-json"); setInterval(()=>{},1000)',
    ]),
    (error) => error instanceof SyntaxError && assertExited(error),
  );
});

test("spawn readiness handles premature exit", async () => {
  await assert.rejects(
    spawnWithReadiness("node", ["-e", "process.exit(3)"]),
    (error) => /exited before readiness/.test(error.message) && assertExited(error),
  );
});

test("spawn readiness handles child spawn errors", async () => {
  await assert.rejects(
    spawnWithReadiness("definitely-not-a-real-readiness-command", []),
    (error) => error.code === "ENOENT" && error.child.pid === undefined,
  );
});

test("waitForExit propagates a child error event", async () => {
  const child = new EventEmitter();
  child.exitCode = null;
  child.signalCode = null;
  child.pid = 1;
  const failure = new Error("forced child error");
  const waiting = waitForExit(child, 1000);
  child.emit("error", failure);
  await assert.rejects(waiting, (error) => error === failure);
});

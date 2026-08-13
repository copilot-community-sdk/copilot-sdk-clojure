import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { connect } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { test } from "node:test";
import { spawnWithReadiness, terminateAndWait } from "./spawn_readiness.mjs";

const corpusPath = resolve("benchmarks/corpus.json");
const fixturePath = resolve("benchmarks/fixture/server.mjs");
const corpus = JSON.parse(await readFile(corpusPath));

function frame(message) {
  const body = Buffer.from(JSON.stringify(message));
  return Buffer.concat([
    Buffer.from(`Content-Length: ${body.length}\r\n\r\n`),
    body,
  ]);
}

function responseReader(socket) {
  let buffer = Buffer.alloc(0);
  const waiting = [];
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    while (true) {
      const headerEnd = buffer.indexOf("\r\n\r\n");
      if (headerEnd === -1) return;
      const header = buffer.subarray(0, headerEnd).toString("ascii");
      const length = Number(/content-length:\s*(\d+)/i.exec(header)?.[1]);
      const start = headerEnd + 4;
      if (buffer.length < start + length) return;
      const message = JSON.parse(buffer.subarray(start, start + length));
      buffer = buffer.subarray(start + length);
      waiting.shift()?.resolve(message);
    }
  });
  return () =>
    new Promise((resolveMessage, reject) => {
      const timeout = setTimeout(
        () => reject(new Error("Timed out waiting for fixture response")),
        2000,
      );
      waiting.push({
        resolve(message) {
          clearTimeout(timeout);
          resolveMessage(message);
        },
      });
    });
}

async function startFixture(directory) {
  const state = join(directory, "state.json");
  const trace = join(directory, "trace.ndjson");
  const { child, readiness } = await spawnWithReadiness("node", [
    fixturePath,
    `--corpus=${corpusPath}`,
    `--state=${state}`,
    `--trace=${trace}`,
    "--phase=test",
    "--implementation=test",
  ]);
  return { child, readiness, state, trace };
}

async function stopFixture(fixture, expectedExit) {
  fixture.child.kill("SIGTERM");
  const exit = await new Promise((resolveExit, reject) => {
    const timeout = setTimeout(() => {
      fixture.child.kill("SIGKILL");
      reject(new Error("Fixture failed to clean up after SIGTERM"));
    }, 3000);
    fixture.child.on("exit", (code) => {
      clearTimeout(timeout);
      resolveExit(code);
    });
  });
  assert.equal(exit, expectedExit);
  return JSON.parse(await readFile(fixture.state));
}

test("fixture signals readiness and validates deterministic requests", async () => {
  const directory = await mkdtemp(join(tmpdir(), "copilot-benchmark-fixture-"));
  let fixture;
  try {
    fixture = await startFixture(directory);
    assert.equal(fixture.readiness.ready, true);
    const socket = connect(fixture.readiness.port, fixture.readiness.host);
    await new Promise((resolveConnect, reject) => {
      socket.once("connect", resolveConnect);
      socket.once("error", reject);
    });
    const nextResponse = responseReader(socket);
    socket.write(
      frame({
        jsonrpc: "2.0",
        id: 1,
        method: "connect",
        params: { token: corpus.connectionToken },
      }),
    );
    assert.equal((await nextResponse()).result.protocolVersion, corpus.protocolVersion);
    socket.write(
      frame({
        jsonrpc: "2.0",
        id: 2,
        method: "ping",
        params: { message: corpus.pingMessage },
      }),
    );
    assert.deepEqual((await nextResponse()).result, {
      message: corpus.pingMessage,
      timestamp: corpus.timestamp,
      protocolVersion: corpus.protocolVersion,
    });
    socket.end();
    const state = await stopFixture(fixture, 0);
    assert.deepEqual(state.counts, { connect: 1, ping: 1 });
    assert.equal(state.failed, false);
  } finally {
    if (fixture) await terminateAndWait(fixture.child);
    await rm(directory, { recursive: true, force: true });
  }
});

test("fixture rejects invalid payloads and still confirms cleanup", async () => {
  const directory = await mkdtemp(join(tmpdir(), "copilot-benchmark-fixture-"));
  let fixture;
  try {
    fixture = await startFixture(directory);
    const socket = connect(fixture.readiness.port, fixture.readiness.host);
    await new Promise((resolveConnect, reject) => {
      socket.once("connect", resolveConnect);
      socket.once("error", reject);
    });

    const nextResponse = responseReader(socket);
    socket.write(
      frame({
        jsonrpc: "1.0",
        id: 1,
        method: "ping",
        params: { message: corpus.pingMessage },
      }),
    );
    assert.match((await nextResponse()).error.message, /JSON-RPC version/);
    socket.write(
      frame({
        jsonrpc: "2.0",
        id: 2,
        method: "ping",
        params: { message: corpus.pingMessage },
        extra: true,
      }),
    );
    assert.match((await nextResponse()).error.message, /request keys/);
    socket.write(
      frame({
        jsonrpc: "2.0",
        id: { invalid: true },
        method: "ping",
        params: { message: corpus.pingMessage },
      }),
    );
    assert.match((await nextResponse()).error.message, /request id/);
    socket.end();
    const state = await stopFixture(fixture, 1);
    assert.equal(state.failed, true);
  } finally {
    if (fixture) await terminateAndWait(fixture.child);
    await rm(directory, { recursive: true, force: true });
  }
});

test("fixture records scalar and array JSON request failures", async () => {
  const directory = await mkdtemp(join(tmpdir(), "copilot-benchmark-fixture-"));
  let fixture;
  try {
    fixture = await startFixture(directory);
    const socket = connect(fixture.readiness.port, fixture.readiness.host);
    await new Promise((resolveConnect, reject) => {
      socket.once("connect", resolveConnect);
      socket.once("error", reject);
    });
    const nextResponse = responseReader(socket);
    for (const value of ["invalid", 42, [], null]) {
      socket.write(frame(value));
      assert.match(
        (await nextResponse()).error.message,
        /JSON-RPC request must be an object/,
      );
    }
    socket.end();
    const state = await stopFixture(fixture, 1);
    assert.equal(state.failed, true);
    const traces = (await readFile(fixture.trace, "utf8")).trim().split("\n");
    assert.equal(traces.length, 4);
    assert.ok(
      traces.every(
        (line) => JSON.parse(line).method === "<invalid>",
      ),
    );
  } finally {
    if (fixture) await terminateAndWait(fixture.child);
    await rm(directory, { recursive: true, force: true });
  }
});

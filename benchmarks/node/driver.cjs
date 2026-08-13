"use strict";

const { appendFileSync, readFileSync } = require("node:fs");
const { spawnSync } = require("node:child_process");
const { validateDriverArgs } = require("./args.cjs");
const { sampleOperations, validatePreflight } = require("./measurement.cjs");

const args = validateDriverArgs(process.argv.slice(2));

const corpus = JSON.parse(readFileSync(args.corpus));
const sdk = require(args["node-sdk-root"]);
const { CopilotClient, RuntimeConnection } = sdk;
if (
  typeof CopilotClient !== "function" ||
  typeof RuntimeConnection?.forUri !== "function"
) {
  throw new Error(
    "Configured Node SDK package does not publicly export CopilotClient and RuntimeConnection.forUri; build the resolved upstream nodejs package",
  );
}

const implementation = "node";
const warmup = Number(args.warmup ?? 0);
const iterations = Number(args.iterations ?? 1);
const timeoutMs = Number(args["timeout-ms"] ?? 5000);
const sampleIndex = Number(args["sample-index"] ?? 0);
const replicate = Number(args.replicate ?? 0);
const sampleOffset = Number(args["sample-offset"] ?? 0);
const warmupWindowSize = Number(args["warmup-window-size"] ?? 1);
const stableWindowCount = Number(args["stable-window-count"] ?? 1);
const maxWarmupRelativeDrift = Number(args["max-warmup-relative-drift"] ?? 1);
const measuredDriftWindow = Number(args["measured-drift-window"] ?? 1);
const maxMeasuredRelativeDrift = Number(
  args["max-measured-relative-drift"] ?? 1,
);

function nowNs() {
  return process.hrtime.bigint();
}

function elapsedMs(start) {
  return Number(process.hrtime.bigint() - start) / 1_000_000;
}

function rssBytes() {
  const result = spawnSync("ps", ["-o", "rss=", "-p", String(process.pid)], {
    encoding: "utf8",
  });
  if (result.status !== 0) {
    throw new Error(`ps failed while measuring RSS: ${result.stderr}`);
  }
  const kib = Number(result.stdout.trim());
  if (!Number.isFinite(kib)) throw new Error(`Invalid RSS output: ${result.stdout}`);
  return kib * 1024;
}

function observe(phase, workload, metric, index, value, unit) {
  const observation = {
    "schema-version": 1,
    "run-id": args["run-id"],
    implementation,
    phase,
    workload,
    metric,
    replicate,
    "sample-index": index,
    value,
    unit,
  };
  appendFileSync(args.output, `${JSON.stringify(observation)}\n`);
}

function median(values) {
  const ordered = [...values].sort((left, right) => left - right);
  return ordered[Math.max(0, Math.ceil(ordered.length * 0.5) - 1)];
}

function relativeDrift(first, last) {
  return Math.abs(last - first) / first;
}

function stabilityRecord(
  workload,
  kind,
  windowIndex,
  operationCount,
  medianMs,
  referenceMedianMs,
  relativeDrift,
  stable,
) {
  return {
    "schema-version": 1,
    "run-id": args["run-id"],
    implementation,
    workload,
    replicate,
    kind,
    "window-index": windowIndex,
    "operation-count": operationCount,
    "median-ms": medianMs,
    "reference-median-ms": referenceMedianMs,
    "relative-drift": relativeDrift,
    stable,
  };
}

function createClient() {
  return new CopilotClient({
    connection: RuntimeConnection.forUri(args.uri, {
      connectionToken: corpus.connectionToken,
    }),
    logLevel: "error",
  });
}

function assertPing(result) {
  if (
    result.message !== corpus.pingMessage ||
    result.timestamp !== corpus.timestamp ||
    result.protocolVersion !== corpus.protocolVersion
  ) {
    throw new Error(`Invalid ping result: ${JSON.stringify(result)}`);
  }
}

function assertResponse(result) {
  if (
    result?.type !== "assistant.message" ||
    result?.data?.content !== corpus.response
  ) {
    throw new Error(`Invalid sendAndWait result: ${JSON.stringify(result)}`);
  }
}

async function stopClient(client) {
  const errors = await client.stop();
  if (errors.length !== 0) {
    throw new AggregateError(errors, "Node client cleanup failed");
  }
}

async function runCold() {
  const rssBefore = rssBytes();
  const start = nowNs();
  const client = createClient();
  try {
    await client.start();
    const result = await client.ping(corpus.pingMessage);
    const latency = elapsedMs(start);
    const rssDelta = rssBytes() - rssBefore;
    assertPing(result);
    observe("cold", "connect-ping", "latency", sampleIndex, latency, "ms");
    observe(
      "cold",
      "connect-ping",
      "rss-delta",
      sampleIndex,
      rssDelta,
      "bytes",
    );
  } finally {
    await stopClient(client);
  }
}

async function measuredLoop(workload, operation, validate) {
  if (
    warmup % warmupWindowSize !== 0 ||
    warmup / warmupWindowSize < 2 * stableWindowCount
  ) {
    throw new Error("Warmup must contain complete stability windows");
  }
  if (iterations < 2 * measuredDriftWindow) {
    throw new Error("Measurement must contain two drift windows");
  }
  await validatePreflight(operation, validate);
  const warmupMedians = [];
  const stabilityRecords = [];
  for (
    let windowIndex = 0;
    windowIndex < warmup / warmupWindowSize;
    windowIndex += 1
  ) {
    const windowSamples = await sampleOperations(
      warmupWindowSize,
      operation,
      elapsedMs,
    );
    const windowMedian = median(windowSamples);
    warmupMedians.push(windowMedian);
    const enoughWindows = warmupMedians.length >= 2 * stableWindowCount;
    const previous = warmupMedians.slice(
      -2 * stableWindowCount,
      -stableWindowCount,
    );
    const recent = warmupMedians.slice(-stableWindowCount);
    const drift = enoughWindows
      ? relativeDrift(median(previous), median(recent))
      : null;
    stabilityRecords.push(
      stabilityRecord(
        workload,
        "warmup-window",
        windowIndex,
        (windowIndex + 1) * warmupWindowSize,
        windowMedian,
        null,
        drift,
        drift !== null && drift <= maxWarmupRelativeDrift,
      ),
    );
  }
  const warmupStable = stabilityRecords.at(-1).stable;
  const rssBefore = rssBytes();
  const batchStart = nowNs();
  const samples = await sampleOperations(iterations, operation, elapsedMs);
  const batchDuration = elapsedMs(batchStart);
  const rssDelta = rssBytes() - rssBefore;
  const firstMedian = median(samples.slice(0, measuredDriftWindow));
  const lastMedian = median(samples.slice(-measuredDriftWindow));
  const measuredDrift = Math.abs(lastMedian - firstMedian) / firstMedian;
  const measuredStable = measuredDrift <= maxMeasuredRelativeDrift;
  await validatePreflight(operation, validate);
  samples.forEach((value, index) => {
    observe("steady", workload, "latency", sampleOffset + index, value, "ms");
  });
  observe(
    "steady",
    workload,
    "batch-duration",
    replicate,
    batchDuration,
    "ms",
  );
  observe("steady", workload, "rss-delta", replicate, rssDelta, "bytes");
  stabilityRecords.push(
    stabilityRecord(
      workload,
      "measurement-drift",
      0,
      iterations,
      firstMedian,
      lastMedian,
      measuredDrift,
      measuredStable,
    ),
  );
  appendFileSync(
    args["stability-output"],
    stabilityRecords.map((record) => JSON.stringify(record)).join("\n") + "\n",
  );
  if (!warmupStable) {
    throw new Error(`${workload} warmup failed the stability criterion`);
  }
  if (!measuredStable) {
    throw new Error(`${workload} measured latency drift exceeded the bound`);
  }
}

async function runSteady() {
  const client = createClient();
  try {
    await client.start();
    await measuredLoop(
      "ping",
      () => client.ping(corpus.pingMessage),
      assertPing,
    );
    const session = await client.createSession({
      sessionId: "bench-session",
      model: corpus.model,
    });
    await measuredLoop(
      "send-and-wait",
      () => session.sendAndWait({ prompt: corpus.prompt }, timeoutMs),
      assertResponse,
    );
  } finally {
    await stopClient(client);
  }
}

async function main() {
  if (args.mode === "cold") await runCold();
  else if (args.mode === "steady") await runSteady();
  else throw new Error(`Unsupported mode ${args.mode}`);

  console.log(JSON.stringify({ ok: true, implementation, mode: args.mode }));
}

main().catch((error) => {
  console.error(error.stack ?? String(error));
  process.exitCode = 1;
});

"use strict";

const os = require("node:os");
const { performance } = require("node:perf_hooks");
const v8 = require("node:v8");

const diagnosticKeys = [
  "schema-version",
  "run-id",
  "implementation",
  "workload",
  "replicate",
  "pair-order-index",
  "workload-order-index",
  "checkpoint",
  "window-index",
  "timed-operation-count",
  "validation-operation-count",
  "wall-time",
  "elapsed-ms",
  "process-cpu-ms",
  "rss-bytes",
  "heap-used-bytes",
  "heap-committed-bytes",
  "gc-count",
  "gc-time-ms",
  "jit-code-bytes",
  "jit-compilation-time-ms",
  "event-loop-idle-ms",
  "total-allocated-bytes",
  "host-load-average-1m",
  "available-processors",
];

function capture({
  runId,
  workload,
  replicate,
  pairOrderIndex,
  workloadOrderIndex,
  checkpoint,
  windowIndex,
  timedOperationCount,
  validationOperationCount,
  startedNs,
  rssBytes,
}) {
  const cpu = process.cpuUsage();
  const memory = process.memoryUsage();
  const heap = v8.getHeapStatistics();
  const code = v8.getHeapCodeStatistics();

  return {
    "schema-version": 1,
    "run-id": runId,
    implementation: "node",
    workload,
    replicate,
    "pair-order-index": pairOrderIndex,
    "workload-order-index": workloadOrderIndex,
    checkpoint,
    "window-index": windowIndex,
    "timed-operation-count": timedOperationCount,
    "validation-operation-count": validationOperationCount,
    "wall-time": new Date().toISOString(),
    "elapsed-ms": Number(process.hrtime.bigint() - startedNs) / 1_000_000,
    "process-cpu-ms": (cpu.user + cpu.system) / 1000,
    "rss-bytes": rssBytes,
    "heap-used-bytes": memory.heapUsed,
    "heap-committed-bytes": memory.heapTotal,
    "gc-count": null,
    "gc-time-ms": null,
    "jit-code-bytes": code.code_and_metadata_size,
    "jit-compilation-time-ms": null,
    "event-loop-idle-ms": performance.nodeTiming.idleTime,
    "total-allocated-bytes": heap.total_allocated_bytes,
    "host-load-average-1m": os.loadavg()[0],
    "available-processors": os.availableParallelism(),
  };
}

module.exports = { capture, diagnosticKeys };

import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";
import { createServer } from "node:net";

const args = Object.fromEntries(
  process.argv.slice(2).map((arg) => {
    const separator = arg.indexOf("=");
    if (separator === -1) throw new Error(`Expected --name=value, got ${arg}`);
    return [arg.slice(2, separator), arg.slice(separator + 1)];
  }),
);

for (const name of ["corpus", "state", "trace", "phase", "implementation"]) {
  if (!args[name]) throw new Error(`Missing --${name}`);
}

const corpusBytes = readFileSync(args.corpus);
const corpus = JSON.parse(corpusBytes);
const corpusSha256 = createHash("sha256").update(corpusBytes).digest("hex");
const counts = new Map();
const pendingRecords = [];
const connections = new Set();
let connectionNumber = 0;
let failed = false;

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((key) => [key, canonical(value[key])]),
    );
  }
  return value;
}

function normalizeSessionIds(value, key = null) {
  if (key === "sessionId") return "<session>";
  if (Array.isArray(value)) return value.map((item) => normalizeSessionIds(item));
  if (value && typeof value === "object") {
    return Object.fromEntries(
      Object.keys(value)
        .sort()
        .map((childKey) => [
          childKey,
          normalizeSessionIds(value[childKey], childKey),
        ]),
    );
  }
  return value;
}

function normalizedRequest(request) {
  if (!request || typeof request !== "object" || Array.isArray(request)) {
    return { invalidRequestValue: normalizeSessionIds(request) };
  }
  return {
    ...normalizeSessionIds(request),
    id: "<request>",
  };
}

function validateRequestEnvelope(request) {
  if (!request || typeof request !== "object" || Array.isArray(request)) {
    throw new Error("JSON-RPC request must be an object");
  }
  const keys = Object.keys(request).sort();
  const expectedKeys = ["id", "jsonrpc", "method", "params"];
  if (JSON.stringify(keys) !== JSON.stringify(expectedKeys)) {
    throw new Error(`Unexpected JSON-RPC request keys: ${JSON.stringify(keys)}`);
  }
  if (request.jsonrpc !== "2.0") {
    throw new Error(`Invalid JSON-RPC version: ${JSON.stringify(request.jsonrpc)}`);
  }
  const validId =
    (Number.isInteger(request.id) && request.id >= 0) ||
    (typeof request.id === "string" && request.id.length > 0);
  if (!validId) {
    throw new Error(`Invalid JSON-RPC request id: ${JSON.stringify(request.id)}`);
  }
  if (typeof request.method !== "string" || request.method.length === 0) {
    throw new Error("Invalid JSON-RPC method");
  }
  if (
    !request.params ||
    typeof request.params !== "object" ||
    Array.isArray(request.params)
  ) {
    throw new Error("Invalid JSON-RPC params");
  }
}

function recordTrace(connectionId, request, responseMessages, rawPayload) {
  pendingRecords.push({
    connection: connectionId,
    index: pendingRecords.length,
    request,
    responseMessages,
    rawPayload: Buffer.from(rawPayload),
  });
}

function frame(message) {
  const body = Buffer.from(JSON.stringify(message));
  return Buffer.concat([
    Buffer.from(`Content-Length: ${body.length}\r\n\r\n`),
    body,
  ]);
}

function event(id, type, data, ephemeral = false) {
  return {
    id: String(id),
    timestamp: corpus.timestamp,
    parentId: null,
    ephemeral,
    type,
    data,
  };
}

function handle(request) {
  const params = request.params ?? {};
  switch (request.method) {
    case "connect":
      if (params.token !== corpus.connectionToken) {
        throw new Error("connection token mismatch");
      }
      return {
        result: {
          ok: true,
          protocolVersion: corpus.protocolVersion,
          version: `matched-fixture-${corpus.fixtureVersion}`,
        },
        notifications: [],
      };
    case "ping":
      if (params.message !== corpus.pingMessage) {
        throw new Error(`ping payload mismatch: ${JSON.stringify(params)}`);
      }
      return {
        result: {
          message: corpus.pingMessage,
          timestamp: corpus.timestamp,
          protocolVersion: corpus.protocolVersion,
        },
        notifications: [],
      };
    case "session.create": {
      if (params.model !== corpus.model) {
        throw new Error(`session model mismatch: ${JSON.stringify(params.model)}`);
      }
      const sessionId = params.sessionId ?? "bench-session";
      return {
        result: { sessionId },
        notifications: [
          {
            jsonrpc: "2.0",
            method: "session.event",
            params: {
              sessionId,
              event: event(1, "session.start", {
                sessionId,
                version: 1,
                producer: "matched-benchmark-fixture",
                copilotVersion: `matched-fixture-${corpus.fixtureVersion}`,
                startTime: corpus.timestamp,
                selectedModel: corpus.model,
              }),
            },
          },
        ],
      };
    }
    case "session.send": {
      if (params.prompt !== corpus.prompt) {
        throw new Error(`send payload mismatch: ${JSON.stringify(params)}`);
      }
      const sessionId = params.sessionId;
      const sendNumber = counts.get("session.send");
      const messageId = `bench-message-${sendNumber}`;
      const turnId = `bench-turn-${sendNumber}`;
      return {
        result: { messageId },
        notifications: [
          {
            jsonrpc: "2.0",
            method: "session.event",
            params: {
              sessionId,
              event: event(`${sendNumber}-2`, "user.message", {
                content: corpus.prompt,
              }),
            },
          },
          {
            jsonrpc: "2.0",
            method: "session.event",
            params: {
              sessionId,
              event: event(`${sendNumber}-3`, "assistant.turn_start", { turnId }),
            },
          },
          {
            jsonrpc: "2.0",
            method: "session.event",
            params: {
              sessionId,
              event: event(`${sendNumber}-4`, "assistant.message", {
                messageId,
                content: corpus.response,
              }),
            },
          },
          {
            jsonrpc: "2.0",
            method: "session.event",
            params: {
              sessionId,
              event: event(`${sendNumber}-5`, "assistant.turn_end", { turnId }),
            },
          },
          {
            jsonrpc: "2.0",
            method: "session.event",
            params: {
              sessionId,
              event: event(`${sendNumber}-6`, "session.idle", {}, true),
            },
          },
        ],
      };
    }
    case "session.destroy":
      return { result: { success: true }, notifications: [] };
    default:
      throw new Error(`Unexpected method ${request.method}`);
  }
}

function processBuffer(socket, state) {
  while (true) {
    const headerEnd = state.buffer.indexOf("\r\n\r\n");
    if (headerEnd === -1) return;
    const header = state.buffer.subarray(0, headerEnd).toString("ascii");
    const match = /content-length:\s*(\d+)/i.exec(header);
    if (!match) throw new Error("Missing Content-Length");
    const length = Number(match[1]);
    const bodyStart = headerEnd + 4;
    if (state.buffer.length < bodyStart + length) return;
    const rawPayload = state.buffer.subarray(bodyStart, bodyStart + length);
    state.buffer = state.buffer.subarray(bodyStart + length);
    const request = JSON.parse(rawPayload);
    try {
      validateRequestEnvelope(request);
      counts.set(request.method, (counts.get(request.method) ?? 0) + 1);
      const { result, notifications } = handle(request);
      const response = { jsonrpc: "2.0", id: request.id, result };
      const messages = [...notifications, response];
      for (const message of messages) socket.write(frame(message));
      recordTrace(state.id, request, messages, rawPayload);
    } catch (error) {
      failed = true;
      const response = {
        jsonrpc: "2.0",
        id:
          request && typeof request === "object" && !Array.isArray(request)
            ? (request.id ?? null)
            : null,
        error: { code: -32602, message: error.message },
      };
      socket.write(frame(response));
      recordTrace(state.id, request, [response], rawPayload);
    }
  }
}

const server = createServer((socket) => {
  const state = { id: ++connectionNumber, buffer: Buffer.alloc(0) };
  connections.add(socket);
  socket.on("data", (chunk) => {
    try {
      state.buffer = Buffer.concat([state.buffer, chunk]);
      processBuffer(socket, state);
    } catch (error) {
      failed = true;
      socket.destroy(error);
    }
  });
  socket.on("close", () => connections.delete(socket));
});

function finish(signal) {
  for (const socket of connections) socket.destroy();
  server.close(() => {
    const traceRecords = pendingRecords.map((pending) => {
      const normalizedMessages = pending.responseMessages.map((message) => {
        const normalized = canonical(message);
        return Object.hasOwn(normalized, "id")
          ? { ...normalized, id: "<request>" }
          : normalized;
      });
      const comparable = {
        connection: pending.connection,
        index: pending.index,
        request: normalizedRequest(pending.request),
        responseMessages: normalizedMessages,
      };
      return {
        ...comparable,
        rawRequestSha256: sha256(pending.rawPayload),
        comparableSha256: sha256(JSON.stringify(canonical(comparable))),
      };
    });
    const comparable = traceRecords.map(
      ({ rawRequestSha256: _raw, comparableSha256: _hash, ...record }) =>
        canonical(record),
    );
    writeFileSync(
      args.trace,
      traceRecords
        .map((record) =>
          JSON.stringify({
            connection: record.connection,
            index: record.index,
            method:
              record.request &&
              typeof record.request === "object" &&
              !Array.isArray(record.request) &&
              typeof record.request.method === "string"
                ? record.request.method
                : "<invalid>",
            rawRequestSha256: record.rawRequestSha256,
            comparableSha256: record.comparableSha256,
          }),
        )
        .join("\n") + "\n",
    );
    writeFileSync(
      args.state,
      JSON.stringify(
        {
          schemaVersion: 1,
          fixtureVersion: corpus.fixtureVersion,
          corpusSha256,
          phase: args.phase,
          implementation: args.implementation,
          signal,
          failed,
          counts: Object.fromEntries([...counts].sort()),
          connectionCount: connectionNumber,
          comparableSequenceSha256: sha256(JSON.stringify(comparable)),
        },
        null,
        2,
      ),
    );
    process.exit(failed ? 1 : 0);
  });
}

server.listen(0, "127.0.0.1", () => {
  const address = server.address();
  console.log(
    JSON.stringify({
      ready: true,
      host: "127.0.0.1",
      port: address.port,
      fixtureVersion: corpus.fixtureVersion,
      corpusSha256,
    }),
  );
});

process.on("SIGTERM", () => finish("SIGTERM"));
process.on("SIGINT", () => finish("SIGINT"));

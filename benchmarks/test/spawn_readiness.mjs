import { spawn } from "node:child_process";

function exited(child) {
  return child.exitCode !== null || child.signalCode !== null || child.pid === undefined;
}

function waitForExit(child, timeoutMs) {
  if (exited(child)) return Promise.resolve();
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      child.off("exit", onExit);
      reject(new Error("Child failed to exit within cleanup timeout"));
    }, timeoutMs);
    function onExit() {
      clearTimeout(timeout);
      resolve();
    }
    child.once("exit", onExit);
  });
}

export async function terminateAndWait(child, timeoutMs = 1000) {
  if (exited(child)) return;
  child.kill("SIGTERM");
  try {
    await waitForExit(child, timeoutMs);
  } catch {
    child.kill("SIGKILL");
    await waitForExit(child, timeoutMs);
  }
}

export async function spawnWithReadiness(
  command,
  args,
  { timeoutMs = 2000, parse = JSON.parse } = {},
) {
  const child = spawn(command, args);
  let stdout = "";
  let timer;
  const cleanupListeners = () => {
    clearTimeout(timer);
    child.stdout?.off("data", onData);
    child.off("error", onError);
    child.off("exit", onExit);
  };
  let resolveReadiness;
  let rejectReadiness;
  const readinessPromise = new Promise((resolve, reject) => {
    resolveReadiness = resolve;
    rejectReadiness = reject;
  });
  function fail(error) {
    cleanupListeners();
    rejectReadiness(error);
  }
  function onData(chunk) {
    stdout += chunk;
    const newline = stdout.indexOf("\n");
    if (newline === -1) return;
    try {
      const readiness = parse(stdout.slice(0, newline));
      cleanupListeners();
      resolveReadiness(readiness);
    } catch (error) {
      fail(error);
    }
  }
  function onError(error) {
    fail(error);
  }
  function onExit(code, signal) {
    fail(
      new Error(
        `Child exited before readiness (code=${String(code)}, signal=${String(signal)})`,
      ),
    );
  }
  child.stdout?.on("data", onData);
  child.once("error", onError);
  child.once("exit", onExit);
  timer = setTimeout(
    () => fail(new Error("Timed out waiting for child readiness")),
    timeoutMs,
  );
  try {
    return { child, readiness: await readinessPromise };
  } catch (error) {
    await terminateAndWait(child);
    error.child = child;
    throw error;
  }
}

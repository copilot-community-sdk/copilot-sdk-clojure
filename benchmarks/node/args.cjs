"use strict";

const driverRequiredArgs = [
  "mode",
  "uri",
  "corpus",
  "output",
  "run-id",
  "node-sdk-root",
];

const steadyDriverRequiredArgs = [
  "stability-output",
  "diagnostics-output",
  "warmup",
  "iterations",
  "timeout-ms",
  "replicate",
  "pair-order-index",
  "sample-offset",
  "warmup-window-size",
  "stable-window-count",
  "warmup-relative-drift-reference",
  "measured-drift-window",
  "measured-relative-drift-reference",
];

const fixtureRequiredArgs = [
  "corpus",
  "state",
  "trace",
  "phase",
  "implementation",
];

function parseArgs(argv) {
  return Object.fromEntries(
    argv.map((arg) => {
      const separator = arg.indexOf("=");
      const name = separator > 2 ? arg.slice(2, separator) : "";
      const value = separator >= 0 ? arg.slice(separator + 1) : "";
      if (
        !arg.startsWith("--") ||
        !name.trim() ||
        !value.trim()
      ) {
        throw new Error(`Expected --name=value, got ${arg}`);
      }
      return [name, value];
    }),
  );
}

function requireArgs(args, names) {
  for (const name of names) {
    if (!args[name]) throw new Error(`Missing --${name}`);
  }
  return args;
}

function validateDriverArgs(argv) {
  const args = requireArgs(parseArgs(argv), driverRequiredArgs);
  if (args.mode === "steady") requireArgs(args, steadyDriverRequiredArgs);
  return args;
}

module.exports = {
  driverRequiredArgs,
  fixtureRequiredArgs,
  parseArgs,
  requireArgs,
  steadyDriverRequiredArgs,
  validateDriverArgs,
};

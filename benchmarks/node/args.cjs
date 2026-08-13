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
  "warmup",
  "iterations",
  "timeout-ms",
  "replicate",
  "sample-offset",
  "warmup-window-size",
  "stable-window-count",
  "max-warmup-relative-drift",
  "measured-drift-window",
  "max-measured-relative-drift",
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
      if (!arg.startsWith("--") || separator <= 2) {
        throw new Error(`Expected --name=value, got ${arg}`);
      }
      return [arg.slice(2, separator), arg.slice(separator + 1)];
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

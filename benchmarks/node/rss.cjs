"use strict";

const { spawnSync } = require("node:child_process");

function parseRssResult(result) {
  const text = String(result.stdout).trim();
  const kib = Number(text);
  if (
    result.status !== 0 ||
    !text ||
    !Number.isInteger(kib) ||
    kib < 0
  ) {
    const error = new Error("Invalid ps output while measuring RSS");
    error.details = {
      stdout: result.stdout,
      stderr: result.stderr,
      exit: result.status,
    };
    throw error;
  }
  return kib * 1024;
}

function rssBytes() {
  return parseRssResult(
    spawnSync("ps", ["-o", "rss=", "-p", String(process.pid)], {
      encoding: "utf8",
    }),
  );
}

module.exports = { parseRssResult, rssBytes };

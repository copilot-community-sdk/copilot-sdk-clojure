"use strict";

async function sampleOperations(count, operation, elapsed) {
  const samples = [];
  for (let index = 0; index < count; index += 1) {
    const start = process.hrtime.bigint();
    await operation();
    samples.push(elapsed(start));
  }
  return samples;
}

async function validatePreflight(operation, validate) {
  validate(await operation());
}

module.exports = { sampleOperations, validatePreflight };

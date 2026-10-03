import assert from "node:assert/strict";
import test from "node:test";
import {
  modelWhitelistText,
  parseModelWhitelist,
  resolveModelWhitelist,
} from "../lib/api-key-whitelist.ts";

const models = [
  { id: "model-1", publicName: "gpt-4.1", displayName: "GPT 4.1" },
  { id: "model-2", publicName: "claude-sonnet-4", displayName: "Claude Sonnet 4" },
];

test("parseModelWhitelist accepts English commas, Chinese commas and new lines", () => {
  assert.deepEqual(
    parseModelWhitelist(" gpt-4.1，claude-sonnet-4\nGPT-4.1,  "),
    ["gpt-4.1", "claude-sonnet-4"],
  );
  assert.deepEqual(parseModelWhitelist("  \n，,"), []);
});

test("resolveModelWhitelist maps public model names to ids and reports unknown names", () => {
  assert.deepEqual(resolveModelWhitelist("GPT-4.1, unknown-model", models), {
    names: ["GPT-4.1", "unknown-model"],
    modelIds: ["model-1"],
    canonicalNames: ["gpt-4.1"],
    unknownNames: ["unknown-model"],
  });
});

test("modelWhitelistText restores ids as public model names", () => {
  assert.deepEqual(modelWhitelistText(["model-2", "missing"], models), {
    value: "claude-sonnet-4",
    unknownIds: ["missing"],
  });
});

import assert from "node:assert/strict";
import { test } from "node:test";
import { follow, following, plannedStep, step, type AlertState } from "../src/policy.ts";
import type { Issue } from "../src/types.ts";

const MIN = 60_000;
const t0 = Date.parse("2026-11-09T07:30:00Z");
const issue = (signature: string, severity: Issue["severity"] = "delays"): Issue => ({ signature, severity, line: "Elizabeth line", title: "t", detail: "d" });

test("tells once, again only when it gets worse", () => {
  const first = step(undefined, [issue("line:a", "minor")], t0);
  assert.equal(first.push?.kind, "issues");
  const same = step(first.state, [issue("line:a", "minor")], t0 + 2 * MIN);
  assert.equal(same.push, undefined);
  const worse = step(same.state, [issue("line:a", "closed")], t0 + 4 * MIN);
  assert.equal(worse.push?.kind, "issues");
  const better = step(worse.state, [issue("line:a", "minor")], t0 + 6 * MIN);
  assert.equal(better.push, undefined, "getting better isn't worth a buzz");
});

test("a new disruption on top of a known one is told, with both", () => {
  const one = step(undefined, [issue("line:a")], t0);
  const two = step(one.state, [issue("line:a"), issue("stop:b", "closed")], t0 + 2 * MIN);
  assert.equal(two.push?.kind, "issues");
  assert.equal(two.push?.kind === "issues" && two.push.issues.length, 2);
});

test("good service again only after a few quiet minutes, and only if told recently", () => {
  let state: AlertState = step(undefined, [issue("line:a")], t0).state;
  let r = step(state, [], t0 + 2 * MIN);
  assert.equal(r.push, undefined, "one quiet check could be TfL flickering");
  r = step(r.state, [], t0 + 6 * MIN);
  assert.equal(r.push?.kind, "clear");
  assert.equal(r.state.open, undefined);

  state = step(undefined, [issue("line:a")], t0).state;
  r = step(step(state, [], t0 + 4 * 60 * MIN).state, [], t0 + 4 * 60 * MIN + 6 * MIN);
  assert.equal(r.push, undefined, "hours later, a clear is just noise");
});

test("trouble back after a clear is told again", () => {
  let r = step(undefined, [issue("line:a")], t0);
  r = step(r.state, [], t0 + 2 * MIN);
  r = step(r.state, [], t0 + 7 * MIN);
  assert.equal(r.push?.kind, "clear");
  r = step(r.state, [issue("line:a")], t0 + 9 * MIN);
  assert.equal(r.push?.kind, "issues");
});

test("next morning, a line's trouble is told afresh but weeks-long station works aren't", () => {
  const evening = step(undefined, [issue("line:a"), issue("stop:works")], t0 + 11 * 60 * MIN);
  const morning = t0 + 24 * 60 * MIN;
  const lineAgain = step(evening.state, [issue("line:a")], morning);
  assert.equal(lineAgain.push?.kind, "issues");
  const worksOnly = step(evening.state, [issue("stop:works")], morning);
  assert.equal(worksOnly.push, undefined);
});

test("planned works are told once", () => {
  const first = plannedStep({}, "alert", [issue("line:p")], t0);
  assert.equal(first.fresh.length, 1);
  const again = plannedStep(first.planned, "alert", [issue("line:p")], t0 + 24 * 60 * MIN);
  assert.equal(again.fresh.length, 0);
  const otherAlert = plannedStep(first.planned, "other", [issue("line:p")], t0);
  assert.equal(otherAlert.fresh.length, 1);
});

test("ways told: what was pushed, read from older memory as the alert's own way, and 'both' needs both", () => {
  const ways = (way: "out" | "back" | "both", on: Record<string, ("out" | "back")[]>, told: ("out" | "back")[] = ["out", "back"]) =>
    ({ way, on: new Map(Object.entries(on)), holds: true, told });
  // Older memory reads as the alert's own way: an out alert's entry is told out only.
  const old: AlertState = { live: { "line:a": { severity: "delays", seen: t0 } }, open: t0 };
  assert.equal(step(old, [issue("line:a")], t0 + 2 * MIN, ways("out", { "line:a": ["out", "back"] }, ["out"])).push, undefined, "no repeat at the deploy");
  assert.equal(step(old, [issue("line:a")], t0 + 2 * MIN, ways("back", { "line:a": ["out", "back"] }, ["out"])).push?.kind, "issues", "a back time added later is told");
  // Told one way; spreading without a push doesn't count as told.
  const told = step(undefined, [issue("line:a")], t0, ways("out", { "line:a": ["out"] }));
  assert.deepEqual(told.state.live["line:a"].ways, ["out"]);
  const spread = step(told.state, [issue("line:a")], t0 + 2 * MIN, ways("out", { "line:a": ["out", "back"] }));
  assert.equal(spread.push, undefined);
  assert.deepEqual(spread.state.live["line:a"].ways, ["out"]);
  assert.equal(step(spread.state, [issue("line:a")], t0 + 4 * MIN, ways("back", { "line:a": ["out", "back"] })).push?.kind, "issues");
  // Riding both ways (overlapping times): told out only isn't told.
  assert.equal(step(told.state, [issue("line:a")], t0 + 2 * MIN, ways("both", { "line:a": ["out", "back"] })).push?.kind, "issues");
  assert.equal(step(told.state, [issue("line:a")], t0 + 2 * MIN, ways("both", { "line:a": ["out"] })).push, undefined, "still out only: no repeat");
  // Both ways when pushed: told both.
  const both = step(undefined, [issue("line:b")], t0, ways("out", { "line:b": ["out", "back"] }));
  assert.equal(step(both.state, [issue("line:b")], t0 + 2 * MIN, ways("back", { "line:b": ["out", "back"] })).push, undefined);
});

test("what was told is kept with its words, and told ended once it has gone a few minutes", () => {
  let r = step(undefined, [issue("line:a"), { ...issue("line:b", "minor"), title: "B: minor delays" }], t0);
  assert.deepEqual(r.state.said, { "line:a": { title: "t", at: t0, severity: "delays" }, "line:b": { title: "B: minor delays", at: t0, severity: "minor" } });
  r = step(r.state, [issue("line:a")], t0 + 2 * MIN);
  assert.equal(r.push, undefined);
  assert.equal(r.state.said!["line:b"].gone, t0 + 2 * MIN);
  r = step(r.state, [issue("line:a")], t0 + 6 * MIN);
  assert.deepEqual(r.push, { kind: "clear", ended: ["B: minor delays"], still: ["t"], all: false });
  assert.ok(r.state.open, "the other is still open");
});

test("old state (no words kept): what is still there is adopted as told, without a push", () => {
  const old: AlertState = { live: { "line:a": { severity: "delays", seen: t0 } }, open: t0 };
  const r = step(old, [issue("line:a")], t0 + 2 * MIN);
  assert.equal(r.push, undefined);
  assert.deepEqual(r.state.said, { "line:a": { title: "t" } });
});

test("following: only after a told disruption, for a limited time, and new trouble isn't told", () => {
  const told = step(undefined, [issue("line:a")], t0).state;
  assert.equal(following(told, t0 + 2 * MIN), true);
  assert.equal(following({ live: {} }, t0), false);
  assert.equal(following({ live: told.live, open: t0 }, t0 + 2 * MIN), false, "state from before: not followed");
  let r = follow(told, [issue("line:a"), issue("line:b", "closed")], t0 + 2 * MIN);
  assert.equal(r.push, undefined);
  assert.equal(r.state.following, t0 + 2 * MIN);
  assert.ok(!("line:b" in r.state.live), "not remembered as told");
  assert.equal(following(r.state, t0 + 3 * 60 * MIN + 2 * MIN), true);
  assert.equal(following(r.state, t0 + 3 * 60 * MIN + 4 * MIN), false, "the limit");
  r = follow(r.state, [], t0 + 4 * MIN);
  r = follow(r.state, [], t0 + 8 * MIN);
  assert.deepEqual(r.push, { kind: "clear", ended: ["t"], still: [], all: true });
  assert.equal(following(r.state, t0 + 10 * MIN), false, "done once told");
});

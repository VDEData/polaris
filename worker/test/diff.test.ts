// The new Worker against today's server (a frozen copy of main at b24bdf5, test/baseline/): on
// thousands of made-up mornings, every disruption notice main sends, the new code sends too, in
// the same run or the next. What only the new code sends is printed, by reason, and may only be
// a disruption back after the new code told you it had ended, or something worse told the first time.

import assert from "node:assert/strict";
import { test } from "node:test";
import { runChecks as baselineChecks } from "./baseline/checks.ts";
import { forgetLineMaps as baselineForget } from "./baseline/linemap.ts";
import { runChecks } from "../src/checks.ts";
import { forgetLineMaps } from "../src/linemap.ts";
import { clock, ended, lineOf, morning, play, random, ridden, titles, type Morning, type Pushed } from "./mornings.ts";

const SEED = 4404;
const MORNINGS = Number(process.env.MORNINGS ?? 3000);

test(`${MORNINGS} made-up mornings: every disruption notice main sends, the new code sends too`, async () => {
  const rand = random(SEED);
  const silenced: string[] = [];
  const extra: Record<string, string[]> = {};
  const counts = { baseline: 0, matched: 0, edited: 0, standing: 0, earlier: 0, newOnly: 0, mornings: 0, late: 0, edits: 0 };
  for (let n = 0; n < MORNINGS; n++) {
    const m = morning(rand, rand() < 0.5 ? 0.2 : 0.05);
    counts.mornings++;
    if (m.late) counts.late++;
    if (m.edit) counts.edits++;
    const baseAll = await play(m, baselineChecks, baselineForget);
    const base = baseAll.filter((p) => p.subtitle);
    const next = await play(m, runChecks, forgetLineMaps);
    const told = next.filter((p) => p.subtitle);
    // Same run first, then the next run.
    const used = new Set<Pushed>();
    const pair = new Map<Pushed, Pushed>();
    for (const lag of [0, 1]) for (const b of base) {
      if (pair.has(b)) continue;
      const match = told.find((p) => !used.has(p) && p.run === b.run + lag && p.subtitle === b.subtitle);
      if (match) { used.add(match); pair.set(b, match); }
    }
    for (const b of base) {
      counts.baseline++;
      if (pair.has(b)) { counts.matched++; continue; }
      // The alert was edited before it, so that what main told isn't covered any more: main has no
      // notion of an edit and judges the new alert against memory of the old.
      if (m.edit && m.edit.at <= b.at) { counts.edited++; continue; }
      // Main told it again after its own "good service again" (sent silently, often while TfL
      // left the line out of its answer); the new code never said it had ended, so its notice
      // for it still stands.
      const all = (await Promise.resolve(baseAll)).filter((q) => q.at < b.at);
      const mainCleared = all.some((q) => !q.subtitle && q.at > (all.filter((x) => x.subtitle && titles(x).includes(b.subtitle!)).at(-1)?.at ?? -Infinity));
      const lastNew = next.filter((q) => q.at <= b.at && titles(q).includes(b.subtitle!)).at(-1);
      const stands = !!lastNew && !next.some((q) => q.at > lastNew.at && q.at <= b.at && ended(q).includes(b.subtitle!));
      if (mainCleared && stands) { counts.standing++; continue; }
      // The new code told it earlier (on every way it is on, as it was then) and hasn't said it
      // ended: main tells it now for its own reasons (a way told late, a memory it let go).
      if (stands) { counts.earlier++; continue; }
      silenced.push(`#${n} ${clock(b.at)} main told "${b.subtitle}" and the new code didn't (${describe(m)}; new code sent ${next.filter((p) => Math.abs(p.at - b.at) <= 10 * 60_000).map((p) => `${clock(p.at)} ${p.subtitle ?? `ended: ${p.body}`}`).join(" / ") || "nothing near"})`);
    }
    for (const p of told) {
      if (used.has(p)) continue;
      if (base.some((b) => Math.abs(b.run - p.run) <= 1 && b.subtitle === p.subtitle)) continue;
      counts.newOnly++;
      const reason = why(p, next, m);
      (extra[reason] ??= []).push(`#${n} ${clock(p.at)} "${p.subtitle}"${p.body.includes("Also:") ? ` (${titles(p).slice(1).join("; ")})` : ""} (${describe(m)})`);
    }
  }
  console.log(`diff: ${counts.mornings} mornings (seed ${SEED}; ${counts.late} late, ${counts.edits} with an edit): main sent ${counts.baseline} disruption notices; the new code sent ${counts.matched} of them in the same run or the next, ${counts.edited} weren't (alert edited before), ${counts.standing} weren't (main retold after its own silent clear; the new code's notice still stood), ${counts.earlier} weren't (the new code had told it earlier and not said it ended), ${silenced.length} silenced`);
  for (const [reason, list] of Object.entries(extra)) {
    console.log(`  new code only, ${reason}: ${list.length}`);
    for (const line of list.slice(0, 4)) console.log(`    ${line}`);
  }
  for (const line of silenced.slice(0, 20)) console.log(`  SILENCED ${line}`);
  assert.deepEqual(silenced, []);
  // Only these reasons: back after the new code said it had ended; worse, or on a way you ride,
  // told the first time; and once after an edit that settled what was told.
  const allowed = ["back after an ended notice", "worse, told the first time", "told the first time on the way you ride now", "after an edit settled what was told"];
  assert.deepEqual(Object.keys(extra).filter((r) => !allowed.includes(r)), []);
});

/** Why the new code told this when main didn't. */
function why(p: Pushed, pushes: Pushed[], m: Morning): string {
  const before = pushes.filter((q) => q.at < p.at);
  for (const title of titles(p)) {
    const lastTold = before.filter((q) => titles(q).includes(title)).at(-1);
    const lastEnded = before.filter((q) => ended(q).includes(title)).at(-1);
    if (lastEnded && (!lastTold || lastEnded.at > lastTold.at)) return "back after an ended notice";
    // Ended lately, though the same words were told since for another of TfL's statuses (two
    // severe delays on one line: one in a section, one for the line).
    if (lastEnded && p.at - lastEnded.at <= 32 * 60_000) return "back after an ended notice";
  }
  // Main has no notion of a partial end: after one of two disruptions on a line was told ended,
  // the other's words told are still main's open notice.
  for (const title of titles(p)) {
    const line = lineOf(title);
    const lastEndedOnLine = before.filter((q) => ended(q).some((t) => lineOf(t) === line)).at(-1);
    const toldSince = before.filter((q) => (!lastEndedOnLine || q.at > lastEndedOnLine.at) && titles(q).includes(title));
    if (!toldSince.length) return lastEndedOnLine ? "back after an ended notice" : "worse, told the first time";
  }
  // Told before only for the other way; you ride this way now (a time going the other way).
  for (const title of titles(p)) {
    const lastTold = before.filter((q) => titles(q).includes(title)).at(-1);
    if (lastTold && ridden(m, lastTold.at) !== ridden(m, p.at)) return "told the first time on the way you ride now";
  }
  if (m.edit && m.edit.at <= p.at) return "after an edit settled what was told";
  return "other";
}

const describe = (m: Morning) => `${m.lines.join("+")}, times ${m.alert.windows.map((w) => `${w.start}-${w.end}${w.direction ? ` ${w.direction}` : ""}`).join(", ")}, edit ${m.edit ? `${m.edit.kind} at ${clock(m.edit.at)}` : "none"}, ${m.late ? "late" : "on the mark"}`;

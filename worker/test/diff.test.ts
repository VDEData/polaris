// The new Worker against today's server (a frozen copy of main at b24bdf5, test/baseline/), on
// thousands of made-up mornings for each of three seeds. What is judged is what the phone shows:
// for each alert, its newest notice (the one its collapse ID leaves on screen), as the disruptions
// it presents as on now. Wherever main's newest notice shows a disruption that has been on for 6
// minutes or more, on a way the alert covers, at its severity or worse, the new code's shows it
// too, by the next run. Cases the other way (the new code shows it and main doesn't) are printed
// by reason. Disruption notices sent by one and not the other are counted, for information.

import assert from "node:assert/strict";
import { test } from "node:test";
import { runChecks as baselineChecks } from "./baseline/checks.ts";
import { forgetLineMaps as baselineForget } from "./baseline/linemap.ts";
import { runChecks } from "../src/checks.ts";
import { forgetLineMaps } from "../src/linemap.ts";
import { clock, covered, MIN, morning, play, random, ridden, shows, showsAtLeast, type Morning, type Pushed } from "./mornings.ts";

const MORNINGS = Number(process.env.MORNINGS ?? 3000);

for (const seed of [4404, 1, 7]) {
  test(`seed ${seed}, ${MORNINGS} made-up mornings: whatever main's phone shows, the new code's shows too`, async () => {
    const rand = random(seed);
    const missed: string[] = [];
    const more: Record<string, string[]> = {};
    const counts = { checks: 0, mainShows: 0, newShows: 0, editExempt: 0, late: 0, edits: 0, mainNotices: 0, newNotices: 0, mainOnly: 0, newOnly: 0 };
    for (let n = 0; n < MORNINGS; n++) {
      const m = morning(rand, rand() < 0.5 ? 0.2 : 0.05);
      if (m.late) counts.late++;
      if (m.edit) counts.edits++;
      const base = await play(m, baselineChecks, baselineForget);
      const next = await play(m, runChecks, forgetLineMaps);
      noticeCounts(base, next, counts);
      const editAt = m.edit && m.edit.kind !== "rename" ? m.edit.at : Infinity;
      const since = new Map<string, number>();
      for (const [k, t] of m.marks.entries()) {
        if (!ridden(m, t)) { since.clear(); continue; }
        const now = covered(m, t);
        for (const key of [...since.keys()]) if (!now.some((x) => x.title === key)) since.delete(key);
        for (const x of now) if (!since.has(x.title)) since.set(x.title, t);
        const after = m.marks[k + 1] ?? t;
        for (const x of now) {
          const from = since.get(x.title)!;
          if (t - from < 6 * MIN) continue;
          // From an edit to what the alert matches until the disruption next changes.
          if (editAt <= t && from < editAt) { counts.editExempt++; continue; }
          counts.checks++;
          const mainShows = showsAtLeast(shows(base, t), x);
          const newShows = showsAtLeast(shows(next, t), x) || showsAtLeast(shows(next, after), x);
          if (mainShows) counts.mainShows++;
          if (newShows) counts.newShows++;
          if (mainShows && !newShows) missed.push(`#${n} ${clock(t)} "${x.title}" on since ${clock(from)}: main shows ${JSON.stringify(shows(base, t))}, the new code ${JSON.stringify(shows(next, t))} (${describe(m)})`);
          if (newShows && !showsAtLeast(shows(base, t), x) && !showsAtLeast(shows(base, after), x)) (more[why(base, t)] ??= []).push(`#${n} ${clock(t)} "${x.title}" (${describe(m)})`);
        }
      }
    }
    console.log(`seed ${seed}: ${MORNINGS} mornings (${counts.late} late, ${counts.edits} with an edit); ${counts.checks} checks of a disruption on 6 minutes or more (${counts.editExempt} more not judged, after an edit): main's phone shows it in ${counts.mainShows}, the new code's in ${counts.newShows}; shown by main and not the new code: ${missed.length}`);
    console.log(`  disruption notices: main ${counts.mainNotices}, new ${counts.newNotices}; main's with no new one within a run: ${counts.mainOnly}; new with no main one within a run: ${counts.newOnly}`);
    for (const [reason, list] of Object.entries(more)) {
      console.log(`  shown by the new code and not main, ${reason}: ${list.length}`);
      for (const line of list.slice(0, 3)) console.log(`    ${line}`);
    }
    for (const line of missed.slice(0, 20)) console.log(`  MISSED ${line}`);
    assert.deepEqual(missed, []);
  });
}

/** Why main's phone doesn't show it. */
function why(base: Pushed[], t: number): string {
  const newest = base.filter((p) => p.at <= t).at(-1);
  if (!newest) return "main hasn't told anything yet";
  if (!newest.subtitle) return "main's newest notice is its silent \"good service again\"";
  return "main's newest notice is about something else, or milder";
}

function noticeCounts(base: Pushed[], next: Pushed[], counts: Record<string, number>) {
  const a = base.filter((p) => p.subtitle), b = next.filter((p) => p.subtitle);
  counts.mainNotices += a.length;
  counts.newNotices += b.length;
  counts.mainOnly += a.filter((p) => !b.some((q) => q.subtitle === p.subtitle && Math.abs(q.run - p.run) <= 1)).length;
  counts.newOnly += b.filter((p) => !a.some((q) => q.subtitle === p.subtitle && Math.abs(q.run - p.run) <= 1)).length;
}

const describe = (m: Morning) => `${m.lines.join("+")}, times ${m.alert.windows.map((w) => `${w.start}-${w.end}${w.direction ? ` ${w.direction}` : ""}`).join(", ")}${m.alert.direction ? `, way ${m.alert.direction}` : ""}, edit ${m.edit ? `${m.edit.kind} at ${clock(m.edit.at)}` : "none"}, ${m.late ? "late" : "on the mark"}`;

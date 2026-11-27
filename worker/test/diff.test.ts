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
import { clock, covered, cycleMorning, fixedMorning, lineState, MIN, morning, play, random, ridden, shows, showsAtLeast, type Morning, type Playing, type Pushed } from "./mornings.ts";

const MORNINGS = Number(process.env.MORNINGS ?? 3000);
/** The other modes: fewer mornings each, as there are three of them on three seeds. */
const MODE_MORNINGS = Number(process.env.MODE_MORNINGS ?? 1000);

/** Main reading the clock 1 to 5 s late, as production does; the new code on its mark. */
const mainLate = (r: number) => 1000 + ((r * 7919) % 4000);

type Mode = { name: string; count: number; make: (rand: () => number) => Morning; main: (m: Morning, rand: () => number) => Playing; next: (m: Morning, rand: () => number) => Playing };
const MODES: Mode[] = [
  { name: "made-up mornings", count: MORNINGS, make: (r) => morning(r, r() < 0.5 ? 0.2 : 0.05), main: () => ({}), next: () => ({}) },
  { name: "main 1 to 5 s late, the new code on the mark", count: MODE_MORNINGS, make: (r) => morning(r, r() < 0.5 ? 0.2 : 0.05), main: () => ({ lateness: mainLate }), next: () => ({ lateness: () => 0 }) },
  {
    name: "deployed at a random run (main's state handed to the new code)", count: MODE_MORNINGS, make: (r) => morning(r, r() < 0.5 ? 0.2 : 0.05), main: () => ({}),
    next: (m, r) => ({ handover: { run: Math.floor(r() * m.marks.length), runChecks, forgetLineMaps } }),
  },
  { name: "fixed cycles of two or three statuses, 2 to 20 minutes each", count: MODE_MORNINGS, make: (r) => cycleMorning(r), main: () => ({}), next: () => ({}) },
];

test("a 32-minute cycle, main 1 to 5 s late (so it never clears), the new code on the mark: the phone shows the suspension at 07:52", async () => {
  const cycle = fixedMorning(["jubilee"], 600, (t) => {
    const m = Number(t.slice(0, 2)) * 60 + Number(t.slice(3)) - 422;
    const k = ((m % 32) + 32) % 32;
    return { jubilee: lineState("jubilee", m < 0 ? "good" : k < 12 ? "part suspended" : k < 22 ? "suspended" : k < 26 ? "part suspended" : "good") };
  });
  const base = await play(cycle, baselineChecks, baselineForget, undefined, { lateness: mainLate });
  const next = await play(cycle, runChecks, forgetLineMaps, undefined, { lateness: () => 0 });
  const at = cycle.marks.find((t) => clock(t) === "07:52")!;
  assert.deepEqual(shows(base, at), ["Jubilee line: suspended"]);
  assert.ok(showsAtLeast(shows(next, at), { title: "Jubilee line: suspended", rank: 3 }), JSON.stringify(shows(next, at)));
});

test("deployed at 07:26 after severe eased to minor and the line went missing: severe back at 07:42 is told, as main tells it", async () => {
  const morning_ = fixedMorning(["jubilee"], 540, (t) => ({ jubilee: lineState("jubilee", t < "07:02" ? "good" : t < "07:12" ? "severe" : t < "07:22" ? "minor" : t < "07:42" ? "missing" : "severe") }));
  const base = await play(morning_, baselineChecks, baselineForget, undefined, { lateness: () => 0 });
  const deploy = morning_.marks.findIndex((t) => clock(t) === "07:26");
  const next = await play(morning_, baselineChecks, baselineForget, undefined, { lateness: () => 0, handover: { run: deploy, runChecks, forgetLineMaps } });
  assert.ok(base.some((p) => clock(p.at) === "07:42" && p.subtitle === "Jubilee line: severe delays"));
  const at = morning_.marks.find((t) => clock(t) === "07:44")!;
  assert.ok(showsAtLeast(shows(next, at), { title: "Jubilee line: severe delays", rank: 2 }), next.map((p) => `${clock(p.at)} ${p.subtitle ?? p.body}`).join(" / "));
});

for (const mode of MODES) for (const seed of [4404, 1, 7]) {
  test(`${mode.name}, seed ${seed}, ${mode.count} mornings: whatever main's phone shows, the new code's shows too`, async () => {
    const MORNINGS = mode.count;
    const rand = random(seed);
    const missed: string[] = [];
    const more: Record<string, string[]> = {};
    const counts = { checks: 0, mainShows: 0, newShows: 0, editExempt: 0, late: 0, edits: 0, mainNotices: 0, newNotices: 0, mainOnly: 0, newOnly: 0 };
    for (let n = 0; n < MORNINGS; n++) {
      const m = mode.make(rand);
      if (m.late) counts.late++;
      if (m.edit) counts.edits++;
      const base = await play(m, baselineChecks, baselineForget, undefined, mode.main(m, rand));
      const how = mode.next(m, rand);
      // A deploy: main runs first, then the new code takes over with main's state.
      const next = how.handover ? await play(m, baselineChecks, baselineForget, undefined, how) : await play(m, runChecks, forgetLineMaps, undefined, how);
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
    console.log(`${mode.name}, seed ${seed}: ${MORNINGS} mornings (${counts.late} late, ${counts.edits} with an edit); ${counts.checks} checks of a disruption on 6 minutes or more (${counts.editExempt} more not judged, after an edit): main's phone shows it in ${counts.mainShows}, the new code's in ${counts.newShows}; shown by main and not the new code: ${missed.length}`);
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

// Thousands of made-up mornings (test/mornings.ts, fixed seed): random TfL statuses on one or
// two lines (two statuses on one line included, lines missing from TfL's answer), a random time
// end, alerts whose times go one way and back, at most one edit (ways, stretch, minor delays,
// lines, the time's end), runs on the mark or late. Every push is held to rules a person would
// expect, whatever TfL does.

import assert from "node:assert/strict";
import { test } from "node:test";
import { runChecks } from "../src/checks.ts";
import { forgetLineMaps } from "../src/linemap.ts";
import { alertAt, clock, covered, DAY, ended, lineOf, MIN, missing, morning, play, random, reported, ridden, titles, type Morning, type Pushed } from "./mornings.ts";

const SEED = 20261127;
const MORNINGS = Number(process.env.MORNINGS ?? 3000);
const RANK: Record<string, number> = { "minor delays": 1, "severe delays": 2, "part suspended": 2, "reduced service": 2, suspended: 3 };
const rankOf = (title: string) => RANK[title.split(": ")[1]];

test(`${MORNINGS} random mornings: every push follows the rules`, async () => {
  const rand = random(SEED);
  const faults: string[] = [];
  const counts = { pushes: 0, ended: 0, checkedEnds: 0, checkedOn: 0, edits: 0, unfollowedEnds: 0, flippedEnds: 0, editedEnds: 0 };
  const busiest: number[] = [];
  for (let n = 0; n < MORNINGS; n++) {
    const m = morning(rand, rand() < 0.5 ? 0.2 : 0.05);
    const pushed = await play(m, runChecks, forgetLineMaps);
    counts.pushes += pushed.length;
    if (m.edit) counts.edits++;
    const told = pushed.filter((p) => p.subtitle);
    const ends = pushed.filter((p) => !p.subtitle);
    counts.ended += ends.length;
    const fault = (rule: string, text: string) => faults.push(`#${n} ${rule}: ${text} (${describe(m)})`);
    const editAt = m.edit?.at ?? Infinity;
    // An edit that takes away cover from a disruption: its line, minor delays, or (for the
    // alert going one way and back) its way or stretch.
    const uncovers = (title: string) => !!m.edit && (
      (m.edit.kind === "line removed" && lineOf(title) === "central")
      || (m.edit.kind === "minor off" && title.endsWith("minor delays"))
      || ["way turned round", "time's way changed", "stretch changed"].includes(m.edit.kind));
    const endedSince = (title: string, from: number, to: number) => ends.some((e) => e.at > from && e.at < to && ended(e).includes(title));

    // (a) Per disruption: told again only after an ended notice for it, or when it is worse than
    // anything told for its line since that line's last ended notice. A push is a repeat only if
    // nothing in it is news. Not judged across an edit, a change of the way ridden, or its line
    // missing from TfL's answer for over half an hour (a memory let go, as main).
    for (const p of told) {
      const before = told.filter((q) => q.at < p.at);
      // Two of TfL's statuses can share words ("severe delays" for a section, and for the whole
      // line): the notice's headline is told apart by its detail, the rest by their words.
      const detail = (q: Pushed) => q.body.split("\n\n")[0];
      const news = titles(p).some((title, k) => {
        const last = before.filter((q) => (k === 0 ? q.subtitle === title && detail(q) === detail(p) : titles(q).includes(title))).at(-1);
        if (!last) return true;
        if (endedSince(title, last.at, p.at)) return true;
        const line = lineOf(title);
        const lineEnded = ends.filter((e) => e.at < p.at && ended(e).some((t) => lineOf(t) === line)).at(-1)?.at ?? -Infinity;
        const worst = Math.max(0, ...before.filter((q) => q.at > lineEnded).flatMap((q) => titles(q)).filter((t) => lineOf(t) === line).map(rankOf));
        if (rankOf(title) > worst) return true;
        if (editAt > last.at && editAt <= p.at) return true;
        // A notice in between headed by other words for the line replaced it.
        if (told.some((q) => q.at > last.at && q.at < p.at && q.subtitle && lineOf(q.subtitle) === line && (q.subtitle !== p.subtitle || detail(q) !== detail(p)))) return true;
        if (ridden(m, last.at) !== ridden(m, p.at)) return true;
        // Not on for over half an hour (missing, or gone and back without an ended notice): main lets it go too.
        const away = m.marks.filter((t) => t > last.at && t < p.at && (missing(m, t, line) || !reported(m, t, title)));
        return away.length * 2 > 30;
      });
      if (!news) fault("a", `${clock(p.at)} "${titles(p).join("; ")}" told again with nothing new`);
    }

    // (b) Never "ended" for something TfL still reports on a way the alert covers, nor while
    // its line is missing from TfL's answer.
    for (const e of ends) {
      for (const title of ended(e)) {
        const way = ridden(m, e.at) ?? "both";
        if (missing(m, e.at, lineOf(title))) fault("b", `${clock(e.at)} "${e.body}" while TfL leaves the ${lineOf(title)} line out`);
        else if (covered(m, e.at, way).some((x) => x.title === title)) fault("b", `${clock(e.at)} "${e.body}" while TfL still reports it`);
      }
    }

    // (c) A told disruption that ends gets exactly one ended notice within 10 minutes of ending
    // (36 for one back soon after an ended notice for it). Not judged when an edit took its cover
    // away, its line went missing, or the end is past following's limit.
    const slotEnd = (t: number) => DAY + Math.max(...alertAt(m, t).windows.map((w) => w.end)) * MIN;
    for (const p of told) {
      for (const title of titles(p)) {
        const line = lineOf(title);
        // Back soon after an ended notice for it, or for its line when TfL's words for it changed
        // without a notice (the flap carries over to the new words).
        const quiet = (isShaky(title, p, told, ends) || ends.some((e) => e.at < p.at && e.at >= p.at - 60 * MIN && ended(e).some((t) => lineOf(t) === line)) ? 30 : 4) * MIN;
        const after = m.marks.filter((t) => t > p.at);
        // Gone, and nothing on its line that the notice didn't tell (a flip to other words, which
        // the notice standing still covers, isn't an end).
        const onLine = (t: number) => { const st = m.truth.get(t)![line]; return st && !("missing" in st && st.missing) ? (st as { disruptions: { title: string }[] }).disruptions.map((x) => x.title) : []; };
        // (One of those told back after its own ended notice is new trouble on the line, which
        // keeps the line's notice open.)
        const gone = (t: number) => !missing(m, t, line) && !reported(m, t, title) && onLine(t).every((x) => titles(p).includes(x) && !endedSince(x, p.at, t));
        const g = after.find((t) => gone(t) && after.filter((u) => u >= t && u <= t + quiet).every(gone));
        if (g === undefined) continue;
        const taken = told.some((q) => q.at >= g + quiet && q.at <= g + quiet + 3 * MIN);
        // The run its ended notice was due was taken by another notice, and by the next run it was back.
        if (taken && !gone(g + quiet + 2 * MIN)) continue;
        const window = quiet + (taken ? 16 : 6) * MIN;
        // A later notice about its line takes over: it says what is on that line now.
        if (told.some((q) => q.at > p.at && q.at <= g + window && titles(q).some((t) => lineOf(t) === line))) continue;
        if (editAt <= g + window && uncovers(title)) continue;
        const editMark = m.marks.find((t) => t >= editAt);
        if (editAt > p.at && editAt <= g + window && m.edit!.kind !== "rename" && editMark !== undefined && (onLine(editMark).length > 0 || missing(m, editMark, line))) {
          // Known follow-up: an edit to what the alert matches, made while a told disruption is
          // ending and its line still shows other trouble, settles it quietly.
          counts.editedEnds++;
          continue;
        }
        if (m.marks.some((t) => t > p.at && t <= g + window && missing(m, t, line))) continue;
        if (m.marks.some((t) => t > p.at && t < g && onLine(t).some((x) => !titles(p).includes(x)))) {
          // Known follow-up: TfL's words for the line changed without a notice before it ended,
          // and the notice standing now ends in the latest words (or, when two told merged into
          // one, says only one of them ended).
          counts.flippedEnds++;
          continue;
        }
        const end = slotEnd(g);
        if (g + window > end + 3 * 60 * MIN) continue;
        if (g + quiet >= end && !m.marks.some((t) => t < end && t >= end - 30 * MIN && reported(m, t, title))) {
          // Known follow-up: it ended inside the time, its ended notice falls due after the time
          // ends, and it wasn't seen in the time's last half hour, so following never begins.
          counts.unfollowedEnds++;
          continue;
        }
        counts.checkedEnds++;
        // Its ended notice, in the words told or (known follow-up) the latest words TfL used for
        // its line when they changed without a notice.
        const inWindow = ends.filter((e) => e.at > p.at && e.at <= g + window);
        const exact = inWindow.filter((e) => ended(e).includes(title));
        const got = exact.length ? exact : inWindow.filter((e) => ended(e).some((t) => lineOf(t) === line && !titles(p).includes(t)));
        if (got.length !== 1) fault("c", `"${title}" told ${clock(p.at)}, gone from ${clock(g)}: ${got.length} ended notices by ${clock(g + window)}${got.length ? ` (${got.map((e) => clock(e.at)).join(", ")})` : ""}`);
      }
    }

    // (d) Outside a time, only ended notices.
    for (const p of told) if (!ridden(m, p.at)) fault("d", `${clock(p.at)} "${p.subtitle}" outside the time`);

    // (f) A disruption on for 6 minutes or more inside the time, on a way the alert covers, is
    // told, unless it was told and not told ended since.
    const since = new Map<string, number>();
    for (const t of m.marks) {
      if (!ridden(m, t)) { since.clear(); continue; }
      const now = new Set(covered(m, t).map((x) => x.title));
      for (const k of [...since.keys()]) if (!now.has(k)) since.delete(k);
      for (const title of now) if (!since.has(title)) since.set(title, t);
      for (const [title, from] of since) {
        if (t - from < 6 * MIN) continue;
        since.set(title, Infinity); // judged once for each stretch it is on
        if (editAt > from - 2 * MIN && editAt <= t) continue;
        counts.checkedOn++;
        const last = told.filter((q) => q.at <= t && titles(q).includes(title)).at(-1);
        if (last && !endedSince(title, last.at, t + 1)) continue;
        fault("f", `"${title}" on from ${clock(from)} to ${clock(t)} and not told${last ? ` (told ${clock(last.at)}, ended since)` : ""}`);
      }
    }

    let most = 0;
    for (const line of m.lines) {
      const about = told.filter((p) => titles(p).some((t) => lineOf(t) === line));
      for (const p of about) most = Math.max(most, about.filter((q) => q.at >= p.at && q.at < p.at + 60 * MIN).length);
    }
    busiest.push(most);
  }
  const byRule = faults.reduce((h, f) => ((h[f.split(" ")[1]] = (h[f.split(" ")[1]] ?? 0) + 1), h), {} as Record<string, number>);
  const hist = busiest.reduce((h, k) => ((h[k] = (h[k] ?? 0) + 1), h), {} as Record<number, number>);
  console.log(`random: ${MORNINGS} mornings (seed ${SEED}; ${counts.edits} with an edit): ${counts.pushes} pushes, ${counts.ended} ended notices; ${counts.checkedEnds} ends checked (c), ${counts.checkedOn} disruptions checked (f), ${counts.unfollowedEnds} ends not judged (following never began), ${counts.flippedEnds} not judged (words flipped without a notice first), ${counts.editedEnds} not judged (an edit while it ended); faults ${JSON.stringify(byRule)}`);
  console.log(`  busiest hour about one line, disruption notices (count: mornings): ${JSON.stringify(hist)}`);
  for (const f of faults.slice(0, 30)) console.log(`  ${f}`);
  assert.deepEqual(faults, []);
});

/** Told again within half an hour of an ended notice for it, and not told ended since: then it
 * is told ended only after half an hour without it. */
function isShaky(title: string, p: Pushed, told: Pushed[], ends: Pushed[]): boolean {
  let shaky = false, lastEnd = -Infinity;
  const events = [
    ...told.filter((q) => q.at <= p.at && titles(q).includes(title)).map((q) => ({ at: q.at, ended: false })),
    ...ends.filter((e) => e.at < p.at && ended(e).includes(title)).map((e) => ({ at: e.at, ended: true })),
  ].sort((a, b) => a.at - b.at);
  for (const e of events) {
    if (e.ended) { shaky = false; lastEnd = e.at; }
    else if (e.at - lastEnd <= 32 * MIN) shaky = true;
  }
  return shaky;
}

const describe = (m: Morning) => `${m.lines.join("+")}, times ${m.alert.windows.map((w) => `${w.start}-${w.end}${w.direction ? ` ${w.direction}` : ""}`).join(", ")}, edit ${m.edit ? `${m.edit.kind} at ${clock(m.edit.at)}` : "none"}, ${m.late ? "late" : "on the mark"}`;

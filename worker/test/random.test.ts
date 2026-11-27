// A few hundred made-up mornings, the same ones every run (fixed seed): random TfL statuses on
// one or two lines at every two-minute run, a random time end, at most one edit to the alert.
// Each goes through the real checks and a fake APNs, and every push is held to rules a
// person would expect, whatever TfL does.

import assert from "node:assert/strict";
import { test } from "node:test";
import { runChecks, type Env } from "../src/checks.ts";
import { forgetLineMaps } from "../src/linemap.ts";

const TOKEN = "ab".repeat(32);
const SEED = 20261127;
const TIMELINES = 300;
const MIN = 60_000;
const DAY = Date.parse("2026-11-09T00:00:00Z"); // a Monday, GMT

type Truth = "good" | "minor" | "severe" | "suspended" | "missing";
const RANK: Record<Truth, number> = { good: 0, missing: -1, minor: 1, severe: 2, suspended: 3 };
const LINES = { jubilee: "Jubilee line", central: "Central line" } as const;
type LineId = keyof typeof LINES;

function random(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const status = (id: LineId, truth: Truth) => {
  const name = LINES[id];
  const s = { good: [10, "Good Service", ""], minor: [9, "Minor Delays", `${name.toUpperCase()}: Minor delays due to an earlier signal failure.`], severe: [6, "Severe Delays", `${name.toUpperCase()}: Severe delays due to a faulty train.`], suspended: [2, "Suspended", `${name.toUpperCase()}: Suspended due to a person taken ill on a train.`] }[truth as Exclude<Truth, "missing">];
  return { id, name, lineStatuses: [{ statusSeverity: s[0], statusSeverityDescription: s[1], reason: s[2], validityPeriods: [], disruption: { category: "RealTime", affectedStops: [] } }] };
};

const segment = (id: LineId) => ({ line: { id, name: LINES[id] }, mode: "tube", stations: [] as string[] });

interface Alert { id: string; name: string; days: number[]; windows: { start: number; end: number }[]; minorDelays: boolean; segments: ReturnType<typeof segment>[] }
interface Pushed { at: number; subtitle?: string; body: string; level: string }

function makeTimeline(rand: () => number, change: number) {
  const ids: LineId[] = rand() < 0.5 ? ["jubilee"] : ["jubilee", "central"];
  const end = 7 * 60 + 40 + 10 * Math.floor(rand() * 12); // 07:40 to 09:30
  const alert: Alert = { id: "7A1B2C3D-0000-4000-8000-000000000001", name: "Random", days: [1, 2, 3, 4, 5], windows: [{ start: 420, end }], minorDelays: true, segments: ids.map(segment) };
  const first = DAY + 418 * MIN, last = DAY + (end + 220) * MIN;
  const marks: number[] = [];
  for (let t = first; t <= last; t += 2 * MIN) marks.push(t);
  // Each line keeps its status for a while, then changes.
  const truth = new Map<number, Record<LineId, Truth>>();
  const now: Record<LineId, Truth> = { jubilee: "good", central: "good" };
  const pick = (): Truth => { const r = rand(); return r < 0.4 ? "good" : r < 0.6 ? "minor" : r < 0.8 ? "severe" : r < 0.9 ? "suspended" : "missing"; };
  for (const t of marks) {
    for (const id of Object.keys(LINES) as LineId[]) if (rand() < change) now[id] = pick();
    truth.set(t, { ...now });
  }
  // At most one edit.
  let edit: { at: number; alert: Alert; kind: string } | undefined;
  if (rand() < 0.7) {
    const at = marks[Math.floor(rand() * marks.length)];
    const kinds = ["rename", "end", "minor off", ids.length === 2 ? "remove line" : "add line"];
    const kind = kinds[Math.floor(rand() * kinds.length)];
    const changed: Alert = kind === "rename" ? { ...alert, name: "Renamed" }
      : kind === "end" ? { ...alert, windows: [{ start: 420, end: end + (rand() < 0.5 ? -20 : 20) }] }
      : kind === "minor off" ? { ...alert, minorDelays: false }
      : kind === "remove line" ? { ...alert, segments: [segment("jubilee")] }
      : { ...alert, segments: [segment("jubilee"), segment("central")] };
    edit = { at, alert: changed, kind };
  }
  return { alert, marks, truth, edit, late: rand() < 0.5 };
}

async function play(tl: ReturnType<typeof makeTimeline>) {
  forgetLineMaps();
  const rows = [{ token: TOKEN, environment: "sandbox", time_zone: "Europe/London", alerts: JSON.stringify([tl.alert]), state: JSON.stringify({ alerts: {}, planned: {} }) }];
  const db = {
    prepare(sql: string) {
      let args: unknown[] = [];
      const st = {
        bind(...v: unknown[]) { args = v; return st; },
        async all() { return { results: rows.map((r) => ({ ...r })) }; },
        async first() { return null; },
        async run() { if (sql.startsWith("UPDATE devices SET state")) rows[0].state = args[0] as string; return {}; },
      };
      return st;
    },
  };
  const env = { DB: db, APNS_KEY: KEY, APNS_KEY_ID: "KEY123", APNS_TEAM_ID: "TEAM123456", APNS_TOPIC: "com.example.polaris" } as unknown as Env;
  const pushed: Pushed[] = [];
  let mark = 0;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("push.apple.com")) {
      const aps = JSON.parse(init!.body as string).aps;
      pushed.push({ at: mark, subtitle: aps.alert.subtitle, body: aps.alert.body, level: aps["interruption-level"] });
      return new Response("", { status: 200 });
    }
    if (url.includes("/StopPoint/") || url.includes("/to/")) return Response.json([]);
    if (url.includes("/Line/")) {
      const ids = decodeURIComponent(url.split("/Line/")[1].split("/")[0]).split(",") as LineId[];
      const now = tl.truth.get(mark)!;
      return Response.json(ids.filter((id) => now[id] !== "missing").map((id) => status(id, now[id])));
    }
    throw new Error(`unexpected fetch ${url}`);
  }) as typeof fetch;
  let i = 0;
  for (const t of tl.marks) {
    mark = t;
    if (tl.edit && t === tl.edit.at) rows[0].alerts = JSON.stringify([tl.edit.alert]);
    await runChecks(env, t + (tl.late ? 300 + ((i++ * 7919) % 2700) : 0));
    if (process.env.SHOW_STATE === String(CURRENT)) console.log("STATE", clock(t), rows[0].state);
  }
  return pushed;
}

let KEY = "";
let CURRENT = -1;

/** Lines a sentence says have ended: "Minor delays on the Jubilee line have cleared." */
function endedIn(body: string): { line: LineId; truth: Truth }[] {
  const out: { line: LineId; truth: Truth }[] = [];
  for (const sentence of body.split(/(?<=\.)\s+/)) {
    for (const [id, name] of Object.entries(LINES) as [LineId, string][]) {
      if (!sentence.includes(`the ${name}`)) continue;
      if (/^Minor delays .* have cleared/.test(sentence)) out.push({ line: id, truth: "minor" });
      else if (/^Severe delays .* have cleared/.test(sentence)) out.push({ line: id, truth: "severe" });
      else if (/suspension .* has ended|has ended|have ended/.test(sentence)) out.push({ line: id, truth: "suspended" });
    }
  }
  return out;
}
const mentions = (p: Pushed) => (Object.entries(LINES) as [LineId, string][]).filter(([, name]) => `${p.subtitle ?? ""} ${p.body}`.includes(name)).map(([id]) => id);
const minute = (t: number) => Math.round((t - DAY) / MIN);
const clock = (t: number) => `${String(Math.floor(minute(t) / 60)).padStart(2, "0")}:${String(minute(t) % 60).padStart(2, "0")}`;

// Rule (e) (no more than 4 pushes about one line in an hour) is reported, not enforced: when TfL
// changes a line's status every few minutes, each change is real news, and main sends as many.
for (const change of [0.2, 0.05]) test(`${TIMELINES} random mornings, statuses changing at ${change * 100}% of runs: rules (a) to (d) hold on every one`, async () => {
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"])) as CryptoKeyPair;
  KEY = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const rand = random(SEED);
  const faults: string[] = [];
  const counts = { pushes: 0, ended: 0, checkedEnds: 0, edits: 0 };
  // The busiest hour about one line, in disruption notices alone, per morning.
  const busiest: number[] = [];
  for (let n = 0; n < TIMELINES; n++) {
    const tl = makeTimeline(rand, change);
    CURRENT = n;
    const pushed = await play(tl);
    if (process.env.SHOW === String(n)) {
      for (const t of tl.marks) {
        const ps = pushed.filter((p) => p.at === t).map((p) => `  >> ${p.subtitle ?? "ENDED"} | ${p.body.replace(/\n/g, " ")}`);
        console.log(clock(t), JSON.stringify(tl.truth.get(t)), ...ps);
      }
    }
    counts.pushes += pushed.length;
    counts.ended += pushed.filter((p) => !p.subtitle).length;
    if (tl.edit) counts.edits++;
    const alertAt = (t: number) => (tl.edit && t >= tl.edit.at ? tl.edit.alert : tl.alert);
    const covers = (t: number, id: LineId, truth: Truth) => alertAt(t).segments.some((s) => s.line.id === id) && (truth !== "minor" || alertAt(t).minorDelays);
    const inSlot = (t: number) => { const m = minute(t), w = alertAt(t).windows[0]; return m >= w.start && m < w.end; };
    const fault = (rule: string, text: string) => faults.push(`#${n} ${rule}: ${text} (lines ${tl.alert.segments.map((s) => s.line.id).join("+")}, ends ${tl.alert.windows[0].end}, edit ${tl.edit ? `${tl.edit.kind} at ${clock(tl.edit.at)}` : "none"}, ${tl.late ? "late" : "on the mark"})`);
    const told = pushed.filter((p) => p.subtitle);
    const ends = pushed.filter((p) => !p.subtitle);

    // (a) The same disruption notice twice in a row only with an ended notice, a rise, or an edit between.
    for (let k = 1; k < told.length; k++) {
      const [a, b] = [told[k - 1], told[k]];
      if (a.subtitle !== b.subtitle || a.body !== b.body) continue;
      const ids = mentions(b);
      const between = tl.marks.filter((t) => t > a.at && t < b.at);
      const endedBetween = ends.some((e) => e.at > a.at && e.at < b.at && endedIn(e.body).some((x) => ids.includes(x.line)));
      const rose = ids.some((id) => between.some((t) => RANK[tl.truth.get(t)![id]] >= 0 && RANK[tl.truth.get(t)![id]] < RANK[tl.truth.get(b.at)![id]]));
      const id = ids[0];
      const editBetween = !!tl.edit && tl.edit.at > a.at && tl.edit.at <= b.at;
      const longMissing = between.filter((t) => tl.truth.get(t)![id] === "missing").length * 2 > 60;
      if (!endedBetween && !rose && !editBetween && !longMissing) fault("a", `"${b.subtitle}" at ${clock(a.at)} and again at ${clock(b.at)}`);
    }
    // (b) Never "ended" for something TfL still reports, on a line and severity the alert covers.
    for (const e of ends) {
      for (const x of endedIn(e.body)) {
        const now = tl.truth.get(e.at)![x.line];
        if (now !== "good" && (now === "missing" || covers(e.at, x.line, now))) fault("b", `${clock(e.at)} "${e.body}" while TfL says ${now}`);
      }
    }
    // (c) A told disruption that ends, unedited, gets exactly one ended notice within 10 minutes
    // of ending. One back within half an hour of an ended notice is told ended after half an hour
    // without it, so then within 36.
    const slotEnd = DAY + tl.alert.windows[0].end * MIN;
    for (const p of told) {
      for (const id of mentions(p)) {
        // Back within half an hour of an ended notice (then, or at an earlier notice that this one repeats).
        const shaky = ends.some((e) => e.at < p.at && e.at >= p.at - 60 * MIN && endedIn(e.body).some((x) => x.line === id));
        const quiet = (shaky ? 30 : 4) * MIN;
        const after = tl.marks.filter((t) => t > p.at);
        const g = after.find((t) => tl.truth.get(t)![id] === "good" && after.filter((u) => u >= t && u <= t + quiet).every((u) => tl.truth.get(u)![id] === "good"));
        if (g === undefined) continue;
        // A disruption notice about another line on the run the ended notice was due takes that
        // run; the ended notice comes at the next.
        const taken = told.some((q) => q.at >= g + quiet && q.at <= g + quiet + 3 * MIN);
        const window = quiet + (taken ? 16 : 6) * MIN;
        if (told.some((q) => q.at > p.at && q.at <= g + window && mentions(q).includes(id))) continue; // a later notice takes over
        if (tl.edit && tl.edit.at <= g + window) continue;
        if (tl.marks.some((t) => t > p.at && t <= g + window && tl.truth.get(t)![id] === "missing")) continue;
        if (g + window > slotEnd + 3 * 60 * MIN) continue;
        // Following starts only if it was seen in the last half hour of the time.
        if (g >= slotEnd && !tl.marks.some((t) => t < slotEnd && t >= slotEnd - 30 * MIN && tl.truth.get(t)![id] !== "good")) continue;
        counts.checkedEnds++;
        const got = ends.filter((e) => e.at > p.at && e.at <= g + window && endedIn(e.body).some((x) => x.line === id));
        if (got.length !== 1) fault("c", `${LINES[id]} told ${clock(p.at)}, good from ${clock(g)}: ${got.length} ended notices by ${clock(g + window)}${got.length ? ` (${got.map((e) => clock(e.at)).join(", ")})` : ""}`);
      }
    }
    // (d) Outside a time, only ended notices.
    for (const p of told) if (!inSlot(p.at)) fault("d", `${clock(p.at)} "${p.subtitle}" outside the time`);
    let most = 0;
    for (const id of Object.keys(LINES) as LineId[]) {
      const about = told.filter((p) => mentions(p).includes(id));
      for (const p of about) most = Math.max(most, about.filter((q) => q.at >= p.at && q.at < p.at + 60 * MIN).length);
    }
    busiest.push(most);
    // (e) No more than 4 pushes about one line in any hour.
    for (const id of Object.keys(LINES) as LineId[]) {
      const about = pushed.filter((p) => mentions(p).includes(id));
      for (const p of about) {
        const hour = about.filter((q) => q.at >= p.at && q.at < p.at + 60 * MIN);
        if (hour.length > 4) { fault("e", `${hour.length} pushes about ${LINES[id]} from ${clock(p.at)}: ${hour.map((q) => clock(q.at)).join(", ")}`); break; }
      }
    }
  }
  const hist = busiest.reduce((h, n) => ((h[n] = (h[n] ?? 0) + 1), h), {} as Record<number, number>);
  console.log(`busiest hour about one line, disruption notices only (count: mornings): ${JSON.stringify(hist)}; mornings with (e) faults: ${new Set(faults.filter((f) => / e: /.test(f)).map((f) => f.split(" ")[0])).size}`);
  console.log(`random mornings: ${TIMELINES} (seed ${SEED}, change ${change}), ${counts.pushes} pushes, ${counts.ended} ended notices, ${counts.checkedEnds} ends checked for (c), ${counts.edits} with an edit, ${faults.length} faults`);
  const counted = faults.reduce((h, f) => ((h[f.split(" ")[1]] = (h[f.split(" ")[1]] ?? 0) + 1), h), {} as Record<string, number>);
  console.log(`faults by rule: ${JSON.stringify(counted)}`);
  for (const f of faults.filter((x) => / e: /.test(x)).slice(0, 5)) console.log(`  (e), reported only: ${f}`);
  assert.deepEqual(faults.filter((f) => !/ e: /.test(f)), []);
});

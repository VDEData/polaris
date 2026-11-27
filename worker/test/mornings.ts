// Made-up mornings for the randomised checks (random.test.ts) and the comparison with main
// (diff.test.ts): TfL statuses that change at random from run to run, a time that ends at a
// random point, at most one edit to the alert, runs on the mark or a little late. The same
// mornings every time for a seed. Each is played through a real `runChecks` against a fake D1,
// fake TfL and fake APNs, and every push is recorded.

import { fixture } from "./helpers.ts";

export const MIN = 60_000;
export const DAY = Date.parse("2026-11-09T00:00:00Z"); // a Monday, GMT
const TOKEN = "ab".repeat(32);

export function random(seed: number) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

export type Way = "out" | "back" | "both";
/** One disruption TfL reports: its title as the matcher words it, how bad, which ways. */
export interface Disruption { title: string; rank: number; ways: Way }
export type LineState = { missing: true } | { missing?: false; statuses: unknown[]; disruptions: Disruption[] };

const NAMES: Record<string, string> = { jubilee: "Jubilee line", central: "Central line", elizabeth: "Elizabeth line" };
// Each disruption has its own words, so a title tells one apart from another.
const RANKS = { minor: 1, severe: 2, "part suspended": 2, "reduced service": 2, suspended: 3 } as const;
const st = (severity: number, description: string, reason: string) => ({ statusSeverity: severity, statusSeverityDescription: description, reason, validityPeriods: [], disruption: { category: "RealTime", affectedStops: [] } });
const d = (line: string, what: keyof typeof RANKS, ways: Way = "both"): Disruption =>
  ({ title: `${NAMES[line]}: ${what === "minor" ? "minor delays" : what === "severe" ? "severe delays" : what}`, rank: RANKS[what], ways });

const STOP = "cwstop"; // Canary Wharf's station notices, kept beside the lines
const stop = (type: "Closure" | "Part Closure", description: string) => ({ type, stationAtcoCode: "910GCANWHRF", commonName: "Canary Wharf", mode: "elizabeth-line", description, appearance: "RealTime" });

/** The kinds of answer TfL gives for a line, by line (and Canary Wharf's station notices). Two
 * statuses on one line included. */
function choices(line: string): (() => LineState)[] {
  if (line === STOP) {
    const none = (): LineState => ({ statuses: [], disruptions: [] });
    return [
      none, none, none, none, none, none,
      () => ({ statuses: [stop("Closure", "Canary Wharf: closed due to a fire alarm.")], disruptions: [{ title: "Canary Wharf closed (Elizabeth line)", rank: 3, ways: "both" }] }),
      () => ({ statuses: [stop("Part Closure", "Canary Wharf: eastbound trains will not stop here while the platform is repaired.")], disruptions: [{ title: "Canary Wharf part closed (Elizabeth line)", rank: 2, ways: "out" }] }),
    ];
  }
  const N = NAMES[line].toUpperCase();
  const good = (): LineState => ({ statuses: [st(10, "Good Service", "")], disruptions: [] });
  const missing = (): LineState => ({ missing: true });
  const minor = (): LineState => ({ statuses: [st(9, "Minor Delays", `${N}: Minor delays due to an earlier signal failure.`)], disruptions: [d(line, "minor")] });
  const severe = (): LineState => ({ statuses: [st(6, "Severe Delays", `${N}: Severe delays due to a faulty train.`)], disruptions: [d(line, "severe")] });
  const suspended = (): LineState => ({ statuses: [st(2, "Suspended", `${N}: Suspended due to a person taken ill on a train.`)], disruptions: [d(line, "suspended")] });
  if (line === "elizabeth") {
    const east = "Severe delays eastbound between Liverpool Street and Abbey Wood.";
    const west = "Part suspended westbound between Abbey Wood and Liverpool Street.";
    const both = `${east} Minor delays on the rest of the line.`;
    return [
      good, good, good, minor, missing,
      () => ({ statuses: [st(6, "Severe Delays", east)], disruptions: [d(line, "severe", "out")] }),
      () => ({ statuses: [st(3, "Part Suspended", west)], disruptions: [d(line, "part suspended", "back")] }),
      () => ({ statuses: [st(7, "Reduced Service", "Reduced service between Liverpool Street and Abbey Wood.")], disruptions: [d(line, "reduced service")] }),
      () => ({ statuses: [st(6, "Severe Delays", both), st(9, "Minor Delays", both)], disruptions: [d(line, "severe", "out"), d(line, "minor")] }),
    ];
  }
  const sections = `${N}: Part suspended between Stratford and North Greenwich. Minor delays on the rest of the line.`;
  return [
    good, good, good, good, minor, minor, severe, suspended, missing,
    () => ({ statuses: [st(3, "Part Suspended", sections), st(9, "Minor Delays", sections)], disruptions: [d(line, "part suspended"), d(line, "minor")] }),
    () => ({ statuses: [st(3, "Part Suspended", `${N}: Part suspended between Stratford and North Greenwich.`)], disruptions: [d(line, "part suspended")] }),
  ];
}

export interface Window { start: number; end: number; direction?: Way }
export interface Alert { id: string; name: string; days: number[]; windows: Window[]; minorDelays: boolean; direction?: Way; segments: any[] }
export interface Edit { at: number; alert: Alert; kind: string }
export interface Morning { alert: Alert; marks: number[]; truth: Map<number, Record<string, LineState>>; edit?: Edit; late: boolean; lines: string[] }

const tube = (id: string) => ({ line: { id, name: NAMES[id] }, mode: "tube", stations: [] as string[] });
const ELIZABETH = { line: { id: "elizabeth", name: "Elizabeth line" }, mode: "elizabeth-line", from: { id: "910GLIVSTLL|910GLIVST", name: "Liverpool Street" }, to: { id: "910GCANWHRF", name: "Canary Wharf" }, stations: ["910GLIVSTLL|910GLIVST", "910GWCHAPXR", "910GCANWHRF"] };

/** One morning. `change`: how often (share of runs) a line's status changes. */
export function morning(rand: () => number, change: number): Morning {
  const pick = <T>(xs: T[]) => xs[Math.floor(rand() * xs.length)];
  const end = 7 * 60 + 40 + 10 * Math.floor(rand() * 12); // 07:40 to 09:30
  const slotted = rand() < 0.4;
  // Half the Elizabeth line alerts have a way of their own and one time, the rest a way per time.
  const alertWay = slotted && rand() < 0.5;
  const lines = slotted ? ["elizabeth"] : rand() < 0.5 ? ["jubilee"] : ["jubilee", "central"];
  const alert: Alert = alertWay
    ? { id: "7A1B2C3D-0000-4000-8000-000000000003", name: "Liverpool Street to Canary Wharf", days: [1, 2, 3, 4, 5], windows: [{ start: 420, end }], minorDelays: true, direction: "out", segments: [ELIZABETH] }
    : slotted
    ? { id: "7A1B2C3D-0000-4000-8000-000000000002", name: "Liverpool Street to Canary Wharf", days: [1, 2, 3, 4, 5], windows: [{ start: 420, end, direction: "out" }, { start: end, end: end + 40, direction: "back" }], minorDelays: true, segments: [ELIZABETH] }
    : { id: "7A1B2C3D-0000-4000-8000-000000000001", name: "Commute", days: [1, 2, 3, 4, 5], windows: [{ start: 420, end }], minorDelays: true, segments: lines.map(tube) };
  const last = Math.max(...alert.windows.map((w) => w.end));
  const marks: number[] = [];
  for (let t = DAY + 418 * MIN; t <= DAY + (last + 200) * MIN; t += 2 * MIN) marks.push(t);
  const all = slotted ? ["elizabeth", STOP] : ["jubilee", "central"];
  const now: Record<string, LineState> = Object.fromEntries(all.map((l) => [l, choices(l)[0]()]));
  const truth = new Map<number, Record<string, LineState>>();
  for (const t of marks) {
    for (const l of all) if (rand() < change) now[l] = pick(choices(l))();
    truth.set(t, { ...now });
  }
  let edit: Edit | undefined;
  if (rand() < 0.7) {
    const at = pick(marks);
    const kinds = alertWay ? ["rename", "end", "minor off", "way turned round", "stretch changed"] : slotted ? ["rename", "end", "minor off", "way turned round", "time's way changed", "stretch changed"] : ["rename", "end", "minor off", lines.length === 2 ? "line removed" : "line added"];
    const kind = pick(kinds);
    const w = alert.windows;
    const changed: Alert =
      kind === "rename" ? { ...alert, name: "Renamed" }
      : kind === "end" ? { ...alert, windows: [{ ...w[0], end: w[0].end + (rand() < 0.5 ? -20 : 20) }, ...w.slice(1).map((x) => ({ ...x, start: x.start + (rand() < 0.5 ? 0 : 0) }))] }
      : kind === "minor off" ? { ...alert, minorDelays: false }
      : kind === "line removed" ? { ...alert, segments: [tube("jubilee")] }
      : kind === "line added" ? { ...alert, segments: [tube("jubilee"), tube("central")] }
      : kind === "way turned round" && alertWay ? { ...alert, direction: "back" }
      : kind === "way turned round" ? { ...alert, windows: w.map((x) => ({ ...x, direction: x.direction === "out" ? "back" : "out" })) }
      : kind === "time's way changed" ? { ...alert, windows: [{ ...w[0], direction: "back" }, ...w.slice(1)] }
      : { ...alert, segments: [{ ...ELIZABETH, from: { id: "910GPADTLL", name: "Paddington" }, to: { id: "910GBONDST", name: "Bond Street" }, stations: ["910GPADTLL", "910GBONDST"] }] };
    edit = { at, alert: changed, kind };
  }
  return { alert, marks, truth, edit, late: rand() < 0.5, lines };
}

/** A tube line's status, by name, as TfL gives it. */
export function lineState(line: string, what: "good" | "minor" | "severe" | "suspended" | "part suspended" | "missing"): LineState {
  const [good, , , , minor, , severe, suspended, missing, , part] = choices(line);
  return { good, minor, severe, suspended, missing, "part suspended": part }[what]();
}

/** A morning of one tube alert, 07:00 until `end`, with TfL saying `at(time)` for each line. */
export function fixedMorning(lines: string[], end: number, at: (hhmm: string) => Record<string, LineState>): Morning {
  const alert: Alert = { id: "7A1B2C3D-0000-4000-8000-000000000005", name: "Commute", days: [1, 2, 3, 4, 5], windows: [{ start: 420, end }], minorDelays: true, segments: lines.map(tube) };
  const marks: number[] = [];
  for (let t = DAY + 418 * MIN; t <= DAY + (end + 30) * MIN; t += 2 * MIN) marks.push(t);
  const truth = new Map(marks.map((t) => [t, at(clock(t))]));
  return { alert, marks, truth, late: false, lines };
}

/** A morning of fixed cycles: each line goes round two or three statuses, each held for 2 to 20
 * minutes, all morning. */
export function cycleMorning(rand: () => number): Morning {
  const pick = <T>(xs: T[]) => xs[Math.floor(rand() * xs.length)];
  const end = 7 * 60 + 40 + 10 * Math.floor(rand() * 12);
  const lines = rand() < 0.5 ? ["jubilee"] : ["jubilee", "central"];
  const alert: Alert = { id: "7A1B2C3D-0000-4000-8000-000000000004", name: "Commute", days: [1, 2, 3, 4, 5], windows: [{ start: 420, end }], minorDelays: true, segments: lines.map(tube) };
  const marks: number[] = [];
  for (let t = DAY + 418 * MIN; t <= DAY + (end + 200) * MIN; t += 2 * MIN) marks.push(t);
  const cycles = Object.fromEntries(["jubilee", "central"].map((l) => {
    const options = choices(l).filter((c, i, all) => all.indexOf(c) === i);
    const steps = Array.from({ length: rand() < 0.5 ? 2 : 3 }, () => ({ state: pick(options)(), minutes: 2 + 2 * Math.floor(rand() * 10) }));
    return [l, steps];
  }));
  const truth = new Map<number, Record<string, LineState>>();
  for (const t of marks) {
    const minute = (t - marks[0]) / MIN;
    truth.set(t, Object.fromEntries(Object.entries(cycles).map(([l, steps]) => {
      const period = steps.reduce((a, x) => a + x.minutes, 0);
      let k = minute % period;
      for (const x of steps) { if (k < x.minutes) return [l, x.state]; k -= x.minutes; }
      return [l, steps[0].state];
    })));
  }
  return { alert, marks, truth, late: rand() < 0.5, lines };
}

export interface Pushed { at: number; run: number; subtitle?: string; body: string; level: string; collapseId: string }

type RunChecks = (env: any, now?: number) => Promise<unknown>;

let KEY = "";
async function key() {
  if (!KEY) {
    const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"])) as CryptoKeyPair;
    KEY = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  }
  return KEY;
}

/** How a morning is played. */
export interface Playing {
  /** How late each run reads the clock, by run (ms): default the morning's own (on the mark, or 0.3 to 3 s late). */
  lateness?: (run: number) => number;
  /** From this run on, another Worker takes over with the state the first left (a deploy). */
  handover?: { run: number; runChecks: RunChecks; forgetLineMaps: () => void };
}

/** Plays a morning through one Worker's `runChecks` (and its `forgetLineMaps`). */
export async function play(m: Morning, runChecks: RunChecks, forgetLineMaps: () => void, show?: (t: number, state: string) => void, how: Playing = {}): Promise<Pushed[]> {
  forgetLineMaps();
  const rows = [{ token: TOKEN, environment: "sandbox", time_zone: "Europe/London", alerts: JSON.stringify([m.alert]), state: JSON.stringify({ alerts: {}, planned: {} }) }];
  const meta = new Map<string, unknown>();
  const db = {
    prepare(sql: string) {
      let args: unknown[] = [];
      const s = {
        bind(...v: unknown[]) { args = v; return s; },
        async all() { return { results: rows.map((r) => ({ ...r })) }; },
        async first() { return sql.includes("FROM meta") ? meta.get("jwt") ?? null : null; },
        async run() {
          if (sql.startsWith("UPDATE devices SET state")) rows[0].state = args[0] as string;
          if (sql.startsWith("INSERT INTO meta")) meta.set("jwt", { value: args[0], updated_at: args[1] });
          return {};
        },
      };
      return s;
    },
  };
  const env = { DB: db, APNS_KEY: await key(), APNS_KEY_ID: "KEY123", APNS_TEAM_ID: "TEAM123456", APNS_TOPIC: "com.example.polaris" };
  const pushed: Pushed[] = [];
  let mark = 0, run = 0;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("push.apple.com")) {
      const aps = JSON.parse(init!.body as string).aps;
      pushed.push({ at: mark, run, subtitle: aps.alert.subtitle, body: aps.alert.body, level: aps["interruption-level"], collapseId: (init!.headers as Record<string, string>)["apns-collapse-id"] });
      return new Response("", { status: 200 });
    }
    if (url.includes("/StopPoint/")) { const s = m.truth.get(mark)![STOP]; return Response.json(s && !s.missing ? s.statuses : []); }
    if (url.includes("/to/")) return Response.json([]);
    if (url.includes("/Route/Sequence/")) return url.includes("/elizabeth/") ? Response.json(fixture("tfl-sequence-elizabeth.json")) : new Response("", { status: 404 });
    if (url.includes("/Line/")) {
      const ids = decodeURIComponent(url.split("/Line/")[1].split("/")[0]).split(",");
      const now = m.truth.get(mark)!;
      return Response.json(ids.flatMap((id) => { const s = now[id]; return !s || s.missing ? [] : [{ id, name: NAMES[id], lineStatuses: s.statuses }]; }));
    }
    throw new Error(`unexpected fetch ${url}`);
  }) as typeof fetch;
  const late = how.lateness ?? ((r: number) => (m.late ? 300 + ((r * 7919) % 2700) : 0));
  for (const t of m.marks) {
    mark = t;
    if (m.edit && t === m.edit.at) rows[0].alerts = JSON.stringify([m.edit.alert]);
    if (how.handover && run === how.handover.run) { runChecks = how.handover.runChecks; how.handover.forgetLineMaps(); }
    await runChecks(env, t + late(run));
    show?.(t, rows[0].state);
    run++;
  }
  return pushed;
}

// Reading what was pushed and what TfL said.

export const minute = (t: number) => Math.round((t - DAY) / MIN);
export const clock = (t: number) => `${String(Math.floor(minute(t) / 60)).padStart(2, "0")}:${String(minute(t) % 60).padStart(2, "0")}`;
export const alertAt = (m: Morning, t: number) => (m.edit && t >= m.edit.at ? m.edit.alert : m.alert);
/** The way ridden at `t`, or undefined outside every time. */
export function ridden(m: Morning, t: number): Way | undefined {
  const a = alertAt(m, t);
  const ways = a.windows.filter((w) => minute(t) >= w.start && minute(t) < w.end).map((w) => w.direction ?? a.direction ?? "both");
  if (!ways.length) return undefined;
  return new Set(ways).size === 1 ? ways[0] : "both";
}
/** Where a disruption is: its line, or (for a station's notice) the station. */
export const lineOf = (title: string) => (/ \(Elizabeth line\)$/.test(title) ? STOP : Object.keys(NAMES).find((id) => title.startsWith(`${NAMES[id]}:`))!);
/** The disruptions TfL reports at `t` that the alert covers on `way`. */
export function covered(m: Morning, t: number, way: Way = ridden(m, t) ?? "both"): Disruption[] {
  const a = alertAt(m, t);
  const now = m.truth.get(t)!;
  const atCanaryWharf = a.segments.some((s) => s.to?.id === "910GCANWHRF" || s.from?.id === "910GCANWHRF");
  const stops = atCanaryWharf && now[STOP] && !now[STOP].missing ? now[STOP].disruptions.filter((x) => way === "both" || x.ways === "both" || x.ways === way) : [];
  return [...stops, ...a.segments.flatMap((s) => {
    const state = now[s.line.id];
    if (!state || state.missing) return [];
    // A stretch elsewhere on the line (Paddington to Bond Street): none of these sections touch it.
    if (s.from?.id === "910GPADTLL") return state.disruptions.filter((x) => x.title.endsWith("minor delays"));
    return state.disruptions.filter((x) => (x.rank > 1 || a.minorDelays) && (way === "both" || x.ways === "both" || x.ways === way));
  })];
}
export const reported = (m: Morning, t: number, title: string) => { const s = m.truth.get(t)![lineOf(title)]; return !!s && !s.missing && s.disruptions.some((x) => x.title === title); };
export const missing = (m: Morning, t: number, line: string) => !!(m.truth.get(t)![line] as { missing?: boolean })?.missing;

/** The titles a disruption notice tells: its subtitle, then "Also: a; b." */
export function titles(p: Pushed): string[] {
  if (!p.subtitle) return [];
  const also = p.body.match(/\n\nAlso: (.*)\.$/s)?.[1].split("; ") ?? [];
  return [p.subtitle, ...also];
}
/** The titles an ended notice says have ended. */
export function ended(p: Pushed): string[] {
  if (p.subtitle) return [];
  const out: string[] = [];
  for (const sentence of p.body.split(/(?<=\.)\s+/)) {
    const m1 = sentence.match(/^(Minor|Severe) delays on the (\w+ line) have cleared\./);
    if (m1) out.push(`${m1[2]}: ${m1[1].toLowerCase()} delays`);
    const m2 = sentence.match(/^The (suspension|part suspension|reduced service) on the (\w+ line) has ended\./);
    if (m2) out.push(`${m2[2]}: ${({ suspension: "suspended", "part suspension": "part suspended", "reduced service": "reduced service" } as Record<string, string>)[m2[1]]}`);
    const m3 = sentence.match(/^(Minor|Severe) delays on part of the (\w+ line) have cleared\./);
    if (m3) out.push(`${m3[2]}: ${m3[1].toLowerCase()} delays`);
    const m4 = sentence.match(/^(.+) has reopened \((.+)\)\./);
    if (m4) out.push(`${m4[1]} closed (${m4[2]})`);
    const m5 = sentence.match(/^(.+) is no longer part closed \((.+)\)\./);
    if (m5) out.push(`${m5[1]} part closed (${m5[2]})`);
  }
  return out;
}

/** The titles an ended notice says go on. */
export function continuing(p: Pushed): string[] {
  if (p.subtitle) return [];
  const out: string[] = [];
  const nouns: Record<string, string> = { suspension: "suspended", "part suspension": "part suspended", "reduced service": "reduced service" };
  for (const sentence of p.body.split(/(?<=\.)\s+/)) {
    const a = sentence.match(/^(Minor|Severe) delays on the (\w+ line) continue\./);
    if (a) out.push(`${a[2]}: ${a[1].toLowerCase()} delays`);
    const b = sentence.match(/^The (suspension|part suspension|reduced service) on the (\w+ line) continues\./);
    if (b) out.push(`${b[2]}: ${nouns[b[1]]}`);
    const c = sentence.match(/^(.+) is still (part closed|closed) \((.+)\)\./);
    if (c) out.push(`${c[1]} ${c[2]} (${c[3]})`);
  }
  return out;
}

const RANK_OF: Record<string, number> = { "minor delays": 1, "severe delays": 2, "part suspended": 2, "reduced service": 2, suspended: 3 };
/** How bad a title says it is. */
export const rankOf = (title: string) => (/ part closed \(/.test(title) ? 2 : / closed \(/.test(title) ? 3 : RANK_OF[title.split(": ")[1]]);

/** What the phone shows for the alert at `t`: the newest notice under its collapse ID, as the
 * disruptions it presents as on now (a disruption notice: all it tells; an ended notice: what it
 * says goes on). */
export function shows(pushes: Pushed[], t: number): string[] {
  const newest = pushes.filter((p) => p.at <= t).at(-1);
  return !newest ? [] : newest.subtitle ? titles(newest) : continuing(newest);
}
/** The phone shows trouble at this disruption's place at least as bad as it is. */
export const showsAtLeast = (shown: string[], d: { title: string; rank: number }) => shown.some((t) => lineOf(t) === lineOf(d.title) && rankOf(t) >= d.rank);

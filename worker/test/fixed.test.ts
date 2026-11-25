// "Fixed" notices: telling you, so you notice, when a disruption you were told about has ended.
// Each test runs the two-minute cron over a stretch of a Monday (GMT, so local time is UTC)
// against TfL answers that change over time, and records exactly what reached APNs.

import assert from "node:assert/strict";
import { test } from "node:test";
import { runChecks, type Env } from "../src/checks.ts";
import { forgetLineMaps } from "../src/linemap.ts";
import { contract, fixture } from "./helpers.ts";

const TOKEN = "ab".repeat(32);

/** Jubilee and Central, whole lines, 07:00 to 09:00 on weekdays, no way of its own. */
const commute = {
  id: "5B0E7C2D-6A41-4F0C-9E1B-3D2A1C0F9E77",
  name: "Morning commute",
  days: [1, 2, 3, 4, 5],
  windows: [{ start: 420, end: 540 }],
  minorDelays: true,
  segments: [
    { line: { id: "jubilee", name: "Jubilee line" }, mode: "tube", stations: [] },
    { line: { id: "central", name: "Central line" }, mode: "tube", stations: [] },
  ],
};

type Status = { id: string; name: string; lineStatuses: unknown[] };
const line = (id: string, name: string, severity: number, description: string, reason = ""): Status =>
  ({ id, name, lineStatuses: [{ statusSeverity: severity, statusSeverityDescription: description, reason, validityPeriods: [], disruption: { category: "RealTime", affectedStops: [] } }] });
const good = (id: string, name: string) => line(id, name, 10, "Good Service");
const JUB_MINOR = line("jubilee", "Jubilee line", 9, "Minor Delays", "JUBILEE LINE: Minor delays due to an earlier signal failure at Canary Wharf.");
const JUB_SEVERE = line("jubilee", "Jubilee line", 6, "Severe Delays", "JUBILEE LINE: Severe delays due to an earlier signal failure at Canary Wharf.");
const CEN_SEVERE = line("central", "Central line", 6, "Severe Delays", "CENTRAL LINE: Severe delays due to a faulty train at Leytonstone.");
const JUB_GOOD = good("jubilee", "Jubilee line");
const CEN_GOOD = good("central", "Central line");

/** One push as APNs got it. */
interface Sent { at: string; priority: string; collapseId: string; expiresIn: number; payload: any }

/** The cron every two minutes from `from` to `to` (inclusive, "HH:MM" on Monday 9 November
 * 2026, or a full "YYYY-MM-DDTHH:MM"), with TfL saying `lines(time)` then. */
async function simulate(from: string, to: string, lines: (at: string) => Status[], alerts: unknown[] = [commute], options: { state?: unknown; stops?: (at: string) => unknown[] } = {}) {
  forgetLineMaps();
  const db = fakeDB([{ token: TOKEN, environment: "sandbox", time_zone: "Europe/London", alerts: JSON.stringify(alerts), state: JSON.stringify(options.state ?? { alerts: {}, planned: {} }) }]);
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"])) as CryptoKeyPair;
  const key = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const env = { DB: db, APNS_KEY: key, APNS_KEY_ID: "KEY123", APNS_TEAM_ID: "TEAM123456", APNS_TOPIC: "com.example.polaris" } as unknown as Env;
  const sent: Sent[] = [];
  const tfl: { at: string; url: string }[] = [];
  let clock = "";
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("push.apple.com")) {
      const h = init!.headers as Record<string, string>;
      sent.push({ at: clock, priority: h["apns-priority"], collapseId: h["apns-collapse-id"], expiresIn: Number(h["apns-expiration"]) - Date.parse(`${iso(clock)}:00Z`) / 1000, payload: JSON.parse(init!.body as string) });
      return new Response("", { status: 200 });
    }
    tfl.push({ at: clock, url });
    if (url.includes("/StopPoint/")) return Response.json(options.stops?.(clock) ?? []);
    if (url.includes("/Route/Sequence/")) return Response.json(fixture("tfl-sequence-elizabeth.json"));
    if (url.includes("/to/")) return Response.json([]);
    if (url.includes("/Line/")) {
      // Only the lines asked for, as TfL answers.
      const ids = decodeURIComponent(url.split("/Line/")[1].split("/")[0]).split(",");
      return Response.json(lines(clock).filter((l) => ids.includes(l.id)));
    }
    throw new Error(`unexpected fetch ${url}`);
  }) as typeof fetch;
  const runs: string[] = [];
  for (let t = Date.parse(`${iso(from)}:00Z`); t <= Date.parse(`${iso(to)}:00Z`); t += 120_000) {
    clock = new Date(t).toISOString().slice(0, 16);
    const result = await runChecks(env, t);
    if (result.checked) runs.push(clock);
  }
  return { sent, tfl, runs, state: () => JSON.parse(db.rows[0].state), db };
}

const iso = (time: string) => (time.includes("T") ? time : `2026-11-09T${time}`);
const hhmm = (s: Sent) => s.at.slice(11);
/** What the person sees, notice by notice: when, and the words. */
const seen = (sent: Sent[]) => sent.map((s) => `${hhmm(s)} ${s.payload.aps.alert.subtitle ? `${s.payload.aps.alert.subtitle} | ` : ""}${s.payload.aps.alert.body}`);
const until = (switchAt: string, before: Status[], after: Status[]) => (at: string) => (at.slice(11) < switchAt ? before : after);

test("a fixed notice is an ordinary notification you notice: active, with sound, sent at once, and it says what ended", async () => {
  // Told at 07:30, cleared at 07:50, inside the time.
  const { sent } = await simulate("07:30", "08:10", until("07:50", [JUB_MINOR, CEN_GOOD], [JUB_GOOD, CEN_GOOD]));
  assert.deepEqual(seen(sent), [
    "07:30 Jubilee line: minor delays | Minor delays due to an earlier signal failure at Canary Wharf.",
    "07:54 Minor delays on the Jubilee line have cleared. Good service again.",
  ]);
  const fixed = sent[1];
  assert.deepEqual(fixed.payload, {
    aps: {
      alert: { title: "Morning commute", body: "Minor delays on the Jubilee line have cleared. Good service again." },
      sound: "default",
      "interruption-level": "active",
      "relevance-score": 0.5,
      "thread-id": commute.id,
    },
    alert: commute.id,
  });
  assert.equal(fixed.priority, "10");
  assert.equal(fixed.collapseId, commute.id, "it takes the place of the notice it settles");
  assert.equal(fixed.expiresIn, 3600);
});

test("told, the time ends, and it clears 20 minutes later: the fixed notice still comes", async () => {
  const { sent, runs } = await simulate("08:50", "10:00", until("09:20", [JUB_SEVERE, CEN_GOOD], [JUB_GOOD, CEN_GOOD]));
  assert.deepEqual(seen(sent), [
    "08:50 Jubilee line: severe delays | Severe delays due to an earlier signal failure at Canary Wharf.",
    // Station notices aren't asked for while following line trouble, so "good service" isn't claimed.
    "09:24 Severe delays on the Jubilee line have cleared.",
  ]);
  // Followed only until it cleared: nothing asked of TfL after 09:24.
  assert.equal(runs.at(-1), "2026-11-09T09:24");
});

test("after the time ends, only the fixed notice: new trouble and worse trouble aren't pushed outside your times", async () => {
  const lines = (at: string) => {
    const t = at.slice(11);
    if (t < "09:00") return [JUB_MINOR, CEN_GOOD];
    if (t < "09:30") return [JUB_SEVERE, CEN_SEVERE]; // worse, and a new one, after 09:00
    return [JUB_GOOD, CEN_SEVERE];
  };
  const { sent } = await simulate("08:50", "10:00", lines);
  assert.deepEqual(seen(sent), [
    "08:50 Jubilee line: minor delays | Minor delays due to an earlier signal failure at Canary Wharf.",
    // Central's trouble was never told, so it isn't "continuing" news, and isn't "good service" either.
    "09:34 Minor delays on the Jubilee line have cleared.",
  ]);
});

test("told, the time ends, it never clears: following stops at the limit and nothing is sent", async () => {
  const { sent, runs, state } = await simulate("08:50", "13:00", () => [JUB_SEVERE, CEN_GOOD]);
  assert.equal(sent.length, 1);
  // Followed for three hours after the time ended, then no more checks.
  assert.equal(runs.at(-1), "2026-11-09T12:00");
  assert.ok(runs.length <= 7 + 90 + 1, `${runs.length} runs`);
  assert.ok(state().alerts[commute.id].following, "remembers it stopped following");
});

test("told yesterday, never cleared while followed, gone by the next morning: nothing is sent", async () => {
  const first = await simulate("2026-11-09T08:50", "2026-11-09T12:10", () => [JUB_SEVERE, CEN_GOOD]);
  assert.equal(first.sent.length, 1);
  const next = await simulate("2026-11-10T06:58", "2026-11-10T07:30", () => [JUB_GOOD, CEN_GOOD], [commute], { state: first.state() });
  assert.deepEqual(seen(next.sent), []);
  assert.equal(next.state().alerts[commute.id].open, undefined, "settled quietly");
});

test("two told, one clears while the other continues: said once, as an ordinary notice", async () => {
  const lines = (at: string) => {
    const t = at.slice(11);
    if (t < "07:40") return [JUB_MINOR, CEN_SEVERE];
    if (t < "08:10") return [JUB_MINOR, CEN_GOOD];
    return [JUB_GOOD, CEN_GOOD];
  };
  const { sent } = await simulate("07:30", "08:30", lines);
  assert.deepEqual(seen(sent), [
    "07:30 Central line: severe delays | Severe delays due to a faulty train at Leytonstone.\n\nAlso: Jubilee line: minor delays.",
    "07:44 Severe delays on the Central line have cleared. Minor delays on the Jubilee line continue.",
    "08:14 Minor delays on the Jubilee line have cleared. Good service again.",
  ]);
  assert.equal(sent[1].payload.aps["interruption-level"], "active");
  assert.equal(sent[1].payload.aps.sound, "default");
});

test("easing (severe to minor) is told as the new state, and then one fixed notice, not two", async () => {
  const lines = (at: string) => {
    const t = at.slice(11);
    if (t < "07:40") return [JUB_SEVERE, CEN_GOOD];
    if (t < "08:00") return [JUB_MINOR, CEN_GOOD];
    return [JUB_GOOD, CEN_GOOD];
  };
  const { sent } = await simulate("07:30", "08:20", lines);
  // TfL gives the eased trouble its own status, so it is told as it now is (as before this
  // change); the severe delays it replaced aren't then "cleared" on top.
  assert.deepEqual(seen(sent), [
    "07:30 Jubilee line: severe delays | Severe delays due to an earlier signal failure at Canary Wharf.",
    "07:40 Jubilee line: minor delays | Minor delays due to an earlier signal failure at Canary Wharf.",
    "08:04 Minor delays on the Jubilee line have cleared. Good service again.",
  ]);
});

test("a flapping line: told, fixed, back, and then quiet until it has stayed away for half an hour", async () => {
  // Minor delays on and off every few minutes from 07:30 to 08:20, then good service.
  const flaps = ["07:30", "07:40", "07:50", "08:00", "08:10"];
  const lines = (at: string) => {
    const t = at.slice(11);
    const on = t < "08:20" && flaps.some((f) => t >= f && t < f.replace(/:(\d)0/, (_, d) => `:${d}4`));
    return [on ? JUB_MINOR : JUB_GOOD, CEN_GOOD];
  };
  const { sent } = await simulate("07:30", "09:00", lines);
  assert.deepEqual(seen(sent), [
    "07:30 Jubilee line: minor delays | Minor delays due to an earlier signal failure at Canary Wharf.",
    "07:38 Minor delays on the Jubilee line have cleared. Good service again.",
    "07:40 Jubilee line: minor delays | Minor delays due to an earlier signal failure at Canary Wharf.",
    // Last seen 08:12; the fixed notice waits for half an hour without it.
    "08:44 Minor delays on the Jubilee line have cleared. Good service again.",
  ]);
});

test("a fixed notice outside your times doesn't bring back new pushes if it returns", async () => {
  const lines = (at: string) => {
    const t = at.slice(11);
    if (t < "09:10") return [JUB_MINOR, CEN_GOOD];
    if (t < "09:20") return [JUB_GOOD, CEN_GOOD];
    return [JUB_MINOR, CEN_GOOD];
  };
  const { sent, runs } = await simulate("08:50", "10:00", lines);
  assert.deepEqual(seen(sent), [
    "08:50 Jubilee line: minor delays | Minor delays due to an earlier signal failure at Canary Wharf.",
    "09:14 Minor delays on the Jubilee line have cleared.",
  ]);
  assert.equal(runs.at(-1), "2026-11-09T09:14", "following stopped once cleared");
});

test("a long disruption inside a long time is still told fixed, however long ago the notice was", async () => {
  const allDay = { ...commute, windows: [{ start: 420, end: 1140 }] };
  const { sent } = await simulate("07:30", "11:40", until("11:30", [JUB_SEVERE, CEN_GOOD], [JUB_GOOD, CEN_GOOD]), [allDay]);
  assert.deepEqual(seen(sent).map((s) => s.slice(0, 5)), ["07:30", "11:34"]);
});

test("TfL asked no more than needed while following: one line status request per run, for the alert's lines, and no station notices for line trouble", async () => {
  const { tfl } = await simulate("08:58", "09:30", () => [JUB_SEVERE, CEN_GOOD]);
  const after = tfl.filter((r) => r.at.slice(11) >= "09:00");
  assert.ok(after.length > 0);
  assert.ok(after.every((r) => r.url === "https://api.tfl.gov.uk/Line/central,jubilee/Status?detail=true"), after.map((r) => r.url).join("\n"));
  assert.equal(new Set(after.map((r) => r.at)).size, after.length, "one request per run");
});

// Times of day with their own way, as in checks.test.ts: out until 10:00, back 10:00 to 12:00.
const slotted = { ...contract().alerts[0], direction: undefined, windows: [{ start: 420, end: 600, direction: "out" }, { start: 600, end: 720, direction: "back" }] };
const said = (reason: string): Status[] => [{ id: "elizabeth", name: "Elizabeth line", lineStatuses: [{ statusSeverity: 6, statusSeverityDescription: "Severe Delays", reason, validityPeriods: [], disruption: { category: "RealTime", affectedStops: [] } }] }];
const EAST = said("Severe delays eastbound between Liverpool Street and Abbey Wood.");
const ELIZ_GOOD = [good("elizabeth", "Elizabeth line")];

test("a time going the other way: one-way trouble told going out is not 'fixed' while it lasts, and is when it goes", async () => {
  const lines = (at: string) => (at.slice(11) < "10:20" ? EAST : ELIZ_GOOD);
  const { sent } = await simulate("09:50", "10:40", lines, [slotted]);
  assert.deepEqual(seen(sent), [
    "09:50 Elizabeth line: severe delays | Severe delays eastbound between Liverpool Street and Abbey Wood.",
    "10:24 Severe delays on the Elizabeth line have cleared. Good service again.",
  ]);
});

test("one-way trouble told going out, after the last time ends: followed, and told fixed when it goes", async () => {
  const lines = (at: string) => (at.slice(11) < "12:30" ? EAST : ELIZ_GOOD);
  const { sent } = await simulate("11:50", "13:00", lines, [{ ...slotted, windows: [{ start: 690, end: 720, direction: "out" }, { start: 990, end: 1100, direction: "back" }] }]);
  assert.deepEqual(seen(sent), [
    "11:50 Elizabeth line: severe delays | Severe delays eastbound between Liverpool Street and Abbey Wood.",
    "12:34 Severe delays on the Elizabeth line have cleared.",
  ]);
});

test("state stored by the deployed Worker: an open notice with no words kept still clears, in the old words but noticeably", async () => {
  // As the Worker before this change stored it: told at 07:30, no record of what was said.
  const old = { alerts: { [commute.id]: { live: { "line:jubilee:9:abc": { severity: "minor", seen: Date.parse("2026-11-09T07:30:00Z") } }, open: Date.parse("2026-11-09T07:30:00Z") } }, planned: {}, plannedOn: "2026-11-08" };
  const { sent, state } = await simulate("07:32", "07:50", () => [JUB_GOOD, CEN_GOOD], [commute], { state: old });
  assert.deepEqual(seen(sent), ["07:36 Good service again on the Jubilee line and the Central line."]);
  assert.equal(sent[0].payload.aps["interruption-level"], "active");
  assert.equal(state().alerts[commute.id].open, undefined);
  assert.equal(state().plannedOn, "2026-11-08", "the rest of the state is kept");
});

test("state stored by the deployed Worker: an open notice from hours ago isn't followed or cleared after the time", async () => {
  const at = Date.parse("2026-11-09T06:00:00Z");
  const old = { alerts: { [commute.id]: { live: { "line:jubilee:9:abc": { severity: "minor", seen: at } }, open: at } }, planned: {} };
  const { sent, runs } = await simulate("09:00", "10:00", () => [JUB_GOOD, CEN_GOOD], [commute], { state: old });
  assert.deepEqual(sent, []);
  assert.deepEqual(runs, [], "nothing asked of TfL");
});

test("state stored by the deployed Worker: still disrupted, the same trouble isn't told again at the deploy", async () => {
  const at = Date.parse("2026-11-09T07:30:00Z");
  const { sent } = await simulate("07:32", "07:40", () => [JUB_MINOR, CEN_GOOD], [commute], { state: {} });
  // A first notice for a fresh phone, for comparison.
  assert.equal(sent.length, 1);
  const signature = Object.keys((await simulate("07:30", "07:30", () => [JUB_MINOR, CEN_GOOD])).state().alerts[commute.id].live)[0];
  const old = { alerts: { [commute.id]: { live: { [signature]: { severity: "minor", seen: at } }, open: at } }, planned: {} };
  const again = await simulate("07:32", "07:40", () => [JUB_MINOR, CEN_GOOD], [commute], { state: old });
  assert.deepEqual(again.sent, []);
});

test("weeks-long station works told once: followed after that one time, not after every time on the days after", async () => {
  const works = () => [{ type: "Part Closure", stationAtcoCode: "910GCANWHRF", commonName: "Canary Wharf", mode: "elizabeth-line", description: "Canary Wharf: eastbound trains will not stop here while the platform is rebuilt.", appearance: "RealTime" }];
  const mornings = { ...contract().alerts[0], windows: [{ start: 420, end: 540 }] };
  const day1 = await simulate("2026-11-09T08:50", "2026-11-09T12:10", () => ELIZ_GOOD, [mornings], { stops: works });
  assert.equal(day1.sent.length, 1);
  assert.equal(day1.runs.at(-1), "2026-11-09T12:00", "followed for three hours after the first time");
  const day2 = await simulate("2026-11-10T06:58", "2026-11-10T13:00", () => ELIZ_GOOD, [mornings], { state: day1.state(), stops: works });
  assert.deepEqual(day2.sent, [], "known works aren't told again");
  assert.equal(day2.runs.at(-1), "2026-11-10T08:58", "nothing asked of TfL after the time on day two");
  const day3 = await simulate("2026-11-11T06:58", "2026-11-11T13:00", () => ELIZ_GOOD, [mornings], { state: day2.state(), stops: (at) => (at.slice(11) < "08:00" ? works() : []) });
  // The works end inside the time on day three: told, as a station reopening, with "good service".
  assert.deepEqual(seen(day3.sent), ["08:04 Canary Wharf is no longer part closed (Elizabeth line). Good service again."]);
  assert.equal(day3.runs.at(-1), "2026-11-11T08:58");
});

test("station trouble told, followed after the time: station notices are asked for, and its end is told", async () => {
  const closed = () => [{ type: "Closure", stationAtcoCode: "910GCANWHRF", commonName: "Canary Wharf", mode: "elizabeth-line", description: "Canary Wharf: closed due to a fire alarm.", appearance: "RealTime" }];
  const mornings = { ...contract().alerts[0], windows: [{ start: 420, end: 540 }] };
  const { sent, tfl } = await simulate("08:50", "09:40", () => ELIZ_GOOD, [mornings], { stops: (at) => (at.slice(11) < "09:20" ? closed() : []) });
  assert.deepEqual(seen(sent), [
    "08:50 Canary Wharf closed (Elizabeth line) | closed due to a fire alarm.",
    "09:24 Canary Wharf has reopened (Elizabeth line). Good service again.",
  ]);
  assert.ok(tfl.some((r) => r.at.slice(11) > "09:00" && r.url.includes("/StopPoint/")));
});

/** Enough of D1 for the checks, as in checks.test.ts. */
function fakeDB(rows: Record<string, any>[]) {
  const meta = new Map<string, { value: string; updated_at: number }>();
  return {
    rows,
    prepare(sql: string) {
      let args: unknown[] = [];
      const statement = {
        bind(...values: unknown[]) { args = values; return statement; },
        async all() { return { results: rows.map((r) => ({ ...r })) }; },
        async first() { return sql.includes("FROM meta") ? meta.get("apns_jwt") ?? null : null; },
        async run() {
          if (sql.startsWith("UPDATE devices SET state")) rows.find((r) => r.token === args[1])!.state = args[0];
          if (sql.startsWith("INSERT INTO meta")) meta.set("apns_jwt", { value: args[0] as string, updated_at: args[1] as number });
          return {};
        },
      };
      return statement;
    },
  };
}

import assert from "node:assert/strict";
import { test } from "node:test";
import { sign } from "../src/apns.ts";
import { clearPush, issuesPush, lineList, plannedPush, words } from "../src/push.ts";
import type { Issue } from "../src/types.ts";
import * as valid from "../src/validate.ts";
import { contract } from "./helpers.ts";

const now = Date.parse("2026-11-09T07:30:00Z");
const [commute, district] = contract().alerts;
const closed: Issue = { signature: "a", severity: "closed", line: "Elizabeth line", title: "Canary Wharf closed (Elizabeth line)", detail: "Closed - escalators." };
const minor: Issue = { signature: "b", severity: "minor", line: "Elizabeth line", title: "Elizabeth line: minor delays", detail: "Minor delays." };

test("a closure is Time Sensitive, says where, and replaces the last one for this alert", () => {
  const push = issuesPush(commute, [closed, minor], now);
  const aps = (push.payload as any).aps;
  assert.equal(aps["interruption-level"], "time-sensitive");
  assert.deepEqual(aps.alert, { title: "Liverpool Street → Canary Wharf", subtitle: closed.title, body: "Closed - escalators.\n\nAlso: Elizabeth line: minor delays." });
  assert.equal(push.collapseId, commute.id);
  assert.equal((push.payload as any).alert, commute.id);
});

test("minor delays don't break through a Focus; nor does 'it has ended', but it sounds and comes at once", () => {
  assert.equal((issuesPush(commute, [minor], now).payload as any).aps["interruption-level"], "active");
  const clear = clearPush(commute, { kind: "clear", ended: ["Elizabeth line: minor delays"], still: [], all: true }, now);
  const aps = (clear.payload as any).aps;
  assert.equal(aps["interruption-level"], "active");
  assert.equal(aps.sound, "default");
  assert.equal(clear.priority, 10);
  assert.equal(aps.alert.title, commute.name);
  assert.equal(aps.alert.body, "Minor delays on the Elizabeth line have cleared. Good service again.");
  assert.equal(clear.collapseId, commute.id);
  assert.equal(aps["thread-id"], commute.id);
  assert.equal(clear.expiration, now / 1000 + 3600);
  // State from before the words were kept: the old sentence, now with a sound.
  assert.equal((clearPush(commute, { kind: "clear", ended: [], still: [], all: true }, now).payload as any).aps.alert.body, "Good service again on the Elizabeth line.");
});

test("what ended is said in the words it was told, and what goes on too", () => {
  const two = { ...commute, segments: [...commute.segments, { line: { id: "southeastern", name: "Southeastern" }, mode: "national-rail", stations: [] }] };
  const say = (title: string) => words(two, title);
  assert.deepEqual(say("Elizabeth line: severe delays"), { ended: "Severe delays on the Elizabeth line have cleared.", still: "Severe delays on the Elizabeth line continue." });
  assert.deepEqual(say("Southeastern: minor delays"), { ended: "Minor delays on Southeastern have cleared.", still: "Minor delays on Southeastern continue." });
  assert.deepEqual(say("Elizabeth line: part suspended"), { ended: "The part suspension on the Elizabeth line has ended.", still: "The part suspension on the Elizabeth line continues." });
  assert.deepEqual(say("Elizabeth line: part closure"), { ended: "The part closure on the Elizabeth line has ended.", still: "The part closure on the Elizabeth line continues." });
  assert.deepEqual(say("Elizabeth line: no service"), { ended: "The Elizabeth line is running again.", still: "The Elizabeth line is still not running." });
  assert.deepEqual(say("Canary Wharf closed (Elizabeth line)"), { ended: "Canary Wharf has reopened (Elizabeth line).", still: "Canary Wharf is still closed (Elizabeth line)." });
  assert.deepEqual(say("Canary Wharf part closed (Elizabeth line)"), { ended: "Canary Wharf is no longer part closed (Elizabeth line).", still: "Canary Wharf is still part closed (Elizabeth line)." });
  assert.deepEqual(say("Something else"), { ended: "Ended: Something else.", still: "Still on: Something else." });
  assert.deepEqual(say("Elizabeth line: bus service"), { ended: "The replacement bus service on the Elizabeth line has ended; trains are running again.", still: "Replacement buses are still running on the Elizabeth line." });
  // Two told with the same words, one ended.
  const same = clearPush(two, { kind: "clear", ended: ["Elizabeth line: part suspended"], still: ["Elizabeth line: part suspended"], all: false }, now);
  assert.equal((same.payload as any).aps.alert.body, "One of the part suspensions on the Elizabeth line has ended. One continues.");
  const delays = clearPush(two, { kind: "clear", ended: ["Elizabeth line: minor delays"], still: ["Elizabeth line: minor delays", "Elizabeth line: minor delays"], all: false }, now);
  assert.equal((delays.payload as any).aps.alert.body, "Minor delays on part of the Elizabeth line have cleared. They continue elsewhere on the line.");
  const partial = clearPush(two, { kind: "clear", ended: ["Elizabeth line: severe delays"], still: ["Southeastern: minor delays"], all: false }, now);
  assert.equal((partial.payload as any).aps.alert.body, "Severe delays on the Elizabeth line have cleared. Minor delays on Southeastern continue.");
});

test("planned works say what and when", () => {
  const push = plannedPush(commute, [{ ...closed, title: "Elizabeth line: part closure", detail: "Sunday 15 November, until 1015, no service." }], now);
  assert.equal((push.payload as any).aps.alert.subtitle, "Planned: part closure tomorrow");
  assert.equal(push.collapseId, `${commute.id}-planned`);
});

test("lines are said the way people say them", () => {
  assert.equal(lineList(district), "the District line");
  assert.equal(lineList({ ...commute, segments: [...commute.segments, { line: { id: "southeastern", name: "Southeastern" }, mode: "national-rail", stations: [] }] }),
    "the Elizabeth line and Southeastern");
});

test("the app's alerts pass validation unchanged", () => {
  const body = contract();
  // The second alert is as older builds send it, without a direction: both ways.
  assert.equal(body.alerts[1].direction, undefined);
  assert.deepEqual(valid.alerts(body.alerts), body.alerts.map((a: any) => ({ ...a, direction: a.direction ?? "both", segments: a.segments.map((s: any) => ({ from: undefined, to: undefined, ...s })) })));
  assert.equal(valid.alerts(body.alerts)[0].direction, "out");
  assert.equal(valid.alerts([{ ...commute, direction: "back" }])[0].direction, "back");
  assert.equal(valid.alerts([{ ...commute, direction: "sideways" }])[0].direction, "both", "a word from a newer build is both ways, not refused");
  // Each time's own way: kept; missing stays missing (the alert's); an unknown word is both ways.
  assert.deepEqual(body.alerts[0].windows.map((w: any) => w.direction), [undefined, "back"]);
  assert.ok(!("direction" in valid.alerts(body.alerts)[0].windows[0]), "missing isn't filled in");
  assert.equal(valid.alerts(body.alerts)[0].windows[1].direction, "back");
  const sideways = valid.alerts([{ ...commute, windows: [{ start: 420, end: 600, direction: "sideways" }] }])[0];
  assert.equal(sideways.windows[0].direction, "both");
  assert.equal(valid.environment(body.environment), "sandbox");
  assert.equal(valid.timeZone(body.timeZone), "Europe/London");
  assert.throws(() => valid.deviceToken("not-a-token"));
  assert.throws(() => valid.alerts([{ ...commute, segments: [{ ...commute.segments[0], mode: "bus" }] }]));
  assert.throws(() => valid.alerts([{ ...commute, windows: [{ start: 600, end: 600 }] }]));
});

test("the APNs token is a JWT Apple can verify", async () => {
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const pem = `-----BEGIN PRIVATE KEY-----\n${pkcs8.match(/.{1,64}/g)!.join("\n")}\n-----END PRIVATE KEY-----\n`;
  const jwt = await sign(pem, "KEY123", "TEAM456", now);
  const [header, claims, signature] = jwt.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(header, "base64url").toString()), { alg: "ES256", kid: "KEY123" });
  assert.deepEqual(JSON.parse(Buffer.from(claims, "base64url").toString()), { iss: "TEAM456", iat: now / 1000 });
  const ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, pair.publicKey, Buffer.from(signature, "base64url"), new TextEncoder().encode(`${header}.${claims}`));
  assert.ok(ok);
});

// Android phones: the same notices through FCM (HTTP v1), beside APNs. Google and Apple are a
// fake `fetch`; nothing leaves the machine.

import assert from "node:assert/strict";
import { timingSafeEqual } from "node:crypto";
import { test } from "node:test";
import { runChecks, type Env } from "../src/checks.ts";
import { platform } from "../src/deliver.ts";
import { canPush, message, serviceAccount, sign } from "../src/fcm.ts";
import worker from "../src/index.ts";
import { forgetLineMaps } from "../src/linemap.ts";
import { clearPush, issuesPush, plannedPush } from "../src/push.ts";
import type { Issue } from "../src/types.ts";
import * as valid from "../src/validate.ts";
import { contract, fixture, sqliteD1 } from "./helpers.ts";

(crypto.subtle as any).timingSafeEqual ??= (a: ArrayBuffer, b: ArrayBuffer) => timingSafeEqual(Buffer.from(a), Buffer.from(b));

const now = Date.parse("2026-11-09T17:00:00Z");
const [commute] = contract().alerts;
const IPHONE = "ab".repeat(32);
/** A Firebase installation ID's shape: 22 characters of URL-safe base64, case kept. */
const ANDROID = "cXk3Tm9wQl9aLWFiY2RlZg";
const closed: Issue = { signature: "a", severity: "closed", line: "Elizabeth line", title: "Canary Wharf closed (Elizabeth line)", detail: "Closed - escalators." };
const minor: Issue = { signature: "b", severity: "minor", line: "Elizabeth line", title: "Elizabeth line: minor delays", detail: "Minor delays." };

/** A service-account file as Firebase gives it, with a key made here. */
async function account(projectId = "polaris-test") {
  const pair = (await crypto.subtle.generateKey({ name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pkcs8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  // The PEM's first and last lines, put together here so no key-shaped text sits in the file.
  const edge = (which: string) => `-----${which} PRIVATE KEY-----`;
  const pem = [edge("BEGIN"), ...pkcs8.match(/.{1,64}/g)!, edge("END"), ""].join("\n");
  const file = JSON.stringify({ type: "service_account", project_id: projectId, client_email: `push@${projectId}.iam.gserviceaccount.com`, private_key: pem });
  return { file, publicKey: pair.publicKey };
}

test("a phone's service is told from what it registered with", () => {
  assert.equal(platform(IPHONE), "apns");
  assert.equal(platform("AB".repeat(50)), "apns");
  assert.equal(platform(ANDROID), "fcm");
  // Kept exactly: an installation ID's case matters; an APNs token's doesn't.
  assert.equal(valid.deviceToken(ANDROID), ANDROID);
  assert.equal(valid.deviceToken("AB".repeat(32)), IPHONE);
  // A hex string too short to be an APNs token is an installation ID's shape; nothing else passes.
  for (const bad of ["", "short", "a".repeat(64) + "!", "has:colon-and-more-characters", "x".repeat(64), "x".repeat(300), "../../etc/passwd-padding-here"]) {
    assert.throws(() => valid.deviceToken(bad), `"${bad}"`);
  }
});

test("a closure for Android: the same words, urgent, at once, under the alert's tag", () => {
  const push = issuesPush(commute, [closed, minor], now);
  const m = message(ANDROID, push, now) as any;
  assert.equal(m.fid, ANDROID);
  assert.deepEqual(m.data, {
    alert: commute.id,
    tag: commute.id,
    title: "Liverpool Street → Canary Wharf",
    subtitle: closed.title,
    body: "Closed - escalators.\n\nAlso: Elizabeth line: minor delays.",
    level: "urgent",
    sent: String(now),
  });
  assert.deepEqual(m.android, { priority: "HIGH", ttl: "10800s" });
  assert.equal(m.notification, undefined, "data only: the app draws it, open or not");
  assert.equal(m.android.collapse_key, undefined, "FCM keeps four collapse keys a phone; the app replaces by tag");
});

test("every value FCM is given is text", () => {
  for (const push of [issuesPush(commute, [minor], now), plannedPush(commute, [closed], now), clearPush(commute, { kind: "clear", ended: [], still: [], all: true }, now, true)]) {
    for (const [key, value] of Object.entries((message(ANDROID, push, now) as any).data)) assert.equal(typeof value, "string", key);
  }
});

test("minor delays and 'it has ended' sound without breaking through; a quiet ended notice is silent and can wait", () => {
  assert.equal((message(ANDROID, issuesPush(commute, [minor], now), now) as any).data.level, "normal");
  const ended = { kind: "clear", ended: ["Elizabeth line: minor delays"], still: [], all: true } as const;
  const loud = message(ANDROID, clearPush(commute, ended, now), now) as any;
  assert.equal(loud.data.level, "normal");
  assert.equal(loud.data.body, "Minor delays on the Elizabeth line have cleared. Good service again.");
  assert.equal(loud.data.subtitle, undefined);
  assert.equal(loud.data.tag, commute.id, "it replaces the notice it settles");
  assert.deepEqual(loud.android, { priority: "HIGH", ttl: "3600s" });
  const quiet = message(ANDROID, clearPush(commute, ended, now, true), now) as any;
  assert.equal(quiet.data.level, "quiet");
  assert.equal(quiet.data.tag, commute.id);
  assert.deepEqual(quiet.android, { priority: "NORMAL", ttl: "3600s" });
});

test("planned works keep their own tag, so they don't replace today's notice", () => {
  const m = message(ANDROID, plannedPush(commute, [{ ...closed, title: "Elizabeth line: part closure", detail: "Sunday 15 November, until 1015, no service." }], now), now) as any;
  assert.equal(m.data.tag, `${commute.id}-planned`);
  assert.equal(m.data.subtitle, "Planned: part closure tomorrow");
  assert.equal(m.data.level, "normal");
  assert.equal(m.android.ttl, "43200s");
});

test("the service account's assertion is a JWT Google can verify", async () => {
  const { file, publicKey } = await account();
  const jwt = await sign(serviceAccount({ FCM_SERVICE_ACCOUNT: file } as any)!, now);
  const [header, claims, signature] = jwt.split(".");
  assert.deepEqual(JSON.parse(Buffer.from(header, "base64url").toString()), { alg: "RS256", typ: "JWT" });
  assert.deepEqual(JSON.parse(Buffer.from(claims, "base64url").toString()), {
    iss: "push@polaris-test.iam.gserviceaccount.com", scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token", iat: now / 1000, exp: now / 1000 + 3600,
  });
  assert.ok(await crypto.subtle.verify("RSASSA-PKCS1-v1_5", publicKey, Buffer.from(signature, "base64url"), new TextEncoder().encode(`${header}.${claims}`)));
});

test("a secret that isn't the service-account file means no Android pushes, not a crash", async () => {
  const { file } = await account();
  assert.equal(canPush({ FCM_SERVICE_ACCOUNT: file } as any), true);
  for (const bad of [undefined, "", "not json", "{}", JSON.stringify({ project_id: "p", client_email: "a@b" }), JSON.stringify({ project_id: "../x", client_email: "a@b", private_key: "-----BEGIN PRIVATE KEY-----" })]) {
    assert.equal(canPush({ FCM_SERVICE_ACCOUNT: bad } as any), false, String(bad));
  }
});

// ---- The cron, with an iPhone and an Android phone ----

function fakeDB(rows: Record<string, any>[]) {
  const meta = new Map<string, { value: string; updated_at: number }>();
  const key = (sql: string) => sql.match(/'(apns_jwt|fcm_token)'/)?.[1] ?? "";
  return {
    rows, meta,
    prepare(sql: string) {
      let args: unknown[] = [];
      const statement = {
        bind(...values: unknown[]) { args = values; return statement; },
        async all() { return { results: rows.map((r) => ({ ...r })) }; },
        async first() { return sql.includes("FROM meta") ? meta.get(key(sql)) ?? null : null; },
        async run() {
          if (sql.startsWith("UPDATE devices SET state")) rows.find((r) => r.token === args[1])!.state = args[0];
          if (sql.startsWith("DELETE FROM devices")) rows.splice(rows.findIndex((r) => r.token === args[0]), 1);
          if (sql.startsWith("INSERT INTO meta")) meta.set(key(sql), { value: args[0] as string, updated_at: args[1] as number });
          if (sql.startsWith("DELETE FROM meta")) meta.delete(key(sql));
          return {};
        },
      };
      return statement;
    },
  };
}

const row = (token: string) => ({ token, environment: token === IPHONE ? "sandbox" : "production", time_zone: "Europe/London", alerts: JSON.stringify([commute]), state: JSON.stringify({ alerts: {}, planned: {} }) });
const fcmError = (code: number, errorCode: string) => new Response(JSON.stringify({ error: { code, message: "refused", status: errorCode, details: [{ "@type": "type.googleapis.com/google.firebase.fcm.v1.FcmError", errorCode }] } }), { status: code });

interface Setup {
  tokens?: string[];
  apns?: boolean;
  fcm?: boolean;
  /** What FCM answers the nth send. */
  answer?: (n: number) => Response;
  oauth?: () => Response;
}

async function setup({ tokens = [IPHONE, ANDROID], apns = true, fcm = true, answer = () => Response.json({ name: "projects/polaris-test/messages/1" }), oauth }: Setup = {}) {
  forgetLineMaps();
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign"])) as CryptoKeyPair;
  const p8 = Buffer.from(await crypto.subtle.exportKey("pkcs8", pair.privateKey)).toString("base64");
  const db = fakeDB(tokens.map(row));
  const env = {
    DB: db,
    ...(apns ? { APNS_KEY: p8, APNS_KEY_ID: "KEY123", APNS_TEAM_ID: "TEAM123456", APNS_TOPIC: "com.example.polaris" } : {}),
    ...(fcm ? { FCM_SERVICE_ACCOUNT: (await account()).file } : {}),
  } as unknown as Env;
  const requests: { url: string; init?: RequestInit }[] = [];
  let sends = 0, signIns = 0;
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push({ url, init });
    if (url.includes("push.apple.com")) return new Response("", { status: 200 });
    if (url === "https://oauth2.googleapis.com/token") return oauth?.() ?? Response.json({ access_token: `access-${++signIns}`, expires_in: 3600, token_type: "Bearer" });
    if (url.includes("fcm.googleapis.com")) return answer(++sends);
    if (url.includes("/StopPoint/")) return Response.json(fixture("tfl-station-disruptions.json"));
    if (url.includes("/to/")) return Response.json(fixture("tfl-line-status-week.json"));
    if (url.includes("/Route/Sequence/")) return Response.json(fixture("tfl-sequence-elizabeth.json"));
    if (url.includes("/Line/")) return Response.json(fixture("tfl-line-status-detail.json"));
    throw new Error(`unexpected fetch ${url}`);
  }) as typeof fetch;
  const to = (host: string) => requests.filter((r) => r.url.includes(host));
  const told = (token: string) => Boolean(JSON.parse(db.rows.find((r) => r.token === token)?.state ?? "{}").alerts?.[commute.id]?.open);
  return { env, db, requests, apnsSends: () => to("push.apple.com"), fcmSends: () => to("fcm.googleapis.com"), signIns: () => to("oauth2.googleapis.com"), told };
}

test("an iPhone and an Android phone on the same alert: one notice each, each by its own service", async () => {
  const { env, apnsSends, fcmSends, signIns, told } = await setup();
  assert.deepEqual(await runChecks(env, now), { devices: 2, checked: 2, sent: 2 });
  assert.equal(apnsSends().length, 1);
  assert.equal(apnsSends()[0].url, `https://api.sandbox.push.apple.com/3/device/${IPHONE}`);
  assert.equal(fcmSends().length, 1);
  const send = fcmSends()[0];
  assert.equal(send.url, "https://fcm.googleapis.com/v1/projects/polaris-test/messages:send");
  assert.equal((send.init!.headers as Record<string, string>).authorization, "Bearer access-1");
  const sent = JSON.parse(send.init!.body as string);
  assert.deepEqual(Object.keys(sent), ["message"]);
  assert.equal(sent.message.fid, ANDROID);
  // The same words the iPhone got.
  const aps = JSON.parse(apnsSends()[0].init!.body as string).aps;
  assert.equal(sent.message.data.title, aps.alert.title);
  assert.equal(sent.message.data.subtitle, aps.alert.subtitle);
  assert.equal(sent.message.data.body, aps.alert.body);
  assert.equal(sent.message.data.level, "urgent");
  // Signed in once, with the service account's assertion.
  assert.equal(signIns().length, 1);
  const form = new URLSearchParams(signIns()[0].init!.body as string);
  assert.equal(form.get("grant_type"), "urn:ietf:params:oauth:grant-type:jwt-bearer");
  assert.equal(form.get("assertion")!.split(".").length, 3);
  assert.ok(told(IPHONE) && told(ANDROID));
  // Told once: the next run sends neither again.
  assert.equal((await runChecks(env, now + 120_000)).sent, 0);
});

test("only iPhones registered: Google is never asked", async () => {
  const { env, requests, apnsSends } = await setup({ tokens: [IPHONE] });
  assert.equal((await runChecks(env, now)).sent, 1);
  assert.equal(apnsSends().length, 1);
  assert.ok(!requests.some((r) => r.url.includes("googleapis.com")));
});

test("the access token is kept: a second notice signs in no more", async () => {
  const { env, db, fcmSends, signIns } = await setup({ tokens: [ANDROID] });
  await runChecks(env, now);
  // Another Android phone, told nothing yet, 40 minutes on: the kept token is used.
  db.rows.push(row("QW5vdGhlclBob25lXzAwMQ"));
  await runChecks(env, now + 40 * 60_000);
  assert.equal(fcmSends().length, 2);
  assert.equal(signIns().length, 1);
  // After 50 minutes it signs in again.
  db.rows.push(row("VGhpcmRQaG9uZV8wMDAwMg"));
  await runChecks(env, now + 52 * 60_000);
  assert.equal(fcmSends().length, 3);
  assert.equal(signIns().length, 2);
});

test("no Firebase service account: the iPhone is still told; nothing is judged for the Android phone", async () => {
  const { env, db, apnsSends, requests, told } = await setup({ fcm: false });
  const before = db.rows[1].state;
  assert.deepEqual(await runChecks(env, now), { devices: 2, checked: 1, sent: 1 });
  assert.equal(apnsSends().length, 1);
  assert.ok(!requests.some((r) => r.url.includes("googleapis.com")));
  assert.ok(told(IPHONE));
  assert.equal(db.rows[1].state, before, "not marked told: it is told once the server can");
  // The secret set: the Android phone hears of the closure that is still on.
  (env as any).FCM_SERVICE_ACCOUNT = (await account()).file;
  assert.deepEqual(await runChecks(env, now + 120_000), { devices: 2, checked: 2, sent: 1 });
  assert.ok(told(ANDROID));
});

test("no APNs key: the Android phone is still told; nothing is judged for the iPhone", async () => {
  const { env, db, apnsSends, fcmSends, told } = await setup({ apns: false });
  const before = db.rows[0].state;
  assert.deepEqual(await runChecks(env, now), { devices: 2, checked: 1, sent: 1 });
  assert.equal(apnsSends().length, 0);
  assert.equal(fcmSends().length, 1);
  assert.ok(told(ANDROID));
  assert.equal(db.rows[0].state, before);
});

test("neither set: nothing judged, not even TfL asked", async () => {
  const { env, requests } = await setup({ apns: false, fcm: false });
  assert.deepEqual(await runChecks(env, now), { devices: 2, checked: 0, sent: 0 });
  assert.equal(requests.length, 0);
});

test("FCM says the phone is unregistered: it is forgotten, as a dead APNs token is; the iPhone stays", async () => {
  const { env, db } = await setup({ answer: () => fcmError(404, "UNREGISTERED") });
  assert.equal((await runChecks(env, now)).sent, 1);
  assert.deepEqual(db.rows.map((r) => r.token), [IPHONE]);
});

test("registered with another Firebase project: forgotten too", async () => {
  const { env, db } = await setup({ tokens: [ANDROID], answer: () => fcmError(403, "SENDER_ID_MISMATCH") });
  await runChecks(env, now);
  assert.equal(db.rows.length, 0);
});

test("a 400 is not a dead phone: refused for good, kept, and not sent again", async () => {
  const { env, db, fcmSends, told } = await setup({ tokens: [ANDROID], answer: () => fcmError(400, "INVALID_ARGUMENT") });
  await runChecks(env, now);
  await runChecks(env, now + 120_000);
  assert.equal(db.rows.length, 1, "one bad payload mustn't remove every phone");
  assert.equal(fcmSends().length, 1);
  assert.ok(told(ANDROID), "recorded as handled");
});

test("a 404 that doesn't say the phone is unregistered (a wrong project) removes nobody", async () => {
  const notFound = () => new Response(JSON.stringify({ error: { code: 404, message: "Requested entity was not found.", status: "NOT_FOUND" } }), { status: 404 });
  const { env, db, fcmSends } = await setup({ tokens: [ANDROID, "QW5vdGhlclBob25lXzAwMQ"], answer: notFound });
  await runChecks(env, now);
  assert.equal(db.rows.length, 2, "one mistake in the setup mustn't remove every phone");
  assert.equal(fcmSends().length, 2);
  // And with nothing readable at all in the answer.
  const empty = await setup({ tokens: [ANDROID], answer: () => new Response("", { status: 404 }) });
  await runChecks(empty.env, now);
  assert.equal(empty.db.rows.length, 1);
});

test("FCM busy: tried again next run, a bounded number of times, then once it goes through, no more", async () => {
  const busy = await setup({ tokens: [ANDROID], answer: () => fcmError(503, "UNAVAILABLE") });
  for (let i = 0; i < 6; i++) await runChecks(busy.env, now + i * 120_000);
  assert.equal(busy.fcmSends().length, 3);
  const once = await setup({ tokens: [ANDROID], answer: (n) => (n === 1 ? fcmError(429, "QUOTA_EXCEEDED") : Response.json({ name: "x" })) });
  assert.equal((await runChecks(once.env, now)).sent, 0);
  assert.ok(!once.told(ANDROID), "not told until it is sent");
  assert.equal((await runChecks(once.env, now + 120_000)).sent, 1);
  assert.equal((await runChecks(once.env, now + 240_000)).sent, 0);
  assert.equal(once.fcmSends().length, 2);
});

test("an access token FCM no longer takes is dropped, and the next run signs in again", async () => {
  const { env, db, fcmSends, signIns } = await setup({ tokens: [ANDROID], answer: (n) => (n === 1 ? fcmError(401, "UNAUTHENTICATED") : Response.json({ name: "x" })) });
  assert.equal((await runChecks(env, now)).sent, 0);
  assert.equal(db.meta.has("fcm_token"), false);
  assert.equal(db.rows.length, 1);
  assert.equal((await runChecks(env, now + 120_000)).sent, 1);
  assert.equal(signIns().length, 2);
  assert.equal((fcmSends()[1].init!.headers as Record<string, string>).authorization, "Bearer access-2");
});

test("Google refuses the service account: retried, never a crash, and the iPhone is still told", async () => {
  const { env, db, fcmSends, apnsSends } = await setup({ oauth: () => new Response(JSON.stringify({ error: "invalid_grant" }), { status: 400 }) });
  assert.equal((await runChecks(env, now)).sent, 1);
  assert.equal(apnsSends().length, 1);
  assert.equal(fcmSends().length, 0);
  assert.equal(db.rows.length, 2);
});

// ---- The routes ----

const mine = "a".repeat(40), theirs = "b".repeat(40);

function call(env: any, method: string, path: string, secret?: string, body?: unknown) {
  const headers: Record<string, string> = { authorization: "Bearer app-token", "content-type": "application/json" };
  if (secret) headers["x-device-secret"] = secret;
  return worker.fetch(new Request(`https://alerts.example.com${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined }), env, {} as ExecutionContext);
}

test("an Android phone registers, is kept under its installation ID exactly, and only its install can change it", async () => {
  const db = sqliteD1();
  const env = { DB: db, APP_TOKEN: "app-token", FCM_SERVICE_ACCOUNT: (await account()).file };
  const body = { ...contract(), environment: "production" };
  const put = await call(env, "PUT", `/v1/devices/${ANDROID}`, mine, body);
  assert.equal(put.status, 200);
  assert.deepEqual(await put.json(), { ok: true, alerts: 2, pushes: true });
  assert.ok(db.rows.get(ANDROID), "case kept");
  assert.equal(db.rows.get(ANDROID.toLowerCase()), undefined);
  assert.equal((await call(env, "PUT", `/v1/devices/${ANDROID}`, theirs, body)).status, 403);
  assert.equal((await call(env, "DELETE", `/v1/devices/${ANDROID}`, theirs)).status, 403);
  assert.equal((await call(env, "DELETE", `/v1/devices/${ANDROID}`, mine)).status, 200);
  assert.equal(db.rows.size, 0);
});

test("registering says whether this server can push to this kind of phone", async () => {
  const body = { ...contract(), environment: "production" };
  const apnsOnly = { DB: sqliteD1(), APP_TOKEN: "app-token", APNS_KEY: "k", APNS_KEY_ID: "K", APNS_TEAM_ID: "T", APNS_TOPIC: "com.example.polaris" };
  assert.equal(((await (await call(apnsOnly, "PUT", `/v1/devices/${ANDROID}`, mine, body)).json()) as any).pushes, false);
  assert.equal(((await (await call(apnsOnly, "PUT", `/v1/devices/${IPHONE}`, mine, { ...body, environment: "sandbox" })).json()) as any).pushes, true);
  const health = await (await worker.fetch(new Request("https://alerts.example.com/health"), { DB: sqliteD1(), FCM_SERVICE_ACCOUNT: (await account()).file } as any, {} as ExecutionContext)).json();
  assert.deepEqual(health, { ok: true, pushes: false, android: true });
});

test("a test notification to an Android phone goes through FCM; without the service account it says so", async () => {
  const db = sqliteD1();
  const body = { ...contract(), environment: "production" };
  const without = { DB: db, APP_TOKEN: "app-token" };
  await call(without, "PUT", `/v1/devices/${ANDROID}`, mine, body);
  const sent: any[] = [];
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url === "https://oauth2.googleapis.com/token") return Response.json({ access_token: "access", expires_in: 3600 });
    if (url.includes("fcm.googleapis.com")) { sent.push(JSON.parse(init!.body as string).message); return Response.json({ name: "x" }); }
    // TfL not answering: the test notice still goes, as one of what a notice will look like.
    return new Response("", { status: 503 });
  }) as typeof fetch;
  const refused = await call(without, "POST", `/v1/devices/${ANDROID}/test`, mine);
  assert.equal(refused.status, 503);
  assert.match(((await refused.json()) as any).error, /no push key/);
  const env = { ...without, FCM_SERVICE_ACCOUNT: (await account()).file };
  const ok = await call(env, "POST", `/v1/devices/${ANDROID}/test`, mine);
  assert.equal(ok.status, 200);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].fid, ANDROID);
  assert.equal(sent[0].data.subtitle, "Test: alerts are on");
  assert.equal(sent[0].data.tag, `${contract().alerts[0].id}-test`, "its own tag: it replaces no real notice");
  // A key Google refuses: said plainly, with nothing of the key.
  globalThis.fetch = (async () => new Response(JSON.stringify({ error: "invalid_grant" }), { status: 400 })) as typeof fetch;
  const fresh = { ...env, DB: db };
  await db.prepare("DELETE FROM meta WHERE key = 'fcm_token'").run();
  const failed = await call(fresh, "POST", `/v1/devices/${ANDROID}/test`, mine);
  assert.equal(failed.status, 502);
  assert.match(((await failed.json()) as any).error, /couldn't sign in/);
});

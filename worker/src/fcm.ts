// Firebase Cloud Messaging (HTTP v1) for Android phones, beside APNs. The same notices, in the
// same words: each `Push` built for APNs is sent as a data message the app draws itself, so a
// notice looks the same whether the app is open or not. Signed in with a service account
// (RS256 JWT exchanged for an access token), which lives in D1 for 50 minutes as the APNs
// provider token does.

import type { Push } from "./push.ts";

export interface FcmEnv {
  DB: D1Database;
  /** The contents of the Firebase service-account JSON file (Project settings, Service accounts,
   * Generate new private key): a secret. Its project_id, client_email and private_key are used. */
  FCM_SERVICE_ACCOUNT?: string;
}

interface ServiceAccount {
  projectId: string;
  clientEmail: string;
  privateKey: string;
}

const TOKEN_URL = "https://oauth2.googleapis.com/token";
const SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

/** The service account, or nothing when the secret is missing or isn't that file. Never throws
 * and never logs the secret. */
export function serviceAccount(env: FcmEnv): ServiceAccount | undefined {
  if (!env.FCM_SERVICE_ACCOUNT) return undefined;
  try {
    const file = JSON.parse(String(env.FCM_SERVICE_ACCOUNT)) as Record<string, unknown>;
    const { project_id: projectId, client_email: clientEmail, private_key: privateKey } = file;
    if (typeof projectId !== "string" || typeof clientEmail !== "string" || typeof privateKey !== "string") return undefined;
    // The project goes into the request's path.
    if (!/^[a-z0-9][a-z0-9-]{2,62}$/.test(projectId) || !clientEmail.includes("@") || !privateKey.includes("PRIVATE KEY")) return undefined;
    return { projectId, clientEmail, privateKey };
  } catch {
    return undefined;
  }
}

/** Everything an Android push needs: a readable service-account file. */
export const canPush = (env: FcmEnv) => serviceAccount(env) !== undefined;

export interface Sent {
  status: number;
  reason?: string;
  /** The phone is gone (app removed, or registered with another Firebase project): forget it. */
  gone: boolean;
  /** Worth trying again next run: FCM busy, or an access token it no longer takes (dropped). */
  retry?: boolean;
}

/** How a notice comes through on Android, from APNs' interruption level: "urgent" may be let
 * through Do Not Disturb, "normal" sounds, "quiet" neither sounds nor pops up. */
export type Level = "urgent" | "normal" | "quiet";

/** The FCM message for a push: data only, every value text. The app shows it under `tag`, so a
 * later notice for the same alert replaces the earlier one, and drops one older (`sent`) than
 * the notice it already shows. No collapse key: FCM keeps at most four per phone, fewer than
 * the alerts a phone may have. */
export function message(fid: string, push: Push, now: number): Record<string, unknown> {
  const aps = (push.payload.aps ?? {}) as { alert?: { title?: string; subtitle?: string; body?: string }; "interruption-level"?: string };
  const level: Level = aps["interruption-level"] === "time-sensitive" ? "urgent" : aps["interruption-level"] === "passive" ? "quiet" : "normal";
  const data: Record<string, string> = {
    alert: String(push.payload.alert ?? ""),
    tag: push.collapseId,
    title: aps.alert?.title ?? "",
    body: aps.alert?.body ?? "",
    level,
    sent: String(now),
  };
  if (aps.alert?.subtitle) data.subtitle = aps.alert.subtitle;
  return {
    fid,
    data,
    android: {
      // High wakes a sleeping phone at once, as APNs priority 10 does; quiet notices can wait.
      priority: push.priority === 10 ? "HIGH" : "NORMAL",
      // As long as APNs would keep trying, and never past it.
      ttl: `${Math.max(0, push.expiration - Math.floor(now / 1000))}s`,
    },
  };
}

export async function send(env: FcmEnv, fid: string, push: Push, now = Date.now()): Promise<Sent> {
  const account = serviceAccount(env);
  if (!account) throw new Error("FCM service account not set");
  const token = await accessToken(env, account, now);
  const response = await fetch(`https://fcm.googleapis.com/v1/projects/${account.projectId}/messages:send`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}`, "content-type": "application/json" },
    body: JSON.stringify({ message: message(fid, push, now) }),
  });
  if (response.ok) return { status: 200, gone: false };
  const error = ((await response.json().catch(() => ({}))) as { error?: { status?: string; details?: { errorCode?: string }[] } }).error;
  const reason = error?.details?.find((d) => d.errorCode)?.errorCode ?? error?.status;
  // The access token wasn't taken: dropped, so the next run signs in again.
  const unauthenticated = response.status === 401 || reason === "UNAUTHENTICATED";
  if (unauthenticated) await env.DB.prepare("DELETE FROM meta WHERE key = 'fcm_token'").run();
  // Only what FCM says means this phone: unregistered, or registered with another project (as
  // APNs' DeviceTokenNotForTopic). A 400 is not: it also covers a payload FCM won't take, and
  // one bad payload mustn't remove every phone.
  const gone = response.status === 404 || reason === "UNREGISTERED" || reason === "SENDER_ID_MISMATCH";
  return { status: response.status, reason, gone, retry: unauthenticated || response.status === 429 || response.status >= 500 };
}

async function accessToken(env: FcmEnv, account: ServiceAccount, now: number): Promise<string> {
  const cached = await env.DB.prepare("SELECT value, updated_at FROM meta WHERE key = 'fcm_token'").first<{ value: string; updated_at: number }>();
  if (cached && now - cached.updated_at < 50 * 60_000) return cached.value;
  const response = await fetch(TOKEN_URL, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer", assertion: await sign(account, now) }).toString(),
  });
  const body = (await response.json().catch(() => ({}))) as { access_token?: string; error?: string };
  // Google's word for what's wrong (invalid_grant: a deleted key, a wrong clock), never the key.
  if (!response.ok || typeof body.access_token !== "string") throw new FcmSignIn(response.status, String(body.error ?? "").slice(0, 80));
  await env.DB.prepare("INSERT INTO meta (key, value, updated_at) VALUES ('fcm_token', ?1, ?2) ON CONFLICT(key) DO UPDATE SET value = ?1, updated_at = ?2")
    .bind(body.access_token, now).run();
  return body.access_token;
}

/** Google wouldn't give an access token for the service account. */
export class FcmSignIn extends Error {
  constructor(status: number, reason: string) {
    super(`FCM sign-in failed: ${status} ${reason}`);
  }
}

/** The service account's own assertion: an RS256 JWT, good for an hour, for the messaging scope. */
export async function sign(account: ServiceAccount, now: number): Promise<string> {
  const der = Uint8Array.from(atob(account.privateKey.replace(/-----[^-]+-----|\s|\\n/g, "")), (c) => c.charCodeAt(0));
  const key = await crypto.subtle.importKey("pkcs8", der, { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"]);
  const encode = (value: object) => base64url(new TextEncoder().encode(JSON.stringify(value)));
  const iat = Math.floor(now / 1000);
  const unsigned = `${encode({ alg: "RS256", typ: "JWT" })}.${encode({ iss: account.clientEmail, scope: SCOPE, aud: TOKEN_URL, iat, exp: iat + 3600 })}`;
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned));
  return `${unsigned}.${base64url(new Uint8Array(signature))}`;
}

function base64url(bytes: Uint8Array): string {
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

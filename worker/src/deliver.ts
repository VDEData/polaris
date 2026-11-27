// One notice to one phone, by whichever service the phone is on: APNs for an iPhone, FCM for an
// Android phone. What is sent and when is decided before this (policy.ts, checks.ts, push.ts).

import * as apns from "./apns.ts";
import * as fcm from "./fcm.ts";
import type { Push } from "./push.ts";
import type { Environment } from "./types.ts";

export type PushEnv = apns.ApnsEnv & fcm.FcmEnv;
export type Platform = "apns" | "fcm";

export interface Sent {
  status: number;
  reason?: string;
  /** The phone is gone: forget it. */
  gone: boolean;
  /** Worth trying again next run, beyond what the status says. */
  retry?: boolean;
}

/** Which service a phone is on, from what it registered with: an APNs device token is 64 or
 * more hex digits; a Firebase installation ID is shorter (22 characters of URL-safe base64).
 * Told by shape rather than kept in a column, so the devices table and every statement on it
 * are as they were, and a deploy needs no migration. */
export function platform(token: string): Platform {
  return /^[0-9a-f]{64,200}$/i.test(token) ? "apns" : "fcm";
}

/** Whether a push can reach a phone on this service: its keys are set. */
export function canPush(env: PushEnv, to: Platform): boolean {
  return to === "apns" ? apns.canPush(env) : fcm.canPush(env);
}

/** [test]: "Send a test notification". An Android phone is told so; what an iPhone is sent is as it was. */
export function send(env: PushEnv, token: string, environment: Environment, push: Push, now = Date.now(), test = false): Promise<Sent> {
  return platform(token) === "apns" ? apns.send(env, token, environment, push, now) : fcm.send(env, token, push, now, test);
}

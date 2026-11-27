// Frozen copy of worker/src/checks.ts at commit b24bdf5 (main before alerts told you when a disruption ends), for tests only:
// test/diff.test.ts drives it beside the new code. Not part of the Worker; never edit it.

// Every two minutes: for each phone, the alerts whose times are now, against TfL. Nothing is
// fetched when nobody is travelling. The evening before, planned works on tomorrow's trips.

import { canPush, send, type ApnsEnv } from "./apns.ts";
import { loadLineMaps } from "./linemap.ts";
import { issuesFor, issuesInRanges, linesToPlace, type LineMaps } from "./match.ts";
import { MAX_RETRIES, plannedStep, step, type DeviceState, type Ways } from "./policy.ts";
import { clearPush, issuesPush, plannedPush, type Push } from "./push.ts";
import { activeWindows, dateKey, during, eachWay, localTime, PLANNED_CHECK_AT, travelRanges } from "./schedule.ts";
import { fetchTfL, lineStatusURL, parseLineStatuses, parseStationDisruptions, stationDisruptionsURL, type LineStatus, type StationDisruption } from "./tfl.ts";
import type { Alert, Device, Direction } from "./types.ts";

export interface Env extends ApnsEnv {
  APP_TOKEN?: string;
  TFL_APP_KEY?: string;
  /** How many phones may register (a var; default 100). */
  MAX_DEVICES?: string | number;
  /** Local runs only: where recorded TfL answers are served. */
  TFL_BASE?: string;
}

interface Row {
  token: string;
  environment: Device["environment"];
  time_zone: string;
  alerts: string;
  state: string;
}

export const emptyState = (): DeviceState => ({ alerts: {}, planned: {} });

export async function loadDevices(env: Env): Promise<(Device & { state: DeviceState })[]> {
  const { results } = await env.DB.prepare("SELECT token, environment, time_zone, alerts, state FROM devices").all<Row>();
  return results.map((r) => ({
    token: r.token,
    environment: r.environment,
    timeZone: r.time_zone,
    alerts: JSON.parse(r.alerts) as Alert[],
    state: { ...emptyState(), ...(JSON.parse(r.state) as Partial<DeviceState>) },
  }));
}

export async function runChecks(env: Env, now = Date.now()): Promise<{ devices: number; checked: number; sent: number }> {
  const devices = await loadDevices(env);
  // Without the push key, key ID, team and topic nothing can be sent, so nothing is judged:
  // a disruption marked told that no phone heard about would never be told.
  if (!canPush(env)) {
    if (devices.length) console.error("pushes not configured: set APNS_KEY, APNS_KEY_ID, APNS_TEAM_ID and APNS_TOPIC");
    return { devices: devices.length, checked: 0, sent: 0 };
  }
  const work = devices.map((device) => {
    const local = localTime(now, device.timeZone);
    return {
      device,
      // Each alert as ridden now: the way of the time you're in (overlapping times that
      // disagree are both ways).
      live: device.alerts.flatMap((a) => {
        const windows = activeWindows(a, now, device.timeZone);
        return windows.length ? [{ alert: during(a, windows), base: a }] : [];
      }),
      planned: local.minutes >= PLANNED_CHECK_AT && device.state.plannedOn !== dateKey(local),
      today: dateKey(local),
    };
  });
  const live = work.flatMap((w) => w.live.map((l) => l.alert));
  const plannedAlerts = work.filter((w) => w.planned).flatMap((w) => w.device.alerts);
  if (!live.length && !plannedAlerts.length) return { devices: devices.length, checked: 0, sent: 0 };

  const segments = [...live, ...plannedAlerts].flatMap((a) => a.segments);
  let lines: LineStatus[] = [], stations: StationDisruption[] = [];
  try {
    [lines, stations] = await Promise.all([
      live.length ? fetchTfL(lineStatusURL(live.flatMap((a) => a.segments.map((s) => s.line.id))), 60, env.TFL_APP_KEY).then(parseLineStatuses) : [],
      fetchTfL(stationDisruptionsURL(segments.map((s) => s.mode)), 60, env.TFL_APP_KEY).then(parseStationDisruptions),
    ]);
  } catch (error) {
    console.error("TfL unavailable", String(error));
    return { devices: devices.length, checked: 0, sent: 0 };
  }

  // Only when TfL names a section in words, or says one way only: the line's stations in order.
  // Each way an alert's times go, too: which ways a disruption is on decides what's news.
  const ridden = [...live, ...work.flatMap((w) => w.live.flatMap((l) => eachWay(l.base)))];
  const maps = await loadLineMaps(linesToPlace(ridden, lines, stations), env.TFL_APP_KEY, now);

  let sent = 0;
  for (const { device, live: active, planned, today } of work) {
    if (!active.length && !planned) continue;
    const before = JSON.stringify(device.state);
    const state = device.state;
    const retries = { ...(state.retries ?? {}) };
    let gone = false;

    /** Sends one push and keeps what it decided only once it's done with: sent, or refused for
     * good (logged loudly), or failed for a passing reason too often. A passing failure keeps the
     * previous state for that alert alone, so only that push is tried again next run. */
    const deliver = async (key: string, push: Push, commit: () => void): Promise<boolean> => {
      const outcome = await attempt(env, device.token, device.environment, push, now);
      if (outcome === "gone") {
        gone = true;
        return false;
      }
      if (outcome === "sent") sent++;
      if (outcome === "retry" && (retries[key] ?? 0) + 1 < MAX_RETRIES) {
        retries[key] = (retries[key] ?? 0) + 1;
        return false;
      }
      if (outcome === "retry") console.error("push given up after retries", key);
      delete retries[key];
      commit();
      return true;
    };

    try {
      for (const { alert, base } of active) {
        if (gone) break;
        const issues = issuesFor(alert, lines, stations, { at: now }, maps);
        const result = step(state.alerts[alert.id], issues, now, waysFor(alert, base, lines, stations, now, maps));
        const commit = () => void (state.alerts[alert.id] = result.state);
        if (result.push?.kind === "issues") await deliver(alert.id, issuesPush(alert, result.push.issues, now), commit);
        else if (result.push?.kind === "clear") await deliver(alert.id, clearPush(alert, now), commit);
        else commit();
      }
      if (planned && !gone) {
        let allDone = true;
        const tomorrow = device.alerts.map((alert) => ({ alert, ranges: travelRanges(alert, now, device.timeZone, 2, true) })).filter((t) => t.ranges.length);
        if (tomorrow.length) {
          const all = tomorrow.flatMap((t) => t.ranges);
          const range = { from: Math.min(...all.map((r) => r.from)), to: Math.max(...all.map((r) => r.to)) };
          const ahead = parseLineStatuses(await fetchTfL(lineStatusURL(tomorrow.flatMap((t) => t.alert.segments.map((s) => s.line.id)), range), 900, env.TFL_APP_KEY));
          const more = linesToPlace(tomorrow.flatMap((t) => eachWay(t.alert)), ahead, stations).filter((id) => !maps.has(id));
          for (const [id, map] of await loadLineMaps(more, env.TFL_APP_KEY, now)) maps.set(id, map);
          for (const { alert, ranges } of tomorrow) {
            if (gone) break;
            const result = plannedStep(state.planned, alert.id, issuesInRanges(alert, ahead, stations, ranges, maps), now);
            const commit = () => void (state.planned = result.planned);
            if (result.fresh.length) allDone = (await deliver(`planned:${alert.id}`, plannedPush(alert, result.fresh, now), commit)) && allDone;
            else commit();
          }
        }
        // Tomorrow is settled only when every alert's planned works went out (or were given up on).
        if (allDone) state.plannedOn = today;
      }
      // Forget alerts the phone no longer has.
      for (const id of Object.keys(state.alerts)) if (!device.alerts.some((a) => a.id === id)) delete state.alerts[id];
    } catch (error) {
      console.error("check failed", String(error));
    }
    if (gone) {
      await env.DB.prepare("DELETE FROM devices WHERE token = ?1").bind(device.token).run();
      continue;
    }
    if (Object.keys(retries).length) state.retries = retries;
    else delete state.retries;
    if (JSON.stringify(state) !== before) {
      await env.DB.prepare("UPDATE devices SET state = ?1 WHERE token = ?2").bind(JSON.stringify(state), device.token).run();
    }
  }
  return { devices: devices.length, checked: live.length, sent };
}

/** For an alert with any time going one way: the way ridden now, which ways each disruption on
 * the stretch is on, and how to read memory from before ways were kept. Nil for alerts that go
 * both ways at every time, which are judged as before. */
export function waysFor(alert: Alert, base: Alert, lines: LineStatus[], stations: StationDisruption[], now: number, maps: LineMaps): Ways | undefined {
  const going = eachWay(base).map((a) => a.direction!);
  if (!going.some((d) => d !== "both")) return undefined;
  const on = new Map<string, Direction[]>();
  for (const direction of ["out", "back"] as const) {
    for (const issue of issuesFor({ ...alert, direction }, lines, stations, { at: now }, maps)) on.set(issue.signature, [...(on.get(issue.signature) ?? []), direction]);
  }
  const way = alert.direction ?? "both";
  return {
    way,
    on,
    holds: way !== "both" && going.some((d) => d !== way),
    told: base.direction === "out" || base.direction === "back" ? [base.direction] : ["out", "back"],
  };
}

/** One push: sent; the phone is gone; worth trying again (APNs busy or rate limited, a network
 * failure, an expired provider token it has just dropped); or refused for good (bad topic,
 * payload, key), which is logged loudly and not retried. */
async function attempt(env: Env, token: string, environment: Device["environment"], push: Push, now: number): Promise<"sent" | "gone" | "retry" | "refused"> {
  try {
    const result = await send(env, token, environment, push, now);
    if (result.gone) {
      console.log("device gone", result.reason);
      return "gone";
    }
    if (result.status === 200) return "sent";
    if (result.status === 429 || result.status >= 500 || result.reason === "ExpiredProviderToken") {
      console.error("APNs busy, will retry", result.status, result.reason);
      return "retry";
    }
    console.error("APNs REFUSED for good: check the push setup", result.status, result.reason);
    return "refused";
  } catch (error) {
    console.error("push failed, will retry", String(error));
    return "retry";
  }
}

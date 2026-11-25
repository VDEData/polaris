// What each notification says. Closures and delays are Time Sensitive, so they come
// through a Focus; minor delays, planned works and "it has ended" are ordinary, with a sound.

import type { Decision } from "./policy.ts";
import type { Alert, Issue, Segment } from "./types.ts";

export interface Push {
  payload: Record<string, unknown>;
  priority: 5 | 10;
  collapseId: string;
  /** Seconds since 1970 after which APNs stops trying. */
  expiration: number;
}

const RELEVANCE = { closed: 1, delays: 0.8, minor: 0.4 } as const;
const MAX_BODY = 700;

export function issuesPush(alert: Alert, issues: Issue[], now: number): Push {
  const [first, ...rest] = issues;
  const urgent = issues.some((i) => i.severity !== "minor");
  const also = rest.length ? `\n\nAlso: ${rest.map((i) => i.title).join("; ")}.` : "";
  return {
    payload: {
      aps: {
        alert: { title: alert.name, subtitle: first.title, body: trim(first.detail || first.title, MAX_BODY - also.length) + also },
        sound: "default",
        "interruption-level": urgent ? "time-sensitive" : "active",
        "relevance-score": RELEVANCE[first.severity],
        "thread-id": alert.id,
      },
      alert: alert.id,
    },
    priority: 10,
    collapseId: alert.id,
    expiration: Math.floor(now / 1000) + 3 * 3600,
  };
}

/** A disruption told has ended, in the words it was told: "Minor delays on the Jubilee line have
 * cleared. Good service again." With others told still on, it says so instead. It has the
 * collapse ID of the notice it settles, which APNs documents as merging the two into one
 * notification, so Notification Centre shows how things stand now rather than trouble that is
 * over. Whether the merged one still sounds and shows a banner hasn't been seen on a phone. */
export function clearPush(alert: Alert, decision: Extract<Decision, { kind: "clear" }>, now: number): Push {
  const body = !decision.ended.length
    ? `Good service again on ${lineList(alert)}.`
    : [...sentences(alert, decision.ended, decision.still), ...(!decision.still.length && decision.all ? ["Good service again."] : [])].join(" ");
  return {
    payload: {
      aps: {
        alert: { title: alert.name, body: trim(body, MAX_BODY) },
        sound: "default",
        "interruption-level": "active",
        "relevance-score": 0.5,
        "thread-id": alert.id,
      },
      alert: alert.id,
    },
    priority: 10,
    collapseId: alert.id,
    expiration: Math.floor(now / 1000) + 3600,
  };
}

/** What ended, then what goes on. Two told in the same words (two part suspensions on one line)
 * are told apart by number: "One of the part suspensions on the Jubilee line has ended. One continues." */
function sentences(alert: Alert, ended: string[], still: string[]): string[] {
  const count = (list: string[], title: string) => list.filter((t) => t === title).length;
  const out: string[] = [];
  for (const title of new Set(ended)) {
    const e = count(ended, title), s = count(still, title);
    out.push(s ? some(alert, title, e).ended : e > 1 ? some(alert, title, e, true).ended : words(alert, title).ended);
  }
  for (const title of new Set(still)) {
    const e = count(ended, title), s = count(still, title);
    out.push(e ? some(alert, title, s).still : words(alert, title).still);
  }
  return out;
}

/** Several told in the same words: `n` of them ended (or, for `still`, go on); `all` when every one ended. */
function some(alert: Alert, title: string, n: number, all = false): { ended: string; still: string } {
  for (const segment of alert.segments) {
    const prefix = `${segment.line.name}: `;
    if (!title.startsWith(prefix)) continue;
    const what = title.slice(prefix.length);
    const on = lineName(segment);
    if (/delays$|^issues reported$/.test(what)) {
      return all
        ? { ended: `${capital(what)} on ${on} have cleared.`, still: "" }
        : { ended: `${capital(what)} on part of ${on} have cleared.`, still: "They continue elsewhere on the line." };
    }
    const noun = NOUNS[what] ?? what;
    const nouns = /s$/.test(noun) ? noun : `${noun}s`;
    return {
      ended: all ? `The ${nouns} on ${on} have ended.` : n === 1 ? `One of the ${nouns} on ${on} has ended.` : `${n} of the ${nouns} on ${on} have ended.`,
      still: n === 1 ? "One continues." : `${n} continue.`,
    };
  }
  return { ended: words(alert, title).ended, still: words(alert, title).still };
}

// TfL's words for a line's trouble, as things that end: "the part suspension".
const NOUNS: Record<string, string> = { suspended: "suspension", "part suspended": "part suspension", closed: "closure", "service closed": "closure", "part closed": "part closure", diverted: "diversion" };

/** How to say that something told has ended, or goes on, from the title it was told with
 * ("Jubilee line: minor delays", "Canary Wharf closed (Elizabeth line)"). */
export function words(alert: Alert, title: string): { ended: string; still: string } {
  for (const segment of alert.segments) {
    const prefix = `${segment.line.name}: `;
    if (!title.startsWith(prefix)) continue;
    const what = title.slice(prefix.length);
    const on = lineName(segment);
    if (/delays$|^issues reported$/.test(what)) return { ended: `${capital(what)} on ${on} have cleared.`, still: `${capital(what)} on ${on} continue.` };
    if (/^(?:no service|not running|no trains)$/.test(what)) return { ended: `${capital(on)} is running again.`, still: `${capital(on)} is still not running.` };
    // TfL's "Bus Service": buses run in place of trains.
    if (what === "bus service") return { ended: `The replacement bus service on ${on} has ended; trains are running again.`, still: `Replacement buses are still running on ${on}.` };
    const noun = NOUNS[what] ?? what;
    return { ended: `The ${noun} on ${on} has ended.`, still: `The ${noun} on ${on} continues.` };
  }
  const station = title.match(/^(.+?) (part closed|closed) \((.+)\)$/);
  if (station) {
    const [, name, how, line] = station;
    return how === "closed"
      ? { ended: `${name} has reopened (${line}).`, still: `${name} is still closed (${line}).` }
      : { ended: `${name} is no longer part closed (${line}).`, still: `${name} is still part closed (${line}).` };
  }
  return { ended: `Ended: ${title}.`, still: `Still on: ${title}.` };
}

const lineName = (s: Segment) => (s.mode === "national-rail" ? s.line.name : `the ${s.line.name}`);
const capital = (text: string) => text.charAt(0).toUpperCase() + text.slice(1);

export function plannedPush(alert: Alert, issues: Issue[], now: number): Push {
  const body = issues.map((i) => i.detail || i.title).join("\n\n");
  return {
    payload: {
      aps: {
        alert: {
          title: alert.name,
          subtitle: issues.length === 1 ? `Planned: ${issues[0].title.replace(/^.*?: /, "")} tomorrow` : `${issues.length} planned works tomorrow`,
          body: trim(body, MAX_BODY),
        },
        sound: "default",
        "interruption-level": "active",
        "relevance-score": 0.6,
        "thread-id": alert.id,
      },
      alert: alert.id,
    },
    priority: 10,
    collapseId: collapse(alert.id, "-planned"),
    expiration: Math.floor(now / 1000) + 12 * 3600,
  };
}

/** An alert's ID with a suffix, within APNs' 64 bytes for a collapse ID (IDs are up to 64 characters
 * of ASCII; the app's are 36). */
export function collapse(id: string, suffix: string): string {
  return `${id.slice(0, 64 - suffix.length)}${suffix}`;
}

/** "the Elizabeth line and the Central line"; train operators go without "the". */
export function lineList(alert: Alert): string {
  const names = [...new Set(alert.segments.map(lineName))];
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names.at(-1)}` : names[0] ?? "your lines";
}

function trim(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1).trimEnd()}…`;
}

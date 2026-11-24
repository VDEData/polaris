// What each notification says. Closures and delays are Time Sensitive, so they come
// through a Focus; minor delays and planned works are ordinary; "good service again" is quiet.

import type { Alert, Issue } from "./types.ts";

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

export function clearPush(alert: Alert, now: number): Push {
  return {
    payload: {
      aps: {
        alert: { title: alert.name, body: `Good service again on ${lineList(alert)}.` },
        "interruption-level": "passive",
        "relevance-score": 0.2,
        "thread-id": alert.id,
      },
      alert: alert.id,
    },
    priority: 5,
    collapseId: alert.id,
    expiration: Math.floor(now / 1000) + 3600,
  };
}

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
    collapseId: `${alert.id}-planned`,
    expiration: Math.floor(now / 1000) + 12 * 3600,
  };
}

/** "the Elizabeth line and the Central line"; train operators go without "the". */
export function lineList(alert: Alert): string {
  const names = [...new Set(alert.segments.map((s) => (s.mode === "national-rail" ? s.line.name : `the ${s.line.name}`)))];
  return names.length > 1 ? `${names.slice(0, -1).join(", ")} and ${names.at(-1)}` : names[0] ?? "your lines";
}

function trim(text: string, max: number): string {
  return text.length <= max ? text : `${text.slice(0, max - 1).trimEnd()}…`;
}

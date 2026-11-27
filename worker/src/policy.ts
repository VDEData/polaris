// When to tell you: once per disruption, again if it gets worse, and that it has ended, in the
// words you were told, when it ends (followed for a while after your time, too). Pure, so the
// rules are tested without a clock or a phone.

import { rank } from "./match.ts";
import type { Direction, Issue, Severity } from "./types.ts";

export interface AlertState {
  /** Disruptions seen, by signature: how bad, when last seen, and the ways you were told it is
   * on (absent: every way, as before times had their own ways). */
  live: Record<string, { severity: Severity; seen: number; ways?: Direction[] }>;
  /** When we last told you about trouble, until it clears. */
  open?: number;
  /** The first check that found nothing, while `open` (state from before `said`). */
  quietSince?: number;
  /** The disruptions in the notices sent that haven't been told ended, by signature, with the
   * words they were told in. Absent in state stored before it: then the old "good service
   * again" rules apply. */
  said?: Record<string, Told>;
  /** Told ended, by signature, with when: one back soon after is a flapping line. */
  fixed?: Record<string, number>;
  /** When following began after your time ended, to tell you when it ends. */
  following?: number;
  /** What the alert matched when this was decided (its stretches, ways and minor delays), so an
   * edit that changes it settles what it no longer matches quietly. */
  matches?: string;
}

export interface Told {
  /** As the notice said it: "Jubilee line: minor delays". */
  title: string;
  /** When a notice told it (absent in state from before it was kept). */
  at?: number;
  /** How bad the notice said it was; kept when TfL's words flip without a notice. */
  severity?: Severity;
  /** The first check it wasn't there. */
  gone?: number;
  /** Back soon after it was told ended: told ended again only after a longer quiet. */
  shaky?: boolean;
  /** Last seen while followed after a time. Kept apart from `live`, so a sighting outside your
   * times doesn't count as told for the next time. */
  watched?: number;
}

/** What else a check knows. */
export interface Context {
  /** Places ("line:jubilee") asked of TfL that its answer left out: unknown, so nothing there ends. */
  unknown?: ReadonlySet<string>;
  /** TfL reports nothing at all wrong on the alert's lines (and stations, when asked): only then
   * is it "good service again". Default true. */
  calm?: boolean;
}

export interface DeviceState {
  alerts: Record<string, AlertState>;
  /** Planned works already told, by alert and signature, with when. */
  planned: Record<string, number>;
  /** Local date of the last evening check for tomorrow. */
  plannedOn?: string;
  /** Pushes APNs couldn't take for a passing reason (busy, rate limited, network), by what they
   * were for (an alert id, or "planned:" and one): tried again next run, up to `MAX_RETRIES`. */
  retries?: Record<string, number>;
}

export const MAX_RETRIES = 3;

/** "clear": what has ended (`ended`, in the words told; empty for state from before `said`),
 * what told still goes on (`still`), and whether nothing at all is wrong now (`all`). */
export type Decision = { kind: "issues"; issues: Issue[] } | { kind: "clear"; ended: string[]; still: string[]; all: boolean };

const MINUTE = 60_000;
// A line's status overnight says nothing about whether this morning's is the same incident.
const LINE_MEMORY = 90 * MINUTE;
// Station works run for weeks; don't repeat them every morning.
const STATION_MEMORY = 7 * 24 * 60 * MINUTE;
// TfL flips a line between "minor delays" and "good service" for a few minutes at a time.
const FLAP_GUARD = 30 * MINUTE;
const QUIET_BEFORE_CLEAR = 4 * MINUTE;
// State from before `said` only: "good service again" if told this recently.
const CLEAR_WITHIN = 3 * 60 * MINUTE;
/** After your time ends, how long a disruption told is followed to tell you it has ended. */
export const FOLLOW_FOR = 3 * 60 * MINUTE;
const PLANNED_MEMORY = 14 * 24 * 60 * MINUTE;
// Cron runs come a few seconds after their mark: a span is judged to the minute, so a run 3 s
// late or early lands on the same side of every rule.
const SLACK = MINUTE;
/** At least `span` has passed from `from` to `to`. */
const passed = (from: number, to: number, span: number) => to - from >= span - SLACK;
/** No more than `span` has passed. */
const within = (from: number, to: number, span: number) => to - from <= span + SLACK;

/** What a check knows about ways, for an alert whose times go one way or the other. */
export interface Ways {
  /** The way ridden at this check ("both" when times going different ways overlap). */
  way: Direction;
  /** For each disruption on your stretch either way, the ways it is on. */
  on: ReadonlyMap<string, Direction[]>;
  /** Some time of this alert goes the other way: trouble told now only the other way stays open. */
  holds: boolean;
  /** What memory from before ways were kept counts as told: the alert's own way. */
  told: Direction[];
}

const BOTH: Direction[] = ["out", "back"];

/** One check during a time you travel. With times going different ways (mornings one way,
 * evenings the other), a disruption is told again when it is on a way you ride now and haven't
 * been told about (it spread, or you ride a way it wasn't on before); the ways told are what was
 * pushed. One-way trouble told that is now only the other way is remembered, and isn't "good
 * service again" while it lasts, if some time of the alert goes that way. Without `ways`, as before. */
export function step(previous: AlertState | undefined, issues: Issue[], now: number, ways?: Ways, context: Context = {}): { state: AlertState; push?: Decision } {
  const prev = previous ?? { live: {} };
  const known = remembered(prev, now);

  const rides = !ways || ways.way === "both" ? BOTH : [ways.way];
  // The ways a disruption of yours is on that you ride now.
  const yours = (signature: string) => {
    const on = (ways?.on.get(signature) ?? rides).filter((d) => rides.includes(d));
    return on.length ? on : rides;
  };
  const told = (entry: { ways?: Direction[] }) => entry.ways ?? ways?.told ?? BOTH;
  const untold = (signature: string) => !!ways && yours(signature).some((d) => !told(known[signature]).includes(d));
  // What the notices said. State from before `said`, still disrupted: the same disruption has
  // the same words, so what is still there is what was told.
  let said = prev.said;
  if (!said && prev.open !== undefined) {
    const still = issues.filter((i) => known[i.signature]);
    if (still.length) said = Object.fromEntries(still.map((i) => [i.signature, { title: i.title }]));
  }
  const fixed = recent(prev.fixed, now);
  // Back at a place a notice still standing tells, as it was there lately (severe and minor
  // delays taking turns, a list of stations that changes and changes back): TfL flipping, told already.
  const standing = (i: Issue) => Object.keys(said ?? {}).filter((s) => place(s) === place(i.signature));
  const recentlySeen = (i: Issue) => !!known[i.signature] && within(known[i.signature].seen, now, FLAP_GUARD);
  // How bad the standing notice says that place is.
  const toldRank = (i: Issue) => Math.max(...standing(i).map((s) => rank(said![s].severity ?? known[s]?.severity ?? "closed")));
  const flipped = (i: Issue) => !!said && recentlySeen(i) && standing(i).length > 0 && rank(i.severity) <= toldRank(i);
  // Back to worse than the notice standing says (severe, eased to minor, severe again): news, but
  // once in half an hour at most for that place, or a line taking turns would buzz at every turn.
  const worseAgain = (i: Issue) => !!said && recentlySeen(i) && standing(i).length > 0 && rank(i.severity) > toldRank(i);
  const lastTold = (i: Issue) => Math.max(...standing(i).map((s) => said![s].at ?? -Infinity));
  // Not in a notice still standing: told ended, or never told.
  const fresh = (i: Issue) => !!said && !(i.signature in said) && !flipped(i) && (!worseAgain(i) || passed(lastTold(i), now, FLAP_GUARD));
  const news = issues.some((i) => !known[i.signature] || rank(i.severity) > rank(known[i.signature].severity) || untold(i.signature) || fresh(i));
  const pushing = issues.length > 0 && (news || prev.open === undefined);

  const live: AlertState["live"] = {};
  for (const issue of issues) {
    const seen = known[issue.signature];
    // Ways told grow only with a push, by every way the disruption is on then (the notice says so);
    // without ways, nothing is recorded, as before.
    const kept = !ways ? seen?.ways : pushing ? [...new Set([...(seen ? told(seen) : []), ...(ways.on.get(issue.signature) ?? rides)])] : seen?.ways;
    live[issue.signature] = kept ? { severity: issue.severity, seen: now, ways: kept } : { severity: issue.severity, seen: now };
  }
  // Told before, still there, only the other way now: remembered, if a time of the alert goes that way.
  const away = new Set<string>();
  if (ways?.holds) {
    for (const signature of ways.on.keys()) {
      if (!known[signature] || signature in live) continue;
      live[signature] = { ...known[signature], seen: now };
      away.add(signature);
    }
  }
  keep(known, live, said, context, now);
  const extra = { ...(Object.keys(fixed).length ? { fixed } : {}) };

  if (pushing) {
    // The new notice says what is wrong now. One told before that has just gone stays to be told
    // ended, unless the notice tells its place anew (severe delays eased to minor on that line).
    const next: Record<string, Told> = {};
    for (const issue of issues) {
      const shaky = issue.signature in fixed || said?.[issue.signature]?.shaky;
      next[issue.signature] = shaky ? { title: issue.title, at: now, severity: issue.severity, shaky: true } : { title: issue.title, at: now, severity: issue.severity };
    }
    for (const [signature, entry] of Object.entries(said ?? {})) {
      if (signature in next || !(signature in known)) continue;
      if (away.has(signature) || context.unknown?.has(place(signature))) next[signature] = { ...entry, gone: away.has(signature) ? undefined : entry.gone };
      else if (!issues.some((i) => place(i.signature) === place(signature))) next[signature] = { ...entry, gone: entry.gone ?? now };
    }
    return { state: { live, open: now, said: tidy(next), ...extra }, push: { kind: "issues", issues } };
  }

  if (prev.open === undefined) return { state: { live, ...extra } };

  if (!said) {
    // State from before `said`, as it was; a line TfL left out isn't good service.
    if (issues.length || away.size || context.unknown?.size) return { state: { live, open: prev.open, ...(prev.quietSince !== undefined ? { quietSince: prev.quietSince } : {}) } };
    const quietSince = prev.quietSince ?? now;
    if (!passed(quietSince, now, QUIET_BEFORE_CLEAR)) return { state: { live, open: prev.open, quietSince } };
    return { state: { live }, push: within(prev.open, now, CLEAR_WITHIN) ? { kind: "clear", ended: [], still: [], all: context.calm ?? true } : undefined };
  }

  // A flip back to words told lately at the same place: the notice standing tells that place,
  // now in these words, so it ends in them.
  const current: Record<string, Told> = { ...said };
  for (const issue of issues) {
    if (issue.signature in current || !flipped(issue)) continue;
    const before = Object.entries(current).filter(([s]) => place(s) === place(issue.signature) && !issues.some((i) => i.signature === s));
    for (const [s] of before) delete current[s];
    const at = Math.max(...before.map(([, t]) => t.at ?? -Infinity));
    // What the notice said stays what was told: the worst of what it replaces.
    const told = before.map(([s, t]) => t.severity ?? known[s]?.severity).filter((x): x is Severity => !!x);
    const severity = told.length ? told.reduce((a, b) => (rank(b) > rank(a) ? b : a)) : undefined;
    current[issue.signature] = { title: issue.title, ...(Number.isFinite(at) ? { at } : {}), ...(severity ? { severity } : {}), ...(before.some(([, t]) => t.shaky) ? { shaky: true } : {}) };
  }

  const result = settle(current, known, live, issues, away, fixed, now, prev.open, context, false);
  // Followed once after a time for this notice: not again after the next time, or weeks-long
  // station works told once would be followed after every time, every day.
  if (result.state.open !== undefined && prev.following !== undefined) result.state.following = prev.following;
  return result;
}

/** After your time ends, while a disruption told is followed: only whether it has ended. New
 * trouble, or worse, isn't told outside your times, nor remembered as told, and sightings don't
 * count as told for your next time (that's `Told.watched`). */
export function follow(previous: AlertState, issues: Issue[], now: number, context: Context = {}): { state: AlertState; push?: Decision } {
  const known = remembered(previous, now);
  const said = previous.said ?? {};
  const live: AlertState["live"] = {};
  keep(known, live, said, context, now);
  const result = settle(said, known, live, issues, new Set(), recent(previous.fixed, now), now, previous.open, context, true);
  if (result.state.open !== undefined) result.state.following = previous.following ?? now;
  return result;
}

/** The alert was changed so it no longer covers some of what was told (a line taken off, minor
 * delays turned off): those are settled quietly, not told ended. */
export function forget(state: AlertState | undefined, covers: (signature: string, severity?: Severity) => boolean): AlertState | undefined {
  if (!state?.said) return state;
  const said = Object.fromEntries(Object.entries(state.said).filter(([s]) => covers(s, state.live[s]?.severity)));
  if (Object.keys(said).length === Object.keys(state.said).length) return state;
  if (Object.keys(said).length) return { ...state, said };
  const { open: _open, following: _following, ...rest } = state;
  return { ...rest, said };
}

/** Whether to keep checking an alert outside its times: something told is open and hasn't been
 * told ended, and following began less than `FOLLOW_FOR` ago (or the time has only just ended).
 * Once per notice: following isn't begun again after a later time until a new notice. */
export function following(state: AlertState | undefined, now: number): boolean {
  if (!state?.said || state.open === undefined || !Object.keys(state.said).length) return false;
  if (state.following !== undefined) return within(state.following, now, FOLLOW_FOR);
  const seen = Math.max(...Object.keys(state.said).map((s) => state.live[s]?.seen ?? 0));
  return within(seen, now, FLAP_GUARD);
}

/** Remembered, though not seen now: for a while (TfL flickers), and for as long as it is told
 * and not yet settled. */
function keep(known: AlertState["live"], live: AlertState["live"], said: Record<string, Told> | undefined, context: Context, now: number) {
  for (const [signature, entry] of Object.entries(known)) {
    if (signature in live) continue;
    if (within(entry.seen, now, FLAP_GUARD) || (said && signature in said) || context.unknown?.has(place(signature))) live[signature] = entry;
  }
}

/** What has ended of what was told, and the notice for it. */
function settle(said: Record<string, Told>, known: AlertState["live"], live: AlertState["live"], issues: Issue[], away: Set<string>, fixed: Record<string, number>, now: number, open: number | undefined, context: Context, watching: boolean): { state: AlertState; push?: Decision } {
  const left: Record<string, Told> = {};
  const ended: string[] = [];
  for (const [signature, entry] of Object.entries(said)) {
    // TfL's answer left its line out: as it was.
    // TfL's answer left its line out: as it was, and the time unknown doesn't count against it.
    if (context.unknown?.has(place(signature))) {
      left[signature] = { ...entry, watched: now };
      continue;
    }
    if (away.has(signature) || stillThere(signature, said, issues)) {
      left[signature] = watching ? { ...entry, gone: undefined, watched: now } : { ...entry, gone: undefined, watched: undefined };
      continue;
    }
    const seen = Math.max(known[signature]?.seen ?? -Infinity, entry.watched ?? -Infinity);
    const gone = entry.gone ?? now;
    // Not watched as it ended (last seen long before it was found gone: overnight, or after
    // following stopped): settled without a notice. Yesterday's delays needn't be undone at breakfast.
    if (!Number.isFinite(seen) || !within(seen, gone, FLAP_GUARD)) continue;
    // Found gone, then not watched long enough to say so (following stopped): not told hours later.
    if (!within(gone, now, (entry.shaky ? FLAP_GUARD : 0) + FLAP_GUARD)) continue;
    if (passed(gone, now, entry.shaky ? FLAP_GUARD : QUIET_BEFORE_CLEAR)) {
      ended.push(entry.title);
      fixed[signature] = now;
      continue;
    }
    left[signature] = { ...entry, gone };
  }
  const still = Object.values(left).filter((t) => t.gone === undefined).map((t) => t.title);
  const rest = tidy(left);
  const state: AlertState = Object.keys(rest).length ? { live, open, said: rest } : { live };
  if (Object.keys(fixed).length) state.fixed = fixed;
  if (!ended.length) return { state };
  const all = !issues.length && !away.size && !Object.keys(rest).length && !context.unknown?.size && (context.calm ?? true);
  return { state, push: { kind: "clear", ended, still, all } };
}

/** Told and still there: itself, or (for trouble that changed its words without a notice, as
 * outside your times) something new in the same place. */
function stillThere(signature: string, said: Record<string, Told>, issues: Issue[]): boolean {
  return issues.some((i) => i.signature === signature || (!(i.signature in said) && place(i.signature) === place(signature)));
}

/** "line:jubilee" or "stop:940GZZLUCYF": where a disruption is, whatever it is. */
export const place = (signature: string) => signature.split(":").slice(0, 2).join(":");

function remembered(prev: AlertState, now: number): AlertState["live"] {
  const known: AlertState["live"] = {};
  for (const [signature, entry] of Object.entries(prev.live ?? {})) {
    const memory = signature.startsWith("stop:") ? STATION_MEMORY : LINE_MEMORY;
    if (within(entry.seen, now, memory)) known[signature] = entry;
  }
  return known;
}

function recent(fixed: Record<string, number> | undefined, now: number): Record<string, number> {
  return Object.fromEntries(Object.entries(fixed ?? {}).filter(([, at]) => within(at, now, FLAP_GUARD)));
}

/** Without empty keys, so the stored state stays small. */
function tidy(said: Record<string, Told>): Record<string, Told> {
  return Object.fromEntries(Object.entries(said).map(([k, t]) => [k, Object.fromEntries(Object.entries(t).filter(([, v]) => v !== undefined && v !== false)) as unknown as Told]));
}

/** The evening before: planned works on tomorrow's trips you haven't been told about. */
export function plannedStep(planned: Record<string, number>, alertId: string, issues: Issue[], now: number): { planned: Record<string, number>; fresh: Issue[] } {
  const next: Record<string, number> = {};
  for (const [key, at] of Object.entries(planned)) if (now - at <= PLANNED_MEMORY) next[key] = at;
  const fresh = issues.filter((i) => !(`${alertId}|${i.signature}` in next));
  for (const issue of issues) next[`${alertId}|${issue.signature}`] ??= now;
  return { planned: next, fresh };
}

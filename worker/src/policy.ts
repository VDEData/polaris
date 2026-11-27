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
  /** What the phone shows: the newest notice's disruptions as on now (an ended notice: what it
   * says goes on), as how bad each place is (`rank`), by place ("line:jubilee"). */
  shown?: Record<string, number>;
  /** What the server before this change would have on the phone, followed alongside: its open
   * notice, its quiet "good service again" and what its newest notice shows. Whatever that would
   * show, this shows too. */
  main?: MainShadow;
  /** When the last ended notice that sounded went out: another within half an hour is quiet. */
  endedLoud?: number;
}

export interface MainShadow {
  open?: number;
  quiet?: number;
  /** Its quiet "good service again" was due at this run, but a run a few seconds late (as it
   * reads the clock) puts it at the next: until then, it may or may not have cleared. */
  maybe?: boolean;
  shown: Record<string, number>;
  /** The ways it told each disruption, as it would have recorded them. */
  ways?: Record<string, Direction[]>;
}

export interface Told {
  /** As the notice said it: "Jubilee line: minor delays". */
  title: string;
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
  const shown = prev.shown ?? showing(prev, known);
  const main = shadow(prev, issues, known, now, ways, shown);
  // Whatever the server before this change would now show, this phone shows too: a disruption it
  // would show at this severity or worse that this phone's newest notice doesn't is news.
  const mustTell = issues.some((i) => (main.shown[place(i.signature)] ?? 0) >= rank(i.severity) && (shown[place(i.signature)] ?? 0) < rank(i.severity));
  const result = decide(previous, issues, now, ways, context, mustTell);
  let next = shown;
  if (result.push?.kind === "issues") next = ranks(result.push.issues.map((i) => [i.signature, i.severity]));
  else if (result.push?.kind === "clear") next = ranks(Object.entries(result.state.said ?? {}).filter(([, t]) => t.gone === undefined).map(([s]) => [s, result.state.live[s]?.severity ?? known[s]?.severity ?? "minor"]));
  result.state.shown = next;
  result.state.main = main;
  return result;
}

/** How bad the phone shows each place, from what it shows. */
function ranks(items: [string, Severity][]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [signature, severity] of items) out[place(signature)] = Math.max(out[place(signature)] ?? 0, rank(severity));
  return out;
}

/** State from before `shown` was kept: what the notice standing tells, as well as is known: what
 * was on when it was sent (severe delays eased to minor were told as minor). */
function showing(prev: AlertState, known: AlertState["live"]): Record<string, number> {
  if (prev.open === undefined) return {};
  const told = prev.said ? Object.keys(prev.said) : Object.keys(known);
  return ranks(told.filter((s) => known[s] && known[s].seen >= prev.open! - MINUTE).map((s) => [s, known[s].severity]));
}

/** The server before this change, run alongside on the same memory: when it would push, what it
 * would show, and its quiet "good service again" (which shows nothing). */
function shadow(prev: AlertState, issues: Issue[], known: AlertState["live"], now: number, ways: Ways | undefined, shown: Record<string, number>): MainShadow {
  const before: MainShadow = prev.main ?? { open: prev.open, quiet: prev.quietSince, shown };
  const toldWays = before.ways ?? {};
  // Cleared at the last run, or not: with trouble back now, it shows the worse of its old notice
  // and a new one; with none, it has cleared by now either way.
  if (before.maybe) {
    const kept = before.ways ? { ways: prune(before.ways, known, issues) } : {};
    if (!issues.length) return { shown: {}, ...kept };
    const now_ = ranks(issues.map((i) => [i.signature, i.severity]));
    const worse: Record<string, number> = { ...before.shown };
    for (const [k, v] of Object.entries(now_)) worse[k] = Math.max(worse[k] ?? 0, v);
    return { open: now, shown: worse, ...kept };
  }
  const rides = !ways || ways.way === "both" ? BOTH : [ways.way];
  const yours = (signature: string) => {
    const on = (ways?.on.get(signature) ?? rides).filter((d) => rides.includes(d));
    return on.length ? on : rides;
  };
  const told = (signature: string) => toldWays[signature] ?? known[signature]?.ways ?? ways?.told ?? BOTH;
  const untold = (signature: string) => !!ways && yours(signature).some((d) => !told(signature).includes(d));
  const news = issues.some((i) => !known[i.signature] || rank(i.severity) > rank(known[i.signature].severity) || untold(i.signature));
  if (issues.length && (news || before.open === undefined)) {
    const nextWays: Record<string, Direction[]> = { ...toldWays };
    if (ways) for (const i of issues) nextWays[i.signature] = [...new Set([...(known[i.signature] ? told(i.signature) : []), ...(ways.on.get(i.signature) ?? rides)])];
    return { open: now, shown: ranks(issues.map((i) => [i.signature, i.severity])), ...(ways ? { ways: prune(nextWays, known, issues) } : {}) };
  }
  const keepWays = before.ways ? { ways: prune(before.ways, known, issues) } : {};
  if (issues.length || before.open === undefined) return { open: before.open, shown: before.shown, ...keepWays };
  // Nothing on: still there the other way, if a time goes that way; else, after a few quiet
  // minutes, its quiet "good service again".
  if (ways?.holds && [...ways.on.keys()].some((s) => known[s])) return { open: before.open, shown: before.shown, ...keepWays };
  const quiet = before.quiet ?? now;
  if (now - quiet < QUIET_BEFORE_CLEAR) return { open: before.open, quiet, shown: before.shown, ...keepWays };
  if (now - quiet < QUIET_BEFORE_CLEAR + MINUTE) return { open: before.open, quiet, maybe: true, shown: before.shown, ...keepWays };
  return { shown: {}, ...keepWays };
}

/** Ways told, for what is still remembered. */
function prune(ways: Record<string, Direction[]>, known: AlertState["live"], issues: Issue[]): Record<string, Direction[]> {
  return Object.fromEntries(Object.entries(ways).filter(([s]) => s in known || issues.some((i) => i.signature === s)));
}

function decide(previous: AlertState | undefined, issues: Issue[], now: number, ways: Ways | undefined, context: Context, mustTell: boolean): { state: AlertState; push?: Decision } {
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
  // Remembered (`live` is kept exactly as main keeps it), at a place a notice still stands for,
  // and not told ended lately: back after "cleared", it is told again.
  // Back after "cleared" but milder than a told disruption still standing at that place (still
  // on, and remembered): a flip, as main sees it, so the worse notice stays the newest.
  const milder = (i: Issue) => standing(i).some((s) => !!known[s] && said![s].gone === undefined && rank(known[s].severity) > rank(i.severity));
  const flipped = (i: Issue) => !!said && !!known[i.signature] && standing(i).length > 0 && (!(i.signature in fixed) || milder(i));
  // Not in a notice still standing: told ended, or never told.
  const fresh = (i: Issue) => !!said && !(i.signature in said) && !flipped(i);
  // News as main judges it, plus what isn't in a notice still standing.
  const news = issues.some((i) => !known[i.signature] || rank(i.severity) > rank(known[i.signature].severity) || untold(i.signature) || fresh(i));
  const pushing = issues.length > 0 && (news || prev.open === undefined || mustTell);

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
      // Only what a notice still standing told: once told ended, it is new trouble when it is
      // back on your way.
      if (!known[signature] || signature in live || (said && !(signature in said))) continue;
      live[signature] = { ...known[signature], seen: now };
      away.add(signature);
    }
  }
  keep(known, live, now);
  if (pushing) {
    // The new notice says what is wrong now. One told before that has just gone stays to be told
    // ended, unless the notice tells its place anew (severe delays eased to minor on that line).
    const next: Record<string, Told> = {};
    for (const issue of issues) {
      const shaky = issue.signature in fixed || said?.[issue.signature]?.shaky;
      next[issue.signature] = shaky ? { title: issue.title, shaky: true } : { title: issue.title };
      // Told again: its return after "cleared" is told, once (the notice now carries it as shaky).
      delete fixed[issue.signature];
    }
    for (const [signature, entry] of Object.entries(said ?? {})) {
      if (signature in next) continue;
      // Its line left out of TfL's answer: kept as it was, remembered or not, to tell its end.
      if (context.unknown?.has(place(signature))) { next[signature] = entry; continue; }
      if (!(signature in known)) continue;
      if (away.has(signature)) next[signature] = { ...entry, gone: undefined };
      else if (!issues.some((i) => place(i.signature) === place(signature))) next[signature] = { ...entry, gone: entry.gone ?? now };
    }
    return { state: { live, open: now, said: tidy(next), ...(Object.keys(fixed).length ? { fixed } : {}) }, push: { kind: "issues", issues } };
  }
  const extra = { ...(Object.keys(fixed).length ? { fixed } : {}) };

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
    current[issue.signature] = { title: issue.title, ...(before.some(([, t]) => t.shaky) ? { shaky: true } : {}) };
  }

  const result = settle(current, known, live, issues, away, fixed, now, prev.open, context);
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
  // Outside your times main doesn't check, so its memory stays as it was.
  const live: AlertState["live"] = { ...known };
  const result = settle(said, known, live, issues, new Set(), recent(previous.fixed, now), now, previous.open, context);
  if (result.state.open !== undefined) result.state.following = previous.following ?? now;
  // The server before this change didn't check outside your times: its phone stays as it was.
  if (previous.main) result.state.main = previous.main;
  const shown = previous.shown ?? showing(previous, known);
  result.state.shown = result.push?.kind === "clear"
    ? ranks(Object.entries(result.state.said ?? {}).filter(([, t]) => t.gone === undefined).map(([s]) => [s, known[s]?.severity ?? "minor"]))
    : shown;
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

/** Remembered, though not seen now, for a while (TfL flickers): exactly as main, so what is news
 * is judged as main judges it. What a notice told is watched until it is settled in `said`
 * (`Told.watched`), not here. */
function keep(known: AlertState["live"], live: AlertState["live"], now: number) {
  for (const [signature, entry] of Object.entries(known)) {
    if (!(signature in live) && now - entry.seen <= FLAP_GUARD) live[signature] = entry;
  }
}

/** What has ended of what was told, and the notice for it. */
function settle(said: Record<string, Told>, known: AlertState["live"], live: AlertState["live"], issues: Issue[], away: Set<string>, fixed: Record<string, number>, now: number, open: number | undefined, context: Context): { state: AlertState; push?: Decision } {
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
      left[signature] = { ...entry, gone: undefined, watched: now };
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
  // An ended notice takes the place of the one standing: it settles too every told disruption gone
  // the usual few minutes, flapping or not, so that one back soon after is told (once) rather
  // than hidden behind the milder notice.
  if (ended.length) {
    for (const [signature, entry] of Object.entries(left)) {
      if (entry.gone === undefined || context.unknown?.has(place(signature)) || !passed(entry.gone, now, QUIET_BEFORE_CLEAR)) continue;
      ended.push(entry.title);
      fixed[signature] = now;
      delete left[signature];
    }
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

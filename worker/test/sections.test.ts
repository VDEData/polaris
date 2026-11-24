import assert from "node:assert/strict";
import { test } from "node:test";
import { clauses } from "../src/clauses.ts";
import { heading, keyFor, parseLineMap, place, type LineMap } from "../src/linemap.ts";
import { issuesFor, issuesInRanges, linesToPlace, type LineMaps } from "../src/match.ts";
import { issuesPush } from "../src/push.ts";
import { parseLineStatuses, type LineStatus } from "../src/tfl.ts";
import type { Alert, Direction, Segment } from "../src/types.ts";
import { alert, contract, fixture } from "./helpers.ts";

const elizabeth = parseLineMap("elizabeth", fixture("tfl-sequence-elizabeth.json"))!;
const district = parseLineMap("district", fixture("tfl-sequence-district.json"))!;
const northern = parseLineMap("northern", fixture("tfl-sequence-northern.json"))!;
const maps: LineMaps = new Map([["elizabeth", elizabeth], ["district", district], ["northern", northern]]);
/** Recorded at 18:27 on Monday 23 November 2026. */
const AT = Date.parse("2026-11-23T18:27:00Z");
const today = parseLineStatuses(fixture("tfl-line-status-2026-11-23.json"));
const weekend = parseLineStatuses(fixture("tfl-line-status-weekend.json"));
const SATURDAY = { ranges: [{ from: Date.parse("2026-11-28T08:00:00Z"), to: Date.parse("2026-11-28T10:00:00Z") }] };

const LINES: Record<string, [LineMap, string, string]> = {
  elizabeth: [elizabeth, "Elizabeth line", "elizabeth-line"],
  district: [district, "District line", "tube"],
  northern: [northern, "Northern line", "tube"],
};

/** The stretch you ride, as the app saves it: each station's ids along one of the line's routes. */
function ride(lineId: string, from: string, to: string, via?: string): Segment {
  const [map, name, mode] = LINES[lineId];
  const a = [...place(map, from)][0], b = [...place(map, to)][0], v = via ? [...place(map, via)][0] : undefined;
  assert.ok(a && b, `${from} and ${to} are on the ${name}`);
  const route = map.routes.find((r) => r.includes(a) && r.includes(b) && (!v || r.includes(v)))!;
  const i = route.indexOf(a), j = route.indexOf(b);
  const keys = i < j ? route.slice(i, j + 1) : route.slice(j, i + 1).reverse();
  const ids = (key: string) => map.places.get(key)!.ids.filter((id) => !id.startsWith("HUB")).join("|");
  return { line: { id: lineId, name }, mode, from: { id: ids(a), name: from }, to: { id: ids(b), name: to }, stations: keys.map(ids) };
}

const going = (segments: Segment[], direction?: Direction, extra: Partial<Alert> = {}) => alert(segments, { direction, ...extra });

/** A line status as TfL sends it with no stations listed, as the Elizabeth line's often is. */
function status(lineId: string, severity: number, reason: string, stops: string[] = []): LineStatus {
  const summary = ({ 3: "Part Suspended", 5: "Part Closure", 6: "Severe Delays", 9: "Minor Delays" } as Record<number, string>)[severity] ?? "Severe Delays";
  return { lineId, lineName: lineId, severity, summary, reason, planned: false, periods: [], stops };
}

// An Elizabeth line notice of the kind TfL sends. It is quoted, not
// recorded, so it is built both ways TfL sends such a notice: one status at the worst severity,
// or one per severity sharing the text.
const NOTICE = "Elizabeth line: Severe delays between Paddington and Heathrow Terminals, and MINOR DELAYS between Hayes & Harlington and Reading, and between Whitechapel and Shenfield due to an earlier signal failure. GOOD SERVICE on the rest of the line.";
const asOne = [status("elizabeth", 6, NOTICE)];
const asTwo = [status("elizabeth", 6, NOTICE), status("elizabeth", 9, NOTICE)];
const summary = (issues: ReturnType<typeof issuesFor>) => issues.map((i) => [i.title, i.severity, i.detail]);

test("the line's stations in order: branches, hubs and TfL's spellings", () => {
  assert.equal(elizabeth.routes.length, 19);
  assert.deepEqual([...place(elizabeth, "Paddington")], ["HUBPAD"]);
  assert.ok(elizabeth.places.get("HUBPAD")!.ids.includes("910GPADTLL") && elizabeth.places.get("HUBPAD")!.ids.includes("910GPADTON"), "both Paddingtons are one station");
  assert.equal(place(elizabeth, "Heathrow Terminals").size, 3, "Heathrow Terminals means all three");
  assert.equal(place(elizabeth, "Heathrow (all terminals)").size, 3);
  assert.equal(place(elizabeth, "Hayes & Harlington").size, 1);
  assert.equal(place(elizabeth, "Nowhere Junction").size, 0);
  assert.equal(place(district, "Edgware Road").size, 1, "the District's Edgware Road (Circle Line)");
  assert.equal(keyFor(elizabeth, "910GLIVSTLL|910GLIVST"), keyFor(elizabeth, "910GLIVST"));
});

test("TfL's words taken apart: sections, severities, one way", () => {
  assert.deepEqual(clauses(NOTICE).map((c) => [c.family, c.sections.map((s) => `${s.from.join("/")}–${s.to.join("/")}`)]), [
    ["delays", ["Paddington–Heathrow Terminals"]],
    ["minor", ["Hayes & Harlington–Reading", "Whitechapel–Shenfield"]],
  ]);
  const central = clauses("Central Line: Minor delays between North Acton and Ealing Broadway / West Ruislip and between, Leytonstone and Epping / Hainault via Newbury Park due to train cancellations.");
  assert.deepEqual(central[0].sections.map((s) => [s.from, s.to, s.via]), [[["North Acton"], ["Ealing Broadway", "West Ruislip"], undefined], [["Leytonstone"], ["Epping", "Hainault"], "Newbury Park"]]);
  assert.deepEqual(clauses("Severe delays westbound between Stratford and Paddington.")[0].way, { compass: { axis: "ew", sign: -1 } });
  assert.deepEqual(clauses("Minor delays towards Heathrow due to a signal failure.")[0].way, { towards: ["Heathrow"] });
  assert.deepEqual(clauses("Minor delays on trains towards Battersea Power Station and Morden.")[0].way, { towards: ["Battersea Power Station", "Morden"] });
  assert.equal(clauses("Severe delays in both directions between Stratford and Paddington.")[0].way, undefined);
  // What still runs, and dates, aren't sections.
  assert.deepEqual(clauses("Part suspended. Trains run from Morden to Kennington only.")[0].sections, []);
  assert.deepEqual(clauses("Reduced service from Monday to Friday.")[0].sections, []);
});

test("The quoted notice: nothing on Liverpool Street to Canary Wharf, either way TfL sends it", () => {
  for (const lines of [asOne, asTwo]) {
    for (const direction of [undefined, "out", "back", "both"] as const) {
      assert.deepEqual(issuesFor({ ...contract().alerts[0], direction }, lines, [], { at: AT }, maps), [], `${lines.length} status(es), ${direction}`);
    }
  }
  // Whitechapel to Shenfield meets the stretch at Whitechapel only and runs off east: not on it.
  assert.deepEqual(linesToPlace([contract().alerts[0]], asOne, []), ["elizabeth"], "the line's order is fetched to tell");
});

test("The quoted notice: inside Paddington to Heathrow is severe; each clause at its own severity", () => {
  const ealing = going([ride("elizabeth", "Ealing Broadway", "West Ealing")]);
  for (const lines of [asOne, asTwo]) {
    assert.deepEqual(summary(issuesFor(ealing, lines, [], { at: AT }, maps)), [
      ["Elizabeth line: severe delays", "delays", "Severe delays between Paddington and Heathrow Terminals due to an earlier signal failure."],
    ]);
  }
  const westDrayton = going([ride("elizabeth", "Hayes & Harlington", "West Drayton")]);
  assert.deepEqual(summary(issuesFor(westDrayton, asTwo, [], { at: AT }, maps)), [
    ["Elizabeth line: minor delays", "minor", "Minor delays between Hayes & Harlington and Reading due to an earlier signal failure."],
  ]);
  assert.deepEqual(issuesFor(going([ride("elizabeth", "Hayes & Harlington", "West Drayton")], undefined, { minorDelays: false }), asTwo, [], { at: AT }, maps), []);
  const stratford = going([ride("elizabeth", "Stratford", "Maryland")]);
  assert.deepEqual(summary(issuesFor(stratford, asOne, [], { at: AT }, maps)).map((s) => s[2]), ["Minor delays between Whitechapel and Shenfield due to an earlier signal failure."]);
  // A stretch across both: one push, the worse first, with both clauses' words.
  const across = going([ride("elizabeth", "Ealing Broadway", "Hayes & Harlington")]);
  const issues = issuesFor(across, asTwo, [], { at: AT }, maps);
  assert.deepEqual(issues.map((i) => i.severity), ["delays"], "Hayes–Reading meets this stretch only at Hayes");
  const body = (issuesPush(across, issues, AT).payload.aps as any).alert.body as string;
  assert.ok(!/Whitechapel|Reading/.test(body), body);
});

test("one way only: only for alerts that go that way", () => {
  const east = [status("elizabeth", 6, "Severe delays eastbound between Paddington and Stratford due to a broken down train.")];
  const towards = [status("elizabeth", 9, "Minor delays towards Shenfield between Liverpool Street and Stratford.")];
  const both = [status("elizabeth", 6, "Severe delays in both directions between Paddington and Stratford.")];
  const commute = [ride("elizabeth", "Liverpool Street", "Stratford")];
  for (const [lines, label] of [[east, "eastbound"], [towards, "towards Shenfield"]] as const) {
    assert.equal(issuesFor(going(commute, "out"), lines, [], { at: AT }, maps).length, 1, `${label}: Liverpool Street to Stratford goes that way`);
    assert.deepEqual(issuesFor(going(commute, "back"), lines, [], { at: AT }, maps), [], `${label}: coming back doesn't`);
    assert.equal(issuesFor(going(commute, "both"), lines, [], { at: AT }, maps).length, 1, `${label}: both ways does`);
    assert.equal(issuesFor(going(commute), lines, [], { at: AT }, maps).length, 1, `${label}: an alert saved before directions is both ways`);
    assert.equal(issuesFor(going(commute, "back"), lines, [], { at: AT }).length, 1, `${label}: without the line's order, unsure, so tell`);
  }
  assert.equal(issuesFor(going(commute, "back"), both, [], { at: AT }, maps).length, 1);
  assert.deepEqual(linesToPlace([going(commute, "back")], [status("elizabeth", 6, "Severe delays westbound.", ["910GSTFD"])], []), ["elizabeth"]);
});

test("TfL's line directions, end to end along the line, and unsure where the ride bends away", () => {
  const key = (name: string) => [...place(district, name)][0];
  assert.equal(heading(district, key("Earl's Court"), key("West Kensington"), "ew"), -1);
  assert.equal(heading(district, key("Whitechapel"), key("Stepney Green"), "ew"), 1);
  assert.equal(heading(northern, key2("Kennington"), key2("Euston"), "ns"), 1);
  // The Wimbledon branch is "eastbound" towards Earl's Court, but runs north and a little west
  // there: unsure, so a one-way notice reaches both ways.
  assert.equal(heading(district, key("Southfields"), key("East Putney"), "ew"), 0);
  const east = [status("district", 6, "Severe delays eastbound between Wimbledon and Earl's Court.")];
  const branch = [ride("district", "Southfields", "East Putney")];
  assert.equal(issuesFor(going(branch, "out"), east, [], { at: AT }, maps).length, 1);
  assert.equal(issuesFor(going(branch, "back"), east, [], { at: AT }, maps).length, 1);
  const west = [status("district", 6, "Severe delays westbound between Tower Hill and Earl's Court.")];
  const city = [ride("district", "Whitechapel", "Stepney Green")];
  assert.deepEqual(issuesFor(going(city, "out"), west, [], { at: AT }, maps), [], "Whitechapel to Stepney Green is eastbound");
  function key2(name: string) { return [...place(northern, name)][0]; }
});

test("trouble TfL doesn't place, or names a station we can't find, still comes through", () => {
  const commute = contract().alerts[0];
  // Today's Elizabeth line: severe delays, the cause at Ealing Broadway, no section named.
  const now = issuesFor(commute, today, [], { at: AT }, maps);
  assert.deepEqual(now.map((i) => [i.title, i.severity]), [["Elizabeth line: severe delays", "delays"]]);
  assert.match(now[0].detail, /^Severe delays while we conduct a safety inspection.*Tickets are being accepted/, "one clause: TfL's whole notice");
  const unknown = [status("elizabeth", 6, "Severe delays between Paddington and Nowhere Junction due to a signal failure.")];
  assert.equal(issuesFor(commute, unknown, [], { at: AT }, maps)[0]?.severity, "delays");
  // And with no line order at all (TfL didn't answer), the quoted notice behaves as before: told.
  assert.equal(issuesFor(commute, asOne, [], { at: AT })[0]?.severity, "delays");
});

test("branches: the Northern's Charing Cross branch delays, from the stations TfL lists and from its words", () => {
  const listed = today.filter((s) => s.lineId === "northern");
  assert.equal(listed[0].stops.length, 11);
  const words = listed.map((s) => ({ ...s, stops: [] }));
  const cases: [string, Segment, number][] = [
    ["Camden Town to Kentish Town meets it at Camden only", ride("northern", "Camden Town", "Kentish Town"), 0],
    ["Euston to Warren Street is on it", ride("northern", "Euston", "Warren Street"), 1],
    ["Euston to King's Cross is the Bank branch", ride("northern", "Euston", "King's Cross St. Pancras"), 0],
    ["Kennington to Oval runs off its end", ride("northern", "Kennington", "Oval"), 0],
  ];
  for (const [label, segment, count] of cases) {
    assert.equal(issuesFor(going([segment]), listed, [], { at: AT }, maps).length, count, `listed: ${label}`);
    assert.equal(issuesFor(going([segment]), words, [], { at: AT }, maps).length, count, `words: ${label}`);
  }
  const euston = issuesFor(going([ride("northern", "Euston", "Warren Street")]), words, [], { at: AT }, maps)[0];
  // One trouble in the notice: TfL's words as they are, as before.
  assert.equal(euston.detail, "Minor delays between Camden Town and Kennington via Charing Cross due to a points failure at Kennington. GOOD SERVICE on the rest of the line.");
});

test("planned works with sections: inside is closed, an end is trouble, elsewhere nothing", () => {
  const words = weekend.map((s) => ({ ...s, stops: [] }));
  const cases: [string, Segment, string | undefined][] = [
    ["Notting Hill Gate to Bayswater is inside", ride("district", "Notting Hill Gate", "Bayswater"), "closed"],
    ["Earl's Court to High Street Kensington ends at it", ride("district", "Earl's Court", "High Street Kensington"), "delays"],
    ["Earl's Court to West Brompton is elsewhere", ride("district", "Earl's Court", "West Brompton"), undefined],
    ["Liverpool Street to Canary Wharf is inside the Elizabeth line's", contract().alerts[0].segments[0], "closed"],
  ];
  for (const [label, segment, severity] of cases) {
    for (const [lines, how] of [[weekend, "listed"], [words, "words"]] as const) {
      const issues = issuesFor(going([segment]), lines, [], SATURDAY, maps);
      assert.equal(issues[0]?.severity, severity, `${how}: ${label}`);
    }
  }
  const nhg = issuesFor(going([ride("district", "Notting Hill Gate", "Bayswater")]), words, [], SATURDAY, maps)[0];
  assert.match(nhg.detail, /^Saturday 28 and Sunday 29 November, no service between High Street Kensington and Edgware Road\./);
  assert.ok(nhg.from! >= Date.parse("2026-11-28T04:30:00Z"));
});

test("signatures stay put when TfL rewords a placed notice", () => {
  const ealing = going([ride("elizabeth", "Ealing Broadway", "West Ealing")]);
  const a = issuesFor(ealing, asOne, [], { at: AT }, maps)[0];
  const b = issuesFor(ealing, [status("elizabeth", 6, NOTICE.replace("an earlier signal failure", "a signal failure at Acton"))], [], { at: AT }, maps)[0];
  assert.equal(a.signature, b.signature);
});

test("a station closed one way only: only for alerts that go that way through it", () => {
  const westbound = [{ stationId: "910GLIVSTLL", name: "Liverpool Street", mode: "elizabeth-line", type: "Part Closure", description: "Liverpool Street: Westbound Elizabeth line trains will not stop here.", planned: false, lines: ["elizabeth"] }];
  const commute = contract().alerts[0].segments;
  assert.deepEqual(issuesFor(going(commute, "out"), [], westbound, { at: AT }, maps), [], "Liverpool Street to Canary Wharf is eastbound");
  assert.equal(issuesFor(going(commute, "back"), [], westbound, { at: AT }, maps)[0]?.severity, "delays");
  assert.equal(issuesFor(going(commute), [], westbound, { at: AT }, maps)[0]?.severity, "delays");
  assert.deepEqual(linesToPlace([going(commute, "out")], [], westbound), ["elizabeth"]);
});

test("a section with alternatives counts only if every pair runs along the line", () => {
  // No service pattern runs Reading to Shenfield: unsure, so tell.
  const odd = [status("elizabeth", 6, "Severe delays between Reading and Abbey Wood / Shenfield.")];
  assert.equal(issuesFor(going([ride("elizabeth", "Stratford", "Maryland")]), odd, [], { at: AT }, maps)[0]?.severity, "delays");
  // The weekend's "Maidenhead / Heathrow (all terminals) and Abbey Wood / Shenfield" all place.
  assert.equal(issuesFor(going([ride("elizabeth", "Stratford", "Maryland")]), weekend.map((s) => ({ ...s, stops: [] })), [], SATURDAY, maps)[0]?.severity, "closed");
});

test("earlier delays in a cause aren't trouble of their own", () => {
  assert.deepEqual(clauses("Minor delays between Ilford and Shenfield due to earlier delays.").map((c) => c.family), ["minor"]);
  const minor = [status("elizabeth", 9, "Minor delays between Ilford and Shenfield due to earlier delays.")];
  assert.deepEqual(issuesFor(contract().alerts[0], minor, [], { at: AT }, maps), []);
});

test("a station closed outright is closed both ways, whatever compass word its notice uses", () => {
  const closed = [{ stationId: "910GCANWHRF", name: "Canary Wharf", mode: "elizabeth-line", type: "Closure", description: "Canary Wharf: Closed. Use westbound Jubilee line trains from Canary Wharf instead.", planned: false, lines: ["elizabeth"] }];
  assert.equal(issuesFor(going(contract().alerts[0].segments, "out"), [], closed, { at: AT }, maps)[0]?.severity, "closed");
});

// Review of PR #11: ways a real alert could be wrongly silenced.
const circle = parseLineMap("circle", fixture("tfl-sequence-circle.json"))!;
maps.set("circle", circle);
LINES.circle = [circle, "Circle line", "tube"];

test("review 1: towards X and Y counts for a ride towards either", () => {
  const two = [status("elizabeth", 9, "Minor delays on trains towards Shenfield and Abbey Wood due to a signal failure.")];
  assert.equal(issuesFor(going(contract().alerts[0].segments, "out"), two, [], { at: AT }, maps).length, 1, "Liverpool Street to Canary Wharf heads to Abbey Wood");
  const morden = [status("northern", 9, "Minor delays on trains towards Battersea Power Station and Morden.")];
  assert.equal(issuesFor(going([ride("northern", "Kennington", "Morden")], "out"), morden, [], { at: AT }, maps).length, 1);
  // Coming back from Morden gets nearer Battersea along the line: told (the safe side). Towards
  // Morden alone isn't the way back.
  assert.equal(issuesFor(going([ride("northern", "Kennington", "Morden")], "back"), morden, [], { at: AT }, maps).length, 1);
  const toMorden = [status("northern", 9, "Minor delays on trains towards Morden.")];
  assert.deepEqual(issuesFor(going([ride("northern", "Kennington", "Morden")], "back"), toMorden, [], { at: AT }, maps), []);
  const unknown = [status("elizabeth", 9, "Minor delays on trains towards Shenfield and Nowhere Junction.")];
  assert.equal(issuesFor(going(contract().alerts[0].segments, "out"), unknown, [], { at: AT }, maps).length, 1, "a name we can't place: both ways");
});

test("review 2: a direction word belongs to its own section", () => {
  const mixed = [status("elizabeth", 6, "Severe delays between Paddington and Abbey Wood, and westbound between Shenfield and Stratford.")];
  const issues = issuesFor(going(contract().alerts[0].segments, "out"), mixed, [], { at: AT }, maps);
  assert.equal(issues.length, 1, "Paddington to Abbey Wood is both ways");
  assert.match(issues[0].detail, /Paddington and Abbey Wood/);
});

test("review 3: 'with delays on the rest of the line' is trouble, not a cause", () => {
  const rest = [status("elizabeth", 3, "No service between Paddington and Reading with delays on the rest of the line.")];
  assert.equal(issuesFor(contract().alerts[0], rest, [], { at: AT }, maps)[0]?.severity, "delays");
});

test("review 4: a station of the stretch the line map doesn't know: tell", () => {
  const segment = { ...contract().alerts[0].segments[0], stations: ["910GLIVSTLL|910GLIVST", "910GRENUMBERED", "910GCANWHRF"] };
  const severe = [status("elizabeth", 6, "Severe delays between Paddington and Abbey Wood.")];
  assert.equal(issuesFor(going([segment]), severe, [], { at: AT }, maps)[0]?.severity, "delays");
});

test("review 5: lines that double back: the Circle's direction is unsure where its route and the ride disagree", () => {
  const key = (name: string) => [...place(circle, name)][0];
  assert.equal(heading(circle, key("Liverpool Street"), key("Aldgate"), "ew"), 1, "agreeing: eastbound");
  assert.equal(heading(circle, key("Westminster"), key("Embankment"), "ew"), 0, "the route runs west end to end, the ride east");
  assert.equal(heading(circle, key("Tower Hill"), key("Aldgate"), "ew"), 0);
  for (const word of ["westbound", "eastbound"]) {
    const one = [status("circle", 6, `Severe delays ${word} between Victoria and Tower Hill.`)];
    assert.equal(issuesFor(going([ride("circle", "Westminster", "Embankment")], "out"), one, [], { at: AT }, maps).length, 1, word);
  }
});

test("review 6: the same section keeps its signature whether or not the line's order is to hand", () => {
  const ealing = going([ride("elizabeth", "Ealing Broadway", "West Ealing")]);
  const placed = issuesFor(ealing, asOne, [], { at: AT }, maps)[0];
  const unplaced = issuesFor(ealing, asOne, [], { at: AT }).find((i) => /Paddington and Heathrow/.test(i.detail))!;
  assert.equal(placed.signature, unplaced.signature);
});

test("a multi-segment alert with a direction: each segment judged its own way", () => {
  const segments = [ride("elizabeth", "Paddington", "Liverpool Street"), ride("northern", "Bank", "Old Street")];
  const westbound = [status("elizabeth", 6, "Severe delays westbound between Stratford and Paddington.")];
  const north = [status("northern", 6, "Severe delays northbound between Kennington and Moorgate.")];
  assert.deepEqual(issuesFor(going(segments, "out"), westbound, [], { at: AT }, maps), [], "out, the Elizabeth leg is eastbound");
  assert.equal(issuesFor(going(segments, "back"), westbound, [], { at: AT }, maps).length, 1);
  assert.equal(issuesFor(going(segments, "out"), north, [], { at: AT }, maps).length, 1, "out, the Northern leg is northbound");
  assert.deepEqual(issuesFor(going(segments, "back"), north, [], { at: AT }, maps), []);
});

test("planned works ahead: each time you travel judged its own way", () => {
  const works = (reason: string): LineStatus[] => [{ ...status("elizabeth", 5, reason), planned: true, periods: [{ from: Date.parse("2026-11-28T00:00:00Z"), to: Date.parse("2026-11-29T23:59:00Z") }] }];
  const east = works("Saturday 28 and Sunday 29 November, no service eastbound between Liverpool Street and Stratford.");
  const commute = going([ride("elizabeth", "Liverpool Street", "Stratford")]);
  const morning = { from: Date.parse("2026-11-28T08:00:00Z"), to: Date.parse("2026-11-28T10:00:00Z") };
  const evening = { from: Date.parse("2026-11-28T17:00:00Z"), to: Date.parse("2026-11-28T19:00:00Z") };
  assert.equal(issuesInRanges(commute, east, [], [{ ...morning, direction: "out" }, { ...evening, direction: "back" }], maps).length, 1, "the morning goes that way");
  assert.deepEqual(issuesInRanges(commute, east, [], [{ ...morning, direction: "back" }, { ...evening, direction: "back" }], maps), [], "neither time does");
  assert.equal(issuesInRanges(commute, east, [], [{ ...morning, direction: "both" }], maps).length, 1);
  assert.equal(issuesInRanges(going(commute.segments, "out"), east, [], [morning], maps).length, 1, "a range without a way is the alert's");
  const twice = issuesInRanges(commute, works("No service between Liverpool Street and Stratford."), [], [{ ...morning, direction: "out" }, { ...evening, direction: "back" }], maps);
  assert.equal(twice.length, 1, "one issue for the disruption, not one per time");
});

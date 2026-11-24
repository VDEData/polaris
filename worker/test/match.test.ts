import assert from "node:assert/strict";
import { test } from "node:test";
import { clean, issuesFor } from "../src/match.ts";
import { travelRanges } from "../src/schedule.ts";
import { parseLineStatuses, parseStationDisruptions } from "../src/tfl.ts";
import { alert, contract, fixture, RECORDED_AT, stretch } from "./helpers.ts";

const lines = parseLineStatuses(fixture("tfl-line-status-detail.json"));
const stations = parseStationDisruptions(fixture("tfl-station-disruptions.json"));
const week = parseLineStatuses(fixture("tfl-line-status-week.json"));
const commute = contract().alerts[0];

test("reads the stations each disruption covers", () => {
  const district = lines.find((l) => l.lineId === "district" && l.severity === 3)!;
  assert.equal(district.summary, "Part Suspended");
  assert.ok(district.stops.includes("940GZZLUECT"), "Earl's Court");
  assert.equal(district.stops.length, 6);
  const wholeLine = lines.find((l) => l.lineId === "district" && l.severity === 9)!;
  assert.deepEqual(wholeLine.stops, [], "minor delays on the rest of the line name no stations");
});

test("today's Canary Wharf closure reaches the Liverpool Street commute", () => {
  const issues = issuesFor(commute, lines, stations, { at: RECORDED_AT });
  assert.equal(issues[0].title, "Canary Wharf closed (Elizabeth line)");
  assert.equal(issues[0].severity, "closed");
  assert.match(issues[0].detail, /^Closed - This station is closed to Elizabeth line services/);
  // Minor delays between Ilford and Shenfield are on another stretch.
  assert.ok(!issues.some((i) => /minor delays/.test(i.title)));
});

test("delays on the stretch you ride count, and minor ones only if you asked", () => {
  const romford = stretch("elizabeth", "Elizabeth line", "elizabeth-line", [
    { id: "910GLIVSTLL|910GLIVST", name: "Liverpool Street" }, { id: "910GWCHAPXR", name: "Whitechapel" }, { id: "910GSTFD", name: "Stratford" },
    { id: "910GMRYLAND", name: "Maryland" }, { id: "910GFRSTGT", name: "Forest Gate" }, { id: "910GMANRPK", name: "Manor Park" },
    { id: "910GILFORD", name: "Ilford" }, { id: "910GSVNKNGS", name: "Seven Kings" }, { id: "910GGODMAYS", name: "Goodmayes" },
    { id: "910GCHDWLHT", name: "Chadwell Heath" }, { id: "910GROMFORD", name: "Romford" },
  ]);
  const issues = issuesFor(alert([romford]), lines, stations, { at: RECORDED_AT });
  assert.deepEqual(issues.map((i) => [i.title, i.severity]), [["Elizabeth line: minor delays", "minor"]]);
  assert.deepEqual(issuesFor(alert([romford], { minorDelays: false }), lines, stations, { at: RECORDED_AT }), []);
});

test("riding inside a suspended stretch is closed; touching its end is trouble, not closed", () => {
  const inside = stretch("district", "District line", "tube", [
    { id: "940GZZLUPAC", name: "Paddington" }, { id: "940GZZLUBWT", name: "Bayswater" }, { id: "940GZZLUNHG", name: "Notting Hill Gate" },
  ]);
  const fromTheEnd = stretch("district", "District line", "tube", [
    { id: "940GZZLUECT", name: "Earl's Court" }, { id: "940GZZLUWBN", name: "West Brompton" },
  ]);
  const insideIssues = issuesFor(alert([inside]), lines, stations, { at: RECORDED_AT });
  assert.equal(insideIssues[0].title, "District line: part suspended");
  assert.equal(insideIssues[0].severity, "closed");
  // The rest of the line has minor delays, which name no stations, so they're everyone's.
  assert.ok(insideIssues.some((i) => i.title === "District line: minor delays"));
  const endIssues = issuesFor(alert([fromTheEnd]), lines, stations, { at: RECORDED_AT });
  assert.equal(endIssues.find((i) => i.title === "District line: part suspended")?.severity, "delays");
});

test("a whole-line alert hears about a part suspension anywhere on it", () => {
  const district = { ...contract().alerts[1], minorDelays: true };
  const issues = issuesFor(district, lines, stations, { at: RECORDED_AT });
  assert.deepEqual(issues.map((i) => [i.title, i.severity]), [["District line: part suspended", "delays"], ["District line: minor delays", "minor"]]);
});

test("trains not calling one way only is trouble rather than a closure", () => {
  const baronsCourt = stretch("district", "District line", "tube", [
    { id: "940GZZLUBSC", name: "Barons Court" }, { id: "940GZZLUWKN", name: "West Kensington" },
  ]);
  const issues = issuesFor(alert([baronsCourt]), [], stations, { at: RECORDED_AT });
  assert.equal(issues[0].title, "Barons Court part closed (District line)");
  assert.equal(issues[0].severity, "delays");
  // The same notice concerns the Circle, District and Piccadilly only.
  const jubilee = stretch("jubilee", "Jubilee line", "tube", [{ id: "940GZZLUBSC", name: "Barons Court" }, { id: "940GZZLUWKN", name: "West Kensington" }]);
  assert.deepEqual(issuesFor(alert([jubilee]), [], stations, { at: RECORDED_AT }), []);
});

test("planned works ahead count only when they overlap a time you travel", () => {
  // Sunday 15 November: no service between Paddington and Abbey Wood / Stratford until 10:15.
  const sunday = (start: number, end: number) => ({ ...commute, days: [7], windows: [{ start, end }] });
  const early = issuesFor(sunday(480, 570), week, [], { ranges: travelRanges(sunday(480, 570), RECORDED_AT, "Europe/London", 8) });
  assert.equal(early.length, 1);
  assert.equal(early[0].title, "Elizabeth line: part closure");
  assert.equal(early[0].severity, "closed");
  assert.match(early[0].detail, /^Sunday 15 November, until 1015, no service between Paddington and Abbey Wood/);
  assert.equal(early[0].from, Date.parse("2026-11-15T04:30:00Z"));

  const later = sunday(660, 720);
  assert.deepEqual(issuesFor(later, week, [], { ranges: travelRanges(later, RECORDED_AT, "Europe/London", 8) }), []);
  // Weekday travel this week misses the Sunday works, and today's live delays aren't "planned".
  const weekdays = issuesFor(commute, week, [], { ranges: travelRanges(commute, RECORDED_AT, "Europe/London", 8) });
  assert.ok(!weekdays.some((i) => /part closure|minor delays/.test(i.title)), JSON.stringify(weekdays.map((i) => i.title)));
});

test("clean drops TfL's shouted line or station prefix", () => {
  assert.equal(clean("ELIZABETH LINE: Sunday 15 November, until 0800, no service"), "Sunday 15 November, until 0800, no service");
  assert.equal(clean("Canary Wharf: Closed - This station is closed"), "Closed - This station is closed");
  assert.equal(clean("Minor delays between Ilford and Shenfield.  GOOD SERVICE on the rest of line.  "), "Minor delays between Ilford and Shenfield. GOOD SERVICE on the rest of line.");
});

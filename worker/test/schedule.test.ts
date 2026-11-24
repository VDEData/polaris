import assert from "node:assert/strict";
import { test } from "node:test";
import { activeWindows, dateKey, during, eachWay, instant, isActive, localTime, travelRanges } from "../src/schedule.ts";
import { alert } from "./helpers.ts";

const LONDON = "Europe/London";
const commute = alert([], { days: [1, 2, 3, 4, 5], windows: [{ start: 420, end: 600 }] });

test("London time in winter and summer", () => {
  assert.deepEqual(localTime(Date.parse("2026-11-09T07:30:00Z"), LONDON), { year: 2026, month: 11, day: 9, weekday: 1, minutes: 450 });
  assert.equal(localTime(Date.parse("2026-07-06T06:30:00Z"), LONDON).minutes, 450, "07:30 BST");
  assert.equal(dateKey(localTime(Date.parse("2026-11-08T23:30:00Z"), LONDON)), "2026-11-08");
});

test("instants either side of the clocks changing", () => {
  assert.equal(instant(2026, 11, 9, 450, LONDON), Date.parse("2026-11-09T07:30:00Z"));
  assert.equal(instant(2026, 7, 6, 450, LONDON), Date.parse("2026-07-06T06:30:00Z"));
  assert.equal(instant(2027, 3, 29, 450, LONDON), Date.parse("2027-03-29T06:30:00Z"), "the Monday after clocks go forward");
});

test("active only on your days, inside your times", () => {
  assert.ok(isActive(commute, Date.parse("2026-11-09T07:00:00Z"), LONDON), "Monday 07:00");
  assert.ok(!isActive(commute, Date.parse("2026-11-09T10:00:00Z"), LONDON), "10:00 is the end");
  assert.ok(!isActive(commute, Date.parse("2026-11-08T08:00:00Z"), LONDON), "Sunday");
  assert.ok(isActive(commute, Date.parse("2026-07-06T06:30:00Z"), LONDON), "summer time");
});

test("a late window runs past midnight on the day it started", () => {
  const friday = alert([], { days: [5], windows: [{ start: 23 * 60, end: 60 }] });
  assert.ok(isActive(friday, Date.parse("2026-11-13T23:30:00Z"), LONDON), "Friday 23:30");
  assert.ok(isActive(friday, Date.parse("2026-11-14T00:30:00Z"), LONDON), "into Saturday");
  assert.ok(!isActive(friday, Date.parse("2026-11-15T00:30:00Z"), LONDON), "Sunday morning belongs to Saturday");
});

test("tomorrow's trips for the evening check", () => {
  const sunday = Date.parse("2026-11-08T19:05:00Z");
  assert.deepEqual(travelRanges(commute, sunday, LONDON, 2, true), [{ from: Date.parse("2026-11-09T07:00:00Z"), to: Date.parse("2026-11-09T10:00:00Z"), direction: "both" }]);
  const friday = Date.parse("2026-11-13T19:05:00Z");
  assert.deepEqual(travelRanges(commute, friday, LONDON, 2, true), [], "no weekday trip on Saturday");
});

test("a time's own way: its own, else the alert's, else both; overlapping times that disagree are both", () => {
  const ways = alert([], { days: [1], direction: "out", windows: [{ start: 420, end: 600 }, { start: 540, end: 660, direction: "back" }, { start: 990, end: 1170, direction: "back" }] });
  const monday = (hhmm: string) => Date.parse(`2026-11-09T${hhmm}:00Z`);
  assert.equal(during(ways, activeWindows(ways, monday("08:00"), LONDON)).direction, "out", "the alert's way");
  assert.equal(during(ways, activeWindows(ways, monday("09:30"), LONDON)).direction, "both", "out and back at once");
  assert.equal(during(ways, activeWindows(ways, monday("17:00"), LONDON)).direction, "back");
  assert.equal(during(ways).direction, "both", "all its times together");
  assert.equal(during(alert([], { windows: [{ start: 420, end: 600 }] })).direction, "both", "no way anywhere");
  assert.deepEqual(eachWay(ways).map((a) => a.direction), ["out", "back"]);
  assert.deepEqual(travelRanges(ways, monday("06:00"), LONDON, 1).map((r) => r.direction), ["out", "back", "back"]);
});

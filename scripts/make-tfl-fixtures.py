#!/usr/bin/env python3
"""Writes three hand-written TfL Journey Planner answers for the shared expectations
(PolarisTests/Fixtures/journey-expectations.json), between well-known public stations.

- tfl-journeys-arrive-by.json: Liverpool Street to Canary Wharf on Sunday 8 November 2026,
  the day tfl-line-status.json and tfl-stop-disruptions.json were recorded, so TfL's notices
  apply. Four ways, built so the sorts disagree: best and fastest lead with the Northern and
  Jubilee, fewest changes with the DLR, least walking with the 135. One of them stops at Canary
  Wharf on the Elizabeth line, which TfL reported closed to it that day.
- tfl-journeys-summer.json: Paddington to Tower Hill on Wednesday 15 July 2026, in British
  Summer Time, when TfL's zone-less London times are an hour ahead of UTC.
- tfl-journeys-chain.json: King's Cross St. Pancras to Oxford Circus on the Victoria line, leaving
  at 12:11 (twice, from two platforms), 12:14 and 12:17, to pin how merging groups a run of
  departures and which copy leads.

The shapes follow TfL's real responses (only the fields Polaris reads). Run from the repo root:
    python3 scripts/make-tfl-fixtures.py
"""
import json
import pathlib

STATIONS = {
    "Liverpool Street": (51.5178, -0.0823),
    "Liverpool Street Station": (51.5176, -0.0822),
    "Whitechapel": (51.5195, -0.0597),
    "Canary Wharf": (51.5054, -0.0235),
    "Bank": (51.5133, -0.0886),
    "Shadwell": (51.5112, -0.0569),
    "Limehouse": (51.5124, -0.0397),
    "West India Quay": (51.5070, -0.0203),
    "London Bridge": (51.5052, -0.0864),
    "Bermondsey": (51.4979, -0.0637),
    "Canada Water": (51.4982, -0.0502),
    "Paddington": (51.5154, -0.1755),
    "Bond Street": (51.5142, -0.1494),
    "Tottenham Court Road": (51.5165, -0.1310),
    "Farringdon": (51.5203, -0.1053),
    "Aldgate": (51.5143, -0.0755),
    "Tower Hill": (51.5098, -0.0766),
    "Edgware Road": (51.5199, -0.1679),
    "Baker Street": (51.5226, -0.1571),
    "King's Cross St. Pancras": (51.5308, -0.1238),
    "Moorgate": (51.5186, -0.0886),
    "Euston": (51.5282, -0.1337),
    "Warren Street": (51.5247, -0.1384),
    "Oxford Circus": (51.5152, -0.1419),
}

MODES = {"elizabeth": "elizabeth-line", "dlr": "dlr", "northern": "tube", "jubilee": "tube",
         "circle": "tube", "victoria": "tube", "135": "bus"}
NAMES = {"elizabeth": "Elizabeth line", "dlr": "DLR", "northern": "Northern", "jubilee": "Jubilee",
         "circle": "Circle", "victoria": "Victoria", "135": "135"}


def walk(start, end, frm, to):
    return {
        "departureTime": start, "arrivalTime": end,
        "departurePoint": {"commonName": frm}, "arrivalPoint": {"commonName": to},
        "mode": {"id": "walking", "name": "walking"},
        "path": {"lineString": json.dumps([list(STATIONS.get(frm, STATIONS.get(to)))])},
    }


def ride(line, start, end, frm, to, via, platform=None, direction=None, disrupted=False):
    stops = via + [to]
    return {
        "departureTime": start, "arrivalTime": end, "scheduledDepartureTime": start,
        "departurePoint": {"commonName": f"{frm} Station" if MODES[line] != "bus" else frm,
                           **({"platformName": platform} if platform else {})},
        "arrivalPoint": {"commonName": f"{to} Station" if MODES[line] != "bus" else to},
        "mode": {"id": MODES[line], "name": MODES[line]},
        "routeOptions": [{"name": NAMES[line], "directions": [direction or to],
                          "lineIdentifier": {"id": line, "name": NAMES[line]}}],
        "path": {"lineString": json.dumps([list(STATIONS[s]) for s in [frm] + stops]),
                 "stopPoints": [{"id": s.lower().replace(" ", "-"), "name": f"{s} Station"} for s in stops]},
        "isDisrupted": disrupted,
    }


def journey(start, end, legs, fare):
    return {"startDateTime": start, "arrivalDateTime": end, "legs": legs, "fare": {"totalCost": fare}}


day = "2026-11-08T"
arrive_by = {"journeys": [
    # Elizabeth line, direct: what a planner that didn't know about the closure would suggest.
    journey(day + "16:30:00", day + "16:43:00", [
        walk(day + "16:30:00", day + "16:32:00", "Liverpool Street", "Liverpool Street Station"),
        ride("elizabeth", day + "16:33:00", day + "16:40:00", "Liverpool Street", "Canary Wharf", ["Whitechapel"],
             platform="B", direction="Abbey Wood"),
        walk(day + "16:40:00", day + "16:43:00", "Canary Wharf", "Canary Wharf"),
    ], 290),
    # DLR from Bank: no change, ten minutes on foot.
    journey(day + "16:20:00", day + "16:45:00", [
        walk(day + "16:20:00", day + "16:26:00", "Liverpool Street", "Bank Station"),
        ride("dlr", day + "16:28:00", day + "16:41:00", "Bank", "Canary Wharf", ["Shadwell", "Limehouse", "West India Quay"],
             platform="10", direction="Lewisham"),
        walk(day + "16:41:00", day + "16:45:00", "Canary Wharf", "Canary Wharf"),
    ], 290),
    # Northern then Jubilee: leaves latest, one change.
    journey(day + "16:25:00", day + "16:45:00", [
        walk(day + "16:25:00", day + "16:27:00", "Liverpool Street", "Bank Station"),
        ride("northern", day + "16:28:00", day + "16:31:00", "Bank", "London Bridge", [], platform="4", direction="Morden"),
        walk(day + "16:31:00", day + "16:34:00", "London Bridge", "London Bridge Station"),
        ride("jubilee", day + "16:36:00", day + "16:42:00", "London Bridge", "Canary Wharf", ["Bermondsey", "Canada Water"],
             platform="1", direction="Stratford"),
        walk(day + "16:42:00", day + "16:45:00", "Canary Wharf", "Canary Wharf"),
    ], 290),
    # The 135 bus: leaves earliest, hardly any walking.
    journey(day + "16:10:00", day + "16:47:00", [
        walk(day + "16:10:00", day + "16:11:00", "Liverpool Street", "Liverpool Street Station"),
        ride("135", day + "16:12:00", day + "16:45:00", "Liverpool Street Station", "Canary Wharf", ["Whitechapel", "Limehouse"],
             platform="K", direction="Crossharbour"),
        walk(day + "16:45:00", day + "16:47:00", "Canary Wharf", "Canary Wharf"),
    ], 175),
]}

summer_day = "2026-07-15T"
summer = {"journeys": [
    # Elizabeth line then Circle: quicker, one change.
    journey(summer_day + "09:02:00", summer_day + "09:28:00", [
        walk(summer_day + "09:02:00", summer_day + "09:04:00", "Paddington", "Paddington Station"),
        ride("elizabeth", summer_day + "09:05:00", summer_day + "09:17:00", "Paddington", "Liverpool Street",
             ["Bond Street", "Tottenham Court Road", "Farringdon"], platform="A", direction="Abbey Wood"),
        walk(summer_day + "09:17:00", summer_day + "09:20:00", "Liverpool Street", "Liverpool Street Station"),
        ride("circle", summer_day + "09:22:00", summer_day + "09:26:00", "Liverpool Street", "Tower Hill", ["Aldgate"],
             platform="2", direction="Hammersmith"),
        walk(summer_day + "09:26:00", summer_day + "09:28:00", "Tower Hill", "Tower Hill"),
    ], 280),
    # Circle line all the way: slower, no change.
    journey(summer_day + "09:04:00", summer_day + "09:44:00", [
        walk(summer_day + "09:04:00", summer_day + "09:06:00", "Paddington", "Paddington Station"),
        ride("circle", summer_day + "09:07:00", summer_day + "09:42:00", "Paddington", "Tower Hill",
             ["Edgware Road", "Baker Street", "King's Cross St. Pancras", "Farringdon", "Moorgate", "Liverpool Street", "Aldgate"],
             platform="1", direction="Edgware Road"),
        walk(summer_day + "09:42:00", summer_day + "09:44:00", "Tower Hill", "Tower Hill"),
    ], 280),
]}

chain_day = "2026-11-24T"


def victoria(leave, platform):
    h, m = 12, leave
    t = lambda minutes: f"{chain_day}{h:02d}:{minutes:02d}:00"
    return journey(t(m - 2), t(m + 9), [
        walk(t(m - 2), t(m), "King's Cross St. Pancras", "King's Cross St. Pancras Station"),
        ride("victoria", t(m), t(m + 7), "King's Cross St. Pancras", "Oxford Circus", ["Euston", "Warren Street"],
             platform=platform, direction="Brixton"),
        walk(t(m + 7), t(m + 9), "Oxford Circus", "Oxford Circus"),
    ], 280)


# Listed out of order on purpose: merging must not depend on it.
chain = {"journeys": [victoria(17, "6"), victoria(11, "6"), victoria(14, "5"), victoria(11, "5")]}

fixtures = pathlib.Path(__file__).resolve().parent.parent / "PolarisTests" / "Fixtures"
for name, body in [("tfl-journeys-arrive-by.json", arrive_by), ("tfl-journeys-summer.json", summer),
                   ("tfl-journeys-chain.json", chain)]:
    (fixtures / name).write_text(json.dumps(body, indent=2, ensure_ascii=False) + "\n")
    print("wrote", fixtures / name)

# Polaris

An iOS app for getting around London (and the rest of Great Britain) that asks several
journey planners the same question and shows every answer in one ranked list, because
each has blind spots: one says 50 minutes, another swears 75 is the best you can do.
Then it takes you there: live departure boards, turn-by-turn walking, a Live Activity,
and alerts for the lines you ride every day.

SwiftUI for iOS 26 and later, Swift 6 with strict concurrency, no third-party packages.
The optional alerts server is a small Cloudflare Worker in `worker/`.

It began as one person's app, so it is tuned for London: TfL is the richest source, and
alerts, disruptions and live buses are TfL's. Transitous and Google work anywhere they
have data.

## Sources

| Source | What it gives | Key |
|---|---|---|
| Google Routes API (`TRANSIT`) | Full routes, several alternatives per query, fares | Optional: `GOOGLE_ROUTES_API_KEY` |
| TfL Journey Planner | London's official planner incl. National Rail, live running | Optional: `TFL_APP_KEY` (50 req/min without) |
| Transitous (MOTIS) | Open GB timetables: rail, BODS buses, Underground | None |
| Apple MapKit | Transit **ETA only** (MapKit returns no route for `.transit`) | None |
| Citymapper, Google Maps, Apple Maps apps | One-tap hand-off with the same trip filled in | None |

Citymapper and Waze have no usable API (Citymapper closed theirs in June 2023;
Waze has no transit), so Citymapper is a deep link.

**Deep search** (on by default) asks Google five ways (fastest, fewer changes,
less walking, rail only, bus only) and TfL three ways. Most "blind spots" are
ranking rather than missing data, and the variants surface routes a source knows
about but buries. Results with the same lines boarding within 4 minutes merge
into one journey with every source's badge, and later departures on the same
lines fold into one card ("Or leave at 15:08 or 15:18"). The merge is the same
whichever source answers first: copies are taken TfL first, then Google, then
Transitous, earliest boarding first, and each joins a journey only if it boards
within 4 minutes of that journey's leading copy, whose times, legs and platforms
the journey shows (the fare is the first any copy has). So 12:01, 12:04 and 12:07
make two journeys, 12:01 and 12:07, on one card.

**Google's free allowance.** Google is used only inside its free monthly allowance
(planned to the lowest cap, 1,000 requests). The phone counts every Google request
in Google's own month (Pacific time, `GoogleUsage`): deep search asks all five ways
under 700, one way under 900, and not at all past 900, when routes come from TfL and
Transitous alone. The count shows under Sources in Settings.

**Live map and Go.** Everything sits on one live map with a sheet over it, as in
Apple and Google Maps, with your dot's beam showing which way the phone faces:
home (search, Home/Work, recents, what's leaving near you),
then the routes, then a journey, then Go. Go follows you on the map, gives
turn-by-turn on every walk (MapKit walking directions, re-routed when you stray),
says whether you'll make the next departure ("2 min to spare", "you'll miss the
18:47; the next is 18:49, 2 min later at Farringdon"), moves leg by leg on its own
(by location on foot, by the clock underground), and sends a "get off next" alert
before each stop off. A Live Activity keeps the current step and a countdown on
the Lock Screen and in the Dynamic Island, with what missing your ride costs you at
the end of the trip ("If missed +8 min · Next 11:59, misses the 12:04 Elizabeth"):
the next one is followed through every later change on its departure board.
If you're already aboard (an earlier bus, or Go started once you'd got on), Go sees
you moving along the ride faster than anyone walks and catches up; one button says
it for you ("I'm on the 25", "I've got off"), and any step can be tapped to jump to it.

**Re-planning during Go**, as a satnav does. When you're early or late Go re-times
the trip and says so plainly ("You're early: the 07:00 277. Arrive 07:14, 20 min
sooner"). When that leaves a long wait at a change (10 min or more, 7 more than
planned), loses a connection or a missed ride makes you 8 min late, or the planned
departure ahead is cancelled, it asks the planners once, from the next stop you'll be
at and when the re-timed trip has you there (not your GPS underground). A new way is
offered only if it gets there 5 min sooner, or 15% of the time left if that's more,
less 3 min per extra change, and TfL flags nothing on it: in the sheet (Switch or
Keep my route), the banner, the map (edged in gold), the Live Activity and one
ordinary notification with the same two buttons. Switch keeps what you've done and
carries on by the new way; Keep isn't asked again for the same reason. "Find a faster
way" asks by hand. Nothing better and a long wait is a line in the sheet, never a
notification. Go never switches route on its own. If TfL's status can't be had, the
offer still comes and says the line status couldn't be checked. Each search is at most
5 requests (Google, TfL and Transitous once each, never their deep-search variants,
plus TfL's status if older than 3 minutes), and a trip makes at most 3, answered or
not, 4 minutes apart: 15 requests, Google 3, at most. Nothing goes without a network
connection, and nothing runs on a timer.

**Live buses.** Tap a bus line leaving near you, or "Where are the buses?" on a bus
ride, and London's buses on that line show on the map, each with how soon it reaches
your stop. TfL publishes no bus GPS, only each bus's predicted arrival at the stops
ahead (Countdown), so a bus is placed between the stop it last passed and the next
by how soon it's due there. During Go the bus you're waiting for or riding is shown,
and its live times replace the timetable's: whether you'll make it and what the next
one costs come from TfL's buses on the way, and once you're on, from your own bus.

**Disruptions.** Each search also asks TfL for the whole network's status (every
line, every closed or skipped station, National Rail operators included) and checks
every source's routes against it. The planner that knew about a closure routes round
it and says nothing; the ones that didn't put you straight through it. So a route
that gets on or off at a closed station, or rides a closed stretch, is flagged in
orange and can't be "Best"; it goes under the others. What's reported shows at the
top of the list, in TfL's own words, and on the leg it touches.

**Alerts for your lines.** For the journeys you make most days and wouldn't open a
planner for: save a line from where you get on to where you get off (or from a journey's
bell button, which takes its TfL lines as you'd ride them) and when you travel. A small
Cloudflare Worker (`worker/`, which you host yourself: see below) checks TfL every two minutes
during those times and sends a push for trouble on the track you ride: the stations TfL
lists against a disruption, or else the sections its notice names ("between Paddington
and Heathrow"), placed on the line's station order. A section that only meets your
stretch at one station doesn't count; one TfL can't place, or a notice that names none,
does. Each alert can be set to one way (out or back): then trouble TfL reports as one way
only ("westbound", "towards Heathrow") counts only that way. The push is: Time Sensitive for closures and delays (so it comes through a Focus), ordinary for
minor delays, quiet for "good service again". Planned works on tomorrow's trips come at
7 pm the evening before. One notification per disruption, again only if it gets worse.
The home page shows each saved line's status.

**Departure boards.** Every boarding stop shows the departures around yours, the
ones that have just gone too, filtered to those that stop where you get off, with
what each does to your arrival there ("+3 min", "−2 min") and the line's status.
They come from Transitous's stop times (one request per board, kept for a minute).

**Places.** Search takes a chain or a kind of place as well as an address: "Apple
Store" lists every one nearby with its distance and Apple's public transport time,
quickest first, and ⓘ opens Apple Maps' card for it (opening hours, photos,
reviews). A bus ride says what walking the same stretch would take.

**Ranking** (`JourneyRanker`). Each sort (Best, Fastest, Fewest changes, Least
walking) is a generalised cost: arrival time plus minutes charged per change and
per minute walked, weighted differently per sort, so a minute less walking never
beats an hour quicker. A card shows if it's close to the best on the balanced
score or the chosen sort's own; anything beaten on time, changes *and* walking
(allowing a minute or two of noise between sources) folds under "more routes".
Filters (max changes, max walking, modes to avoid) are remembered, and the list
says how many routes they hid.

## Build from source

You need a Mac with Xcode 26 or later and [XcodeGen](https://github.com/yonaskolb/XcodeGen)
(`brew install xcodegen`). The Xcode project is generated, not committed.

1. Clone the repo and run `scripts/generate.sh`. It copies `Config/Secrets.example.xcconfig`
   to `Config/Secrets.xcconfig` (git-ignored) the first time, then writes `Polaris.xcodeproj`.
   Run it again whenever you add a file.
2. Open `Polaris.xcodeproj`, pick an iPhone simulator and run. With no keys at all it works:
   TfL (keyless), Transitous and Apple's estimates answer, and Google is shown as needing a key.
3. For your own iPhone, set in `Config/Secrets.xcconfig`:
   - `POLARIS_BUNDLE_ID`, a bundle ID of your own (`com.yourname.polaris`); the widget and
     tests add `.widgets` and `.tests`;
   - `DEVELOPMENT_TEAM`, your Apple team ID (developer.apple.com, Membership).
   A free Apple account can install it for a week at a time, but can't sign the push and
   Time Sensitive entitlements alerts need; for those you need the paid Apple Developer Program.
   If signing complains about them on a free account, delete those two keys from
   `Polaris/Resources/Polaris.entitlements` in your copy.
4. Set `POLARIS_CONTACT` to a URL or address where you can be reached: it goes in the
   User-Agent sent to TfL and Transitous, who ask clients to identify themselves. It defaults
   to this project's page, which is fine for a build on your own phone. If you distribute a
   build (TestFlight, an `.ipa`, a fork), put your own contact there: requests from your build
   are yours to answer for.

`Config/Base.xcconfig` holds the defaults and includes your `Secrets.xcconfig` last, so
anything you set there wins. In an xcconfig `//` starts a comment: write URLs as
`https:/$()/host`.

`scripts/testflight.sh` archives and uploads to App Store Connect. It is for shipping to
your own account (it needs your bundle ID, team and an app record there); building for
yourself doesn't need it.

### Keys

All optional. A new install opens on a short setup step that says what works with nothing set
and offers each item below, with what it adds and where to get it; skip it and everything stays
in Settings. Anything entered in the app is kept in the Keychain and beats the build-time value
from `Config/Secrets.xcconfig`. Prefer the app:
anything in the xcconfig is compiled into the app, where anyone with the `.ipa` can read it.

| Key | Where to get it | Without it |
|---|---|---|
| `GOOGLE_ROUTES_API_KEY` | [Google Cloud console](https://console.cloud.google.com): enable **Routes API**, create an API key restricted to iOS apps with your bundle ID and to the Routes API only | No Google routes |
| `TFL_APP_KEY` | [TfL API portal](https://api-portal.tfl.gov.uk): subscribe, copy the primary key | TfL still answers, at 50 requests a minute |
| `POLARIS_ALERTS_URL`, `POLARIS_ALERTS_TOKEN` | Your own alerts server, below | Alerts are off |
| `POLARIS_CONTACT` | A URL or email of yours; also settable per phone in Settings | The User-Agent names this project's page |

## Android (early: first screens)

`android/` is a Kotlin port in progress. So far it holds the engine: the models, the TfL,
Transitous and Google providers, merging, ranking, TfL's network status and Google's monthly
count, with the same names and behaviour as the Swift, and the same request budgets. Around it is
the plumbing and the first screens:

- **Map:** MapLibre Native over OpenFreeMap's vector tiles, keyless, in Polaris's own day and
  night style (`MapStyle`), drawing a journey's legs in their line colours, its stops, the gold
  star at the end and your location dot (asked for only when a screen needs it). OpenFreeMap's
  credit is always shown on the map. Tiles are fetched only for what is on screen and kept in
  MapLibre's default cache: no offline packs, no prefetching.
- **Place search** (`PlaceSearch`): Transitous's own geocoder, as you type, debounced (below).
  Searching for a chain or a kind of place, which Apple's search does on iOS, will hand off to a
  maps app instead.
- **Settings storage:** keys, the contact and the alerts server's token sealed with an Android
  Keystore key; ordinary preferences in DataStore; the same precedence as iOS (entered, else
  built in, else none). Nothing is backed up or moved to a new phone.

- **Screens:** one map with one sheet over it, as on iOS: Home (where to, Home and Work, recent
  places), the place search, and the routes for a trip (from and to with swap, leave now, depart
  at or arrive by, the order and filters, remembered between searches, TfL's notices, and each
  source's progress). The sheet rests low, at half or full height; pages push inside it, and
  system back and predictive back return through them. Unfolded wide or on a tablet in landscape,
  the sheet is a card on the leading half and the order and filters dock on the map. Location is
  asked for only when you plan from where you are, tap the location button or pick Current
  Location.
- **Journey page:** a route's times, fare and when to leave, Share, and the itinerary with a
  departure strip under each ride (yours in gold, what missing it costs, a sooner one worth
  running for) opening the full board; who found it and the hand-off to Citymapper or Google
  Maps. Wide, a ride's board docks on the map beside the itinerary. Home lists what's leaving
  near you once location is allowed, each line opening its next departures.
- **Settings and setup:** appearance, sources, deep search, the Google and TfL keys and the
  Transitous contact (sealed as above), saved places, the data credits in full and the
  open-source licences. A new install sees setup once; everything in it is optional and can be
  added later in Settings. Live buses come next.
- **Alerts for your lines:** the list, the editor (lines and stretches, which way, days, times
  each with its own way, minor delays, a name), "Your lines" on Home, the bell on a route and
  the alerts server in Settings, as on iOS. Notices come through Firebase Cloud Messaging, on
  three notification channels: closures and delays (which you can let through Do Not Disturb in
  the phone's settings, Android's nearest thing to Time Sensitive), minor delays and planned
  works, and quiet updates. A later notice for an alert replaces the earlier one, and tapping
  one opens Alerts. Push needs your own Firebase project (below); without one the app builds and
  runs, and Alerts says notifications aren't set up while still showing what TfL says.

Debug builds add a second launcher entry, "Polaris map check", and can replay recorded answers
instead of asking TfL or Transitous (`--es demo routes`; see `app/src/debug/.../Variant.kt`);
neither is in release builds. Third-party licences for the Android app (the icons, MapLibre, the libraries) are in
`android/THIRD_PARTY_NOTICES.md`.

The engine needs only JDK 17; the app also needs the Android SDK (platform 36), from Android
Studio or the command-line tools. Without an SDK, Gradle builds and tests the engine alone.

```sh
cd android
./gradlew test                                     # the engine's tests (and the app's JVM tests with the SDK), offline
adb devices                                        # see what is connected first
ANDROID_SERIAL=emulator-5554 ./gradlew :app:installDebug   # onto that one device only
```

Gradle's install and connected-test tasks (`installDebug`, `connectedDebugAndroidTest`) go to
**every** connected device and emulator at once, including a phone that is only plugged in to
charge. Always set `ANDROID_SERIAL` to the one you mean (the name `adb devices` prints).

Per-builder values go in `android/local.properties` (git-ignored; copy
`local.properties.example`) or as Gradle properties (`-Ppolaris.contact=…`): `polaris.applicationId`
(default `com.example.polaris`), `polaris.contact` (for the User-Agent, as `POLARIS_CONTACT` on iOS, and like it defaulting to this
project's page: set your own if you distribute the build),
the optional `polaris.googleKey`, `polaris.tflKey`, `polaris.alertsUrl` and `polaris.alertsToken`,
and `polaris.mapTiles` and `polaris.mapGlyphs` to use your own OpenMapTiles-compatible tile server
instead of OpenFreeMap. With none of them set the build works as the iOS one does with no keys.

`ANDROID_SERIAL=<emulator> ./gradlew :app:connectedDebugAndroidTest` runs the few checks only a
device can do (the Keystore, DataStore, and the sheet's pages with back) on that emulator. They use their own files and Keystore
alias, never the app's, so a debug install's entered keys are left alone.

Build-time keys are compiled into the app (`BuildConfig`), where anyone with the `.apk` can read
them, just as on iOS. A Google key for Android is restricted to your application ID and the SHA-1
of your signing certificate; the app sends both with each request, and neither is a secret, so the
restriction only stops casual reuse. Restrict the key to the Routes API, set a daily quota cap in
the Google Cloud console, and prefer entering keys in the app's Settings, which seals them.

### Push on Android: your own Firebase project

Android has no push without Google's Firebase Cloud Messaging (FCM), and FCM only delivers to an
app from the Firebase project that app was built with. So, as with the APNs key on iOS, each
person who builds Polaris for Android and wants notifications uses a Firebase project of their
own (free: FCM has no charge on Firebase's no-cost plan). It gives two files, one for the app
and one for the alerts server:

1. In the [Firebase console](https://console.firebase.google.com), create a project (Analytics
   isn't needed) and add an Android app to it whose package name is exactly your
   `polaris.applicationId` (`com.example.polaris` unless you set your own).
2. Download the `google-services.json` it offers and put it at `android/app/google-services.json`.
   It is git-ignored; `android/app/google-services.json.example` shows its shape. Rebuild the app.
3. For the server: Project settings, Service accounts, Generate new private key. That downloads
   a JSON key file; give it to your alerts server as the `FCM_SERVICE_ACCOUNT` secret (step 9
   under "Run your own alerts server"). Keep the file out of the repository and delete your copy
   once the secret is set.

The app reads the first file at build time (`app/build.gradle.kts`); the Google Services Gradle
plugin isn't used, because it fails a build that has no such file. With no file, a file for
another package name, or the example's placeholders, the build says so and makes an app without
push. Nothing is asked of Firebase until you save an alert with an alerts server set: its
start-up provider is removed, its registration at launch and Analytics collection are switched
off in the manifest, and a phone is known to FCM only by a Firebase installation ID, which is
what the alerts server keeps. Phones without Google Play services can't receive FCM; Alerts says
so there too.

Firebase's and Google Play services' client libraries come with this: the Firebase ones are
Apache-licensed, the Play services ones are Google's own (the Android Software Development Kit
licence), so an Android build with them is not made only of open-source parts
(`android/THIRD_PARTY_NOTICES.md`).

To see a notice without any of this, a debug build can draw one locally, through the same code a
real one goes through (`adb shell am broadcast -n <app id>/polaris.android.debug.NoticeCheckReceiver
-e alert A -e title "Home to work" -e subtitle "Jubilee line: severe delays" -e body "…" -e level
urgent`), and `--es alerts lines` opens the alerts pages on made-up answers (`Variant.kt` lists the
states). Neither is in release builds, and neither proves that a push arrives.

The engine's tests read the recordings in `PolarisTests/Fixtures` in place, and both platforms'
tests assert against `PolarisTests/Fixtures/journey-expectations.json`: for a recorded King's Cross
to Canary Wharf search and two hand-written TfL answers (an arrive-by search on a day with
disruptions, and one in summer time), each leg's mode, line, stops, times, platform and stop count,
the fares, the merged groups and TfL's notices on them, and the order under every sort and several
filters. A change to one side's parsing, merging or ranking of those cases that the other doesn't
make fails a test; the rest of each engine is covered by its own tests.

## Run your own alerts server

Alerts need a server that watches TfL while the app is closed and sends pushes. There is no
shared one: each person who wants alerts runs their own `worker/`, which fits in Cloudflare's
free plan for a handful of phones. Without one, the app makes no alert requests and doesn't
ask for push permission; the Alerts page says what to set up.

You need a Cloudflare account, Node 24 or later with pnpm, and, for the pushes themselves: for
iPhones, a paid Apple Developer Program membership (only that can make the APNs key that lets
your server send notifications to your build of the app, and sign the app with the push
entitlement); for Android phones, a Firebase project of your own (free; "Push on Android"
above). Either can be left out: phones of the other kind are still told.

1. `cd worker && pnpm install`, then `pnpm exec wrangler login`.
2. Create the database: `pnpm exec wrangler d1 create polaris-alerts`. Copy
   `wrangler.local.example.jsonc` to `wrangler.local.jsonc` (git-ignored) and put the printed
   `database_id` in it. `pnpm run deploy` and `pnpm run migrate` lay that file over
   `wrangler.jsonc`, and refuse to run while the id is still the placeholder.
3. In the same file set `APNS_TEAM_ID` (your team ID) and `APNS_TOPIC` (exactly your
   `POLARIS_BUNDLE_ID`). Add a `routes` entry only if you want your own domain; otherwise the
   Worker is served at `polaris-alerts.<your-subdomain>.workers.dev`.
4. `pnpm run migrate` creates the tables.
5. Make an APNs key: developer.apple.com, Certificates, Identifiers & Profiles, Keys, a new
   key with Apple Push Notifications service (Sandbox & Production). Download the `.p8` once.
6. Set the secrets:
   ```sh
   openssl rand -hex 32                                              # copy this: it's APP_TOKEN, for the app too
   pnpm exec wrangler secret put APP_TOKEN                           # paste it
   pnpm exec wrangler secret put APNS_KEY < AuthKey_ABC123DEFG.p8    # the whole key file, read from stdin
   pnpm exec wrangler secret put APNS_KEY_ID                         # the key's 10-character ID
   pnpm exec wrangler secret put TFL_APP_KEY                         # optional
   ```
7. `pnpm run deploy`. Check `https://<your worker>/health` says `"pushes":true` (iPhones) and,
   if you set up step 9, `"android":true`. `"pushes":false` means one of `APNS_KEY`,
   `APNS_KEY_ID`, `APNS_TEAM_ID` or `APNS_TOPIC` is missing; `"android":false` that
   `FCM_SERVICE_ACCOUNT` is missing or isn't the service-account file. Until a kind of phone can
   be pushed to, the cron judges nothing for phones of that kind, so no disruption is lost.
8. Point the app at it: in Settings, Alerts server, enter the address and the `APP_TOKEN`
   and tap Use this server. Or build it in with `POLARIS_ALERTS_URL` and
   `POLARIS_ALERTS_TOKEN` in `Config/Secrets.xcconfig` (on Android, `polaris.alertsUrl` and
   `polaris.alertsToken` in `android/local.properties`). Then Alerts, Add a line, and
   Send a test notification.
9. For Android phones: give the server your Firebase project's service-account key file (from
   "Push on Android" above), read from the file so it never passes through your shell history:
   ```sh
   pnpm exec wrangler secret put FCM_SERVICE_ACCOUNT < your-project-firebase-adminsdk.json
   ```
   No redeploy or migration is needed: a phone's kind is told from what it registers with (an
   iPhone's APNs token, or an Android phone's Firebase installation ID), and the database is as
   it was. The server signs in to Google with that key, keeps the access token it gets for 50
   minutes, and sends through FCM's HTTP v1 API; a phone FCM says is unregistered is forgotten,
   as a dead APNs token is.

The server only accepts requests with its `APP_TOKEN`, and each install sends its own random
secret, so only the install that registered a phone can change, test or remove it.
`MAX_DEVICES` (a var, default 100; `0` takes no new phones) caps how many phones it keeps.

Nothing changes until you tap Use this server (or finish the setup step with an address and
token filled in); leaving Settings with a half-typed server keeps the one in use. Changing
server first removes this phone from the old server, using the old server's own token and
secret, then registers your saved alerts with the new one. Each server
gets its own device secret and the token entered with it, never the built-in one, so nothing
of one server's is sent to another. If the old server can't be reached, Settings says so: it
may keep pushing until you take it down or delete the phone's row.

Development: `pnpm test` runs offline against the recorded TfL responses in
`PolarisTests/Fixtures`, which the app's tests share (`alert-contract.json` is the JSON the
app sends), and `pnpm exec tsc --noEmit` type-checks. To run it locally, put `APP_TOKEN=`
some test value in `.dev.vars` (see `.dev.vars.example`), `pnpm run migrate:local`, then
`pnpm run dev`; the simulator reaches it at `http://localhost:8787` (plain http is accepted
for localhost only). To use recorded TfL answers instead of TfL, serve them over HTTP and add
`--var TFL_BASE:http://127.0.0.1:<port>`. With no push key of either kind it registers phones and
answers what TfL says, but judges and sends nothing. An Android debug build reaches a local
server at `http://localhost:8787` after `adb reverse tcp:8787 tcp:8787` (plain http to localhost
is allowed in debug builds only).

## Being a good client

Every source here is free or has a free tier, run by people who ask for light use. Polaris
keeps to these limits, and changes should too:

- **Transitous** (volunteer-run): one request per search, never fanned out into variants.
  Departure boards are one stop-times request per board as it is shown, kept a minute;
  during Go only the next ride's board refreshes, once a minute. What's leaving near you is one
  stop-times request within 400 m, only once location is allowed, kept a minute (and until you
  move 150 m); Refresh asks again. A descriptive User-Agent
  with `POLARIS_CONTACT`. Android's place search asks its geocoder only once the text is 3
  characters or more and typing has paused for 300 ms; a newer keystroke cancels the request
  in flight, and the last 20 answers are reused rather than asked again.
- **OpenFreeMap** (Android's map tiles, run by one person, free with attribution): tiles only
  for what is on screen, MapLibre's default cache, no offline downloads or prefetching, and
  the app's User-Agent on every request. Its credit is always visible on the map.
- **TfL**: network status is two requests per search, kept three minutes. Live buses are one
  arrivals request every 30 seconds while a bus line is on the map, and each line's route
  once per launch. The alerts server asks TfL only while someone is inside their travel
  times: one line-status and one station-disruptions request per two-minute run, cached a
  minute, plus planned works once an evening, and a line's station order (about 200 KB) only
  for lines a saved alert needs, kept a week.
- **Google**: kept inside the free monthly allowance (counted per Google month on the phone:
  deep search asks five ways under 700 requests, one under 900, none past 900).
- **Re-plans during Go** happen only on an event or a tap, never on a timer: at most three
  searches a trip, four minutes apart, none without a network.

## Security

- A Google key set at build time ships inside the app, and its iOS restriction is only a
  header anyone can copy. Prefer entering it in Settings, restrict it to the Routes API,
  cap its daily quota and set a billing budget alert. For a wider audience, put it behind a
  small proxy instead.
- The alerts server's `APP_TOKEN` is a password for your deployment, shared by everyone you
  give a build or the token to; it is not per-person sign-in. Whoever has it can register
  phones (up to `MAX_DEVICES`) and ask for status, which makes the Worker ask TfL and fetch line
  orders, though they can't change or remove another install's phone (that takes the install's
  own secret). Status isn't tied to a registered phone because the app asks for it before a
  phone has registered (or when notifications are off). Give the token only to people you
  trust, and change it (`wrangler secret put APP_TOKEN`, then in the app) if it gets out.
- Keys and tokens entered in the app stay in the Keychain on that phone and go only to their
  own service. Alert requests refuse redirects and plain http (except localhost).
- Report a security problem through the repository's private vulnerability reporting
  (Security tab, Report a vulnerability), not in a public issue.

## Tests

Everything runs offline: the app's tests parse recorded TfL and Transitous responses and a
hand-written Google Routes answer in `PolarisTests/Fixtures` (Google's terms don't allow
republishing its responses; `scripts/make-google-fixture.py` writes that one), and the Worker's
tests share the TfL recordings.

```sh
xcodebuild test -project Polaris.xcodeproj -scheme Polaris -destination 'platform=iOS Simulator,name=iPhone 17'
cd worker && pnpm test && pnpm exec tsc --noEmit
(cd android && ./gradlew test)
```

If a recording is ever replaced, check it holds no key, token or personal location before committing it.

## Licence

MIT. See `LICENSE`.

## Layout

- `Polaris/Providers`: one file per source: request, response DTOs, mapping to `Journey`; `DepartureProvider` for boards and nearby departures; `TfLBuses` for live bus positions (`LiveBusStore` keeps them fresh).
- `Polaris/Engine`: `JourneyMerger` (same journey from several sources), `JourneyRanker` (sorts, filters, folding), `SearchModel` (fan-out, live status), `LiveTrip` and `PathProgress` (Go: legs, walking progress, connections).
- `Polaris/Views`: `RootView` (map plus sheet), `MapCanvas`, then one page per step: `HomeSheet`, `ResultsSheet`, `JourneySheet`, `BoardSheet`, `LineSheet` (a line at a stop near you, live), `LiveGuide`.
- `Polaris/Shared` and `PolarisWidgets`: the trip's Live Activity (the attributes are compiled into both targets).
- `worker/`: the alerts server: `match.ts` (which disruptions touch your stretch), `policy.ts` (when to tell you), `checks.ts` (the cron), `push.ts` (what a notice says), and `deliver.ts` with `apns.ts` (iPhones) and `fcm.ts` (Android phones).
- `PolarisTests`: parsers against recorded Google/TfL/Transitous responses, boards, walking progress and leg-by-leg tracking, and the merge and ranking rules.
- `android/`: the Kotlin port so far: `engine/` (plain Kotlin, no Android imports, so its tests run on the JVM: also `PlaceSearch`, `PlaceStore`, `Snapshot`, `MapStyle`, `JourneyMap` and the settings storage rules) and `app/` (Compose: `shell/` for the map, the sheet and its pages, `home/`, `search/` and `results/` with their view models, `map/PolarisMap`, `storage/`, `alerts/` for the alerts pages and the push service; the debug-only map check and recorded answers in `src/debug`).
- `Polaris/Design/Theme.swift`: the star-atlas palette, light and dark (System, Light or Dark in Settings).

Launch flags for checking screens without tapping (Debug only): `-setup` shows the first-run setup step; `-demo -demoReplan
offer|wait|early` opens Go on a canned trip early onto the Bakerloo, with canned boards
and a canned planner answer, and asks no source at all; `-demo` plans King's
Cross → Canary Wharf, `-demoDetail` opens the best journey, `-demoGo` starts it,
and `-demoLeg N` starts Go at step N (from 0).

# PikaSync (Android POC)

Pikabook proof of concept: **can a phone automatically turn a month of photos into
a printable photobook, in the background, for pennies?** This is the Android twin
of [pikasync-poc](https://github.com/travisseh/pikasync-poc) (iOS) — same pipeline
shape, same server judge, platform-native stages.

Android's background story is *stronger* than iOS: `JobScheduler` content-trigger
wakes fire seconds after a new photo lands (no iOS equivalent), with WorkManager
periodic jobs as the steady arm.

## Architecture

Everything heavy runs on-device; only ~2–3 small contact-sheet JPEGs go to the
judge server (`https://pikasync-judge.vercel.app/api/judge`, source in the iOS
repo's `server/`), which holds the Anthropic key. No key ships in the app.

Judging uses the synchronous `/api/judge` endpoint — WorkManager's generous
background budget fits the whole call (the async submit/collect endpoints exist
for iOS's 30s wakes and are available if ever needed here).

Pipeline stages (`app/src/main/java/com/travisse/pikasync/pipeline/`):

1. **Ingest** — MediaStore month query by `DATE_TAKEN` (falls back to `DATE_ADDED`)
2. **Scoring** — ML Kit face detection + sharpness/exposure heuristics, cached in
   `ScoreCache` so onboarding prescoring and repeat runs are cheap
2b. **Face identity** — ArcFace `w600k_mbf.onnx` via ONNX Runtime, aligned from
   ML Kit landmarks (embeddings match the iOS Core ML port at 0.94–0.98 cosine,
   so person clusters are cross-platform); clusters persist in `files/people.json`
   (name / star = required / exclude), driving stranger drops and starred seats
3. **Document gate** — ML Kit text recognition drops letters/receipts/screenshots
   by text-block density (tuned against a real IRS letter)
4. **Burst dedup** — time window + aHash similarity
5. **Coverage shortlist** — week floors, no-face quota, person rules
6. **Scene collapse** — union-find clustering (aHash Hamming ≤ 8 within 6h), max 3
   representatives per cluster
7. **Contact sheets** — 4×4 labeled grids, 420px cells; JPEG quality steps down
   (70→50→35) to stay under the server's 4.5MB request cap
8. **Judge** — server-side claude-sonnet-5; book size
   `max(4, min(poolScaled, 2×sessions, sceneClusters))`
9. **Scene check** — deterministic post-judge duplicate check; corrective retry at
   ≥2 residual pairs (1 tolerated for major events)

## UX

Airbnb-style design system (tokens mirrored from `pikasync-poc/DESIGN.md`):
image-forward Books gallery, square-spread book viewer with per-photo feedback,
bottom sheets, coral FAB → "Create Photobook" sheet with a live loading entry.
First-run onboarding: welcome → permission priming → scan of the 200 most
recent photos (prescores last month in the background while the user picks) →
top-10 people grid with top 3 pre-selected → explicit "Make my first book"
step. Books auto-share silently after creation (5 concurrent 1600px uploads);
share/feedback buttons show a spinner only if tapped before the link exists.
Analytics: PostHog (`Analytics.kt`, same event taxonomy as iOS/web).

## Background generation

`AutoBook.kt`: once per calendar month, the WorkManager sync job also generates
LAST month's book (full pipeline in one wake — Android's budget fits it),
posts a notification, and beacons to `ntfy.sh/pikasync-android-trav-8347`.
Success marker `autoBook-yyyy-MM` (SharedPreferences) is set only on success, so
failures retry next wake.

## Build & run

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` (gitignored) needs `sdk.dir`; the legacy `ANTHROPIC_API_KEY`
entry is unused now that judging is server-side.

Tester distribution is Firebase App Distribution (project `pikabook-poc-7h3k`,
group `pikabook-testers`):

```bash
npx -y firebase-tools appdistribution:distribute   app/build/outputs/apk/debug/app-debug.apk   --app 1:873775304604:android:f788963bc0be2782aec259   --groups pikabook-testers --release-notes "..."
```

## Observability & testing

- Run log: `adb exec-out run-as com.travisse.pikasync cat files/last-run.json`
  (status, error, stage timings for every run — manual or background)
- Force the background job:
  read the current job id from `adb shell dumpsys jobscheduler | grep pikasync`
  (it changes on every re-enqueue), then
  `adb shell cmd jobscheduler run -f com.travisse.pikasync <id>`.
  To re-test auto-book in the same month, first clear the marker:
  `adb shell run-as com.travisse.pikasync rm shared_prefs/autobook.xml`
- Emulator test photos live in `/sdcard/Pictures/PikaTest`

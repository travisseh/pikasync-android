# Agent notes — pikasync-android

## Non-negotiables

- **Never commit `local.properties`** (gitignored; contains sdk.dir and a legacy
  API key). No secrets in tracked files — judging is server-side and the app
  ships no key.
- Do not commit/push without explicit authorization from Travisse.

## Build

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

SDK at `~/Library/Android/sdk` (set `ANDROID_HOME` if gradle can't find it).
Emulator: AVD `pika`, usually `emulator-5554`, test photos in
`/sdcard/Pictures/PikaTest`.

## adb tricks

- Pull run outcomes: `adb exec-out run-as com.travisse.pikasync cat files/last-run.json`
- Force background job: job id changes on every periodic re-enqueue — read it
  fresh from `adb shell dumpsys jobscheduler | grep -A2 pikasync`, then
  `adb shell cmd jobscheduler run -f com.travisse.pikasync <id>` (stale ids no-op
  silently).
- Reset the monthly auto-book marker before re-testing:
  `adb shell run-as com.travisse.pikasync rm shared_prefs/autobook.xml`
- Wake beacons: `ntfy.sh/pikasync-android-trav-8347` (~12h retention; the
  in-app wake log is the durable record).
- Distribute to testers: `npx -y firebase-tools appdistribution:distribute
  app/build/outputs/apk/debug/app-debug.apk --app
  1:873775304604:android:f788963bc0be2782aec259 --groups pikabook-testers`
- `run-as` file reads work via `adb shell "run-as … cat"`; `exec-out` variants
  can fail on the emulator.

## Conventions

- Mirror pipeline-rule changes with the iOS repo (pikasync-poc) and the Mac eval
  harness — the three implementations share stage semantics and thresholds; if
  you change a rule here, flag the other two.
- MediaStore ingest filters by `DATE_TAKEN` with `DATE_ADDED` fallback — don't
  "simplify" to `DATE_ADDED` (it's the file-copy time, wrong for synced photos).
- Judge server contract and its gotchas (4.5MB body cap, reasoning-vs-max_tokens,
  async submit/result endpoints) are documented in the iOS repo's AGENTS.md; the
  server source lives there (`server/`).
- Face identity must stay embedding-compatible with iOS (same `w600k_mbf`
  weights, same 5-point alignment) — swapping either side breaks cross-platform
  person clusters.
- PostHog events (`Analytics.kt`) share names with iOS/web — don't rename
  unilaterally; no photo content/filenames in properties.

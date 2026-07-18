# T9 V2 quality and performance report

## Scope

The reproducible JVM suite is implemented in
`keyboard-engine/src/test/kotlin/com/example/devicesync/keyboard/engine/T9QualityBenchmarkTest.kt`.
Its input is the repository-owned anonymous RU/EN corpus
`keyboard-engine/src/test/resources/t9_quality_corpus.tsv`. The corpus contains synthetic,
generic phrases only; it does not contain user messages, clipboard data, contacts or logs.

Run:

```powershell
gradlew.bat :keyboard-engine:test --tests "*T9QualityBenchmarkTest" --console=plain
```

## CI regression thresholds

| Metric | Threshold |
|---|---:|
| Top-1 suggestion accuracy | >= 70% |
| Top-3 suggestion accuracy | >= 95% |
| Typo correction recall | >= 75% |
| False corrections for known corpus words | 0% |
| Keystroke savings | >= 25% |
| Warm suggestion latency p95 | <= 50 ms |
| Cold 20k-word index build | <= 2500 ms |
| Approximate heap growth for 20k-word index | <= 96 MiB |

The thresholds are executable JUnit assertions, so a regression fails the normal test task and CI.

## Latest local JVM result

Run on 2026-07-16:

| Metric | Result |
|---|---:|
| Top-1 suggestion accuracy | 100% |
| Top-3 suggestion accuracy | 100% |
| Typo correction recall | 81.8% |
| False correction rate for known words | 0% |
| Keystroke savings | 45.2% |
| Warm latency p50 / p95 / p99 | 0.009 / 0.039 / 0.229 ms |
| Cold 20k-word index build | 271 ms |
| Approximate heap growth | 40.8 MiB |

JVM timings are regression signals, not substitutes for physical-device IME measurements.

## Runtime instrumentation

Debug builds emit privacy-safe aggregate counters under the `DeviceSyncImePerf` tag:

- key handler duration;
- key-down to `commitText`;
- key-down to next draw;
- suggestion request duration;
- stale suggestion result count;
- keyboard rebuild count;
- main-thread work exceeding 8 ms.

No entered text, phrase, clipboard value or suggestion content is logged.

The key-down-to-commit product target is median <= 16 ms on a representative mid-range Android
device. JVM tests cannot validate Android rendering, `InputConnection`, vibrator drivers or OEM
scheduling, so this target remains part of the manual device matrix. Suggestions are computed on a
single bounded background executor; the main thread only commits text and applies the latest UI
result. Generation tokens reject stale results.

## Dictionary and privacy notes

- Bundled RU/EN frequency lists are attributed in `docs/KEYBOARD_THIRD_PARTY_NOTICES.md` and
  `third_party/frequencywords/`.
- User frequencies, learned word pairs, blacklist and optional grammatical-gender preference stay
  in app-private local storage.
- Sensitive/password and incognito fields do not read or update personalization.
- No typed phrase is written to logs or sent to an external suggestion service.

## Manual device matrix still required before release

Measure p50/p95/p99 key-down-to-commit and suggestion latency on at least one low-end and one
mid-range physical device, for cold/warm starts, RU, EN, code-switching, landscape and large font.
Use the existing `docs/KEYBOARD_MANUAL_TEST_MATRIX.md`; do not use the camera.

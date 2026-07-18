# DeviceSync Keyboard verification report

Date: 2026-07-16

## Scope

This report verifies the keyboard work requested in the recovery prompt. Emulator testing was
explicitly excluded during the final verification run. No physical Android device was connected,
so device-only checks are listed separately and are not reported as passed.

## Completed implementation

- Responsive weighted RU/EN, symbol and numeric layouts.
- Consistent row height, gaps, key radii, pressed state and bounded key widths.
- Immediate common-key commit on `ACTION_DOWN`; suggestions run on a bounded background executor.
- Cancellation of obsolete suggestion generations and a one-item executor queue.
- Debug latency counters for key handling, key-down-to-commit, key-down-to-draw and suggestions.
- Compact in-IME emoji panel with categories, search groups, recent items and favorites.
- Clipboard panel with sensitive/incognito field restrictions.
- RU/EN frequency dictionaries, prefix lookup, typo correction, contextual ranking, lightweight
  Russian morphology and local personalization.
- Password, email, URL and no-personalized-learning policies.
- Backspace correction undo and Unicode code-point deletion.
- Translator UI, state, commands, network calls, settings and translator-only tests removed.
- Accessibility descriptions for tools, special keys, categories, suggestions and emoji.
- Explicit `performClick()` accessibility contract for low-latency programmatic key views.

## Automated verification

Command:

```powershell
gradlew.bat cleanTest testDebugUnitTest :keyboard-engine:test \
  :keyboard-ime:testDebugUnitTest lintDebug assembleDebug assembleRelease \
  assembleDebugAndroidTest --console=plain
```

Results:

- Build: successful.
- JVM/unit tests: 192 passed, 0 failed, 0 errors across 42 suites.
- Android instrumented test APK: compiled successfully; not executed because no physical device
  was connected and emulator use was excluded.
- App lint: 0 errors.
- Keyboard IME lint: 0 errors.
- Accessibility `ClickableViewAccessibility` warning for keyboard touches: resolved.
- Debug APK: built.
- Minified release APK: built, aligned and signed with the local Android debug certificate for
  installable verification use.

## T9 quality and performance

Latest local JVM results:

| Metric | Result |
|---|---:|
| Top-1 accuracy | 100% |
| Top-3 accuracy | 100% |
| Typo correction recall | 81.8% |
| False correction rate for known words | 0% |
| Keystroke savings | 45.2% |
| Warm suggestion latency p50 | 0.0073 ms |
| Warm suggestion latency p95 | 0.0371 ms |
| Warm suggestion latency p99 | 0.2787 ms |
| Cold 20k-word index build | 173.1 ms |
| Approximate heap growth | 30.8 MiB |

These JVM numbers verify ranking regressions and background-engine performance. They do not replace
Android `InputConnection`, rendering, vibration or OEM scheduling measurements.

## APK

- Installable verification APK: `app/release/app-release.apk`
- SHA-256: `7558B2C2C40F1ADD070109033FEAE2A56D95D850B82422F1A85FA92D283AF619`
- Signature schemes: APK Signature Scheme v2 and v3.
- Certificate: local Android debug certificate, not a production release certificate.

## Device-only checks still required

The following cannot be honestly completed without a connected physical Android device:

- install and enable `DeviceSync Keyboard`;
- measured key-down-to-`InputConnection` p50/p95/p99;
- sustained fast RU/EN typing and held Backspace;
- visual geometry at multiple widths, orientations, font scales and navigation modes;
- emoji panel screenshots and height transition validation;
- TalkBack traversal and announcements;
- real vibration intensity and system haptic settings;
- low-memory process recreation and leak observation;
- execution of instrumented tests.

Use `docs/KEYBOARD_MANUAL_TEST_MATRIX.md` and store final evidence in
`docs/screenshots/keyboard/1.0.0/`.

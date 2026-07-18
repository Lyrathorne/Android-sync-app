# DeviceSync Keyboard visual QA

## Automated policy coverage

`KeyboardUiPolicyTest` verifies:

- emoji grid remains between 6 and 10 columns;
- emoji panel uses a compact portrait/landscape height budget;
- RU rows retain positive widths on narrow screens;
- punctuation and Enter keep usable relative widths;
- compact keyboard height budgets;
- Smart Strip changes tools to suggestions without adding a second row;
- cancelled/multi-touch presses do not remain visually stuck;
- navigation/gesture insets are applied once.

Emoji glyph size is specified in density pixels rather than unbounded scaled pixels. This prevents
large system font settings from making emoji overlap adjacent cells while TalkBack still receives a
full content description. Text actions use clamped sizes, one-line ellipsis and explicit touch
targets.

## Before/after capture protocol

The historical baseline supplied during development includes:

- `Screenshot_20260715-092425.png` — overlapping emoji cells;
- `Screenshot_20260715-100221.png` — oversized keyboard;
- `Screenshot_20260715-131711.png` — keyboard/system navigation overlap.

Final screenshots must be captured on a physical Android device after installing the current APK:

1. portrait RU letters, no number row;
2. portrait RU letters, number row;
3. emoji Smileys and Search;
4. clipboard panel with short and multiline entries;
6. landscape;
7. font scales 1.0 and 1.5;
8. gesture navigation and three-button navigation.

Store release evidence under `docs/screenshots/keyboard/<version>/`. Do not use the camera. Emulator
screenshots are not accepted as final release evidence for the current verification run.

## Acceptance checklist

- [ ] No clipped first/last key at 320, 360, 393 and 411 dp widths.
- [ ] Emoji cells do not overlap and have no oversized empty tiles.
- [ ] Recent, Favorites, Search and every category are reachable.
- [ ] Long press toggles a favorite; normal tap commits exactly one emoji.
- [ ] Back returns to letters without changing total window/inset placement unexpectedly.
- [ ] Press feedback appears on ACTION_DOWN and clears on UP/CANCEL in under 80 ms.
- [ ] Fast two-thumb typing produces no stuck keys, reordered characters or visible strip flashing.
- [ ] Bottom content remains above gesture/three-button navigation controls.
- [ ] TalkBack announces tool, category, suggestion and special-key purposes.

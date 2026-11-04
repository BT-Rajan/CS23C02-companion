# Design system (proposed, small by design)
**Tone:** calm, readable, little decoration. One primary action per screen.

| Token | Value |
|---|---|
| Grid | 4 dp; page margin 16 dp; touch targets ≥ 48 dp |
| Type | System sans for UI; monospace (bundled, e.g., JetBrains Mono or Noto Sans Mono) for code. Scale: 28 / 22 / 18 / 16 (body) / 14 (caption). Body line height 1.5; respect system font scale up to 200% |
| Colour roles | Surface, On-surface, Primary, Success, Error, Warning, Code-surface. All text/background pairs ≥ WCAG AA (4.5:1); never rely on colour alone (icon + text for correct/incorrect) |
| Modes | Light and dark from day one |
| Buttons | Primary (filled, one per screen), Secondary (outlined), Text. Labels are verbs. No icon-only controls without labels except system back and profile |
| Cards | Used only for Continue and unit/topic rows; no nested cards |
| Code block | Code-surface background, horizontal scroll (no wrapping), copy not offered for protected items, line numbers in editor |
| Editor | Auto-indent after `:`, 4-space tab key, a small symbol row (`: ( ) [ ] " # =`) above the keyboard, no autocomplete popups in release 1, undo/redo, **Run** and **Reset** only |
| Output | Separate pane, monospace, truncated with a notice; errors rewritten in plain language with the original traceback line collapsed below |
| Feedback states | Correct (Success + check icon), Incorrect (Error + cross icon + explanation), Pending (progress, never blocking), Offline banner (single line under the title) |
| Motion | Only functional (state transitions ≤ 150 ms); honours the system "reduce animations" setting |
| Accessibility | TalkBack labels, focus order, contrast checks in CI, tested at 200% font scale on a 360×640 dp screen |
Implementation: one Compose theme module; no per-screen styling.

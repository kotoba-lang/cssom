# Layout conformance: cssom.layout vs a real Blink browser

Differential testing against a real browser, because unit tests can only
check what someone thought to assert. This renders the same markup through
`htmldom` → `cssom.core` → `cssom.layout` and through a real headless
Brave/Chrome, and compares **line structure**: the ordered list of lines,
each line being the text that landed on it, left to right.

```bash
nbb --classpath "src:../dom-gpu/src:../htmldom/src" conformance/run.cljs \
  [--browser "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"] \
  [--width 800] [--only inline/] [--ledger path/to/ledger.edn]
```

## Why line structure and not pixels

This engine has no glyph shaping — widths come from a
`(long (* 0.6 font-size))` per-character approximation unless a host
supplies `:measure-text` (see `cssom.layout`'s namespace docstring). Its
absolute coordinates therefore *cannot* match a real font's, and comparing
them would measure the approximation rather than the layout. What text
shares a line, in what order, and how many lines there are is what both
engines agree on when the layout is right — and it is exactly what this
engine got wrong for all inline content until 2026-08-03.

Both sides are measured **per word** (a `Range` per word in the browser,
draw-op text split by the engine's own width model on this side) and then
grouped into lines by the *same* vertical-overlap clustering function, so
neither side gets a grouping rule the other doesn't. Comparison is
whitespace-normalized and case-folded (`text-transform` genuinely rewrites
what this engine emits, while a browser upper-cases at paint time — both
are correct).

## Result — 2026-08-04

**30/32 = 94%** (2 cases unscorable, see below), up from 27/32 = 84% at the
first run. Per group:

| group | | |
|---|---|---|
| inline | 11/11 | 100% |
| flex | 3/3 | 100% |
| grid | 2/2 | 100% |
| position | 2/2 | 100% |
| text | 3/3 | 100% |
| visibility | 2/2 | 100% |
| float | 1/1 | 100% |
| inline-replaced | 3/3 | 100% |
| block | 3/4 | 75% |
| table | 0/1 | 0% |

The two remaining failures are known, documented scope-cuts rather than
surprises: an inline box containing a block box is not split
(`block-in-inline`), and there is no table layout at all.

`inline-replaced` went 0/3 → 3/3 when `<img>`/`<input>`/`<button>`/
`<select>`/`<textarea>` became atomic inline boxes, sized by their own
intrinsic width and baseline-aligned by their bottom edge.

### Known divergence this metric no longer sees

A control's INNER text (a `<button>`'s label, an `<input>`'s value) is
excluded from the line comparison on both sides, because it belongs to that
control's own formatting context rather than to the line being measured.
That exclusion hides a real divergence found on the way here: this engine
paints a button's label at the top inset of its box, while a browser
centers it vertically. Measuring that needs a different axis — comparing
the control's own box and its content position — and is a candidate for the
next corpus dimension rather than something to pretend the current one
covers.

### Bugs found

The harness has already paid for itself twice. It found that a
`display: none` element in the middle of a sentence split the surrounding
text into two one-child inline runs, stacking `keep`/`this` on separate
lines where every real browser puts them on one. That bug was invisible to
497 passing unit tests. Fixed in the same change, with regression tests.

It also caught, via the downstream `kotoba-lang/browser` suite, an arity
error in the new `<select>` intrinsic-width path that this repo's own 502
tests never reached — a reminder that cssom's consumers exercise shapes its
own corpus does not.

## Two cases are unscorable, on purpose

Generated content (`::before`/`::after`, list markers) is not DOM text — a
real browser paints it from the box tree and no `Range` can reach it, while
this engine synthesizes it as real text. Those cases are marked
`:oracle/blind true`, excluded from the score, and printed, rather than
silently counted as failures. Their correctness is covered by unit tests.

## Oracle caveat (measured, not assumed)

Brave is the intended oracle and is tried first. On **Brave 151.1.93.129**
in this environment its headless mode produces *nothing*:
`--headless=new --dump-dom` exits 0 with zero bytes, `--headless=old` writes
zero bytes and never exits, and disabling Brave-specific features
(`BraveRewards`/`BraveAds`/`BraveVPN`/`BraveSync`/`SpeedReader`) does not
change that. The run therefore falls through to Chrome, which writes its
dump and *then* hangs — handled by `timeout -s KILL` plus reading the output
file rather than a pipe (Chromium's children keep stdout open, so a pipe
never reaches EOF).

This fallback measures the **same engine**: Brave is Chromium plus
network/privacy shields, and shields do not change layout. The oracle that
actually produced a number is printed and recorded in every ledger entry, so
the substitution is never silent.

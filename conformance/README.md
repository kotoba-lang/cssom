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

## Two axes

**Line structure** — what text landed on which line, in what order. This is
what the harness measured first, and it saturated at 93%.

**Geometry** (added 2026-08-04) — every element's own box, matched between
the two sides by tag and occurrence order and compared within 2px on all of
x/y/w/h. It exists because the line axis is blind to whole classes of
divergence: a `colspan` cell is alone on its row either way, a button's
label sits in the same line box whether it is centered vertically or not,
and an `<h1>` that renders at body size still lands on its own line. The
first geometry run scored **47%** on a corpus the line axis scored 93% on,
and its per-tag breakdown pointed straight at the causes.

## Result — 2026-08-04

**Line structure: 91/98 = 93%. Geometry: 199/325 element boxes (61%), 46/102
cases with every box in agreement.** The corpus has grown
34 → 98 cases. The series so far: 27/32 = 84% → 30/32 = 94% → 82/91 = 90%
(corpus tripled) → 91/98 = 93% (tables implemented). A percentage that
falls when the corpus grows is the corpus doing its job. Per group:

| group | | |
|---|---|---|
| inline | 18/18 | 100% |
| inline-replaced | 8/8 | 100% |
| table | 9/9 | 100% |
| flex | 9/9 | 100% |
| box | 5/5 | 100% |
| page | 5/5 | 100% |
| inline-block | 1/1 | 100% |
| visibility | 2/2 | 100% |
| block | 7/8 | 88% |
| grid | 6/7 | 86% |
| wrap | 6/7 | 86% |
| text | 8/10 | 80% |
| position | 4/5 | 80% |
| float | 1/2 | 50% |

The seven remaining failures are all genuine gaps, now measured rather
than asserted: no float positioning, no `block-in-inline` split, `fixed`
anchored to its containing block rather than the viewport, grid
auto-placement not resuming after an explicitly placed item, one wrap
point inside a nested inline, and CSS-driven `white-space: pre-wrap`/
`pre-line` (the parser collapses newlines before layout ever sees them —
it cannot see CSS).

### What the geometry axis found in its first hour

- A table filled its container instead of shrink-wrapping to its columns
  (`width: auto` on a table is shrink-to-fit in real CSS). One decision put
  every `<table>`, `<tr>` and row-group box in the wrong place: table 0/9,
  tr 0/15.
- Row groups had no box at all — `<thead>`/`<tbody>`/`<tfoot>` were
  flattened away, so `tbody` scored 0/9 against a browser that has a box
  there.
- No UA stylesheet whatsoever: `<b>` was not bold, `<em>` was not italic,
  every heading rendered at body size. Authors never write those rules.
- `border-spacing: 2px` and `td { padding: 1px }` — Chrome UA defaults —
  were absent, which is exactly why a two-cell table measured 49x20 here
  against the browser's 59x26.
- `line-height: normal` was a flat theme constant rather than ~1.2x the
  font size, so an `<h1>` at 28px got a 20px line box and the next block
  painted on top of it. This one only became visible once headings had
  their UA size.

After fixing those: geometry 47% → 56%, td 6/29 → 21/29, tr 0/15 → 9/15,
table 0/9 → 5/9, tbody out of the worst-tag list entirely.

### Round two on the geometry axis (per-side box model)

The uniform-box-model ceiling is gone: `margin`/`padding` now expand to
four per-side longhands (real CSS's 1-to-4 value rule), so the UA
stylesheet's one-axis rules are expressible and applied — `p { margin: 1em
0 }`, heading margins from `.67em` to `2.33em`, `ul, ol { padding-left:
40px }`, `blockquote` side margins. `li` left the worst-tag list entirely.

Three further real rules had to follow, each one found by the oracle
disagreeing rather than by reading a spec:

- **Adjacent vertical margins collapse** to the larger of the two, not
  their sum. Without it every gap between paragraphs doubled.
- **A parent's first child's top margin collapses THROUGH the parent** when
  the parent has no top border or padding — which is why a browser puts the
  first `<p>` of a plain wrapper at the wrapper's own top edge, not 1em
  below it.
- **An inherited explicit `line-height` beats the `normal` floor.** The
  1.2em floor added last round is right for `normal`, but a container
  saying `line-height: 20px` means 20px for the 28px heading inside it too,
  however cramped — the engine reported a 33px box where Chrome reports 20.

And one plain bug the same work exposed: `layout-block` added a child's
margin AGAIN after the parent had already positioned it — a double count
that was invisible while every margin was zero.

Inline boxes now report the font's **content area** (~1.2em, centred in the
line box by half-leading) rather than the line box, matching what a browser
reports for a `<span>`/`<a>`/`<b>`.

### Still open on the geometry axis

`b` 0/11 is a WIDTH disagreement, not a box-model one: the harness feeds
the oracle's own measured bold character advance into `:measure-text`, and
the engine still comes out ~40% wide on bold runs. That is a harness-vs-
engine metric question worth isolating before assuming which side is wrong.

`div` 75/101 and `td` 21/29 are the remaining tail, not yet diagnosed.

### Known divergence the LINE axis does not see

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

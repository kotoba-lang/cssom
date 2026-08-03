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

**Line structure: 95/98 = 97%. Geometry: 284/325 element boxes (87%), 75/102
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

### The bold-width mystery: the harness was wrong

`b` scored 0/11 and it looked like an engine bug. Measured directly in the
browser instead of assumed:

| | per character |
|---|---|
| `<b>manual</b>` as rendered | 7.94px |
| 40 `M`s, bold, same font/size | 11.05px |
| 40 `M`s, regular | 7.00px |
| plain text as rendered | 7.00px |

This system's `monospace` face is fixed-pitch in **regular** and
**proportional in bold**. The harness's single-character probe used `M` —
the widest glyph — so it overstated every bold run by ~40%. The engine was
being fed a wrong number and faithfully laying out to it.

The probe is now a **per-character advance table** (ASCII 32–126, normal
and bold) measured in the oracle and summed by `:measure-text`. b 0/11 →
4/11, a 3/12 → 8/12, td 21/29 → 23/29, and one line-structure case
recovered as well.

The lesson is the one the loop keeps teaching: measure before attributing.

### Then: a real box-model bug the axis exposed

`div{width:300px;padding:16px}` reported 300px wide with 268px of content,
where a browser reports 332 and 300. This engine read a declared `width` as
the BORDER box in both modes; real CSS's default `box-sizing: content-box`
means it is the CONTENT width and padding/border add outside it. Fixed —
with the theme's own decorative padding deliberately excluded from that sum,
since it is a host styling choice and not CSS.

`<br>` also had no box at all (0/4); it now reports the same content-area
box every other inline element does.

### Reading the tail by DIMENSION, not by case

The harness now reports, per tag, WHICH of x/y/w/h disagrees, how often,
and the median delta. A tail is far easier to attribute from "always `w`,
always −750" than from a list of failing case names. Three causes fell out
of one run:

- **`div w −750`, `x −389`**: a form control as a flex item took the whole
  container. Intrinsic sizing lived only on the inline path; it is now
  shared, so an `<input>` is ~153px wherever it appears rather than 800.
- **`p h +8`, `p y +12.25`**: a harness asymmetry, not an engine error.
  The browser page sets `line-height: 20px` on the case container and the
  engine was never told, so it applied its own `normal` (1.2em) rule and
  gave an `<h1>` a 33px line box where the browser inherits 20. The engine
  wrapper now carries the same declarations.
- **`td`/`tr w +730`**: `colspan` was not implemented. A spanning cell sat
  in ONE column, making that column as wide as the spanning content. Now a
  spanning cell widens the columns it covers only if they cannot already
  hold it (sharing the shortfall), and is laid out across them plus the
  border-spacing that no longer separates anything. That case went from 1/7
  boxes in agreement to 7/7.

### Round three: the deltas name their own causes

With per-dimension deltas attributed to case ids, three more fell out:

- **A space belongs to the run that contains it.** The gap in
  `a <b>b</b>` is a space in the PARAGRAPH's font; this engine charged the
  *following* fragment's font, and since this system's bold face is
  proportional (3.88px space against the regular 7.00px), every following
  inline box landed ~3px left of where the browser draws it.
- **`display: flex` is a BLOCK-level flex container.** It fills its
  containing block; only its ITEMS shrink-to-fit. This engine shrink-wrapped
  the container, so a row of three one-character items was 21px wide where
  a browser reports 800 — and `justify-content` then distributed space
  inside that 21px box. Ten boxes, all of them scored as passes by the line
  axis.
- **The explicit-line-height flag was dropped on the way into inline
  boxes**, so a larger inline inside a declared `line-height: 20px` grew
  the line box to 1.2 × its own size instead of overflowing it the way a
  browser does.

### Round four

- **`justify-content` had no free space to distribute.** The main-axis size
  it centres within was the sum of the items, not the container's content
  width, so `justify-content: center` pinned every row hard against the
  left edge (items at x=0,7 where the browser centres them at 393,400).
- **An absolutely positioned box with `width: auto` is shrink-to-fit**, not
  fill-the-container: a corner-pinned label measured 800px against the
  browser's 21, covering the whole row it was pinned over.
- **Mixed inline content has a max-content width.** `go <b>now</b>` in a
  table cell fell back to the container width, so a two-cell table filled
  800px where a browser shrink-wraps to 72. Computed by reusing the inline
  fragments and tokenizer, so whitespace collapses exactly as it will when
  the run is really laid out.
- Harness: **italic is a third face.** This system's `monospace` is
  fixed-pitch in regular but proportional in BOTH bold and italic, so an
  `<em>` measured 7.0px/char here against the browser's 10.28.
- Harness: **boxes are matched by nearest, not by index.** The browser
  lists elements in document order while this engine emits draw-ops in
  PAINT order, so an absolutely positioned box (painted last) lined up
  against the wrong sibling and reported two mismatches where the boxes
  were identical.

### Round five, and where the line-box story stops

- **A line box's height comes from the line-heights on it, not the font
  sizes.** Real CSS lets a larger run OVERFLOW a line box its declared
  `line-height` made too small; this engine grew the box instead. An atomic
  inline is the exception — a replaced box cannot overflow its line, and a
  browser does grow the line to fit it.
- **A trailing `<br>` leaves no empty line box.** `<p>line<br></p>` is 20px
  tall in the browser; this engine produced a second, empty 20px line.

What this axis can no longer decide without new machinery: the browser
positions each inline box by the font's REAL ascent and descent and takes
their union, so `small <span style="font-size:24px">big</span>` gets a 24px
line box where the line-height rule alone says 20. Same for `<sub>`/`<sup>`,
whose vertical shifts are font-relative. This engine models no ascent or
descent at all — every inline box is approximated as 1.2em centred by
half-leading. Closing those cases means adding a font-metrics model (real
ascent/descent per face, measurable by the same probe that already measures
advances), not tuning constants until one browser on one machine agrees.
That is a deliberate stopping point, recorded rather than fitted.

### Round six: the last two structural gaps on the line axis

- **Grid auto-placement shares its cursor with explicitly placed items.**
  `<div style="grid-column: 2">right</div><div>next</div>` in a two-column
  grid put `next` beside and BEFORE the explicit item; a browser wraps it to
  the next row, because the cursor is already past column 2. The cursor now
  walks every child in DOM order and only ever moves forward.

- **Whitespace collapsing is a CSS decision, not a parser one.** The parser
  collapsed newlines along with spaces, which destroyed the information
  `white-space: pre-line`/`pre-wrap` need before layout ever ran — both were
  permanent failures for a reason no amount of layout work could fix.
  kotoba-lang/htmldom now keeps newlines (collapsing only space/tab runs)
  and cssom.layout collapses them for `normal`/`nowrap`, per the declared
  property. Both modes now match the browser.

The three remaining line-structure failures are all genuine engine gaps
with no ambiguity about what they are: no `block-in-inline` split, no float
positioning, and `fixed` anchored to its containing block rather than the
viewport.

### Still open on the geometry axis

`input` w is now within ~7px rather than ~650: the remainder is that a
browser does NOT inherit the page font into form controls (Chrome UA gives
them 13.33px Arial), so their intrinsic width comes from a font this
harness never measures.

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

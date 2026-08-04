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

**Line structure: 185/190 = 97%. Geometry: 603/719 element boxes (84%),
158/200 cases with every box in agreement**, on a corpus of 200.

That geometry number is one point BELOW the previous round's 87%, and it is
the right trade: see "the font-metrics model" below. The corpus has grown
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

### Round seven: floats

`float: left|right` is implemented, bounded and documented rather than
pretended: a floated box is blockified, taken out of the inline run, placed
against its container's edge, and NARROWS the content beside it for its own
height. Before this there was no float concept at all — a right-floated
badge sat at the START of the text (x=0 against the browser's 233 in a 240px
box). Three corpus cases were added (a tall float with text wrapping beside
it, two left floats side by side, and a left plus a right float); all three
agree with the browser on both axes.

Not implemented, and named: floats that appear AFTER other content in their
container (v1 places floats at the container's top, the shape real markup
almost always uses), floats stacking vertically when they do not fit side by
side, and `clear`.

### Round nineteen: grid span, and relative on a flex/grid item

Both named by the round-17 corpus expansion, both small once measured.

`grid-column: span 2` declares only a WIDTH — the item stays auto-placed
and occupies N tracks, and the shared cursor resumes after it. It was
previously parsed as a placement request and would have been treated as an
explicit one.

`position: relative` on a FLEX or GRID item was never applied: the shift
existed only in block flow, a scope-cut documented since relative
positioning landed. Both cases now agree with the browser on every box.

### Round eighteen: flex-grow and flex-shrink

Real flexbox distributes a line's FREE SPACE across its items — positive by
`flex-grow`, negative by `flex-shrink` weighted by each item's base size.
This engine froze every item at its base size, so `flex-grow: 1` (the most
common flex idiom on the real web) did nothing at all and over-wide items
overflowed instead of shrinking.

The distribution was the easy half. The bug that made it *look*
unimplemented after it was written: the `align-items: stretch` pass
re-measures each item at the CONTAINER width, which silently undid the main
-axis sizing. The re-measure at the final main size now runs after it.
`flex/grow-fills-the-row` went from 1/3 boxes to exact.

Two unit tests that pinned the old no-shrink behaviour are updated: three
90px items plus two 20px gaps want 310px of a 292px content area, and with
`flex-shrink: 1` — the default — they shrink to fit rather than overflow.

### Round seventeen: an inline box is its OWN font's height

A `<span>` wrapping a `<b>` is 15px tall in the browser — its own 14px face —
while the bold run inside it is 18. This engine sized every nesting parent
by its tallest child, because the box rect was accumulated from each
fragment's metrics rather than the owner's. One rule, +22 boxes: geometry
89% → 90% on the 150-case corpus, cases fully clean 122 → 127.

### Round seventeen (b): the corpus grows again, 150 → 200

98% line / 90% geometry was saturation for the third time. 50 more cases in
territory still unmeasured: flex sizing beyond the defaults (`flex-grow`,
shrink, `align-items: flex-end`, `space-around`, wrap with gap), grid flow
and spans (`span 2`, explicit rows, `minmax`, nested grids), sticky with an
offset, overflow with explicit heights, min/max-height, margin collapsing
through a border, deeper inline nesting, mixed row/col spans in one table,
table inside flex, label wrapping a control, and ten larger page shapes
(sidebar + main, card grid with images, article with a figure, data table,
inline form in a paragraph, two-column text, header/nav/main, blockquote
with attribution).

Scores on contact: line 98% → 96%, geometry 90% → 83%. What the new cases
name, all now measured rather than assumed:

- `grid-column: span 2` is not implemented.
- `position: relative` is still not applied to a FLEX item (a scope-cut
  documented since the relative-positioning round).
- an `inline-block` wrapping a block box took the container width — the
  empty/single-element natural-width rules added last round lived only in
  the flex path, not the atomic one. Fixed here.

### Round sixteen: one baseline rule for every atomic inline

The `p h +6` cluster was one rule again. An atomic inline's baseline is its
own LAST LINE's baseline — top inset, half-leading, ascent — for everything
except a replaced box, which alone sits on the baseline. That single rule
covers an `inline-block` (a browser reports a line holding a 20px one as
20px, where the bottom-edge reading stacks the strut's descent underneath
and gives 26), a form control (21px, not 27), and a `<textarea>`, whose
last line is `rows − 1` lines further down.

`<textarea>` also gained its own height: `rows` lines (HTML's default is 2),
where every other control is one — 34px against a 21px input, and a 40px
line box around it.

And the harness stopped measuring only ASCII. `©`, `·`, `›`, `—` and friends
were falling back to a guessed advance, which put the first link of a footer
1.4px off and the second 6.4px — small, but a mismatch the engine was blamed
for and never made. The advance table now covers ASCII, Latin-1 and the
typographic codepoints real page furniture uses.

### Round fifteen: a cell with nothing measurable in it

Two clusters with enormous deltas (`td w +727`, `tbody w +759`, `tr w +759`)
turned out to be one missing rule with two faces: a box whose content has no
measurable natural width fell back to the CONTAINER width.

- An **empty** `<td>` took 782px where the browser gives it 2 (its padding),
  so a single empty cell made its table fill the page.
- A `<td>` holding a **nested table** did the same, for want of a rule for
  "a single element child": measure it.

And measuring it exposed the follow-on: a TABLE must be laid out rather than
recursed into, because it already shrink-wraps itself and recursing finds
its rows while losing its border-spacing (37px against the browser's 41).

The nested-table case now agrees with the browser on **all nine boxes**, the
empty-cells case on all six, and the geometry axis went 86% → 89%.

### Round fourteen: vertical-align, on that foundation

The first rule the metrics model made implementable. Measured rather than
looked up: Chrome raises a 14px `super` run by 5.66px and lowers a `sub`
one by 3.79px — 0.404em and 0.271em, the font's own superscript/subscript
offsets, which a browser reads from the OS/2 table and this engine takes as
measured platform values.

`sub { vertical-align: sub }` and `sup { vertical-align: super }` are UA
rules too — an author writes the tag, never the declaration — so without
them a subscript and a superscript sat on the same baseline as the text
around them, which is the entire visual point of both tags. A shifted box
carries its whole vertical span with it, so it grows the line box in that
direction; the corpus case went from a 21px line box against the browser's
29.45 to 28, with `sup` at 4.13 against 4 and `sub` at 11.55 against 13.45.

`top`, `bottom` and `middle` are deliberately absent: each aligns against
the FINAL line box, which is not known until every other box on the line
has been placed, so they need a second pass this file does not have. They
keep the baseline default — which is what every value did before.

### Round thirteen: the font-metrics model

Round eleven stopped at a named prerequisite rather than fitting constants:
a browser positions each inline box by the font's REAL ascent and descent
and takes their union, and this engine modelled neither. That prerequisite
now exists.

Measured first, as the stopping note asked for. Chrome on this machine:

| face | ascent | descent | content area |
|---|---|---|---|
| 14px monospace | 12 | 3 | 15 (not the 16.8 a 1.2em guess assumes) |
| bold 14px monospace | 14 | 4 | 18 |
| 24px monospace | 21 | 5 | 26 |
| 13.33px Arial (controls) | 12 | 3 | 15 |

Working the CSS line-box rule by hand with those numbers reproduces the
browser exactly: for `small <span style="font-size:24px">big</span>` in a
`line-height: 20px` container, the strut contributes
`[baseline−14.5, baseline+5.5]` and the 24px run `[baseline−18,
baseline+2]` (its half-leading is NEGATIVE, which is why it overflows
rather than growing the line), union 23.5 ≈ the browser's 24.

So the engine gained a `:font-metrics` theme hook — the vertical
counterpart of `:measure-text`, with the same bargain: a host that HAS real
metrics supplies them, a host that does not keeps this file's documented
1.2em approximation BYTE FOR BYTE. The harness supplies them (canvas
`TextMetrics`), and its own text boxes are converted from the engine's
em-box convention to the content-area one the oracle reports, so the two
sides describe the same box in the same coordinates.

It also forced a rule that only becomes visible once the vertical model is
real: **an atomic inline's baseline is not always its bottom edge.** A
replaced box sits ON the baseline, but a form control's baseline is its own
internal text's — which is why a browser reports a line holding an
`<input>` as exactly 21px, where treating the bottom edge as the baseline
adds the strut's descent underneath and gives 27.

Cases that are now exact where they never were: `<b>` inline boxes
(63,1,47.63,18 against the browser's 63,1,47.64,18), the mixed-font-size
line box (24, previously 20), the input line (21).

And the honest part: the aggregate went 87% → 86%. The remaining vertical
rules — `vertical-align` for `<sub>`/`<sup>`, and a `<textarea>`'s own
baseline — are still missing, and with a REAL vertical model they now show
up as mismatches instead of being averaged away by an approximation that
was wrong in a compensating direction. The model is the foundation those
rules need; the point of this axis is to measure the engine, not to protect
a number.

### Round twelve: form controls do not inherit the page font

`input w +7` had sat in the residual for six rounds with a plausible story
attached to it. Measured directly instead: an `<input>` inside a
`font-family: monospace; font-size: 14px` container computes to
**`Arial 13.3333px`** in Chrome — controls do not inherit the page font at
all — with `padding: 2px; border: 2px` of their own (a `<button>` gets
`6px` horizontal and `1px` vertical padding; a checkbox is a bare 13×13
square with `margin: 3px 3px 3px 4px` and no padding or border).

So the answer to "which side is wrong" was: the ENGINE, and not in the way
the number suggested — it was not 7px of arithmetic error but a missing UA
rule. The engine now carries that font and box as UA defaults, names the
family so a host can measure it (the harness measures a fourth face for
it), and sizes a control's content box by its font-size rather than by a
line box. Two general box-model bugs fell out of the same work: neither
block nor control height added the border, which `box-sizing: content-box`
puts outside the content box in both axes.

input 0/9 → 5/9 boxes, geometry 84% → 85%, cases fully clean 115 → 118.

Both follow-ups from that round are now closed. A `<button>`'s label is
measured in the CONTROL font and its width counts the UA *horizontal*
padding (6px a side) rather than the uniform value — charging the uniform
padding left it 10px narrow even after the font was right. And an atomic
inline carries its own margins: a checkbox's UA `margin: 3px 3px 3px 4px`
now places it at x=4 y=3, exactly where the browser puts it, where before
the margins were counted in the line's advance but never applied to the box
itself (the line breaker rebuilt the piece and dropped them).

Geometry 85% → 87%, cases fully clean 118 → 123.

### Round eleven: rowspan, and the rule behind it

`rowspan` is implemented: cells are assigned their [row col colspan
rowspan] by an occupancy walk, the rows below skip the columns a spanning
cell still holds, a spanning cell grows the last row it covers when the
rows cannot hold it, and its own box spans every row it covers.

The browser's expectation for the corpus case named the rule that made it
work: **a table cell's UA default is `vertical-align: middle`**, so its
content is centred in the cell box — which is exactly what makes a rowspan
cell sit BETWEEN the rows it covers rather than at the top of the first
one. The browser renders `tall` (rowspan 2) on its own line between `a` and
`b`; this engine had it beside `a`.

### Round ten: what the widened corpus was hiding

Four real rules, each found by attributing a delta cluster to its cases:

- **A box that establishes a formatting context does not collapse margins
  with its children.** `overflow: hidden` is the obvious one — it is *why*
  authors reach for it — and a flex or grid ITEM is the same, decided by
  the parent rather than by a declaration. This one cluster was `p y −14`
  and `div h −30.75` across a dozen boxes.
- **A caption participates in its table's width.** The table grows to fit
  the caption's MIN-content (its longest word) and the caption then wraps
  inside that width. Measured: `Caption text` gave a 24px table here where
  the browser reports 49, with the caption overflowing it.
- **Whitespace-only text runs are the space between inline elements.** The
  parser dropped them entirely, so `<a>one</a>\n  <a>two</a>` rendered as
  `onetwo` — with no way for layout to recover the gap. kotoba-lang/htmldom
  now keeps them; layout drops the ones that would form a stray row between
  blocks, because that decision needs the box tree the tokenizer cannot
  see. Geometry 81% → 84% on this alone.

### Round nine: widening the corpus again

The line axis had reached 99% and geometry 88% on 105 cases — which is
exactly when a corpus stops earning its keep. 45 cases were added in
territory the harness had never touched: selectors and the cascade
(specificity, `!important`, `:nth-child`, `:not`, attribute presence,
custom properties), overflow and scroll containers, `z-index` stacking,
`position: sticky`, `direction: rtl`, letter/word spacing, `text-indent`,
nested tables, `rowspan`, richer forms, and six larger page shapes
(pricing grid, comment thread, breadcrumb, table of contents, hero with a
floated image, form row).

Both scores fell, which is the point: line 99% → 94%, geometry 88% → 78%.
Fixing what the new cases exposed brought them back to 98% and 79% on the
larger corpus. What they exposed:

- **`<mark>`, `<del>`, `<ins>`, `<meter>`, `<output>`, `<progress>`, ruby
  tags were not inline-level**, so any of them broke the sentence around it
  into stacked rows.
- **An atomic inline with MIXED content had no max-content width.**
  `<button>save <b>now</b></button>` fell back to the container width, so a
  button with any markup in its label swallowed the whole line and pushed
  the text after it onto the next one.
- **`&rsaquo;` and friends painted as literal source text** — a breadcrumb
  (`Home &rsaquo; Docs`) showed the entity. Another 25 page-furniture
  entities landed in kotoba-lang/htmldom.
- Harness: **an `inline-block`'s contents are their own formatting
  context**, exactly like a form control's, and must be excluded from the
  line comparison on both sides. The engine had that case geometrically
  right (2/2 boxes) while the line metric scored it wrong.

### Round eight: block-in-inline

An inline box containing a BLOCK box is now split around it, as real CSS
does: `<p>text <span>a <div>b</div> c</span> end</p>` renders as `text a` /
`b` / `c end`, three lines, with both fragments still styled by the same
`<span>`. This engine used to refuse to flow the whole span once it saw the
block child, so the paragraph fell apart into five stacked rows. The split
is expressed as data — the element is replaced by a clone per run of inline
children, with the block children hoisted between them — so nothing
downstream needed a new concept. Bounded v1: the block must be a DIRECT
child of the inline element.

### The three remaining line-structure failures

`page/hero-with-floated-image` (the float band's documented v1 boundary:
the narrowed width applies to the whole run rather than only to the lines
beside the float) are honest gaps, now measured.

The third is deliberate:

`position/fixed-leaves-flow` stays red and is NOT chased. `position: fixed`
takes the box out of flow and OVERLAPS the content beside it, so "which line
is this word on" has no single right answer — the browser's own word rects
for the fixed box and for the flow text occupy the same band. The GEOMETRY
axis, which asks the well-defined question (where is the box, how big is
it), agrees with the browser on every box in that case. Contorting the
engine to satisfy an ill-defined comparison would make the number better
and the engine worse.

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

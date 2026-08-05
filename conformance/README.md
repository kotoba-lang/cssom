# Conformance: cssom vs a real Blink browser

Differential testing against a real browser, because unit tests can only
check what someone thought to assert. This renders the same markup through
`htmldom` → `cssom.core` → `cssom.layout` and through a real headless
Brave/Chrome, and compares three axes: **line structure**, **geometry**,
and **computed style**.

```bash
nbb --classpath "src:../dom-gpu/src:../htmldom/src" conformance/run.cljs \
  [--browser "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser"] \
  [--width 800] [--only inline/] [--ledger path/to/ledger.edn] \
  [--debug-geometry] [--debug-style] [--debug-paint] \
  [--dump-ops path/to/ops.txt]
```

`--dump-ops` writes every case's `:node` ops, in emitted order, to a plain
text file made to be `diff`ed between two commits. The scoreboard cannot
answer "did this change break anything" — it is four sums over 501 cases,
and a sum hides an exchange. This corpus has already been bitten by one: a
5/5 → 0/5 regression on the table group went unnoticed in a round where
three other cases improved by as much. Diffing two dumps names every case
whose boxes moved, in both directions.

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

## Three axes

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

**Computed style** (added 2026-08-04) — what `cssom.core`'s CASCADE
resolved for each element, against the browser's own `getComputedStyle`.
Both layout axes read only the *result* of the cascade through
`cssom.layout`, so selector matching, specificity, `!important`, layers,
custom properties and shorthand expansion were an entire unmeasured
subsystem: a cascade that silently dropped a declaration still lays out
*something*, and both older axes score the something. See "The
computed-style axis" below for its first run.

## Result — 2026-08-04

**Line structure: 248/280 = 89%. Geometry: 937/1138 element boxes (82%),
199/291 cases with every box in agreement. Computed style: 13779/15886
cascade-resolved values (87%), 285/291 cases with no mismatch attributable
to the cascade itself**, on a corpus of 292.

Two of those three numbers just fell, because the corpus grew 200 → 292 in
territory it had never entered. The before/after, so the drop is
attributable to coverage rather than to a regression in `src/`:

| axis | 200 cases | 292 cases |
|---|---|---|
| line structure | 184/190 = **97%** | 248/280 = **89%** |
| geometry (boxes) | 638/719 = **89%** | 937/1138 = **82%** |
| geometry (clean cases) | 166/200 = **83%** | 199/291 = **68%** |
| computed style (values) | 8537/9982 = **86%** | 13779/15886 = **87%** |
| computed style (cases clean of a cascade mismatch) | 197/200 | 285/291 |

The 291 denominators are 292 minus one case that produces no boxes and no
styles at all: `grid/row-span-two` makes `cssom.layout` **throw**, which is
its own finding and is described below.

`src/` was not touched between the two columns; 542 unit tests and the
linter are unchanged and green either side (0 failures, 0 errors; 0 lint
errors, 22 pre-existing warnings, all in `test/`). See "Round twenty-one"
below for what the 92 new cases found.

The computed-style figures are after the two shorthand bugs this axis found
on its first run were fixed — cascade-attributed mismatches 41 → 5, cases
clean of them 190 → 197. See "The 41 that were actually the cascade's".

That geometry number dipped to 85% on the round the font-metrics model
landed, which was the right trade at the time — see "the font-metrics
model" below — and has since come back up past the earlier 87%. A
percentage that falls when the corpus grows is the corpus doing its job:
the corpus has gone 32 → 98 → 105 → 150 → 200 → 292 cases, and every one of
those steps cost points that the following rounds earned back.

**The per-group table immediately below is HISTORICAL** — it is the
98-case run, kept because the prose around it refers to it. The current
per-group numbers are in the harness's own output.

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

## The computed-style axis

### What it compares, and against what

Fourteen properties, chosen because each one has a normal form both sides
can be reduced to without guessing: `color`, `font-size`, `font-weight`,
`font-style`, `display`, `text-align`, and the four sides each of `margin`
and `padding`.

`getComputedStyle` returns the browser's **computed** value — cascade, then
inheritance, then defaulting from the UA stylesheet, collapsed into one
absolute normal form (`rgb(255, 0, 0)`, `"14px"`, `"700"`). This engine's
`:style/*` attrs hold only the FIRST of those three stages, in author-ish
form (`"red"`, `14`, `"bold"`). So each side is normalised per property
kind — colours parsed to `[r g b a]`, lengths to bare pixels (0.5px
tolerance, for the browser's fractional device pixels), `bold`/`normal` to
700/400 — and the two later stages are supplied on the engine's behalf,
**labelled** so every value's provenance is in the report:

| source | meaning | count, first run |
|---|---|---|
| `:direct` | the cascade wrote a value for this element. The only source this axis genuinely MEASURES. | 68 |
| `:inherited` | no value here, but an ancestor had one and the property inherits. Supplied by the harness walking up the engine's own document — exactly the `(or (:prop st) (:prop inherited))` fallback `cssom.layout` applies at paint time. | 709 |
| `:initial` | nobody in the ancestor chain declared it, so CSS's own INITIAL value stands. **Not** the browser's UA value. | 9205 |

That `:initial` row is the whole story of the number, and it was a real
architectural fact rather than nine thousand bugs: **this engine's UA
stylesheet lived in `cssom.layout`** (`node-style`'s `(or (style node :x)
<ua default>)` chains), so nothing reading the cascade's output —
`cssom.core/computed-style`, a devtools panel, a live page's
`getComputedStyle` — could see that a `<b>` is bold or a `<div>` is a block.

**Both halves of that sheet moved into the cascade on 2026-08-05
(ADR-2800003100), and this axis is now 100%.** The first half was the
declarations whose value is an absolute length or a keyword (87% → 97%,
layout byte-identical). The second was everything `em`-relative —
`p { margin: 1em 0 }`, `h1 { font-size: 2em }`, a fieldset's
`padding: 0.35em 0.75em 0.625em` — which needed the cascade to compute a
font size at all, since `em` resolves against the element's OWN computed
size and a font-size's own `em` resolves against its parent's. That landed
the axis at **18687/18763 (100%)**, `ua-default` 2123 → 557 → **35**, and
the cascade-attributed residual at **1**. What is left in `ua-default` is
not `em` and not a length: `<th>`/`<button>` `text-align: center`,
`<caption>`'s `-webkit-center`, and `<option>`'s padding — three UA rules
this engine does not have at all, which are new knowledge to be measured
against geometry rather than a move.

The axis reads the cascaded document directly and **never touches
`cssom.layout`**, so it shares no machinery with the geometry axis: a
layout bug cannot resurface here as a cascade bug. That is also why
elements are matched by tag + occurrence order rather than by the geometry
axis's nearest-box pairing.

### Every mismatch is attributed, not just counted

An 85% headline made of one repeated architectural fact would be unreadable
and would hide the handful of real divergences underneath it. So each
mismatch is classified against a **UA baseline measured in the oracle
itself** — a bare element of each tag, in the same font context, with no
author CSS anywhere near it:

| cause | first run | meaning |
|---|---|---|
| `ua-default` | 1407 | the engine declared nothing and the browser's value is exactly what its UA sheet gives that tag bare |
| `cascade` | 41 → **5** | the two sides disagree about a value the cascade is responsible for. **This is the bucket worth reading** |
| `blockified` | 23 | a `display` mismatch on a flex/grid item, a float, or an absolutely positioned box — real CSS blockifies all three at computed-value time |
| `ua-inherited` | 10 | the browser's value here is simply its parent's. Whatever diverged happened at an ancestor and is already scored there; charging it again at every descendant would multiply one cause by the depth of the tree |

The probe is measured rather than assumed for a reason: a bare `<a>` is
**not** a link, so probing without an `href` reported plain black and
charged all 19 link-colour divergences to the cascade. An `<input>` is
probed per `type`, because a checkbox's UA `margin: 3px 3px 3px 4px` is not
a text field's. `<td>`, `<li>`, `<option>` and friends are probed inside
the minimal legal ancestor chain they need to get their real UA style at
all.

`--debug-style` prints the whole cascade-attributed residual rather than
its head.

### The 41 that were actually the cascade's — both fixed

Two bugs, both invisible to 502 unit tests and to both layout axes, both
reproduced directly through the real pipeline. **Both are fixed; the
cascade bucket went 41 → 5 and cases clean of it 190 → 197.** What they
were:

- **An inline `style="..."` shorthand is not expanded into longhands.**
  `htmldom.core`'s inline-style declaration splitter expands `border`,
  `text-shadow`, `box-shadow` and `outline` — but not `margin`/`padding`,
  which `cssom.core`'s stylesheet path *does* expand
  (`expand-box-side-shorthand`). Two independent copies of the same idea,
  drifted. Measured: `.a { padding: 12px }` yields
  `{:style/padding 12, :style/padding-top 12, …}` (all five), while
  `style="padding: 12px"` yields `{:style/padding 12}` alone. Worse,
  `style="margin: 4px 8px"` is stored as the raw **string** `"4px 8px"`,
  which the per-side box model cannot read at all. 34 of the 41.

  **Fixed by deleting the second copy, not by adding margin/padding to it.**
  An inline `style="..."` *is* a CSS declaration block, so `htmldom.core`
  now hands the text to `cssom.core/parse-declarations-with-importance` —
  the same entry point a `<style>` rule body goes through — and its own
  ~330-line copy of the declaration parser (the `calc()` pipeline, five
  shorthand expanders, the value coercion, the `!important` regex) is gone.
  htmldom depends on cssom; cssom does not depend on htmldom, so there is
  no cycle. The drift was not confined to margin/padding either:
  `content`/`counter-reset`/`counter-increment` also meant different things
  inline than in a rule, and now do not.
- **Shorthand expansion runs BEFORE `var()` substitution.**
  `padding: var(--pad)` is not a length at declaration-parse time, so
  `expand-box-side-shorthand` correctly declines to expand it — and nothing
  re-expands it after `style-element` resolves the custom property. Result:
  `:style/padding 20` with no longhands. 4 of the 41.

  **Fixed by admitting a whole-token `var()` reference as expandable**, so
  each side carries the reference and substitutes independently. Expansion
  deliberately stays at declaration-parse time: re-expanding after the
  cascade merged would be simpler and wrong, because it would clobber a
  longhand that legitimately won by being declared later
  (`padding: 12px; padding-left: 0` and its reverse do resolve
  differently, and there is now a test that says so). The follow-on case —
  a custom property whose own value is a whole shorthand, `--pad: 4px 8px`
  — is re-sliced per side after substitution, which is the one place a box
  shorthand is legitimately re-read post-cascade.

Neither fix moved geometry (634/719 before and after, byte-identical
report). That is expected rather than disappointing: `cssom.layout` falls
back to the uniform `:padding`/`:margin` key when a side is missing, so a
one-value inline shorthand already laid out correctly. What was wrong was
the *computed style* — the values `getComputedStyle` actually reports, and
the ones a multi-value shorthand needs.

The 5 that remain are a different cause, not a residue of these two: four
are a `<p>`'s UA `margin: 1em 0` resolved against a font size the two sides
disagree about (`:cascade/inherited-font-size-chain`,
`:text/font-size-percentless-inheritance`), and one is a disabled
`<input>`'s UA text colour (`:form/disabled-input`).

### Excluded from comparison, explicitly

Nothing is dropped silently; every exclusion is counted, reasoned and
printed with its case ids.

- **`:element-count-mismatch` (70 values, 2 cases)** — the two sides
  disagree on how many elements of a tag exist, so there is nothing to zip
  the surplus against. Both cases are the same htmldom divergence:
  `<p>text <span>a <div>b</div> c</span> end</p>` nests the `<div>` inside
  the `<p>`, where HTML5's "in body" insertion mode auto-closes an open
  `<p>` on a block-level start tag. Not a cssom bug — recorded here because
  this axis is where it became visible.
- **Non-absolute lengths** (`1em`, `50%`, `auto`, a relative `calc()`) —
  the cascade legitimately holds the SPECIFIED value and resolves it at
  paint time against a font size and containing block it does not have
  here, while `getComputedStyle` reports the already-resolved used value.
  Comparing them would compare two *stages*, not two answers.
- **`lighter`/`bolder`** — relative to the parent's computed weight, a
  resolution step the cascade does not perform.
- **Colours neither side's parser recognises** — reported with the raw text
  rather than scored as a mismatch, because a value nobody parsed says
  nothing about who is wrong.

And three limits of the UA probe itself, which land in the `cascade` bucket
and are named here rather than tuned away:

- A UA value expressed in `em` (`p { margin: 1em 0 }`) scales with the
  element's own font size, so the bare-tag baseline only matches at the
  corpus's default 14px. 4 values, in the two cases that change the
  inherited font size.
- State-conditioned UA rules (`:disabled`, `:checked`) are not probed — the
  probe sets attributes, not states. 1 value (`form/disabled-input`, where
  Chrome greys a disabled field to `rgb(84, 84, 84)`).
- `ua-default` cannot distinguish "the engine's cascade has no UA sheet"
  from "the cascade dropped a declaration that happened to restate the UA
  value". Both look like *engine declared nothing, browser matches its own
  default*.

### Why the score is where it is, and why that is fine

85% on contact, with **0/200 cases fully clean** — no case in the corpus
avoids the UA-stylesheet divergence, because every case contains at least
one `<div>` or `<p>`. The actionable number beside it is **190/200 cases
with no cascade-attributed mismatch**, and the per-property table says the
same thing from the other direction: `display` is 134/713 = 19% (almost
entirely `inline` where the browser says `block`/`table-cell`/`list-item`),
while `font-style` and `text-align` are at 98%.

The number was not tuned to look better. Making it look better means either
moving the UA stylesheet up into the cascade — a real change to `src/`,
with real consequences for `getComputedStyle` consumers, and not this
harness's decision — or quietly excluding `display` and the margins, which
would be exactly the kind of measurement this corpus exists to prevent.

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

### Round twenty-two: the float band, and all three of round seven's exclusions

Round seven's three named exclusions are gone, and so is the fourth thing it
did not name (a float's own margins). Every number below was read out of a
real headless Brave 151 over CDP first — one isolating shape per behaviour,
each wrapped in its own `overflow: hidden` box, because floats leaking
between probe cases moved a float from x=0 to x=80 and another from y=0 to
y=28 before the wrappers went on.

Measured against the main this landed on (`3cace9b`, i.e. with the flex
round already in): line **255 → 263 / 280**, geometry **981 → 995 / 1142**
(220 → 228 cases fully clean), paint order **6978 → 7006 / 7285** (239 →
244 cases fully clean), float group **9/13 → 13/13**, page **24/28 →
27/28**. Eight cases went red to green and none went the other way.
Computed style is unchanged, as it should be: the only cascade-facing part
of this round is that `clear` is now read at all.

The v1 band was one `{:h :left :right}` rectangle pinned to the container's
top. It is now a list of placed float MARGIN boxes and three pure functions
over them — `float-band` (the `[left right]` edges on a scanline),
`float-clearance-y` (the lowest bottom edge on a cleared side),
`place-float` (CSS 9.5.1's placement: never above the flow position or an
earlier float's top, pushed down until the band is wide enough). Every float
rule in the file is now stated in terms of those three.

| what changed | browser | before | after |
|---|---|---|---|
| two 120px floats in 200px | (0,0) (0,20) | (0,0) **(120,0)** | (0,0) (0,20) |
| `float:left; margin:10px` | (10,10) | **(0,0)** | (10,10) |
| a float written after a `<p>` | y=34 | **y=0** | y=34 |
| `clear:left` past a 40px float | y=40, container 64 | **y=20, container 40** | y=40, container 60 |
| `clear:both`, floats 30 and 50 tall | y=50 | **y=0** | y=50 |
| plain `<div>` holding only a float | 200×0 | **200×60** | 200×0 |
| the same div, `overflow:hidden` | 200×60 | 200×60 | 200×60 |

Four findings worth keeping:

- **Containment and margin-collapsing are different questions.** The engine
  had one `fc-free?` flag doing both. `border-width` stops a margin
  collapsing through an edge but does NOT establish a formatting context, and
  `display: flow-root` — which exists for no other purpose than to establish
  one — was in neither list. Splitting them fixed
  `display/flow-root-establishes-a-context`, which had been failing for the
  margin half while passing the containment half by accident (the old code
  contained every container's floats unconditionally).
- **A float must be transparent to inline grouping.** It is blockified, so it
  never joins a line box — but leaving it in the sequence `inline-runs`
  partitions splits `text <float> more` into two one-child runs on two lines,
  where every browser keeps them on one. Floats are lifted out, the rest is
  grouped, and each float is put back in front of the entry its following
  sibling landed in. That re-insertion is also what lets a float be placed at
  the flow position it was WRITTEN at rather than hoisted to the top.
- **A float that escapes keeps rising, and only the paint-order axis
  noticed it did not.** The first cut of the containment rule asked each
  container about its own direct float children, which gets the clearfix
  wrong the moment the `overflow: hidden` box is not the float's own
  parent — `<div overflow:hidden><div width:200px><div float>` left the
  outer box 0px tall instead of 60. Floats a box does not contain are now
  returned as `:float/escaped` and carried up by `layout-block`, the same
  journey `:margin/collapsed-top`/`-bottom` already make, and they join
  the receiving container's band rather than being a height contribution
  bolted on the side — so they narrow its lines, push its later floats
  down and answer its `clear`s. Worth recording HOW this was caught: the
  geometry axis was happy (it compares each box against the browser and
  those boxes are the ones the corpus samples), while the paint-order axis
  landed all 25 of that case's sample points on nothing at all. "What
  would a user click" answering `none` for a page that visibly contains a
  float is a failure the other three axes had no way to express.
- **`page/hero-with-floated-image` was the harness, not the engine.** It
  reported `got []` — an empty line structure for a page that rendered
  correctly. `engine-lines` skips text inside a replaced box geometrically
  (draw-ops carry no parentage) and its rectangle test had the right and
  bottom edges INCLUSIVE, so the headline at x=80 counted as "inside" the
  80px `<img>` it was flowing beside and all three lines were discarded. A
  float is the one construct that reliably puts text at exactly a replaced
  box's right edge. Making those two edges exclusive — a point on a box's
  right edge is adjacent to it, not in it — also fixed
  `page/article-with-figure` and `page/login-form`, which is the evidence
  that it was a real bug in the proxy and not a fix aimed at one case.

**Still not implemented, and named:** the band is consulted by the line boxes
of the float's OWN container only. `layout-node` does not carry a float
context into a child, so a block DESCENDANT lays its lines out at full width
where a browser narrows them. Border boxes — what the geometry axis compares —
are unaffected either way; what this costs is a wrap point in text long enough
to break. Pinned by
`a-float-narrows-its-own-containers-lines-and-not-a-descendants` so it stays a
recorded cut rather than a silent wrong answer.

Three smaller ones, all pinned by tests too: clearance does not suppress the
cleared box's own margin collapsing; `float-band` is queried at a line's top
scanline rather than over its full height (a run gets ONE content width from
`layout-inline-run`, so a paragraph that starts beside a float keeps the
narrow width for the lines continuing below it); and whether a LONE inline
child flows as a run — and so consults the band at all — is decided before
the loop from whether the container has a float CHILD, so a lone text child
beside a float that ROSE from a descendant is not narrowed. Deciding that one
correctly means re-deriving the formatting-context rule recursively over the
whole subtree before laying anything out, for a shape the corpus does not
contain.

### Round twenty-three: grid flow, track sizing and item alignment

Every gap round twenty-one recorded under "Grid: one crash, and
flow/gap/alignment" is closed except the crash, which round nineteen had
already fixed. Each behaviour was read out of a real headless Brave 151 over
CDP *before* it was written — three probe rounds of isolating shapes, not
one — because none of these rules is derivable from the spec text alone.

The one that had to be measured to be believed is `auto` track sizing. A
browser gives an `auto` track its items' **min-content** width as a floor and
their **max-content** width as a growth limit, and then shares whatever is
left **equally** — not proportionally. `grid-template-columns: auto auto` in
a 400px grid holding `short` (max-content 41.625) and `a much longer cell`
(145.312) comes out **148.156 / 251.844**: each track's own content plus
exactly half of the 213px leftover. Proportional distribution would have
given 85 / 315, and both numbers sum to 400, so a corpus case checking only
"do the tracks fill the container" would have passed either. The equal share
is also what makes the narrow case work: at 200px the same two tracks are
48.156 / 151.844, because `short` freezes at its max-content, its unspent
share moves to the other track, and only the remainder is shared. And at
60px they are 41.625 / 50.141 — the min-content floor, overflowing the box
rather than crushing the words.

Two more that a guess would have got backwards:

- The stretch step does **not** run when an `fr` track is competing for the
  same space. `auto 1fr` leaves the auto track at exactly 41.625; `auto
  100px` stretches it to 300.
- Auto **rows** stretch the same way against a definite container height.
  `grid-template-rows: 30px` with three items in a 200px-tall grid is
  **30 / 85 / 85** — the explicit track keeps its size and the two implicit
  rows share the rest. But `auto 1fr` is 20 / 180, the same fr rule.

`grid-auto-flow: column` is the row-major algorithm with the two axes
swapped, so it is implemented by transposing the placement request on the
way in and the placement on the way out rather than by a second placement
engine; the bounded axis becomes the ROW track count (1 when no
`grid-template-rows` is declared) and columns become the axis that grows
implicit tracks. Measured: three items with `grid-auto-columns: 70px` sit at
x=0/70/140 on one row, and adding `grid-template-rows: 30px 30px` makes the
second item go DOWN rather than right.

`justify-items`/`justify-self` and `align-items`/`align-self` are a **size**
decision before they are a position one: `stretch` (the initial value) fills
the track, and anything else makes the item fit-content and places it
inside. A one-character item in a 120px column is 9.2px wide at x=55.4 under
`center` — an engine that only computed the offset and left the item
track-width would have centred a 120px box in a 120px track and moved
nothing.

One bug, and it is a placement bug rather than a sizing one: an item with a
definite **row** was moving the auto-placement cursor. CSS Grid §8.5 runs
that cursor in step 4, which only ever sees items with a definite COLUMN or
none at all — an item locked to a row is placed in step 2 and never touches
it. `<div style="grid-row: 2">t</div><div>b</div>` in a two-column grid puts
`b` at row 1 column 1 in a browser; this engine advanced the cursor past `t`
and put them on the same row, which the line axis reported as
`want ["b" "t"] got ["t b"]`.

`display: inline-grid` joins `inline-flex` on the inline path now that
`layout-grid` has the shrink-to-fit branch that set was waiting on: an
inline-level grid is its tracks' max-content sum plus the gaps between them,
so a two-30px-track grid in a sentence is 60px wide and the sentence stays
one line instead of becoming three.

`row-gap`/`column-gap` and the two-value `gap: <row> <column>` are resolved
in `node-style` rather than in the cascade, which loses one thing and says
so: declaration ORDER between the shorthand and a longhand. `row-gap: 24px;
gap: 5px` — where the later shorthand should reset the longhand — still
reports 24.

Named, still not implemented: `justify-content`/`align-content` on the
container (tracks are always placed from the start edge, which is also why
the `auto` stretch step runs unconditionally — an authored
`justify-content: start` should suppress it and cannot be told apart from
the default here), implicit COLUMN creation in row flow (an out-of-range
`grid-column` is still clamped, so `grid-auto-columns` only reaches the
tracks `grid-auto-flow: column` creates), a spanning item's contribution to
the intrinsic size of the tracks it crosses, and `min-content`/`max-content`/
`fit-content()` as track keywords.

Fourteen corpus cases were added for the shapes that had no case of their
own — the `auto` floor and the two `auto`-versus-`fr`/`fixed` interactions,
`justify-items: end`, `justify-self`, `align-items: end`, `align-self`,
column flow with explicit rows and past an explicit template, the two-value
`gap`, `grid-auto-rows` past the template, implicit rows against a definite
height, an auto row beside an fr row, and an inline-grid with auto tracks.
All fourteen were measured first and all fourteen pass. The grid group is
**37/37 on the line axis with 136/136 boxes**.

Corpus-wide: line 280/301 → **289/301**, geometry 1032/1214 →
**1086/1214** (234 → **256** clean cases), paint order 7499/7805 →
**7539/7805**. Twenty-two cases improved on geometry and none regressed.

### Round twenty-four: intrinsic width, and the viewport a fixed box belongs to

Two gaps, one of them the largest single number the geometry axis was
reporting.

**A shrink-to-fit box took the whole container whenever its content was
not a shape the intrinsic sizing recognised.** `flex-item-main-width` —
which despite the name is *every* shrink-to-fit width in the engine (flex
item, grid item, table cell, inline-block, all through `measure-child`) —
recognised a single text child, an all-inline run, an empty box and a
single element child, and ended with `:else content-w`. Everything else
swallowed its container. Two corpus cases were sitting on that fallback
with `td` **+761px** and `tbody` **+755px**, and they had two different
causes underneath one symptom:

- **An absolutely positioned child.** `<td style="position:relative">
  <span style="position:absolute">abs</span>cell</td>` has two children,
  and the absolute one is (correctly) not an inline-flow candidate, so the
  cell was neither all-inline nor single-element. Real CSS excludes
  out-of-flow boxes from intrinsic sizing outright — measured in Brave, td
  is **30**, the width of `cell` alone. Same for `fixed`: a
  `position: fixed` div in a cell leaves the cell **9** wide.
- **Block children.** `<td><ul><li>one</li><li>two</li></ul></td>` and
  `<td><div>alpha</div><div>bb</div></td>` are block containers, whose
  max-content width in real CSS is the **widest** of their children's,
  with each maximal run of adjacent inline children forming one anonymous
  block measured on a single line. Measured in Brave: 63 and 37.

Both are implemented (`intrinsic-flow-children`,
`block-max-content-width`), and two things fell out of doing it that the
old fallback had been hiding:

- **A child's horizontal MARGINS are part of its contribution.**
  `<td><blockquote>q</blockquote></td>` is **89** in Brave — the UA
  `margin: 1em 40px` is 80 of it — where the single-element rule this
  generalises measured the border box alone and said 9.
- **The intrinsic path and layout disagreed about what the children
  ARE.** A `<ul>` was measured from its bare `<li>` text and then laid out
  with the `• ` marker `with-implicit-list-markers` adds, so every item
  came out 14px wider than the box it had just been given and wrapped to
  two lines. Both now go through one `laid-out-children`.

**`position: fixed` is anchored to the viewport, not to an ancestor.**
It already left the flow correctly; what it did not do was escape its
ancestor. Measured in Brave on a probe page shaped like this corpus (800px
cases, 756px viewport): a `left: 0` fixed box inside a `margin-left: 120px`
wrapper is at **x=0**, not 120; `left: 10px` is at **10**, not 130;
`left: 50%` is at **378** = half the *viewport*, not half the 200px
wrapper; `right: 0` is at **749** = 756 − 7. `draw-ops` now assembles a
viewport from its own `:x`/`:y`/`:width` (plus a new optional `:height`)
and `layout-absolute-children` resolves a fixed box's offsets against it.
An offsetless fixed box still uses its static position, which is what
Brave does too (measured x=40 inside a `margin-left: 40px` container).

**This harness cannot score the block axis of a fixed box, and says so
rather than pretending.** Every case shares one long scrolling page and a
fixed box is measured against the viewport, so the browser's answer for
its `y` relative to a case is `-(that case's distance from the viewport
top)` — measured **−47.84** for `:position/fixed-leaves-flow`, **0** if
the same case is placed first on the page, and a different number again as
soon as a case is added above it. No engine behaviour can agree with a
number that moves when an unrelated case is inserted. So that case keeps
one permanently disagreeing box (3/4), **no new `fixed` case was added**,
and the inline-axis behaviour is pinned by unit tests against the measured
Brave numbers instead. The same measurement rules out the other case that
suggested itself — an absolutely positioned box in a cell with *no*
positioned ancestor, which Brave anchors to the initial containing block
and reports at **y=−304**, i.e. this case's own offset down the page.

Three cases were added that ARE honest — `:table/cell-holding-two-blocks`,
`:table/cell-holding-a-block-with-margins`,
`:flex/item-with-an-absolutely-positioned-child` — and all three agree with
Brave on **every box, exactly**, on first contact.

Corpus-wide, 313 → 316 cases: geometry 1120/1212 → **1142/1229**
(267/313 → **271/316** clean), paint order 7575/7805 → **7693/7880**,
line 297/301 → **300/304**, computed style 14782/16964 → **14984/17202**.
Five cases' boxes changed and **none regressed**;
`:position/absolute-inside-table-cell` went 1/6 → **6/6**.

The one that did not move: `:table/cell-with-a-list` is still 0/8, but
every box is now the right shape and **13.7px** too wide instead of
**+769**, and the whole residual has one named cause — this engine paints
an `<li>`'s marker inside the item's own line, where a browser's
`list-style-position: outside` puts it in the list's padding and leaves it
out of the item's width. That property is named as out of scope in
`with-implicit-list-markers`, and the honest fix is that property, not a
wider cell.

### Round thirty-five: stacking contexts, and the difference between painting above and confining

The single largest measured divergence in the corpus was one subject:
`210` of the `418` remaining paint-order points, across nine cases, all of
them with **every geometry box already exact**. Everything in this file
above this round paints in document order — CSS 2.1 Appendix E's step 3 —
which is right for most of a page and leaves out exactly the properties
authors reach for on purpose.

Result on the corpus, measured on `a49f29b` before and after: paint order
**13971 → 14181 / 14389** (clean cases **529 → 538 / 576**), and line
structure, geometry and computed style all **unchanged to the value**
(540/551, 1851/1959 with 510 clean, 27514/27544 with 563 clean). Nine cases
went red to green and **none went the other way**. Diffing `--dump-ops`
corpus-wide names 25 cases whose op ORDER changed and exactly one whose op
CONTENT changed (`:stacking/content-visibility-hidden-is-not-painted`,
which gains `hit=[]` on its inner box); no case's set of boxes moved,
which is what says this was a paint change and not a layout one.

#### What was measured, and where Brave departs from a spec reading

Every rule below was read out of a real headless Brave 151 over CDP before
any of it was implemented, on two probe shapes, each in its own
`overflow: hidden` wrapper and sampled with `document.elementFromPoint`:

- **lift** — does this trigger on the EARLIER of two boxes pulled onto each
  other by `margin-top: -60px` put it on top? 25 interior points.
- **confine** — does this trigger on a WRAPPER stop a `z-index: 5` box
  inside it from beating a `z-index: 2` sibling of the wrapper? 20 points.

The two questions have different answers for exactly one input, and that
one difference is the whole of the rule: **a positioned box with
`z-index: auto` lifts but does not confine.** The engine confined in both
shapes, so it was wrong on the first half of the pair and right by accident
on the second.

Creates a stacking context (both probes): `position: fixed | sticky` (with
or without offsets); `position: relative | absolute` **with an integer
`z-index`**; a flex/grid **item** with an integer `z-index`;
`opacity < 1` (`0.999999` does, `1` does not); `transform` / `rotate` /
`scale` / `translate`; `filter` (even `opacity(1)`); `backdrop-filter`;
`clip-path`; `perspective`; `transform-style: preserve-3d`;
`isolation: isolate`; `mix-blend-mode` other than `normal`;
`contain: paint | layout | strict | content`;
`content-visibility: auto | hidden`; `view-transition-name`;
`will-change` naming one of those properties.

Does not, each probed because a spec reading suggests it might:
`overflow: hidden`; `contain: size`; `contain: style`;
`will-change: z-index`; `will-change: top`; `z-index` on a `position:
static` box.

Two departures worth naming rather than arguing with:

- **`container-type` creates no stacking context in Brave.** css-contain
  gives a size container `layout` containment, and `contain: layout` DOES
  create one here — yet `container-type: inline-size` lifted nothing on
  the first probe and confined nothing on the second. Followed the
  browser.
- **`will-change: z-index` creates none either**, although a non-initial
  `z-index` plainly creates one on a positioned box, which is the rule
  css-will-change states. `will-change: position` does.

#### The mechanism, and why it is not the obvious one

A box that paints in the positioned band does not paint in its parent's op
run — it paints in its nearest ancestor stacking CONTEXT's, which may be
many levels up. So the ops cannot be reordered where they are produced;
they have to travel. They travel as a marked span in the op vector itself
(`:stack` push/pop, the same shape as the `:clip` pair already there),
carrying no `:x`/`:y` so that `translate-ops` and `transform-ops` leave
them alone, and the first ancestor that is a stacking context extracts
every span it can see, sorts by level, and splices the negatives after its
own `:node` op and the rest at the end.

Riding in the op vector rather than in a new return key is what kept this
out of `layout-flex`, `layout-grid`, `layout-table`, `layout-multicol` and
`layout-inline-run` entirely: each of them already concatenates its
children's `:draw`, so each already carries the spans correctly without
knowing they exist.

The extraction FLATTENS — a span found inside another span becomes its
sibling — and that is the rule, not a convenience: a `z-index: auto`
positioned box is not a context, so its own positioned descendants belong
to whatever context IT belongs to. Measured through two nested wrappers.

`layout-absolute-children` stopped sorting. It used to return
`{:below :above}` split by the sign of `:z`, which was this file's whole
stacking model; a local sort there cannot be right, because the containing
block is not the stacking context and two boxes anchored to different
containing blocks have to meet in a common ancestor to be compared at all.

#### The splice point, measured on one declaration

Appendix E's steps 1 and 2 put a negative-`z` child above its context's own
background and below everything else in it. In this engine the element's
own `:node` op is the proxy for its own box — it is what both real
hit-testers and this harness read — so `after the node op` is what `above
its own background` means here. The pair: a `z-index: -1` child of a
`position: relative; z-index: 0` parent is answered as the CHILD at all 20
points; drop the `z-index: 0` and the same child is answered as the
PARENT, because it has sunk past a parent that is no longer a context and
the parent's background is ordinary step-3 content painted over it. One
declaration apart, opposite answers, and the splice point is the only
thing that produces both. One unit test asserting the old, single-shape
behaviour was wrong and now asserts the measurement, with its pair beside
it.

#### `content-visibility: hidden`

The last case in the group, and not an ordering question: the element
paints its own background and its contents paint nothing and answer
nothing. Measured, Brave answers the `<section>` at all 25 points while
the inner `<article>` still reports a real 800x60 box a `Range` reads
`inner` out of — so this is not `display: none` and not a box-tree change.
Expressed on the ops the subtree already produced, in the two channels
that already exist for it: `:opacity 0` (what `visibility: hidden` uses)
and `:hit []` (`not a hit-test candidate at all`).

**Scope cut, stated with its cost:** real `content-visibility: hidden`
skips the subtree's LAYOUT too — its size comes from
`contain-intrinsic-size`, not from its contents. This engine lays the
contents out and then declines to paint them: the right picture for the
wrong reason, and the right numbers on all four axes here only because
Brave reports the real inner box for this shape anyway.

#### What this round does NOT implement

- **Appendix E's steps 3, 4 and 5 are still one band.** A block child's
  background and its text travel together, so in-flow INLINE content does
  not paint above a float the way step 5 says. That was already true (see
  round twenty-two's float comment) and is unchanged; nothing in the
  corpus discriminates it.
- **Positioned INLINE-level boxes are not hoisted.** The spans are created
  in `layout-node`, and an inline box inside an inline formatting context
  is laid out by `inline-fragments`, which does not go through it. An
  inline-block does (it is an atomic inline and gets a real `layout-node`
  call), and so does every block, flex item, grid item, table cell and
  out-of-flow box.
- **A hoisted box escapes an `overflow: hidden` ancestor's CLIP.** The
  clip is a `clip-push`/`clip-pop` pair in the parent's own op run and the
  span is spliced past it. Real CSS clips such a descendant when the
  scroll container is between it and its containing block. Nothing in the
  corpus measures a clip edge (the geometry axis reads boxes, not clips),
  so this is a paint change scored by nothing — recorded rather than
  fitted.
- **`z-index` is read as an integer only.** No `calc()`, and no
  `revert`/`unset` handling beyond what the cascade already does.

#### Two harness defects this round exposed, both inert on the old engine

Reordering the op stream broke two places where the harness read the op
vector by POSITION, and both were fixed by reading identity instead. The
proof that neither is a thumb on the scale: the whole harness change, run
against the **unmodified** engine at `a49f29b`, reproduces the baseline
scoreboard to the value on all four axes and produces a **byte-identical**
`--dump-ops` dump.

- `engine-boxes` took the first `:div` node op as the `#root` wrapper and
  subtracted its origin from every box in the case, and
  `engine-topmost-at` took the first two node ops as the two wrappers.
  A `z-index: -1` box whose nearest context is the root is now painted
  before every other box in the document, so it became the first `:div`
  op — and `:stacking/negative-z-index`, whose geometry is exact on both
  sides, came out with all four boxes shifted by −120px. Both now read
  `wrapper-op-ids`, which finds them on the document tree by identity.
- `cluster-lines` sorted a line's words by `:left` and let equal keys fall
  through to arrival order, which is DOCUMENT order on the browser side
  and PAINT order on the engine side. Six corpus cases put two words at
  exactly x=0 on the same line on purpose, and the newly-correct paint
  order read as `want ["a b"] got ["b a"]` on an axis that is not about
  paint order at all. Coincident words now sort by their own text — the
  same rule on both sides, and it can only reorder words whose positions
  are identical, which carry no order to preserve.

### Round thirty-four: the table cluster — `caption-side`, and a cell's own declared width

Four of the corpus's five table-group residuals were one cluster, and all
four are closed. Everything below was measured in the same headless Brave
151 the harness drives, on probe pages built to the harness's own page
shell, **before** anything was implemented.

**`caption-side: bottom` did nothing.** `layout-table`'s docstring said so
("a caption is laid out as an ordinary block row above the rows, never
below"), and the property is not rare — it cost 12 boxes across two cases
and 4 paint-order points. Measured: the caption is in the TABLE WRAPPER
box, which is what a `<table>` element's own box is on both sides. It
spans the table's whole BORDER-box width and sits outside its border and
padding, at whichever end `caption-side` names, and the table's HEIGHT is
the same either way — a `width:200px; border:6px; padding:9px` table with
a `Cap` caption is 200x76 in both, with the caption at y=0 or y=56 and the
rows at y=37 or y=17. So a following block does not move; what moves is
which end each part sits at. `caption-side` also INHERITS: declared on a
wrapping `<div>`, it moves the caption of a `<table>` that declares
nothing (measured), so it is carried down `inherited` like any other
inherited property rather than read off the table alone.

**A cell's declared `width` sized nothing.** The engine read it only as a
max-content DEMAND and then grew every column in proportion to its demand,
so `<table style="width:300px"><tr><td style="width:25%">a</td><td>b</td>`
came out 68 and 31 with the second cell starting at x=267, against Brave's
73.5 and 220.5 at x=2 and x=77.5. Two rules, and neither is guessable from
the other:

- **A length sizes the CELL; a percentage sizes the COLUMN.** `width:
  100px` on a `<td>` gives a 102px column — the declared 100 plus the UA's
  1px padding each side, ordinary `content-box` arithmetic, and `padding:
  8px` gives 116 and `border: 3px` gives 108. `width: 25%` gives 73.5,
  which is 25% of **294** exactly — the content width minus all three 2px
  border-spacings — and not 25% of anything plus padding. Moving the
  spacing moves the basis with it (`border-spacing: 10px` → 67.5 = 25% of
  270; `border-collapse: collapse` → 75 = 25% of the whole 300), which is
  how the basis was identified as "what is left for the columns" rather
  than the table's content width.
- **The declared width is floored at the COLUMN's min-content width, not
  the declaring cell's.** `width: 10px` on a cell holding `averylongword`
  is 93; a `width: 50px` cell in row 1 whose column holds
  `averylongcellword` in row 2 is 121 in both rows.

A declared column then HOLDS while the others absorb the surplus — the
`locked` set `distribute-excess` already had for `<col>` widths, now fed
from cells as well. Where both a `<col>` and a cell declare, the larger
wins (measured both directions: `<col>` 80 against a `<td>` 200 gives 202,
and the mirror gives 200).

And a third thing the first two exposed: **a cell FILLS its column**. Its
own declared width has already been spent on sizing the column, and
resolving it a second time against that column applied it twice — the 25%
cell reported a 21px box inside its own 73px column. Brave reports the
column (73.5), and reports 185 for a `width: 500px` cell in a 200px table.
The declaration is DROPPED rather than overwritten with the column width:
a column width is routinely fractional (50.5625 for `go <b>now</b>`) and
writing one back as a declared length goes through an integer parse that
truncated the cell to 50 and wrapped its content onto a second line. That
spelling cost 5 boxes in `:table/cell-with-inline-content` to fix 2
elsewhere, and the per-case op diff below is what caught it.

**`empty-cells` is not implemented, and is measured to be unscorable
here.** The same table with `hide` and with `show` gives identical boxes
to three decimals in Brave (128.625 / 59.375 with cell borders, 153.813 /
34.188 with backgrounds), and `elementFromPoint` answers `td` over the
empty cell either way. The property changes which pixels are FILLED, and
no axis here compares a fill. Its one-point paint-order residual is a
different thing entirely, and not a table thing: **Brave hit-tests every
element from one pixel above and left of its box.** Swept at 0.05px, a
`td` at y=4..26 answers `table` at y=3.00 and `td` from y=3.05, and stops
at y=26.00 exactly; the same 1px slop appears on a `<p>` in a padded
`<div>` and on a bare `<span>`, in x as well as y. This engine's own
half-open `[x, x+w)` test is the honest answer to the question the axis
asks, so nothing was changed for it — it is recorded beside the case.

**The fifth was not a cssom bug and was fixed where it lived.** `htmldom`
did not synthesise the `<colgroup>` a real parser inserts around a bare
`<col>`, so the corpus reported one box Brave has and this side had not,
plus 14 computed-style values it could not zip against anything. Measured
in Brave and fixed in `htmldom.core/maybe-insert-implied-table-structure`:
consecutive bare `<col>`s share ONE synthesised group, and one that
arrives after a row group gets a group of its own BESIDE it. That repo's
own tree-shape corpus went 137/137 → **139/139** with two new cases, and
its attribute axis 404/405 → **421/422** — the new case was the first with
an inline style on an otherwise bare element, and exposed that the axis
was reporting a phantom `width` attribute for every `:style/width` key,
because `name` erases the namespace that identifies one.

| axis | before | after |
|---|---|---|
| line structure | 540/553 | **542/553** |
| geometry (boxes) | 1823/1959 | **1843/1959** |
| geometry (clean cases) | 502/576 | **505/576** |
| paint order (points) | 13936/14389 | **13956/14389** |
| paint order (clean cases) | 524/576 | **527/576** |
| computed style (values) | 27483/27528 | 27483/27528 |
| computed style (clean cases) | 556/576 | 556/576 |

799 tests / 1834 assertions, 0 failures (789/1817 before, +10 for this
change); 0 lint errors and the same 25 pre-existing warnings, all in
`test/`. Downstream, against this branch: browser 754 / 0, dom-gpu 130 / 0,
htmldom 178 / 0.

Not one table entry is left in either layout residual. The htmldom fix does
not show in this table because it landed first and its pin has not moved
yet; measured on the 501-case corpus either side of it, it took geometry
1722/1766 → 1723/1766 (472 → 473 clean) and turned the 14 EXCLUDED
`element-count-mismatch` computed-style values into 14 comparable ones that
all agree.

#### `--dump-ops`, and the exchange it caught

The scoreboard cannot answer *did this change break anything*: it is four
sums over 501 cases, and a sum hides an exchange. So this round added
`--dump-ops <file>`, which writes every case's `:node` ops in EMITTED
order to a plain text file made to be `diff`ed between two commits, and
the two dumps either side of this change name **exactly three** cases of
576: `:table/caption-side-bottom`, `:table/cell-percentage-width` and
`:page/data-table-with-caption-and-foot`, all three moving onto Brave's
numbers, and **nothing else in the corpus moved at all** — no op added, no
op removed, no coordinate changed. The htmldom fix adds exactly one line to
exactly one more case (`colgroup 2 2 296 22`, which is Brave's box for it
to the pixel).

That is also how the write-back spelling of the cell-fills-its-column fix
was caught: the net geometry score went UP while
`:table/cell-with-inline-content` silently went 6/6 → 1/6.

#### Scope cuts, each with the number a fix will need

- **A `<table>`'s declared width is its BORDER box in Blink**, whatever
  `box-sizing` says, and `resolve-width` gives it the ordinary content-box
  treatment. `width:200px; border:6px solid; padding:9px` is 200 wide in
  Brave with 166 of rows inside it; this engine makes it 230 with the same
  166 of rows — every y is right, and only the outer width (and therefore
  a caption's) is 2x(border+padding) too big. Left alone because it is a
  different rule from anything this round is about, it moves every
  bordered table at once, and no corpus case has a table with both a
  declared width and a border.
- **A percentage cell width on an AUTO-width table is left to the
  max-content path.** It is circular, and Brave does something this engine
  does not model: `<table><tr><td style="width:25%">a</td><td>b</td>` is
  42px wide overall with columns 9 and 27, and 25% of anything in sight is
  none of those numbers.
- **A declared width is not capped by what the other columns need.**
  `width: 500px` in a 200px table gives 185 in Brave — the other column
  keeps its 9px min-content and the declared one takes the rest — where
  this engine leaves 502 and lets the proportional scale-down handle the
  overflow, which is what it did before.
- **A `colspan` cell contributes no declared column width**, exactly as it
  contributes no max-content one. `<td colspan="2" style="width:50%">` over
  a 300px table gives its two columns 73 and 73 (146 = 50% of 292, split
  evenly).
- **A percentage beats a length in the same column**, whichever is larger:
  `width: 20%` in row 1 against `width: 200px` in row 2 gives 58.797 = 20%
  of 294, not 202. Here the two are simply maxed.
- **`distribute-excess` hands out whole pixels**, so the column Brave
  leaves at 220.5 comes out 220 at x=78 against 77.5. Inside the geometry
  axis's 2px tolerance, and the one place these shapes are not exact.

### Round thirty-three: the corpus grows 501 → 576, and the paint axis was asking the wrong question

Seventy-five cases, in seven areas the corpus had never entered, chosen by
inventorying every case-id prefix and then asking of each candidate *can
one of the five axes actually see this*. Every number below was read out of
a real headless Brave 151.1.93.129 over CDP **before** the case was
written, on a page built with the harness's own wrapper.

| axis | 501 cases | 576 cases |
|---|---|---|
| line structure | 473/482 = **98%** | 540/553 = **98%** |
| geometry (boxes) | 1702/1766 = **96%** | 1823/1959 = **93%** |
| geometry (clean cases) | 469/501 | **502/576** |
| paint order (points) | 12421/12514 = **99%** | 13936/14389 = **97%** |
| paint order (clean cases) | 479/501 | **524/576** |
| computed style (values) | 24797/24816 = **100%** | 27483/27528 = **100%** |
| computed style (clean cases) | 492/501 | **556/576** |
| cascade-attributed residual | **11** | **30** |

The right column is *after* the two fixes below. 789 tests / 1817
assertions, 0 failures (783/1804 before, +6 tests for the `inset` fix); 0
lint errors and the same 25 pre-existing warnings, all in `test/`.

Of the 75, **21 agree with Brave on first contact** and are in the corpus
as controls rather than as findings — several of the pairs below exist
for no other reason, because a divergence next to an agreement is a
measurement of a RULE and a divergence on its own is a number.

#### The harness defect, which is worth more than any of the cases

**`engine-topmost-at` was not reading `:pointer-events` or `:visibility`,
and both of the engine's real hit-testers do.** `style-passthrough` puts
both keys on every `:node` draw-op for exactly this purpose — its own
docstring says so — and `browser.session/node-at` (session.cljc) and
dom-gpu's `retained` host each drop an op whose `:pointer-events` is
`none` or whose `:visibility` is `hidden`/`collapse` before taking the
topmost. This harness did not, so the question it was asking was one no
consumer of this engine asks, and it charged the engine for two behaviours
the engine has *right*: 40 sample points across
`:stacking/pointer-events-none-falls-through` and
`:stacking/visibility-hidden-is-not-hit`, where Brave answers the box
UNDERNEATH the transparent one and this engine's own hosts would too.

Reading the two keys is not re-deriving stacking in the harness — it is
reading two more fields of the answer the engine already emitted, exactly
like `:hit`. Measured per case over the whole corpus: **two cases changed,
both to zero disagreements, and nothing else moved at all.**

**And a second, structural one: the axis compared TAG NAMES.** Two
overlapping `<div>`s were indistinguishable to it however they were
stacked, which is most of what stacking is about on a real page — so a
case built out of two divs would have passed while measuring nothing. The
oracle now reports the author's `class` beside the tag and
`engine-topmost-at` returns `[tag class]`, because `:class (attr node
:class)` is already on every `:node` op and is the one identity both sides
can read off the same markup. Corpus-wide effect on landing: **zero** —
no existing case changed by a single point — and
`:stacking/same-tag-opacity-lifts-the-earlier-box` (Brave `div.lower`,
this engine `div.upper`) is the case that could not have existed before
it, with `:stacking/same-tag-later-sibling-covers-earlier` as its control.

#### The one engine fix: the `inset` shorthand

`inset: 10px 20px` was stored verbatim under an `:inset` key nothing
reads, so an absolutely positioned box declaring it fell back to its
static position and shrink-to-fit width. Measured in Brave: **x=20 y=10
w=260 h=40** in a 300x60 relative parent, against **0,0,7x20** here.
Expanded now by the same 1-to-4 rule `margin`/`padding` use, into the
four BARE side names `layout-absolute-children` already reads — which is
the only reason it needed a function of its own rather than
`expand-box-side-shorthand`. `auto` is admitted (it is the property's own
initial value); a percentage is declined, exactly as margin/padding
decline one. Per-case box diff over all 576 cases: **one case changed,
0/2 boxes → 2/2, and no paint-order point moved.**

#### The new divergences, ranked by size

**Logical properties — 12 cases, every one diverging, one cause.**
`margin-inline-*`, `padding-inline-*`, `padding-block-*`, `inline-size`,
`block-size`, `border-inline-*` and `inset-inline-*` match NOTHING in
`src/` (a repo-wide grep for each is zero in both namespaces). The two
that matter most are the ones that FLIP: `margin-inline: 20px 60px` is
x=20 w=220 in an ltr block and **x=60 w=220** in an rtl one, and
`padding-inline-start: 50px` under rtl puts the padding on the right
(outer box 350 wide, inner `<p>` still at x=0). Those two are the only
cases in the corpus that can tell a logical property from an alias for a
physical one.

**Replaced elements with no box — 8 cases.** `inline-atomic-tags` is
`#{:img :input :button :select :textarea}` and its docstring names
`svg`/`canvas`/`video`/`audio`/`iframe` as a deliberate cut, on the
grounds that a box for them "would place an empty rectangle in the middle
of a sentence rather than fix anything". Measured, the cost is not an
empty rectangle: Brave reserves **300x150** for a bare `<canvas>`,
`<video>` and `<svg>`, and **304x154** for an `<iframe>` (the 300x150
default plus Chrome's UA `border: 2px inset`), where this engine lays each
one out as a BLOCK that fills its container and is 0px tall. So a page
with a `<canvas>` in it is 150px shorter here than anywhere else, and
`:replaced/canvas-in-a-sentence` shows the line-axis half: Brave keeps
`before <canvas> after` on one line with the canvas an atomic inline at
x=49 y=4 w=20 h=10, and this engine puts the text on two lines around a
400px block. The `<img>` half is an intrinsic **ratio**: a 40x20 SVG data
URI at `width: 200px` is **200x100** in Brave and 200x**0** here, and its
mirror at `height: 60px` is **120x60** against 0x60. Its control agrees —
`width`/`height` ATTRIBUTES are presentational hints, so a CSS `width`
replaces the width hint and the height hint stands alone: **200x20** on
both sides, which is what says the gap is the resource's ratio and not the
attributes.

**Stacking contexts — 10 diverging cases out of 18, and every geometry box
in all eighteen agrees exactly.** That is the paint-order axis's entire
reason to exist. Four separate triggers put a box above a later in-flow
sibling that overlaps it, and all four are missed:
`opacity: 0.99`, `transform: translateY(0px)`, `filter: blur(0px)` and
plain `position: relative` — Brave answers the EARLIER box at all 25
points in each, this engine the later one. Round twenty-seven already
named the transform half ("a transformed element also establishes a
stacking context in real CSS and this engine does not model that"); this
is that sentence with a number, plus three more triggers it did not name.
The other half is confinement, and it is a discriminating pair:
`position: relative` with `z-index: auto` is NOT a stacking context so a
`z-index: 5` descendant escapes it (Brave `section`, engine `article`),
while the same markup with `z-index: 0` confines it (Brave `article`, and
this engine **agrees**) — because this engine confines a descendant's
z-index in *both* shapes, which is what makes it right by accident in one
and wrong in the other. `isolation: isolate` is the same accident.
`z-index` on a **flex item** is missed too (it applies without
positioning), and so is the Appendix E step-1/step-2 order: a `z-index:
-1` child stays ABOVE its own stacking context's background (Brave
`article`) and sinks past a parent that is not one (Brave `section`) —
this engine sinks it in both.

**Percentage margins and padding in the block axis — 4 cases.** CSS 2.1
§8.3/§8.4 resolves a percentage margin or padding against the containing
block's INLINE size on all four sides. Measured: `padding-top: 10%` of a
300px container is **30px** (inner box 50 tall), `padding-bottom: 50%`
alone makes a **300x150** box with no height anywhere, `margin-top: 10%`
puts the child at **y=31**. This engine reads the number and drops the
unit — 10px, 50px, 10px — which is the same defect round thirty-two
recorded for percentage GAPS ("`node-style` runs `parse-int` on the
string, and it has no available size to resolve a percentage against").
The inline-axis control fails identically (`margin-left: 10%` is x=30
against x=10), so it is not a block-axis problem: the unit is dropped on
every side, and a fix belongs where round thirty-two said it did.

**`vertical-align` on an atomic inline — 6 of 7 cases.** The machinery
exists: `vertical-align-shift` knows `super`/`sub`, `line-edge-aligned`
knows `top`/`bottom`, and `middle` is resolved in `inline-fragments`. But
only `middle` reaches an inline-BLOCK — and it reaches it exactly, both
sides reporting **y=31.828125**, which is what makes the other six a
finding rather than "the property does nothing here". Against a 40px
inline-block setting a 46px line box, Brave puts a 10px one at y=**0**
(`top`), **36** (`bottom`), **28** (`text-top`), **33** (`text-bottom`),
**18** (`12px`) and **20** (`50%`); this engine leaves it on the baseline
at 30 in all six.

**Per-side border shorthands — 2 cases.** Round twenty-six named
"per-side border widths" as not implemented, on the grounds that this
engine has one uniform `:border-width`. Measured, it is sharper than that:
a per-side SHORTHAND is not read *at all*. `border-top: 10px solid` gives
Brave a 30-tall box with its `<p>` at y=10 and the full w=300 (a top
border costs height and nothing else) and gives this engine 20 tall with
the `<p>` at y=0 — not ten pixels on four sides, zero on all of them. Its
control agrees and constrains the fix: `border-width: 10px;
border-style: none` computes to a border width of ZERO on both sides, so
a fix must not simply start adding declared widths up.

**`visibility: collapse` on a table row — 1 case, with two controls.**
Brave gives the collapsed `<tr>` and its `<td>` **0px** of height, moves
the third row up to y=22 and makes the table **44** tall rather than 66.
This engine renders it as `hidden` — which the two controls say is right
everywhere else: on a plain block and on a flex item, `collapse` is
exactly `hidden` and both sides agree.

**Form controls with a UA box of their own — 5 cases.** `<progress>` is
**140x14** and `<meter>` **70x14** in Brave, and neither tag exists in
`src/` at all (both come out as 400x0 blocks). Three `<input>` types all
come out of this engine's text-field rule at 153x21 where Brave says
**129x16 at x=2 y=2** (`range`, which carries `margin: 2px` and no
padding — those four padding values are the only cascade-attributed
computed-style residual these cases add), **50x27** (`color`) and
**253x27** (`file`, which is the button plus its label text, i.e. a
different KIND of number that a per-type constant table would not
produce).

**Generated content where the oracle can see it — 2 of 4.** A
`display: block` ::before is a block box, so Brave's `<p>` is **40** tall
with `tail` at y=22 and this engine flows the generated text into the
paragraph's own line and reports 20. `list-style-position: inside` — the
value round twenty-nine implemented the default of, measured alongside,
and never gave a case — puts the `<a>` at **x=59** against this engine's
53.70625; that 5.29 is exactly the number that round recorded in
`list-style-inside?` rather than modelling (Brave's disc advance is a
function of the font size, 19px at 14px, not the 13.70625 the `"• "`
string measures). Both controls agree: an `::after` string widens the
`<b>`'s box to 37.03 on both sides, and `counter-reset: k 7` starts the
counter at 7 on both.

**Three singles.** `display: inline-table` is an inline 28x20 box at x=49
in Brave and three stacked 400px blocks here. `writing-mode: vertical-rl`
matches nothing in `src/`: Brave lays the text down the page in a
**20x70** box and this engine gives it a horizontal 300x20 — a deep gap,
recorded rather than chased, because a vertical writing mode is a second
axis convention through every function in `layout.cljc` and not a property
to read. And `border-radius: 50%` shrinks a box's HIT region without
changing the box: Brave answers the wrapper at the four corner sample
points of an 800x200 rounded box and the box itself at the other 21 — the
third measured case of a reported box and a hit region being different
rectangles, and the first where the region is not a union of rectangles at
all.

#### Two things measured and deliberately NOT added

**`shape-outside`.** `circle(30px)` on a 60px left float in a 200px box
moves the first line's text from x=60 to **x=58.281** and leaves the
second line identical — a 1.7px difference, inside the geometry axis's 2px
tolerance, on a coordinate (a word's x) that no axis compares, and with
the same words on the same lines either way. There is nothing here for the
five axes to disagree about at this size, and a shape large enough to move
a wrap point would be measuring the float band rather than the shape.

**`overflow: clip`.** Its boxes and its hit region are identical to
`overflow: hidden`'s in Brave (inner box reports its full 700x60, hit only
inside the 200px clip), and the corpus already has the `hidden` case. What
differs — that `clip` does not create a scroll container — is not
observable on any axis here.

### Round thirty-two: the corpus grows 370 → 431, into layout it had never entered

All four axes were at or rounding to **100%** on the 370-case corpus —
line structure 356/356, geometry 1335/1335 boxes across 370/370 cases,
paint order 9227/9234, computed style 18687/18763. **A corpus that scores
100% can no longer find defects.** Growing it is the only thing left that
teaches anything, and the numbers are *expected* to fall: a fall that
comes from coverage is a better measurement, not a regression.

61 cases, every one of them measured in Brave 151 over CDP *before* it was
added to the corpus. The territory was chosen by inventorying the case-id
prefixes and then targeting what had no case at all.

| axis | 370 cases | 431 cases |
|---|---|---|
| line structure | 356/356 = **100%** | 397/414 = **96%** |
| geometry (boxes) | 1335/1335 = **100%** | 1507/1584 = **95%** |
| geometry (clean cases) | 370/370 | 400/431 |
| paint order (points) | 9227/9234 | 10703/10764 |
| paint order (clean cases) | 364/370 | 414/431 |
| computed style (values) | 18687/18763 | 22183/22273 |
| computed style (clean cases) | 332/370 | 389/431 |
| computed style (clean of a CASCADE mismatch) | 369/370 | 429/431 |

The right column is *after* the four fixes below; without them it would be
lower still. 736 unit tests / 1702 assertions, 0 failures either side; 0
lint errors, the same 25 pre-existing warnings, all in `test/`.

#### What the new cases cover

**multi-column, 12 cases.** `column-count` appears nowhere in `src/`, and
the only `column-gap` there is the grid/flex longhand — so these measure
the size of the gap rather than assert one exists. `column-count`,
`column-width` deriving the count, `column-gap: normal` (1em on a multicol
box, unlike the 0 it means on a grid), `column-rule` taking no space,
`break-inside: avoid` against a control that lets the block split,
`column-span: all`, `column-fill: auto`, a definite height overflowing
sideways, padding outside the columns, and text flowing at the *column's*
inline size.

**`position: sticky`, 4 cases.** The corpus had one, and it covered the
unscrolled default — which `layout.cljc` is explicitly, correctly scoped
out of. But a sticky box **is stuck with no scrolling at all** when it is
anchored to the far edge of a scrollport it starts below, and that is what
these use. No `:oracle/isolated` needed: the harness scrolls the *window*,
and these anchor inside an inner scroll container, so the measurement does
not depend on where the case sits on the page.

**interactive elements, 9 cases.** `<details>` closed, open, and with no
`<summary>`; `<dialog>` with and without `open`; `<template>`; the
`hidden` attribute and an author `display` beating it.

**tables, 8 cases.** Nested tables, `caption-side: bottom`, `<colgroup
span>`, `table-layout: fixed` with overflowing content, `empty-cells`,
`<tfoot>` written before `<tbody>`, a bare `<col>`, a percentage cell
width.

**intrinsic sizes and clamps, 10 cases.** `min-width`/`max-width` on flex
and grid items, `min-content`/`max-content`/`fit-content` as `width`
values, the automatic minimum size of a flex item and what `overflow:
hidden` does to it.

**`gap`, 5 cases**, across the three box types that take one: percentage
gaps on both axes, `row-gap` between flex lines, `gap` on a multicol box,
the two-value shorthand.

**scroll containers, 7 cases**, and **5 real-page composites** (labelled
`:composite`): a sidebar, a sticky header over scrolling content, a card
grid, a two-column article, a data table with a caption and a foot.

#### Four fixes the new cases found

**1. A flex container read the single `:gap` for both axes.** `row-gap`,
`column-gap`, and the second half of `gap: <row> <column>` all did nothing
on a flex box, while a grid two lines away in the same `node-style` map
honoured all three. Measured: `flex-wrap: wrap; row-gap: 12px;
column-gap: 8px` over two 120px items in a 200px box is **52px** tall with
the second line at **y=32** in Brave; this engine had 40 and y=20. And
`gap: 6px 18px` on a flex row put the second item at **x=106** where Brave
says 118 — the shorthand's row half used for the column axis, because
`parse-int "6px 18px"` is 6. `layout-flex` now takes its main-axis gap
from `column-gap`/`row-gap` by direction, and `layout-flex-wrap-row` takes
its cross-axis gap from `row-gap`.

**2. `distribute-excess` grew a column a `<col>` had DECLARED.** A table's
surplus width is handed to its columns in proportion to their demand,
which is right for automatic columns and wrong for declared ones.
Measured: `<table style="width:300px"><col style="width:200px"><col>
<tr><td>a</td><td>b</td></tr></table>` is **200 + 94** in Brave; this
engine had **281 + 13**. Declared columns are now locked and the automatic
ones absorb the surplus. When *every* column is declared the proportional
hand-out still applies — `table-fixed-column-widths` relies on exactly
that, and its own case is unchanged.

**3. Row groups were laid out in SOURCE order.** `<tfoot>` before
`<tbody>` is idiomatic HTML — it exists so a UA can paint the footer
before it has streamed the body — and it renders **last**. Measured, Brave
puts `tfoot` at y=26 and `tbody` at y=2; this engine had them the other
way round. `table-rows` now partitions header/body/footer, stably, so
order *within* a group is still document order.

**4. Two UA rules the sheet was missing.** `template { display: none }`
and `dialog:not([open]) { display: none }`. A closed `<dialog>` rendered
its own text straight into the page — the text of a dialog nobody has
opened, which is as visible a bug as this corpus has found in a while.

#### The new divergences, grouped by cause

**No multi-column implementation at all** — 11 cases, ~90 box mismatches,
and the single largest cluster in the corpus. Columns are laid out as one
stacked block column: `column-count: 2` over four 30px blocks is
(0,0) (0,30) (160,0) (160,30) in Brave and (0,0) (0,30) (0,60) (0,90)
here, and text wraps at the container's 300px rather than the column's
140px. `column-span: all` is the one case that passes the line axis, by
coincidence — the spanner is full-width either way.

**No scroll-position-dependent sticky offset** — 2 cases. A `bottom: 0`
sticky box whose flow position is below its scrollport is pulled **up** to
sit on it (y=100 → **40**), and clamped by its containing block when that
is what stops it (100 → **80**, not the 40 the inset asks for). The two
unscrolled cases still agree, so the same round both confirms the existing
scope-cut is correct *and* bounds it.

**`width: min-content | max-content | fit-content` is treated as `auto`** —
3 cases. `min-content` over "alpha beta" is **35px** in Brave and 300
here; `max-content` and `fit-content` are **70** and 300. The intrinsic
measurement itself already exists in `layout.cljc` — tables and flex items
both use it — it is simply not wired to the `width` property. This is the
cheapest-looking of the remaining gaps.

**Flex min/max clamping does not redistribute** — 2 cases. `min-width:
150px` on one of two `flex: 1` items in a 200px row leaves the other at
its unclamped **100** instead of the **50** that is left, and `max-width:
60px` in a 300px row leaves it at 150 instead of 240. The clamp is
applied; the freeze-and-re-run loop that gives the freed space to the
other items is not there.

**Percentage gaps are read as pixels** — 2 cases. `column-gap: 10%` of a
300px grid is **30** in Brave and 10 here. The block axis is the cyclic
one: Brave resolves a 10% row gap against the grid's *own* 40px content
height and gets **4**. Both are the same defect — `node-style` runs
`parse-int` on the string, and it has no available size to resolve a
percentage against, which is where a fix belongs.

**Inline-blocks wrap inside a `white-space: nowrap` scroll container** —
2 cases, and this one was *re-attributed* by a control. The sticky
horizontal-scroller case looked like a sticky failure; the identical
markup without the sticky wraps the second inline-block onto a second line
too (container 40px tall, not 20), so the wrap is the cause and the sticky
pull (x=300 → 160 in Brave) is a second, smaller one on top of it.

**`caption-side: bottom`** — 2 cases. Already a documented scope-cut in
`layout-table`'s docstring; now it has a number: the caption is 26px down
in Brave and at 0 here, and everything below it shifts by its height.

**The `<dialog open>` UA box** — 1 case. `position: absolute; margin:
auto; width: fit-content; height: fit-content; border: solid; padding:
1em` gives **48x54 at x=126**; this engine lays out an ordinary 300x20
block. Left alone deliberately: it cannot be right until `fit-content`
(above) is, and adding the padding alone would move the numbers without
converging on them.

**A `<details>` with no `<summary>`** — 1 case. Brave synthesises one and
the element is 20px tall; this engine renders it as empty.
`with-details-visibility`'s docstring already says a summary-less
`<details>` is out of scope, so this is that scope-cut, measured.

**Not a `cssom` finding, recorded where it was seen:** `htmldom` does not
synthesise the implicit `<colgroup>` a real HTML parser inserts around a
bare `<col>`, so Brave reports one box this side has not got. The declared
width now resolves correctly either way (fix 2); only the box count
differs.

#### Two cases are `:oracle/blind`, for the reason the corpus already has

`:interactive/details-closed-shows-only-the-summary`. Chromium hides a
closed `<details>`'s content with `content-visibility: hidden`, **not**
`display: none`: measured, the `<p>` reports a real 300x20 box at y=34 and
a `Range` reads "Body" out of it — and it is never painted. An engine that
emits draw-ops cannot report a box it does not paint. This is the
`text-overflow: ellipsis` situation exactly: comparing the two on text
compares a DOM to a rendering. Geometry and paint order still score.

`:page/sidebar-with-a-nav-list`, for list markers, exactly like
`:page/nested-lists-in-nav`.

#### One thing the harness cannot see, so no case was added

`object-fit` / `object-position` change what is painted **inside** a
replaced element's box. They change neither the box, nor hit testing, nor
any of the 14 properties the computed-style axis compares — there is
nothing here for either side to disagree about, at any value. A case that
can never be scored does not belong in the corpus, and saying so is more
useful than a case that passes for a reason unrelated to the feature.

#### One case was written expecting a divergence and found none

`:overflow/auto-reserves-no-gutter-in-this-oracle` (and the two beside
it). A classic scrollbar takes its width out of a scroll container's
*content* box, and this engine reserves nothing — so this looked like a
guaranteed 15px divergence. Brave 151 headless on macOS uses **overlay
scrollbars** and reserves nothing either. The three cases stay in, named
for what they actually measure: on a classic-scrollbar oracle they would
all diverge by the gutter width, and this corpus should notice when the
oracle changes underneath it.

### Round thirty-one: an element's box and its hit region are two different things

The paint-order axis was at **9202/9234, 356/370 clean**, and its whole
residual sat on one question the axis had never asked out loud: a `:node`
draw-op's `:x`/`:y`/`:w`/`:h` had to be both the box a browser *reports*
and the region a browser *clicks*, and those are not the same rectangle.
Three measurements say so, all taken in Brave 151 over CDP before anything
was changed.

**A wrapped inline box is hit only inside its fragments.** `<p style=
"width:200px">alpha beta gamma <b>delta epsilon</b> zeta eta</p>` gives the
`<b>` client rects `[119,1,33.7,18]` and `[0,22,46.8,18]`, a bounding rect
of `[0,1,152.7,39]`, and answers `elementFromPoint(80, 4)` — inside that
union, inside neither fragment — with **`p`**. `inline-owner-ops` emitted
the union and documented it as "an honest, documented approximation of real
CSS's per-fragment box list"; the geometry axis wanted exactly that union
(`getBoundingClientRect` *is* the union) and the paint-order axis was
charging all five of `:wrap/inline-element-straddles-break`'s sample points
to it, plus two of `:wrap/link-wraps-across-two-lines`' and one of
`:page/login-form`'s.

**Overflowing inline content is hit outside the box, per line.**
`<p style="width:80px">short aaaaaaaaaaaaaaaaaaaa tail</p>` reports an
80×60 box and is hit out to **x=140 on its middle line** — the long word,
which does not fit — and stops at x=80 on the two lines that do. So it is
not `scrollWidth` and it is not a rectangle around the element: it is the
lines themselves. Eleven points (`:text/nowrap-in-narrow-box`,
`:text/white-space-pre-in-a-narrow-box`, `:wrap/single-word-longer-than-
line`) were the engine reporting **nothing** where the browser reports the
paragraph.

**A table row and row group are never hit at all.** Not "they have no
background": measured with `background` set on the `<tbody>` *and* on both
`<tr>`s and `border-spacing: 6px` opening real gaps between the rows,
`elementsFromPoint` over every point of that table returns `td, table`
inside a cell and `table` alone everywhere else. Neither `tr` nor `tbody`
appears at any point. A row's painted background *is* hit — as the table.

**The fix is a second key, not a wider box.** A `:node` op now carries an
optional `:hit`: a vector of rects that replaces `:x`/`:y`/`:w`/`:h` for
hit testing, `[]` meaning "not a hit-test candidate". Absent — the
overwhelmingly common case — means the border box, so nothing that reads
`:node` ops today changes shape. Widening the box instead would have made
every `getBoundingClientRect` comparison wrong to fix the hit test, and
this corpus scores both: **geometry did not move at all** (1335/1335, 370
clean, byte-identical residual).

A fourth cause was ordinary paint order rather than a hit region.
**A float was painted under its own siblings.** CSS 2.1 Appendix E paints
in-flow block-level boxes (step 3) *before* non-positioned floats (step 4),
and this engine emitted a float's ops at the point in the child list where
it was written — so a following `<p>`'s background covered it whole, which
is every ordinary use of a float, because a float narrows a sibling's line
boxes and not its border box. Measured with real backgrounds on both: the
float's colour is what is visible over its own width and what
`elementFromPoint` answers there. `layout-children-block` now accumulates
float draws in their own band and concatenates them after the in-flow ones.
Appendix E puts in-flow *inline* content (step 5) above floats again, which
this still does not model — the whole of a block child's op run goes in one
band — and that is inert here because `float-band` is what narrows the line
boxes in the first place, so in-flow text does not overlap a float it can
see. Five points, all of `:float/float-right-block-with-width`.

**Result**, same corpus, same commit either side:

| axis | before | after |
|---|---|---|
| line structure | 356/356 | 356/356 |
| geometry (boxes) | 1335/1335 | 1335/1335 |
| geometry (clean cases) | 370/370 | 370/370 |
| paint order | 9202/9234 | **9227/9234** |
| paint order (clean cases) | 356/370 | **364/370** |
| computed style | 18687/18763 | 18687/18763 |

`--debug-paint` was added alongside `--debug-geometry`/`--debug-style`, and
prints for every disagreeing case the engine's `:node` ops **in emitted
order** with each sampled point. Without the order a paint-order residual
is unreadable, because the boxes it is made of usually all agree — which is
the axis's entire point.

#### The seven that are left, and why they are not converged

All seven are boundary points where the two sides are within about one
pixel of each other, from two causes, and **neither is a paint-order
error**. They are listed here rather than fixed because fixing either one
would mean breaking something that is currently right.

Four are **this engine flooring a fractional layout position**, inside the
geometry axis's own 2px tolerance and therefore invisible to it:
`:page/article-paragraphs` puts its first `<p>` at y=38 where Brave says
38.75 (an `<h1>`'s `0.67em` bottom margin on a 28px font is 18.76px), and
the sample point is at y=80.3 — inside the browser's box, one third of a
pixel outside the engine's. `:page/table-of-contents` (40 vs 40.609) and
`:page/login-form` (40 vs 40.422) are the same shape;
`:form/textarea-with-rows` is the mirror, an engine box 0.86px *taller*
than the browser's, from the fractional font metrics the harness's own
`:font-metrics` hook already documents. Rounding these is a layout change
that moves every UA margin in the corpus, which is the geometry axis's
subject and not this one's.

Three are **the oracle's own leading-edge slop**, and this one is worth
writing down because it is not in any spec. Scanned at 1/64px, Brave's
`elementFromPoint` answers a box's **top** edge exactly one CSS pixel
early and its **bottom** edge exactly: two stacked 30px divs switch at
**29.0156**, two stacked 30.5px divs at **29.5156**, and a 40px inline
whose box is `y=9..51` is hit from **8.0156** to **51.0000**. It is a
constant one pixel — not font-relative, not box-type-relative, not
affected by fractional position — and the left/right edges have no slop at
all. `:inline/em-strong-code` (point 0.9px above the `<code>`),
`:form/input-inside-a-table-cell` (0.3px above the `<input>`) and
`:page/login-form` are inside it. Modelling it would mean shifting every
element's hit region one pixel up, so `browser.session/node-at` would
deliver every real click one pixel above the box it belongs to — a
regression in the actual product to win three points against one browser's
implementation detail. It stays measured and unmodelled.

`:page/login-form`'s single point changed *kind* rather than count: it was
`form -> label`, the union box over-claiming a 36px-tall rectangle, and is
now `label -> form`, a 0.42px rounding at a fragment's bottom edge.

### Round thirty-two: the corpus grows 370 → 441, into text, typography and selectors

**Every number on this page fell, and that is the point.** All four axes
were at 100% of what the 370-case corpus could ask — line 356/356, geometry
1335/1335 boxes across 370/370 clean cases, paint order 9227/9234, computed
style 18687/18763 — and **a corpus that scores 100% can no longer find
defects**. Seventy-one cases were added in two areas the corpus had barely
entered: text and typography (28 of them), and selectors, specificity and
the cascade (43). The drop below is added coverage, not a regression:
`src/` was **not touched** by this round, and 736 tests / 1702 assertions and
the linter are green and unchanged either side (0 failures, 0 errors; 0 lint
errors, 25 pre-existing warnings, all in `test/`). Both columns were measured
on the SAME commit, after merging the hit-region round that landed alongside
this one, so nothing in the table is another round's doing.

| axis | 370 cases | 441 cases |
|---|---|---|
| line structure | 356/356 = **100%** | 411/425 = **97%** |
| geometry (boxes) | 1335/1335 = **100%** | 1484/1521 = **98%** |
| geometry (clean cases) | 370/370 | **416/441** |
| paint order | 9227/9234 | **10952/11014** |
| paint order (clean cases) | 364/370 | **422/441** |
| computed style (values) | 18687/18763 | **21278/21362** |
| computed style (cases with no cascade-attributed mismatch) | 369/370 | **434/441** |
| cascade-attributed residual | **1** | **9** |

Every case was measured in a real headless Brave 151 over CDP *before* it
was added, and the first draft had to be thrown away for a reason worth
recording: **five of the text cases passed while measuring nothing.** A
`letter-spacing: 4px` on `alpha beta` inside a 120px box is 110px against a
bare 70px, and *both* fit — so the case agreed with the browser about a
property neither side had applied. Every width in the text group below is
now a number at which the two answers must differ (100px for that one), and
they were found by driving the shapes through the browser and reading the
line boxes back, not by arithmetic.

#### What the text group found — nine gaps, five causes

- **`letter-spacing` and `word-spacing` never reach a text run's advance.**
  Both are already in `cssom.layout`'s inherited set, so they arrive and are
  then unused. Measured four ways: a wrap point (`alpha beta` at 100px is
  two lines in Brave, one here), its mirror (a *negative* letter-spacing
  makes 70px of text fit 60px — Brave one line, this engine two), an inline
  box's own width (`<b letter-spacing:3px>wide</b>` is **42.69** against
  **30.68**; `<b word-spacing:10px>one two</b>` is **64.14** against
  **54.14**), and a shrink-to-fit box (**44** against **28**). Six cases.

- **`text-indent` is not implemented at all** — the property does not appear
  in `cssom.layout`. Four cases, and the shapes are chosen so the indent
  changes what wraps rather than only where the first line starts, because
  neither layout axis compares a text run's x. `160px`/`40px` is two lines
  in Brave and one here; `text-indent: 50%` resolves against the *containing
  block*; the property **inherits**, so a 60px indent declared on a wrapper
  reaches the `<p>` inside it; and `hanging` inverts it — the same text is
  **three** lines in Brave and two here. `each-line` re-applies after a
  forced break, which the geometry axis reads directly as the `<br>` at
  **x=65** against **x=35**.

- **A tab is not one space, and `tab-size` is not read.** In a shrink-to-fit
  `white-space: pre` box, `a<tab>b` is **63px** in Brave — nine columns, the
  next multiple of the initial `tab-size: 8` — and **35px** at `tab-size: 4`.
  This engine reports **21px** for both, i.e. three characters. The pair is
  deliberate: one case alone cannot separate *the tab stop is wrong* from
  *`tab-size` is ignored*. `white-space: pre` also drops leading spaces from
  the measured width (**77** against **63**).

- **Three break-opportunity mechanisms are missing, and one is applied too
  eagerly.** A soft hyphen (U+00AD) is a break point under the initial
  `hyphens: manual` — Brave breaks the word and the box is 40px tall against
  20 here. `hyphens: auto` with `lang="en"` breaks `hyphenation` and gives 60
  against 40. `text-wrap: balance` gives `alpha beta gamma` / `delta epsilon`
  where a greedy breaker gives `alpha beta gamma delta` / `epsilon`.
  `white-space: break-spaces` is one of the four *preserving* values, so its
  newline survives — two lines in Brave, one here. And in the other
  direction, `white-space: nowrap` on an **inline child** makes this engine
  break either side of it: `alpha` / `beta gamma` / `delta` against Brave's
  `alpha beta gamma` / `delta`.

- **The three break keywords are treated as one, and one of them differs.**
  `cssom.layout`'s `break?` is `#{"break-word" "anywhere" "break-all"}`, but
  they part company exactly where a box is being **sized**: `anywhere` and
  `break-all` let a long word break while its min-content width is computed,
  so an inline-block takes the 60px on offer; `break-word` does **not**, so
  the same box is **105px** and overflows. Brave: `60×20` with a `105×20`
  span. This engine: `60×40` and `60×40`. The three cases are in the corpus
  together because two of them agreeing is what makes the third one a
  finding rather than a guess.

- **`font-size`'s absolute keywords cost more than the font size.**
  `resolve-font-size` returns nil for `large`/`x-large`/... on purpose (the
  table is keyed on the default font of the *family*, which the cascade
  cannot know — `large` is 16px in this page's monospace and 18 in a
  proportional one). Measured, that unresolved value propagates: the UA
  `p { margin: 1em 0 }` then resolves against 14px instead of 16, so the
  margins are wrong too. Two of the nine cascade-attributed values. Its
  contrast partner `font-size: larger` is a pure ratio off the parent, needs
  no family table, and agrees exactly.

- **`<q>` gets no quotation marks.** The oracle's per-word Ranges cannot see
  generated content, so the *line* axis is blind here — but the `<q>`'s own
  box is two characters wider each side and the geometry axis reads it
  directly: **63** against **35**, and a nested `<q>` **91/35** at x **14/42**
  against **35/7** at x **14/28**.

#### And one margin rule, found sideways

`:selector/empty-pseudo-class` was written to check that `p:empty` matches.
It does — and the case failed anyway, on geometry and on 15 paint-order
points. An empty block with no border or padding is **self-collapsing**: its
own top and bottom margins collapse into one, that one then collapses with
the next sibling's top margin, and with nothing to stop the set at the
parent's top edge the whole of it escapes. Brave puts both children at
**y=0** and the parent is **20px** tall; this engine leaves 14px inside and
reports **34**. `:block/an-empty-block-collapses-through-itself` isolates it
away from the selector.

#### The selector and cascade axis: 43 cases, 5 divergences

This is the axis with the most to lose from a corpus that cannot see it —
the computed-style residual was **1 value** across 370 cases, and there were
six selector cases in the whole corpus. Forty-three more went in:
`:nth-child()` with a negative coefficient / `An+B` / `odd`/`even` / `of
<selector>`, `:nth-of-type`, `:nth-last-child`, `:first-`/`:last-`/`:only-
child`, `:first-of-type`, `:empty`, `:not()` with a selector list and with a
nested pseudo-class, `:has()` in its descendant, direct-child and sibling
forms, `+` and `~`, all six attribute operators plus the case-insensitive
`i` flag, four specificity shapes, three importance-versus-inline-style
shapes, and eight `var()`/shorthand/CSS-wide-keyword shapes.

**Thirty-eight of the forty-three agree with Brave exactly**, which is a
measurement and not a formality: it is the first evidence that the selector
engine's An+B matching, its `:is()`-versus-`:where()` specificity split, its
`:not()` argument specificity, its six attribute operators and its
inline-versus-`!important` ordering are right, rather than merely present.
Two of them also correct this file's own prose: `:is(.wrap section) p` and
`p:not(:first-child)` are both named as out of scope in `cssom.core`'s
docstring and both **work** (a control, `:is(.nomatch xyz) p`, correctly
matches nothing) — the docstring is stale, not the engine incomplete.

The five that diverge:

| case | Brave | this engine |
|---|---|---|
| `:selector/nth-child-of-a-selector` | `2n+1 of .m` counts among the `.m` siblings only, so the first `.m` is bold | the `of` clause is not parsed; nothing matches |
| `:selector/has-a-following-sibling` | `h2:has(~ p)` matches | the sibling-relative form is named as out of scope, and is |
| `:cascade/inherit-on-a-non-inherited-property` | `padding-left: inherit` takes the parent's 40px | `inherit-keyword?` *removes* the declaration, which is right for an inherited property and gives 0 here |
| `:cascade/initial-keyword-resets-to-the-css-initial-value` | `text-align: initial` is `start` | the literal string `initial` is stored |
| `:cascade/revert-drops-to-the-user-agent-value` | `margin: revert` rolls the author origin back, so the UA `1em 0` returns | the literal string `revert` is stored and the author `margin: 0` stands |

The last three are `cssom.core`'s own documented cut — `initial`/`unset`/
`revert` are "deliberately NOT handled" — and the point of writing them into
the corpus is that a documented cut and a measured one are different things.
`revert` in particular needed an author rule to measure at all: with no
author declaration to roll back, *ignoring* `revert` and *obeying* it both
leave the UA margin standing, and the first draft of that case passed for
exactly that reason.

Two more are visible but not scorable, and the harness says so rather than
scoring them: `color: var(--missing)` after a `color: #0000ff` leaves this
engine's cascade holding the **empty string** (real CSS makes the whole
declaration invalid at computed-value time and the property inherits), and
`color: unset` is stored as the literal `unset`. They land in the excluded
buckets as `absent` and `unparseable-color`, printed with their case ids.

#### Not added, and why

- **`text-decoration` and its `-thickness`/`-offset`/`-line` longhands.**
  No axis reads them: decoration does not change a box, a line, or a hit
  region, and the computed-style axis compares fourteen properties none of
  which is a decoration. A case would be scored on everything except the
  thing it was written for.
- **`::first-line` and `::first-letter`.** Neither produces an element box,
  so neither the geometry axis (which walks elements) nor the oracle's
  element probe can see one.
- **`font-variant: small-caps`.** It *is* scorable — Brave renders
  `caps here` at 47px against a plain 63 — but the number comes from a
  synthesized small-caps face whose advances the harness's per-character
  probe does not measure, so the expected value would not reproduce on a
  machine with a different `monospace`. Recorded here instead of pinned.

### Round thirty: `direction: rtl` inside a line, and the coincidence that was not one

Round twenty-six left `direction: rtl` implemented at block level only, and
wrote down why it stopped there: right-aligning `text/rtl-with-inline-
elements` would land its `<b>` within 2px of Brave "purely because the text
either side of it is the same width, while the words on the line were still
in the wrong order."

Measuring it first says the second half of that is not true. Driven through
`cdp_dump.cljs` on twenty-two rtl shapes that were not in the corpus:

| shape (300px block) | ltr | rtl |
|---|---|---|
| `alpha <b>beta</b> gamma` | 0 / 42 / 79.52 | 185.48 / 227.48 / 265 |
| `aa <b>bbbb</b> cccccc <i>d</i> ee` | 0 / 21 / 62.13 / 111.13 / 126.55 | 159.45 / 180.45 / 221.58 / 270.58 / 286 |

The **same order** both times, every word shifted by the same amount, and
that amount is the line's own leftover in the content width (300 − 114.52 =
185.48; 300 − 140.55 = 159.45). Brave does not reorder either line, and it
is not being lenient: UAX #9 resolves a line whose every word is strong
left-to-right into a single left-to-right run, placed at the line's
inline-end. The shift was never the coincidence — believing the words had to
move was the error. The asymmetric second row is in the corpus now
(`text/rtl-two-inline-elements`) precisely because nothing about it is
symmetric, so no coincidence can produce it.

What genuinely reverses is strong-rtl script, and that was measured too:

| shape (300px rtl block) | Brave, left to right |
|---|---|
| `שלום עולם אבג` | `אבג` 193.58, `עולם` 225.78, `שלום` 266.38 — reversed |
| `שלום one two אבג` | `אבג` 178.17, `one` 210.39, `two` 238.39, `שלום` 266.39 — the Latin run keeps ITS order |
| `alpha שלום עולם beta` in an **ltr** block | alpha 0, `עולם` 42, `שלום` 82.59, beta 123.22 — only the pair swapped |
| `שלום 123 אבג` | `אבג` 206.17, `123` 238.39, `שלום` 266.39 — the number reads forward |

So both halves landed, and only together: **line placement** (which edge a
line packs against, including `text-align`'s direction-relative
`start`/`end` — all eight {ltr,rtl}×{start,end,left,right} combinations
measured) and **run reordering** (UAX #9 rule L2, applied per line after
breaking, at the granularity of whole words).

**What is not implemented, and what it costs.** A word is the smallest
thing that carries a direction: no per-character bidi classes, no W-rules
for numbers, no explicit embedding/override/isolate controls, no
`unicode-bidi`. Measured, that costs exactly one shape — a single word
holding strong characters of both directions (`שלוםabc`) is split by Brave
into two runs inside the word and kept whole here.
`text/rtl-mixed-direction-word-is-not-split` records it, and records that
it **passes**: a multi-rect word is re-grouped per character by top on the
oracle side (a facility that exists for words broken across LINES), so both
of its rects fold back into one entry and the line axis cannot see the
split at all. A passing case is not evidence of agreement here; the
boundary is real and this harness has no axis for it.

**A bug the fix exposed.** `layout-text`'s box shrink-wraps its widest line,
so a bare text child looked like a narrow block to
`layout-children-block`'s own rtl rule and got pushed to the right edge by
it — the right answer reached by the wrong mechanism, invisible while it
was the only mechanism. With line placement in force as well,
`<p style="direction: rtl">alpha beta</p>` was shifted right **twice** and
its text left the paragraph. Real CSS wraps such a child in an anonymous
block box that fills its containing block, so that rule has nothing to
place; it now skips text and generated-text children, and the line rule
owns them. Nothing on any axis moved when it was fixed — with the
harness's `padding: 0` the two mechanisms had coincided to the pixel — 
which is exactly why a draw-op diff of all 357 pre-existing cases was taken
rather than trusting the scoreboard.

**Result**, on a corpus grown 357 → 370 by the thirteen cases above:

| axis | before | after |
|---|---|---|
| line structure | 342/344 | 355/357 |
| geometry (boxes) | 1304/1313 | 1327/1335 |
| geometry (clean cases) | 349/357 | 363/370 |
| paint order | 8866/8909 | 9196/9234 |
| computed style | 16138/18453 | 16400/18761 |

Every one of the thirteen new cases passes on all three layout axes;
`text/rtl-with-inline-elements` moves from a −185.48 `b x` and a
`b -> p` paint-order miss on five sample points to clean; and a draw-op
diff of the whole pre-existing corpus shows exactly one case changed.

### Round twenty-nine: the marker that is not in the item, and the line-height that inherits a ratio

Two residuals the geometry axis had localised to the pixel, both measured in
Brave before anything was written.

**`list-style-position`.** Its default is `outside`: the marker is a box of
its own beside the item, not the first thing in the item's content. This
engine synthesised the marker as the `<li>`'s own `::before` and let it flow
inline, so it advanced the item's line and widened anything that
shrink-wrapped the list. Round twenty-eight had already named this as the
whole remaining cause of `:table/cell-with-a-list`, and named the property
as the honest fix rather than a wider cell.

| markup (14px monospace) | Brave | engine |
|---|---|---|
| `<ul><li><a>First section</a>` — `a` x | 40 | 53.70625 |
| `<td><ul><li>one</li><li>two</li></ul>` — `td` w | 63 | 76.70625 |
| the same, `ul` w / `li` w | 61 / 21 | 74.70625 / 34.70625 |

13.70625 is exactly one `"• "` advance in every row. The marker now travels
through the inline pipeline as a fragment of its own KIND: never merged into
the item's text run, never collapsing whitespace against it, never moving
the pen, contributing nothing to the run's max-content width, and painted at
the negative x that puts it immediately before the content edge.
`list-style-position: inside` keeps the old inline behaviour, which makes it
a real property rather than an accident — Brave puts the same `<a>` at x=59
and the same cell at 82px there, so the two values are 19px apart in the
oracle and were 0 apart here.

**What this axis cannot check, said out loud.** A `::marker` is not an
element, so `getBoundingClientRect` has nothing to return for it and the
oracle reports no box for it in either direction. Where the marker itself
paints is therefore unverifiable by this harness, and no assertion here
claims otherwise. What IS verifiable — and what all three failing cases
measured — is the position and width of the item's CONTENT. The closest the
harness can get to the marker's own size is the `inside` value, whose marker
does take inline space: measured there, Brave's `<ol>` advance IS the width
of the marker string (21px for `1. `, 28 for `10. `, 35 for `100. `), which
is what this engine uses, while its `<ul>` disc box is a function of the
font-size alone (19px at 14px, 14 at 10px, 37 at 28px — identical for Arial
and monospace and for `list-style-type: square`, i.e. not the 6.7px bullet
glyph). That 5.3px is recorded in `list-style-inside?` rather than modelled
from three points.

**Unitless `line-height` inherits the FACTOR.** `line-height: 1.5` inherits
as the NUMBER, so a child with a different `font-size` re-multiplies it;
`1.5em` resolves once and inherits as that length. `inherited` only ever
carried resolved pixels, so both reached a 24px child as 21px. The corpus
holds both halves on purpose (`:text/unitless-line-height-inherits-the-factor`
and `:text/em-line-height-inherits-the-computed-value`) and only the unitless
one failed, which is what localised it: Brave reports 36/36 for the first
and 21/21 for the second.

On the way, the multiplier forms are parsed explicitly instead of through
`parse-dbl`, which does not answer the same question on both platforms —
`Double/parseDouble` rejects a trailing unit and `js/parseFloat` ignores
one. `line-height: 1.5em` was 21px under nbb (right, by accident) and the
theme default on the JVM, and `150%` was **150x** the font-size rather than
1.5x. `rem` and the viewport units now fall through to `normal` rather than
being multiplied by a length this file cannot resolve.

**Result**, at the same 341 cases: geometry 1244/1283 → **1256/1283**
(321 → **324** clean), paint order 8429/8509 → **8444/8509** (319 → **321**
clean), line structure and computed style unchanged at 326/328 and
15678/17949 — this is a layout change and the cascade axis should not move.
Four cases' boxes changed. `:page/table-of-contents`,
`:table/cell-with-a-list` and `:text/unitless-line-height-inherits-the-factor`
all went to **every box exact**; `:table/cell-with-a-list` had been 0/8 since
the corpus gained tables.

The fourth is a 1px cost, stated rather than hidden: `:selector/nth-child`
bolds its second `<li>`, and that item now reaches the inline path (it has
two children — marker and text — where the merged marker made it one) where
it used to reach `layout-text`. The two paths round a bold line box
differently, so it reports 21 where the browser and `layout-text` say 20. It
stays within the 2px tolerance and the case stays clean, but it is a real
pre-existing disagreement between two height models that this change
exposed on more elements rather than one it introduced.

**Still not fixed, and not the same cause.** `:page/two-column-text` and
`:overflow/x-hidden-y-scroll` were in the same residual and were checked
against these two causes before being left: both are margin questions. A
flex ITEM's margins do not collapse (Brave puts each `<p>` at y=14 and the
container at 68; this engine drops them), and an `overflow` container
establishes a block formatting context so the margin inside it cannot
collapse through (Brave y=14, this engine 0). Neither has anything to do
with markers or line-height, and improving them from here would have meant
guessing.

An `<li>` whose content is a BLOCK (`<li><div>x</div></li>`) still gives its
outside marker a row of its own where Brave puts it beside the block's first
line — the item comes out one line too tall. Unchanged by this round (the
marker had its own row before too), no corpus case covers it, and it is
named in the namespace docstring.

### Round twenty-eight: the border every content box was standing on

`inset-side` — the whole engine's answer to *where does this box's content
start* — counted the border only under `box-sizing: border-box`. Named as
still-open by rounds twenty-five, twenty-six and twenty-seven, each time
with the same reason for not doing it: it is one change to the box model
rather than one UA reading, and it moves every bordered box on every page.

**The rule, measured before anything was written.** `box-sizing` does not
move the content edge. It decides what a *declared* `width`/`height`
measures — the content box under `content-box`, the border box under
`border-box` — and that question belongs to `resolve-width` /
`used-block-height`, which already answered it correctly through
`declared-inset-side`. Where the content *starts* is border + padding in
from the border edge either way. Ten shapes in Brave 151 over CDP
(2026-08-05), reading `getBoundingClientRect` and `clientLeft`/`clientWidth`
side by side, all say the same thing:

| shape | child lands at | `clientLeft` |
|---|---|---|
| `div{border:2px;padding:10px}` | x=12, w=376 of 400 | 2 |
| `div{box-sizing:border-box;width:200px;border:2px;padding:10px}` | x=12, w=176 | 2 |
| `div{width:200px;border:5px;padding:4px 8px 12px 16px}` | x=21, y=23, w=200 | 5 |
| `div{display:flex;width:200px;border:3px;padding:7px}` | items at x=10, y=10 | 3 |
| `div{display:grid;...;border:3px;padding:7px}` | items at x=10, y=10 | 3 |
| `td{border:4px;padding:6px}` | child at x=10, y=24 | 4 |
| `fieldset` (UA 2px border) | `<p>` at x=12.5 of the fieldset | 2 |

Two neighbours it is **not**, both re-measured so the change would not
swallow them:

- **An absolutely positioned descendant's containing block is the PADDING
  box** — border alone, padding *outside* it. `div{position:relative;
  border:7px;padding:9px}` puts a `top:0;left:0` child at **x=7**, not
  x=16. `layout-block` computes that separately (`pad-x`/`pad-y`) and it
  stays separate.
- **An `overflow` clip lands on the padding box too**, and this engine
  clips at the border box: `div{width:100px;height:40px;overflow:hidden;
  border:6px;padding:5px}` is a 122x62 border box reporting a 110x50
  scrollport. Left alone and written up as a scope cut at the clip site —
  it is a *paint* change where this is a *layout* change, and nothing in
  the corpus scores a clip edge.

**One place had to give the border back.** Under `border-collapse:
collapse` a cell keeps only *half* of each grid line it sits between, and
the column widths were built by ADDING the inner half to a natural width
that was "content + padding, never border". With the border now in the
natural width that double-counted, and `:table/border-collapse` went 5/5 →
**0/5** — cells 15px wide where Brave says 11. Subtracting the *outer* half
instead is the same rule against the corrected input, and restored it to
5/5 exactly. It is the one thing in this round that a net score would have
hidden: geometry read 1241 → 1239 with three cases improving.

**Result.** Per-case old-vs-new box diff over all 341 cases, replaying the
harness's own tag+nearest pairing: **four cases changed, three improved,
none regressed**, and every box that moved landed on the oracle.

| case | boxes | what moved |
|---|---|---|
| `:form/fieldset-and-legend` | 2/3 → **3/3** | `<p>` (12.5, 36.9, 775) → (14.5, 38.9, 771); Brave (14.5, 38.891, 771) |
| `:position/absolute-containing-block-is-the-padding-box` | 2/3 → **3/3** | `<p>` (20, 34, 210) → (25, 39, 200); Brave (25, 39, 200) |
| `:page/toolbar-with-auto-margin` | 4/5 → **5/5** | container h 32 → 34 (Brave 35), children +1px in |
| `:page/card-with-absolute-badge` | 4/4 → 4/4 | `<h3>`/`<p>` (12, 28, 242) → (13, 29, 240) = Brave exactly; was already inside the 2px tolerance |

Geometry **1241 → 1244** boxes, **318 → 321** clean cases; paint order
**8424 → 8429** (the `p -> fieldset` cluster of 5 is gone, and nothing
replaced it); line structure 326/328 and computed style 15678/17949
unchanged, with per-case status identical on the line axis.

Four probe shapes run through both engines to check the axes the corpus
does not cover, before and after:

| shape | before | after | Brave |
|---|---|---|---|
| `flex{width:200px;height:60px;border:5px;padding:8px}` | 226x76, item (8,8) | **226x86, item (13,13)** | 226x86, (13,13) |
| same as `block` | 226x86, item (8,8,210) | **226x86, item (13,13,200)** | 226x86, (13,13,200) |
| same as `grid` | h **60**, item (8,8) | h **60**, item **(13,13)** | h 86, (13,13) |
| `overflow:hidden;border:6px;padding:5px` | item (5,5) | **item (11,11)** | (11,11) |

The grid container's height is the one thing that did not move: `layout-grid`
reads `resolve-height` straight into its own `node-h` instead of
`used-block-height`, so a `content-box` grid with a declared height is still
its content height rather than its border box. That was already true before
this round and is untouched by it — named here rather than left to be
rediscovered.

**Tests.** Sixteen assertions across four tests changed answer, every one
rewritten with the reason rather than with a new number, and every one of
them a test that had been pinning the *wrong* value:

- The three `fieldset`/`legend` tests carried Brave's real numbers in their
  own comments — *"(Brave 14.5, one border out)"*, *"(Brave 38.891, again
  one border out)"*, *"(Brave 771)"*, *"(Brave 24.891)"*, *"(Brave 6.891)"*.
  All five now match Brave exactly, and the comments say so.
- `grid-container-border-and-background-match-block-flex-convention`
  asserted the old convention in prose: *"Content is inset by padding only
  … border-width only offsets content when box-sizing: border-box."*
  Re-measured on that exact shape (Brave: 112x22 container, item at (6,6));
  the container is 22 tall here now, not 18, and the item is at (6,6).
- The three input-caret tests moved 2 → 4, which is an `<input>`'s border
  (2) plus its padding (2). The caret, the value text and the placeholder
  were all being painted one border outside the box they belong to.

665 tests / 1455 assertions, 0 failures; lint 0 errors, 23 warnings
(unchanged).

### Round twenty-seven: `transform`, which changes what a box reports without changing where it is

`transform` matched nothing in `src/` but `text-transform`, an unrelated
property. Round twenty-six added six `:transform/*` cases to find out how
big the gap was; all six failed. All six pass now, plus four more added
from the twenty-three further shapes measured in Brave while implementing
it.

**The two properties the cases exist to pin, and what each cost.** A
transform is a **paint-time** operation: the box still occupies its
untransformed space in flow, so a following sibling does not move and the
parent is not resized. That fell out of *where* the code went rather than
from any care taken with it — `apply-element-transform` wraps
`layout-node`'s whole element dispatch and rewrites the `:draw` half of the
result, handing `:box`, the collapsed margins, the escaped floats and the
out-of-flow list back untouched. Wrapping the dispatch rather than each
branch is also why the same nine lines cover a block, a flex or grid
container, a table, a form control and an atomic inline: every one of them
returns the same `{:box :draw}` shape there. The second property — a
**percentage in `translate` resolves against the element's own border
box**, the one place in CSS a percentage looks inward — is the reason
`transform-length` takes the element's own `w`/`h` as its basis where every
other percentage in the file takes the containing block's.

**Where the transform applies: both, or it would be worse than nothing.** A
transform that moved the `:node` box the geometry axis reads while leaving
the background and the text where they were would score six cases and
render worse than no transform at all. `transform-ops` maps the whole
subtree: a rect-shaped op becomes the axis-aligned bounding box of its
transformed corners, a text op is placed at its transformed origin with its
font size scaled by `sqrt(|det|)`. For the `:node` op the bounding box is
not an approximation — `getBoundingClientRect` reports exactly that box,
which is why `rotate(45deg)` on a 100×20 box reports 84.85 square in both
engines. For a background fill under a rotation it is an over-covering
approximation, and for glyphs it is a stated cut: there is no rotated-quad
or rotated-text primitive in this engine or its hosts.

**One shape that had to *not* move.** CSS applies `transform` to
transformable elements, which excludes a non-replaced inline box. Measured:
`<span style="transform: translateX(30px)">` in a sentence *computes*
`matrix(1, 0, 0, 1, 30, 0)` and sits at exactly the x an untransformed span
sits at. `:transform/not-on-a-non-replaced-inline` is the corpus case for
it, and `transformable?` is the predicate — without it, applying the
transform there would have moved a box every browser leaves alone.

**Scope cuts, stated rather than discovered.** `matrix()` is implemented
(six numbers is the canonical form the others reduce to, and Brave reports
the same box for `matrix(2,0,0,2,10,10)` as for `translate(10px,10px)
scale(2)`). The Z-only 3D functions are accepted as their 2D projections,
because without a `perspective` that projection *is* what a browser
reports. `matrix3d`, `perspective`, `rotate3d`, `rotateX`, `rotateY`,
`transform-style` and `backface-visibility` are **not** modelled, and a
list containing any of them — or a length in a unit outside `px`/`%` — is
dropped **whole**: a list is one composed transform, and applying three of
its four functions puts the box confidently in the wrong place, where
reporting the untransformed box says truthfully that it was not modelled.
A transformed element also establishes a **stacking context** in real CSS
and this engine does not model that. It establishes a **containing block**
for absolutely positioned descendants too, and this engine gets that right
— measured, a `left: 5px; top: 5px` child of a static
`transform: translate(20px, 10px)` box lands at (25, 15) in both — but for
an unrelated reason: every block box here anchors its own out-of-flow
children with no positioned-ancestor check at all, so the containing-block
question never reaches a `position` test. The right answer by the wrong
route, named in `apply-element-transform` so that fixing the broader
simplification does not silently take this with it.

Corpus-wide, same harness both sides, on the corpus as it grew 337 → 341:
line structure 322/324 → **326/328**, geometry 1224/1272 → **1241/1283**
(308/337 → **318/341** clean), paint order 8321/8409 → **8424/8509**
(312/337 → **318/341** clean), computed style 15535/17795 →
**15678/17949** (the axis compares no transform property; the delta is the
four new cases' own elements). Ten cases' boxes changed, all ten are the
`:transform/*` group, and **none regressed** — every other case's box list
and line-axis status is byte-identical either side.

| case | before | after |
|---|---|---|
| `:transform/translate-moves-the-box` | 1/2 | **2/2** |
| `:transform/translate-does-not-affect-siblings` | 2/3 | **3/3** |
| `:transform/scale-changes-the-reported-box` | 1/2 | **2/2** |
| `:transform/percentage-translate-is-of-the-box` | 1/2 | **2/2** |
| `:transform/rotate-grows-the-reported-box` | 1/2 | **2/2** |
| `:transform/on-an-inline-block` | 1/2 | **2/2** |
| `:transform/not-on-a-non-replaced-inline` | new | **2/2** |
| `:transform/functions-compose-in-order` | new | **3/3** |
| `:transform/origin-moves-the-fixed-point` | new | **2/2** |
| `:transform/on-a-flex-item` | new | **4/4** |

No existing test changed answer, because nothing in this file had an answer
for `transform` before. Eleven new tests, 654 → 665 tests / 1407 → 1455
assertions, 0 failures; lint unchanged at 0 errors.

### Round twenty-six: the legend that is not in the flow, the button that wrapped its own label

Five things, each measured in a real headless Brave 151 over CDP **before**
the code that produces it was written, and each probed for its *rule*
rather than for the corpus case's numbers.

**`<fieldset>`/`<legend>` — the largest remaining geometry cluster.** The
box constants were already written down in `ua-control-box` by an earlier
round, which deliberately left the case failing rather than half-fixed
because the legend's placement is not a constant. It is now implemented.
A fieldset carries `margin-inline: 2px`, a 2px groove border and
`0.35em 0.75em 0.625em` of padding — **em**, which the cascade resolves
against the element's own font size (14 → 4.9/10.5/8.75, 20 → 7/15/12.5)
instead of pinning one row of numbers — and its content box establishes an
independent formatting context (measured on `border:0;padding:0;margin:0`,
where the inner `<p>`'s margins stay **inside**: 68px tall with the `<p>`
at y=34, both of which collapse out of an ordinary div).

The legend is lifted out of flow into the block-start border band:

```
band     = max(border-block-start-width, legend height + its margin-BOTTOM)
legend y = (band - that margin box) / 2, from the fieldset's border-box top
content  = band + padding-top
```

Three of those clauses are readings, not derivations. The legend's
margin-**top** is ignored outright: `margin-top: 10px` and `margin-top:
40px` both leave the fieldset **83.641** tall, while `margin-bottom: 10px`
makes it **93.641**. The legend is *centred* in the band, which only shows
up when the border is thicker than it (`border-top-width: 40px` puts a 20px
legend at **y=10**). And its margin-*inline* is honoured normally
(`margin: 10px` moves it from x=14.5 to **24.5**).

Which legend gets lifted was probed the same way, and three of the four
answers are surprising:

| shape | Brave |
|---|---|
| `<legend>` after two `<p>`s | still lifted; the `<p>`s start below the band |
| a **second** `<legend>` | ordinary full-width block in the content, (14.5, 24.891, 771, 20) |
| `<div><legend>` | not a legend at all — no band, 85.641 tall |
| `display:none` / `position:absolute` | no band; fieldset is 65.641, as with no legend |
| `float:left` | an ordinary float *inside* the content, with the `<p>`'s text at x=53.5 |

**A `<button>`'s label counts its markup.** `<button>save <b>now</b>
</button>` was measured through `real-text-child`, which sees a control's
*direct* text children only, so the label came out `save ` — 36px of
content where Brave reports 58.5. The consequence was not a 22px box error:
the button **wrapped its own label**, grew to 34px, and painted the first
line above its own border box. `atomic-intrinsic-width`'s form-control
branch had been shadowing the `:else` branch that already knew how to
measure mixed inline content.

**`box-sizing: border-box` on `<button>`, `<select>` and checkbox/radio.**
Read off `getComputedStyle`, not inferred: those three report `border-box`
and `<input>`/`<textarea>`/`<fieldset>`/`<legend>` report `content-box`
(confirmed on `width: 200px`: button **200**, select **200**, input **208**,
textarea **206**; this engine said 216 for the button). It matters twice.
`inset-side` only counted the border for a border-box box *at the time of
this round* (round twenty-eight removed that gate — it counts the border in
both modes now), so a button's own label was painted at its **border** edge
instead of one border plus one
padding in — and the harness attributes a text op to the atomic inline
whose box *contains* it, so a label sitting 3px above its own box leaked
onto the surrounding line. That is the whole of
`:form/button-with-nested-inline` wanting `["tail"]` and getting
`["save now tail"]`.

**A float narrows a DESCENDANT block's line boxes.** Round twenty-two left
this as a named scope cut — "`layout-node` does not carry a float context
down into a child" — on the grounds that only a wrap point was at stake.
It was worse than that. `:page/media-object` reported **one** line for a
three-line page: every line the engine left at x=0 is geometrically inside
the float's own box, so the harness attributed all of them to the float
rather than to the paragraph they belong to. `layout-node` now takes the
band as an optional trailing argument; it is seeded into the child's own
float list and tagged `:intruding?` so the child neither grows to contain
it nor re-escapes it to its parent. Verified against Brave on five shapes
(text in the same block, in a `<p>`, across two `<p>`s, a right float, and
a doubly nested block) — line content and x-offset agree exactly on all
five.

One consequence is worth naming: a float breaks `layout-node`'s
translation-invariance, which every other placement in this file relies on.
The band a child must avoid depends on where the child really ends up, and
that is not known until the child has been laid out once (its collapsed top
margin takes part in the gap). So a block child that has a band **and** a
non-zero shift is laid out twice — once to learn the gap, once with the
band moved to match. Bounded by construction, and it does not compound.

**A line box after a block keeps the pending bottom margin.** CSS wraps
inline content in an *anonymous* block, and the previous sibling's bottom
margin separates it exactly as it separates a real block. This branch
dropped it. It hid because a **lone** inline child never becomes a run
(`inline-runs` needs two), so `<div><p>para</p>bare text</div>` took the
block path and was right at 54px while `<div><p>para</p><span>a</span>
<b>b</b></div>` came here and was 41 against Brave's 55. It was the whole
of `:page/login-form`'s `label y −18.4` / `input y −17.4`: an `<h2>`'s
17.43px bottom margin vanishing before the first `<label><input>` line.

**One floating-point bug, fixed at its source rather than tolerated.** An
intrinsic width is `inset + content`; it is handed back as the box's
*available* width, and `layout-block` then re-derives the content width by
subtracting the **same** inset. In binary floating point that does not
return `content` — measured, `save now` is 57.89096472741182 and the round
trip returns 57.8909647274118, one ulp short — and one ulp short is enough
for the line breaker to decide the label does not fit the box that was
sized for it. The content is now rounded **up** to a whole pixel before the
inset is added, which makes the subtraction exact instead of lucky, and is
the direction a box may only err in: wider than its content, never
narrower.

Corpus-wide, on the 337-case corpus, same harness both sides: line
structure 320/324 → **322/324**, geometry 1215/1272 → **1224/1272**
(307/337 → **308/337** clean), paint order 8308/8409 → **8321/8409**,
computed style 15535/17795 unchanged. Nine cases' boxes changed and **none
regressed**; the six that changed without changing score are all button
widths landing closer to Brave (43.46 → 44 against 44.17, 29.73 → 30
against 30.09, and so on).

| case | before | after |
|---|---|---|
| `:page/login-form` | 1/7 | **7/7** |
| `:form/fieldset-and-legend` | 0/3 | **2/3** |
| `:form/button-with-nested-inline` | 0/3 | **1/3** |

Two closed tests changed answer, both rewritten with the reason rather than
with a new number: the float scope-cut test now asserts the browser's
`x=80` instead of its own `x=0`, and the flex-child test asserts a button's
**200px** border box instead of 216.

Still open, and named rather than left to be rediscovered:

- **`inset-side` omits the border for a content-box element**, in both axes
  — already named by round twenty-five for
  `position/absolute-containing-block-is-the-padding-box`, and it is now
  also the *entire* remaining residual of `:form/fieldset-and-legend`: its
  inner `<p>` is 4px wide of Brave and 2px left of it, and nothing else
  about that case disagrees. It is one change to the box model rather than
  one UA reading, and it moves every bordered box on every page, so it
  belongs with whoever owns width/height resolution — not folded into a
  fieldset constant, which would fit this case and mislead the next.
  **Closed by round twenty-eight**, exactly as described: both cases went
  clean and nothing regressed.
- **`:page/table-of-contents`' `a x +13.70625`** and
  `:table/cell-with-a-list`'s `li w +13.70625` are the same number and the
  same cause round twenty-four already named: this engine paints an
  `<li>`'s marker *inside* the item's line, as `list-style-position:
  inside`, where the browser's `outside` default puts it in the list's own
  40px padding and leaves it out of the item's content entirely. Brave puts
  the `<a>` at x=**40**, the `<li>`'s content edge, and this engine at
  53.70625 — exactly one marker advance in. The fix is that property. It is
  not attempted here partly because a `::marker` produces **no element box
  in the oracle**, so the marker's own placement cannot be verified by this
  harness at all — only the content's can.
- **A `display: flex|grid|table` fieldset gets no band.** The lift lives in
  `layout-block`, and Brave lifts the legend before the fieldset picks a
  formatting context at all (measured: `display: flex` leaves the fieldset
  83.641 tall with the legend still at y=0, and 20 here). Doing it properly
  means moving the lift above `layout-node`'s display-driven branch.
- **Per-side border widths.** `border-top-width: 40px` is not read at all —
  this engine has one uniform `:border-width` — so the band's centring
  clause, which is correct, can only be reached by a uniform border taller
  than the legend.

### Round twenty-five: where the leftover inline space goes, and a height that is a content height

Six gaps, and every number below was read out of a real headless Brave 151
first. Three of them turned out to be the same defect wearing three faces:
**a value that is not a plain px length reached arithmetic that only knows
plain px lengths**, and the arithmetic won.

**`margin: 0 auto` did not centre a block, in either layer.** The cascade
declined to expand the shorthand at all (`auto` is not a length, and
`expand-box-side-shorthand` only expanded lengths), so `margin-left` read
0 where the browser reports 150px — 4 of the 16 values the computed-style
axis still charged to the cascade. Then layout had nothing to read even if
it had been expanded, because node-style's `parse-int` turns `auto` into
the same nil a missing margin gives. Both halves are fixed: `margin`
(and only `margin` — `padding: auto` is not CSS) expands with `auto`
tokens, node-style carries the raw inline margins alongside the coerced
ones, and `layout-children-block` distributes the leftover space by CSS
2.1 §10.3.3's own rule.

Which is not "an auto margin centres the box", and the corpus now says so
in four cases measured before any of it was written:

| case | Brave | this engine, before |
|---|---|---|
| `box/margin-auto-centers-a-block` (100 in 400) | x=150 | x=0 |
| `box/margin-left-auto-pushes-a-block-right` | x=300 | x=0 |
| `box/margin-right-auto-leaves-a-block-left` | x=0 | x=0 (agreed already) |
| `box/margin-auto-with-no-room-does-not-centre` (300 in 200) | x=0, w=300 | agreed already |
| `box/margin-auto-with-a-vertical-length` (`margin: 10px auto`) | x=150 | x=10 |

The last two are the ones worth keeping. The over-constrained case says
Brave does **not** centre a box wider than its container at x=−50 — an
auto margin with nothing to absorb is 0 — and the `10px auto` case caught a
bug the fix introduced on its way in: `margin-side` falls back to the
uniform `:margin` when a per-side longhand is absent, so the *vertical*
10px leaked into the `auto` inline sides and indented the box instead of
centring it.

**`direction: rtl`, exactly as far as it can be verified.** In an rtl
containing block the over-constrained equation resolves `margin-left`
instead of `margin-right`, so a 60px block in a 200px container sits at
**x=140** where this engine had it at 0 (`text/rtl-block-alignment`).
That is block-level alignment, and in this round it was all that was
implemented: no embedding levels, no neutral resolution, no run reversal,
so `text/rtl-with-inline-elements` still failed. `text-align`'s
direction-relative `start`/`end` was left alone deliberately, on the
reasoning that right-aligning that case's line would land its `<b>` within
2px of Brave's x purely because the text either side of it is the same
width, while the words on the line were still in the wrong order.

**Round thirty measured that and found the second half of it wrong** —
Brave does not reorder that line, because every word on it is strong
left-to-right — and implemented both line placement and UAX #9 run
reordering. See round thirty for the measurements and for what is still
not resolved below a word. The block-level rule described here survives
unchanged except that it no longer applies to a bare TEXT child, which was
being shifted right twice once line placement existed.

**A negative margin never wins a `max`.** The cascade had the `-8` all
along; `layout-children-block` collapsed adjacent margins with `max`, and
`(max 0 -8)` is 0. Real CSS collapses to *the largest positive plus the
most negative*, which reduces to `max` when everything is positive.
Measured in Brave, both directions:
`box/negative-margin-pulls-up` is a 32px-tall parent with its second block
at y=12 (engine: 40 and 20), and the new
`box/negative-margin-bottom-pulls-the-next-sibling-up` is the same numbers
from the other side. The control that says the rule is not "negatives are
special" is `box/negative-margin-left-widens-an-auto-width-block`:
`margin-left: -20px` on an auto-width block makes it x=−20, w=220 in
Brave, which this engine already got right.

**A percentage height against an auto-height parent is `auto`.**
`resolve-height` read `"50%"` through `explicit-length`'s leading-digit-run
as 50 **px**, whatever the parent was. `percentage-of` — which already
returns nil for an indefinite basis, exactly the "size me from content" nil
every caller spells — was right there and unused on this path. The basis
now travels down as `:block/containing-height`, set by `layout-block` and
**dropped** by `layout-flex`/`layout-grid`/`layout-table`, whose items'
containing block is not the block that set it and whose own definite
content height this round does not compute; an honest `auto` beats the
grandparent's height.

`box/percentage-height-of-a-fixed-parent` had been passing throughout
because 50% of a 100px parent is 50 either way. Adding
`box/percentage-height-of-a-padded-parent` — asking WHICH height, the
content box or the border box — found a second bug immediately, and it was
not in the percentage:

**A declared `height` is the CONTENT height, and was being used as the
border box.** `resolve-width` had been corrected for this in the inline
axis and the block axis had not. Measured in Brave,
`div{height:100px;padding:10px}` is **120** tall and lays its children out
in **100**; this engine said 100 and 80. Fixing it also moved a case
nobody had connected to it: `table/cell-vertical-align`'s
`<td style="height:60px">` cells are 62 tall in Brave (their UA 1px
padding), and were 60 here.

That fix needed one thing to be kept apart from it, and the corpus caught
it on the same run. Two places in this engine SOLVE a height and inject it
back onto the node to be re-read through the ordinary path — a stretched
flex item's line cross size (`force-cross-size`), and an absolute box's
`top`+`bottom` (`layout-absolute-children`). Those numbers are already
border boxes, and box-sizing must not be applied to them twice; routed
through `style/height` they were, and `page/form-row`'s `<button>` went
from 20 to 24 against Brave's 21. They now travel as a distinct
`:kotoba/used-height`, which is what `force-main-width` had already
concluded for itself in the inline axis (it pins `box-sizing: border-box`
on its own injection for exactly this reason).

**`calc()` with a percentage in it, and a percentage width resolved
twice.** `cssom.core` collapses a *constant* `calc()` during the cascade,
so anything still wearing the `calc(` text in layout contains something
the cascade could not resolve — which in practice means a percentage.
`calc(100% - 40px)` in a 300px block is **260** in Brave and was 300 here
(the value failed to parse outright and the avail-width fallback won);
`calc(50% + 10px)` is **160**. The `%` is resolved at the calc tokenizer's
leaf against the containing block, which leaves `eval-calc-node`'s
same-unit rule for `+`/`-` exactly as it was, and degrades to nil — not to
zero — when there is no definite basis.

`position/absolute-percentage-width` was a different bug with the same
symptom: `measure-child` resolved `width: 50%` against the containing
block (correctly, 100) and handed the result down as the child's
*available width*, where the child's own `resolve-width` resolved the same
percentage a second time and got 50. The used width is now written back
onto the child as a plain length, so the second resolution is a no-op.

Corpus-wide, this round added 10 cases (four of them controls that already
agreed). Measured on the 337-case corpus as it stands after merging round
twenty-six's transform cases, same harness both sides: geometry
1201/1272 → **1215/1272** (296/337 → **307/337** clean), computed style
15534/17801 → **15535/17795** with the cascade-attributed residual 16 →
**9**, paint order 8307/8409 → **8308/8409** (311 → **312** clean cases),
line structure **320/324** unchanged. Twelve cases' boxes changed and
**none regressed**; every one of the twelve went to exact agreement on
every box.

The computed-style axis's compared-value count falls by 6 because
`margin-left`/`margin-right` now hold the string `auto` on the cases that
declare it, which that axis excludes as a non-absolute-length rather than
scoring — the same treatment a directly-declared `margin-left: auto`
already got. The browser reports a *used* value there (150px, or −100px on
the over-constrained case); this engine's cascade holds the *specified*
one. Neither is wrong and they are not comparable, which is what the
exclusion says.

Still open, and named rather than left to be rediscovered: no bidi
reordering (above -- **closed by round thirty**, at word granularity);
`flex/auto-margin-pushes-item-right` still fails,
because a flex line distributes its free space through
`place-main-axis-auto-margins` and not through this round's block-flow
path; `inset-side` still omits the border for a content-box element, in
both axes, which is what `position/absolute-containing-block-is-the-
padding-box` measures (**closed by round twenty-eight**); and a percentage
height inside a flex or grid item resolves to `auto` rather than against
the container.

### Round twenty-one: the corpus grows 200 → 292

The engine went 47% → 89% on geometry while the corpus stayed at 200 cases.
A corpus that stops growing stops finding things, so this round inventoried
what the 200 actually covered — by keyword namespace, case by case — and
added 92 cases only where the answer was "nothing" or "one case".

Nothing here was chosen to pass. **59 of the 92 diverge**, and the
aggregate percentages fell accordingly (table above). That is the honest
number, not a regression: the same `src/` scores 89% on the old 200 and 82%
on the new 292, because the new 92 ask about things the old 200 never did.

Two corpus bugs were found and fixed before any of it counted, both worth
recording because they are hazards of a shared-document harness:

- **An unbalanced case leaks into every case after it.** `<div>` × 16 with
  15 `</div>`s in one new case put the harness's own `<script>` *inside*
  that case's root, and its element counts went off by one. There is now a
  tag-balance check over the whole corpus; it is clean.
- **A float that escapes its container intrudes on the NEXT case.** Three
  new cases deliberately leave a float uncontained. Measured on the shared
  page, one pushed the following case's BFC box 50px right, one moved the
  following case's float from x=10 to x=130, and one turned a one-line
  paragraph into two. Each is now wrapped in an `overflow: hidden` box that
  is *not* the subject, with a comment saying so. Every new case was then
  re-run in isolation and its oracle geometry diffed against its geometry
  on the full page, to prove no case's numbers depend on its neighbours.

#### What the 92 found, by cause

**Percentage lengths are consumed as bare pixels.** `cssom.layout`'s
`parse-int`/`parse-dbl` run `js/parseInt`/`js/parseFloat` over the cascaded
string, so `"50%"` becomes `50` **px** and `calc(…)` fails to parse and
takes the fallback. One cause, nine cases, four subsystems:

| case | browser | engine |
|---|---|---|
| `box/percentage-width` (50% of 400) | 200 | 50 |
| `box/max-width-percentage` (50% of 300) | 150 | 50 |
| `box/percentage-height-of-an-auto-parent` | 20 (auto) | 50 |
| `position/absolute-percentage-offsets` (50%/50% of 200×60) | x=100 y=30 | x=50 y=50 |
| `position/absolute-percentage-width` (50% of 200) | 100 | 50 |
| `position/relative-percentage-offset` (25% of 200) | x=50 | x=25 |
| `table/width-percentage` (50% of 400) | 200 | 50 |
| `box/calc-width` (`calc(100% - 40px)` of 300) | 260 | 300 |

`box/percentage-height-of-a-fixed-parent` **passes**, and is in the corpus
as the control that says why: 50% of a 100px parent is 50, which is exactly
what reading `"50%"` as `50` produces. The pass is a coincidence, and
without the other eight it would read as coverage.

**`auto` and negative values in the box model.**

- `box/margin-auto-centers-a-block` — a 100px block with `margin: 0 auto`
  in 400px sits at x=150 in the browser, x=0 here. This one shows on the
  CASCADE axis too, and is a genuinely new cascade finding: `margin: 0 auto`
  is stored as the raw string `"0 auto"` and never expanded into longhands
  (the expander declines because `auto` is not a length), so a
  `getComputedStyle` consumer reads `margin-left: 0` where the browser
  reports `150px`. 2 of the 11 cascade-attributed values.
- `flex/auto-margin-pushes-item-right` — `margin-left: auto` on a flex item:
  x=265 in the browser, x=28 here.
- `box/negative-margin-pulls-up` — `margin-top: -8px` is stored correctly by
  the cascade (`-8`) and dropped by layout: the browser puts the second
  block at y=12 in a 32px parent, the engine at y=20 in a 40px one.

**Floats: `clear`, containment, and the float's own margins.** The float
round named three unimplemented things and left them without cases. All
three now have one, plus two more. (All five were fixed in round
twenty-two, above; the measurements are kept here because they are what
that round was written against.)

- `float/clear-left`, `float/clear-both` — `clear` is unimplemented.
  `below` lands at y=40 (container 60) in the browser and y=20 (container
  40) here; `after` at y=50 (container 70) against y=0 (container 50).
- `float/parent-does-not-contain-its-float` — a float does not contribute to
  its parent's height. The browser reports the wrapper **200×0**; the
  engine **200×60**. The companion `float/overflow-hidden-contains-its-float`
  agrees with the browser, so the BFC half of the rule is already right and
  only the non-BFC half is missing.
- `float/float-with-margin` — a float's own margins are dropped: the browser
  places it at (10,10) in a 50px-tall container, the engine at (0,0) in a
  30px one.
- `float/floats-that-do-not-fit-stack` — two 120px floats in 200px: the
  browser drops the second to y=20, the engine leaves it beside at x=120.
- `float/float-after-text-in-its-container` — the documented v1 boundary,
  now measured: y=34 in the browser, y=0 here.

**Flexbox past the defaults.** Ten cases, ten separate properties, none of
them implemented:

| case | browser | engine |
|---|---|---|
| `flex/align-self-overrides-align-items` | items at y=40, y=20 | both y=0 |
| `flex/order-reorders-items` | `second` first (x=0) | DOM order |
| `flex/row-reverse` | x = 279, 286, 293 | x = 0, 7, 14 |
| `flex/column-reverse` | three 800×20 rows, bottom-up | three 7×90 boxes side by side (laid out as a ROW) |
| `flex/wrap-reverse` | second line at y=0 | y=0 for line 1, item 2 at x=100 |
| `flex/align-content-space-between` | line 2 at y=100 | y=20 |
| `flex/flex-shorthand-one` (`flex: 1`) | 150 / 150 | 70 / 7 (basis not zeroed) |
| `flex/column-align-items-center` | items centred at x=96.5, x=86 | stretched to 200 at x=0 (cross axis taken as vertical) |
| `flex/align-items-baseline` | line 24 tall, item 2 at y=4 | 20 tall, y=0 |
| `flex/min-content-floor-on-an-item` | 147 / 7 | 113 / 6 (shrinks past min-content) |

`flex/inline-flex-in-a-sentence` is a different shape of the same gap:
`display: inline-flex` is not inline-level, so the paragraph around it goes
from one 20px line to three.

**Grid: one crash, and flow/gap/alignment.**

- **`grid-row: span 2` throws.** `:grid/row-span-two` is the only case in
  292 that makes `cssom.layout` raise rather than answer:
  `nth not supported on this type`. Narrowed by probe: `grid-column:
  span 2` and `grid-row: 1 / 3` both lay out fine, so the defect is
  specifically the `span` form on the ROW axis.
- `grid/explicit-row-placement` — `grid-row: 2` places the item but does not
  leave the column cursor where a browser leaves it: the browser puts the
  next auto item at (0,0), the engine at (70,30).
- `grid/auto-rows` — `grid-auto-rows: 40px` ignored (rows 20px, container 40
  against the browser's 80).
- `grid/auto-flow-column` — `grid-auto-flow: column` ignored; items stack
  vertically at the full container width.
- `grid/separate-row-and-column-gap` — the `row-gap`/`column-gap` LONGHANDS
  are ignored where the `gap` shorthand works: an 8px column gap and a 24px
  row gap are both applied as 0 (container 40 against 64).
- `grid/justify-items-center`, `grid/align-items-center-in-a-tall-row` —
  neither is implemented; items sit at the track origin and stretch.
- `grid/auto-columns-size-to-content` — `grid-template-columns: auto auto`
  produces **zero-width** tracks (browser 154.5 / 245.5), and the container
  goes 80 tall instead of 20.
- `grid/inline-grid-in-a-sentence` — same as inline-flex.

**Absolute positioning: the containing-block rules themselves.** Every
pre-existing `absolute` case gave the box a `position: relative` PARENT, so
none of this was measured:

- `position/absolute-left-and-right-set-width` — `left` and `right` together
  do not resolve a width: 260 in the browser, 63 here.
- `position/absolute-top-and-bottom-set-height` — same on the block axis:
  80 against 20.
- `position/absolute-containing-block-is-the-padding-box` — the containing
  block is taken as the ancestor's CONTENT box. `left: 0; top: 0` lands at
  (5,5) in the browser (just inside the 5px border) and at (20,20) here —
  off by exactly the ancestor's 20px padding.
- `position/absolute-static-position-no-offsets` and
  `position/absolute-with-no-positioned-ancestor` — when the offsets are
  `auto` the box should keep its STATIC position; the engine puts it at
  y=0 (browser y=48 and y=34 respectively).
- `position/absolute-child-of-a-relative-inline` — a relatively positioned
  INLINE is a containing block; here the paragraph goes from one line to
  three.

**Display types with no implementation.**

- `display/table-cells-from-divs`, `display/table-with-anonymous-rows` — the
  CSS table display types on `<div>`s produce **one** draw-op, tagged
  `table`, sized 300×2, and no boxes for the divs at all. Two divergences in
  one: the display types are not laid out, and an element laid out as a
  table reports the wrong tag to the geometry axis.
- `display/contents-is-transparent` — the engine gives the box a real
  300×40 rect and lays its children out as blocks; the browser generates no
  box for it and promotes the children to flex items (7×20 each). One of
  this case's four boxes is not comparable by construction and is noted in
  the case itself.
- `display/flow-root-establishes-a-context` — `flow-root` does not establish
  a formatting context, so the first child's margin collapses through: the
  `<p>` is at y=14 in the browser and y=0 here.

**Table sizing algorithms.**

- `table/layout-fixed` — `table-layout: fixed` ignored: the browser gives
  147/147 columns in a 46px-tall table, the engine 163/9 in a 26px one.
- `table/colgroup-widths` — `<colgroup>`/`<col>` widths are ignored AND
  neither element gets a box: browser 186px table with 120/60 columns,
  engine 24px with 9/9.
- `table/th-is-centered-and-bold` — an explicit `width` on a table reaches
  the table, tbody and tr boxes (all 196) but is not distributed to the
  CELLS, which shrink-wrap to 49.3.
- `table/border-collapse` — not implemented; border-spacing is still
  applied, so the table is 30px tall against the browser's 26 (the widths
  happen to agree at 24).

**`direction: rtl` is parsed and not applied.**
`text/rtl-with-inline-elements` puts the `<b>` at x=227.5 in the browser and
x=42 here; `text/rtl-block-alignment` puts a 60px block at x=140 in the
browser and x=0 here.

**Inherited `line-height`: unitless is not a length.**
`text/unitless-line-height-inherits-the-factor` — `line-height: 1.5` on a
parent with a `font-size: 24px` child gives 36px lines in the browser and 21
here. Its deliberate pair `text/em-line-height-inherits-the-computed-value`
(`1.5em`, same markup otherwise) **passes at 21**, which localises the bug
precisely: the engine resolves the unitless factor against the DECLARING
element's font size and inherits the result, instead of inheriting the
factor.

**BFC detection reads `overflow` but not `overflow-x`/`overflow-y`.**
`overflow/x-hidden-y-scroll` — the round-ten rule that a scroll container
does not collapse margins with its children fires on the shorthand and not
on the longhands: the `<p>` is at y=14 in the browser and y=0 here. The
three shorthand overflow cases all pass.

#### Two things measured rather than assumed

- **`overflow/scroll-container-reserves-a-scrollbar` passes**, which is a
  fact about the ORACLE, not about the engine: this headless Brave uses
  overlay scrollbars, so a 200px `overflow-y: scroll` box has a 200px-wide
  child and there is no gutter to disagree about. The case stays in as a
  control; if the oracle configuration ever changes it will start failing
  and the reason will be recorded here.
- **Documents at scale are fine.** Six of the seven `doc/` cases — 24
  sibling blocks, 12 margin-collapsing paragraphs, 16 levels of nesting, 16
  levels of nesting each with 4px of padding, a 16-item list, 20 wrapping
  inline siblings, a 12-row table — agree with the browser on **every** box.
  Nothing accumulates. The seventh is unscorable for the list-marker reason
  below. This was worth measuring precisely because it was assumed.

#### The composites, and why they are not evidence

Seven cases are deliberately NOT isolating: a dashboard stat row, a login
form, a media object, a card with an absolute badge, a sticky header over a
list, a three-column grid page, a toolbar with an auto margin. They are page
shapes real sites are made of, and each one fails for reasons the isolating
cases above already name — `page/card-with-absolute-badge` is the padding-box
containing block (x off by 11 = padding 12 − border 1),
`page/toolbar-with-auto-margin` is `margin-left: auto` on a flex item (x=99
against 440.6), `page/login-form` is control metrics plus label layout.
A composite failing tells you the page is wrong; it does not tell you which
rule. Read them as regression cover, not as attribution.

### Round twenty: two more from the biggest cluster

`div h −20` (13 boxes) held two unrelated causes:

- **A grid item stretches to its track.** `align-items: stretch` is the
  default, so an item in a `grid-template-rows: 40px` track is 40px tall
  whatever its content needs; this engine left every item at its content
  height. That case went from 1/5 boxes to exact.
- **`overflow-wrap: break-word` was unimplemented.** A long unbroken string
  — a URL, a hash, a compound word — overflowed its column instead of being
  split to fit: a 90px column reported 40px of height where the browser
  needs 60. Now exact on the geometry axis.

The break-word case does, however, now FAIL the line axis, and that is
recorded rather than papered over: the browser measures one Range per WORD,
so a word broken across two lines still reports a single union rect and
clusters as one line, while this engine emits one text op per piece. The
line axis structurally cannot represent an intra-word break. Making the
engine stop breaking the word would "fix" the number and unfix the layout.

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

### The remaining line-structure failures

`page/hero-with-floated-image` was written up here as the float band's
documented v1 boundary. Round twenty-two measured it and it was neither:
the engine's three lines were correct and `engine-lines` was discarding
them, because its inside-a-replaced-box test counted the right edge as
inside. Fixed there.

The one below is deliberate:

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

## Some cases are unscorable on the LINE axis, on purpose

Generated content (`::before`/`::after`, list markers) is not DOM text — a
real browser paints it from the box tree and no `Range` can reach it, while
this engine synthesizes it as real text. Those cases are marked
`:oracle/blind true`, excluded from the LINE score, and printed, rather than
silently counted as failures. Their geometry and computed style are still
scored — only the text-per-line comparison is blind. Their line-level
correctness is covered by unit tests.

12 of the 292 carry the marker as of round twenty-one; the two added that
round are `display/list-item-on-a-div` (a `<div>` made a list item generates
a marker) and `doc/long-list-of-items` (16 `<li>`s). 22 of the 501 carry it
now.

The two most recent are worth naming, because they show the marker being
earned rather than granted: `inline/q-adds-quotation-marks` and
`inline/nested-q-uses-the-second-quote-level` scored on the line axis for
the whole of their life and stopped the day the engine learned to generate
the quotes a `<q>` gets from the UA sheet. Both sides used to read
`he said hello loudly`, for the same wrong reason — neither produced a
quotation mark at all. Now the engine reads `he said “hello” loudly` and
the oracle still cannot: no `Range` reaches a `::before`. Their geometry
went 0/3 boxes to 3/3 in the same change.

## How the oracle is driven (measured, not assumed)

Brave is the intended oracle, and as of 2026-08-04 it is again the one that
actually runs — over **CDP**, not `--dump-dom`.

`--dump-dom` is a *headless-shell* facility, and on **Brave 151.1.93.129**
it is simply dead: zero bytes and no stderr on a 200-case page, on
`<p>hello</p>`, in `--headless=old`, `--headless=new` and plain
`--headless`; `--screenshot` writes no file either. Chrome 150 on the same
machine dumps fine. For a while the harness therefore fell through to
Chrome on every run — the same engine, so the numbers were sound, but the
named comparison target had quietly stopped being measured.

What Brave does still answer is the DevTools protocol. `conformance/
cdp_dump.cljs` launches it with `--headless=new --remote-debugging-port=0`,
reads the port back out of `DevToolsActivePort` (so parallel runs cannot
collide), navigates, polls `Runtime.evaluate` for the measurement block and
kills the browser. `run.cljs` tries CDP first and `--dump-dom` second, and
prints which transport produced the numbers — `oracle: … (cdp)` — because
"the oracle was Brave" and "the oracle was Brave over CDP because its
`--dump-dom` is dead" are different facts about a measurement.

Two things fell out of this that were worth the change on their own:

- **It is ~6x faster** (12s vs 79s for the full corpus), because the
  `--dump-dom` path depends on a headless Chromium exiting, which it never
  does — every run burned its whole SIGKILL timeout.
- **Brave and Chrome were verified byte-identical over CDP** on all 202
  measured blocks, which is the concrete version of "same engine, shields
  do not change layout" that was previously only asserted.

Getting 124 KB out of the child process needed a file, not a pipe: `println`
loses the tail (Node buffers pipe writes; `process.exit` drops the rest), a
single `writeSync` to a non-blocking pipe writes 65536 bytes and reports it,
and looping on that count throws `EAGAIN`. The `--dump-dom` path already
wrote to a file for an unrelated reason (Chromium's children hold stdout
open, so a pipe never reaches EOF); both paths now do.

## The paint-order axis (added 2026-08-04)

The three original axes all measure SIZE and POSITION. None of them can see
**stacking**: which element is on top where two overlap. `z-index`, negative
`z-index`, later-sibling overlap and `pointer-events` were invisible to the
whole harness, so a paint-order regression could not fail anything.

The axis samples a 5x5 grid of interior points per case and asks each side
which element is at that point: `document.elementFromPoint` in the browser,
and in the engine the LAST `:node` draw-op whose HIT REGION contains the
point — its `:hit` rects when it has them, its box otherwise. That
distinction is round thirty-one's; before it the box was the only answer a
`:node` op could give, and three quarters of this axis's residual was the
places where a browser's reported box and its hit region are different
rectangles.
Reading the emitted op vector back is deliberate — that vector *is* the
engine's paint order, so the question asked is "given what this engine told
a host to paint, what would a user click?". Re-deriving stacking inside the
harness would test the harness author's model of the engine instead.

First measurement: **6978/7285 points = 96%**, 238/292 cases fully agreeing.

Two things it found immediately:

- `:stacking/negative-z-index` agrees on **every box** (geometry 12/12) and
  still disagrees at 5 points. That is the whole reason for the axis: an
  engine can get every rectangle right and still paint them in the wrong
  order.
- The rest of the residue lands on cases already failing on geometry —
  `:page/two-column-text`, `:position/absolute-inside-table-cell`,
  `:form/fieldset-and-legend` — where a box in the wrong place naturally
  puts the wrong element under a point. Those are not new bugs and are not
  counted as such.

Both sides wrap each case (the page in `.kotoba-case`, `cascaded-document`
in `<div id="root">`), and neither wrapper is an answer. Scoring one against
the other made every point landing on a case's own top-level text read as a
disagreement — text nodes are not elements, so the browser returns the
containing element, which there is the wrapper. That single asymmetry was
2246 of 2523 disagreements on the first run, i.e. the axis's first number
was 65% and meaningless. Points outside the viewport, or on page chrome, are
counted as skipped rather than scored.

## An axis that measured nothing says so

If any axis compares zero values, the run prints `UNMEASURED:` with the
oracle and transport, appends nothing to the ledger, and exits 3. Measured
2026-08-04: two runs of the same checkout minutes apart printed
`COMPUTED STYLE 0/0 (0%)` and `8501/9982 (85%)` — the oracle had returned no
styles once, and only the implausibility of the number gave it away. A
silent zero is worse than a crash: it enters the ledger as a data point and
reads as a regression forever after.

(ns cssom.layout
  "Box-model + flexbox + grid layout projection from a kotoba virtual DOM
   tree (kotoba.wasm.dom/tree) to renderer draw ops.

   Covers: padding/border/margin box model with min/max-width and
   content-box/border-box sizing; display:flex with flex-direction/
   flex-wrap/justify-content/align-items/gap; display:grid and
   display:inline-grid with grid-template-columns/grid-template-rows
   (fixed px, fr and content-sized `auto` tracks, plus
   `repeat(<n>, <track>)` and `minmax(<px>, <px-or-1fr>)` composing over
   them, plus a constant, percentage-free `calc(...)` track --
   `calc(100px + 20px)`, not `calc(50% - 10px)`, see resolve-constant-calc
   -- a small local mirror of cssom.core's own same-scoped calc() support),
   grid-auto-rows/grid-auto-columns for the implicit tracks, grid-auto-flow:
   column, separate row-gap/column-gap, justify-items/justify-self and
   align-items/align-self on items, and THREE composing item-placement
   mechanisms — per-item `grid-column`/`grid-row` explicit line-based
   placement, per-item `grid-area: <name>` named-area placement resolved
   against the container's own `grid-template-areas` quoted-string template,
   and auto-placement for everything else — see layout-grid for the exact
   subset and its documented limitations; position:absolute (left/top/
   right/bottom anchored against the containing block) with z-index
   stacking; position:relative (top/left/right/bottom as a direct pixel
   shift from the box's own normal position, affecting painting only,
   never layout -- see relative-offset/layout-children-block; currently
   scoped to plain block-flow children only, not yet a flex/grid item,
   an honest, documented scope-cut); 2D `transform` (translate/scale/
   rotate/skew/matrix and the Z-only 3D spellings, with `transform-origin`
   -- likewise paint-only, applied to a whole subtree's draw ops and never
   to a box, on ANY display type; see the `---- CSS transforms ----`
   section and apply-element-transform for the exact subset, for why a
   list containing an unmodelled function is dropped whole, and for the
   two things real CSS does that this does not);
   opacity (multiplicatively inherited); background/
   background-color; borders; overflow+scroll-top/scroll-left clipping;
   form-control value/checked/selected-option-label projection; text input
   caret/selection; grid item explicit placement via `grid-column`/
   `grid-row` and/or `grid-area` composing with auto-placement for
   everything else (see layout-grid/parse-grid-placement/
   parse-grid-template-areas/item-grid-placement/place-grid-items for the
   exact subset); ::before/::after generated `content` (see
   with-generated-content) — cssom.core's cascade already resolves each
   element's ::before/::after style onto its :attrs (:pseudo/before /
   :pseudo/after, e.g. `{:content \"→ \" :color \"red\"}`); this namespace
   reads that and synthesizes a layout-only child that flows through the
   exact same text-wrapping/paint path (layout-text) real text already
   uses, positioned immediately before/after the element's real children.
   general INLINE FLOW (see layout-inline-run): a run of adjacent
   inline-level children — real text nodes, generated content, and
   inline-level elements (inline-level-tags, or any element an author gives
   `display: inline`) — shares line boxes instead of each getting its own
   block row, so `<li>text<b>bold</b></li>` renders on ONE line, wraps as
   one unit at the content width, collapses whitespace across fragment
   boundaries the way real CSS does, keeps each fragment's own
   color/font-size/weight/style/decoration as its own draw-op, and sits
   every fragment on one shared baseline. Replaced elements and form
   controls (`<img>`, `<input>`, `<button>`, `<select>`, `<textarea>`) flow
   in that line too, as ATOMIC inlines: laid out at their own intrinsic
   width (inline-atomic-avail-width) and sitting on the text baseline with
   their own internal baseline (a control's text, an inline-block's last
   line, a scroll container's bottom margin edge — inline-fragments), the
   real CSS `vertical-align: baseline` default.

   The LINE BOX itself is the real CSS one (leading-ascent,
   inline-line-metrics): a strut from the block's own font plus every
   participant, each reaching `floor(ascent + halfLeading)` above the
   shared baseline and the rest of its line-height below it, the box being
   the union. Every inline element's own box is its OWN font's content
   area on that baseline (layout-inline-run's owner-fragments), never the
   line box and never the box of whatever it contains.

   Bounded, documented cuts remain, each at the fn that owns it:
   `<svg>`/`<canvas>`/`<video>`/`<iframe>` are still not inline-level,
   because this engine cannot render them at all (inline-atomic-tags);
   an inline box containing a BLOCK box keeps the
   old block-row path rather than being mis-nested, since real CSS's
   block-in-inline box split is not implemented (inline-flow-candidate?);
   a non-normal `white-space` or a `text-overflow` keeps the old path,
   whether declared on the child or inherited from the container
   (inline-flow-candidate?, inline-runs);
   inline padding/border/margin are not applied and a wrapped inline box
   reports one union `:node` box (inline-owner-ops); `vertical-align`
   `top`/`bottom`/`middle` are not modeled (`super`/`sub` are —
   vertical-align-shift, inline-line-metrics); an EMPTY inline box inside a
   multi-child run still emits no `:node` op (inline-fragment-bearing?);
   and a LONE inline child that is bare TEXT deliberately stays on the
   pre-existing layout-text path, byte for byte, where a lone inline
   ELEMENT does flow (inline-runs). Two older, narrower
   string-level merges predate this and still do exactly what they did —
   they collapse text into ONE styled run, which is a different thing from
   flowing separately styled runs onto one line. The first: a ::before
   immediately followed by (or ::after immediately preceded by) the SAME
   element's own real text-node child with nothing else in between is
   merged into a single text run sharing one line (see with-generated-content's
   own docstring for the exact structural check and its scope boundary) —
   this specific pairing is the canonical CSS-counters numbered-list idiom
   (`li::before { content: counter(x) '. ' }` immediately followed by that
   `<li>`'s own text) and was confirmed, via kotoba-lang/browser's own live
   demo, to previously render as two separate stacked lines instead of one
   (e.g. draw-ops `{:text \"1. \" :y 828}` / `{:text \"...\" :y 860}`) — a
   real, user-visible divergence from real CSS this file's existing tests
   never caught because they only asserted on generated content's
   COMPUTED STRING value, never its on-screen line position. A SECOND,
   independent bounded exception (see merge-adjacent-text-runs, folded into
   with-generated-content as a pre-merge pass): a RUN of two-or-more
   consecutive real text-node DOM children with nothing but each other in
   between — no element boundary — is collapsed into ONE text child before
   layout, the same way real browsers always coalesce sibling DOM Text
   nodes into one contiguous run for rendering/painting purposes (this is
   not itself a CSS inline-layout feature, just how adjacent text nodes
   paint). This is a REAL shape this file's own upstream HTML parser
   produces, not a hypothetical one: kotoba-lang/htmldom's tokenizer
   discards HTML comments as producing no token at all, so
   `<p>Hello <!--c-->world</p>` (or any number of interleaved comments)
   parses to a `<p>` with TWO (or more) adjacent sibling `:text` DOM nodes
   — `\"Hello \"` and `\"world\"` — with nothing else in between, which
   this file's block layout (absent this merge) would render as two
   stacked lines instead of the one contiguous line real CSS renders.
   These two exceptions compose: merge-adjacent-text-runs runs first, so a
   ::before/::after directly bordering what was originally several real
   text-node siblings still sees (and merges with) the WHOLE
   already-combined run, not just its first fragment. Neither merge ever
   reaches across an ELEMENT boundary — `<li>a<b>x</b>b</li>` still yields
   three separate runs, since `a` and `b` are not adjacent children — and
   that is now correct rather than a limitation: the three runs carry
   three different style contexts, and layout-inline-run puts them on one
   shared line as three separate draw-ops, which is exactly what real CSS
   does. Real hosts can still swap
   this for text shaping/WebGPU buffers etc — the draw-ops data boundary
   is unchanged. Word-wrap itself normally decides
   line breaks with a per-character `(long (* 0.6 font-size))`
   monospace-like approximation (see text-lines) since this is a pure,
   host-independent engine with no real glyph shaping and no Canvas API
   guaranteed to exist in every environment it runs in (e.g. plain JVM
   tests) — but a real host that DOES have real text measurement (e.g. a
   browser's `CanvasRenderingContext2D.measureText`) can opt into using it
   for wrap decisions instead, via draw-ops' optional `:measure-text` theme
   key (see draw-ops/layout-text), so wrapping agrees with how that host
   actually paints the already-wrapped lines.

   Implicit `<ul>`/`<ol>` default `<li>` markers (see
   with-implicit-list-markers, in the same section of this file as
   with-generated-content, right below it): real browsers render a bullet
   (\"•\") before every `<li>` whose DIRECT PARENT is a `<ul>`, and an
   auto-incrementing decimal number (\"1.\", \"2.\", ...) before every `<li>`
   whose direct parent is an `<ol>`, from the UA stylesheet, with ZERO
   author CSS required — before this feature, this engine (which had no
   list-style/marker/tag-default concept at all) rendered a bare
   `<ul><li>Apple</li></ul>` as just the literal text \"Apple\", confirmed via
   kotoba-lang/browser's own live demo. Rather than new rendering machinery,
   this reuses the exact same ::before/generated-content pipeline described
   above: an implicit marker is written as if it were the `<li>`'s own
   cascade-resolved `:pseudo/before` attr, sharing the `<li>`'s own line the
   way the explicit-CSS numbered-list idiom already does. Numbering is a purely
   POSITIONAL 1-based count of an `<li>`'s index among its OWN parent's
   direct `<li>` children only — deliberately NOT cssom.core's general
   counter-reset/counter-increment machinery, so it can never collide with
   an author's own independent counter() usage elsewhere on the page — so a
   nested `<ol>`/`<ul>` inside one of those `<li>`s restarts at 1
   independently, with no shared state. An `<li>` (or its direct `<ul>`/
   `<ol>` parent) with `list-style`/`list-style-type: none`, or an `<li>`
   that already has its own explicit ::before `content`, is skipped (see
   with-implicit-list-markers' own docstring for exactly why each case is
   skipped rather than combined/overridden). An `<ol>`'s own `start=` HTML
   attribute is honored (real, common HTML for resuming a numbered list at
   an arbitrary number, including negative/zero per real HTML5 semantics)
   -- see implicit-marker-content. An `<li>`'s own `value=` HTML attribute
   is also honored (real, common HTML for setting that one item's own
   displayed number directly, e.g. after a manual reorder -- every
   following sibling without its OWN `value=` then continues counting
   from THAT number, not from the original position, matching real
   HTML5 semantics exactly). An `<ol>`'s own `reversed` HTML5 boolean
   attribute is also honored (counts DOWN instead of up; with no
   explicit `start=`, defaults `start` to the total count of direct
   <li> children, matching real HTML5/browser semantics).

   `list-style-position` IS honored, in the only way that is measurable
   here. Its default, `outside`, puts the marker in a box of its own beside
   the item rather than in the item's content: the marker takes no inline
   space, so the item's own content starts at the item's content edge and
   a box that shrink-wraps the list is no wider for the marker's sake.
   Measured in Brave, `<ul><li><a>First section</a></li></ul>` puts the
   `<a>` at x=40 and a `<td>` around a two-item list at 63px, where this
   engine had them at 53.7 and 76.7 -- one marker advance out in both. The
   marker itself is painted immediately left of that content edge, and that
   part is NOT verified against a browser by anything: a `::marker` is not
   an element, so the conformance oracle has no box to report for it (see
   with-implicit-list-markers, PLACEMENT). `inside` keeps the marker as the
   first thing on the item's first line, which is measurable and measured
   (list-style-inside?) -- exactly right for an `<ol>`, whose inside marker
   advance in Brave IS the width of the number and its space (21px for
   `1. `, 28 for `10. `, 35 for `100. ` at 14px monospace), and 5.3px short
   for a `<ul>`, whose disc marker box Brave sizes from the font-size alone
   (19px at 14px, 14 at 10px, 37 at 28px, the same for every family and
   every bullet-ish list-style-type) rather than from the glyph.

   Explicitly out of scope: the full `list-style-type` property (circle/
   square/roman/alpha/...), the `list-style` SHORTHAND (only the
   `list-style-type`/`list-style-position` longhands, plus a whole-value
   `list-style: none`, are read), `display: list-item` on anything that is
   not an `<li>` inside a `<ul>`/`<ol>`, and `<menu>`. An `<li>` whose own
   content is a BLOCK (`<li><div>x</div></li>`) still gives its outside
   marker a row of its own rather than putting it beside the block's first
   line, where a browser puts it -- the item comes out one line too tall.

   `<details>`/`<summary>` default disclosure hiding (see
   with-details-visibility, right below with-implicit-list-markers): a
   real `<details>` without an `open` attribute renders ONLY its first
   direct `<summary>` child, hiding every other direct child -- before
   this feature, this engine had no notion of it at all, so a bare
   `<details><summary>...</summary><p>...</p></details>` rendered BOTH
   the summary and the content, permanently, defeating the entire point
   of a real, common, no-JS disclosure/spoiler/FAQ widget. Reuses the
   existing `:style/display \"none\"` mechanism (a hidden element child
   gets it written onto its own attrs; a bare text-node child, which has
   no attrs to write it onto, is dropped from the children vector
   instead -- same visual result). Click-to-toggle interactivity (a real
   `open` attribute flip + `toggle` event on a `<summary>` click) is a
   SEPARATE concern implemented in kotoba-lang/browser's
   document_input.cljc, not here -- this function only ever renders
   whatever `open` state is already given to it.

   Moved out of kotoba-lang/wasm-ui into kotoba-lang/cssom (ADR-2607051140)."
  (:require [clojure.string :as str]))

(def default-theme
  {:font-size 14
   :line-height 20
   :padding 4
   :gap 4
   :fg "#e6ebf5"
   :bg "#121724"
   :button-bg "#1f2738"})

(defn- parse-int
  [x fallback]
  (cond
    (integer? x) x
    (number? x) (long x)
    (string? x) (or #?(:clj (try (Long/parseLong (re-find #"-?\d+" x))
                               (catch Exception _ nil))
                       :cljs (let [n (js/parseInt x 10)]
                               (when-not (js/isNaN n) n)))
                    fallback)
    :else fallback))

(defn- parse-dbl
  [x fallback]
  (cond
    (number? x) (double x)
    (string? x) (or #?(:clj (try (Double/parseDouble (str/trim x))
                               (catch Exception _ nil))
                       :cljs (let [n (js/parseFloat x)]
                               (when-not (js/isNaN n) n)))
                    fallback)
    :else fallback))

(defn- attr [node k] (get-in node [:attrs k]))
(defn- style [node k] (get-in node [:attrs (keyword "style" (name k))]))

(defn- truthy-attr?
  "Real HTML boolean-attribute presence: `true` (htmldom's own parser's
   value for a bare attribute like `checked`), the empty string
   (`checked=\"\"`), or any other non-blank value that isn't literally
   \"false\" -- this last case is what actually recognizes the common
   XHTML-compatible explicit form (`checked=\"checked\"`), which a bare
   `(true? ...)` check never does. Mirrors htmldom.core's own private
   `truthy-attr?` and this repo's own `browser.browser-use/truthy?`
   (fixed earlier this session for the identical class of bug)."
  [value]
  (or (= true value)
      (= "" value)
      (and (string? value)
           (not (str/blank? value))
           (not= "false" (str/lower-case value)))))

(defn- listeners [node]
  (let [ls (:listeners node)]
    (cond
      (map? ls) (keys ls)
      (sequential? ls) ls
      (set? ls) (seq ls)
      :else nil)))

(defn- text-node? [node] (string? node))

(defn- text-lines
  "Word-wraps text into lines that each fit within max-w pixels, using the
   char-w-per-character heuristic already used elsewhere in this file for
   text metrics (no real glyph shaping).

   If the whole string already fits in max-w it is returned completely
   unmodified as the single line -- this keeps today's single-line
   behavior byte-for-byte identical (including any incidental whitespace)
   for every text run that doesn't actually need to wrap.

   When wrapping is needed, the text is split on whitespace runs into
   words and greedily packed: as many words as fit are joined with a
   single space, then a new line starts. A word that alone is wider than
   max-w is placed on its own (overflowing) line rather than being split
   mid-word or dropped -- this file has no glyph-level shaping to make a
   principled break point inside a word, so an overflowing single-word
   line is the same 'let it overflow the box' behavior already used
   elsewhere in the box model (e.g. min/max-width clamps without
   hyphenation).

   This is the DEFAULT text-measurement strategy, used whenever a host
   doesn't supply its own (see layout-text's `measure-text` and
   text-lines-measured below for the injectable alternative) -- kept
   completely unmodified by that feature so every existing caller that
   doesn't opt in keeps this exact monospace-approximation behavior,
   byte-for-byte, forever."
  [char-w max-w text]
  (let [text (str text)]
    (if (<= (* (count text) char-w) (max 0 max-w))
      [text]
      (let [words (remove str/blank? (str/split text #"\s+"))]
        (if (empty? words)
          [text]
          (let [max-chars (max 1 (long (quot max-w (max 1 char-w))))]
            (loop [words words cur nil lines []]
              (if (empty? words)
                (conj lines cur)
                (let [word (first words)
                      more (rest words)]
                  (cond
                    (nil? cur)
                    (recur more word lines)

                    (<= (+ (count cur) 1 (count word)) max-chars)
                    (recur more (str cur " " word) lines)

                    :else
                    (recur words nil (conj lines cur))))))))))))

(defn- break-long-word
  "Splits a word that cannot fit `max-w` into the largest pieces that do --
   real CSS `overflow-wrap: break-word` / `word-break: break-all`, which is
   how a long unbroken string (a URL, a hash, a German compound) is made to
   fit a narrow column instead of overflowing it. Without it this engine
   put the whole word on one overflowing line: measured against the
   browser, a 90px column reported 40px of height where the browser needs
   60."
  [line-w max-w word]
  (loop [remaining word out []]
    (if (or (str/blank? remaining) (<= (line-w remaining) max-w))
      (if (str/blank? remaining) out (conj out remaining))
      (let [n (loop [i (count remaining)]
                (cond
                  (<= i 1) 1
                  (<= (line-w (subs remaining 0 i)) max-w) i
                  :else (recur (dec i))))]
        (recur (subs remaining n) (conj out (subs remaining 0 n)))))))

(defn- text-lines-measured
  "Word-wraps text exactly like text-lines' greedy word-packing algorithm
   (same whitespace splitting, same 'never split/drop a word', same
   'an overflowing single word gets its own line' rules) but consults a
   real width-measurement function `measure` (text -> px width, e.g. a
   real browser's `CanvasRenderingContext2D.measureText(text).width`,
   see layout-text's `measure-text`) instead of assuming every character
   is a fixed char-w px wide. This is what makes wrap decisions agree with
   how a REAL proportional font actually renders -- a 'W'-heavy string
   measures wider, and wraps earlier, than an 'i'-heavy string of the same
   character count, which the char-w approximation can never tell apart."
  [measure max-w text]
  (let [text (str text)]
    (if (<= (measure text) (max 0 max-w))
      [text]
      (let [words (remove str/blank? (str/split text #"\s+"))]
        (if (empty? words)
          [text]
          (loop [words words cur nil lines []]
            (if (empty? words)
              (conj lines cur)
              (let [word (first words)
                    more (rest words)
                    candidate (if cur (str cur " " word) word)]
                (cond
                  (nil? cur)
                  (recur more word lines)

                  (<= (measure candidate) max-w)
                  (recur more candidate lines)

                  :else
                  (recur words nil (conj lines cur)))))))))))

(defn- apply-text-transform
  "Applies `text-transform`'s `uppercase`/`lowercase`/`capitalize` to
   `text`, returning it unmodified for `nil`/`\"none\"`/any other
   unrecognized value. `capitalize` upper-cases the first character of
   each whitespace-delimited word (`\\b\\w` -- a single word character at
   a word boundary), matching real CSS's per-word behavior rather than
   just the string's own first character. Unlike font-weight/font-style/
   text-decoration/text-align (all threaded onto the draw-op as separate
   metadata for a host to interpret at paint time), text-transform
   actually REWRITES the text content itself before word-wrapping --
   real CSS text-transform changes what characters are rendered, not
   just how, so wrapping must see the transformed text (an upper-cased
   word is often wider than its original) rather than wrapping the
   original and transforming after, which could wrap at the wrong point.
   Deliberately scoped to these three keywords -- `full-width`/
   `full-size-kana` (CJK-specific, real but rare CSS values) are not
   implemented."
  [text-transform text]
  (let [text (str text)]
    (case text-transform
      "uppercase" (str/upper-case text)
      "lowercase" (str/lower-case text)
      "capitalize" (str/replace text #"\b\w" str/upper-case)
      text)))

(defn- ellipsize
  "Real CSS `text-overflow: ellipsis`'s own truncation: if `line`'s own
   measured width (via `line-w-fn`, either a real host `:measure-text`
   callback or this file's own `char-w` approximation) already fits
   within `content-w`, `line` is returned unchanged. Otherwise, the
   LONGEST prefix of `line` such that `(prefix + \"…\")` still fits is
   kept -- a simple linear shrink from the full length rather than a
   binary search, since real single-line control/label text this applies
   to is short enough that the difference is not measurable. An
   `content-w` too narrow to fit even a bare `\"…\"` degrades to just the
   ellipsis alone, rather than an empty string or a crash."
  [line content-w line-w-fn]
  (if (<= (line-w-fn line) content-w)
    line
    (loop [n (dec (count line))]
      (if (<= n 0)
        "…"
        (let [candidate (str (subs line 0 n) "…")]
          (if (<= (line-w-fn candidate) content-w)
            candidate
            (recur (dec n))))))))

(defn- layout-text
  "Word-wraps and lays out `text` as one or more :text draw-ops -- exactly
   the algorithm layout-node's real-DOM-text-node branch uses, factored out
   so generated ::before/::after content (see with-generated-content) can
   flow through the identical text-measurement/wrapping/paint path instead
   of a second, forked implementation. `color`/`font-size`/`line-height`/
   `font-weight`/`font-style` are taken as explicit args (rather than read
   off `inherited`/`theme` internally) so a pseudo-element's own resolved
   style can override any of them while still falling back to whatever a
   real text child in the same spot would use. `line-height` is already a
   resolved, absolute pixel number by the time it reaches here -- see
   `resolve-line-height`, which every caller applies to its own
   cascade-resolved `:line-height` (falling back to `(:line-height theme)`
   the same way this arg always effectively did before this fix, so a
   page with no author `line-height` anywhere sees no behavior change).

   `font-weight`/`font-style` are passed through to each resulting :text
   draw-op unchanged (real hosts, e.g. kotoba-lang/dom-gpu's webgl.cljs/
   webgpu.cljs, interpolate them into the real Canvas 2D `font` string) --
   before this, this engine resolved `font-weight: bold`/`font-style:
   italic` correctly in the cascade (real, cascade-computed `:style/
   font-weight`/`:style/font-style` attrs already existed) but silently
   dropped both once layout built the actual :text draw-op, so bold/italic
   CSS had ZERO visual effect no matter what a real author wrote.

   `font-weight`/`font-style` are ALSO passed to the OPTIONAL `:measure-
   text` word-wrap callback (see its own paragraph below) -- extended
   from `(fn [text font-size] width-in-px)` to `(fn [text font-size
   font-weight font-style] width-in-px)`, a real, deliberate BREAKING
   change to this already-established host callback contract (this
   codebase is pre-release, see ADR-2607050700's own precedent for this
   kind of change; the two real implementers, kotoba-lang/dom-gpu's
   webgl.cljs/webgpu.cljs, are updated in the same cycle as this change).
   Before this, a real host's word-wrap MEASUREMENT for bold/italic text
   always used NORMAL-weight/upright metrics even though the PAINT step
   already correctly rendered bold/italic (confirmed via direct REPL
   reproduction: a long bold string wrapped identically to the same
   string in normal weight, despite bold glyphs typically measuring
   ~5-10% wider at the same point size in a real proportional font) --
   a real, if minor and rarely visible, mismatch between where a line
   wraps and how wide its real glyphs actually render. This was flagged
   as a known, deliberately deferred limitation across several earlier
   cycles this session (and as the one remaining open item in
   ADR-2607061100's own retrospective) before being closed here.

   `text-decoration` is the same shape again -- a direct follow-up gap
   found in the same text-styling-property survey that turned up the
   font-weight/font-style bug above: `underline`/`line-through`/`overline`
   already resolve fine in the cascade (this file's style resolution is
   generic, not an allowlist of known property names) but were never read
   here at all, so `text-decoration: underline` also had ZERO visual
   effect. Unlike real CSS (where `text-decoration-line` is NOT an
   inherited property but its drawn line still visually propagates across
   descendant inline boxes regardless of their own value, a distinct
   'propagation' mechanism from inheritance), this engine deliberately
   models it as an ordinary inherited-with-override property -- the exact
   same shape color/font-size/font-weight/font-style already use here. A
   descendant explicitly setting `text-decoration: none` correctly stops
   ITS OWN line (and everything further nested under it), which is a
   simplification of the real spec's more subtle non-overridable
   propagation, but is consistent with how every other text property this
   engine tracks already behaves, and is flagged here rather than silently
   diverging from CSS without a note.

   `text-align` -- unlike font-weight/font-style/text-decoration above,
   this one is a REAL, spec-accurate inherited property in actual CSS too,
   no simplification needed. Each line is offset within `content-w` (the
   same full available content width every line already wraps within,
   NOT this fn's own shrink-to-fit `w` below) by its own individually
   measured width -- `center`/`right` need each line's real width
   separately, since shorter/longer wrapped lines of the same paragraph
   offset by different amounts. Aligning against `content-w` (rather than
   `w`) matters for the common case: an ordinary block element with no
   explicit `:width` already resolves to fill its full available width
   (see resolve-width's `avail` fallback), so `text-align: center` on a
   plain `<div>` centers within that full block width exactly like a real
   browser, not within some auto-shrunk width that would make centering
   invisible. Deliberately scoped to `left`/`center`/`right` -- `justify`
   falls back to `left` (this engine has no per-space stretch-justification
   of its own), a safe degrade rather than a wrong guess, matching this
   codebase's existing convention for other unimplemented keyword values.

   `text-transform` (see apply-text-transform above) rewrites `text`
   itself, BEFORE word-wrapping, so `uppercase`/`lowercase`/`capitalize`
   wrap according to the actual rendered (transformed) characters' width,
   not the original untransformed string's.

   `white-space` -- the last item on this file's text-styling-property
   survey -- is a REAL, spec-accurate inherited property. All five real
   CSS values are now supported: `normal` (the pre-existing default),
   `nowrap`, `pre`, `pre-wrap`, and `pre-line`, the last three added
   across three separate, tightly-scoped cycles (originally `pre`/
   `nowrap` only, `pre-wrap` the very next cycle, `pre-line` the cycle
   after that). `nowrap` skips word-wrapping
   entirely: the WHOLE text becomes a single line, left to overflow its
   box exactly like this file's existing 'let an oversized single word
   overflow rather than hyphenate' convention. `pre` splits `text` on
   LITERAL `\\n` characters (not the normal `#\"\\s+\"` word-wrap
   splitting) and does NOT re-wrap each resulting segment, preserving
   whatever verbatim whitespace/structure is already inside each one --
   this is a real, confirmed-via-REPL paint bug fix: BEFORE this, a text
   node containing an embedded `\\n` (kotoba-lang/htmldom already
   preserves these verbatim for real `<pre>`/raw-text-tag content, see
   htmldom.core/preserve-whitespace-context?) either silently vanished
   into a single :text draw-op whose string still had the `\\n` baked in
   (a raw newline character inside one Canvas 2D `fillText` call does
   NOT create a visual line break -- browsers render it as an invisible
   or tofu-like glyph, never a break), or got its embedded newlines
   silently destroyed by ordinary word-wrap collapsing (`text-lines`'s
   `#\"\\s+\"` split treats a newline as just another whitespace run to
   collapse away) -- either way, a real `<pre>` block's line structure
   was never actually visible in a real rendered page. KNOWN,
   deliberately scoped limitation: `white-space: pre` on an ORDINARY
   element (not `<pre>`/a raw-text tag) whose source HTML already had
   its embedded newlines collapsed to single spaces by
   kotoba-lang/htmldom's own HTML-structural (not CSS-driven) parse-time
   whitespace handling cannot recover those lost newlines here -- by the
   time this file ever sees the text, they are already gone. A real
   browser defers ALL whitespace collapsing to layout time (CSS-driven,
   tag-independent), which this pipeline does not; fixing that
   architectural gap would mean changing htmldom's parse-time behavior
   itself, out of scope for this cycle. `<pre>`'s own UA-stylesheet
   default (`white-space: pre` with no author CSS at all, matching every
   real browser's default stylesheet) is wired in node-style below, so a
   bare, unstyled `<pre>` renders its line structure correctly out of
   the box without requiring an author to write explicit CSS for it.

   `pre-wrap` combines `pre`'s literal-`\\n`-splitting with `normal`'s
   own existing per-segment word-wrap: each `\\n`-delimited segment is
   independently re-wrapped via `text-lines`/`text-lines-measured` if it
   doesn't fit `content-w` -- unlike a bare `pre`, an overly long
   `pre-wrap` line DOES get broken at a word boundary rather than
   overflowing its box. KNOWN, deliberately accepted simplification: real
   CSS `pre-wrap` preserves EVERY whitespace character verbatim, even
   inside a segment that needs wrapping, but `text-lines`/`text-lines-
   measured`'s own word-packing collapses runs of inter-word whitespace
   to a single space once a segment is long enough to actually need
   re-wrapping (see text-lines' own docstring: the ORIGINAL string is
   preserved byte-for-byte ONLY when it already fits on one line without
   wrapping at all). This means `pre-wrap`'s hard line breaks and any
   segment that already fits on one line are both fully verbatim, and
   ONLY a segment that genuinely needs word-wrapping loses its exact
   original inter-word spacing (collapsing to single spaces, matching
   `normal`'s own long-standing behavior) -- a real, narrow divergence
   from the CSS spec's own more exacting semantics, accepted rather than
   rewriting the established word-wrap algorithm itself for this cycle.

   `pre-line` is the third, final combination: preserves hard `\\n`
   breaks like `pre`/`pre-wrap`, but ALWAYS collapses each segment's own
   internal whitespace runs to a single space first (`normal`'s own
   behavior), THEN re-wraps if the collapsed segment doesn't fit --
   unlike `pre-wrap`, `pre-line` never preserves multiple internal
   spaces verbatim even when a segment already fits on one line, since
   collapsing happens unconditionally before the fits-on-one-line check
   ever runs. This is the one white-space value that is fully spec-
   accurate with no accepted simplification at all -- `pre-line`'s own
   real CSS semantics never promise verbatim internal spacing to begin
   with, so there is no divergence to document here.

   Text measurement is pluggable via an OPTIONAL `:measure-text` key on
   `theme` -- a `(fn [text font-size font-weight font-style font-family]
   width-in-px)` -- see draw-ops' docstring for the full rationale (this
   file is a pure, host-independent layout engine with no real glyph
   shaping of its own; a real host that DOES have one, e.g. a browser's
   real Canvas 2D `measureText`, can supply it here so word-wrap
   decisions agree with how the text will actually be painted, INCLUDING
   its real bold/italic/font-family metrics). When `theme` has no
   `:measure-text` (the default -- absent from default-theme, and absent
   from every existing caller), this resolves to the EXACT SAME
   char-w-approximation code path (text-lines/char-w below) this file
   has always used, so today's word-wrap behavior is completely
   unaffected unless a host opts in.

   `font-family` -- like font-weight/font-style above, this is a REAL,
   cascade-computed, real-CSS-inheritable property passed through onto
   the resulting `:text` draw-op unchanged (a real host, e.g.
   kotoba-lang/dom-gpu's webgl.cljs/webgpu.cljs, interpolates it into the
   real Canvas 2D `font` string) -- before this, an author's own
   `font-family` had ZERO visual effect no matter what a real page
   declared, every host hardcoding the same fixed system-font fallback
   regardless of any real author CSS, confirmed via direct REPL
   reproduction: `:style/font-family` existed on the resolved node but
   layout's own `:text` draw-op never carried it at all -- the exact
   same bug shape already fixed for font-weight/font-style/
   text-decoration/line-height.

   `text-shadow` (a single `{:x :y :blur :color}` map arg -- consolidated
   from 4 separate positional args to keep this fn's own arity under
   Clojure's hard 20-positional-parameter limit, hit for real once
   `text-overflow` below pushed the previous flat-arg signature to 21)
   -- real CSS's own `text-shadow` shorthand is expanded into four
   longhand-shaped attrs at cascade-parse time (see
   `cssom.core/expand-text-shadow-shorthand`) and bundled into this map
   by each `layout-node` call site below. `text-shadow` was previously
   read NOWHERE at all, stored verbatim as a single unrecognized
   `:style/text-shadow` string, confirmed via direct REPL reproduction
   that no shadow :text draw-op was ever emitted no matter what a real
   page declared. When `:color` is present and not the literal string
   `\"none\"` (the real explicit 'no shadow' keyword, and this fn's own
   sentinel for 'an ancestor's shadow was explicitly cancelled here',
   since `text-shadow` genuinely inherits in real CSS), an EXTRA,
   shadow-colored `:text` draw-op is emitted for each line, offset by
   `:x`/`:y`, immediately BEFORE that line's own real-color op so paint
   order puts the real glyphs on top. `:blur` is parsed and threaded but
   NOT rendered -- an honest, documented scope-cut (no blur/glow
   rendering primitive exists in this engine or its real hosts), the
   same class of simplification as border-radius/box-shadow spread being
   left unimplemented elsewhere. The shadow op deliberately does NOT
   carry `text-decoration` -- underline/strikethrough is this file's own
   separate draw-op concern, not duplicated here.

   `text-overflow: ellipsis` -- previously read NOWHERE at all, so the
   classic `white-space: nowrap; overflow: hidden; text-overflow:
   ellipsis` fixed-width label/menu-item/table-cell idiom just silently
   overflowed (or, since the real `overflow: hidden` scissor-rect fix,
   got hard-clipped mid-glyph with no `…` at all) no matter what a real
   page declared, confirmed via direct REPL reproduction. Only takes
   effect for the already-existing `nowrap` branch below (a wrapped,
   multi-line paragraph has no single 'the line' to truncate, matching
   real CSS's own requirement that `text-overflow` only ever act on a
   NON-wrapping block) -- when the raw, untruncated line's own measured
   width exceeds `content-w`, the LONGEST prefix that (prefix + `…`)
   still fits is kept, reusing the exact same pluggable `measure`/
   `char-w` width function every other word-wrap decision in this file
   already uses, so a host with a real `:measure-text` callback gets
   pixel-accurate truncation, not just a character-count guess. Honest,
   documented scope-cut: real CSS also requires the container's own
   `overflow` to be non-`visible` for `text-overflow` to take effect at
   all -- this engine applies ellipsis whenever `nowrap` + `ellipsis` are
   BOTH declared, regardless of `overflow`'s own value, since the
   overwhelming real-world pattern always declares all three together
   and this simplification never produces an incorrect-looking result on
   its own. `text-overflow` is threaded through `inherited` the exact
   same way `white-space` already is, even though real CSS's own
   `text-overflow` is NOT an inherited property -- a pragmatic
   architectural simplification (this file has no general inline flow
   anywhere, see e.g. layout-node's own text-node branch, so a bare
   text-node child has no other route to learn its containing block's
   own truncation intent) rather than a spec-accuracy claim."
  [theme x y avail-width opacity color font-size line-height font-weight font-style font-family
   text-shadow
   text-decoration text-align text-transform white-space text-overflow overflow-wrap text]
  (let [line-height (or line-height (:line-height theme))
        padding (:padding theme)
        measure-text (:measure-text theme)
        char-w (long (* 0.6 font-size))
        content-w (max 0 (- avail-width (* 2 padding)))
        ;; `white-space: normal`/`nowrap` collapse EVERY run of whitespace,
        ;; newlines included, into a single space. The parser deliberately
        ;; keeps newlines (it cannot see CSS, and `pre-line`/`pre-wrap` need
        ;; them), so the collapse happens here, per the property's declared
        ;; value -- which is what makes those two modes implementable at all.
        text (if (contains? #{nil "normal" "nowrap"} white-space)
               (str/replace (str text) #"\s+" " ")
               text)
        text (apply-text-transform text-transform text)
        measure #(measure-text % font-size font-weight font-style font-family)
        line-w #(if measure-text (measure %) (* (count %) char-w))
        break? (contains? #{"break-word" "anywhere" "break-all"} overflow-wrap)
        ;; `overflow-wrap: break-word` splits a word that cannot fit rather
        ;; than letting it overflow -- applied AFTER the ordinary greedy
        ;; word packing, on whichever resulting line is still too wide.
        break-lines (fn [ls]
                      (if break?
                        (vec (mapcat #(if (<= (line-w %) content-w)
                                        [%]
                                        (break-long-word line-w content-w %))
                                     ls))
                        ls))
        lines (cond
                (= "pre" white-space) (str/split (str text) #"\n" -1)
                (= "nowrap" white-space)
                (let [line (str text)]
                  [(if (= "ellipsis" text-overflow) (ellipsize line content-w line-w) line)])
                (= "pre-wrap" white-space)
                (mapcat #(if measure-text
                           (text-lines-measured measure content-w %)
                           (text-lines char-w content-w %))
                        (str/split (str text) #"\n" -1))
                (= "pre-line" white-space)
                (mapcat #(let [collapsed (str/replace % #"\s+" " ")]
                           (if measure-text
                             (text-lines-measured measure content-w collapsed)
                             (text-lines char-w content-w collapsed)))
                        (str/split (str text) #"\n" -1))
                measure-text (break-lines (text-lines-measured measure content-w text))
                :else (break-lines (text-lines char-w content-w text)))
        max-line-w (if measure-text
                     (apply max 0 (map measure lines))
                     (apply max 0 (map #(* (count %) char-w) lines)))
        w (min avail-width (+ max-line-w (* 2 padding)))
        h (+ (* (count lines) line-height) (* 2 padding))
        align-offset (fn [line]
                       (case text-align
                         "center" (/ (max 0 (- content-w (line-w line))) 2)
                         "right" (max 0 (- content-w (line-w line)))
                         0))]
    {:box {:x x :y y :w w :h h}
     :draw (vec (mapcat
                 (fn [i line]
                   (let [line-x (+ x padding (align-offset line))
                         line-y (+ y padding (* i line-height))
                         base (cond-> {:text line :font-size font-size :opacity opacity}
                                font-weight (assoc :font-weight font-weight)
                                font-style (assoc :font-style font-style)
                                font-family (assoc :font-family font-family))
                         shadow-op (when (and (:color text-shadow) (not= "none" (:color text-shadow)))
                                     (assoc base :draw/op :text
                                            :x (+ line-x (or (:x text-shadow) 0))
                                            :y (+ line-y (or (:y text-shadow) 0))
                                            :color (:color text-shadow)))
                         main-op (cond-> (assoc base :draw/op :text :x line-x :y line-y :color color)
                                   text-decoration (assoc :text-decoration text-decoration))]
                     (if shadow-op [shadow-op main-op] [main-op])))
                 (range) lines))}))

;; ---- per-node computed style bag ----

(defn- presentational-size
  "HTML's `width`/`height` ATTRIBUTES on `<img>` are presentational hints
   that a real UA stylesheet maps onto the CSS `width`/`height` properties,
   which is why `<img width=\"10\" height=\"10\">` has a real 10px box in
   every browser with no CSS at all. This engine had no such mapping, so
   such an image resolved through the ordinary block path to the FULL
   available width -- visible the moment images became inline-level, where
   a full-width image forced a line break after every one of them.

   Restricted to `<img>` on purpose: the other elements that historically
   honour these attributes (`<canvas>`/`<embed>`/`<iframe>`/`<video>`/
   `<td>`) have no rendering in this engine at all, so mapping the
   attribute for them would size a box that never paints."
  [node k]
  (when (= :img (:tag node))
    (let [v (get-in node [:attrs k])]
      (when (and v (re-matches #"\d+" (str v)))
        (parse-int v nil)))))

(def ^:private ua-font-weight
  "The UA stylesheet's own `font-weight: bold` set. This engine has no user-
   agent stylesheet at all, which the conformance harness's geometry axis
   made impossible to ignore: `<b>`, `<strong>`, `<th>` and every heading
   rendered in NORMAL weight, because nothing anywhere said otherwise.
   Authors do not write `b { font-weight: bold }` -- the UA does."
  {:b "bold" :strong "bold" :th "bold"
   :h1 "bold" :h2 "bold" :h3 "bold" :h4 "bold" :h5 "bold" :h6 "bold"})

(def ^:private ua-margin-scale
  "Vertical margins from the HTML5 UA stylesheet, as multiples of the
   element's OWN font size -- `p { margin: 1em 0 }`, `h1 { margin: .67em 0 }`
   and so on. These are the rules that make a page look like a document
   rather than a wall of text, and this engine had none of them: the
   conformance harness's geometry axis reported `p` 40/54 for exactly this
   reason.

   They are VERTICAL only, which is why they had to wait for the per-side
   box model: applied as this engine's old uniform margin they would have
   indented every paragraph sideways as well.

   `menu`/`dir` are the legacy list containers: `list-container-tags`
   already treats them as lists for the nested-list cancellation, and
   Brave gives them the same `margin: 1em 0` as `<ul>` (measured 2026-08-05
   on a bare `<menu><li>a</li></menu>`: `margin-block: 16px`,
   `padding-left: 40px`).

   `hr`'s is half an em, and the 1px border that is the rest of an
   `<hr>`'s box lives in ua-em-box -- see there for why.

   NOT here, and for a reason worth stating rather than leaving as a
   silent omission (measured in Brave 151 the same day):

   - `pre`'s margin IS 1em and is in this table, but its em is the wrong
     size: a browser also gives `pre` `font-family: monospace` and, with
     it, the monospace default size of 13px, so the real margin is 13 and
     this engine computes 16. Fixing the margin without the font would
     make the number right for a reason that is not true."
  {:p 1.0 :blockquote 1.0 :ul 1.0 :ol 1.0 :menu 1.0 :dir 1.0
   :dl 1.0 :pre 1.0 :figure 1.0 :hr 0.5
   :h1 0.67 :h2 0.83 :h3 1.0 :h4 1.33 :h5 1.67 :h6 2.33})

(def ^:private list-container-tags
  "The elements a browser's UA stylesheet treats as a list container -- the
   left half of its `:is(ul, ol) ul` nested-list rules. `<menu>`/`<dir>`
   are in Chrome's own rule and cost nothing to honour here."
  #{:ul :ol :menu :dir})

(def ^:private ua-box-sides
  "Horizontal UA-stylesheet box values -- the list indent every browser
   applies (`ul, ol { padding-left: 40px }`) and a blockquote's own side
   margins. Horizontal-only, for the same reason ua-margin-scale is
   vertical-only.

   `figure` is the same `margin: 1em 40px` rule as `blockquote`, and only
   the 1em half of it was here: the vertical margin was in
   ua-margin-scale from the start while the 40px indent was not, so a
   `<figure>` sat flush against its article's content edge and was 80px
   too wide. Measured in Brave 151, 2026-08-05, on
   `:page/article-with-figure`'s own 300px article -- the browser puts the
   figure at x=40 with w=220 and this engine had x=0, w=300, which the
   figure's `<img>` and `<figcaption>` then inherited box for box (the
   harness's whole `figure`/`figcaption`/`img` residual, six numbers from
   one missing declaration).

   `menu`/`dir` carry `<ul>`'s 40px indent for the same reason they carry
   its vertical margin -- see ua-margin-scale."
  {:ul {:padding-left 40} :ol {:padding-left 40}
   :menu {:padding-left 40} :dir {:padding-left 40}
   :blockquote {:margin-left 40 :margin-right 40}
   :figure {:margin-left 40 :margin-right 40}
   :dd {:margin-left 40}})

(def ^:private form-control-tags #{:input :button :select :textarea})

(def ^:private ua-control-font
  "Form controls do NOT inherit the page font. Every browser's UA
   stylesheet gives them their own -- measured directly in Chrome on this
   platform, an `<input>` inside a `font-family: monospace; font-size: 14px`
   container computes to `Arial 13.3333px` regardless -- which is why this
   engine's controls came out ~7px narrower than the browser's however
   carefully their intrinsic width was computed from the INHERITED font.

   The family is named here for the same reason a UA stylesheet names it:
   it is the platform default, not a guess, and a host that measures text
   (see draw-ops' `:measure-text`) needs a family it can actually measure.

   The size is the browser's 13.3333 TRUNCATED to 13, and that 0.33px is
   load-bearing in a way worth writing down, because it is the only reason
   any control box still disagrees with the browser. Measured 2026-08-04:

   - Chrome sizes a text `<input>` at `7 * size + 5` px of content in this
     face -- exactly 7 per character across size=1/2/5/10/20/40 (12, 19,
     40, 75, 145, 285), i.e. the font's OS/2 average advance plus one
     `maxCharWidth - avgCharWidth` slack. This engine has no average-advance
     hook: `:measure-text` measures a STRING, and the closest proxy it can
     ask for is the `0` glyph, which is 7.4219 at 13.3333. Charged at 13
     that proxy becomes 7.2364, and `20 * 7.2364 + 8` is 152.7 against the
     browser's 153 -- the two errors cancel almost exactly at the default
     size=20, and diverge at other sizes (size=40 gives 297.5 against 293).
   - So raising the size to the true 13.3333 WITHOUT an average-advance
     hook makes inputs worse (156.3 against 153), while leaving it at 13
     leaves long control text ~2.5% narrow -- which is why
     `:form/select-with-long-option` still reports a 24-character option
     label as 156px against the browser's 160.

   Fixing that honestly needs the font's average advance, i.e. a new host
   hook alongside `:measure-text`/`:font-metrics`, and a re-derivation of
   the `size`-based input width against it. Deliberately NOT faked here by
   scaling the `0` advance by a fudge factor: that would fit these two
   measurements and mislead the next one."
  {:family "Arial" :size 13})

(def ^:private ua-control-box
  "UA padding and border for form controls, read straight off
   `getComputedStyle` in Brave rather than reverse-engineered from a total:
   an `<input>` is `padding: 1px 2px; border: 2px`, a `<button>`
   `padding: 1px 6px; border: 2px`, a `<select>` `border: 1px` (plus 1px of
   Chrome's own internal block padding, see below), a `<textarea>`
   `padding: 2px; border: 1px`. Without them a control's box was its
   content width exactly, where a browser reports 8px more.

   `:line-height :normal` on every one of them is the other half of the
   same reading: a control's UA `font:` shorthand RESETS `line-height` to
   `normal`, so the page's own line-height never reaches it. Measured, all
   five report `line-height: normal` even inside a `line-height: 20px`
   block. That matters twice over -- it sets the control's CONTENT height
   (one font content area per row, not one page line box) and it removes
   the half-leading from the control's internal baseline, which is what
   put every `<input>`, `<button>` and the `<label>` around them ~3px
   wrong on the line.

   The decomposition, not just the total, is what a line box needs: an
   input measures 21 either way, but its own text baseline sits at
   `border + padding-top + ascent` -- 15 with the measured `1px` block
   padding and 16 with the uniform `2px` this used to carry, and the whole
   line box is built on that number. The earlier note here that a button
   is `13px content + 2 + 4 border = 21` did not add up (that is 19, which
   is what this engine produced): the content is the FONT's 15px area, not
   its 13px size.

   `:box-sizing \"border-box\"` on `<button>` and `<select>` is the same
   kind of reading, and it is NOT decoration. Measured in Brave (2026-08-04,
   `getComputedStyle`), a `<button>`, a `<select>` and a checkbox/radio
   `<input>` all report `border-box`, while a text `<input>`, a
   `<textarea>`, a `<fieldset>` and a `<legend>` report `content-box`.
   It matters because `inset-side` -- the whole engine's answer to \"where
   does this box's CONTENT start\" -- only counts the border for a
   border-box box. Without it a button's own label was painted at its
   BORDER edge instead of one border plus one padding in: its text sat 3px
   high, and the em box the engine reports a text op in poked out ABOVE the
   button's own border box. That is not a rounding error, it is a label
   that has escaped the control it belongs to -- which is exactly how
   `:form/button-with-nested-inline` (a button on a line, wanting
   `[\"tail\"]`) came back as `[\"save now tail\"]`: the harness attributes a
   text op to the atomic inline whose box CONTAINS it, and the button's did
   not contain its own.

   The engine-wide version of that bug -- `inset-side` leaving the border
   out for a plain `content-box` element, so a `border:2px;padding:10px`
   div put its `<p>` at y=24 where Brave says 26 and gave it 4px too much
   content width -- was named here as NOT fixed by this reading, because it
   is one change to the box model rather than one UA reading and it moves
   every bordered box on every page. It is fixed now (2026-08-05, see
   inset-side). A fieldset's inner `<p>` was 4px wide of Brave for exactly
   that reason and no other, and `:form/fieldset-and-legend` is clean.

   `<fieldset>` and `<legend>` are NOT in this map -- their box is em-based
   rather than constant, so it lives in `ua-em-box` next door, and the
   legend's placement is a layout rule (`fieldset-legend`), not a box."
  ;; An input's padding is NOT uniform either: 2px inline, 1px block. The
  ;; uniform `:padding 2` is kept as the fallback the horizontal axis (and
  ;; `content-inset`, i.e. where the control's own text and caret are
  ;; painted) already used; the per-side block values are what the vertical
  ;; axis reads through `inset-side`.
  {:input {:padding 2 :padding-top 1 :padding-bottom 1 :border 2
           :line-height :normal}
   :textarea {:padding 2 :border 1 :line-height :normal}
   ;; a button's padding is NOT uniform: 6px each side, 1px top and bottom.
   ;; Measured in Brave (`padding: 1px 6px`, `border: 2px`, so
   ;; h = 15 + 2 + 4 = 21), and only expressible at all since the box model
   ;; gained per-side values.
   :button {:padding 1 :padding-left 6 :padding-right 6 :border 2
            :line-height :normal :box-sizing "border-box"}
   ;; a <select>'s 1px block padding is Chrome's own internal button
   ;; padding: it reports `padding: 0px` in getComputedStyle yet a select
   ;; measures 4px taller than its font's content area at EVERY size
   ;; (measured: 10px->15, 12px->18, 13.3333px->19, 16px->21, 24px->31,
   ;; against font ascent+descent of 11/14/15/17/27 -- a constant +4, i.e.
   ;; 1px of padding and 1px of border on each side). Inline padding stays
   ;; 0; the horizontal slack is the dropdown arrow, see
   ;; select-arrow-width.
   :select {:padding 0 :padding-top 1 :padding-bottom 1 :border 1
            :line-height :normal :box-sizing "border-box"}})

(defn- select-multiple?
  "A `<select multiple>` is a completely different box from a closed
   dropdown: an open, scrollable LIST of option rows with no dropdown
   arrow.

   Keyed on `multiple` alone. Real HTML also opens the list for a
   single-select with `size` > 1, which is NOT handled here -- an
   honest, documented scope-cut: `multiple` is the form that appears in
   the conformance corpus and in real markup, and adding the `size` case
   without measuring how a browser sizes a one-choice open list would be
   guessing at a second set of constants."
  [node]
  (and (= :select (:tag node)) (truthy-attr? (get-in node [:attrs :multiple]))))

(defn- select-rows
  "How many option ROWS an open `<select multiple>` reserves: its `size`
   attribute, defaulting to HTML's own 4. Measured in Chrome, the reserved
   height is `size` rows REGARDLESS of how many options exist -- a
   `size=\"5\"` select holding one option is still 5 rows (87px) tall, and a
   `multiple` select with three options and no `size` is 4 rows (70px)."
  [node]
  (max 1 (parse-int (get-in node [:attrs :size]) 4)))

(defn- select-option-labels
  "Each `<option>`'s own text, in document order. Read straight off the
   option's text children rather than through `option-label`, which answers
   a different question (the label for one selected VALUE)."
  [node]
  (->> (:children node)
       (filter #(and (map? %) (= :option (:tag %))))
       (mapv #(->> (:children %) (filter string?) (str/join "")))))

(def ^:private select-option-side-padding
  "An `<option>`'s UA inline padding, 2px per side. Measured: a listbox row
   holding `MM` is exactly 4px wider than that text at every font size
   tried (10/13.3333/16/24px), and Chrome reports `padding: 0px 2px 1px`."
  2)

(defn- select-option-height
  "One `<option>` row's height in an open listbox, for a control font of
   `font-size`. Measured in Chrome at four sizes with `size=\"3\"`: 10px ->
   13, 13.3333px -> 17, 16px -> 20.1875, 24px -> 29.7969. Every one of
   those is `1.2 * font-size + 1` to within 0.02px (12+1, 16+1, 19.2+1,
   28.8+1) -- 1.2em of row plus the option's own 1px bottom padding.

   Expressed as a ratio rather than through `font-metrics` on purpose: the
   font's real ascent+descent (11/15/17/27 at those sizes) does NOT
   reproduce the measurements, so this row height is Chrome's own
   normal-line-height rule for the listbox rather than the face's content
   area, and pretending otherwise would fit the numbers by accident."
  [font-size]
  (+ (* 1.2 font-size) 1))

(def ^:private select-arrow-width
  "The fixed horizontal slack a closed `<select>` reserves for its dropdown
   arrow, INSIDE its borders. Measured in Chrome: an EMPTY select is 22px
   wide at every font size tried (10/13.3333/16/24px), and each option
   label then adds exactly `ceil` of its own rendered text width -- 22+33
   for `alpha` (32.63), 22+138 for `a very long option label` (137.11),
   22+112 for `MMMMMMMMMM` (111.05). 22 minus the two 1px borders is this
   20. Font-size-independent, which is why it is a constant and not a
   multiple of the em: it is a fixed-size platform widget, not text."
  20)

(defn- ua-control-box-for
  "The UA box for one control, by tag AND -- for `<input>` -- by type: a
   checkbox or radio is a bare 13x13 square with no padding and no border
   at all, where a text input has 2px of each. Measured in Chrome."
  [node]
  (let [tag (:tag node)]
    (cond
      (and (= :input tag)
           (contains? #{"checkbox" "radio"}
                      (str/lower-case (str (or (get-in node [:attrs :type]) "text")))))
      ;; ...and its own margins: Chrome's UA sheet gives a checkbox/radio
      ;; `margin: 3px 3px 3px 4px`, which is the gap a reader sees between
      ;; the box and the label beside it.
      ;; `:box 13` -- a checkbox is a fixed-size PLATFORM WIDGET, not a box
      ;; sized from a font: measured in Brave it is 13x13 at every font
      ;; size. The width path already spells that 13 (see
      ;; atomic-intrinsic-width); naming it here is what keeps the height
      ;; from being derived from the control font instead.
      {:padding 0 :border 0 :margin-top 3 :margin-right 3
       :margin-bottom 3 :margin-left 4 :box 13 :box-sizing "border-box"}

      ;; An OPEN listbox has none of the closed dropdown's 1px internal
      ;; block padding: measured, a `size="3"` multiple select is exactly
      ;; `2 (border) + 3 rows` tall at every font size (41/53/62.5625/
      ;; 91.3906 for rows of 13/17/20.1875/29.7969), with nothing left over.
      (select-multiple? node)
      {:padding 0 :border 1 :line-height :normal}

      :else (get ua-control-box tag))))

(def ^:private ua-em-box
  "The UA box of the two form-GROUPING elements, whose padding is stated in
   `em` rather than pixels and so cannot live in `ua-control-box` beside the
   controls' constants. Measured in Brave 2026-08-04 via `getComputedStyle`,
   at font-size 14 and again at 20 to separate the em terms from the px
   ones:

     fieldset  margin-inline 2px, border 2px groove,
               padding 0.35em 0.75em 0.625em
               (14px -> 4.9/10.5/8.75, 20px -> 7/15/12.5)
     legend    padding-inline 2px, no block padding, no border
               (2px at 14, 20 and 30px font -- a constant, not an em)

   `:em` values are multiples of the element's own font size, resolved by
   `ua-em-box-for`; everything else is pixels. The fieldset's is the LAST
   piece of the `:form/fieldset-and-legend` cluster that is a box at all --
   where the legend actually goes is a layout rule, see `fieldset-legend`.

   A `<legend>` gets its 2px whether or not it is inside a fieldset:
   measured, a bare `<legend>bare legend</legend>` is a full-width block
   whose text still starts at x=2.

   `<hr>` is here for the mechanism rather than the em: this is the only
   table `node-style`'s `:border-width` consults for a UA border, and an
   `<hr>`'s entire visible self IS its border. Measured in Brave 151,
   2026-08-05: `border: 1px inset`, no padding, no content -- a 2px-tall
   box, where this engine drew a 0px-tall one and every block below it sat
   2px high. Its `margin: 0.5em 0` is in ua-margin-scale with the other
   vertical margins. The `inset` STYLE is not modelled (this engine draws
   one solid border), so the line is a hairline rectangle in the border
   colour rather than a two-tone bevel -- geometry, not paint."
  {:fieldset {:margin-left 2 :margin-right 2 :border 2
              :em {:padding-top 0.35 :padding-right 0.75
                   :padding-bottom 0.625 :padding-left 0.75}}
   :legend {:padding-left 2 :padding-right 2 :padding-top 0 :padding-bottom 0}
   :hr {:border 1 :padding 0}})

(defn- ua-em-box-for
  "`ua-em-box`'s entry for `node`, with its `:em` terms resolved against the
   element's own font size.

   The font size is the element's DECLARED one or the theme's base, exactly
   as `ua-margin-y` and `ua-font-scale` already resolve their own em terms
   -- and with exactly the same honest simplification: a fieldset nested
   inside larger text will not compound the way real `em` would, because
   the inherited size is not available here. Measured to be worth having
   anyway: it is the difference between 4.9px and a guess."
  [node theme]
  (when-let [box (get ua-em-box (:tag node))]
    (if-let [em (:em box)]
      (let [fs (or (parse-int (get-in node [:attrs :style/font-size]) nil)
                   (:font-size theme))]
        (merge (dissoc box :em)
               (reduce-kv (fn [m k v] (assoc m k (* v fs))) {} em)))
      box)))

(def ^:private ua-padding
  "UA-stylesheet padding defaults, in the uniform form this engine's box
   model can express. Real Chrome ships `td, th { padding: 1px }`; without
   it every table cell was 2px short in each axis, which the conformance
   harness's geometry axis reported as td 6/29."
  {:td 1 :th 1})

(def ^:private ua-font-style
  {:em "italic" :i "italic" :cite "italic" :dfn "italic" :var "italic" :address "italic"})

(def ^:private ua-vertical-align
  "`sub { vertical-align: sub }` and `sup { vertical-align: super }` are UA
   stylesheet rules -- an author writes `<sub>`, never the declaration --
   so without them a subscript and a superscript sat on the same baseline
   as the text around them, which is the entire visual point of both tags."
  {:sub "sub" :sup "super"})

(def ^:private ua-font-scale
  "Heading font sizes from the HTML5 UA stylesheet, as multiples of the
   base font size (`h1 { font-size: 2em }` and so on down). Resolved
   against the THEME's base size rather than the inherited size -- an
   honest simplification: a heading nested inside larger text will not
   compound, which real `em` would."
  {:h1 2.0 :h2 1.5 :h3 1.17 :h4 1.0 :h5 0.83 :h6 0.67 :small 0.83 :sub 0.83 :sup 0.83})

(defn- ua-margin-y
  "The UA vertical margin for `node`, in pixels, resolved against the
   element's own (possibly UA-scaled) font size the way real `em` margins
   are.

   A NESTED list has none: Chrome's UA stylesheet cancels it outright
   (`:is(ul,ol) ul, :is(ul,ol) ol { margin-block-start: 0;
   margin-block-end: 0 }`), so a sublist sits flush against the line above
   it. Measured on `<ul><li>a<ul><li>b</li></ul></li></ul>`: Chrome reports
   the inner `<ul>` at `margin-block: 0px` and its `<li>` at y=20, where
   this engine gave the sublist a full 1em top margin and put the same
   `<li>` at y=34 -- the whole of the harness's `li y +14` residual. The
   `:ua/list-descendant` mark is written by with-nested-list-margins."
  [node theme]
  (when-not (and (contains? list-container-tags (:tag node))
                 (attr node :ua/list-descendant))
    (when-let [scale (get ua-margin-scale (:tag node))]
      (let [fs (or (parse-int (get-in node [:attrs :style/font-size]) nil)
                   (when-let [heading-scale (get ua-font-scale (:tag node))]
                     (long (* heading-scale (:font-size theme))))
                   (:font-size theme))]
        (long (* scale fs))))))

(defn- gap-shorthand-axis
  "One axis of the `gap` shorthand's `<row-gap> [<column-gap>]` value, in
   px, or nil when `v` declares nothing usable.

   `gap: 10px` sets both axes to 10; `gap: 24px 8px` sets rows to 24 and
   columns to 8 (row first, the same order every two-value box shorthand
   in CSS uses). cssom.core coerces a WHOLE-value single length to a plain
   number before this file ever sees it, which is why the number case is
   handled here rather than only the string one -- see parse-track-list's
   docstring for the same split.

   Returns nil rather than a fallback for an unparseable value, so
   node-style's own `(or longhand shorthand theme)` chain falls through to
   the next source instead of the shorthand silently winning with a zero."
  [v axis]
  (cond
    (number? v) v
    (string? v) (let [toks (str/split (str/trim v) #"\s+")
                      tok (if (and (= :column axis) (= 2 (count toks))) (second toks) (first toks))]
                  (parse-int tok nil))
    :else nil))

(declare font-metrics)

(defn- node-style [node theme]
  ;; `ua-box` is this element's UA box: the control constants
  ;; (ua-control-box-for) or, for the two form-grouping elements whose
  ;; padding is stated in em, ua-em-box-for. One lookup, read by every
  ;; side below -- they used to call ua-control-box-for ten times over.
  (let [ua-box (or (ua-control-box-for node) (ua-em-box-for node theme))]
  ;; real HTML5's [hidden] { display: none } is an ordinary, low-priority
  ;; UA-stylesheet rule, not !important -- any author :display the cascade
  ;; already resolved wins over it, matching that real override pattern.
  {:display (or (style node :display)
                (when (truthy-attr? (attr node :hidden)) "none"))
   :position (or (style node :position) "static")
   :left (style node :left)
   :top (style node :top)
   :right (style node :right)
   :bottom (style node :bottom)
   :z-index (parse-int (style node :z-index) 0)
   :width (or (style node :width) (presentational-size node :width))
   :height (or (style node :height) (presentational-size node :height))
   :min-width (style node :min-width)
   :max-width (style node :max-width)
   :min-height (style node :min-height)
   :max-height (style node :max-height)
   ;; Left RAW, like :transform above and for the same reason: an
   ;; `aspect-ratio` is a ratio (or the keyword `auto`, or both), not a
   ;; length, so this file's numeric coercions would turn `3 / 1` into the
   ;; integer 3. The one place that reads it parses it -- see
   ;; aspect-ratio.
   :aspect-ratio (style node :aspect-ratio)
   :box-sizing (or (style node :box-sizing)
                   (:box-sizing ua-box)
                   "content-box")
   :padding (parse-int (style node :padding)
                       (or (:padding ua-box)
                           (get ua-padding (:tag node))
                           (:padding theme)))
   ;; The DECLARED padding only -- author or UA -- with no theme fallback.
   ;; The theme's uniform padding is a host decoration, not CSS: letting it
   ;; widen a content-box `width` would make `div{width:50px}` occupy 58px
   ;; because of a styling choice the author never made.
   :padding/declared (parse-int (style node :padding) (get ua-padding (:tag node)))
   :padding-top (parse-int (style node :padding-top)
                           (:padding-top ua-box))
   :padding-right (parse-int (style node :padding-right)
                             (:padding-right ua-box))
   :padding-bottom (parse-int (style node :padding-bottom)
                              (:padding-bottom ua-box))
   :padding-left (parse-int (style node :padding-left)
                            (or (:padding-left ua-box)
                                (get-in ua-box-sides [(:tag node) :padding-left])))
   :margin-top (parse-int (style node :margin-top)
                          (or (:margin-top ua-box)
                              (ua-margin-y node theme)))
   :margin-bottom (parse-int (style node :margin-bottom)
                             (or (:margin-bottom ua-box)
                                 (ua-margin-y node theme)))
   :margin-left (parse-int (style node :margin-left)
                           (or (:margin-left ua-box)
                               (get-in ua-box-sides [(:tag node) :margin-left])))
   :margin-right (parse-int (style node :margin-right)
                            (or (:margin-right ua-box)
                                (get-in ua-box-sides [(:tag node) :margin-right])))
   ;; A USED height injected by the layout itself (force-cross-size's
   ;; stretch, layout-absolute-children's top+bottom solve), as an attr
   ;; rather than a `style/height` declaration because it is a BORDER-box
   ;; number that has already been solved -- box-sizing must not be applied
   ;; to it a second time. See used-block-height.
   :height/used (parse-int (attr node :kotoba/used-height) nil)
   ;; The margins as the cascade wrote them, alongside the coerced numbers
   ;; above. `auto` is a real, extremely common margin value that is not a
   ;; length at all, and the coercion that makes every other reader safe is
   ;; exactly what erases it -- see auto-margin?.
   :margin/raw-top (style node :margin-top)
   :margin/raw-bottom (style node :margin-bottom)
   :margin/raw-left (style node :margin-left)
   :margin/raw-right (style node :margin-right)
   ;; The containing block's inline-axis START edge. Read here, resolved
   ;; and inherited by layout-node -- see the `:direction` entry it adds to
   ;; the inherited map for what part of `rtl` this engine implements.
   :direction (some-> (style node :direction) str str/lower-case str/trim)
   ;; Real CSS's `border-spacing` defaults to 2px in every browser: cells
   ;; are separated by it AND the table is inset by it on all four sides.
   ;; Measured against Chrome, its absence was the single reason table/tr
   ;; geometry never matched -- a 2-cell table reported 49x20 here against
   ;; the browser's 59x26, an exactly-4px-per-axis difference plus the cell
   ;; padding above.
   ;;
   ;; That 2px is a UA-STYLESHEET rule keyed on the TAG (`table {
   ;; border-spacing: 2px }`), not a CSS initial value -- the initial value
   ;; is 0. So an ordinary element made into a table by `display: table`
   ;; gets NO spacing: measured in Brave, `<div style="display:table">`
   ;; reports `border-spacing: 0px` where `<table>` reports `2px`, and a
   ;; 300px-wide div-table puts its two cells at x=0 and x=100 with no gap
   ;; anywhere. Defaulting to 2 for every node put 2px of phantom spacing
   ;; into every CSS-declared table.
   :border-spacing (parse-int (style node :border-spacing)
                              (if (= :table (:tag node)) 2 0))
   ;; Both read only by layout-table. `border-collapse`'s initial value is
   ;; `separate` and `table-layout`'s is `auto`, so absent means "what this
   ;; engine already did".
   :border-collapse (some-> (style node :border-collapse) str/lower-case)
   :table-layout (some-> (style node :table-layout) str/lower-case)
   :margin (parse-int (style node :margin) 0)
   ;; Real CSS resolves the USED border width through `border-style`,
   ;; whose initial value is `none`: a `none`/`hidden` border is 0px wide
   ;; however many pixels `border-width` declares. This engine honours a
   ;; bare `border-width` on its own. Measured in Chrome,
   ;; `<div style="border-width: 1px">` reports `border-top-width: 0px` /
   ;; `border-top-style: none`, so a div wrapping one `<p>` is 20px tall
   ;; there and 50 here (the phantom border also stops margins collapsing
   ;; through the box, moving three boxes at once), and
   ;; `border-width: 4px; padding: 10px; width: 200px` is 220x40 there
   ;; against 228x48 here. The phantom border also stopped margins
   ;; collapsing through the box, moving three boxes at once.
   ;;
   ;; A UA control border survives, because it IS written with a style
   ;; (`1px solid`), which is why the gate consults `ua-border` rather than
   ;; only the author's `border-style`.
   ;;
   ;; This was measured on 2026-08-04 and left unlanded for one day, not
   ;; because the rule was in doubt but because it changes a contract this
   ;; repo's consumers depend on: `kotoba-lang/browser`'s suite went 0 -> 5
   ;; failures on it, and those tests live in another repository. It landed
   ;; together with that repo's update -- the fixtures there declared a
   ;; bare `border-width` and asserted a border, so they were asking for
   ;; something no browser draws; they now declare `border-style` and go on
   ;; testing borders.
   :border-width (let [ua-border (get ua-box :border 0)
                       border-style (or (some-> (style node :border-style) str/lower-case)
                                        (when (pos? ua-border) "solid"))]
                   (if (or (nil? border-style)
                           (contains? #{"none" "hidden"} border-style))
                     0
                     (parse-int (style node :border-width) ua-border)))
   :border-color (or (style node :border-color) "#000000")
   ;; parse-int'd (unlike :left/:top/:width/etc a few lines up, which
   ;; stay raw strings because real CSS auto/% are legitimate non-numeric
   ;; values those properties must preserve for explicit-length's own
   ;; unset-vs-zero distinction) -- box-shadow's offset/spread have NO
   ;; such legitimate non-numeric CSS form (always a plain length or
   ;; absent), yet box-shadow-ops below does raw (+ x dx (- spread))
   ;; arithmetic directly against these, with zero coercion anywhere else
   ;; in the pipeline. The shorthand-expansion path (cssom.core/expand-
   ;; box-shadow-shorthand, and htmldom.core's own independent copy for
   ;; inline style="...") already numeric-coerces these before they ever
   ;; reach :attrs, so this is a no-op for ordinary authored CSS -- but
   ;; browser/dom-bridge.cljc's set-style-property (a script mutating an
   ;; INDIVIDUAL element.style.<prop> = value, e.g. via the QuickJS
   ;; shim's element.style set trap) merges whatever JS handed it
   ;; straight into :style-inline with NO coercion pass at all, so that
   ;; path can and does deliver a raw string here. Confirmed via direct
   ;; REPL reproduction before this fix: building a real element with
   ;; :box-shadow-x/:box-shadow-y set to the strings "8"/"8" (simulating
   ;; exactly what that uncoerced JS-mutation path produces) crashed
   ;; draw-ops with `ClassCastException: class java.lang.String cannot
   ;; be cast to class java.lang.Number` on the JVM -- and on this
   ;; engine's real ClojureScript/JS target, the same string instead
   ;; silently produces JS string-concatenation garbage (`+`/`-` compile
   ;; straight to native JS operators there, with no runtime type check),
   ;; which is exactly how a real demo page's own box-shadow rect was
   ;; separately observed rendering with garbage coordinates like
   ;; "460146820" before this fix, confirmed via a temporary console.log.
   :box-shadow-x (parse-int (style node :box-shadow-x) 0)
   :box-shadow-y (parse-int (style node :box-shadow-y) 0)
   :box-shadow-blur (parse-int (style node :box-shadow-blur) 0)
   :box-shadow-spread (parse-int (style node :box-shadow-spread) 0)
   :box-shadow-color (style node :box-shadow-color)
   :outline-width (parse-int (style node :outline-width) 0)
   :outline-color (or (style node :outline-color) "#000000")
   :outline-offset (parse-int (style node :outline-offset) 0)
   :background (or (style node :background) (style node :background-color))
   :color (style node :color)
   :font-size (or (style node :font-size)
                  (when (contains? form-control-tags (:tag node)) (:size ua-control-font))
                  (when-let [scale (get ua-font-scale (:tag node))]
                    (long (* scale (:font-size theme)))))
   :font-family (or (style node :font-family)
                    (when (contains? form-control-tags (:tag node)) (:family ua-control-font)))
   :line-height (or (style node :line-height)
                    ;; A control's UA `font:` shorthand resets line-height to
                    ;; NORMAL, so an inherited page line-height never applies
                    ;; to it -- read straight off getComputedStyle in Brave,
                    ;; where every control reports `line-height: normal`
                    ;; inside a `line-height: 20px` block. `normal` is the
                    ;; font's own content area, which is why this needs
                    ;; font-metrics and not the font SIZE: at 13.3333px Arial
                    ;; that is 15 (12 + 3), and using the size gave 13, one
                    ;; whole leading short in the control's box AND in its
                    ;; internal baseline.
                    (when (= :normal (:line-height ua-box))
                      (let [fs (or (style node :font-size) (:size ua-control-font))
                            {:keys [ascent descent]}
                            (font-metrics theme fs
                                          (style node :font-weight)
                                          (style node :font-style)
                                          (or (style node :font-family) (:family ua-control-font)))]
                        (+ ascent descent))))
   :font-weight (or (style node :font-weight) (get ua-font-weight (:tag node)))
   :font-style (or (style node :font-style) (get ua-font-style (:tag node)))
   ;; parse-int'd for the exact same reason box-shadow-x/y/blur/spread
   ;; above just were -- layout-text's own inline shadow-op does raw
   ;; (+ line-x (or (:x text-shadow) 0)) arithmetic against these with
   ;; zero coercion, the same crash/silent-garbage class this fix closes
   ;; for box-shadow.
   :text-shadow-x (parse-int (style node :text-shadow-x) 0)
   :text-shadow-y (parse-int (style node :text-shadow-y) 0)
   :text-shadow-blur (parse-int (style node :text-shadow-blur) 0)
   :text-shadow-color (style node :text-shadow-color)
   :text-decoration (style node :text-decoration)
   :text-align (style node :text-align)
   :text-transform (style node :text-transform)
   ;; `transform`/`transform-origin` are read RAW: they are function lists
   ;; and position pairs, not lengths, and the one place that parses them
   ;; (transform-list-matrix / transform-origin-point) needs the element's
   ;; own already-laid-out border box to resolve a percentage against.
   :transform (style node :transform)
   :transform-origin (style node :transform-origin)
   :white-space (or (style node :white-space) (when (= :pre (:tag node)) "pre"))
   :text-overflow (style node :text-overflow)
   :overflow-wrap (or (style node :overflow-wrap) (style node :word-wrap))
   :word-break (style node :word-break)
   ;; CSS Color Module Level 4's `opacity`: "Opacity values outside the
   ;; range [0,1]... are clamped to the range [0,1] in computed values."
   ;; Previously read raw via parse-dbl with no clamp -- an author value
   ;; like `opacity: 2` reached the multiplicative opacity accumulator
   ;; below unclamped, so a child's OWN otherwise-correct opacity got
   ;; multiplied by more than 1 instead of the real, spec-clamped 1,
   ;; silently rendering it MORE opaque than its own declared opacity
   ;; says it should be. A negative value (`opacity: -1`) is equally
   ;; unclamped, propagating a negative alpha into dom-gpu's ->rgba and
   ;; the WebGL/WebGPU paint backends instead of the spec-mandated fully
   ;; transparent 0.
   :opacity (max 0.0 (min 1.0 (parse-dbl (style node :opacity) 1.0)))
   :visibility (style node :visibility)
   :justify-content (or (style node :justify-content) "flex-start")
   :align-items (or (style node :align-items) "stretch")
   ;; How a MULTI-LINE flex container distributes its lines along the
   ;; cross axis. `stretch` is the initial value (`normal` computes to it
   ;; for a flex container), and it is a SIZE change: the lines grow to
   ;; fill a definite cross size rather than moving within it -- see
   ;; layout-flex-wrap-row.
   :align-content (or (style node :align-content) "stretch")
   :flex-grow (parse-dbl (style node :flex-grow) 0.0)
   :flex-shrink (parse-dbl (style node :flex-shrink) 1.0)
   ;; Left as the RAW cascade value (a length, `auto`, a percentage, or
   ;; nil when unauthored) rather than coerced here: `auto` -- the initial
   ;; value and the overwhelmingly common one -- means "use the item's own
   ;; main size", which is a different answer from any number, and
   ;; flex-item-base-size is the one place that distinction is decided.
   :flex-basis (style node :flex-basis)
   ;; A flex/grid item's own cross-axis alignment, overriding the
   ;; container's :align-items. `auto` (the initial value) explicitly means
   ;; "defer to the container", so it is kept verbatim rather than
   ;; normalised away -- see item-cross-align.
   :align-self (style node :align-self)
   ;; A flex item's ORDER-modified document position. The initial value is
   ;; 0 and negative values are legal (an `order: -1` item sorts BEFORE
   ;; every unauthored sibling), which is why this is parse-int'd with a 0
   ;; default rather than read raw.
   :order (parse-int (style node :order) 0)
   :flex-direction (or (style node :flex-direction) "row")
   :flex-wrap (or (style node :flex-wrap) "nowrap")
   :grid-template-columns (style node :grid-template-columns)
   :grid-template-rows (style node :grid-template-rows)
   :grid-template-areas (style node :grid-template-areas)
   :grid-column (style node :grid-column)
   :grid-row (style node :grid-row)
   :grid-area (style node :grid-area)
   ;; `grid-auto-flow` picks which axis auto-placement fills FIRST: `row`
   ;; (the initial value) fills a row left-to-right before wrapping down,
   ;; `column` fills a column top-to-bottom before wrapping right. Read
   ;; raw -- layout-grid only ever asks whether it names `column`, and the
   ;; `dense` packing keyword (which may appear alongside it) is a
   ;; documented non-goal there.
   :grid-auto-flow (style node :grid-auto-flow)
   ;; The track sizes IMPLICIT tracks take -- the ones auto-placement
   ;; creates beyond whatever grid-template-rows/-columns declared. Left
   ;; as the raw cascade value: parse-track-list owns the grammar, and
   ;; layout-grid is the only reader.
   :grid-auto-rows (style node :grid-auto-rows)
   :grid-auto-columns (style node :grid-auto-columns)
   ;; A grid container's default INLINE-axis alignment for its items, and
   ;; an item's own override. `stretch` (the initial value, what `normal`
   ;; computes to for a grid item) fills the whole column track; anything
   ;; else makes the item fit-content and positions it inside the track --
   ;; see grid-item-inline-align/layout-grid. `justify-self: auto` means
   ;; "defer to the container", exactly like align-self, so it is kept
   ;; verbatim rather than normalised away.
   :justify-items (or (style node :justify-items) "stretch")
   :justify-self (style node :justify-self)
   :gap (parse-int (style node :gap) (:gap theme))
   ;; `row-gap`/`column-gap` are the two LONGHANDS `gap` is shorthand for,
   ;; and a grid can set them to different values (`row-gap: 24px;
   ;; column-gap: 8px`). cssom.core's cascade has no shorthand expansion
   ;; for `gap`, so the fallback chain is written here: an explicit
   ;; longhand wins, else the matching half of the `gap` shorthand (which
   ;; itself takes `<row> <column>`, one value meaning both), else the
   ;; theme's own gap. Nothing read either longhand before this: measured
   ;; in Brave, `row-gap: 24px; column-gap: 8px` on a 2x2 grid puts the
   ;; second column at x=68 and the second row at y=44, where this engine
   ;; had 60 and 20 (the theme gap of 0).
   ;;
   ;; Known limit of doing it here rather than in the cascade: DECLARATION
   ;; ORDER between the shorthand and a longhand is lost, so `row-gap:
   ;; 24px; gap: 5px` -- where the later shorthand should reset the
   ;; longhand -- still reports 24. The common order (shorthand first,
   ;; longhand refining it) is correct.
   :row-gap (or (parse-int (style node :row-gap) nil)
                (gap-shorthand-axis (style node :gap) :row)
                (:gap theme))
   :column-gap (or (parse-int (style node :column-gap) nil)
                   (gap-shorthand-axis (style node :gap) :column)
                   (:gap theme))
   :pointer-events (style node :pointer-events)
   ;; :overflow is exclusively a CSS property in real HTML/CSS -- nothing
   ;; ever sets it as a plain (non-namespaced) DOM attribute the way
   ;; :scroll-top/:scroll-left below genuinely are (real runtime scroll-
   ;; position state, not CSS). Previously read via `attr` like those two,
   ;; so a stylesheet-authored `overflow: hidden` (the overwhelmingly
   ;; common way authors set it) never reached this map at all, even
   ;; though apply-cascade already resolves it correctly onto :style/
   ;; overflow -- confirmed via direct REPL reproduction before this fix:
   ;; a real cascaded `.container { overflow: hidden }` produced zero
   ;; clip-push/clip-pop ops from layout-block's `clip?` check just below,
   ;; silently letting an overflowing child paint outside its box.
   ;; browser.document-input's own scrollable-node? already gets this
   ;; right (checks `style` first, falling back to `attr`), confirming
   ;; this file was the outlier, not an intentional design choice.
   :vertical-align (or (style node :vertical-align)
                       (get ua-vertical-align (:tag node)))
   :float (style node :float)
   ;; `clear` was read NOWHERE at all, so the single most common float
   ;; idiom on the web -- a float, some content beside it, then a
   ;; `clear`ed box that must start BELOW it -- silently put the cleared
   ;; box beside the float instead. Measured in Brave on
   ;; `<div style="float:left;width:80px;height:40px">F</div><div>beside
   ;; </div><div style="clear:left">below</div>`: the browser reports the
   ;; cleared div at y=40 (the float's bottom margin edge) where this
   ;; engine had it at y=20, and the container 64px tall against 40.
   :clear (style node :clear)
   ;; Set by measure-child on a FLEX or GRID item: such a box establishes
   ;; its own formatting context, so margins never collapse through it --
   ;; the same rule `overflow` triggers, but decided by the PARENT, which is
   ;; why it arrives as an attr rather than a declaration.
   :independent-fc? (boolean (attr node :kotoba/independent-fc))
   :overflow (style node :overflow)
   :scroll-top (parse-int (attr node :scroll-top) 0)
   :scroll-left (parse-int (attr node :scroll-left) 0)}))

(defn- style-passthrough [st]
  {:display (:display st)
   :position (:position st)
   :z-index (:z-index st)
   :pointer-events (:pointer-events st)
   ;; Previously absent -- a visibility:hidden/collapse element already
   ;; paints nothing (see the multiplicative opacity accumulator this
   ;; property drives in layout-block), but the emitted :node draw-op
   ;; itself carried no trace of that at all, so a hit-tester scanning
   ;; these ops (browser.session/node-at, dom-gpu's retained/hit-test)
   ;; had no way to tell a visibility:hidden box apart from an ordinary
   ;; opaque one -- it still swallowed clicks itself or blocked whatever
   ;; painted underneath it, the same click-through bug class already
   ;; fixed for pointer-events:none, just for visibility instead. Real
   ;; CSS treats visibility:hidden/collapse as fully transparent to
   ;; pointer events (CSS-UI-4 / CSS2.1 SS11.1.1), exactly like
   ;; pointer-events:none.
   :visibility (:visibility st)
   :overflow (:overflow st)
   :scroll-top (:scroll-top st)
   :scroll-left (:scroll-left st)
   :min-width (:min-width st)
   :max-width (:max-width st)
   :box-sizing (:box-sizing st)
   :justify-content (:justify-content st)
   :align-items (:align-items st)
   :flex-wrap (:flex-wrap st)})

(defn- explicit-length
  "Defensively coerces an OPTIONAL, already-cascade-resolved style value to a
   plain integer px number, or nil when absent OR when cssom.core
   intentionally left the raw value an unparsed string -- a percentage
   (`50%`), `auto`, or any other keyword/expression outside this engine's
   bounded numeric subset (see cssom.core's parse-style-value docstring, and
   the calc-with-a-percentage-does-not-resolve-a-width-through-the-real-pipeline
   test in layout_test.clj for why this is a REAL, expected shape reaching
   this file, not a hypothetical). Uses this file's own permissive parse-int
   (leading-digit-run extraction, e.g. \"50%\" -> 50) for the same reason
   resolve-width already does for :width -- consistency with that
   pre-existing, documented behavior, not a new leniency -- but resolves to
   nil (not a fallback number) when parse-int finds no digits at all (e.g.
   `auto`), so a caller's own `(or (explicit-length ...) auto-fallback)`
   correctly falls through to auto/content-driven sizing instead of the
   fallback silently winning over a genuine (if imprecise) numeric read.

   This exists because :width has exactly ONE place in this file that reads
   its raw cascade value directly into arithmetic (resolve-width's own
   `parse-int` call, which already defends against a raw string via its own
   avail fallback) -- but :height, :min-width, :max-width, :left, and :top
   each have their OWN separate raw-read call sites (layout-flex's
   cross/main-content, resolve-width's min/max clamp, layout-grid/
   layout-block/layout-form-control's explicit-h, layout-absolute-children's
   left/top) that used to read the raw style value directly into `+`/`-`/
   `max`/`min` with no coercion at all -- crashing with a ClassCastException
   (String cannot be cast to Number) the moment cssom.core ever left one of
   those properties an unresolved raw string (any percentage/auto/keyword
   value -- real and common, not just a contrived edge case). Every one of
   those call sites now goes through this (or resolve-height, its
   :height-shaped wrapper) instead."
  [v]
  (when (some? v) (parse-int v nil)))

;; The calc() machinery lives ~700 lines down, next to the grid track-list
;; parser that first needed it. Declared here because a `calc()` is a LENGTH
;; before it is a track size -- `width: calc(100% - 40px)` is the ordinary
;; author idiom this file's length resolution has to answer, and the
;; alternative (a second copy of calc-pattern next to the length helpers) is
;; exactly the regex drift this file's own var-ref-pattern comment in
;; cssom.core argues against.
(declare calc-pattern resolve-constant-calc)

(defn- percentage?
  "Whether a cascade value is a percentage this engine should resolve
   against a containing block rather than read as a bare pixel count."
  [v]
  (and (string? v) (some? (re-matches #"\s*-?[0-9]*\.?[0-9]+%\s*" v))))

(defn- calc-value?
  "Whether a cascade value is a whole-value `calc(...)` call this file has to
   resolve itself.

   cssom.core already collapses a CONSTANT calc() (`calc(100px + 20px)`) to a
   plain number during the cascade, so anything still wearing the `calc(`
   text when it gets here contains something the cascade could not resolve
   without knowing the layout -- a percentage, or a unit outside this
   engine's subset. Both must stay out of `parse-int`'s leading-digit-run
   reach: `calc(100% - 40px)` is not 100px, and reading it as one is a guess
   dressed as a value."
  [v]
  (and (string? v) (some? (re-matches calc-pattern (str/trim v)))))

(defn- calc-of
  "Resolves a whole-value `calc(...)` against `basis` -- the containing
   block's size in the axis the value is being read in -- or nil when the
   value is not a calc() call, when its contents are outside this engine's
   calc subset, or when a percentage appears inside it and `basis` is not a
   definite length (`percentage-of`'s own rule, for the same reason: a
   percentage against an indefinite basis is `auto`, not zero).

   Measured against Brave: `width: calc(100% - 40px)` inside a 300px block is
   260 there and was 300 here (the whole value failed to parse and
   resolve-width's avail-width fallback won), and `calc(50% + 10px)` is 160
   against the same 300."
  [v basis]
  (when (calc-value? v)
    (resolve-constant-calc (str/trim v) basis)))

(defn- percentage-of
  "Resolves a cascade value that may be a PERCENTAGE against `basis`, or nil
   when the value is absent, is not a percentage this engine can resolve, or
   `basis` is not a definite length.

   Real CSS resolves a percentage against a dimension of the containing
   block. Until this existed, `parse-int`'s leading-digit-run extraction
   turned a `50%` string straight into 50 PIXELS -- documented in
   `explicit-length` as a deliberate consistency choice with `resolve-width`,
   and measured against Brave as wrong in nine corpus cases at once
   (`box/percentage-width` 200 vs 50, `box/max-width-percentage` 150 vs 50,
   `position/absolute-percentage-offsets`, `table/width-percentage`, ...).
   The corpus also carries the control that shows how such a bug hides: a
   50% width inside a 100px parent resolves to 50 either way, so it passed
   throughout.

   `basis` nil means the containing block's size in that axis is NOT
   definite -- an auto-height parent, typically. Real CSS treats a
   percentage against an indefinite basis as `auto`, which is why this
   returns nil there rather than falling back to the raw number: nil is what
   every caller already spells 'auto, size me from content'."
  [v basis]
  (when (and (string? v) (some? basis))
    (when-let [[_ n] (re-matches #"\s*(-?[0-9]*\.?[0-9]+)%\s*" v)]
      (let [pct #?(:clj (Double/parseDouble n) :cljs (js/parseFloat n))]
        (long (Math/round (* basis (/ pct 100.0))))))))

(defn- length-or-percentage
  "`explicit-length`, but resolving a percentage -- or a `calc()` with a
   percentage in it -- against `basis` first.
   Call sites that know their containing-block dimension use this; ones that
   genuinely do not (yet) keep calling `explicit-length` and keep its
   documented approximation, so the two are easy to tell apart when reading."
  [v basis]
  (or (percentage-of v basis)
      (calc-of v basis)
      (when-not (or (calc-value? v) (and (string? v) (str/includes? (str v) "%")))
        (explicit-length v))))

(defn- clamp-width
  "The :width counterpart to clamp-height's own shared min/max clamp --
   split out so flex-item-main-width's shrink-to-fit natural width (which
   never runs through resolve-width's own avail-width fallback base) still
   gets the same min-width/max-width treatment an explicit or avail-
   defaulted width already does."
  ;; `basis` is the containing block's content WIDTH, against which a
  ;; percentage min/max resolves; callers that do not have one pass nothing
  ;; and a percentage min/max is then ignored rather than read as pixels.
  ([st width] (clamp-width st width nil))
  ([st width basis]
   (let [width (if-let [mn (length-or-percentage (:min-width st) basis)] (max width mn) width)
         width (if-let [mx (length-or-percentage (:max-width st) basis)] (min width mx) width)]
     width)))

(defn- content-inset
  "inset-side's UNIFORM sibling: the distance from a border-box edge to the
   content-box edge when every side is the same. Reads the uniform
   `:padding` alone, so a box with per-side padding is measured against an
   inset it does not have -- which is why layout-block, the one place that
   needed to be right about it, reads inset-side per side instead. Kept for
   the callers whose own axis has no per-side story yet (flex, grid, table,
   the form controls), and for the intrinsic-width branches whose numbers
   were fixed against it."
  [st]
  (+ (:padding st) (:border-width st)))

(defn- declared-inset-side
  "The part of the inset that a CONTENT-BOX `width` has to grow by: the
   DECLARED padding on that side (author or UA, never the theme's own
   decoration) plus the border, which always sits outside the content box."
  [st side]
  (+ (or (get st (keyword (str "padding-" (name side))))
         (:padding/declared st)
         0)
     (:border-width st)))

(defn- inset-side
  "The content inset on ONE side: that side's own padding when the author
   (or the UA stylesheet) gave it one, else the uniform padding this engine
   has always had, PLUS the border -- always, in both `box-sizing` modes.

   `box-sizing` does not move the content edge. It decides what a DECLARED
   `width`/`height` measures (the content box under `content-box`, the
   border box under `border-box`), and that question belongs to
   resolve-width / used-block-height, which answer it through
   declared-inset-side. Where the content STARTS is border + padding in
   from the border edge either way, which is what this function is.

   Reading `box-sizing` here as well conflated the two and left the border
   out of a content-box element's inset in both axes: a
   `border:2px;padding:10px` div put its `<p>` at y=24 where Brave says 26
   and gave it 4px too much content width. Measured in Brave 2026-08-05
   (`getComputedStyle` + `getBoundingClientRect` + `clientLeft`/
   `clientWidth`), on the shapes this reaches:

     div{border:2px;padding:10px}         child at x=12, w=376/400
     div{box-sizing:border-box;...}       child at x=12, w=176/200
     div{border:5px;padding:4 8 12 16}    child at x=21, y=23, w=200
     flex/grid{border:3px;padding:7px}    items at x=10, y=10
     td{border:4px;padding:6px}           child at x=10, y=24
     fieldset (UA 2px border)             <p> at x=12.5 of the fieldset

   -- the same `border + padding` on every one of them, and `clientLeft`
   (the browser's own border-edge-to-padding-edge distance) equal to the
   border in all six.

   NOT the same as an absolutely positioned descendant's containing block,
   which is the PADDING box -- border alone, padding outside it. layout-
   block computes that separately (`pad-x`/`pad-y`), and it stays separate:
   measured, `position:relative;border:7px;padding:9px` puts a `top:0;
   left:0` child at x=7, not x=16."
  [st side]
  (+ (or (get st (keyword (str "padding-" (name side)))) (:padding st))
     (:border-width st)))

(defn- intrinsic-inset-x
  "The horizontal inset an intrinsic width has to put around its content:
   exactly the pair layout-block will subtract when it lays the box out
   (inset-side :left/:right), so a measured content width and the width
   the box is then given describe the same box.

   `(* 2 (content-inset st))` -- what every intrinsic branch used to add --
   reads the UNIFORM `:padding` alone, so any box with a PER-SIDE padding
   was measured against an inset it does not have. That was harmless while
   the fallback for an unfamiliar shape was `content-w` (nothing reached
   the arithmetic), and stops being harmless the moment a block container
   is measured from its children: a `<ul>`, whose UA padding is
   `padding-left: 40px` and nothing else, came out 21px wide -- its widest
   `<li>` and not one pixel of room for the 40px layout-block then takes
   off the left, which collapsed both list items to zero width and two
   lines tall."
  [st]
  (+ (inset-side st :left) (inset-side st :right)))

(defn- auto-margin?
  "Whether ONE side's margin is the keyword `auto` -- read from the raw
   cascade value, because `auto` is not a length and node-style's own
   `parse-int` coercion (correctly) turns it into the same nil a missing
   margin produces.

   The two are the same number (0) and completely different layout: a 0
   margin leaves the box where the flow put it, an `auto` one absorbs the
   leftover space. Real CSS: with both inline margins `auto` the box is
   CENTRED, with one the box is pushed to the other side, and with neither
   the leftover goes to whichever side the containing block's `direction`
   says is the end. (A vertical `auto` margin on an in-flow block is simply
   0, which is what margin-side below already gives it.)"
  [st side]
  (let [v (get st (keyword "margin" (str "raw-" (name side))))]
    (and (string? v) (= "auto" (str/lower-case (str/trim v))))))

(defn- margin-side
  "One side's margin AS A LENGTH: the per-side value when present (author or
   UA), else the uniform `:margin`, and 0 when the author wrote `auto`.

   The `auto` case has to short-circuit the uniform fallback rather than
   ride it: `margin: 10px auto` expands to a per-side `auto` on the inline
   sides AND a uniform `:margin` of 10, so falling through would give the
   inline sides the VERTICAL margin -- a 10px indent where the box should be
   centred. Whether an auto side then absorbs the leftover space is
   layout-children-block's question, asked through auto-margin?."
  [st side]
  (if (auto-margin? st side)
    0
    (or (get st (keyword (str "margin-" (name side)))) (:margin st))))

(defn- resolve-width
  "The element's BORDER-BOX width.

   Real CSS's default `box-sizing: content-box` means a declared `width` is
   the CONTENT width: padding and border add OUTSIDE it, so
   `width: 300px; padding: 16px` occupies 332px and lays its children out
   in 300. This engine treated the declared width as the border box in both
   modes, so the same element was 300px wide with 268px of content -- every
   child inside it 32px too narrow, and the box itself 32px too small.
   Caught by the conformance harness's geometry axis on an ordinary card
   shape (`div{width:300px;padding:16px}`), where the browser reports 332
   and 300 against this engine's 300 and 268.

   With `box-sizing: border-box` the declared width IS the border box, and
   nothing is added -- which is exactly why authors reach for it."
  [st avail]
  (let [;; a percentage width -- on its own or inside a calc() -- resolves
        ;; against the containing block's content width, which is exactly
        ;; what `avail` is here.
        declared (or (percentage-of (:width st) avail)
                     (calc-of (:width st) avail)
                     (when-not (or (percentage? (:width st)) (calc-value? (:width st)))
                       (parse-int (:width st) nil)))]
    (clamp-width st
                 (cond
                   (nil? declared) avail
                   (= "border-box" (:box-sizing st)) declared
                   :else (+ declared
                            (declared-inset-side st :left)
                            (declared-inset-side st :right)))
                 avail)))

(defn- resolve-height
  "The :height counterpart to resolve-width's own defensive numeric
   coercion (see explicit-length for the shared 'a raw, cascade-unresolved
   string never reaches raw arithmetic' philosophy both mirror). Unlike
   resolve-width -- which always resolves to SOME number via its avail-width
   fallback, since a box always has a width -- an explicit :height is
   genuinely OPTIONAL everywhere it's read in this file: absent (or
   unparseable, e.g. `auto`) means 'no explicit height here, fall back to
   auto/content-driven sizing', which is exactly why every caller already
   wraps this in its own `(or (resolve-height st) content-driven-fallback)`
   -- so this returns nil (via explicit-length), not a fallback number, for
   that `or` to correctly detect 'nothing explicit was given' the same way
   `(:height st)` being nil already did before any raw string could reach
   here.

   `basis` is the containing block's CONTENT HEIGHT, which is what a
   percentage height resolves against; nil (the one-argument arity) means
   that height is not definite, and real CSS then treats the percentage as
   `auto` -- which is exactly the nil this already returns for `auto`, so
   the callers' own `(or ... content-driven)` needs no change to get the
   right answer. Before this, `explicit-length`'s leading-digit-run read
   turned `height: 50%` into 50 PIXELS whatever the parent was: measured
   against Brave, `box/percentage-height-of-an-auto-parent` is a 20px
   content-driven box there and was 50 here. Its deliberate pair
   `box/percentage-height-of-a-fixed-parent` PASSED throughout -- 50% of a
   100px parent is 50 either way -- which is how a bug this size hid in a
   corpus that had a case pointed straight at it."
  ([st] (resolve-height st nil))
  ([st basis]
   (or (:height/used st)
       (percentage-of (:height st) basis)
       (calc-of (:height st) basis)
       (when-not (or (percentage? (:height st)) (calc-value? (:height st)))
         (explicit-length (:height st))))))

(defn- aspect-ratio
  "`aspect-ratio` as a single width-over-height NUMBER, or nil when the
   element declares none this engine can use.

   The grammar (CSS Sizing 4) is `auto || <ratio>`, and a `<ratio>` is
   `<number> [ / <number> ]?` with an omitted second term of 1. Every form
   below was measured in Brave 151 on 2026-08-05, on the corpus case's own
   `width: 120px` block with one line of text in it:

   | declaration                | height |
   |----------------------------|--------|
   | `aspect-ratio: 3 / 1`      |     40 |
   | `aspect-ratio: 2`          |     60 |
   | `aspect-ratio: 1.5`        |     80 |
   | `aspect-ratio: auto 3 / 1` |     40 |
   | `aspect-ratio: 3 / 1 auto` |     40 |
   | `aspect-ratio: auto`       |     24 (i.e. the content height) |
   | `aspect-ratio: 0 / 1`      |     24 (i.e. the content height) |

   Two readings that are decisions rather than parsing:

   `auto <ratio>` is treated as the bare ratio. The `auto` keyword means
   \"use the element's NATURAL ratio if it has one, and this one
   otherwise\", and a natural ratio belongs to a replaced element whose
   intrinsic size a browser learns by decoding the resource. This engine
   reads an `<img>`'s size from its `width`/`height` attributes (see
   presentational-size) and never decodes anything, so there is no
   competing natural ratio for the keyword to prefer -- which is a scope
   cut, not an equivalence: an `<img>` with a `width` attribute, a real
   intrinsic ratio, and `aspect-ratio: auto 3/1` would follow the
   intrinsic one in a browser and the declared one here.

   A degenerate ratio -- a zero or negative term on either side -- is not
   a ratio and is dropped rather than divided by: measured,
   `aspect-ratio: 0 / 1` lays out at its content height, exactly as
   `auto` does."
  [st]
  (let [v (:aspect-ratio st)]
    (cond
      (number? v) (when (pos? v) (double v))
      (string? v)
      (let [terms (->> (str/split (str/replace (str/lower-case v) #"auto" " ") #"/")
                       (map str/trim)
                       (remove str/blank?)
                       (mapv #(parse-dbl % nil)))]
        (when (every? #(and (some? %) (pos? %)) terms)
          (case (count terms)
            1 (first terms)
            2 (/ (first terms) (second terms))
            nil)))
      :else nil)))

(defn- clamp-height
  "Real CSS min-height/max-height apply to a box's FINAL height regardless
   of where that height came from -- an explicit :height (resolve-height)
   or content-driven auto-sizing (every caller's own fallback formula) --
   unlike resolve-width's min/max clamp, which resolve-width applies
   internally since width always resolves to SOME number via its
   avail-width fallback. Each of the four call sites that compute a
   node's own final height (layout-block/layout-form-control directly,
   layout-flex/layout-grid via layout-node's own box-h destructuring)
   wraps its own already-or'd height value with this, so min/max apply
   uniformly whether that value came from an explicit :height or a
   content-driven fallback."
  ;; `basis` is the containing block's content HEIGHT (nil when that height
  ;; is not definite -- see percentage-of).
  ([st height] (clamp-height st height nil))
  ([st height basis]
   (let [height (if-let [mn (length-or-percentage (:min-height st) basis)] (max height mn) height)
         height (if-let [mx (length-or-percentage (:max-height st) basis)] (min height mx) height)]
     height)))

(defn- used-block-height
  "A BLOCK box's own border-box height when its `height` is definite, else
   nil -- the block-axis counterpart of the content-box correction
   resolve-width already makes in the inline axis.

   With the default `box-sizing: content-box` a declared `height` is the
   CONTENT height, so padding and border add OUTSIDE it: measured in Brave,
   `div{height:100px;padding:10px}` is 120px tall and lays its children out
   in 100. This engine used the declared height AS the border box in both
   modes, so the same box was 100 tall with 80 of content -- the identical
   bug resolve-width's docstring describes in the inline axis, which had
   been fixed there and not here.

   Deliberately NOT applied in layout-flex/layout-grid/layout-form-control,
   which read `resolve-height` straight into their own `node-h`: those three
   have their own open sizing gaps (see the conformance corpus's flex and
   grid groups) and correcting one axis of one of them in passing would mean
   claiming a fix nothing in the corpus measures."
  ;; ...and NOT applied to a height the layout itself solved and injected
  ;; (`:height/used`): that number is already a border box -- a stretched
  ;; flex item's line cross size, an absolute box's `top`+`bottom` solve --
  ;; so adding the insets to it would grow the box past the size that was
  ;; just computed FOR it. Measured as a 24px `<button>` in
  ;; `page/form-row` where the browser and the flex line both say 21.
  [st basis]
  (if-let [used (:height/used st)]
    used
    (when-let [declared (resolve-height st basis)]
      (if (= "border-box" (:box-sizing st))
        declared
        (+ declared (declared-inset-side st :top) (declared-inset-side st :bottom))))))

(defn- aspect-ratio-block-height
  "The BORDER-BOX height an `aspect-ratio` derives from an already-resolved
   border-box width, or nil when there is no usable ratio.

   The ratio governs the box `box-sizing` names -- so with the default
   `content-box` it relates the CONTENT width to the CONTENT height and
   the padding/border sit outside both, and with `border-box` it relates
   the border boxes directly. Measured in Brave 151, 2026-08-05, on
   `width: 120px; aspect-ratio: 3/1; padding: 10px`: 140x60 with
   `content-box` (content 120x40) and 120x44 with `border-box`. The insets
   are the DECLARED ones, the same halves resolve-width and
   used-block-height already convert their own declared lengths through.

   This is the ratio's answer alone. It is not the used height: the caller
   takes the LARGER of this and the content-driven height, because a
   ratio-sized box with `overflow: visible` has an automatic minimum size
   in the ratio-dependent axis (CSS Sizing 4) and a browser lets content
   push it past the ratio rather than overflow. Measured the same day:
   `width: 40px; aspect-ratio: 3/1` around a string that wraps to six
   lines is 144 tall in Brave, not the 13 the ratio asks for -- and the
   `border-box` case above is 44 rather than 40 for the same reason, its
   ratio height of 40 leaving only 20 for a 24px line."
  [st border-box-w]
  (when-let [r (aspect-ratio st)]
    (if (= "border-box" (:box-sizing st))
      (long (/ border-box-w r))
      (let [ix (+ (declared-inset-side st :left) (declared-inset-side st :right))
            iy (+ (declared-inset-side st :top) (declared-inset-side st :bottom))]
        (+ iy (long (/ (max 0 (- border-box-w ix)) r)))))))

(defn- aspect-ratio-block-width
  "The mirror of aspect-ratio-block-height, for the case a browser solves
   the other way round: `width` is auto and `height` is definite, so the
   ratio supplies the width.

   A block-level box with `width: auto` normally fills its containing
   block, which is why this needs its own call site rather than falling
   out of resolve-width -- and why it returns nil unless BOTH halves hold
   (no author width, a definite height, a usable ratio), leaving the
   fill-the-container answer untouched everywhere else. Measured in Brave
   151, 2026-08-05: `height: 60px; aspect-ratio: 3/1` inside a 400px
   parent is 180 wide, not 400, as a block AND as an inline-block.

   `basis` is the containing block's content height, for the same reason
   used-block-height takes one: the declared height may be a percentage."
  [st basis]
  (when (and (nil? (:width st)) (nil? (:height/used st)))
    (when-let [r (aspect-ratio st)]
      (when-let [h (used-block-height st basis)]
        (if (= "border-box" (:box-sizing st))
          (long (* h r))
          (let [ix (+ (declared-inset-side st :left) (declared-inset-side st :right))
                iy (+ (declared-inset-side st :top) (declared-inset-side st :bottom))]
            (+ ix (long (* (max 0 (- h iy)) r)))))))))

(defn- definite-content-height
  "The CONTENT height this box hands its children as the basis a percentage
   height resolves against, or nil when this box's own height is not
   definite -- an auto-height parent, which is what makes a child's
   percentage height `auto` in real CSS (see percentage-of).

   Deliberately the same subtraction layout-block itself performs
   (`inset-side` on both block edges) rather than an independently derived
   'true' content height: the basis a child resolves against and the box the
   child is actually laid out in have to be the same number.

   That used to be a choice between two wrong answers -- inset-side omitted
   the border for a content-box element, and this preferred to be wrong the
   same way layout-block was rather than differently right. inset-side
   counts the border in both box-sizing modes now (2026-08-05), so the
   consistent answer and the correct one are the same one, and
   `position/absolute-containing-block-is-the-padding-box` -- the case that
   measured the gap in this axis first -- is clean."
  [st basis]
  (when-let [h (used-block-height st basis)]
    (max 0 (- (clamp-height st h basis) (inset-side st :top) (inset-side st :bottom)))))

(defn- collapse-margins
  "Real CSS's own rule for collapsing adjacent vertical margins, negatives
   included: the collapsed margin is the largest POSITIVE margin plus the
   most negative one -- which reduces to `max` when every margin is positive
   (this file's original rule) and to `min` when every one is negative.

   `max` alone silently dropped every negative margin on the floor, because
   a negative never wins a `max` against the 0 that stands in for 'no margin
   here'. Measured in Brave on `<div>first</div><div style=\"margin-top:
   -8px\">pulled</div>`: the second block sits at y=12 in a 32px-tall parent
   there, and sat at y=20 in a 40px one here -- the cascade had the -8 all
   along (`box/negative-margin-pulls-up` is clean on the computed-style
   axis) and layout was the half that discarded it."
  [& ms]
  (let [ms (remove nil? ms)]
    (+ (reduce max 0 (filter pos? ms))
       (reduce min 0 (filter neg? ms)))))

(def ^:private line-height-multiplier-pattern
  "A `line-height` written as a multiple of the element's own `font-size`:
   a bare unitless number (`1.5`), an `em` length (`1.5em`), or a
   percentage (`150%`). All three end up multiplying the same font-size --
   they differ only in what INHERITS (see line-height-factor) and, for the
   percentage, by a factor of a hundred."
  #"([+-]?(?:\d+\.?\d*|\.\d+))(em|%)?")

(defn- line-height-multiplier
  "The number `raw` multiplies this element's own `font-size` by, or nil
   when it is not one of the three multiplying forms at all (`normal`, a
   `rem`/`vh`/`ch` length, anything unparseable).

   This exists instead of `parse-dbl` because parse-dbl does not answer the
   same question on both platforms: it is `Double/parseDouble` under Clojure,
   which REJECTS a trailing unit, and `js/parseFloat` under ClojureScript,
   which silently ignores one. So `line-height: 1.5em` resolved to the
   theme default on the JVM and to 1.5 x font-size under nbb -- the same
   markup, two answers, and the conformance harness (which runs on nbb) only
   ever saw the second. `150%` was worse in the same way: parseFloat says
   150, so the browser's 1.5 x font-size came out as 150 x font-size.

   `em` here is the element's own font-size, which is what `em` means for
   `line-height` (CSS 2.1 10.8.1 -- unlike `font-size: 1.5em`, which is
   relative to the PARENT). `rem` and the viewport units are deliberately
   NOT handled: this file has no root font-size or viewport in `inherited`
   to resolve them against, so they fall through to `normal` rather than
   being multiplied by the wrong length."
  [raw]
  (when-let [[_ n unit] (re-matches line-height-multiplier-pattern (str/trim (str raw)))]
    (when-let [n (parse-dbl n nil)]
      (if (= "%" unit) (/ n 100.0) n))))

(defn- resolve-line-height
  "Real CSS `line-height` is either an absolute length (`24`/`24px`, already
   coerced to a plain number by `cssom.core/parse-style-value` before this
   ever runs) or -- far more common in real-world CSS -- a bare UNITLESS
   number (`1.5`), a real per-element MULTIPLIER of that same element's own
   `font-size`, not a literal pixel count. `parse-style-value` only ever
   coerces a value to a number when it is ENTIRELY a bare INTEGER or an
   integer `px` length (see its own docstring) -- a decimal like `1.5` has
   no unit to strip and isn't a whole integer either, so it survives to
   here as the untouched STRING `\"1.5\"`, letting this fn tell the two
   real forms apart by type alone: a number is already resolved pixels;
   a string is a multiplier of this element's own font-size --
   line-height-multiplier reads the three forms that are one (`1.5`,
   `1.5em`, `150%`) and returns nil for everything else, which falls back
   to the theme default the exact same way an absent `line-height` always
   has. What this fn resolves is what THIS element uses; what a descendant
   that declares none inherits is line-height-factor's separate question,
   and the two answers differ (only a UNITLESS value inherits as a ratio).
   Before this fix, `line-height` was
   read from `node-style` NOWHERE at all -- every single line of text
   anywhere on any page used the SAME fixed `(:line-height theme)` pixel
   constant regardless of any real author CSS, confirmed via direct REPL
   reproduction: `line-height: 60`/`line-height: 100`/no declaration at
   all produced the identical box height."
  ([raw font-size theme-default] (resolve-line-height raw font-size theme-default false))
  ([raw font-size theme-default inherited-explicit?]
  (let [;; CSS `line-height: normal` is not a fixed number of pixels: it is
        ;; the font's own natural leading, ~1.2x the font size in every real
        ;; browser. This engine used the theme's flat pixel default for
        ;; every element regardless of size, which is fine while everything
        ;; is 14px and silently broken the moment anything is not: with UA
        ;; heading sizes in place an `<h1>` at 28px got a 20px line box, so
        ;; its own text overflowed its box and the NEXT block painted on top
        ;; of it -- caught by the conformance harness the same hour the
        ;; heading sizes landed (an `<h1>` and the paragraph after it were
        ;; clustered onto one line).
        ;; ...but an INHERITED explicit line-height is a real declared
        ;; length and must win over that natural leading: a container
        ;; saying `line-height: 20px` means 20px for the 28px heading
        ;; inside it too, however cramped. Confirmed against the browser --
        ;; without this distinction an `<h1>` inside such a container
        ;; reported a 33px box where Chrome reports 20px.
        normal (if inherited-explicit?
                 (or theme-default (long (* 1.2 font-size)))
                 (max (or theme-default 0) (long (* 1.2 font-size))))]
    (cond
      (number? raw) (long raw)
      (string? raw) (if-let [multiplier (line-height-multiplier raw)]
                      (long (* multiplier font-size))
                      normal)
      :else normal))))

(def ^:private unitless-line-height-pattern
  "A `line-height` value that is a bare NUMBER with no unit at all, which
   real CSS reads as a per-element ratio rather than a length. Deliberately
   stricter than `parse-dbl`, which is what makes this a different question
   from resolve-line-height's own: `parse-dbl` is `js/parseFloat` under
   ClojureScript, and parseFloat answers 1.5 for `\"1.5em\"` and 150 for
   `\"150%\"` -- both LENGTHS, which resolve once against the declaring
   element and then inherit as the resolved pixel count. Only a value this
   pattern matches whole may travel down the tree as a ratio."
  #"[+-]?(?:\d+\.?\d*|\.\d+)")

(defn- line-height-factor
  "The RATIO a descendant with no `line-height` of its own inherits, or nil
   when what inherits is a resolved length instead.

   Real CSS computes `line-height` differently depending on the form it was
   written in, and the difference is only visible on a descendant with a
   different `font-size`: a UNITLESS `1.5` inherits as the NUMBER, so each
   element multiplies it by its OWN font-size, while `1.5em`/`21px` resolve
   at the declaring element and inherit as that one pixel count. Measured
   in Brave: `<div style=\"line-height: 1.5\"><p style=\"font-size: 24px\">`
   reports a 36px box for the `<p>` (1.5 x 24) and the same markup with
   `1.5em` reports 21 (1.5 x the DIV's 14) -- and this engine reported 21
   for both, because `inherited` only ever carried the resolved pixels.

   `raw` is the element's OWN cascade-resolved `:line-height`:
   - absent -> whatever ratio was already in force keeps travelling;
   - a unitless number (`\"1.5\"`, and `\"2\"` -- `cssom.core` deliberately
     keeps a unitless INTEGER as a string for exactly this distinction, see
     its `line-height` case) -> a new ratio from here down;
   - anything else -- a number (an absolute px length), `1.5em`, `150%`,
     `normal`, an unparseable value -> nil, i.e. a length (or the font's own
     natural leading) inherits and no ratio is in force below this element.

   SCOPE CUT, and it is the reason this is a separate function from
   resolve-line-height rather than a branch inside it: this decides only
   what INHERITS. What the declaring element itself resolves to is still
   resolve-line-height's `parse-dbl` branch, which treats `1.5em` as
   1.5 x font-size (numerically right, since that is what `em` means here)
   and `150%` as 150 x font-size (wrong -- `150%` is 1.5 x font-size). That
   percentage bug predates this function, no case in the corpus measures it,
   and multiplying by the wrong factor is not made better or worse by
   whether the result then inherits as a length."
  [raw inherited-factor]
  (cond
    (nil? raw) inherited-factor
    (and (string? raw) (re-matches unitless-line-height-pattern (str/trim raw)))
    (parse-dbl raw nil)
    :else nil))

(defn- inherited-line-height
  "The `line-height` in force for an element that declares none of its own,
   in pixels, or nil when nothing is in force -- the value every caller
   used to read straight off `(:line-height inherited)`.

   The one thing it adds is line-height-factor's ratio: when the value in
   force is a unitless one, it is re-multiplied by THIS element's own
   `font-size` instead of handing down the pixel count the ancestor
   resolved for itself. Callers pass their own already-resolved font-size,
   because that is exactly the number real CSS multiplies."
  [inherited font-size]
  (if-let [f (:line-height/factor inherited)]
    (long (* f font-size))
    (:line-height inherited)))

(defn- leading-ascent
  "How far ABOVE the baseline one inline box reaches inside its line box:
   the font's own ascent plus HALF THE LEADING, floored.

   This is the single rule the whole inline vertical model is built from,
   and it is stated once here because four places need to agree about it
   exactly (the line box's own height, where each inline element's box
   lands in it, an atomic inline's internal baseline, and a flex item's
   first-line baseline). CSS 2.1 10.8.1: an inline box occupies
   `[baseline - ascent - halfLeading, baseline + descent + halfLeading]`
   where `halfLeading = (line-height - (ascent + descent)) / 2`. Leading
   can be NEGATIVE -- a declared `line-height` smaller than the font's own
   content area makes the box overflow its line rather than grow it.

   The FLOOR is not decoration: it is what a real engine does, and leaving
   it out put every inline element's box a pixel low. Measured in Brave, a
   14px monospace line (ascent 12, descent 3) at `line-height: 20px` has
   half-leading 2.5 and reports its text at y=2 -- i.e. a baseline 14px
   down, not 14.5. The descent side absorbs the rounding
   (`descent' = line-height - ascent'`, so the two still sum to exactly
   the line-height), which is why this returns only the ascent half and
   every caller derives the other from it.

   Reference points, all measured in Brave at `line-height: 20px`:
   14px/normal (12+3) -> 14, 14px/bold (14+4) -> 15, 24px (21+5) -> 18
   (its box starts 3px ABOVE the line top), 10px (9+2) -> 13."
  [ascent descent line-height]
  ;; `long`, not the bare double `Math/floor` hands back on the JVM: this
  ;; number is a pixel offset that flows straight into `:x`/`:y` draw-op
  ;; coordinates, and a 8.0 where every other box says 8 is a difference
  ;; downstream consumers (and this repo's own tests) can see.
  (long (Math/floor (+ ascent (/ (- line-height (+ ascent descent)) 2)))))

(defn- translate-ops
  [dx dy ops]
  (mapv (fn [op]
          (cond-> op
            (contains? op :x) (update :x + dx)
            (contains? op :y) (update :y + dy)))
        ops))

(defn- default-bg
  "User-agent-stylesheet-style background default: buttons get a raised
   default fill, main/span stay transparent, everything else gets the
   theme's panel background -- unless an explicit background/background-color
   style already won."
  [tag st theme]
  (or (:background st)
      (case tag
        :button (:button-bg theme)
        :main nil
        :span nil
        (:bg theme))))

(defn- border-ops
  [st x y w h opacity]
  (when (pos? (:border-width st))
    (let [bw (:border-width st)
          color (:border-color st)
          base {:draw/op :rect :border? true :color color :opacity opacity}]
      [(assoc base :edge :top :x x :y y :w w :h bw)
       (assoc base :edge :right :x (- (+ x w) bw) :y y :w bw :h h)
       (assoc base :edge :bottom :x x :y (- (+ y h) bw) :w w :h bw)
       (assoc base :edge :left :x x :y y :w bw :h h)])))

(defn- box-shadow-ops
  "Real CSS `box-shadow` (offset + spread + color -- blur-radius is
   parsed by `cssom.core/expand-box-shadow-shorthand` but not rendered
   here, and `inset` is not supported at all, an honest documented
   scope-cut: no blur/glow rendering primitive exists in this engine or
   its real hosts). Emits a single extra `:rect` op, the element's own
   border box expanded outward on all four sides by `:box-shadow-spread`
   (mirroring `outline-ops`'s own real, legal-negative-value `gap`
   expansion below -- a positive spread grows the shadow past the box's
   own edges, a negative spread shrinks it, before the `:box-shadow-x`/
   `:box-shadow-y` offset is applied), reusing the exact `:rect` draw-op
   every real host already paints as a plain quad -- no new dom-gpu
   primitive needed, the same reuse `border-ops` above already
   established. Callers place this BEFORE the element's own background/
   border rects (real CSS paints a non-inset box-shadow BEHIND the
   element's own box). The literal string \"none\" is defensively
   treated the same as absence -- `expand-box-shadow-shorthand` never
   itself produces that value (`none`/blank resolves to an empty map,
   since box-shadow isn't inherited and has no ancestor value to
   cancel), but a direct `:style/box-shadow-color \"none\"` write
   bypassing that shorthand parser is still a real, reachable shape (the
   same defensive check `text-shadow`'s own shadow-op emission already
   makes for its own `:text-shadow-color`)."
  [st x y w h opacity]
  (when (and (:box-shadow-color st) (not= "none" (:box-shadow-color st)))
    (let [dx (or (:box-shadow-x st) 0)
          dy (or (:box-shadow-y st) 0)
          spread (or (:box-shadow-spread st) 0)]
      [{:draw/op :rect :box-shadow? true :color (:box-shadow-color st) :opacity opacity
        :x (+ x dx (- spread)) :y (+ y dy (- spread)) :w (+ w (* 2 spread)) :h (+ h (* 2 spread))}])))

(defn- outline-ops
  "Real CSS `outline` -- a non-layout-affecting ring painted OUTSIDE the
   border box (unlike `border`, which is part of the box itself),
   commonly used for focus rings/accessibility. Previously read NOWHERE
   at all, confirmed via direct REPL reproduction before touching source.
   Mirrors `border-ops`'s exact 4-edge-`:rect` shape and corner-handling
   convention, just computed against a VIRTUAL box expanded outward by
   `:outline-offset` + the outline's own thickness, rather than drawn
   inward from the real box -- a real, legal negative `outline-offset`
   naturally pulls the ring back toward (or even inside) the border, no
   special-casing needed, the arithmetic alone handles it. Like
   `border-ops`, `:outline-style` is parsed but not consulted here --
   gating purely on a positive `:outline-width` matches this engine's
   own existing border-style simplification (parsed but never consulted
   for suppression, so e.g. `outline-style: none` alone, with an
   explicit nonzero width, is not honored -- an honest, documented
   scope-cut consistent with border's own)."
  [st x y w h opacity]
  (when (pos? (:outline-width st))
    (let [thickness (:outline-width st)
          offset (:outline-offset st)
          color (:outline-color st)
          gap (+ offset thickness)
          ox (- x gap)
          oy (- y gap)
          total-w (+ w (* 2 gap))
          total-h (+ h (* 2 gap))
          base {:draw/op :rect :outline? true :color color :opacity opacity}]
      [(assoc base :edge :top :x ox :y oy :w total-w :h thickness)
       (assoc base :edge :right :x (- (+ ox total-w) thickness) :y oy :w thickness :h total-h)
       (assoc base :edge :bottom :x ox :y (- (+ oy total-h) thickness) :w total-w :h thickness)
       (assoc base :edge :left :x ox :y oy :w thickness :h total-h)])))

(defn- absolute?
  "True for both `position: absolute` and `position: fixed` -- real bug
   this guards: `fixed` was previously not recognized at all, so a
   `position: fixed` child fell all the way through to being treated
   like `position: static` -- it stayed in normal flow and, unlike real
   CSS, still occupied layout space and pushed its following siblings
   down. Confirmed via direct REPL reproduction: two block siblings, the
   first `position: fixed`, and the second landed at y=28 (pushed down
   by the first's own height) instead of y=4 -- the exact same two-
   sibling layout with `position: absolute` on the first correctly
   leaves the second at y=4, unaffected.

   `fixed` shares ALL of `absolute`'s out-of-flow machinery -- it takes
   no part in block flow, it keeps a static position for the axes with
   no offset, and it is placed by layout-absolute-children -- and
   differs in exactly one thing: its containing block is the VIEWPORT,
   not an ancestor. That difference lives in layout-absolute-children,
   which reads the viewport off the theme; see its own comment for the
   Brave measurements and for the two things it deliberately does not
   model (there is no scroll position here, so a fixed box does not stay
   put as a page scrolls, and `bottom`/a `%` block offset need a
   viewport HEIGHT the host may not have supplied).

   `position: sticky` is deliberately NOT included here -- its
   unscrolled default position is legitimately identical to normal
   flow, so leaving it in-flow is correct, not a gap, for a rendering
   engine with no real scroll-position-dependent re-layout."
  [theme child]
  (and (map? child) (contains? #{"absolute" "fixed"} (:position (node-style child theme)))))

;; ---- flexbox main-axis distribution / cross-axis alignment ----

(defn- place-main-axis
  [justify sizes gap container-size]
  (let [n (count sizes)]
    (cond
      (zero? n) []

      (= justify "space-between")
      ;; Real CSS reserves `gap` as a MINIMUM inter-item spacing even under
      ;; space-between -- it is not just a fallback for when free space runs
      ;; out, it is added to whatever free space distribution would already
      ;; produce. Previously `total`/`free` here had no gap term at all
      ;; (unlike the center/flex-end branch below, which already correctly
      ;; adds `gap * (n - 1)`), so a nonzero `gap` was silently ignored
      ;; whenever the container wasn't dramatically larger than the summed
      ;; item sizes -- confirmed via a direct REPL reproduction: with
      ;; sizes [90 90 90] gap 20 container 300, this produced the exact
      ;; same offsets as gap 0. `step` (the per-item main-axis advance)
      ;; must carry the base `gap` PLUS whatever additional space
      ;; space-between distributes beyond that floor.
      (let [total (+ (reduce + 0 sizes) (* gap (max 0 (dec n))))
            free (max 0 (- container-size total))
            step (+ gap (if (> n 1) (/ free (dec n)) 0))]
        (loop [i 0 pos 0 offsets []]
          (if (= i n)
            offsets
            (recur (inc i) (+ pos (nth sizes i) step) (conj offsets pos)))))

      (contains? #{"center" "flex-end"} justify)
      (let [total (+ (reduce + 0 sizes) (* gap (max 0 (dec n))))
            free (max 0 (- container-size total))
            lead (if (= justify "center") (quot free 2) free)]
        (loop [i 0 pos lead offsets []]
          (if (= i n)
            offsets
            (recur (inc i) (+ pos (nth sizes i) gap) (conj offsets pos)))))

      ;; space-around/space-evenly -- the two other spec-mandated (CSS
      ;; Flexible Box Layout SS8.3) distribution keywords, previously
      ;; missing from this cond entirely and silently falling through to
      ;; :else's flush-start packing, identical to flex-start. Confirmed
      ;; via a direct REPL reproduction before touching source: sizes
      ;; [50 50 50] gap 0 container 300 under space-around/space-evenly
      ;; produced the exact same offsets as flex-start. Both branches
      ;; reserve `gap` as the same MINIMUM inter-item floor the
      ;; space-between branch above already established (added to, not
      ;; replaced by, the distributed free space): space-around gives
      ;; each item a full `free/n` share split half-lead/half-trail (so
      ;; adjacent items' half-shares combine into one full share between
      ;; them, on top of `gap`); space-evenly divides free space into
      ;; `n+1` equal gaps (before the first item, between each pair, and
      ;; after the last), also on top of `gap` between items. For a
      ;; single item, both correctly reduce to centering (one item has
      ;; exactly one lead and one trail gap of equal size either way),
      ;; matching real CSS.
      (= justify "space-around")
      (let [total (+ (reduce + 0 sizes) (* gap (max 0 (dec n))))
            free (max 0 (- container-size total))
            per-item (/ free n)
            step (+ gap per-item)]
        (loop [i 0 pos (/ per-item 2) offsets []]
          (if (= i n)
            offsets
            (recur (inc i) (+ pos (nth sizes i) step) (conj offsets pos)))))

      (= justify "space-evenly")
      (let [total (+ (reduce + 0 sizes) (* gap (max 0 (dec n))))
            free (max 0 (- container-size total))
            unit (/ free (inc n))
            step (+ gap unit)]
        (loop [i 0 pos unit offsets []]
          (if (= i n)
            offsets
            (recur (inc i) (+ pos (nth sizes i) step) (conj offsets pos)))))

      :else
      (loop [i 0 pos 0 offsets []]
        (if (= i n)
          offsets
          (recur (inc i) (+ pos (nth sizes i) gap) (conj offsets pos)))))))

(defn- mirror-main-offsets
  "Turns the main-axis offsets `place-main-axis` produced into the offsets
   a REVERSED direction (`row-reverse`/`column-reverse`) puts the same
   items at: each item is reflected about the centre of the container's
   main size, so the first item lands at the main-END edge and the line
   runs back towards the start.

   Reflecting the finished offsets, rather than reversing the item vector
   before placing it, is what keeps `justify-content` correct at the same
   time -- real CSS's `flex-start`/`flex-end` are defined against the
   FLEX-relative axis, so a reversed row packed `flex-end` packs against
   the physical LEFT. Both come out of one reflection. Measured in Brave
   at 800px: three 7px items in a 300px `row-reverse` sit at 293/286/279
   (this reflection of 0/7/14), the same row under `justify-content:
   flex-end` at 14/7/0 (the reflection of 279/286/293), and with a 10px
   gap at 293/276/259.

   `gap` needs no separate treatment: it is already inside the offsets
   being reflected."
  [offsets sizes container-main]
  (mapv (fn [off sz] (- container-main off sz)) offsets sizes))

(defn- auto-main-margins
  "A flex item's MAIN-axis `auto` margins, as `[leading trailing]` 0/1
   flags. Read from the RAW cascade value rather than node-style, whose
   `:margin-left`/`:margin-top` are parse-int'd and so report `auto` as
   the same 0 an undeclared margin gets."
  [column? child]
  (if (map? child)
    [(if (= "auto" (style child (if column? :margin-top :margin-left))) 1 0)
     (if (= "auto" (style child (if column? :margin-bottom :margin-right))) 1 0)]
    [0 0]))

(defn- place-main-axis-auto-margins
  "place-main-axis's replacement for a line that has `auto` main-axis
   margins on it: every bit of positive free space goes to those margins,
   split equally, and the items pack flush otherwise.

   `margin-left: auto` is how the real web pushes one item of a toolbar to
   the far end, and it OUTRANKS justify-content -- real CSS gives the auto
   margins the free space first and leaves justify-content nothing to
   distribute, which is why this replaces that call rather than adding to
   it. Measured in Brave, `left` and a `margin-left: auto` `right` in a
   300px row sit at x=0 and x=265; this engine packed them flush at 0 and
   28, because a margin of `auto` parse-int'd to zero and disappeared.

   Only the MAIN axis: a cross-axis `auto` margin centres the item within
   its line, which is not implemented -- such an item keeps its
   align-items/align-self placement."
  [auto-margins sizes gap container-main]
  (let [total (+ (reduce + 0 sizes) (* gap (max 0 (dec (count sizes)))))
        n-auto (reduce + 0 (apply concat auto-margins))
        share (if (pos? n-auto) (/ (max 0 (- container-main total)) n-auto) 0)]
    (first (reduce (fn [[offs pos] [[lead trail] sz]]
                     (let [pos (+ pos (* lead share))]
                       [(conj offs pos) (+ pos sz (* trail share) gap)]))
                   [[] 0]
                   (map vector auto-margins sizes)))))

(defn- item-cross-align
  "The cross-axis alignment that governs ONE flex item: its own
   `align-self` when it names one, otherwise the container's
   `align-items`.

   Real CSS's `align-self: auto` -- the initial value -- means exactly
   'defer to the container', which is why an authored `auto` falls through
   here rather than being treated as a value of its own. A bare text-string
   flex item has no style at all and always takes the container's.

   Nothing read `align-self` before this: measured in Brave, a row with
   `align-items: flex-start` and two items overriding it (`flex-end`,
   `center`) put them at y=40 and y=20 in a 60px container, where this
   engine left all three at y=0 -- the conformance corpus scored it as one
   line where the browser has three."
  [theme st child]
  (let [self (when (map? child) (:align-self (node-style child theme)))]
    (if (and self (not= "auto" self)) self (:align-items st))))

(defn- flex-order
  "A flex item's `order`, which is the position it takes in the flex
   line rather than its document position (CSS Flexible Box Layout §5.4).
   A bare text-string item, and any element that never declares one, is 0."
  [theme child]
  (if (map? child) (:order (node-style child theme)) 0))

(defn- order-flex-items
  "Re-sorts a flex container's in-flow children into `order`-modified
   document order: ascending `order`, ties broken by the original document
   position, which is real CSS's own rule.

   Sorted on the pair rather than relying on a stable sort, so the tie
   rule is stated by the code instead of by a host's sort implementation
   (Clojure's is stable, ClojureScript's delegates to the platform).

   This reorders the children ONCE, before anything is measured, so the
   new order flows through placement AND paint order together -- which is
   also what real CSS does (`order` changes the painting order of flex
   items too, not only their positions). Nothing read `order` before:
   measured in Brave, `order: 2` and `order: 1` on two items swapped them
   to `second first`, where this engine kept document order."
  [theme children]
  (->> children
       (map-indexed vector)
       (sort-by (fn [[i child]] [(flex-order theme child) i]))
       (mapv second)))

(defn- flip-cross-align
  "The cross-axis alignment `wrap-reverse` turns `align` into: that keyword
   flips the cross axis end-for-end, so `flex-start` means the far edge and
   `flex-end` the near one, while `center` and `stretch` are symmetric and
   unaffected.

   `baseline` is deliberately NOT flipped: real CSS answers a reversed
   cross axis with `last baseline` alignment, which needs each item's LAST
   line box rather than its first, and flex-item-baseline only knows the
   first. Left as first-baseline (the unflipped behaviour) rather than
   guessed at."
  [align]
  (case align
    "flex-start" "flex-end"
    "start" "end"
    "flex-end" "flex-start"
    "end" "start"
    align))

(defn- cross-offset
  [align child-cross container-cross]
  (case align
    ("center" "safe center" "unsafe center") (quot (- container-cross child-cross) 2)
    ("flex-end" "end" "self-end") (- container-cross child-cross)
    0))

(defn- stretch-eligible-child?
  "True when `child` is a real element (not a bare text-string flex item --
   see measure-child's own identical map? check) with no explicit cross-
   dimension of its own (`:height` for a row container, `:width` for a
   column container) whose EFFECTIVE cross alignment (its own `align-self`,
   falling back to the container's `align-items` -- see item-cross-align)
   resolves to `\"stretch\"` (node-style's own real-CSS default when
   unauthored -- see node-style). Real CSS's align-items default -- stretch
   -- was never actually implemented as a SIZE change here; cross-offset
   above only ever
   REPOSITIONS a child within the cross axis, so `\"stretch\"` (not handled
   by cross-offset's own case) silently fell through to the same zero-
   offset, zero-resize behavior as `\"flex-start\"`, confirmed via a direct
   REPL reproduction before touching source: two 300px-wide flex-row items,
   one with an explicit height of 40 and one with none, and NO align-items
   declared (real CSS's own default is stretch) -- the auto-height item
   stayed at its own tiny 8px content height instead of stretching to match
   its 40px sibling, exactly like Chrome/Firefox never would."
  [theme column? st child]
  (and (map? child)
       (= "stretch" (item-cross-align theme st child))
       (nil? (style child (if column? :width :height)))))

(defn- force-cross-size
  "Returns `child` with a synthetic explicit cross-dimension (`:height` for
   a row container, `:width` for a column container) of `px` injected into
   its own style attrs, for stretch-eligible-child? to re-measure through
   the EXACT same explicit-height/explicit-width code path an authored
   `style=\"...\"` value already takes (layout-block's/layout-form-control's
   own `explicit-h`/resolve-width, confirmed via direct REPL check: an
   explicit :height on a plain block or form-control child becomes its
   final box height with no further adjustment) -- not a piecemeal after-
   the-fact rect patch, so background/border/outline/clip/absolutely-
   positioned-descendant geometry all come out correct for the new size.
   Known, disclosed imprecision: a child that is ITSELF display:flex/grid
   treats its own explicit cross-dimension as CONTENT size with its own
   inset (padding/border) added on top afterward (a pre-existing asymmetry
   in layout-flex/layout-grid's own explicit-height handling, confirmed via
   a direct REPL check: a nested flex child with explicit height 40 and
   padding 10 resolves to box height 60, not 40) -- unlike a plain block or
   form-control child, where explicit height IS the final box height
   outright. This means a nested flex/grid child stretched by this fix can
   overshoot cross-content by its own 2x inset; still strictly better than
   the previous zero-resize behavior, and a narrower, honestly-disclosed
   scope-cut rather than solving that separate, pre-existing asymmetry
   here too."
  [column? px child]
  (if column?
    (assoc-in child [:attrs :style/width] px)
    ;; The cross size of a ROW line is a BORDER-box height (every base size
    ;; here is a measured `(:h (:box m))`), so it is injected as the solved
    ;; used height rather than as a `height` declaration -- box-sizing has
    ;; already been applied to the number by whatever produced it, and
    ;; layout-block would otherwise apply it a second time. The same reason
    ;; force-main-width pins `box-sizing: border-box` on its own injection.
    (assoc-in child [:attrs :kotoba/used-height] px)))

(defn- force-main-width
  "force-cross-size's MAIN-axis counterpart, for a ROW container: injects
   the main size flex-grow/flex-shrink actually resolved for this item as
   a synthetic explicit `:width`, plus `box-sizing: border-box` so that
   value is read as the BORDER box -- which is what the flex algorithm
   resolved, since every base size here is a measured `(:w (:box m))`.

   Re-laying the item out against a narrower AVAILABLE width is not enough
   on its own and was the bug this closes: an item that declares its own
   `width` resolves to that width no matter how little room it is given,
   so `flex-shrink` moved the following items but left the shrunk one at
   its declared size, overlapping its neighbour. Measured in Brave, two
   150px items in a 200px row are 100px each; this engine drew both at
   150 while placing the second at x=100.

   Only rows: a COLUMN container's main axis is height, and this engine
   still places column items at their grown/shrunk offsets without
   resizing them (pre-existing, and unreachable for shrink -- an
   auto-height column has `avail-main` 0, which disables distribution
   entirely)."
  [child px]
  (-> child
      (assoc-in [:attrs :style/width] px)
      (assoc-in [:attrs :style/box-sizing] "border-box")))

(defn- pack-rows
  "Greedily packs measured children (indices) into rows that fit within
   container-main; row-wrapping is only implemented for flex-direction:row."
  [main-sizes gap container-main]
  (loop [idx 0 cur [] cur-size 0 rows []]
    (if (= idx (count main-sizes))
      (if (seq cur) (conj rows cur) rows)
      (let [sz (nth main-sizes idx)
            next-size (if (seq cur) (+ cur-size gap sz) sz)]
        (if (and (seq cur) (> next-size container-main))
          (recur idx [] 0 (conj rows cur))
          (recur (inc idx) (conj cur idx) next-size rows))))))

;; ---- grid track-size parsing / sizing ----

(defn- paren-split
  "Splits `s` into trimmed, non-blank segments wherever `delim?` matches a
   character AND paren nesting depth is 0 -- so a delimiter that appears
   inside a nested repeat(...)/minmax(...) argument list doesn't split that
   call apart (e.g. splitting \"repeat(2, minmax(80px, 1fr)) 50px\" on
   top-level whitespace must yield [\"repeat(2, minmax(80px, 1fr))\" \"50px\"],
   not fall apart on the commas/spaces *inside* the nested calls). Shared by
   split-tracks-toplevel (splits a whole track-list on whitespace) and
   split-args-toplevel (splits a repeat()/minmax() argument list on
   commas)."
  [delim? s]
  (let [n (count s)]
    (loop [i 0 depth 0 start 0 out []]
      (if (= i n)
        (let [seg (str/trim (subs s start i))]
          (if (str/blank? seg) out (conj out seg)))
        (let [c (nth s i)]
          (cond
            (= c \() (recur (inc i) (inc depth) start out)
            (= c \)) (recur (inc i) (max 0 (dec depth)) start out)

            (and (zero? depth) (delim? c))
            (let [seg (str/trim (subs s start i))
                  out (if (str/blank? seg) out (conj out seg))]
              (recur (inc i) depth (inc i) out))

            :else (recur (inc i) depth start out)))))))

(defn- ws-char? [c] (boolean (re-matches #"\s" (str c))))

(defn- split-tracks-toplevel
  "Splits a `grid-template-columns`/`grid-template-rows` string into its
   top-level track tokens on whitespace, without breaking apart whitespace
   nested inside a repeat(...) argument list (see paren-split; e.g.
   `repeat(2, 100px 1fr)` is one token here, not four)."
  [s]
  (paren-split ws-char? s))

(defn- split-args-toplevel
  "Splits the inside of a repeat(...)/minmax(...) call on its top-level
   commas (see paren-split; a comma nested inside a further repeat()/
   minmax() argument, e.g. the one inside `repeat(3, minmax(80px, 1fr))`,
   doesn't split that inner call apart)."
  [s]
  (paren-split #(= % \,) s))

;; ---- calc() -- constant, percentage-free arithmetic only ----
;;
;; A small, LOCAL mirror of cssom.core's own constant-calc() subset (see
;; its namespace docstring's `calc(...)` paragraph for the full rationale
;; and arithmetic-validity rules this honors: `*`/`/` bind tighter than
;; `+`/`-`, left-to-right same-precedence associativity, `*` needs at least
;; one plain-number side, `/`'s divisor must be a plain number) --
;; deliberately NOT a call into cssom.core, for the same reason this file
;; already owns a separate parse-int/parse-dbl instead of depending on
;; cssom.core for numeric coercion (see parse-track-list's docstring): a
;; grid track list is a multi-token string cssom.core's parse-style-value
;; never touches at all (it only ever coerces a WHOLE declaration value),
;; so a `calc(...)` token embedded in `grid-template-columns: calc(100px +
;; 20px) 1fr` arrives here exactly as the author wrote it, needing its own
;; resolution independent of cssom.core's cascade pass.

(def ^:private calc-pattern
  "Matches a whole TOKEN that is one `calc(...)` call, case-insensitively --
   see resolve-constant-calc, called on a single already-split track/length
   token (split-tracks-toplevel/split-args-toplevel already keep a
   calc(...) call's own parens from being split apart, the same paren-depth
   tracking that already protects repeat(...)/minmax(...) calls)."
  #"(?is)calc\((.*)\)")

(defn- calc-number-at
  "Attempts to match a numeric literal -- optionally decimal, optionally
   with an immediately-following `px` or `%` unit glued on with no space --
   at index `idx` of calc() tokenizer input `s`. Returns `[token next-idx]`,
   or nil if `idx` isn't the start of one (another unit, or stray text),
   signalling to tokenize-calc that this token isn't this engine's
   calc() subset at all.

   A `%` operand is resolved to px HERE, against `basis`, rather than
   carried as a third unit through eval-calc-node: the whole point of a
   percentage in a calc() is that it is a length as soon as the containing
   block is known, and resolving it at the leaf keeps eval-calc-node's
   `+`/`-` same-unit rule (the rule that makes `calc(100px + 2)` invalid)
   exactly as it was. `basis` nil means the containing block's size in this
   axis is not definite, so the percentage cannot be resolved at all and the
   whole calc() degrades to nil -- the same answer percentage-of gives, for
   the same reason."
  [s idx basis]
  (when-let [num-str (re-find #"^[0-9]*\.?[0-9]+" (subs s idx))]
    (let [after (+ idx (count num-str))
          px? (and (<= (+ after 2) (count s)) (= "px" (subs s after (+ after 2))))
          pct? (and (not px?) (< after (count s)) (= \% (nth s after)))
          end (cond px? (+ after 2) pct? (inc after) :else after)
          n (parse-dbl num-str 0.0)]
      (cond
        (not pct?) [{:calc/type :operand :calc/unit (if px? :px :number) :calc/value n} end]
        (some? basis) [{:calc/type :operand :calc/unit :px :calc/value (* basis (/ n 100.0))} end]
        :else nil))))

(defn- tokenize-calc
  "Tokenizes the inside of a `calc(...)` call into a flat token vector --
   bare operator/paren tokens plus number-or-px-length operand tokens (see
   calc-number-at) -- for parse-calc-level, skipping whitespace (ws-char?,
   the same helper split-tracks-toplevel already uses). Returns nil if any
   character isn't part of a recognized token (e.g. a `%`/`em`/other unit
   anywhere inside), the same 'stop, don't guess' contract every other
   token-matching helper in this file already uses (parse-track-token's
   :else, parse-minmax-token's fallbacks, ...).

   `basis` is the containing block's size in the axis this calc() is being
   read in, for a `%` operand to resolve against (see calc-number-at); nil
   where the caller has none, which is what every track-sizing caller
   passes and what makes a percentage inside a track size degrade exactly
   as it always did."
  [s basis]
  (let [n (count s)]
    (loop [idx 0 tokens []]
      (cond
        (= idx n) tokens
        (ws-char? (nth s idx)) (recur (inc idx) tokens)
        :else
        (case (nth s idx)
          \+ (recur (inc idx) (conj tokens {:calc/type :plus}))
          \- (recur (inc idx) (conj tokens {:calc/type :minus}))
          \* (recur (inc idx) (conj tokens {:calc/type :star}))
          \/ (recur (inc idx) (conj tokens {:calc/type :slash}))
          \( (recur (inc idx) (conj tokens {:calc/type :lparen}))
          \) (recur (inc idx) (conj tokens {:calc/type :rparen}))
          (if-let [[operand next-idx] (calc-number-at s idx basis)]
            (recur next-idx (conj tokens operand))
            nil))))))

(defn- parse-calc-level
  "Parses a calc() token vector (tokenize-calc) into an AST node (`:calc/op`
   one of `:num`/`:neg`/`:add`/`:sub`/`:mul`/`:div`, see eval-calc-node) via
   PRECEDENCE CLIMBING -- `level` 0 = lowest precedence (`+`/`-`), 1 =
   `*`/`/`, 2 = unary `+`/`-` and a primary (an operand, or a parenthesized
   sub-expression restarting at level 0). Returns `[node remaining-tokens]`
   or nil on any parse failure. A single self-recursive function
   (recursing into itself at a different `level`) rather than the classic
   four mutually-recursive expr/term/factor/primary grammar functions --
   mirrors cssom.core's own parse-calc-level and its docstring for exactly
   why (this file's own no-declare convention can't satisfy a true
   mutual-recursion grammar cycle, so the whole grammar folds into one
   precedence-parameterized function instead)."
  [tokens level]
  (if (= level 2)
    (when (seq tokens)
      (let [t (first tokens)]
        (case (:calc/type t)
          :minus (when-let [[node toks] (parse-calc-level (rest tokens) 2)]
                   [{:calc/op :neg :calc/arg node} toks])
          :plus (parse-calc-level (rest tokens) 2)
          :operand [{:calc/op :num :calc/unit (:calc/unit t) :calc/value (:calc/value t)}
                    (rest tokens)]
          :lparen (when-let [[node toks] (parse-calc-level (rest tokens) 0)]
                    (when (and (seq toks) (= :rparen (:calc/type (first toks))))
                      [node (rest toks)]))
          nil)))
    (let [ops (if (= level 0) #{:plus :minus} #{:star :slash})
          op->ast (fn [op] (case op :plus :add :minus :sub :star :mul :slash :div))]
      (when-let [[left toks] (parse-calc-level tokens (inc level))]
        (loop [left left toks toks]
          (if (and (seq toks) (contains? ops (:calc/type (first toks))))
            (let [op (:calc/type (first toks))]
              (if-let [[right toks2] (parse-calc-level (rest toks) (inc level))]
                (recur {:calc/op (op->ast op) :calc/left left :calc/right right} toks2)
                nil))
            [left toks]))))))

(defn- eval-calc-node
  "Evaluates a parsed calc() AST node (parse-calc-level) into a `[value
   unit]` pair (`unit` `:number` or `:px`), or nil on an arithmetic-type
   violation -- mirrors cssom.core's own eval-calc-node: `+`/`-` require
   both sides the same unit; `*` requires at least one side to be a plain
   `:number`; `/` requires the right (divisor) side to be a plain, non-zero
   `:number`."
  [node]
  (case (:calc/op node)
    :num [(:calc/value node) (:calc/unit node)]

    :neg (when-let [[v u] (eval-calc-node (:calc/arg node))]
           [(- v) u])

    :add (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (when (= lu ru) [(+ lv rv) lu])))

    :sub (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (when (= lu ru) [(- lv rv) lu])))

    :mul (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (cond
               (= lu :number) [(* lv rv) ru]
               (= ru :number) [(* lv rv) lu]
               :else nil)))

    :div (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (when (and (= ru :number) (not (zero? rv)))
               [(/ lv rv) lu])))

    nil))

(defn- resolve-constant-calc
  "Resolves a single whole TOKEN (e.g. \"calc(100px + 20px)\", already
   isolated by split-tracks-toplevel/split-args-toplevel's paren-aware
   splitting) to a plain px number when it is a whole-value `calc(...)`
   call whose entire contents are this engine's calc() subset (plain
   numbers/px lengths, `+`/`-`/`*`/`/`/parens, plus a `%` operand when the
   caller supplies the `basis` it resolves against -- still no `em`/other
   relative unit), or nil otherwise (not a calc() call at all, an
   unsupported-unit operand anywhere inside, a percentage with no definite
   basis, an arithmetic-type violation, or a malformed expression) --
   callers (parse-track-token, parse-length-px) treat nil exactly like any
   other unsupported token already degrades in this file (a 0px fixed
   track / an unconstrained 1fr minmax() fallback), never guessing a
   number. An exact-integer result is returned as a plain integer (matching
   this file's other integer-pixel track sizes); a genuinely fractional
   result (e.g. `calc(100px / 3)`) is returned as a double rather than
   losing precision.

   The one-argument arity is the no-containing-block caller: every track
   sizer, which has no width to resolve a percentage against and so keeps
   the constant-only subset it has always had."
  ([tok] (resolve-constant-calc tok nil))
  ([tok basis]
   (when-let [[_ inner] (re-matches calc-pattern tok)]
     (when-let [tokens (tokenize-calc inner basis)]
       (when-let [[node toks] (parse-calc-level tokens 0)]
         (when (empty? toks)
           (when-let [[value _unit] (eval-calc-node node)]
             (let [truncated (long value)]
               (if (== value truncated) truncated value)))))))))

(defn- parse-length-px
  "Parses a single px length or bare-integer token -- the two plain-length
   forms this file accepts everywhere a track size is expected -- to a
   pixel value, or nil if `tok` is neither. Used for minmax()'s `min`
   argument (always a plain length in this engine's honestly-scoped
   subset, never `fr`) and for a `max` argument that isn't `Nfr`. Also
   accepts a constant-calc() token (resolve-constant-calc) resolving to
   the same subset a bare `Npx`/integer already does -- e.g. `minmax(calc(
   100px - 20px), 1fr)` -- falling back to nil (this function's existing
   contract) for anything outside that subset."
  [tok]
  (cond
    (re-matches #"-?[0-9]*\.?[0-9]+px" tok) (parse-int (subs tok 0 (- (count tok) 2)) 0)
    (re-matches #"-?[0-9]*\.?[0-9]+" tok) (parse-int tok 0)
    :else (resolve-constant-calc tok)))

(declare parse-track-token)

(defn- parse-track-list
  "Parses a `grid-template-columns`/`grid-template-rows` value into a vector
   of track specs, e.g. \"100px 1fr 2fr\" -> [{:type :fixed :size 100}
   {:type :fr :size 1} {:type :fr :size 2}].

   Deliberately a small, local parser rather than an extension to
   cssom.core's cascade — mirrors how this file already owns its own
   parse-int/parse-dbl numeric coercion instead of depending on cssom.core
   for it. cssom.core/parse-declarations (parse-style-value) passes any
   value that isn't a bare integer, a single `Npx` token, or a whole-value
   constant-`calc(...)` call through untouched as a raw string, so a
   multi-token track list like \"100px 1fr 2fr\" -- or one with a `calc(...)`
   among its tokens, like \"calc(100px + 20px) 1fr\" -- always arrives here
   exactly as the CSS author wrote it (verified against
   cssom.core/parse-style-value, which only special-cases a WHOLE-value
   match, never a multi-token string). A bare NUMBER input (cssom.core
   coerces a lone single-track `Npx` value, e.g. `grid-template-columns:
   200px`, to a plain number -- and, since this namespace's own
   constant-calc() support was added, a lone single-track whole-value
   `calc(...)`, e.g. `grid-template-columns: calc(200px)`, the same way --
   see cssom.core's namespace docstring) is treated as a single fixed-px
   track for symmetry with that string case.

   Supports fixed `Npx` and fractional `Nfr` tracks, plus `repeat(...)`,
   `minmax(...)`, and a constant-`calc(...)` token (see parse-track-token
   for the full per-token grammar and each helper's own docstring for the
   honestly-scoped subset it supports -- resolve-constant-calc for exactly
   which calc() expressions resolve here, mirroring cssom.core's own
   subset). Splits on top-level whitespace only (split-tracks-toplevel), so
   whitespace nested inside a repeat(...)/calc(...) argument list doesn't
   fracture that call into separate tokens. Any token this file can't make
   sense of degrades to a 0px fixed track rather than throwing, so an
   unsupported keyword never crashes layout — see parse-track-token's :else
   branch.

   nil/blank input returns [] — callers decide the no-explicit-tracks
   fallback (layout-grid falls back to a single full-width column when
   grid-template-columns is absent)."
  [v]
  (cond
    (number? v) [{:type :fixed :size v}]
    (string? v) (vec (mapcat parse-track-token (split-tracks-toplevel (str/trim v))))
    :else []))

(defn- parse-minmax-token
  "Parses a `minmax(min, max)` token into a single :minmax track spec (see
   track-sizes for how it resolves to a concrete px size). Only the two
   px/fr combinations this engine can size honestly (no content-based
   auto-sizing, see the ns docstring) are recognized:
     - `min` a plain px length (or bare integer), `max` a plain px length
       -> {:type :minmax :min <px> :max-type :fixed :max <px>} — sized like
       a fixed track clamped up to at least `min` (see track-sizes).
     - `min` a plain px length, `max` literally `Nfr`
       -> {:type :minmax :min <px> :max-type :fr :max <N>} — participates
       in fr-space distribution like a plain fr track, floored at `min`
       (see track-sizes).

   `minmax(min-content, ...)`/`minmax(auto, ...)`/anything else that isn't
   one of the two forms above (including a malformed argument list) falls
   back to a plain, unconstrained 1fr track rather than crashing — chosen
   over the 0px-fixed-track fallback parse-track-token's :else uses for a
   wholly-unrecognized token, since minmax(...) unambiguously asked for
   *some* share of the available space, not zero."
  [tok]
  (let [inner (subs tok (count "minmax(") (dec (count tok)))
        args (split-args-toplevel inner)]
    (if (= 2 (count args))
      (let [[min-tok max-tok] args
            min-px (parse-length-px min-tok)]
        (cond
          (nil? min-px)
          [{:type :fr :size 1.0}]

          (re-matches #"-?[0-9]*\.?[0-9]+fr" max-tok)
          [{:type :minmax :min min-px :max-type :fr
            :max (parse-dbl (subs max-tok 0 (- (count max-tok) 2)) 1.0)}]

          (parse-length-px max-tok)
          [{:type :minmax :min min-px :max-type :fixed :max (parse-length-px max-tok)}]

          :else
          [{:type :fr :size 1.0}]))
      [{:type :fr :size 1.0}])))

(defn- parse-repeat-token
  "Parses a `repeat(count, track)` token by literally expanding it into
   `count` copies of `track`'s parsed tracks (see parse-track-list) --
   identical in effect to writing the track(s) out `count` times, which is
   exactly repeat()'s real-CSS semantics for the fixed-count case. `track`
   may itself be more than one space-separated track (e.g.
   `repeat(2, 100px 1fr)` expands to 4 tracks: 100px 1fr 100px 1fr) and may
   itself be a minmax(...) call (e.g. `repeat(3, minmax(80px, 1fr))`) --
   composes for free since both repeat() and minmax() bottom out in
   parse-track-list/parse-track-token, no special-casing needed.

   `repeat(auto-fill, ...)`/`repeat(auto-fit, ...)` are explicitly out of
   scope (they need real content-based auto-sizing this engine doesn't do,
   see the ns docstring) — and so is any other non-plain-integer count, or
   a malformed argument list. Rather than throwing, these degrade to the
   same single 0px fixed-track placeholder parse-track-token's :else branch
   uses for any other currently-unparseable token, so a malformed/
   unsupported repeat() drops out of the visual layout instead of crashing
   it."
  [tok]
  (let [inner (subs tok (count "repeat(") (dec (count tok)))
        args (split-args-toplevel inner)]
    (if (= 2 (count args))
      (let [[count-tok track-tok] args]
        (if (re-matches #"[0-9]+" count-tok)
          (let [cnt (parse-int count-tok 0)
                one-repeat (parse-track-list track-tok)]
            (if (and (pos? cnt) (seq one-repeat))
              (vec (mapcat identity (repeat cnt one-repeat)))
              [{:type :fixed :size 0}]))
          [{:type :fixed :size 0}]))
      [{:type :fixed :size 0}])))

(defn- parse-track-token
  "Parses a single top-level track-list token (see split-tracks-toplevel)
   into one or more track specs (a vector, since repeat(...) expands to
   more than one). Supported forms: a bare `Nfr`/`Npx`/plain-integer length
   (as before repeat()/minmax() existed), plus `repeat(count, track)`
   (parse-repeat-token) and `minmax(min, max)` (parse-minmax-token), plus a
   whole-value constant-`calc(...)` token (resolve-constant-calc, e.g.
   `calc(100px + 20px)` -> a single 120px fixed track) -- but ONLY when
   every operand inside is this engine's bounded, always layout-independent
   subset (plain numbers/px lengths, no `%`/`em`/other relative unit). Any
   other token — `auto`, percentages, `repeat(auto-fill, ...)`, a
   `calc(...)` mixing in a percentage (e.g. `calc(50% - 10px)`, which needs
   real layout against this container's own resolved size to mean anything
   -- deliberately out of scope, see cssom.core's namespace docstring's
   `calc(...)` paragraph for why) or any other unrecognized/malformed form
   — degrades to a single 0px fixed track rather than throwing, so an
   unsupported keyword never crashes layout."
  [tok]
  (cond
    (re-matches #"-?[0-9]*\.?[0-9]+fr" tok)
    [{:type :fr :size (parse-dbl (subs tok 0 (- (count tok) 2)) 1.0)}]

    (re-matches #"-?[0-9]*\.?[0-9]+px" tok)
    [{:type :fixed :size (parse-int (subs tok 0 (- (count tok) 2)) 0)}]

    (re-matches #"-?[0-9]*\.?[0-9]+" tok)
    [{:type :fixed :size (parse-int tok 0)}]

    (re-matches #"repeat\(.*\)" tok)
    (parse-repeat-token tok)

    (re-matches #"minmax\(.*\)" tok)
    (parse-minmax-token tok)

    (re-matches calc-pattern tok)
    (if-let [size (resolve-constant-calc tok)]
      [{:type :fixed :size size}]
      [{:type :fixed :size 0}])

    ;; An `auto` track is sized from what is IN it: min-content as a floor,
    ;; max-content as a growth limit, then an equal share of whatever is
    ;; left (see track-sizes). This used to fall through to the :else 0px
    ;; placeholder below, so `grid-template-columns: auto auto` produced two
    ;; ZERO-width tracks and every item in them collapsed -- measured in
    ;; Brave as 154.5/245.5 against this engine's 0/0.
    (= "auto" (str/lower-case tok))
    [{:type :auto}]

    :else
    [{:type :fixed :size 0}]))

(defn- distribute-fr
  "Splits `remaining` px across `weights` (fr weights, in track order)
   proportionally using integer division, then assigns any leftover px from
   rounding to the last (highest-index) fr track, so the returned sizes
   always sum exactly to `remaining` (same 'don't lose a pixel to rounding'
   convention as the rest of this file's integer-pixel math)."
  [remaining weights]
  (let [total (reduce + 0 weights)]
    (if (or (<= remaining 0) (not (pos? total)))
      (mapv (constantly 0) weights)
      (let [sizes (mapv #(long (quot (* remaining %) total)) weights)
            leftover (- remaining (reduce + 0 sizes))]
        (if (and (pos? leftover) (seq sizes))
          (update sizes (dec (count sizes)) + leftover)
          sizes)))))

(defn- fixed-contribution
  "The px this track reserves up front, before fr-space distribution: a
   :fixed track's own size; for a :minmax track (see parse-minmax-token),
   `min` px if its max is `fr` (that min is a floor reserved off the top,
   topped up from fr-space below in track-sizes) or max(min,max) if its max
   is a fixed px length (no fr participation at all — see fr-weight); 0 for
   a plain :fr track (nothing reserved, it only ever gets an fr-space
   share)."
  [t]
  (case (:type t)
    :fixed (:size t)
    :minmax (if (= :fixed (:max-type t)) (max (:min t) (:max t)) (:min t))
    0))

(defn- fr-weight
  "The fr weight this track contributes to fr-space distribution, or nil if
   it doesn't participate at all: a plain :fr track's own weight; a
   :minmax track's `max` count when its max is `fr` (its `min` floor is
   already reserved via fixed-contribution — see track-sizes for how the
   two combine); nil for :fixed tracks and for a :minmax track whose max is
   a fixed px length (fully resolved by fixed-contribution alone)."
  [t]
  (case (:type t)
    :fr (:size t)
    :minmax (when (= :fr (:max-type t)) (:max t))
    nil))

(defn- distribute-equally-with-caps
  "Adds `free` px to the entries of `sizes` at `idxs`, in EQUAL shares --
   not shares proportional to what each already holds -- freezing any entry
   that reaches its cap, and re-offering the share a frozen entry could not
   take to whoever is left, until every entry is frozen or the space is
   spent. `cap-of` returns an entry's ceiling, or nil for no ceiling.

   Equal, not proportional, because that is what a browser does with
   `auto` tracks and it is not derivable from the spec text alone:
   measured in Brave, `grid-template-columns: auto auto` in a 400px grid
   holding `short` (max-content 41.625) and `a much longer cell`
   (max-content 145.312) comes out 148.156 / 251.844 -- each track its own
   max-content plus exactly (400 - 41.625 - 145.312) / 2. Proportional
   distribution would have given 85 / 315.

   The re-offer loop is the same shape resolve-flexible-lengths uses for
   flex, and for the same reason: distributing once and clamping afterwards
   leaves the space a capped track refused sitting in nobody's hands.
   Measured in Brave at a 200px width, the same two tracks come out
   48.156 / 151.844 -- `short` freezes at its 41.625 max-content, its
   unspent share goes to the other track, which then freezes at 145.312,
   and only then does the leftover get shared equally."
  [sizes idxs cap-of free]
  (if (or (<= free 0) (empty? idxs))
    sizes
    (loop [sizes (vec sizes) live (vec idxs) free (long free)]
      (if (or (empty? live) (<= free 0))
        ;; nobody left to take it: whatever is unspent stays unspent, which
        ;; is what a fully-capped track list does with leftover space
        sizes
        (let [share (quot free (count live))]
          (if (<= share 0)
            ;; fewer px than tracks: hand the remainder out one px at a time
            ;; so the total is exactly `free` (same 'don't lose a pixel to
            ;; rounding' convention as distribute-fr)
            (first (reduce (fn [[sizes free] i]
                             (if (pos? free)
                               (let [cap (cap-of i)
                                     want (inc (nth sizes i))]
                                 (if (or (nil? cap) (<= want cap))
                                   [(assoc sizes i want) (dec free)]
                                   [sizes free]))
                               [sizes free]))
                           [sizes free] live))
            (let [{:keys [sizes unspent still-live]}
                  (reduce (fn [acc i]
                            (let [cap (cap-of i)
                                  cur (nth (:sizes acc) i)
                                  want (+ cur share)]
                              (if (and cap (>= want cap))
                                (-> acc
                                    (update :sizes assoc i (max cur cap))
                                    (update :unspent + (- want (max cur cap))))
                                (-> acc
                                    (update :sizes assoc i want)
                                    (update :still-live conj i)))))
                          {:sizes sizes :unspent 0 :still-live []}
                          live)
                  spent (- free unspent (rem free (count live)))]
              (recur sizes still-live (- free spent)))))))))

(defn- track-sizes
  "Resolves parsed tracks (see parse-track-list) to concrete pixel sizes.
   `definite-total` is the space available along this axis to distribute fr
   and `auto` tracks against: always the container's content-width for
   columns; for rows it is the container's explicit :height if given, else
   nil. When nil, fr tracks (and the fr-space portion of a :minmax fr-max
   track) resolve to 0px extra here and layout-grid falls back to
   auto/content sizing for that row instead (mirroring flexbox's own auto
   cross-axis convention elsewhere in this file) — there is no definite
   total to share proportionally when the grid container's height is itself
   content-driven (a :minmax fr-max track still gets its `min` floor even
   then, since that floor never depended on fr-space).

   `intrinsics` is one `{:min <px> :max <px>}` per track — what the items in
   that track need at min-content and at max-content — and is consulted
   ONLY by `:auto` tracks. layout-grid measures it (grid-track-intrinsics);
   an empty/short vector reads as 0/0, which is what every caller that has
   no `:auto` track at all can pass.

   Every track resolves one of four ways (see fixed-contribution/fr-weight
   above for the exact per-type rules):
     - :fixed -- always its own px size, no fr participation.
     - :fr -- its proportional share of `remaining` (distribute-fr).
     - :minmax -- a fixed px max resolves like a :fixed track at
       max(min,max); an `fr` max reserves `min` px up front (subtracted
       from `remaining` alongside every other track's fixed contribution)
       and then ALSO gets a proportional fr-space share of whatever is left
       over once every reservation is subtracted — so its final size is
       `min` PLUS that share, never less than `min`.
     - :auto -- its items' min-content size as a floor, grown toward their
       max-content size with an EQUAL share of the free space
       (distribute-equally-with-caps), and then, only when NO `fr` track is
       competing for the same space, grown again past max-content with an
       equal share of whatever is still left (CSS Grid §12.8's own 'stretch
       auto tracks' step, which is what makes `auto auto` fill its
       container rather than hug its content). Measured in Brave:
       `auto 1fr` leaves the auto track at exactly its 41.625px
       max-content, while `auto 100px` stretches the auto track to 300px.

   Scope cut, deliberately: §12.8 stretches auto tracks only when
   `justify-content`/`align-content` is `normal`/`stretch`, and an authored
   `justify-content: start` leaves them at max-content (measured:
   41.625/145.312 rather than 148.156/251.844). This engine does not
   implement `justify-content` on a grid container at all — layout-grid
   hardcodes flex-start track placement — so there is no value here to
   condition the stretch on, and node-style's own `:justify-content`
   default of \"flex-start\" cannot be told apart from an authored one.
   Stretching unconditionally matches the initial value, which is what
   nearly every real grid has."
  ([tracks gap definite-total] (track-sizes tracks gap definite-total []))
  ([tracks gap definite-total intrinsics]
   (let [n (count tracks)
         gap-total (* gap (max 0 (dec n)))
         auto-idxs (filterv #(= :auto (:type (nth tracks %))) (range n))
         intrinsic-min (fn [i] (long (max 0 (:min (nth intrinsics i nil) 0))))
         intrinsic-max (fn [i] (long (max (intrinsic-min i) (:max (nth intrinsics i nil) 0))))
         ;; every track's floor, before anything is distributed: an auto
         ;; track's is its min-content size, everything else's is what
         ;; fixed-contribution already reserved
         base (mapv (fn [i] (let [t (nth tracks i)]
                              (if (= :auto (:type t)) (intrinsic-min i) (long (fixed-contribution t)))))
                    (range n))
         has-fr? (boolean (seq (keep fr-weight tracks)))
         auto-resolved
         (if (and definite-total (seq auto-idxs))
           (let [grown (distribute-equally-with-caps
                        base auto-idxs intrinsic-max
                        (- definite-total (reduce + 0 base) gap-total))]
             (if has-fr?
               grown
               (distribute-equally-with-caps
                grown auto-idxs (constantly nil)
                (- definite-total (reduce + 0 grown) gap-total))))
           base)
         fixed-total (reduce + 0 (map-indexed (fn [i t] (if (= :auto (:type t))
                                                          (nth auto-resolved i)
                                                          (fixed-contribution t)))
                                              tracks))
         remaining (when definite-total (max 0 (- definite-total fixed-total gap-total)))
         fr-weights (keep fr-weight tracks)
         fr-sizes (if remaining (distribute-fr remaining fr-weights) [])]
     (loop [i 0 frs fr-sizes out []]
       (if (= i n)
         out
         (let [t (nth tracks i)]
           (cond
             (= :auto (:type t))
             (recur (inc i) frs (conj out (long (nth auto-resolved i))))

             (some? (fr-weight t))
             (recur (inc i) (rest frs)
                    (conj out (long (+ (if (= :minmax (:type t)) (:min t) 0) (or (first frs) 0)))))

             :else
             (recur (inc i) frs (conj out (long (fixed-contribution t)))))))))))

;; ---- grid item explicit placement (grid-column / grid-row) ----
;;
;; Real CSS lets an author place a grid item at a specific track *line*
;; (`grid-column`/`grid-row`, or their `-start`/`-end` longhands -- only the
;; shorthand is parsed here) instead of relying purely on auto-placement.
;; This engine supports the common subset: a bare line number
;; (`grid-column: 2`), the two-value `<start> / <end>` shorthand
;; (`grid-column: 1 / 3`), and `<start> / span <n>` (`grid-column: 2 / span
;; 2`) -- see parse-grid-placement for the exact per-form grammar and
;; resolve-grid-line for how a negative line number resolves. Explicitly out
;; of scope: the `-start`/`-end` longhand properties, dense packing, and
;; implicit track creation for an out-of-range line (see clamp-col-range for
;; the fallback used instead). `grid-template-areas`/`grid-area` named-area
;; placement is a separate, THIRD placement mechanism (see the
;; "grid-template-areas" section further below, after clamp-col-range) that
;; composes with this one -- see item-grid-placement for exactly how the two
;; (plus fully-auto placement) resolve together per item.
;; See layout-grid's docstring and place-grid-items below for exactly how
;; explicitly- and auto-placed items compose.

(defn- rect-cells
  "Every [row col] cell in the half-open rectangle [row-start row-end) x
   [col-start col-end) -- the unit place-grid-items' occupancy set (below)
   tracks cells in."
  [row-start row-end col-start col-end]
  (for [r (range row-start row-end) c (range col-start col-end)] [r c]))

(defn- rect-free?
  [occupied row-start row-end col-start col-end]
  (not-any? occupied (rect-cells row-start row-end col-start col-end)))

(defn- find-free-row
  "Smallest row-idx >= 0 at which the single row [row-idx (inc row-idx)) x
   [col-start col-end) is entirely free in `occupied` -- resolves the row
   for an item that declares an explicit grid-column but no grid-row (see
   place-grid-items). Always terminates: a row beyond every row touched so
   far is trivially free, since rows are not a fixed axis in this engine
   (unlike columns -- see find-free-col)."
  [occupied col-start col-end]
  (loop [row 0]
    (if (rect-free? occupied row (inc row) col-start col-end)
      row
      (recur (inc row)))))

(defn- find-free-col
  "Smallest col-idx in [0 n-cols) at which [row-start row-end) x [col-idx
   (inc col-idx)) is entirely free in `occupied` -- resolves the column for
   an item that declares an explicit grid-row but no grid-column (see
   place-grid-items). Unlike find-free-row, this search IS bounded (columns
   are a fixed, finite axis in this engine); if every column is already
   occupied across the whole row-span, this falls back to column 0
   (accepting an overlap) rather than searching forever -- an honest
   edge-case fallback for a rare, self-contradictory declaration set."
  [occupied row-start row-end n-cols]
  (or (first (filter #(rect-free? occupied row-start row-end % (inc %)) (range n-cols)))
      0))

(defn- parse-grid-line-token
  "Parses a single grid-line token into a signed integer line number, or nil
   if it isn't a plain integer. cssom.core's parse-style-value already
   coerces a whole-value bare-integer declaration (e.g. `grid-column: 2`)
   straight to a Long before this file ever sees it (see node-style) -- this
   handles that already-coerced-integer case AND the raw-string case (a
   token split out of a multi-part value like `1 / 3`, which arrives here as
   a string since the whole declaration wasn't itself a bare integer)."
  [tok]
  (cond
    (integer? tok) tok
    (and (string? tok) (re-matches #"-?\d+" tok)) (parse-int tok nil)
    :else nil))

(defn- resolve-grid-line
  "Resolves a raw 1-based grid LINE number to a positive 1-based line,
   supporting real CSS's negative 'counts from the end' convention (`-1` is
   the last line, `-2` the one before it, ...) against `track-count` tracks
   (-> track-count + 1 lines total, lines being the dividers between/around
   tracks, not the tracks themselves). Positive lines pass through
   unchanged. A non-positive/zero result (e.g. -1 against track-count 0)
   falls back to line 1 rather than producing a zero/negative track index.

   Used only for the START/END values of the two-value and `span` forms in
   parse-grid-placement (below) -- NOT for a lone single-value declaration,
   which resolves a negative number differently (as a track index counted
   from the end, not a line -- see parse-grid-placement's docstring for
   exactly why those two need different arithmetic)."
  [line track-count]
  (let [num-lines (inc (max 0 track-count))
        resolved (if (pos? line) line (+ num-lines line 1))]
    (if (pos? resolved) resolved 1)))

(defn- parse-grid-placement
  "Parses a grid-column/grid-row declaration's already-normalized value (see
   node-style/style: cssom.core's parse-style-value coerces a whole-value
   bare integer like `grid-column: 2` to a Long; anything else -- containing
   a `/` or the `span` keyword -- arrives as the original raw string) into a
   0-based half-open [start end) track-index range along this axis, or nil
   when the value is absent or in a form this engine doesn't parse (an
   unsupported/malformed value degrades to nil, which callers treat exactly
   like the declaration being absent -- 'auto-place this item on this axis'
   -- instead of crashing).

   Supported forms:
     - a single line number N (`grid-column: 2`) -> [N-1, N), exactly the
       one track starting at that line -- mirrors real CSS's own default of
       `grid-column-end: auto` (= span 1) when only a start line is given.
       A NEGATIVE N here (`grid-column: -1`, a deliberately pragmatic
       stretch goal) is resolved directly as a 0-based TRACK index counted
       from the end (-1 -> the last track, -2 -> the second-to-last, ...)
       rather than through resolve-grid-line's line-number arithmetic:
       resolving it as a line instead (line = num-lines + N + 1, then
       occupying [line-1, line)) would make a lone `-1` land one track PAST
       the last real track (real CSS's actual behavior there, which needs
       an implicit track this engine doesn't create) while `-2` would land
       ON the last track -- a confusing off-by-one for exactly the idiom
       this stretch goal exists for ('the last column'). Track-index
       arithmetic sidesteps that: `-1` always means the last track.
     - `<start> / <end>` (`grid-column: 1 / 3`) -> [start-1, end-1), every
       track between the two lines (both resolved via resolve-grid-line, so
       a negative end here DOES use real line-number semantics -- e.g.
       `1 / -1` spans every declared track, the common 'span the whole
       grid' idiom, matching real CSS). An end line at or before start
       degrades to a 1-track span from start (never an empty/reversed
       range).
     - `<start> / span <n>` (`grid-column: 2 / span 2`) -> [start-1,
       start-1+n), n clamped to >= 1.

   `track-count` is the number of tracks currently known along this axis
   (n-cols for grid-column, the parsed grid-template-rows track count for
   grid-row), used only to resolve a negative line/index."
  [v track-count]
  (letfn [(single [line]
            (if (neg? line)
              (let [idx (max 0 (+ track-count line))]
                [idx (inc idx)])
              (let [l (max 1 line)]
                [(dec l) l])))]
    (cond
      (integer? v) (single v)

      (string? v)
      (let [parts (str/split v #"/")]
        (cond
          (= 1 (count parts))
          (let [tok (str/trim (first parts))]
            (if-let [[_ n] (re-matches #"(?i)span\s+([0-9]+)" tok)]
              ;; `grid-column: span 2` with no start line: the item keeps
              ;; auto placement but occupies N tracks. Only the SPAN is
              ;; declared, so the returned range is relative -- the cursor
              ;; decides where it starts (see item-grid-placement).
              {:span (max 1 (parse-int n 1))}
              (when-let [line (parse-grid-line-token tok)]
                (single line))))

          (= 2 (count parts))
          (let [start-tok (str/trim (first parts))
                end-tok (str/trim (second parts))]
            (when-let [start-line-raw (parse-grid-line-token start-tok)]
              (let [start-line (resolve-grid-line start-line-raw track-count)
                    span-match (re-matches #"(?i)span\s+([0-9]+)" end-tok)]
                (cond
                  span-match
                  (let [n (max 1 (parse-int (second span-match) 1))]
                    [(dec start-line) (+ (dec start-line) n)])

                  (parse-grid-line-token end-tok)
                  (let [end-line (resolve-grid-line (parse-grid-line-token end-tok) track-count)
                        end-line (if (> end-line start-line) end-line (inc start-line))]
                    [(dec start-line) (dec end-line)])

                  :else nil))))

          :else nil))

      :else nil)))

(defn- clamp-col-range
  "Clamps a parsed [start end) column range (see parse-grid-placement) into
   the fixed [0 n-cols) column-track space this engine always has (n-cols is
   never 0 -- layout-grid falls back to a single full-width column when
   grid-template-columns is absent). An out-of-range column line (e.g.
   `grid-column: 5` with only 3 declared column tracks) is a real, common
   case that real CSS handles by implicitly creating new tracks -- out of
   scope here (see layout-grid's docstring) -- so instead this clamps the
   range to whatever of it fits inside the declared tracks, down to a
   1-track minimum at the last column, rather than indexing past the
   col-widths/col-offsets vectors or crashing. Only ever applied to columns
   -- rows have no fixed track count to clamp against in this engine (an
   out-of-range row just becomes another auto-sized row, see layout-grid)."
  [[start end] n-cols]
  (let [last-idx (dec n-cols)
        start (-> start (max 0) (min last-idx))
        end (-> end (max (inc start)) (min n-cols))]
    [start end]))

;; ---- grid-template-areas (named-area placement, a THIRD mechanism) ----
;;
;; Real CSS also lets an author place a grid item by NAME rather than raw
;; line numbers: `grid-template-areas` on the container declares a sequence
;; of quoted-string ROWS (one string per row; whitespace-separated tokens
;; name which area occupies each cell; `.` means "no area, this cell
;; intentionally empty"), and `grid-area: <name>` on an item places it at
;; whatever cell-range that name's area occupies. This composes with, rather
;; than replaces, grid-column/grid-row explicit placement and auto-placement
;; -- see item-grid-placement below for exactly how the three resolve
;; per-item, and layout-grid's docstring for how an areas-only (no explicit
;; grid-template-columns) declaration establishes the grid's column count.
;; Out of scope: the longhand `grid-area: <row-start> / <col-start> /
;; <row-end> / <col-end>` 4-value shorthand -- only a bare area-name
;; reference is parsed.

(def ^:private area-row-pattern
  "Matches a single quoted string, double- or single-quoted -- deliberately
   the same narrow quoted-literal grammar cssom.core's content-literal-pattern
   uses for `content: \"...\"` values. This file owns its own tiny copy
   rather than depending on cssom.core for it, the same convention this file
   already follows for its own numeric coercion (see parse-track-list's
   docstring for why)."
  #"\"([^\"]*)\"|'([^']*)'")

(defn- area-template-row-strings
  "Extracts every quoted-string ROW's unquoted text, in source order, from a
   grid-template-areas raw value -- e.g. the raw multi-line string
   `\"sidebar header\"\n\"sidebar main\"\n\"sidebar footer\"` (exactly how it
   arrives here: cssom.core's parse-style-value passes any value that isn't a
   bare integer/px length through untouched as a raw string, see node-style
   and parse-track-list's docstring for the identical reasoning re:
   grid-template-columns) yields [\"sidebar header\" \"sidebar main\"
   \"sidebar footer\"]. Only the quoted strings matter -- any other
   character in the raw value (the newlines/indentation between them, which
   real CSS also treats as insignificant whitespace here) simply isn't part
   of any match, so it's silently ignored rather than needing separate
   whitespace-skipping logic. Returns [] for a blank/non-string value or one
   with no quoted strings at all."
  [v]
  (if (string? v)
    (mapv (fn [[_ double-quoted single-quoted]] (or double-quoted single-quoted ""))
          (re-seq area-row-pattern v))
    []))

(defn- parse-grid-template-areas
  "Parses a grid-template-areas raw value into {:areas {name {:row-start
   :row-end :col-start :col-end}, ...} :row-count n :col-count n}, or nil
   when the value doesn't parse into a well-formed rectangular grid at all
   (blank, no quoted rows, or rows whose token counts disagree -- real CSS
   requires every row to declare the same number of columns). This engine
   degrades to 'no template' -- treated exactly like the declaration being
   absent -- rather than guessing at a shape, the same 'degrade, don't
   crash' convention parse-track-list/parse-grid-placement above already use
   for their own malformed-input cases.

   Each row (area-template-row-strings) is split on whitespace into cell
   tokens; `.` is real CSS's own 'intentionally empty cell' marker and is
   never part of any named area. Every other distinct token names an area,
   occupying the UNION of every cell naming it. Real CSS requires that union
   to already form a solid rectangle (a named area whose cells don't tile a
   rectangle is invalid CSS) -- rather than replicating real CSS's actual
   error-recovery algorithm for that case, this computes each name's
   BOUNDING rectangle (min/max row and column across every cell naming it),
   then verifies every cell inside that bounding rectangle really is named
   the same -- and DROPS (excludes from the returned :areas map) any name
   that fails that check, so a non-rectangular/invalid area name is an
   honest non-match (grid-area references to it fall back to auto-placement,
   see item-grid-placement) rather than a crash or a guessed-at shape.

   :row-count/:col-count -- the areas template's OWN grid shape, independent
   of whatever grid-template-columns/-rows tracks are (or aren't) declared
   -- are also returned. Only :col-count feeds back into this engine's own
   track resolution (see layout-grid's docstring for exactly how an
   areas-only, no-grid-template-columns declaration uses it to establish
   the column count instead of the usual single-full-width-column fallback);
   :row-count does NOT feed back into anything, since rows are already an
   unbounded, auto-growing axis in this engine regardless of any declared
   grid-template-rows track count or any grid-template-areas row count (see
   layout-grid's row-sizing docstring section) -- an item's row range
   resolved via grid-area is just as capable of growing the grid past
   however many rows grid-template-rows itself declares as an explicit
   grid-row line reference already is."
  [v]
  (when-let [rows (not-empty (area-template-row-strings v))]
    (let [tokenized (mapv #(vec (remove str/blank? (str/split % #"\s+"))) rows)
          col-count (count (first tokenized))]
      (when (and (pos? col-count) (every? #(= col-count (count %)) tokenized))
        (let [row-count (count tokenized)
              named-cells (for [r (range row-count) c (range col-count)
                                 :let [nm (nth (nth tokenized r) c)]
                                 :when (not= nm ".")]
                            [nm r c])
              areas (into {}
                          (keep (fn [[nm entries]]
                                  (let [rs (map second entries)
                                        cs (map #(nth % 2) entries)
                                        row-start (apply min rs)
                                        row-end (inc (apply max rs))
                                        col-start (apply min cs)
                                        col-end (inc (apply max cs))
                                        expected-cells (set (for [r (range row-start row-end)
                                                                   c (range col-start col-end)]
                                                               [r c]))
                                        actual-cells (set (map (fn [[_ r c]] [r c]) entries))]
                                    (when (= expected-cells actual-cells)
                                      [nm {:row-start row-start :row-end row-end
                                           :col-start col-start :col-end col-end}]))))
                          (group-by first named-cells))]
          {:areas areas :row-count row-count :col-count col-count})))))

(defn- item-grid-placement
  "The child's own explicit placement request, resolved to a 0-based [start
   end) range per axis, or nil for an axis nothing places it on. Three
   composing sources, in this engine's documented PER-AXIS precedence:

     1. grid-column/grid-row (parse-grid-placement) -- when present on a
        given axis, always wins on that axis.
     2. grid-area (resolved by name against `areas`, the container's own
        parse-grid-template-areas :areas map, or nil when the container has
        no valid grid-template-areas) -- supplies whichever axis/axes
        grid-column/grid-row left undeclared.
     3. Neither declared on a given axis -- stays nil, i.e. fully-auto for
        that axis, exactly like before grid-area existed.

   This mirrors real CSS's actual cascade mechanics without this engine
   needing to literally expand grid-area into four separate longhand
   declarations: `grid-area: <name>` IS EQUIVALENT to setting the
   grid-row-start/grid-column-start/grid-row-end/grid-column-end longhands
   to that name's own bounds, so a real conflict between grid-area and
   grid-column/grid-row on the same item is really a same-longhand conflict
   -- and an explicitly-written longhand (here, grid-column/grid-row) always
   beats one merely implied by a shorthand's expansion. So an item with BOTH
   `grid-area` and `grid-column` set gets grid-column's COLUMN range, but
   still gets grid-area's ROW range if no grid-row was also given (see the
   grid-column-explicit-on-item-wins-over-grid-area-same-axis test).

   A grid-area reference to a name `areas` doesn't recognize (not declared
   in the container's grid-template-areas at all, or dropped by
   parse-grid-template-areas for being non-rectangular) is treated exactly
   like grid-area being absent -- an honest non-match (falls through to
   grid-column/grid-row if present, else fully auto), never a crash or a
   guessed-at cell.

   A non-element child (e.g. a raw text node, which can't carry a :style/*
   attr) always gets {:col nil :row nil} -- i.e. treated as fully
   auto-placed, same as before any of this existed. `theme` is only needed
   because node-style requires it."
  [theme child n-cols n-row-tracks areas]
  (if (map? child)
    (let [cst (node-style child theme)
          col (parse-grid-placement (:grid-column cst) n-cols)
          row (parse-grid-placement (:grid-row cst) n-row-tracks)
          area-name (:grid-area cst)
          area (when (and areas area-name) (get areas (str/trim (str area-name))))
          area-col (when area [(:col-start area) (:col-end area)])
          area-row (when area [(:row-start area) (:row-end area)])]
      {:col (or col area-col) :row (or row area-row)})
    {:col nil :row nil}))

(defn- place-grid-items
  "Resolves every in-flow grid child's [row-start row-end) x [col-start
   col-end) cell range, honoring explicit grid-column/grid-row and/or
   grid-area declarations (item-grid-placement) and auto-placing everything
   else in DOM order. Returns a vector, parallel to `children` (DOM order
   preserved regardless of placement order), of {:row-start :row-end
   :col-start :col-end}.

   Simplification (this engine's documented subset -- real CSS Grid's own
   auto-placement-around-explicit-items algorithm, CSS Grid section 8.5, is
   genuinely complex and deliberately NOT replicated here):

     1. Every child with an explicit grid-column/grid-row/grid-area-derived
        col and/or row (item-grid-placement returns non-nil for that axis,
        whichever of the three sources supplied it) is placed FIRST, in DOM
        order among themselves, before any fully-auto child -- mirroring
        real CSS's own two-phase placement (explicit items are placed before
        auto-placement runs at all, regardless of where they fall in DOM
        order relative to auto items). A child with only ONE axis resolved
        gets the OTHER axis resolved by searching for the first free row
        (find-free-row, if a column was resolved) or column (find-free-col,
        if a row was resolved) against only the explicitly-placed items so
        far -- not a full 2D bin-pack, but enough to avoid colliding with an
        earlier explicit item on the same row/column.

     2. Every remaining (fully auto -- neither axis resolved by any of the
        three sources) child is then placed, in DOM order among themselves,
        into the next unoccupied SINGLE cell (1 col x 1 row, exactly this
        engine's original pre-explicit-placement auto-placement grain) found
        by scanning row-major from (row 0, col 0), via a cursor that only
        ever advances (never revisits a cell). Cells already claimed by an
        explicit item (including one placed via grid-area) are skipped --
        this is the 'auto-placed items skip explicitly-occupied cells but
        don't attempt sophisticated backfill' simplification: once the scan
        has moved past a gap, it never backtracks into it, even if a later
        cell the scan reaches is itself a dead end (the loop's occupied-cell
        check keeps advancing until it finds a free cell, so it can't get
        stuck, but it also can't go backwards).

   When there are NO explicitly-placed items at all, this degenerates to
   exactly the row-major scan every auto item always got before this
   feature existed (the cursor never encounters an already-occupied cell,
   so it always accepts the first cell it lands on) -- a pure
   backwards-compatibility guarantee, not a special case in the code.

   `flow-column?` (`grid-auto-flow: column`) fills a COLUMN top-to-bottom
   before moving right, which is the row-major algorithm above with the two
   axes swapped -- so rather than a second placement engine, the requests
   are transposed on the way in and the placements transposed back on the
   way out. The bounded 'lane' axis becomes the ROW track count (rows are
   what an item wraps within), and columns become the unbounded axis that
   grows implicit tracks. Nothing else in this function knows the
   difference. Measured in Brave, `grid-auto-flow: column` with three items
   and no template puts them at x=0/70/140 in one row, where this engine
   stacked them vertically at the container's full width."
  [theme children n-cols n-row-tracks areas flow-column?]
  (let [n (count children)
        requests (cond->> (mapv #(item-grid-placement theme % n-cols n-row-tracks areas) children)
                   flow-column? (mapv (fn [r] {:col (:row r) :row (:col r)})))
        ;; the axis an auto-placed item wraps WITHIN. In row flow that is
        ;; the column track count; in column flow it is the row track
        ;; count, which is 1 when no grid-template-rows was declared (a
        ;; single row of columns, which is what a bare
        ;; `grid-auto-flow: column` produces in a browser).
        n-cols (if flow-column? (max 1 n-row-tracks) n-cols)
        idx-range (range n)
        ;; a bare `span N` (no start line) is NOT an explicit placement: the
        ;; item stays auto-placed and only its WIDTH is declared
        ;; A bare `span N` on EITHER axis is auto placement with a declared
        ;; size, not explicit placement. Checking only :col meant
        ;; `grid-row: span 2` was treated as explicit, and `resolve-explicit`
        ;; then destructured its `{:span 2}` map as a [start end] vector --
        ;; the one input in a 292-case corpus that made this engine THROW
        ;; (`nth not supported on this type`) instead of answer.
        span-only? (fn [r] (boolean (or (and (map? (:col r)) (:span (:col r)))
                                        (and (map? (:row r)) (:span (:row r))))))
        explicit? (fn [i] (let [{:keys [col row] :as r} (nth requests i)]
                            (and (not (span-only? r)) (boolean (or col row)))))
        explicit-idxs (filter explicit? idx-range)
        resolve-explicit
        (fn [occupied {:keys [col row]}]
          (cond
            (and col row)
            (let [[cs ce] (clamp-col-range col n-cols)
                  [rs re] row]
              [cs ce rs re])

            col
            (let [[cs ce] (clamp-col-range col n-cols)
                  rs (find-free-row occupied cs ce)]
              [cs ce rs (inc rs)])

            :else
            (let [[rs re] row
                  cs (find-free-col occupied rs re n-cols)]
              [cs (inc cs) rs re])))
        phase1 (reduce
                (fn [{:keys [occupied placements]} i]
                  (let [[cs ce rs re] (resolve-explicit occupied (nth requests i))]
                    {:occupied (into occupied (rect-cells rs re cs ce))
                     :placements (assoc placements i {:col-start cs :col-end ce
                                                       :row-start rs :row-end re})}))
                {:occupied #{} :placements (vec (repeat n nil))}
                explicit-idxs)
        ;; The auto-placement CURSOR is shared with the explicitly placed
        ;; items and only ever moves forward, so this pass walks every child
        ;; in DOM order: an explicit item advances the cursor to just past
        ;; itself, and the next auto item resumes from there. Before this the
        ;; cursor started at (0,0) and only skipped OCCUPIED cells, so
        ;; `<div style="grid-column: 2">right</div><div>next</div>` in a
        ;; two-column grid put `next` at row 1 column 1 -- beside and BEFORE
        ;; the explicit item -- where a browser wraps it to row 2, since the
        ;; cursor is past column 2 by then. Measured against Chrome.
        ;;
        ;; But ONLY an item with a definite COLUMN moves it. CSS Grid §8.5
        ;; runs the cursor in step 4, which handles exactly two kinds of
        ;; item: one with a definite column and an automatic row (it sets
        ;; the cursor's column to the item's column-start), and one that is
        ;; automatic in both axes. Items placed in step 1 (both axes
        ;; definite) and step 2 (definite ROW, automatic column) never touch
        ;; it -- they are already positioned when step 4 starts its cursor
        ;; at the first row and column. Moving the cursor for a row-definite
        ;; item was measured wrong in Brave: `<div style="grid-row: 2">t
        ;; </div><div>b</div>` in a two-column grid puts `b` at row 1
        ;; column 1, where this engine advanced the cursor past `t` and put
        ;; it at row 2 column 2 -- the corpus reported it as
        ;; `want ["b" "t"] got ["t b"]`, i.e. the wrong line structure and
        ;; not merely the wrong cell.
        cursor-moving? (fn [i] (boolean (:col (nth requests i))))
        phase2 (reduce
                (fn [{:keys [occupied placements cursor-row cursor-col] :as state} i]
                  (if-let [p (nth placements i)]
                    (if (cursor-moving? i)
                      (assoc state
                             :cursor-row (:row-start p)
                             :cursor-col (:col-end p))
                      state)
                    (let [req (nth requests i)
                          span (min (max 1 (or (:span (:col req)) 1)) n-cols)
                          ;; `grid-row: span N` occupies N ROWS from wherever
                          ;; the cursor lands. Rows are unbounded in real CSS
                          ;; (the grid grows implicit rows), so unlike the
                          ;; column span this one is not clamped to the
                          ;; declared track count.
                          row-span (max 1 (or (:span (:row req)) 1))]
                      (loop [r cursor-row c cursor-col]
                        (cond
                          (> (+ c span) n-cols) (recur (inc r) 0)
                          ;; every cell of the whole rectangle has to be
                          ;; free, not just the first row's -- a 2-row item
                          ;; dropped into a slot whose row below is taken
                          ;; would silently overlap.
                          (some (fn [rr]
                                  (some #(contains? occupied [rr %]) (range c (+ c span))))
                                (range r (+ r row-span)))
                          (if (< (inc c) n-cols) (recur r (inc c)) (recur (inc r) 0))
                          :else
                          (let [end (+ c span)
                                row-end (+ r row-span)
                                wrap? (>= end n-cols)]
                            (assoc state
                                   :occupied (into occupied (for [rr (range r row-end)
                                                                  cc (range c end)]
                                                              [rr cc]))
                                   :placements (assoc placements i {:col-start c :col-end end
                                                                    :row-start r :row-end row-end})
                                   :cursor-row (if wrap? (inc r) r)
                                   :cursor-col (if wrap? 0 end))))))))
                (assoc phase1 :cursor-row 0 :cursor-col 0)
                idx-range)
        placements (:placements phase2)]
    (if flow-column?
      (mapv (fn [p] {:col-start (:row-start p) :col-end (:row-end p)
                     :row-start (:col-start p) :row-end (:col-end p)})
            placements)
      placements)))

(defn- span-width
  "The combined pixel width an item spanning column tracks [col-start
   col-end) occupies: the sum of those tracks' own widths plus one `gap`
   between every adjacent pair spanned (never a gap before the first or
   after the last, same convention place-main-axis already uses for the
   whole track list)."
  [col-widths gap col-start col-end]
  (+ (reduce + 0 (subvec col-widths col-start col-end))
     (* gap (max 0 (dec (- col-end col-start))))))

;; ---- ::before / ::after generated content ----
;;
;; cssom.core's apply-cascade already resolves each element's ::before/
;; ::after cascade into a plain style map (content/color/font-size/...)
;; under the element's own :attrs, at :pseudo/before / :pseudo/after (see
;; cssom.core's namespace docstring and computed-style). This file doesn't
;; need to run any cascade itself -- it just reads those attrs, same as it
;; already reads every other :style/* attr via `style` above, and
;; synthesizes a layout-only (never a DOM node) child that flows through
;; the exact same code paths (layout-text, layout-block/-flex/-grid's
;; child-stacking) as a real child would.

(defn- pseudo-style
  [node pseudo-key]
  (attr node (keyword "pseudo" (name pseudo-key))))

(defn- generated-content-node
  "Synthesizes a layout-only child node representing `node`'s `pseudo-key`
   (:before/:after) generated content, if cssom.core's cascade resolved a
   usable `content` value for it -- a quoted string literal, a resolved
   `attr(name)` reference (already substituted with the real element's own
   attribute value, `\"\"` if absent), a resolved `counter(name)` reference
   (already substituted with that named counter's current value as of this
   exact point in document tree order -- see cssom.core/apply-cascade's own
   docstring for how it computes that; 0 if the counter was never
   `counter-reset`/`counter-increment`-ed), or any mix of those, all
   arriving here as a plain string either way (see
   cssom.core/parse-content-value and resolve-content-value; this file
   never distinguishes where the string came from, exactly like real CSS's
   own generated-content box doesn't care whether its text came from a
   literal, attr(), or counter()). `counter()`'s two-argument
   `name, <list-style-type>` form (e.g. `counter(item, upper-roman)`),
   `url(...)`, `none`, and absent `content` all leave no :content key (a
   `counter()` reference also has no :content key when the cascade that
   resolved it had no real document-tree-walk context behind it -- see
   cssom.core/resolve-style-for's `counters` argument). Returns nil when
   there's nothing to generate, so a node with no matching ::before/::after
   rule lays out exactly as it did before this feature existed.

   `:generated/marker :outside` is lifted out of the pseudo style onto the
   node itself when this is a LIST MARKER positioned outside its item's
   principal box -- see with-implicit-list-markers, which is the only thing
   that ever sets it, and outside-marker-node?, which is what every reader
   downstream asks. An author's own `::before` never carries it: a
   `::before` is not a `::marker`, and real CSS gives it no such placement."
  [node pseudo-key]
  (let [style (pseudo-style node pseudo-key)]
    (when-let [content (:content style)]
      (cond-> {:generated/pseudo pseudo-key
               :generated/text (str content)
               :generated/style style}
        (:marker/outside? style) (assoc :generated/marker :outside)))))

(defn- generated-node?
  [node]
  (and (map? node) (boolean (:generated/pseudo node))))

(defn- outside-marker-node?
  "True for a generated node that is a list marker at
   `list-style-position: outside` -- the default, and the one every bare
   `<ul>`/`<ol>` in the corpus gets.

   Such a marker is NOT part of its item's inline content: real CSS puts it
   in a box of its own, outside the item's principal box, where it neither
   advances the line's pen nor widens the item. Every place that treats
   generated content as ordinary inline text asks this first."
  [node]
  (and (map? node) (= :outside (:generated/marker node))))

(defn- real-text-child
  "Returns the plain string content of `child` if it's a genuine DOM text
   node, else nil -- used by both merge-adjacent-text-runs' run-detection
   below and with-generated-content's own adjacent-text merge further
   below. Handles both shapes a text node can have by the time it reaches
   this file: a bare string (what kotoba.wasm.dom/tree's own :text case
   already unwraps every real text node to, see dom.cljc's `tree`, and
   what every test in this file that hand-builds a tree also uses
   directly) and the `{:node/type :text :text \"...\"}` map shape
   layout-node's own dispatch defensively also still recurs through. This
   is deliberately narrower than layout-node's dispatch as a whole (which
   also falls through to `(str node)` for anything else entirely, e.g. a
   stray number) -- returning nil for anything but those two literal
   text-node shapes means neither merge below ever fires on a real element
   child (a map with no :node/type at all, or :node/type :element) or on
   an already-generated node (see generated-node? -- a map with
   :generated/pseudo), which is exactly the boundary each feature's scope
   requires."
  [child]
  (cond
    (string? child) child
    (and (map? child) (= :text (:node/type child)) (string? (:text child))) (:text child)
    :else nil))

(defn- merge-adjacent-text-runs
  "Collapses every RUN of two-or-more consecutive real DOM text-node
   children (real-text-child) in `children` into a single bare-string
   child (the concatenation of each run member's own text, in document
   order) -- a run boundary is any non-text child (an element, or --
   though this never actually occurs pre-with-generated-content, since
   generated nodes are synthesized only inside that function, after this
   one has already run -- a generated-content node), so only children
   genuinely ADJACENT in the vector, with nothing else in between, ever
   combine. A lone text child (a 'run' of one) still passes through this
   function, just re-wrapped as a bare string when it started as the
   `{:node/type :text ...}` map shape -- harmless, since layout-node's own
   dispatch already treats that map shape and the bare string it carries
   identically (see real-text-child's docstring), so this is a no-op
   change in output for every child that wasn't actually part of a
   multi-node run.

   This is a REAL shape this file's own upstream HTML parser produces, not
   a hypothetical one to guard against just in case: kotoba-lang/htmldom's
   tokenizer discards HTML comments as producing no token at all (see its
   `tokenize`'s comment-handling branch), so `<p>Hello <!--c-->world</p>`
   parses to a `<p>` element with TWO adjacent sibling `:text` DOM
   children -- \"Hello \" and \"world\" -- with nothing else between them
   (verified directly against kotoba-lang/htmldom's own
   parse-into-document + kotoba.wasm.dom/tree: the comment contributes no
   node of any kind, so the text before and after it end up as two
   consecutive children of the same parent). More than one comment in a
   row produces a correspondingly longer run (e.g. `<p>a<!--1-->b<!--2-->
   c</p>` -> three adjacent text children `[\"a\" \"b\" \"c\"]`) -- hence
   merging a whole RUN, not just a fixed pair. Without this merge,
   layout-children-block would render each run member as its own stacked
   block row (this file's general no-inline-flow behavior, see the ns
   docstring) instead of real CSS's one contiguous line, since a real
   browser always coalesces adjacent DOM Text nodes into one contiguous
   run for rendering -- this isn't itself a CSS inline-layout feature, just
   how adjacent text nodes paint, which is exactly why plain string
   concatenation (not a new draw-op capability) is sufficient here: unlike
   a real inline-level ELEMENT adjacent to text (which may carry its own
   color/font-size this file's single-color-per-run `:text` draw-op has no
   way to represent on the same line as another run, see
   merge-generated-with-text's docstring for that exact limitation), two
   adjacent real text nodes never carry separate styling of their own --
   they inherit identically from the same parent -- so concatenating their
   strings is a complete, not approximate, fix for this specific shape.

   Called from with-generated-content, BEFORE its own ::before/::after
   adjacency check, so the two features compose correctly: a ::before/
   ::after directly bordering what was originally several real text-node
   siblings sees (via real-text-child) the WHOLE already-merged run as its
   one adjacent text child, not just the nearest fragment of it -- e.g.
   `<p>::before{content:\"X \"}` immediately followed by two real text
   children `[\"hello \" \"world\"]` merges all three (generated + both
   real text children) into the single run \"X hello world\", not two
   separate runs. A run interrupted by an ELEMENT child (e.g.
   `<li>a<b>x</b>b</li>`) is NOT merged across that element -- `a` and the
   later `b` are two separate one-node runs, each passed through
   unmerged/unchanged, exactly this file's pre-existing (still broken,
   still out of scope) behavior for text-vs-element adjacency (see the ns
   docstring's `<li>text<b>bold</b></li>` example)."
  [children]
  (->> (vec children)
       (partition-by #(some? (real-text-child %)))
       (mapcat (fn [group]
                 (if (some? (real-text-child (first group)))
                   [(apply str (map real-text-child group))]
                   group)))
       vec))

(defn- merge-generated-with-text
  "Merges synthesized generated-content node `gen` (see
   generated-content-node) with ONE directly-adjacent real text-node
   sibling's already-extracted string `text` (see real-text-child) into a
   SINGLE generated node whose :generated/text is the concatenation --
   see with-generated-content for why (so the pair lays out as one
   layout-text call / one shared line box instead of two independent
   layout-children-block rows). `gen`'s own :generated/style is kept
   as-is, so the merged run's color/font-size still resolves exactly like
   an unmerged generated node's already did (its own declared color/
   font-size if any, else whatever `inherited` supplies at paint time --
   see layout-node's generated-node? branch) -- this is a deliberate,
   documented simplification versus real CSS's separate-per-run styling
   (a ::before with its OWN declared color merged with an adjacent real
   text run paints the WHOLE merged run in the pseudo-element's color, not
   just its own portion), acceptable here because this file already has
   no way to paint two different colors within one :text draw-op's single
   line, and the alternative (staying on two separate lines, today's
   actual bug) is a strictly worse divergence from real CSS than a
   single-line, single-color approximation. `before?` picks concatenation
   order (generated text first for ::before, real text first for ::after)
   so the merged string still reads in real document order either way."
  [gen text before?]
  (assoc gen :generated/text (if before?
                                (str (:generated/text gen) text)
                                (str text (:generated/text gen)))))

(defn- with-generated-content
  "Returns `children` with `node`'s ::before/::after generated-content nodes
   (see generated-content-node) spliced in as the first/last entries
   respectively -- generated content is always positioned immediately
   before a node's real children and immediately after them, mirroring how
   real CSS pseudo-elements are always the first/last box in their
   originating element's box tree.

   FIRST, before any ::before/::after handling at all, `children` is run
   through merge-adjacent-text-runs (see its own docstring for the full
   rationale): any RUN of two-or-more consecutive real text-node DOM
   children collapses into ONE text child, a real (not hypothetical) shape
   this file's own upstream HTML parser (kotoba-lang/htmldom, whose
   tokenizer discards HTML comments as producing no token) actually
   produces. Doing this FIRST, ahead of the ::before/::after adjacency
   check below, is what makes the two exceptions compose correctly: a
   ::before/::after directly bordering what was originally several real
   text-node siblings sees the WHOLE already-merged run as its one
   adjacent text child (via real-text-child), not just the nearest
   fragment of it.

   ANOTHER narrow exception to 'spliced in as its own entry': when ::before is
   immediately followed by (or ::after is immediately preceded by) a real
   text-node child with nothing else in between (see real-text-child --
   by this point, already merge-adjacent-text-runs' single combined entry
   if the original real children had more than one adjacent text node
   here), that pair is merged into a SINGLE node (see
   merge-generated-with-text) instead of two separate entries -- because
   layout-children-block (this file's block-flow child stacker) gives
   every entry in the children vector its own row, advancing the running Y
   offset by that entry's full height, with no concept of inline flow
   (multiple text/inline children sharing one line box) at all. Real CSS
   renders a ::before immediately followed by an element's own text on ONE
   line (e.g. `li::before { content: counter(x) '. ' }` immediately
   followed by that <li>'s own text, the canonical CSS-counters
   numbered-list idiom -- confirmed via kotoba-lang/browser's own live
   `#step-counter` demo) -- without this merge, this engine would instead
   render them as two stacked block rows, a real, user-visible divergence
   from real CSS (see this namespace's own docstring for the concrete
   before/after draw-op coordinates that bug produced).

   This is deliberately much narrower than general inline flow (which
   would be a large layout-engine feature in its own right, well beyond
   this fix's scope -- see this namespace's docstring): it ONLY ever
   combines a ::before/::after pseudo-element's own resolved text with ONE
   directly-adjacent real text-node sibling (itself possibly already the
   product of merge-adjacent-text-runs collapsing several), checked
   structurally (is the very next/previous entry in the children vector a
   bare text node?), not by walking or reasoning about inline-level boxes
   in general. Every other shape is left EXACTLY as unmerged/as broken as
   it already was, no worse, and never crashes:
   - the first (for ::before) or last (for ::after) real child being an
     ELEMENT instead of a text node (e.g. `<li><span>nested</span></li>`,
     or `<li>text<b>bold</b></li>` where the ::after side would see the
     `<b>` element, not text) -- real-text-child returns nil, no merge,
     still its own separate block row, same as before this feature
     existed;
   - a node with NO real children at all (::before/::after with an
     otherwise-empty element) -- nothing to merge with, unaffected;
   - a node with BOTH ::before and ::after wrapping a SINGLE shared real
     text child (e.g. `p::before{...}<p>middle</p>p::after{...}`) -- the
     ::before merge (checked first) consumes that one real text child, so
     by the time the ::after merge is checked there is no real text child
     left for it to see -- ::after stays its own separate, unmerged entry.
     This is a deliberate one-sided tie-break (never a three-way merge of
     ::before+text+::after into one node), kept simple on purpose rather
     than guessing which pairing is 'more correct' when both could apply."
  [node children]
  (let [before (generated-content-node node :before)
        after (generated-content-node node :after)
        children (merge-adjacent-text-runs children)
        ;; An OUTSIDE list marker is never merged with the item's own text:
        ;; the merge exists to put a ::before and the text after it on one
        ;; line, and an outside marker is not on that line at all -- it is
        ;; its own box beside it (outside-marker-node?). Merging it would
        ;; fuse the two into a single `:text` draw-op, which has exactly one
        ;; x, so the marker could not be placed anywhere the item's first
        ;; word is not.
        [before children] (if-let [t (and before
                                          (not (outside-marker-node? before))
                                          (seq children)
                                          (real-text-child (first children)))]
                             [(merge-generated-with-text before t true) (subvec children 1)]
                             [before children])
        [after children] (if-let [t (and after (seq children) (real-text-child (peek children)))]
                            [(merge-generated-with-text after t false) (pop children)]
                            [after children])
        children (if before (into [before] children) children)
        children (if after (conj children after) children)]
    children))

;; ---- implicit <ul>/<ol> default `<li>` markers (UA-stylesheet defaults) ----
;;
;; Real browsers render a bullet ("•") before every <li> whose DIRECT PARENT
;; is a <ul>, and an auto-incrementing decimal number ("1.", "2.", ...)
;; before every <li> whose direct parent is an <ol> -- entirely from the
;; browser's own USER-AGENT stylesheet, with ZERO author CSS required. This
;; engine has no built-in UA-stylesheet / tag-name-based default styling
;; concept at all (cssom.core's cascade only ever resolves declarations an
;; author's own stylesheet or inline style actually contributed -- see its
;; namespace docstring; confirmed via grep before this feature existed that
;; this file had no list-style/marker/tag-default concept either), so before
;; this feature, a bare `<ul><li>Apple</li></ul>` rendered ONLY the literal
;; text "Apple" -- no marker of any kind, confirmed via kotoba-lang/browser's
;; own live demo.
;;
;; Rather than inventing new rendering machinery, this reuses the EXACT same
;; generated-content pipeline the canonical explicit-CSS numbered-list idiom
;; already exercises end to end (`li::before { content: counter(x) '. ';
;; }`, see with-generated-content/generated-content-node above): an implicit
;; marker is synthesized as if it were the <li>'s own cascade-resolved
;; `:pseudo/before` attr (`{:content \"1. \"}` / `{:content \"• \"}`) --
;; so it flows through generated-content-node/with-generated-content
;; completely unmodified the next time layout-node recurses into that <li>
;; as its own node, including the SAME-LINE merge with the <li>'s own real
;; text-node child (merge-generated-with-text) that already makes "1. " +
;; "Apple" render as ONE shared line rather than two stacked block rows,
;; exactly like the explicit-CSS idiom does.
;;
;; Numbering is DELIBERATELY NOT implemented via the general-purpose
;; counter-reset/counter-increment machinery (cssom.core's per-document
;; counters map, threaded through apply-cascade's own tree walk) -- see
;; with-implicit-list-markers below for the much simpler, purely positional
;; computation used instead, and why: reusing the real counters machinery
;; here would risk interfering with a page author's OWN, independent,
;; explicit counter-reset/counter-increment usage elsewhere on the same page
;; (this engine's counters, per cssom.core's own documented simplification,
;; live in ONE flat per-document namespace, not scoped per list the way real
;; CSS technically allows). A purely positional count (this <li>'s 1-based
;; index among its OWN parent's direct <li> children, in document order)
;; needs no shared namespace at all, so it can never collide with -- or be
;; perturbed by -- anything an author's stylesheet does with counter().
;;
;; Hooked into layout-node's :element branch (see with-implicit-list-markers'
;; call site) at the SAME point with-generated-content already runs: every
;; element's own DIRECT children are inspected once, right before recursing
;; into them -- exactly the information this feature needs (a <ul>/<ol>
;; container inspecting its own direct :li children) and nothing more, no
;; tree-wide pass, no new traversal.

(defn- pseudo-content
  "The already-cascade-resolved `content` string of `node`'s `pseudo-key`
   (:before/:after) generated content, or nil if no rule targets that
   pseudo-element at all, OR one does but never resolved a usable `content`
   value (see generated-content-node's own docstring for the several ways
   that can honestly happen, e.g. `p::before { color: red }` with no
   `content` declared at all -- `node`'s :pseudo/before attr would still be
   a non-nil map in that case, just one with no :content key). Used by
   with-implicit-list-markers to decide whether an <li> already has its OWN
   explicit ::before content (see its docstring for why that must skip the
   implicit marker rather than silently clobbering it) -- checking
   specifically for :content, not merely truthiness of the whole
   :pseudo/before attr, is what correctly still allows an implicit marker
   on an <li> whose only ::before rule sets some other property with no
   content of its own."
  [node pseudo-key]
  (:content (pseudo-style node pseudo-key)))

(defn- list-style-none?
  "True when `node`'s OWN already-cascade-resolved `list-style` or
   `list-style-type` style property is literally `none` -- the one
   plausible way a page author can already ask for no markers even though
   this engine has no broader list-style-type property support yet (see
   with-implicit-list-markers' docstring for the exact suppression rule
   this backs). Deliberately narrow, matching this file's existing bare-
   keyword style-value comparisons elsewhere (e.g. `(= \"border-box\"
   (:box-sizing st))`): no shorthand-with-other-values parsing (`list-style:
   none outside` is out of scope, since general list-style-type/list-style-
   position support is itself out of scope, see the ns docstring), no
   case-folding (consistent with every other bare-keyword style comparison
   in this file)."
  [node]
  (or (= "none" (style node :list-style))
      (= "none" (style node :list-style-type))))

(defn- list-style-inside?
  "True when `node`'s OWN cascade-resolved `list-style-position` is
   literally `inside` -- the non-default value, which puts the marker back
   INSIDE the item's principal box as the first thing on its first line.

   Measured in Brave, at 14px monospace, `<ul><li><a>First section</a>`:
   the `<a>` is at x=40 (the item's content edge) by default and at x=59
   under `list-style-position: inside`, i.e. the marker advances the line
   by 19px there and by nothing at all here. The same pair inside a `<td>`
   shrink-wraps the cell to 63px and 82px respectively -- so this is not
   only where the marker paints, it is whether the item is 19px wider.

   The `inside` advance this engine produces is the WIDTH OF THE MARKER
   STRING, which is exactly what Brave uses for an `<ol>` (measured: 21px
   for `1. `, 28 for `10. `, 35 for `100. `) and 5.3px more than it uses
   for a `<ul>`, where Brave's disc marker box is 19px at 14px, 14 at 10px
   and 37 at 28px -- a function of the font-size only, identical for Arial
   and monospace and for `list-style-type: square`, i.e. not the bullet
   glyph's own advance (6.7px) at all. No corpus case measures `inside`, so
   that 5.3px is recorded here rather than modelled from three points.

   Deliberately as narrow as list-style-none? above, and for the same
   reason: the `list-style` SHORTHAND is not parsed (`list-style: disc
   inside` is not recognised), and there is no case-folding. What this does
   NOT read is an INHERITED value: real CSS inherits `list-style-position`,
   and this engine threads only a fixed set of properties through
   `inherited`. with-implicit-list-markers therefore asks it of both the
   container and the item, which is where an author writes it in practice
   (both forms are measured above)."
  [node]
  (= "inside" (style node :list-style-position)))

(defn- implicit-marker-content
  "The implicit marker text for a direct `:li` child of a `parent-tag`
   (:ul/:ol) container, already resolved to its final displayed `number`
   (1-based, `start`/`value=`/`reversed`-adjusted -- see
   with-implicit-list-markers, the only caller, for all of that
   direction/offset logic; this fn itself is direction-agnostic, just
   rendering whatever final `number` it's handed) -- `\"• \"` (one
   bullet character, one space) for :ul, `\"<number>. \"` for :ol,
   matching real browsers' own UA-stylesheet defaults (see the section
   comment above for the remaining, deliberately bounded scope: no
   other list-style-type values). `number` is a plain arithmetic value,
   never clamped to 1 -- a negative or zero `start`/`value=` is real,
   legal HTML too (e.g. `<ol start=\"-2\">` legitimately starts at -2).
   Any other `parent-tag`
   returns nil (with-implicit-list-markers never actually calls this for
   one, but kept total rather than partial defensively), matching the
   'no marker' contract generated-content-node already establishes for
   absent `:content`."
  [parent-tag number]
  (case parent-tag
    :ul "• "
    :ol (str number ". ")
    nil))

(defn- with-implicit-list-markers
  "Returns `children` (a container element's OWN direct children, i.e.
   `(:children node)` before with-generated-content ever sees them) with
   each direct `:li` element child's `:attrs` augmented with a synthetic
   `:pseudo/before {:content <marker>}` entry -- UNLESS that child already
   has its own explicit ::before content (see pseudo-content), or the
   marker is suppressed (see list-style-none?) -- but ONLY when `node` (the
   container currently being laid out) is itself a `<ul>` or `<ol>`
   element. Every other `node` tag is a complete no-op, returning `children`
   unchanged -- in particular a bare `<li>` with no `<ul>`/`<ol>` parent at
   all is NEVER touched by this function, because it is only ever invoked
   from the PARENT's own perspective (see layout-node's call site): if the
   parent isn't itself a :ul/:ol element, this <li> simply never gets a
   synthetic :pseudo/before written onto it by anyone, matching real
   UA-stylesheet semantics exactly (only an <li> whose DIRECT parent is
   <ul>/<ol> gets a default marker at all).

   Once written, that synthetic :pseudo/before attr flows through the EXACT
   same code path a real, author-written `li::before { content: ... }` rule
   already does (generated-content-node/with-generated-content), the next
   time layout-node recurses into that <li> as its own node -- see the
   section comment above for why this reuse, rather than new rendering
   machinery, is both sufficient and correct (including the same-line merge
   with the <li>'s own real text-node child).

   Suppression, checked per <li> (never globally short-circuiting the rest
   of the function -- one suppressed <li> must not affect its siblings'
   markers or numbering, matching real CSS: `list-style: none` only ever
   hides that one marker BOX, it does not renumber anything):
     - the container (`node`) itself has `list-style`/`list-style-type:
       none` (checked ONCE up front, gating the whole function -- if the
       container suppresses its markers, no direct <li> child gets one, and
       `children` returns unchanged) -- real CSS normally sets this on the <ul>/<ol>
       and INHERITS it to every <li>, but this engine has no general
       arbitrary-property inheritance machinery to lean on (only :color/
       :font-size are threaded through `inherited` -- see node-style/
       layout-node), so this checks the container's OWN style directly,
       which is the one place a real author's `list-style-type: none`
       almost always actually appears in source.
     - the <li> ITSELF has `list-style`/`list-style-type: none` on its own
       cascade-resolved style (checked independently of the container-level
       check above, so an author can suppress a single <li>'s marker
       without touching the container at all).
     - the <li> already has its own explicit ::before `content` (see
       pseudo-content) -- a deliberate compose-vs-skip decision, not an
       oversight: :pseudo/before is a single-value attrs key
       (generated-content-node/with-generated-content only ever reads ONE
       value from it), so unconditionally overwriting it here would
       SILENTLY DELETE the author's own explicit ::before content rather
       than adding to or alongside it -- a real, user-visible regression
       (the author's own marker/icon/text vanishing) strictly worse than
       this engine simply not adding an extra implicit one. An explicit
       ::before is a deliberate, independent authoring choice (most
       plausibly itself a hand-written marker, e.g. the exact
       `li::before { content: counter(x) '. '; }` idiom this whole feature
       generalizes) that this function leaves completely alone -- the
       explicit ::before still applies as its own, separate mechanism,
       unaffected by this feature's existence.

   PLACEMENT (`list-style-position`). The synthetic entry carries
   `:marker/outside? true` unless the container or the <li> declares
   `list-style-position: inside` (see list-style-inside?, which has the
   measurements). That flag is what downstream reads to keep the marker out
   of the item's inline content entirely -- it neither advances the line's
   pen (inline-line-breaker), nor widens the item (inline-max-content-
   width), nor joins the item's own text run (with-generated-content). It is
   painted immediately to the LEFT of the item's content edge instead.

   What that placement is and is NOT verified against: the conformance
   oracle reports one box per ELEMENT, and a `::marker` is not an element --
   `getBoundingClientRect` has nothing to return for it, so where the marker
   itself paints cannot be checked by that harness in either direction. What
   IS checked, and is what the three failing cases measured, is the
   position and width of the item's CONTENT: Brave puts the `<a>` of
   `<ul><li><a>First section</a></li></ul>` at x=40, the item's content
   edge, where this engine had it at 53.7 -- exactly one marker advance in
   -- and shrink-wraps a `<td>` holding a two-item list to 63px where this
   engine gave 76.7. Both follow from the marker taking no inline space;
   neither says where the glyph lands. `list-style-position: inside`, whose
   marker DOES take inline space, is measurable that way and is measured
   (again, list-style-inside?), which is the closest this harness can get to
   the outside marker's own width.

   Numbering (`:ol` only): a purely POSITIONAL 1-based index among this
   node's OWN direct `:li` element children, in document order -- counting
   EVERY direct <li> child (even a suppressed one, so suppressing one <li>'s
   marker does not renumber the ones after it, matching real CSS), and
   completely ignoring any non-<li> child (a stray text node, e.g.
   whitespace between <li> tags in real markup, or any other element)
   without incrementing the count. `node`'s own `start=` HTML attribute
   (parsed once, up front, via `parse-int` with a fallback of 1 -- i.e.
   absent/malformed `start=` behaves exactly as before this offset
   existed) shifts every position by a constant amount, so the
   position-counting logic above (suppressed <li>s still counted,
   non-<li> children ignored) is completely unaffected by it -- `start`
   only changes what number a given position DISPLAYS as, never which
   position an <li> occupies.

   Each <li>'s OWN `value=` HTML attribute (parsed per-<li>, independent
   of `start=`) overrides that one item's displayed number directly --
   real HTML5 semantics: a following sibling with no `value=` of its own
   then continues counting from `value + 1`, not from the position it
   would otherwise have occupied, so `value=` shifts every LATER item's
   number too, not just the one it's set on. This is implemented by
   threading the running DISPLAYED number itself as the reduce's own
   accumulator (rather than a separate position counter with `start`
   applied afterward, the shape before this feature) -- each counted <li>
   either takes its own explicit `value=` verbatim or increments the
   previous running number by 1, so a later plain <li> automatically
   continues from wherever the last explicit `value=` (or `start`) left
   off, with no separate bookkeeping needed. A malformed/non-numeric
   `value=` is ignored (falls through to plain +1 continuation), the
   same degrade-don't-guess convention `start=` already established. A
   suppressed <li>'s own `value=` still shifts subsequent numbering
   (consistent with suppression never affecting POSITION counting above
   -- CSS `list-style: none` only hides the marker box, it doesn't
   remove the element from HTML5's own counting semantics either).

   `node`'s own `reversed` HTML5 boolean attribute (checked via
   truthy-attr? -- the same real, common-XHTML-form-aware boolean check
   already established for `checked`/`open`, so `reversed=\"reversed\"`
   is recognized identically to a bare `reversed`) reverses the counting
   DIRECTION: each counted <li> DECREMENTS the running number instead of
   incrementing it. When `reversed` is present with NO explicit `start=`,
   `start` itself defaults to the total COUNT of direct <li> children
   (matching real HTML5/browser semantics exactly -- a 3-item reversed
   list numbers 3, 2, 1) rather than the normal default of 1; an
   explicit `start=` still simply sets what the FIRST item displays,
   counting down from there instead of up. Every other mechanism above
   (per-<li> `value=` override, malformed-value/malformed-start
   graceful fallback, suppressed-<li> still shifting later numbering) is
   completely direction-agnostic -- `value=`'s own override always wins
   regardless of direction, and a later plain <li> simply continues by
   whichever step (+1 or -1) is currently active.

   This is intentionally NOT the same number space as cssom.core's own
   counter-reset/counter-increment machinery -- see the section comment
   above for why -- so it can never collide with (or be perturbed by) a
   page author's own, independent explicit counter usage elsewhere on the
   page. A NESTED <ol>/<ul> inside
   one of these <li>s gets its own, completely independent count for free:
   it is never one of THIS node's own direct children (it's a grandchild,
   nested one level deeper, inside one of the <li>s), so it is only ever
   processed by a LATER, separate call to this same function -- once
   layout-node recurses into that inner <ol>/<ul> as ITS OWN node -- which
   starts its own loop fresh from position 1 (and reads its OWN `start=`/
   `reversed` attributes, if any -- entirely independent of whatever the
   outer <ol>'s own were), with no shared state of any kind between the
   two calls."
  [node children]
  (let [parent-tag (:tag node)]
    (if (and (contains? #{:ul :ol} parent-tag) (not (list-style-none? node)))
      (let [reversed? (truthy-attr? (get-in node [:attrs :reversed]))
            li-count (count (filter #(and (map? %) (= :li (:tag %))) children))
            start (parse-int (get-in node [:attrs :start]) (if reversed? li-count 1))
            step (if reversed? dec inc)
            init-n (if reversed? (inc start) (dec start))
            container-inside? (list-style-inside? node)]
        (first
         (reduce (fn [[out n] child]
                   (if (and (map? child) (= :li (:tag child)))
                     (let [n (or (parse-int (get-in child [:attrs :value]) nil) (step n))]
                       (if (or (list-style-none? child) (pseudo-content child :before))
                         [(conj out child) n]
                         [(conj out (assoc-in child [:attrs :pseudo/before]
                                              (cond-> {:content (implicit-marker-content parent-tag n)}
                                                (not (or container-inside? (list-style-inside? child)))
                                                (assoc :marker/outside? true))))
                          n]))
                     [(conj out child) n]))
                 [[] init-n]
                 children)))
      children)))

;; ---- <details>/<summary> default disclosure hiding ----
;;
;; Real HTML5: a <details> without an `open` attribute renders ONLY its
;; first direct <summary> child -- every other direct child (including any
;; LATER <summary> siblings) is not rendered at all. Confirmed via direct
;; REPL reproduction through the real browser.core/load-html pipeline
;; before this feature existed: a bare `<details><summary>Click me</summary>
;; <p>Hidden content</p></details>` rendered BOTH the summary AND the
;; content, always, permanently -- since this engine had no notion of
;; <details>'s default disclosure hiding at all, defeating the entire
;; purpose of a real, common, no-JS-needed disclosure/spoiler/FAQ widget.
;;
;; Rather than inventing new hiding machinery, this reuses the EXACT same
;; `:style/display "none"` mechanism the general :element branch above
;; already checks per-child (`(= "none" (:display st))`) -- a hidden
;; child's :attrs gets a synthetic :style/display "none" written onto it,
;; so the next time layout-node recurses into that child as its own node,
;; it naturally takes the zero-box/zero-draw-ops branch, no different from
;; an author's own explicit `display: none` rule.
;;
;; Click-to-toggle interactivity (flipping the real `open` attribute when
;; a user clicks the <summary>, and dispatching a real `toggle` event) is
;; a SEPARATE concern, implemented in kotoba-lang/browser's
;; document_input.cljc -- this function only ever computes what a GIVEN,
;; already-resolved `open` state should render as; it has no click
;; handling of its own, mirroring the same layout/interaction split every
;; other form control in this file already has (e.g. layout-form-control
;; renders whatever :checked already says, it doesn't decide when a click
;; toggles it).

(defn- with-details-visibility
  "Returns `children` (a container element's OWN direct children) with
   every direct child EXCEPT the first `:summary` element child either
   given a synthetic `:style/display \"none\"` (an element child) or
   dropped entirely (a bare text-node child -- see below for why it can't
   just get the same attr treatment) -- but ONLY when `node` is itself a
   `:details` element AND lacks a truthy `open` attribute (see
   `truthy-attr?`). A `<details open>` (or one with no <summary> at all
   -- an unusual, out-of-scope shape; see below) is a complete no-op,
   returning `children` unchanged.

   A bare text-node child (`(string? child)`, e.g. whitespace or literal
   text written directly inside `<details>...</details>`, outside any
   wrapping element -- a real, plausible shape) has no `:attrs` map to
   write `:style/display` onto at all, unlike an element child, so it is
   REMOVED from the returned vector instead of hidden in place -- visually
   identical to display:none (nothing renders either way), and simpler
   than inventing a parallel hidden-text-node representation this engine
   has no other use for.

   The forced `:style/display \"none\"` on hidden ELEMENT children
   unconditionally overrides whatever display value that child's own
   cascade already resolved to (real UA-stylesheet disclosure hiding is
   not something an author's own CSS can straightforwardly defeat either)
   -- this is a deliberate simplification, not an oversight: real HTML5's
   actual rendering model for this uses an internal content-distribution
   mechanism stricter than any single CSS declaration an author could
   write, which this engine has no reason to emulate more precisely than
   'always hidden when closed, no override'.

   A `<details>` with NO `:summary` child at all is explicitly out of
   scope: real browsers synthesize a default 'Details' disclosure label
   in that case, which would need new synthetic content generation (like
   `implicit-marker-content` above, but for an entirely different,
   unrelated concern) -- this function simply hides/drops every child in
   that case, which is honest (nothing to click to reveal it in this
   engine either way) rather than spec-perfect."
  [node children]
  (if (and (= :details (:tag node)) (not (truthy-attr? (attr node :open))))
    (let [first-summary-index (some (fn [[i child]]
                                       (when (and (map? child) (= :summary (:tag child))) i))
                                     (map-indexed vector children))]
      (into []
            (keep-indexed (fn [i child]
                            (cond
                              (= i first-summary-index) child
                              (map? child) (assoc-in child [:attrs :style/display] "none")
                              :else nil)))
            children))
    children))

(defn- with-nested-list-margins
  "Returns `children` with every ELEMENT child carrying a synthetic
   `:ua/list-descendant` attr -- but only when `node` is itself a list
   container (`list-container-tags`) or already carries the mark itself.
   `ua-margin-y` reads it to cancel a nested list's vertical margins, the
   way Chrome's own UA stylesheet does.

   The mark propagates through EVERY element rather than only through
   `<li>` because the UA rule is a DESCENDANT selector (`:is(ul, ol) ul`),
   not a child selector: `<ul><li><div><ul>` gets the zero margin in a real
   browser too, and marking only direct children would miss it. Written
   from the PARENT's perspective and re-applied at every level, exactly the
   technique with-implicit-list-markers already uses -- a node has no
   parent pointer here, and threading one just for this would be a far
   larger change than the rule is worth.

   Text children are left alone: they have no `:attrs` map, and nothing
   reads the mark off a text node."
  [node children]
  (if (or (contains? list-container-tags (:tag node))
          (attr node :ua/list-descendant))
    (mapv (fn [child]
            (if (map? child)
              (assoc-in child [:attrs :ua/list-descendant] true)
              child))
          children)
    children))

(def ^:private inheritable-style-props
  "The `:style/*` declarations that real CSS INHERITS, in the subset this
   engine reads (see node-style). Used only by splice-display-contents,
   which has to hand a `display: contents` element's declarations to its
   children by hand because the element itself stops existing before
   layout-node ever threads its `inherited` map."
  [:color :font-size :font-weight :font-style :font-family :line-height
   :text-align :text-transform :text-decoration :white-space :overflow-wrap
   :word-break :text-shadow-x :text-shadow-y :text-shadow-blur
   :text-shadow-color :visibility :list-style-type :letter-spacing
   :word-spacing])

(defn- splice-display-contents
  "Replaces every `display: contents` child with ITS OWN children, promoted
   into this element's formatting context.

   Real CSS gives such an element no box at all: its background, border,
   padding and margin simply do not render, and its children behave as if
   they were written where it is. Measured in Brave,
   `<div style=\"display:flex\"><div style=\"display:contents\"><div>a</div>
   <div>b</div></div></div>` makes `a` and `b` the FLEX ITEMS, at x=0 and
   x=7, and reports 0x0 for the wrapper -- where this engine gave the
   wrapper a real 300x40 box and laid `a`/`b` out as its block children.
   Measured too: a `display: contents` element carrying
   `border: 5px solid; padding: 10px; margin: 8px` renders none of them (its
   children start at the parent's own content edge), while its
   `font-size: 20px` and `color` DO reach them -- inheritance survives, the
   box does not.

   That inheritance is why the promoted children carry the wrapper's
   inheritable declarations (inheritable-style-props) where they declared
   none themselves: this engine resolves inheritance while WALKING the tree,
   so a wrapper that never gets walked would otherwise drop its font and
   colour on the floor.

   The wrapper element itself is kept, emptied, so layout-node can still
   emit the 0x0 box a browser reports for it -- a hit-tester and the
   conformance harness's tag-matched geometry axis both expect an element to
   be somewhere.

   Nested `display: contents` splices recursively."
  [theme children]
  (reduce
   (fn [out child]
     (let [st (when (map? child) (node-style child theme))]
       (if (= "contents" (:display st))
         (let [inherit (into {} (for [p inheritable-style-props
                                      :let [k (keyword "style" (name p))
                                            v (get-in child [:attrs k])]
                                      :when (some? v)]
                                  [k v]))
               promoted (mapv (fn [gc]
                                (if (map? gc)
                                  (update gc :attrs #(merge inherit %))
                                  gc))
                              (:children child))]
           (-> out
               (conj (assoc child :children []))
               (into (splice-display-contents theme promoted))))
         (conj out child))))
   []
   children))

;; ---- non-rendered (metadata) elements ----
;;
;; <head>, <title>, <script>, <style>, <meta>, <link> are never part of a
;; real browser's visual rendering tree at all -- this is independent of
;; whatever `display` value a stylesheet declares for them (a real browser
;; does not let `<script style="display:block">` opt back in). This engine
;; has no separate metadata-tree/rendering-tree split the way a real
;; browser's HTML+CSS integration does (see the ns docstring for this
;; project's honestly-scoped feature set), so the narrowest correct fix is a
;; tag-name gate checked ahead of the general :element branch in
;; layout-node, deliberately NOT folded into node-style/:display -- gating
;; on the cascade's :display would let an author-declared `display: block`
;; make a <script> visible, which no real browser permits.

(def ^:private non-rendered-tags
  "Tags that always contribute zero layout box and paint zero draw-ops, full
   stop, no override mechanism -- see the section comment above.

   `:template` joins this set for the identical reason: real HTML5's
   <template> content is never part of the rendering tree at all -- it
   lives in an inert `.content` DocumentFragment a script clones from,
   not the live document -- so a real <template> holding a row/row
   prototype for later JS cloning must never visibly render, confirmed
   via direct REPL reproduction that its text content previously leaked
   into the real draw-ops right alongside genuinely visible content. This
   engine has no separate `.content` fragment concept (see this project's
   own honestly-scoped feature set), so -- exactly like the other five
   tags above -- the narrowest correct fix is the same tag-name gate
   rather than inventing a fragment split; a script can still read/clone
   a <template>'s children via the ordinary DOM tree, only PAINTING is
   suppressed here."
  #{:head :title :script :style :meta :link :template})

(defn- non-rendered-tag? [tag]
  (contains? non-rendered-tags tag))

(declare layout-node)

(def ^:private flex-item-shrink-to-fit-measure-width
  "An effectively-unconstrained width used to discover a flex row-item's
   own natural (max-content) width when it has no explicit :width --
   large enough that no realistic word-wrap constraint would ever kick in
   first."
  1000000)

(defn- flex-item-natural-text-width
  "flex-item-main-width's own scope-cut: the one common flex-item shape
   this handles is a leaf element wrapping EXACTLY one text child (an
   ordinary <button>/<span>/<a> label -- the overwhelming majority of
   real-world flex items). Lays out just that text child directly, not
   the wrapping element -- layout-block has no shrink-to-fit concept of
   its own at all, its own node-w always defaults to whatever avail-width
   it's given regardless of children's content (confirmed via direct REPL
   reproduction: laying out the WRAPPING element itself against an
   unconstrained width just returns that same unconstrained width back,
   since layout-block's own width resolution never looks at children).
   Merges the wrapping element's own resolved font/color/text-transform
   properties into `inherited` first (mirroring layout-node's own
   :element-branch merge, minus the paint-only properties that don't
   affect measured width), so an item with its own font-size/font-weight
   override still measures against the right metrics."
  [theme opacity inherited st text]
  (let [font-size (parse-int (:font-size st) (:font-size inherited))
        text-inherited (assoc inherited
                              :color (or (:color st) (:color inherited))
                              :font-size font-size
                              :line-height (resolve-line-height (:line-height st) font-size
                                                                (or (inherited-line-height inherited font-size)
                                                                    (:line-height theme)))
                              :line-height/factor (line-height-factor (:line-height st)
                                                                      (:line-height/factor inherited))
                              :font-weight (or (:font-weight st) (:font-weight inherited))
                              :font-style (or (:font-style st) (:font-style inherited))
                              :font-family (or (:font-family st) (:font-family inherited))
                              :text-transform (or (:text-transform st) (:text-transform inherited)))
        text-box (:box (layout-node theme 0 0 flex-item-shrink-to-fit-measure-width opacity text-inherited text))]
    ;; the SAME horizontal inset layout-block will subtract -- see
    ;; intrinsic-inset-x for why the uniform `:padding` is not it.
    (+ (:w text-box) (intrinsic-inset-x st))))

(def ^:private inline-atomic-tags
  "Inline-level elements that are ATOMIC: they participate in a line box as
   a single unbreakable box of their own intrinsic size, rather than
   contributing text that can wrap. Real CSS calls these atomic inline-level
   boxes — replaced elements and form controls.

   They differ from inline-level-tags in every step that matters: their
   children are NOT flattened into the run (a `<button>`'s label is laid
   out inside the button's own box by layout-block/layout-form-control,
   which already works), their width comes from laying the element out and
   reading its box rather than from measuring text, and their baseline is
   the box's BOTTOM edge (real CSS `vertical-align: baseline` aligns a
   replaced box's bottom margin edge with the text baseline), not a font
   ascent.

   `:svg`/`:canvas`/`:video`/`:audio`/`:iframe` are deliberately still
   absent: this engine has no rendering for any of them, so giving them an
   inline box would place an empty rectangle in the middle of a sentence
   rather than fix anything."
  #{:img :input :button :select :textarea})

(def ^:private inline-atomic-default-input-chars
  "HTML's own default `size` for a text input is 20 characters, which is
   where every browser's ~20ch default text-field width comes from."
  20)

(defn- laid-out-children
  "The children `node` will actually be laid out with: its own
   `:children` after every box-tree fixup this file applies -- a
   `<details>`'s collapsed content, an implicit list marker, ::before/
   ::after generated content, the nested-list margin rule, and
   `display: contents` promotion.

   Extracted because there are now TWO readers of it and they must not
   drift: layout-node, which lays these children out, and the intrinsic
   sizing path (flex-item-main-width, atomic-intrinsic-width,
   block-max-content-width), which has to MEASURE the same ones.

   The drift this closes, measured: a `<ul>` inside a shrink-to-fit
   `<td>` was MEASURED from its bare `<li>` text (`one` -> 21px) and then
   LAID OUT with the `\u2022 ` marker with-implicit-list-markers adds
   (`\u2022 one` -> 35px), so every item was 14px wider than the box it
   had just been given and wrapped to two lines -- a cell reporting the
   browser's exact width and twice its height."
  [theme node]
  (splice-display-contents
   theme
   (with-nested-list-margins
    node
    (with-generated-content node (with-implicit-list-markers node (with-details-visibility node (:children node)))))))

(declare inline-fragments inline-tokens inline-flow-candidate? inline-inherited
         inline-max-content-width block-max-content-width intrinsic-flow-children
         font-metrics measure-child)

(defn- atomic-intrinsic-width
  "The available width an atomic inline is laid out at — its intrinsic
   size, NOT the full line width `resolve-width` would hand an ordinary
   block child.

   This is the whole difference between an `<input>` that sits in a
   sentence and one that swallows the line: laid out as a block child a
   form control fills its container (correct for a block, wrong for an
   inline), and until this existed every atomic inline was wider than the
   line it was placed on and therefore always wrapped alone — the exact
   symptom the Blink conformance harness reported as `inline-replaced 0/3`.

   Resolution order mirrors real CSS's own: an explicit width (including
   the `<img width>` presentational hint, see presentational-size) wins;
   otherwise each control type contributes the intrinsic size the HTML
   spec gives it (`size` characters for text-like inputs, defaulting to
   20; a small square for checkbox/radio; the widest option label for a
   `<select>`); everything else falls back to the same shrink-to-fit
   natural width flex items already use (flex-item-main-width), which is
   what gives `<button>go</button>` a button-sized box.

   Used wherever an atomic element needs a size of its own: inside a line
   box, and (through flex-item-main-width/measure-child) as a flex item, a
   grid item or a table cell's content. A BLOCK-level form control still
   fills its container, which is what a browser does too."
  [theme content-w opacity inherited child st]
  (let [tag (:tag child)
        font-size (parse-int (:font-size st) (:font-size theme))
        measure-text (:measure-text theme)
        ;; Use the host's real measurement when it has one -- a control's
        ;; width is `size` characters of ITS OWN font (see ua-control-font),
        ;; and this engine's 0.6-em approximation is exactly the thing a
        ;; host supplies :measure-text to replace. Measured against the
        ;; browser, the approximation left an <input> 9px narrow.
        char-w (if measure-text
                 (measure-text "0" font-size (:font-weight st) (:font-style st) (:font-family st))
                 (long (* 0.6 font-size)))
        ;; the intrinsic size is a BORDER box, and the HORIZONTAL padding
        ;; is what matters for a width -- a <button>'s UA padding is 6px at
        ;; the sides and 1px top/bottom, so charging the uniform value left
        ;; it 10px narrow.
        inset-x (+ (or (:padding-left st) (:padding st))
                   (or (:padding-right st) (:padding st))
                   (* 2 (:border-width st)))
        natural
        (cond
          (:width st) (resolve-width st content-w)

          ;; An `inline-flex` box sizes ITSELF from its flex items
          ;; (layout-flex's `inline?` branch shrink-wraps to `auto-main`,
          ;; gaps and blockified items and all), so the only useful answer
          ;; here is the upper bound it may not exceed. Measuring its
          ;; children as an inline run instead -- what the `:else` branch
          ;; below would do -- gets the items' text but not the `gap`
          ;; between them: measured in Brave, a `gap: 6px` inline-flex
          ;; holding `a` and `b` is 20px wide, and the inline run says 14.
          ;; An `inline-grid` box is the same story for the same reason:
          ;; layout-grid's own `inline?` branch sizes it from its TRACKS
          ;; (see its `intrinsic-cw`), and measuring its children as an
          ;; inline run instead reports the items' text without the track
          ;; widths at all -- for `grid-template-columns: 30px 30px`
          ;; holding `a` and `b` that is ~14px against the browser's 60,
          ;; and the min below would then clamp the grid to the wrong
          ;; answer rather than merely bound it.
          (contains? #{"inline-flex" "inline-grid"} (:display st))
          content-w

          (contains? #{:input :textarea} tag)
          (let [input-type (str/lower-case (str (or (get-in child [:attrs :type]) "text")))]
            (if (contains? #{"checkbox" "radio"} input-type)
              13
              (+ (* char-w (parse-int (get-in child [:attrs :size]) inline-atomic-default-input-chars))
                 inset-x)))

          (= :select tag)
          ;; Widest option label -- a <select> is as wide as the longest
          ;; thing it can display -- plus the fixed dropdown-arrow slack
          ;; (select-arrow-width) that made an empty select 22px wide in
          ;; every browser measurement.
          ;;
          ;; Measured per LABEL rather than as `char-w * longest-count`:
          ;; a control's font is proportional Arial (see ua-control-font),
          ;; where the old count-based estimate charged `alpha` and `beta`
          ;; the same width per character as `MMMM`. Chrome's own number is
          ;; `ceil` of the rendered text width, which is why the ceil is
          ;; here and not a rounding accident: an empty select is 22, and
          ;; `alpha` (32.63px) makes it 55, not 54.63.
          ;;
          ;; Read straight off each <option>'s own text children rather
          ;; than through option-label, which answers a different question
          ;; (the label for one selected VALUE).
          (let [labels (select-option-labels child)
                label-w (fn [s] (if measure-text
                                  (measure-text s font-size (:font-weight st)
                                                (:font-style st) (:font-family st))
                                  (* char-w (count s))))
                widest (apply max 0 (map label-w labels))]
            (if (select-multiple? child)
              ;; An open listbox has no arrow: it is exactly its widest
              ;; option ROW (label + 2px of padding per side) inside its
              ;; borders, and NOT rounded up -- measured, a listbox holding
              ;; `a` is 13.4219 wide (2 border + 4 padding + 7.4219 text),
              ;; fraction and all, where the closed dropdown's width is a
              ;; whole number.
              (+ widest (* 2 select-option-side-padding) inset-x)
              (+ (Math/ceil widest) select-arrow-width inset-x)))

          ;; A <button> and any other atomic element with no intrinsic
          ;; rule of its own shrink-wraps to its content, exactly as a flex
          ;; item does. Inlined rather than delegating to
          ;; flex-item-main-width, which now consults THIS function for
          ;; atomic tags -- delegating would recurse forever.
          ;; A <button>'s label is measured in the CONTROL font, not the
          ;; inherited page font -- the same rule that gives every control
          ;; its own metrics (ua-control-font). Measuring it with the page
          ;; font left a button ~14px narrow against the browser.
          ;;
          ;; MARKUP inside the label counts, and used to be dropped on the
          ;; floor: `real-text-child` sees a control's DIRECT text children
          ;; only, so `<button>save <b>now</b></button>` was measured as
          ;; `save ` -- 36px of content where Brave reports 58.5, i.e. a
          ;; button too narrow to hold its own label. The consequence was
          ;; not a 22px box error, it was a WRAPPED button: the label broke
          ;; onto a second line inside the control, the control grew to 34px
          ;; and its first line's text op ended up ABOVE the control's own
          ;; box, which is how a button's private formatting context leaked
          ;; into the surrounding line (:form/button-with-nested-inline
          ;; wanted `["tail"]` and got `["save tail"]`). The `:else` branch
          ;; below already knew how to measure mixed inline content; this
          ;; branch shadowed it for every form-control tag.
          ;;
          ;; Routed through inline-max-content-width ONLY when there is
          ;; markup to account for. The all-text path is left byte-identical
          ;; on purpose: the two disagree about leading/trailing whitespace
          ;; (a browser collapses `<button> ok </button>` to `ok`; the join
          ;; below charges the spaces), and that is a separate measurement
          ;; from this one.
          ;; ...and the label is rounded UP to a whole pixel before the
          ;; inset is added. Not decoration and not a fudge: this width is
          ;; handed straight back as the box's AVAILABLE width, and
          ;; layout-block then re-derives the content width by SUBTRACTING
          ;; the same inset. `(- (+ inset label) inset)` is not `label` in
          ;; binary floating point -- measured, `save now` is 57.89096472741182
          ;; and the round trip returns 57.8909647274118, one ulp short --
          ;; and one ulp short is enough for the line breaker to decide the
          ;; label does not fit the box that was sized for it. The button
          ;; then wrapped its own label and doubled in height. Rounding the
          ;; content up makes the subtraction exact (whole pixel minus whole
          ;; pixel) instead of lucky, and it is the same direction Chrome's
          ;; own intrinsic sizing rounds -- a box may be a fraction wider
          ;; than its content, never a fraction narrower.
          (contains? form-control-tags tag)
          (+ inset-x
             (Math/ceil
              (let [cs (intrinsic-flow-children theme (laid-out-children theme child))
                   markup? (some #(and (map? %) (= :element (:node/type %))) cs)]
               (if (and markup? (every? #(inline-flow-candidate? theme %) cs))
                 ;; `st` is the CONTROL's own style, so inline-inherited
                 ;; (inside inline-max-content-width) resolves the whole
                 ;; label -- nested elements included -- against the control
                 ;; font rather than the page font.
                 (inline-max-content-width theme content-w opacity inherited st cs)
                 (let [label (->> (:children child) (keep real-text-child) (str/join ""))]
                   (if measure-text
                     (measure-text label font-size (:font-weight st) (:font-style st) (:font-family st))
                     (* (count label) char-w)))))))

          :else
          ;; An out-of-flow child contributes nothing to an intrinsic width
          ;; -- see intrinsic-flow-children, which measures the case.
          (let [cs (intrinsic-flow-children theme (laid-out-children theme child))]
            (cond
              (and (= 1 (count cs)) (string? (first cs)))
              (flex-item-natural-text-width theme opacity inherited st (first cs))

              ;; MIXED inline content counts too: `<button>save <b>now</b>
              ;; </button>` fell through to the container width, so a button
              ;; with any markup in its label swallowed the whole line and
              ;; pushed the text after it onto the next one.
              ;;
              ;; The inset added here is content-inset, not this fn's own
              ;; inset-x, only because that is what inline-max-content-width
              ;; used to add for itself -- kept verbatim so moving the inset
              ;; out of that function changes no number. The two used to
              ;; disagree about per-side padding AND about a non-border-box
              ;; border; the border half is reconciled (content-inset counts
              ;; it in both box-sizing modes now, exactly as inset-x always
              ;; did), and what is left is the per-side padding, on which
              ;; content-inset reads the uniform value alone. Reconciling
              ;; that is a separate change with its own measurements.
              (and (seq cs) (every? #(inline-flow-candidate? theme %) cs))
              (+ (inline-max-content-width theme content-w opacity inherited st cs)
                 (* 2 (content-inset st)))

              ;; an empty box is its own insets -- without this an
              ;; `inline-block` wrapping nothing took the whole container
              ;; width and dropped out of its line.
              (empty? cs) inset-x

              ;; anything else is a block container: its widest child, not
              ;; whatever box happens to contain IT (see
              ;; block-max-content-width, which subsumes the
              ;; single-element-child rule this used to spell separately
              ;; and adds the child margins that rule dropped).
              :else
              (+ (block-max-content-width theme content-w opacity inherited st cs)
                 inset-x))))]
    (max 0 (min content-w natural))))

(defn- inline-max-content-width
  "The width the RUN itself would occupy on ONE line -- real CSS's
   max-content size for a sequence of inline-level children.

   Reuses the inline machinery rather than approximating: the same
   fragments, the same tokenizer (so whitespace collapses exactly as it
   will when the run is really laid out), and the same per-character
   measurement the line breaker uses.

   The containing box's own padding/border is NOT included: this measures
   a run, and a run has no insets. Every caller adds the inset it is
   responsible for -- which is what lets block-max-content-width compare
   an inline run against a block child's width without one of them
   carrying the parent's padding twice."
  [theme content-w opacity inherited st children]
  (let [inherited (inline-inherited inherited st)
        tokens (inline-tokens (:fragments (inline-fragments theme inherited opacity content-w children)))
        measure-text (:measure-text theme)
        w-of (fn [text style]
               (if measure-text
                 (measure-text text (:font-size style) (:font-weight style)
                               (:font-style style) (:font-family style))
                 (* (count text) (long (* 0.6 (:font-size style 14))))))]
    (reduce (fn [total t]
              (case (:kind t)
                :break total
                ;; an OUTSIDE list marker is not in the run's content: it
                ;; takes no inline space, so it cannot widen the box that
                ;; shrink-wraps around it. Measured in Brave, a `<td>`
                ;; holding `<ul><li>one</li><li>two</li></ul>` is 63px wide
                ;; and 82px under `list-style-position: inside` -- the
                ;; second is a real 19px contribution, the first is none.
                :marker total
                :atomic (+ total (:w t))
                (+ total
                   (w-of (:text t) (:style t))
                   (if (:space-before? t) (w-of " " (or (:space-style t) (:style t))) 0))))
            0
            tokens)))

(defn- intrinsic-flow-children
  "The children that take part in a box's INTRINSIC (max-content) width.

   Real CSS excludes out-of-flow boxes from intrinsic sizing outright: an
   `absolute`/`fixed` child is sized and placed against a containing block
   that is not this box, so it can neither widen it nor narrow it.

   Measured in Brave, in both directions, on a page shaped like the
   conformance corpus's own:

   - `<td style=\"position:relative\"><span style=\"position:absolute;
     left:20px\">abs</span>cell</td>` reports `td` 30px wide -- `cell`
     plus the UA cell padding, i.e. exactly what the cell holds once the
     span is disregarded -- where this engine reported 791 and blew the
     whole table out to the full 800px page width. The span is correctly
     NOT an inline-flow candidate (inline-level-element? requires an
     in-flow position), so the cell's children were neither all-inline nor
     a single element, and the intrinsic width fell through to
     `content-w`. Dropping the span puts the cell back on the ordinary
     single-text-child path.
   - `<td><div style=\"position:fixed;left:0\">fixedcell</div>c</td>`
     reports `td` 9px wide for the same reason: `fixed` is out of flow
     too, and the 63px-wide fixed box contributes nothing.

   Floats deliberately stay: a float DOES contribute to its container's
   max-content width in real CSS, and layout-children-block already places
   it inside this box."
  [theme children]
  (if (some #(absolute? theme %) children)
    (vec (remove #(absolute? theme %) children))
    children))

(defn- block-max-content-width
  "The max-content width of a box's CONTENT when its children are not all
   inline-level: the WIDEST of their own max-content contributions.
   Excludes the box's own padding/border -- every caller adds the inset it
   is responsible for.

   Real CSS's intrinsic sizing for a block container is `max` over its
   in-flow children, where each maximal run of adjacent inline-level
   children forms one anonymous block whose max-content is the whole run
   on a single line. That is exactly the grouping here: an inline run is
   measured with inline-max-content-width, a block child is measured at
   its own shrink-to-fit width PLUS its horizontal margins (real CSS
   counts a child's margins in its contribution).

   What this replaces is a `content-w` fallback -- 'I cannot tell, so take
   the whole container' -- which was the single largest numeric error left
   in the conformance corpus: any table cell, flex item or inline-block
   holding more than one element child swallowed its container. Measured
   in Brave against this engine before the change:

   | markup (inside a `<td>`, at 800px)     | Brave | engine |
   |----------------------------------------|-------|--------|
   | `<div>alpha</div><div>bb</div>`        |    37 |    ~782 |
   | `lead<div>bb</div>`                    |    30 |    ~782 |
   | `<ul><li>one</li><li>two</li></ul>`    |    63 |     771 |
   | `<blockquote>q</blockquote>`           |    89 |       9 |

   The last row is the margins: `<blockquote>`'s UA `margin: 1em 40px`
   puts 80px around a 7px word, and the single-element rule this
   generalises measured the border box alone. A flex item is the same
   mechanism seen from the other side -- `<div><div>alpha</div>
   <div>bb</div></div>` as a flex item is 35px in Brave, its widest
   child, not the container.

   SCOPE CUT, stated where it is made: an inline run is measured as its
   own children rather than as a real anonymous block box, so a run that
   contains something with an intrinsic size the inline path does not
   model reports what that path reports. And `min-content` is not
   computed at all -- this engine has one intrinsic width, the
   max-content one, and a box that would need to be narrower than its
   content in a real browser is not narrowed here."
  [theme content-w opacity inherited st children]
  (let [inline? #(boolean (inline-flow-candidate? theme %))
        outer (fn [c]
                (let [w (:w (:box (measure-child theme content-w opacity inherited c true)))]
                  (if (map? c)
                    (let [cst (node-style c theme)]
                      (+ w (margin-side cst :left) (margin-side cst :right)))
                    w)))]
    (->> (partition-by inline? children)
         (map (fn [run]
                (if (inline? (first run))
                  (inline-max-content-width theme content-w opacity inherited st (vec run))
                  (apply max 0 (map outer run)))))
         (apply max 0))))

(defn- flex-item-main-width
  "Real CSS flex-basis:auto (the default) falls back to an item's own
   explicit width if set, else shrink-wraps to its own preferred
   (max-content) width -- NOT resolve-width's own block-default fallback
   to the full available width, which is only correct for an ordinary
   block child (previously applied uniformly to flex children too,
   confirmed via direct REPL reproduction: two unstyled <button> flex
   children each rendered at the FULL flex container width instead of
   shrink-wrapping to their own short labels, ballooning the container
   itself to fit them). Clamps the natural width to both min/max-width AND
   whatever main-axis space is actually available, so an overly-wide label
   still shrinks to fit rather than overflowing un-shrunk. Deliberately
   does not implement flex-grow/flex-shrink/an explicit flex-basis -- with
   the real default flex-grow:0, an item simply stays at this natural size
   regardless (leftover main-axis space is real CSS's own default
   behavior too, governed by justify-content), an honest, separate
   scope-cut.

   Despite the name this is every shrink-to-fit width in the file: a flex
   item, a grid item, a table cell (assign-table-cells' `:natural`) and an
   inline-block all reach it through measure-child. The `fill the
   container when the shape is unfamiliar` fallback it used to end with is
   gone -- see block-max-content-width, which answers for a block
   container, and intrinsic-flow-children, which drops the out-of-flow
   children that were never the box's to measure."
  [theme content-w opacity inherited child st]
  (let [cs (intrinsic-flow-children theme (laid-out-children theme child))
        natural (cond
                  ;; A replaced element or form control has an INTRINSIC
                  ;; size wherever it appears -- as a flex item, a grid
                  ;; item or a table cell's content, not only inside a line
                  ;; box. Before this the intrinsic sizing lived solely on
                  ;; the inline path, so an <input> inside a flex row took
                  ;; the whole 800px container where a browser gives it
                  ;; ~153px.
                  (contains? inline-atomic-tags (:tag child))
                  (atomic-intrinsic-width theme content-w opacity inherited child st)

                  (and (= 1 (count cs)) (string? (first cs)))
                  (flex-item-natural-text-width theme opacity inherited st (first cs))

                  ;; MIXED inline content (`go <b>now</b>`) has a real
                  ;; max-content width too: everything on one line. Falling
                  ;; back to the container width made every table column
                  ;; holding a formatted cell as wide as the whole table --
                  ;; measured, a two-cell table with one `<b>` in it filled
                  ;; 800px where the browser shrink-wraps to 72.
                  (and (seq cs) (every? #(inline-flow-candidate? theme %) cs))
                  (+ (inline-max-content-width theme content-w opacity inherited st cs)
                     (intrinsic-inset-x st))

                  ;; NOTHING inside: the box is its own insets, not the
                  ;; whole container. An empty `<td>` took the container
                  ;; width and swallowed its table -- the browser gives it
                  ;; 2px, this engine gave it 782.
                  (empty? cs)
                  (intrinsic-inset-x st)

                  ;; A BLOCK container: the widest of its children's own
                  ;; max-content contributions. This subsumes the single-
                  ;; element-child rule that used to sit here (a `<td>`
                  ;; holding a nested `<table>` shrink-wraps to 86, not to
                  ;; 800) and adds the child margins that rule dropped --
                  ;; see block-max-content-width for the measurements.
                  :else
                  (+ (block-max-content-width theme content-w opacity inherited st cs)
                     (intrinsic-inset-x st)))]
    (min content-w (clamp-width st natural))))

(defn- flex-item-base-size
  "One flex item's FLEX BASE SIZE -- the main-axis size flex-grow and
   flex-shrink distribute the line's free space around, before either has
   run.

   `flex-basis: auto` (the initial value, and what an item that never
   declares one has) means 'use the item's own main size', which is
   exactly the size it was already measured at -- so `measured-main` is
   the answer and this only has work to do for a declared basis. A
   percentage resolves against the container's own main size, and is nil
   (hence `auto`) when that size is not definite, the same rule
   percentage-of applies everywhere else.

   Scope cut, deliberately: `flex-basis: content` -- max-content sizing
   independent of a declared width -- is treated as `auto` here. This
   engine's measured main size for an item with no width IS its
   max-content size (flex-item-main-width), so the two coincide for every
   item that does not ALSO declare a width; where they differ (`width:
   50px; flex-basis: content`) this reports 50 rather than the content
   size. `content` is rare enough on the real web that inventing a second
   measuring pass for it would be machinery in search of a case."
  [st measured-main avail-main]
  (let [basis (:flex-basis st)]
    (if (or (nil? basis) (= "auto" basis) (= "content" basis))
      measured-main
      (or (percentage-of basis avail-main)
          (when-not (percentage? basis) (explicit-length basis))
          measured-main))))

(defn- flex-item-min-content-width
  "A flex item's automatic minimum main size in a ROW container: the width
   of its longest unbreakable word, which is what a `min-width: auto` item
   refuses to shrink below (CSS Flexible Box Layout §4.5).

   Measured in Brave: two `flex: 1` items in a 120px row hold
   `averylongunbrokenword` and `b`, and come out 147px and 7px -- 154px of
   content overflowing a 120px container -- where an engine that only
   divides the free space gives 60 and 60. The floor is why a flex row
   full of long words overflows in a browser instead of crushing every
   word to nothing.

   Returns nil when the floor does not apply or cannot be measured, which
   is a real answer and not a failure: an explicit `min-width` replaces
   the automatic one outright, a scroll container (`overflow` other than
   visible) has an automatic minimum of zero, and an item whose subtree
   holds no text at all has no word to measure. Text is collected from the
   whole subtree, so `<div><span>word</span></div>` still has a floor;
   what is NOT modelled is an item whose min-content size comes from
   something other than text (a nested table's columns, a replaced
   element's intrinsic width), which reports nil rather than a guess."
  [theme inherited child st]
  (when (and (map? child)
             (nil? (:min-width st))
             (contains? #{nil "visible"} (:overflow st)))
    (let [measure-text (:measure-text theme)
          fs (parse-int (:font-size st) (:font-size inherited (:font-size theme)))
          words (->> (tree-seq map? :children child)
                     (keep real-text-child)
                     (mapcat #(str/split (str %) #"\s+"))
                     (remove str/blank?))]
      (when (seq words)
        (+ (* 2 (content-inset st))
           (apply max 0
                  (map (fn [w]
                         (if measure-text
                           (measure-text w fs (:font-weight st) (:font-style st) (:font-family st))
                           (* (count w) (long (* 0.6 fs)))))
                       words)))))))

(defn- resolve-flexible-lengths
  "CSS Flexible Box Layout §9.7's own loop: distribute the line's free
   space across the items by `flex-grow` (when there is room) or by
   scaled `flex-shrink` (when there is not), clamp each result to the
   item's automatic minimum, FREEZE whatever the clamp had to change, and
   run again with what is left.

   The loop is the point. Distributing once and clamping afterwards leaves
   the space a floored item refused to give up sitting in nobody's hands:
   measured in Brave, `averylongunbrokenword` and `b` as two `flex: 1`
   items in a 120px row come out 147 and 7, because once the long word
   refuses to go below its 147px min-content the OTHER item is left with
   -27px of room and falls to its own 7px floor. A single pass gives 147
   and 60 -- the first item right and the second one holding space that
   was already spent.

   `mins` is one automatic minimum per item, nil where there is none (see
   flex-item-min-content-width, which declines to guess for an item whose
   min-content size does not come from text). A zero `flex-grow` (growing)
   or a zero scaled `flex-shrink` (shrinking) freezes an item before the
   first pass, which is also what keeps `flex-shrink: 0` items at their
   declared size while their siblings absorb the whole overflow.

   Not implemented: `max-width`/`flex-basis` upper clamps, which would
   freeze on MAX violations in the same loop (the sign of `violation`
   already distinguishes them; there is simply no max fed in yet)."
  [base-sizes grows shrinks mins avail-main gaps-main]
  (let [n (count base-sizes)
        base (vec base-sizes)
        grow? (> avail-main (+ (reduce + 0 base) gaps-main))
        weight (fn [i] (if grow?
                         (nth grows i)
                         (* (nth shrinks i) (nth base i))))
        floor (fn [i v] (max (or (nth mins i) 0) v))
        ;; An item the loop did not actually move keeps its ORIGINAL
        ;; number, not an arithmetically-equal double. `flex-shrink`
        ;; arrives as a double (parse-dbl's own contract), so a line with
        ;; exactly zero free space -- extremely common, since that is what
        ;; a container sized to its own items has -- would otherwise turn
        ;; every integer size into 84.0 and every offset downstream with
        ;; it, and re-lay out each item for a size it already had.
        settle (fn [sizes] (mapv (fn [b s] (if (== b s) b s)) base sizes))]
    (loop [sizes base
           frozen (mapv #(not (pos? (weight %))) (range n))
           guard n]
      (if (or (every? true? frozen) (neg? guard))
        (settle sizes)
        (let [used (reduce + 0 (map-indexed (fn [i s] (if (nth frozen i) s (nth base i))) sizes))
              remaining (- avail-main gaps-main used)
              total-w (reduce + 0 (keep-indexed (fn [i _] (when-not (nth frozen i) (weight i))) sizes))
              proposed (vec (map-indexed
                             (fn [i s]
                               (if (nth frozen i)
                                 s
                                 (max 0 (+ (nth base i) (* remaining (/ (weight i) total-w))))))
                             sizes))
              clamped (vec (map-indexed (fn [i s] (floor i s)) proposed))
              violation (reduce + 0 (map - clamped proposed))]
          (if (zero? violation)
            (settle clamped)
            (recur clamped
                   (vec (map-indexed (fn [i f] (or f (> (nth clamped i) (nth proposed i)))) frozen))
                   (dec guard))))))))

(defn- flex-item-baseline
  "The distance from a flex item's own top border edge down to the
   baseline of its FIRST line of text -- what `align-items: baseline`
   lines items up on.

   Built the same way line-metrics builds a line box: top border and
   padding, then the half-leading `(line-height - (ascent + descent)) / 2`,
   then the font's ascent. The half-leading is deliberately NOT clamped at
   zero -- a declared line-height smaller than the font's own content area
   makes it negative, and that is exactly the case baseline alignment
   exists to handle. Measured in Brave, a 24px item and a 14px item in a
   `line-height: 20px` container: the big item's baseline sits 18px down
   (-3 half-leading + 21 ascent) and the small one's 14px (+2 + 12), so
   the small item is pushed down by 4 and the line is 24 tall rather than
   20. Clamping the half-leading at zero gives 21 and 14, an offset of 7.

   Scope cut: this reads the item's OWN font, i.e. it assumes the item's
   first line is text laid out in the item's own inherited style. An item
   whose first line box comes from a nested block, a replaced element or
   an atomic inline has a different baseline, and this will report the
   font-derived one. Real CSS also falls back to the item's bottom MARGIN
   edge for an item with no baseline at all (a `display: flex` item with
   no text); that fallback is not implemented -- such an item aligns on
   its font's first-line baseline here, which for the common case (an
   empty or single-line box) is the same place."
  [theme inherited st]
  (let [fs (parse-int (:font-size st) (:font-size inherited (:font-size theme)))
        {:keys [ascent descent]} (font-metrics theme fs (:font-weight st)
                                               (:font-style st) (:font-family st))
        lh (or (parse-int (:line-height st) nil) (inherited-line-height inherited fs) fs)
        half (/ (- lh (+ ascent descent)) 2)]
    (+ (or (:padding-top st) (:padding st) 0)
       (:border-width st)
       half
       ascent)))

(defn- measure-child
  [theme content-w opacity inherited child shrink-to-fit?]
  (let [child (if (map? child)
                (assoc-in child [:attrs :kotoba/independent-fc] true)
                child)
        ;; A TABLE already shrink-wraps itself (layout-table takes the
        ;; smaller of the available width and its own columns plus
        ;; border-spacing), so recursing into it for a "natural" width finds
        ;; its rows and loses the spacing: a nested table came out 37px
        ;; where the browser reports 41. Lay it out and read its box.
        shrink-to-fit? (and shrink-to-fit?
                            (not (and (map? child) (= :table (:tag child)))))
        ;; A percentage (or percentage-bearing calc()) width would otherwise
        ;; be resolved TWICE: once here against the containing block, and
        ;; again inside the child's own layout against the width this
        ;; function just handed it as its available space. `50%` of 200 came
        ;; out 100 here and then 50 there -- measured against Brave on
        ;; `position/absolute-percentage-width`, which reports 100.
        ;;
        ;; Resolved by writing the USED width back onto the child as a plain
        ;; length, so the second resolution is a no-op instead of a second
        ;; percentage. This is the same technique layout-absolute-children
        ;; already uses for its `left`+`right` stretch height, and it is
        ;; written as the `width` PROPERTY's used value (a content width
        ;; under content-box, a border-box width under border-box), not as
        ;; resolve-width's border-box result, so the child's own box-sizing
        ;; arithmetic still applies exactly once.
        child (if (map? child)
                (let [st (node-style child theme)]
                  (if-let [used (or (percentage-of (:width st) content-w)
                                    (calc-of (:width st) content-w))]
                    (assoc-in child [:attrs :style/width] used)
                    child))
                child)
        child-avail (if (map? child)
                       (let [st (node-style child theme)]
                         (if (and shrink-to-fit? (not (:width st)))
                           (flex-item-main-width theme content-w opacity inherited child st)
                           (resolve-width st content-w)))
                       content-w)]
    (layout-node theme 0 0 child-avail opacity inherited child)))

(declare relative-offset)

(defn- relative-item-offset
  "`position: relative` on a FLEX or GRID item -- a paint-time shift from
   the item's own normal position, exactly as for a block child. This
   engine applied it only in block flow, a scope-cut documented since
   relative positioning landed and measured by the conformance corpus as a
   flex item that never moved."
  [theme child]
  (if (and (map? child) (= :element (:node/type child)))
    (let [cst (node-style child theme)]
      (if (= "relative" (:position cst)) (relative-offset cst) [0 0]))
    [0 0]))

(defn- layout-flex-wrap-row
  [theme cx cy cw cross-avail opacity inherited st in-flow measured wrap-reverse?]
  (let [gap (:gap st)
        main-sizes (mapv #(:w (:box %)) measured)
        rows-idx (pack-rows main-sizes gap cw)
        natural-cross (mapv (fn [idxs] (apply max 0 (mapv #(:h (:box (nth measured %))) idxs))) rows-idx)
        n-rows (count rows-idx)
        align-content (:align-content st)
        ;; ---- align-content ----
        ;; The cross-axis counterpart of justify-content, and the property
        ;; that decides what a multi-line flex container does with cross-
        ;; axis room its lines do not fill. It only has anything to
        ;; distribute when the container's own cross size is DEFINITE
        ;; (`cross-avail`, an explicit height); an auto-height container is
        ;; sized BY its lines and has no free space by construction, which
        ;; is why every existing auto-height wrap case is untouched by
        ;; this.
        ;;
        ;; `stretch` -- the initial value -- is the odd one out: it grows
        ;; the LINES rather than moving them, so it is a size change
        ;; applied here and the placement below then sees no free space
        ;; left. Every other keyword is placement, which is exactly what
        ;; place-main-axis already does (including reserving `gap` as a
        ;; minimum), so it is reused on the cross axis rather than
        ;; reimplemented. Measured in Brave: two 20px lines in a 120px
        ;; `align-content: space-between` container sit at y=0 and y=100
        ;; (this engine stacked them at 0 and 20), and two lines in an
        ;; 80px `wrap-reverse` container are 40px tall each rather than 20.
        stretch-lines? (and cross-avail (pos? n-rows)
                            (contains? #{"stretch" "normal"} align-content))
        row-cross-sizes (if stretch-lines?
                          (let [total (+ (reduce + 0 natural-cross) (* gap (max 0 (dec n-rows))))
                                extra (max 0 (quot (- cross-avail total) n-rows))]
                            (mapv #(+ % extra) natural-cross))
                          natural-cross)
        total-cross (+ (reduce + 0 row-cross-sizes) (* gap (max 0 (dec n-rows))))
        ;; `flex-wrap: wrap-reverse` reverses the CROSS axis: the first
        ;; line is laid at the far edge and each subsequent one above it,
        ;; and (see flip-cross-align at each item below) `flex-start`
        ;; within a line now means that line's far edge too. Measured in
        ;; Brave, three 120px items wrapping in a 200px container sit at
        ;; y=40/20/0 rather than 0/20/40; without this the corpus scored
        ;; two-item `wrap-reverse` as the lines in the wrong order.
        ;; Reflecting the FINISHED offsets about the container's cross size
        ;; is the same move mirror-main-offsets makes on the main axis.
        cross-span (max total-cross (or cross-avail 0))
        row-cross-offsets (let [forward (place-main-axis align-content row-cross-sizes gap cross-span)]
                            (if wrap-reverse?
                              (mapv (fn [off sz] (- cross-span off sz)) forward row-cross-sizes)
                              forward))
        ;; align-items (including stretch) pass, per ROW -- see layout-
        ;; flex's own identical non-wrap pass and cross-offset/stretch-
        ;; eligible-child?/force-cross-size for the full rationale. This
        ;; function previously ignored align-items ENTIRELY (not even the
        ;; pre-existing center/flex-end offsets, let alone stretch) --
        ;; every wrapped child was unconditionally top-aligned to its own
        ;; row's top edge, confirmed via a direct REPL reproduction before
        ;; touching source: two flex-wrap:wrap children (heights 20/80,
        ;; align-items:center) both stayed at y=4, while the identical
        ;; style with flex-wrap OMITTED (the already-correct non-wrap
        ;; path) put the shorter child at y=34, properly centered. Wrap
        ;; mode is ALWAYS row-direction (flex-wrap only meaningfully
        ;; applies to flex-direction:row in real CSS too -- wrap? in
        ;; layout-flex above is only ever true when NOT column), so the
        ;; cross axis is always height and `column?` is always false in
        ;; both helper calls below. Each wrapped ROW stretches its own
        ;; auto-sized children to THAT row's own cross size
        ;; (row-cross-sizes, already settled above from the ORIGINAL
        ;; unstretched measurements), not the whole container's --
        ;; matching real CSS's own per-line stretch model.
        measured (reduce
                  (fn [acc [idxs row-cross-size]]
                    (reduce (fn [acc2 idx]
                              (let [child (nth in-flow idx)]
                                (if (stretch-eligible-child? theme false st child)
                                  (assoc acc2 idx
                                         (measure-child theme cw opacity inherited
                                                        (force-cross-size false row-cross-size child)
                                                        true))
                                  acc2)))
                            acc idxs))
                  measured
                  (map vector rows-idx row-cross-sizes))
        draws (mapcat
               (fn [idxs row-y row-cross-size]
                 (let [sizes (mapv #(nth main-sizes %) idxs)
                       ;; justify-content, per ROW -- see this function's own
                       ;; align-items fix above for the identical rationale.
                       ;; This previously hardcoded "flex-start" instead of
                       ;; reading (:justify-content st) like layout-flex's
                       ;; own non-wrap path already does, so EVERY wrapped
                       ;; row was packed at its own start regardless of the
                       ;; container's actual justify-content -- confirmed via
                       ;; a direct REPL reproduction: two flex-wrap:wrap
                       ;; children under justify-content:flex-end (or
                       ;; :center) both stayed at their flex-start offsets,
                       ;; while the identical style with flex-wrap OMITTED
                       ;; (the already-correct non-wrap path) correctly
                       ;; right-aligned/centered them.
                       offs (place-main-axis (:justify-content st) sizes gap cw)]
                   (mapcat (fn [child-idx off]
                             (let [m (nth measured child-idx)
                                   child (nth in-flow child-idx)
                                   child-cross (:h (:box m))
                                   ;; per-item align-self, and the cross-axis
                                   ;; flip wrap-reverse applies on top of it
                                   ;; -- see item-cross-align/flip-cross-align.
                                   align (cond-> (item-cross-align theme st child)
                                           wrap-reverse? flip-cross-align)
                                   c-off (cross-offset align child-cross row-cross-size)
                                   dx (+ cx off)
                                   dy (+ cy row-y c-off)]
                               (translate-ops dx dy (:draw m))))
                           idxs offs)))
               rows-idx row-cross-offsets row-cross-sizes)]
    {:draws (vec draws) :main-total cw :cross-total total-cross}))

(defn- layout-flex
  [theme x y avail-width opacity inherited st node in-flow]
  (let [;; A flex item's containing block is THIS container, not whatever
        ;; block set the basis on the way in. This function does not resolve
        ;; its own definite content height for its items yet (its `node-h`
        ;; still reads resolve-height straight, see used-block-height), so
        ;; the honest answer for an item's percentage height here is `auto`
        ;; -- which is what dropping the parent's basis produces. Passing the
        ;; GRANDparent's height down instead would be a number arrived at by
        ;; not thinking about it.
        inherited (dissoc inherited :block/containing-height)
        direction (:flex-direction st)
        column? (contains? #{"column" "column-reverse"} direction)
        ;; `row-reverse`/`column-reverse` lay the SAME line out from the
        ;; main-END edge (see mirror-main-offsets, which is where the whole
        ;; of it lives). `column-reverse` in particular was not even
        ;; recognised as a column before this -- it fell through to the
        ;; row branch, so a reversed column laid its items out side by side
        ;; and stretched each to the container's height.
        reverse? (contains? #{"row-reverse" "column-reverse"} direction)
        wrap-reverse? (= "wrap-reverse" (:flex-wrap st))
        wrap? (and (not column?) (contains? #{"wrap" "wrap-reverse"} (:flex-wrap st)))
        ;; `display: inline-flex` is a flex container that is INLINE-level:
        ;; it sits in its parent's line box (the inline path admits it, see
        ;; inline-atomic-element?) and shrink-wraps to its items instead of
        ;; filling its containing block.
        inline? (= "inline-flex" (:display st))
        ;; `order` before anything is measured, so the new order flows
        ;; through placement AND paint order together -- see
        ;; order-flex-items.
        in-flow (order-flex-items theme in-flow)
        w (resolve-width st avail-width)
        inset (content-inset st)
        cx (+ x (:margin st) inset)
        cy (+ y (:margin st) inset)
        cw (max 0 (- w (* 2 inset)))
        gap (:gap st)
        ;; A COLUMN item's cross axis is its WIDTH, and a cross axis is
        ;; only filled when it stretches: under any other alignment the
        ;; item is fit-content, exactly like a row item's main size. Both
        ;; are the same shrink-to-fit measurement, which is why the flag
        ;; is `(or row? (not stretching))` rather than two paths. Measured
        ;; in Brave, a 200px `flex-direction: column; align-items: center`
        ;; container puts a one-character item at x=96.5 with a 7px box;
        ;; measuring every column item at the container width instead gave
        ;; a 200px box at x=0, so `align-items` looked unimplemented for
        ;; columns even though the offset arithmetic was right.
        measured (mapv (fn [child]
                         (measure-child theme cw opacity inherited child
                                        (or (not column?)
                                            (not= "stretch" (item-cross-align theme st child)))))
                       in-flow)]
    (if wrap?
      ;; A DEFINITE cross size is what align-content has to distribute (see
      ;; layout-flex-wrap-row); nil means auto-height, where the container
      ;; is sized by its lines and there is nothing to distribute.
      (let [cross-avail (when-let [h (resolve-height st)] (max 0 (- h (* 2 inset))))
            {:keys [draws cross-total]} (layout-flex-wrap-row theme cx cy cw cross-avail opacity
                                                              inherited st in-flow measured
                                                              wrap-reverse?)
            node-h (or (resolve-height st) (+ cross-total (* 2 inset)))]
        {:box-w w :box-h node-h :draws draws})
      (let [main-of (fn [m] (if column? (:h (:box m)) (:w (:box m))))
            ;; ---- flex-grow / flex-shrink ----
            ;; Real flexbox distributes the line's FREE SPACE across the
            ;; items: positive free space by `flex-grow`, negative by
            ;; `flex-shrink` weighted by each item's own base size. This
            ;; engine froze every item at its base size, so `flex-grow: 1`
            ;; did nothing at all (the most common flex idiom on the real
            ;; web) and over-wide items overflowed instead of shrinking --
            ;; measured against the browser as `div w +8` and `div x +50`
            ;; across ten boxes.
            ;; An INLINE-level flex container with no declared main size is
            ;; sized BY its items, so there is no free space for either
            ;; factor to distribute -- `flex: 1` inside one changes
            ;; nothing. A zero here disables distribution through the
            ;; `(pos? avail-main)` guards below, which is exactly that.
            avail-main (cond
                         column? (or (explicit-length (:height st)) 0)
                         (and inline? (nil? (explicit-length (:width st)))) 0
                         :else cw)
            item-sts (mapv #(when (map? %) (node-style % theme)) in-flow)
            ;; The item's FLEX BASE SIZE, which is its measured main size
            ;; unless it declares a `flex-basis` -- and `flex: 1` declares
            ;; one (`1 1 0%`, expanded in cssom.core), which is the whole
            ;; reason two `flex: 1` items split a row evenly regardless of
            ;; what is in them. See flex-item-base-size.
            base-sizes (mapv (fn [cst m]
                               (if cst
                                 (flex-item-base-size cst (main-of m) avail-main)
                                 (main-of m)))
                             item-sts measured)
            gaps-main (* gap (max 0 (dec (count base-sizes))))
            grows (mapv #(or (:flex-grow %) 0.0) item-sts)
            shrinks (mapv #(or (:flex-shrink %) 1.0) item-sts)
            ;; A flex item's AUTOMATIC MINIMUM main size -- the min-content
            ;; floor it refuses to shrink below, which is why a row of long
            ;; words overflows a narrow container in a browser instead of
            ;; crushing each word (see flex-item-min-content-width). Only
            ;; on the row axis: a column item's automatic minimum is its
            ;; min-content HEIGHT, which this engine has no measurement
            ;; for, so a column feeds nil floors rather than a wrong one.
            mins (if column?
                   (vec (repeat (count in-flow) nil))
                   (mapv (fn [child cst]
                           (when cst (flex-item-min-content-width theme inherited child cst)))
                         in-flow item-sts))
            main-sizes (if (pos? avail-main)
                         (resolve-flexible-lengths base-sizes grows shrinks mins
                                                   avail-main gaps-main)
                         base-sizes)
            ;; An item resized on the main axis is laid out AGAIN at that
            ;; size, so its own content wraps against the real width -- and
            ;; through force-main-width, so an item that declares its own
            ;; width actually takes the resolved one. Carried on the child
            ;; NODE (rather than as a one-off re-measure) so the stretch
            ;; pass further down re-measures from the already-main-sized
            ;; child instead of undoing it.
            sized (mapv (fn [child m sz]
                          (if (and (map? child) (not column?) (not (== (main-of m) sz)))
                            (force-main-width child (long sz))
                            child))
                        in-flow measured main-sizes)
            measured (mapv (fn [child0 child m]
                             (if (identical? child0 child)
                               m
                               (measure-child theme cw opacity inherited child false)))
                           in-flow sized measured)
            ;; ---- align-items / align-self: baseline ----
            ;; Baseline-aligned items line their FIRST text baselines up
            ;; with each other, so the line's cross size is the deepest
            ;; baseline plus the deepest thing hanging below one -- which
            ;; is how a 24px item next to a 14px one makes a 24px-tall line
            ;; out of two 20px boxes (measured in Brave). cross-offset has
            ;; no case for `baseline` and silently treated it as
            ;; flex-start. Only meaningful on the main axis being a row:
            ;; in a column, baseline alignment is along the INLINE axis,
            ;; which this does not model, so a column falls back to the
            ;; flex-start behaviour it already had.
            aligns (mapv #(item-cross-align theme st %) in-flow)
            baseline-align? (fn [align]
                              (and (not column?)
                                   (contains? #{"baseline" "first baseline"} align)))
            baselines (mapv (fn [cst align]
                              (when (and cst (baseline-align? align))
                                (flex-item-baseline theme inherited cst)))
                            item-sts aligns)
            max-baseline (apply max 0 (keep identity baselines))
            baseline-shifts (mapv #(if % (- max-baseline %) 0) baselines)
            cross-sizes (mapv (fn [m] (if column? (:w (:box m)) (:h (:box m)))) measured)
            auto-cross (if (seq cross-sizes)
                         (apply max 0 (map + baseline-shifts cross-sizes))
                         0)
            ;; A COLUMN container's cross axis is its WIDTH, and a
            ;; block-level flex container fills its containing block --
            ;; the same rule the `node-w` comment at the end of this
            ;; function states for rows. Sizing the cross axis from the
            ;; widest item instead would centre `align-items: center`
            ;; inside the widest item rather than inside the container.
            cross-content (or (explicit-length (if column? (:width st) (:height st)))
                              (if (and column? (not inline?)) cw auto-cross))
            auto-main (+ (reduce + 0 main-sizes) (* gap (max 0 (dec (count main-sizes)))))
            ;; The main-axis size justify-content distributes free space
            ;; WITHIN. For a row that is the container's content width --
            ;; which, now that a flex container is block-level, is the full
            ;; containing block -- not the sum of the items, which would
            ;; leave no free space to distribute at all and pin every
            ;; `justify-content: center` row hard against the left edge
            ;; (measured: items at x=0,7 where the browser centres them at
            ;; 393,400). A COLUMN's main axis is its height, which still
            ;; comes from the content unless declared.
            main-content (or (explicit-length (if column? (:height st) (:width st)))
                             (if (or column? inline?) auto-main cw))
            ;; align-items/align-self:stretch pass -- see
            ;; stretch-eligible-child?/force-cross-size. Deliberately AFTER
            ;; cross-content is determined from the UNSTRETCHED
            ;; measurements, matching real flexbox's own algorithm order
            ;; (the flex line's cross size is settled first, from the
            ;; tallest natural item or the container's own explicit size;
            ;; only THEN do stretch-eligible items get resized to fill it
            ;; -- a stretched item never feeds back into cross-content's
            ;; own computation). Re-measures from `sized`, the children
            ;; that already carry their resolved MAIN size, so stretching
            ;; an item cannot undo its grow/shrink -- the bug that made
            ;; `flex-grow: 1` look unimplemented even once the
            ;; distribution was right.
            measured (mapv (fn [child m]
                              (if (stretch-eligible-child? theme column? st child)
                                (measure-child theme cw opacity inherited
                                               (force-cross-size column? cross-content child)
                                               (not column?))
                                m))
                            sized measured)
            auto-margins (mapv #(auto-main-margins column? %) in-flow)
            offsets (let [offs (if (pos? (reduce + 0 (apply concat auto-margins)))
                                 (place-main-axis-auto-margins auto-margins main-sizes gap main-content)
                                 (place-main-axis (:justify-content st) main-sizes gap main-content))]
                      (if reverse?
                        (mirror-main-offsets offs main-sizes main-content)
                        offs))
            draws (mapcat
                   (fn [m off child align shift]
                     (let [child-cross (if column? (:w (:box m)) (:h (:box m)))
                           c-off (if (baseline-align? align)
                                   shift
                                   (cross-offset align child-cross cross-content))
                           [rdx rdy] (relative-item-offset theme child)
                           dx (+ (if column? (+ cx c-off) (+ cx off)) rdx)
                           dy (+ (if column? (+ cy off) (+ cy c-off)) rdy)]
                       (translate-ops dx dy (:draw m))))
                   measured offsets in-flow aligns baseline-shifts)
            node-h (if column? (+ main-content (* 2 inset)) (+ cross-content (* 2 inset)))
            ;; A `display: flex` box is a BLOCK-level flex container: it
            ;; fills its containing block's width exactly like any other
            ;; block, and only its ITEMS shrink-to-fit. This shrink-wrapped
            ;; the container itself to the sum of its items, so a flex row
            ;; of three one-character divs was 21px wide where the browser
            ;; reports 800 -- and every justify-content computation then
            ;; distributed space inside that 21px box. Found by the geometry
            ;; axis (div w -750 across ten boxes); the line-structure axis
            ;; scored all of those cases as passes.
            ;;
            ;; `display: inline-flex` is the exception the same sentence
            ;; implies: an INLINE-level flex container shrink-wraps to its
            ;; own items, exactly like the `inline-block` it is the flex
            ;; spelling of. Measured in Brave, `<span style="display:
            ;; inline-flex; gap: 6px"><span>a</span><span>b</span></span>`
            ;; in a sentence is 20px wide (7 + 6 + 7); this engine gave it
            ;; the full 800 and broke the sentence into three lines.
            node-w (if (and inline? (nil? (explicit-length (:width st))))
                     (+ (if column? cross-content main-content) (* 2 inset))
                     w)]
        {:box-w node-w :box-h node-h :draws (vec draws)}))))

;; ---- grid layout ----
;;
;; An `auto` track is sized from what is IN it, so unlike every other track
;; type it cannot be resolved from the declaration alone: layout-grid has to
;; measure each item's min-content and max-content width first and hand the
;; result to track-sizes. These two helpers are that measurement, and they
;; are deliberately the SAME ones flexbox already uses (flex-item-main-width
;; for max-content, flex-item-min-content-width for the longest-word floor)
;; rather than a second, grid-specific notion of intrinsic size.

(defn- grid-item-max-content-width
  "One grid item's max-content width: what it wants when nothing constrains
   it, which is the growth limit of an `auto` track holding it and the size
   a non-stretch `justify-items` gives the item itself.

   An item that declares its own `width` contributes exactly that (measured
   in Brave: `auto auto` in a 400px grid with a `width: 300px` item and an
   `x` comes out 345.703 / 54.297 -- the 300 plus an equal share of the
   leftover, not the item's text width). Everything else goes through
   flex-item-main-width, inheriting both its coverage (a text leaf, a mixed
   inline run, a replaced element, an empty box, a single element child)
   and its documented scope cut -- an item with several ELEMENT children
   falls back to the available width rather than guessing.

   A bare text child is a real grid item (real CSS wraps it in an anonymous
   one), so it is measured too, by laying the text out against an
   effectively unconstrained width -- the same trick flex-item-natural-text-
   width uses."
  [theme cw opacity inherited child]
  (if (map? child)
    (let [st (node-style child theme)]
      (if (:width st)
        (resolve-width st cw)
        (flex-item-main-width theme cw opacity inherited child st)))
    (min cw (:w (:box (layout-node theme 0 0 flex-item-shrink-to-fit-measure-width
                                   opacity inherited child))))))

(defn- grid-item-min-content-width
  "One grid item's min-content width: the floor an `auto` track will not go
   below, which is what makes a narrow grid OVERFLOW rather than crush its
   words. Measured in Brave, `auto auto` at a 60px width holding `short`
   and `a much longer cell` comes out 41.625 / 50.141 -- the two words'
   own widths, 91.77px of content in a 60px box.

   Falls back to the max-content width whenever flex-item-min-content-width
   declines to answer (an explicit `min-width`, a scroll container, or a
   subtree with no text at all -- see its docstring). That is deliberately
   the CONSERVATIVE direction: a floor that is too high leaves a track
   wider than it had to be, where a zero floor would let the track collapse
   under content that cannot actually shrink."
  [theme cw opacity inherited child]
  (if (map? child)
    (let [st (node-style child theme)]
      (if (:width st)
        (resolve-width st cw)
        (or (flex-item-min-content-width theme inherited child st)
            (grid-item-max-content-width theme cw opacity inherited child))))
    (grid-item-max-content-width theme cw opacity inherited child)))

(defn- grid-track-intrinsics
  "One `{:min <px> :max <px>}` per column track -- the widest min-content and
   max-content any item placed in that track needs -- in the shape
   track-sizes' `intrinsics` argument wants.

   Scope cut, deliberately: an item SPANNING more than one track contributes
   to none of them. Real CSS distributes a spanning item's intrinsic
   contribution across the tracks it crosses (CSS Grid §12.5), which is a
   whole second algorithm; measured in Brave the difference is small for the
   common case -- `grid-template-columns: auto auto` in a 400px grid with a
   full-width spanning item above two one-character items comes out
   199.828 / 200.172, and ignoring the span gives 200.17 / 199.83, well
   inside the harness's 2px tolerance -- because the equal-share stretch
   step dominates once there is room. It is NOT small for a narrow grid,
   where the spanning item's own min-content is what would force the tracks
   wider; that case reports tracks narrower than a browser's."
  [theme cw opacity inherited children placements n-cols]
  (reduce (fn [acc [child pl]]
            (if (and pl (= 1 (- (:col-end pl) (:col-start pl))))
              (let [i (:col-start pl)]
                (if (< -1 i n-cols)
                  (-> acc
                      (update-in [i :min] max (grid-item-min-content-width theme cw opacity inherited child))
                      (update-in [i :max] max (grid-item-max-content-width theme cw opacity inherited child)))
                  acc))
              acc))
          (vec (repeat n-cols {:min 0 :max 0}))
          (map vector children placements)))

;; ---- table layout ----

(def ^:private table-cell-tags #{:td :th})

(def ^:private ua-table-display
  "The `display` a real UA stylesheet gives each table tag. Real CSS's table
   model is driven ENTIRELY by `display`, not by tag names: `<td>` is a
   table cell only because `td { display: table-cell }` says so, and a
   `<div style=\"display: table-cell\">` is exactly as much of a cell.

   This engine's table layout keyed off tags alone, so a CSS-declared table
   found no rows and no cells and emitted one 300x2 box with nothing in it
   -- measured on the conformance corpus, `:display/table-cells-from-divs`
   and `:display/table-with-anonymous-rows` both produced an EMPTY line
   structure while the browser reports four and three real boxes."
  {:table "table"
   :tr "table-row"
   :td "table-cell"
   :th "table-cell"
   :thead "table-header-group"
   :tbody "table-row-group"
   :tfoot "table-footer-group"
   :caption "table-caption"
   :col "table-column"
   :colgroup "table-column-group"})

(def ^:private table-row-group-displays
  "The wrappers a real HTML parser puts between `<table>` and its `<tr>`s.
   `<tbody>` in particular is INSERTED by the parser even when the author
   never wrote it, so a table layout that only looked at direct `<tr>`
   children of `<table>` would find no rows at all on most real markup."
  #{"table-row-group" "table-header-group" "table-footer-group"})

(defn- table-part-display
  "The effective `display` of a child of a table box: the author's own
   declaration when there is one, else the UA default for its tag.

   `nil` for a text node or a tag with no table role, which is what both
   callers below filter on."
  [theme node]
  (when (and (map? node) (not= :text (:node/type node)))
    (or (:display (node-style node theme))
        (get ua-table-display (:tag node)))))

(defn- anonymous-row
  "The anonymous row box CSS generates around cells that are children of a
   table with no row between them.

   Real CSS wraps every run of consecutive table-cell children in ONE
   anonymous `table-row` box (CSS 2.1 SS17.2.1). It has no element behind it,
   so it gets no `:node` draw-op -- measured in Brave,
   `<div style=\"display:table\"><div style=\"display:table-cell\">a</div>
   <div style=\"display:table-cell\">b</div></div>` reports exactly three
   boxes (the table and the two cells) and nothing in between."
  [cells]
  {:row {:node/type :element :tag nil :children (vec cells)}
   :group nil
   :anonymous true})

(defn- table-rows
  "Every row box under `node`, in document order, as `{:row <element>
   :group <row-group element or nil> :anonymous <true when CSS generated
   it>}`.

   The rows are flattened out of their `<thead>`/`<tbody>`/`<tfoot>`
   wrappers -- a real HTML parser INSERTS `<tbody>` even when the author
   never wrote one, so looking only at direct `<tr>` children finds no rows
   at all on most real markup -- but each row REMEMBERS its group, so
   layout-table can still emit a box for the group itself. A row group with
   no box of its own was measurable: the geometry axis of the conformance
   harness reported `tbody 0/9`, because the browser has a box there and
   this engine had nothing to match it with.

   Rows are recognised by their `display` (see table-part-display), so a
   `<div style=\"display: table-row\">` is a row and a
   `<tr style=\"display: block\">` is not, and a run of bare cells with no
   row around them gets the anonymous row box CSS generates for it.

   Scope-cut, deliberate: a NON-cell child of a table (a stray `<div>` with
   its ordinary block display) is dropped rather than wrapped in the
   anonymous cell real CSS would generate for it -- the same thing this
   function did before it looked at `display` at all, so nothing regressed,
   but it is not the whole rule."
  [theme node]
  (let [flush (fn [acc pending] (if (seq pending) (conj acc (anonymous-row pending)) acc))
        [acc pending]
        (reduce
         (fn [[acc pending] child]
           (let [d (table-part-display theme child)]
             (cond
               (= "table-row" d) [(conj (flush acc pending) {:row child :group nil}) []]

               (contains? table-row-group-displays d)
               [(into (flush acc pending)
                      (for [r (:children child)
                            :when (= "table-row" (table-part-display theme r))]
                        {:row r :group child}))
                []]

               (= "table-cell" d) [acc (conj pending child)]

               :else [acc pending])))
         [[] []]
         (:children node))]
    (vec (flush acc pending))))

(defn- table-cells [theme row]
  (vec (filter #(= "table-cell" (table-part-display theme %)) (:children row))))

(defn- cell-colspan
  "A cell's `colspan`, clamped to at least 1. Real CSS lets a cell cover
   several columns; this engine used to place every cell in exactly one,
   so a `colspan=\"2\"` header made its own column as wide as the whole
   header and left the next one holding only its own short content --
   visible on the geometry axis as a table 11px too wide with both cells
   in the wrong place, while the line-structure axis saw nothing at all."
  [cell]
  (max 1 (parse-int (get-in cell [:attrs :colspan]) 1)))

(defn- cell-rowspan
  "A cell's `rowspan`, clamped to at least 1. A spanning cell occupies the
   same column in the rows below it, and those rows must SKIP that column
   -- without which the cells after it shifted left into the space a
   browser reserves, and the spanning cell itself was only ever one row
   tall."
  [cell]
  (max 1 (parse-int (get-in cell [:attrs :rowspan]) 1)))

(defn- assign-table-cells
  "Assigns every cell its [row col colspan rowspan], walking rows in order
   and skipping columns still occupied by a `rowspan` from above -- the
   same occupancy walk a real table layout does.

   Returns `[{:cell :row :col :colspan :rowspan :natural :declared} ...]` in
   document order, where `:natural` is the cell's own shrink-to-fit width
   and `:declared` is its border-box width when it declared one at all (nil
   otherwise) -- which is what `table-layout: fixed` sizes columns from,
   since it never looks at the content `:natural` measures."
  [theme content-w opacity inherited rows]
  (first
   (reduce
    (fn [[acc occupied] [row-idx {:keys [row]}]]
      (let [[acc' occupied' _]
            (reduce
             (fn [[acc occupied col] cell]
               (let [colspan (cell-colspan cell)
                     rowspan (cell-rowspan cell)
                     col (loop [c col] (if (contains? occupied [row-idx c]) (recur (inc c)) c))
                     cells (for [r (range row-idx (+ row-idx rowspan))
                                 c (range col (+ col colspan))]
                             [r c])]
                 [(conj acc {:cell cell :row row-idx :col col
                             :colspan colspan :rowspan rowspan
                             :declared (let [cst (node-style cell theme)]
                                         (when (:width cst) (resolve-width cst content-w)))
                             :natural (:w (:box (measure-child theme content-w opacity
                                                               inherited cell true)))})
                  (into occupied cells)
                  (+ col colspan)]))
             [acc occupied 0]
             (table-cells theme row))]
        [acc' occupied']))
    [[] #{}]
    (map-indexed vector rows))))

(defn- table-columns
  "The `<colgroup>`/`<col>` boxes of a table, one entry per COLUMN they
   cover, as `[{:col <index> :width <px or nil> :el <element> :group
   <colgroup element or nil> :first? <true on the element's first column>
   :span <how many columns the element covers>} ...]`.

   Real CSS gives these two jobs at once, and this engine did neither: a
   `<col>` sets its column's width, AND both it and its `<colgroup>` get a
   real box in the box tree. Measured in Brave,
   `<table><colgroup><col style=\"width:120px\"><col style=\"width:60px\">
   </colgroup><tr><td>a</td><td>b</td></tr></table>` is 186px wide with
   `colgroup` 182x22, `col` 120x22 and `col` 60x22 -- against this engine's
   24px table and no colgroup/col boxes at all.

   A `<col span=\"2\">` covers two columns and gets ONE box spanning both
   (measured: 82px wide over two 40px columns and the 2px between them),
   which is why the entries carry `:first?`/`:span` rather than one box per
   entry. A `<colgroup>` with no `<col>` children covers its own `span`
   columns.

   Recognised by `display` like every other table part (see
   table-part-display), so `display: table-column` on an ordinary element
   works too."
  [theme node content-w]
  (let [col-width (fn [el]
                    (let [st (node-style el theme)]
                      (or (percentage-of (:width st) content-w)
                          (when-not (percentage? (:width st))
                            (parse-int (:width st) nil)))))
        span (fn [el] (max 1 (parse-int (get-in el [:attrs :span]) 1)))
        emit (fn [start el group]
               (let [n (span el) w (col-width el)]
                 (vec (for [i (range n)]
                        {:col (+ start i) :width w :el el :group group
                         :first? (zero? i) :span n}))))]
    (first
     (reduce
      (fn [[acc start] child]
        (let [d (table-part-display theme child)]
          (cond
            (= "table-column" d)
            (let [cs (emit start child nil)] [(into acc cs) (+ start (count cs))])

            (= "table-column-group" d)
            (let [cols (filter #(= "table-column" (table-part-display theme %)) (:children child))]
              (if (seq cols)
                (let [cs (reduce (fn [[out at] c]
                                   (let [e (emit at c child)] [(into out e) (+ at (count e))]))
                                 [[] start] cols)]
                  [(into acc (first cs)) (second cs)])
                (let [cs (emit start child child)] [(into acc cs) (+ start (count cs))])))

            :else [acc start])))
      [[] 0]
      (:children node)))))

(defn- distribute-excess
  "Grows `widths` so they add up to `target`, in proportion to what each
   already wants.

   Real CSS's automatic table layout hands a table's SURPLUS width to its
   columns rather than leaving it at the right edge, weighted by each
   column's own demand. Measured in Brave: a 300px-wide table whose two
   cells want 7px and 14px puts them at 100px and 200px (exactly 1:2), and
   `<table style=\"width:50%\">` in a 400px parent gives its two equal cells
   97px each -- where this engine left both at their 9px natural width and
   the table's own box 182px wider than its contents.

   Returns `widths` untouched when they already reach `target`; the
   shrink-to-fit direction is table-column-widths' own proportional
   scale-DOWN, which this deliberately does not duplicate. The rounding
   remainder goes to the last column so the columns add up to `target`
   exactly rather than leaving a 1px seam."
  [widths target]
  (let [total (reduce + 0 widths)]
    (if (or (empty? widths) (not (pos? total)) (<= target total))
      widths
      (let [grown (mapv #(long (* target (/ % (double total)))) widths)
            short (- target (reduce + 0 grown))]
        (update grown (dec (count grown)) + short)))))

(defn- table-fixed-column-widths
  "`table-layout: fixed`: the columns are sized from the `<col>` elements
   and the FIRST ROW alone, and the rest of the table's content is never
   measured at all.

   That is the whole point of the property -- a browser can start painting
   before it has seen the rest of the table -- and it is why the widths
   differ so much from the automatic ones: measured in Brave,
   `<table style=\"table-layout:fixed; width:300px\"><tr><td>a</td>
   <td>a much longer cell here</td></tr></table>` gives BOTH columns 147px
   (and a 46px-tall table, because the long cell wraps), where automatic
   layout gives 9px and 163px in a 26px-tall table.

   A column with a declared width (from its `<col>`, else from the first
   row's cell) keeps it; the rest share what is left equally -- measured,
   a first row of `width:50px` + two auto cells in a 300px table gives
   52/120/120 (the 50 plus the cell's own 1px UA padding on each side).

   Scope-cut, deliberate: percentage COLUMN widths are resolved against the
   table's content width like any other percentage here, and a table with
   no definite width falls back to the automatic algorithm entirely --
   `table-layout: fixed` on an auto-width table is defined to size from the
   first row's content, which is the automatic algorithm restricted to one
   row, and this engine does not implement that restriction."
  [content-w spacing col-widths first-row-assigns n-cols]
  (let [avail (max 0 (- content-w (* spacing (inc n-cols))))
        declared (vec (for [c (range n-cols)]
                        (or (nth col-widths c nil)
                            (some (fn [a]
                                    (when (and (= c (:col a)) (= 1 (:colspan a)))
                                      (:declared a)))
                                  first-row-assigns))))
        fixed (reduce + 0 (keep identity declared))
        autos (count (filter nil? declared))
        each (if (pos? autos) (max 0 (quot (- avail fixed) autos)) 0)
        widths (mapv #(or % each) declared)]
    (if (pos? autos)
      widths
      ;; every column declared: real CSS still stretches them to the
      ;; table's width. Same proportional hand-out the automatic algorithm
      ;; uses, so the two agree wherever they overlap.
      (distribute-excess widths avail))))

(defn- table-column-widths
  "Real CSS's automatic table layout, in the one form that matters for a
   readable table: every column is as wide as its widest cell needs to be,
   and if the columns together want more than the table has, they are
   scaled down proportionally rather than overflowing.

   Takes the cell ASSIGNMENTS (see assign-table-cells), so a `colspan` cell
   contributes to the columns it actually covers: it widens them only when
   they cannot already hold it, sharing the shortfall equally -- real CSS's
   own distribution, minus the proportional weighting it does by each
   column's own demand.

   `col-widths` is the per-column width a `<col>`/`<colgroup>` declared (nil
   where none did, see table-columns). A declared column width is used as
   given: real CSS floors it at that column's MIN-content width, which this
   engine has no way to compute (it measures max-content only), so a
   `<col>` narrower than its own content makes the content overflow instead
   of pushing the column back out. Measured, the declared width is what a
   browser uses whenever it is the larger of the two, which is the case
   every `<col>` in the wild is written for.

   Deliberately NOT implemented: border collapsing (layout-table's own
   `collapse?` path handles that), and the surplus hand-out when a table is
   WIDER than its columns want (distribute-excess, applied by layout-table
   once the caption and the table's own declared width are known)."
  [content-w spacing col-widths assigns]
  (let [n-cols (apply max 0 (map #(+ (:col %) (:colspan %)) assigns))
        base (vec (for [col (range n-cols)]
                    (or (nth col-widths col nil)
                        (apply max 1
                               (for [a assigns
                                     :when (and (= 1 (:colspan a)) (= col (:col a)))]
                                 (:natural a))))))
        natural (reduce (fn [widths a]
                          (if (= 1 (:colspan a))
                            widths
                            (let [cols (range (:col a) (+ (:col a) (:colspan a)))
                                  have (+ (reduce + 0 (map #(nth widths % 1) cols))
                                          (* spacing (dec (:colspan a))))
                                  short (- (:natural a) have)]
                              (if (pos? short)
                                (let [add (long (Math/ceil (/ short (:colspan a))))]
                                  (reduce #(update %1 %2 + add) widths cols))
                                widths))))
                        base
                        assigns)
        total (reduce + 0 natural)]
    (if (and (pos? total) (> total content-w))
      (mapv #(long (* % (/ content-w total))) natural)
      natural)))

(defn- layout-table
  "Lays out a `<table>` as rows of cells: columns sized by their widest
   cell (table-column-widths), rows as tall as their tallest cell, cells
   laid out through the ordinary layout-node path at their own column
   width so a cell's contents (including inline flow, nested blocks, form
   controls) behave exactly as they would anywhere else.

   `<tr>` and the cells keep their own `:node` draw-ops, so hit testing,
   the accessibility projection and click routing see a real table
   structure rather than a flat pile of text.

   Reached BY DISPLAY, not by tag (see layout-node's dispatch and
   table-part-display): `<div style=\"display: table\">` gets this same
   algorithm, its `display: table-row`/`table-cell` descendants are real
   rows and cells, and a run of cells with no row around them gets the
   anonymous row box CSS generates (anonymous-row). Every draw-op carries
   the element's OWN tag, so a div-table reports `div` boxes -- it used to
   emit one op tagged `table` whatever the element was.

   Honest scope-cuts, all of them real CSS features this does NOT do:
   `<caption>` placement (a caption is laid out as an ordinary block row
   above the rows, never below), `caption-side`, anonymous CELL boxes
   around a non-cell child of a table or a row (such a child is dropped),
   an anonymous TABLE box around a stray `display: table-cell` outside any
   table (it lays out as an ordinary block instead -- measured in Brave,
   two such divs sit side by side in a generated table), `empty-cells`,
   `visibility: collapse` on a row or column, and full border-conflict
   resolution under `border-collapse` (widths only -- see collapse? below).
   Before this existed a table rendered as one stacked column of every cell
   in document order -- the two conformance cases scored 0/2 -- so this is
   a large step from nothing, not a complete table implementation."
  [theme x y avail-width opacity inherited st node]
  (let [;; A cell's containing block is the cell, not the block that set the
        ;; percentage-height basis on the way in -- same reasoning, and same
        ;; honest `auto`, as layout-flex's own dissoc.
        inherited (dissoc inherited :block/containing-height)
        inset (content-inset st)
        avail-content (max 0 (- (resolve-width st avail-width) (* 2 inset)))
        caption (first (filter #(= "table-caption" (table-part-display theme %)) (:children node)))
        rows (table-rows theme node)
        assigns (assign-table-cells theme avail-content opacity inherited rows)
        columns (table-columns theme node avail-content)
        col-widths (let [m (into {} (map (juxt :col :width) columns))]
                     (vec (for [c (range (inc (apply max -1 (map :col columns))))]
                            (get m c))))
        fixed? (and (= "fixed" (:table-layout st)) (some? (:width st)) (seq assigns))
        base-widths (if fixed?
                      (table-fixed-column-widths
                       avail-content (:border-spacing st) col-widths
                       (filter #(zero? (:row %)) assigns)
                       (max (count col-widths)
                            (apply max 0 (map #(+ (:col %) (:colspan %)) assigns))))
                      (table-column-widths avail-content (:border-spacing st) col-widths assigns))
        ;; A CAPTION participates in the table's width: real CSS makes the
        ;; table at least as wide as the caption needs, and the extra width
        ;; goes to the columns. Measured: a two-cell table under a
        ;; `Caption text` caption is 49px wide in the browser (the caption's
        ;; own min-content) where this engine reported 24 -- the cells'
        ;; width alone, with the caption overflowing it.
        ;; MIN-content, not max-content: a table grows to fit the caption's
        ;; longest WORD, and the caption then wraps inside that width. Using
        ;; the whole caption's width instead made the table as wide as the
        ;; unwrapped caption -- measured, `Caption text` gave a 84px table
        ;; where the browser reports 49 (the width of `Caption`) with the
        ;; caption wrapped onto two lines.
        caption-w (if caption
                    (let [measure-text (:measure-text theme)
                          cst (node-style caption theme)
                          fs (parse-int (:font-size cst) (:font-size theme))
                          words (->> (:children caption)
                                     (keep real-text-child)
                                     (mapcat #(str/split (str %) #"\s+"))
                                     (remove str/blank?))]
                      (+ (* 2 (content-inset cst))
                         (apply max 0
                                (map (fn [w]
                                       (if measure-text
                                         (measure-text w fs (:font-weight cst) (:font-style cst) (:font-family cst))
                                         (* (count w) (long (* 0.6 fs)))))
                                     words))))
                    0)
        widths (let [cols-w (reduce + 0 base-widths)
                     spacing-w (* (:border-spacing st) (inc (count base-widths)))
                     short (- caption-w (+ cols-w spacing-w))]
                 (if (and (pos? short) (seq base-widths))
                   (let [add (long (Math/ceil (/ short (count base-widths))))]
                     (mapv #(+ % add) base-widths))
                   base-widths))
        ;; `border-collapse: collapse` removes the spacing between cells and
        ;; puts ONE border on each grid line, shared by the two cells that
        ;; meet there: each takes half of it into its own box and the other
        ;; half sits outside, which is why a collapsed table is narrower AND
        ;; shorter than the same table with separate borders. Measured in
        ;; Brave, `<table style="border-collapse:collapse"><tr><td
        ;; style="border:2px solid">a</td><td style="border:2px solid">b</td>
        ;; </tr></table>` is 24x26 with its cells 11x24 at x=1 and x=12,
        ;; where this engine reported 24x30 with 9x26 cells at x=2 and x=13.
        ;;
        ;; The width on a grid line is the WIDEST of the boxes that meet
        ;; there (the two cells, or a cell and the table's own border):
        ;; measured, 6px and 2px cell borders give cells 15px and 13px wide
        ;; in a 32px table -- 3px of the 6 on each side of the shared line --
        ;; and a 4px table border against 2px cells puts 2px inside the edge
        ;; cells and 2px outside them.
        ;;
        ;; Scope-cut, deliberate and load-bearing: this resolves border
        ;; WIDTHS only. Real CSS's conflict resolution also ranks
        ;; border-STYLE (`hidden` beats everything, `double` beats `solid`,
        ;; ...) and picks the winner's COLOUR, and it resolves per EDGE --
        ;; this engine's box model has one uniform border width per box, so
        ;; a cell whose two vertical edges resolve differently paints the
        ;; half of its OWN border on both sides and only its column width
        ;; accounts for the neighbour. Nothing here reads border-style
        ;; beyond the `none`/`hidden` gate node-style already applies.
        collapse? (= "collapse" (:border-collapse st))
        cell-border (fn [cell] (:border-width (node-style cell theme)))
        edge-max (fn [pick]
                   (fn [i]
                     (apply max 0
                            (for [a assigns
                                  :let [[lo hi] (pick a)]
                                  :when (or (= i lo) (= i hi))]
                              (cell-border (:cell a))))))
        n-cols* (max 1 (count base-widths))
        n-rows* (max 1 (count rows))
        vedge (if collapse?
                (mapv (fn [i] (if (or (zero? i) (= i n-cols*))
                                (max (:border-width st) ((edge-max (juxt :col #(+ (:col %) (:colspan %)))) i))
                                ((edge-max (juxt :col #(+ (:col %) (:colspan %)))) i)))
                      (range (inc n-cols*)))
                [])
        hedge (if collapse?
                (mapv (fn [j] (if (or (zero? j) (= j n-rows*))
                                (max (:border-width st) ((edge-max (juxt :row #(+ (:row %) (:rowspan %)))) j))
                                ((edge-max (juxt :row #(+ (:row %) (:rowspan %)))) j)))
                      (range (inc n-rows*)))
                [])
        half (fn [n] (quot n 2))
        ;; Real CSS: a table with `width: auto` is SHRINK-TO-FIT -- it is as
        ;; wide as its columns need, not as wide as its container. Filling
        ;; the container (what resolve-width does, correctly, for an
        ;; ordinary block) put every `<table>`, `<tr>` and row-group box in
        ;; the wrong place at once: the geometry axis reported table 0/9 and
        ;; tr 0/15 purely because of this one decision.
        spacing (if collapse? 0 (:border-spacing st))
        ;; What sits between the table's own content edge and the first/last
        ;; cell: the border-spacing on every side with separate borders, and
        ;; the OUTER half of the outermost collapsed border with collapsed
        ;; ones.
        lead-x (if collapse? (- (nth vedge 0 0) (half (nth vedge 0 0))) spacing)
        trail-x (if collapse? (- (nth vedge n-cols* 0) (half (nth vedge n-cols* 0))) spacing)
        lead-y (if collapse? (- (nth hedge 0 0) (half (nth hedge 0 0))) spacing)
        trail-y (if collapse? (- (nth hedge n-rows* 0) (half (nth hedge n-rows* 0))) spacing)
        widths (if collapse?
                 ;; A collapsed cell keeps only HALF of each of the two grid
                 ;; lines it sits between; the other half is the neighbour's
                 ;; (or, on the outer lines, the table's -- see lead-x /
                 ;; trail-x). The natural width measured for a cell is its
                 ;; ordinary border box -- content, padding and its WHOLE
                 ;; border, both sides -- so what has to come off each side
                 ;; is the OUTER half of that grid line, the same quantity
                 ;; lead-x/trail-x hand to the table.
                 ;;
                 ;; This used to ADD the inner half instead, because the
                 ;; natural width was content + padding and never the
                 ;; border: inset-side left the border out of every
                 ;; content-box element, and a `<td>` is content-box. The
                 ;; two forms agree exactly whenever a cell's own border is
                 ;; the one that wins its grid lines, which is the same
                 ;; approximation the scope-cut above already names -- this
                 ;; engine has one uniform border width per box, so a cell
                 ;; beaten on an edge by a thicker neighbour still paints
                 ;; its own. Measured in Brave on `:table/border-collapse`
                 ;; (two `border:2px` cells holding `a` and `b`): table
                 ;; 24x26, cells 11x24 at x=1 and x=12 -- which is what this
                 ;; produces, and what adding the inner half to a natural
                 ;; width that now carries the border turned into 32x26
                 ;; with 15px cells.
                 (vec (map-indexed (fn [c cw]
                                     (let [outer (fn [v] (- v (half v)))]
                                       (- cw
                                          (outer (nth vedge c 0))
                                          (outer (nth vedge (inc c) 0)))))
                                   widths))
                 widths)
        n-cols (count widths)
        natural-w (+ (reduce + 0 widths) (* spacing (max 0 (dec n-cols))) lead-x trail-x)
        w (if (:width st)
            (resolve-width st avail-width)
            (min (resolve-width st avail-width) (+ natural-w (* 2 inset))))
        content-x (+ x (:margin st) inset)
        content-y (+ y (:margin st) inset)
        content-w (max 0 (- w (* 2 inset)))
        ;; A table WIDER than its columns want hands the surplus to them
        ;; rather than leaving it at the right edge -- see distribute-excess
        ;; for what a browser actually does with it. Only in the automatic
        ;; algorithm: `table-layout: fixed` already sized every column
        ;; against the table's own width.
        widths (if fixed?
                 widths
                 (distribute-excess widths (- content-w (* spacing (max 0 (dec n-cols)))
                                              lead-x trail-x)))
        col-offsets (vec (reductions (fn [acc cw] (+ acc cw spacing))
                                     lead-x
                                     widths))
        caption-layout (when caption
                         (layout-node theme content-x content-y content-w opacity inherited caption))
        rows-y0 (+ content-y lead-y (if caption-layout (:h (:box caption-layout)) 0))
        ;; Row heights: single-row cells set their own row, then a
        ;; rowspan cell grows its LAST row if the rows it covers cannot
        ;; already hold it -- the same shortfall rule colspan uses across
        ;; columns.
        n-rows (count rows)
        laid-cells (mapv (fn [a]
                           (let [cw (+ (reduce + 0 (map #(nth widths % 0)
                                                        (range (:col a) (+ (:col a) (:colspan a)))))
                                       (* spacing (dec (:colspan a))))
                                 ;; With collapsed borders the cell keeps
                                 ;; only HALF its own border, so its box is
                                 ;; that much shorter -- the same halving
                                 ;; `widths` already applied across the
                                 ;; columns, applied down the rows by the
                                 ;; ordinary layout path.
                                 cell (if (and collapse? (pos? (cell-border (:cell a))))
                                        (assoc-in (:cell a) [:attrs :style/border-width]
                                                  (str (half (cell-border (:cell a))) "px"))
                                        (:cell a))
                                 m (layout-node theme 0 0 cw opacity inherited cell)
                                 ;; How tall the cell's CONTENT is, as
                                 ;; opposed to its box: a cell that declares
                                 ;; a `height` has a box taller than what is
                                 ;; in it, and that difference is the space
                                 ;; `vertical-align` distributes (see the
                                 ;; cell-draws comment below). Only measured
                                 ;; for a cell that declares one -- for every
                                 ;; other cell the box IS the content.
                                 cst (node-style cell theme)]
                             (assoc a :w cw :h (:h (:box m)) :draw (:draw m)
                                    :content-h (if (or (:height cst) (:min-height cst))
                                                 (:h (:box (layout-node
                                                            theme 0 0 cw opacity inherited
                                                            (update cell :attrs dissoc
                                                                    :style/height :style/min-height :height
                                                                    :kotoba/used-height))))
                                                 (:h (:box m))))))
                         assigns)
        row-heights
        (let [base (vec (for [r (range n-rows)]
                          (apply max 0 (for [c laid-cells
                                             :when (and (= 1 (:rowspan c)) (= r (:row c)))]
                                         (:h c)))))]
          (reduce (fn [hs c]
                    (if (= 1 (:rowspan c))
                      hs
                      (let [rs (range (:row c) (+ (:row c) (:rowspan c)))
                            have (+ (reduce + 0 (map #(nth hs % 0) rs))
                                    (* spacing (dec (:rowspan c))))
                            short (- (:h c) have)]
                        (if (pos? short)
                          (update hs (last rs) + short)
                          hs))))
                  base
                  laid-cells))
        row-offsets (vec (reductions (fn [acc rh] (+ acc rh spacing)) 0 row-heights))
        {:keys [draws groups]}
        (reduce
         (fn [{:keys [draws groups]} {:keys [row-idx row group anonymous]}]
           (let [row-y (+ rows-y0 (nth row-offsets row-idx 0))
                 row-h (nth row-heights row-idx 0)
                 rst (node-style row theme)
                 row-x (+ content-x lead-x)
                 row-w (max 0 (- content-w lead-x trail-x))
                 row-tag (or (:tag row) :tr)
                 ;; An ANONYMOUS row has no element behind it, so it gets no
                 ;; box: measured in Brave, a `display: table` with two bare
                 ;; `display: table-cell` children reports exactly three
                 ;; boxes (the table and the two cells).
                 row-op (when-not anonymous
                          (merge {:draw/op :node :id (:node/id row) :tag row-tag
                                  :x row-x :y row-y :w row-w :h row-h
                                  :class (attr row :class) :listeners (listeners row)
                                  :opacity opacity}
                                 (style-passthrough rst)))
                 row-bg (when-let [bg (and (not anonymous) (:background rst))]
                          [{:draw/op :rect :x row-x :y row-y
                            :w row-w :h row-h
                            :color bg :tag row-tag :opacity opacity}])
                 cells (filter #(= row-idx (:row %)) laid-cells)
                 cell-draws (mapcat
                             (fn [c]
                               ;; A `<td>`/`<th>`'s UA default is
                               ;; `vertical-align: middle`, so its content
                               ;; is centred in the cell box -- which is
                               ;; what makes a `rowspan` cell sit BETWEEN
                               ;; the rows it covers rather than at the top
                               ;; of the first one. Measured: the browser
                               ;; renders `tall` (rowspan 2) on its own line
                               ;; between `a` and `b`, where this engine put
                               ;; it beside `a`.
                               ;;
                               ;; An AUTHORED `vertical-align` overrides it.
                               ;; Measured, `<td style="height:60px;
                               ;; vertical-align:top">top</td><td
                               ;; style="height:60px; vertical-align:bottom">
                               ;; bot</td>` puts the two words on different
                               ;; LINES; centring both put them on one, which
                               ;; is what `:table/cell-vertical-align`
                               ;; reported as `want ["top" "bot"] got
                               ;; ["top bot"]`.
                               ;;
                               ;; `baseline` -- the real initial value, and
                               ;; what a `display: table-cell` on an ordinary
                               ;; element computes to (a `<td>` inherits
                               ;; `middle` from the UA's `table` rule) -- is
                               ;; taken as top here. Real CSS aligns the
                               ;; cells' first BASELINES, which coincides with
                               ;; the top whenever the cells' first lines
                               ;; share a font, and this engine does not carry
                               ;; a per-cell baseline out of layout-node to do
                               ;; better.
                               (let [cell-h (+ (reduce + 0 (map #(nth row-heights % 0)
                                                                (range (:row c) (+ (:row c) (:rowspan c)))))
                                               (* spacing (dec (:rowspan c))))
                                     cst (node-style (:cell c) theme)
                                     va (or (:vertical-align cst)
                                            (if (contains? table-cell-tags (:tag (:cell c)))
                                              "middle"
                                              "baseline"))
                                     align (fn [free]
                                             (case va
                                               ("top" "baseline" "text-top") 0
                                               ("bottom" "text-bottom") free
                                               (quot free 2)))
                                     ;; TWO gaps can open under a cell, and
                                     ;; `vertical-align` distributes both.
                                     ;; The cell's BOX floats in the rows it
                                     ;; spans when it is shorter than they
                                     ;; are (a `rowspan` cell), and the
                                     ;; cell's CONTENT floats in the box when
                                     ;; the cell declares a `height` bigger
                                     ;; than what is in it. Only the first
                                     ;; existed here, which is why the
                                     ;; `height: 60px` cells in
                                     ;; `:table/cell-vertical-align` ignored
                                     ;; their `top`/`bottom` entirely -- box
                                     ;; and row were the same 60px, so there
                                     ;; was nothing for the old shift to move.
                                     dy-box (align (max 0 (- cell-h (:h c))))
                                     dy-inner (align (max 0 (- (:h c) (:content-h c (:h c)))))
                                     cell-id (:node/id (:cell c))
                                     ;; the inner shift moves the cell's
                                     ;; CONTENT only, never its own
                                     ;; background/border/box -- those ops
                                     ;; are the ones emitted up to and
                                     ;; including the cell's own `:node` op.
                                     ops (let [v (vec (:draw c))
                                               i (first (keep-indexed
                                                         (fn [i op]
                                                           (when (and (= :node (:draw/op op))
                                                                      (= cell-id (:id op)))
                                                             i))
                                                         v))]
                                           (if (and (pos? dy-inner) i)
                                             (into (subvec v 0 (inc i))
                                                   (translate-ops 0 dy-inner (subvec v (inc i))))
                                             v))]
                                 (->> (translate-ops (+ content-x (nth col-offsets (:col c) 0))
                                                     (+ row-y dy-box)
                                                     ops)
                                      ;; the cell's OWN box spans every row
                                      ;; it covers, even though its content
                                      ;; is centred inside that box
                                      (mapv (fn [op]
                                              (if (and (= :node (:draw/op op))
                                                       (= cell-id (:id op)))
                                                (assoc op :y row-y :h cell-h)
                                                op))))))
                             cells)]
             {:draws (vec (concat draws row-bg (when row-op [row-op]) cell-draws))
              :groups (if group
                        (update groups group
                                (fn [g] {:y (min (:y g row-y) row-y)
                                         :h (+ (:h g 0) row-h spacing)}))
                        groups)}))
         {:draws [] :groups {}}
         (map-indexed (fn [i r] (assoc r :row-idx i)) rows))
        height (+ (reduce + 0 row-heights) (* spacing (max 0 (dec (count row-heights)))) trail-y)
        group-ops (mapv (fn [[g {:keys [y h]}]]
                          (merge {:draw/op :node :id (:node/id g) :tag (:tag g)
                                  :x (+ content-x lead-x) :y y
                                  :w (max 0 (- content-w lead-x trail-x)) :h (max 0 (- h spacing))
                                  :class (attr g :class) :listeners (listeners g)
                                  :opacity opacity}
                                 (style-passthrough (node-style g theme))))
                        groups)
        ;; `<colgroup>`/`<col>` get real boxes too, spanning the columns
        ;; they cover for the full height of the rows -- measured in Brave,
        ;; a `<col style="width:120px">` reports 120x22 next to its table's
        ;; own 186x26, and this engine reported no box at all.
        rows-h (+ (reduce + 0 row-heights) (* spacing (max 0 (dec (count row-heights)))))
        col-span-w (fn [start n]
                     (+ (reduce + 0 (map #(nth widths % 0) (range start (+ start n))))
                        (* spacing (max 0 (dec n)))))
        col-ops (vec (for [c columns
                           :when (and (:first? c) (< (:col c) (count widths)))]
                       (merge {:draw/op :node :id (:node/id (:el c)) :tag (:tag (:el c))
                               :x (+ content-x (nth col-offsets (:col c) 0)) :y rows-y0
                               :w (col-span-w (:col c) (:span c)) :h rows-h
                               :class (attr (:el c) :class) :listeners (listeners (:el c))
                               :opacity opacity}
                              (style-passthrough (node-style (:el c) theme)))))
        colgroup-ops (vec (for [[g cs] (group-by :group columns)
                                :when (and g (not= g (:el (first cs))))
                                :let [lo (apply min (map :col cs))
                                      hi (apply max (map :col cs))]
                                :when (< lo (count widths))]
                            (merge {:draw/op :node :id (:node/id g) :tag (:tag g)
                                    :x (+ content-x (nth col-offsets lo 0)) :y rows-y0
                                    :w (col-span-w lo (inc (- (min hi (dec (count widths))) lo)))
                                    :h rows-h
                                    :class (attr g :class) :listeners (listeners g)
                                    :opacity opacity}
                                   (style-passthrough (node-style g theme)))))
        table-h (clamp-height st (+ (- rows-y0 y) height inset))
        table-w w]
    {:box {:x x :y y :w table-w :h table-h}
     :draw (vec (concat
                 (or (box-shadow-ops st x y table-w table-h opacity) [])
                 ;; the element's OWN tag, not `:table`: a `display: table`
                 ;; div is still a div to a hit-tester, an accessibility
                 ;; projection and the conformance harness's tag-matched
                 ;; geometry axis. Emitting `:table` for it meant the two
                 ;; div-table cases had no div boxes to compare at all.
                 (when-let [bg (default-bg (:tag node) st theme)]
                   [{:draw/op :rect :x x :y y :w table-w :h table-h :color bg
                     :tag (:tag node) :opacity opacity}])
                 (or (border-ops st x y table-w table-h opacity) [])
                 (or (outline-ops st x y table-w table-h opacity) [])
                 [(merge {:draw/op :node :id (:node/id node) :tag (:tag node)
                          :x x :y y :w table-w :h table-h
                          :class (attr node :class) :listeners (listeners node)
                          :opacity opacity}
                         (style-passthrough st))]
                 (:draw caption-layout)
                 colgroup-ops
                 col-ops
                 group-ops
                 draws))}))

(defn- layout-grid
  "display:grid subset: explicit `grid-template-columns`/`grid-template-rows`
   track lists (fixed px + fr, plus `repeat()`/`minmax()` composing over
   them — see parse-track-list), THREE composing per-item placement
   mechanisms — explicit `grid-column`/`grid-row` line-based placement (see
   parse-grid-placement), named `grid-area` placement resolved against the
   container's own `grid-template-areas` (see parse-grid-template-areas), and
   auto-placement in DOM order, row-major (fills a row left-to-right before
   wrapping to the next row) for everything else — see place-grid-items/
   item-grid-placement for exactly how all three compose. `grid-auto-flow:
   column` swaps that last axis (fills a column top-to-bottom before moving
   right, growing implicit COLUMNS rather than implicit rows); it is
   implemented by transposing the placement problem, see place-grid-items.
   `gap` — the same style key flex already reuses — spaces both axes, and
   `row-gap`/`column-gap` (or `gap: <row> <column>`) space them
   independently, see node-style.

   Column count = the number of parsed grid-template-columns tracks. With no
   (or a blank) grid-template-columns, this falls back to `grid-template-areas`'s
   OWN column count when a valid areas template is declared — evenly-split
   `1fr` tracks, so named areas actually land in distinct columns instead of
   collapsing onto one — or, with neither declared, a single
   full-content-width column (i.e. behaves like a vertical stack) — a
   reasonable default that also keeps `display:grid` usable with only
   grid-template-rows/grid-template-areas set. This is the one place
   grid-template-areas feeds back into track resolution: its OWN row count
   is never consulted (rows are already an unbounded, auto-growing axis in
   this engine regardless of any track/areas row count — see the row-sizing
   paragraph below), and when grid-template-columns IS explicitly declared,
   its own track count wins outright even if it disagrees with the areas
   template's column count (this engine does not reconcile the two beyond
   that one fallback case — an honest, documented non-goal, not a guess).
   Column track sizes are always resolved against the container's definite
   content-width (`cw`), exactly like flexbox's main-axis sizing whenever the
   main size is known — so `fr` columns are always well-defined (see
   track-sizes). An `auto` column is sized from its own items instead: their
   min-content width is its floor and their max-content width its growth
   limit (grid-track-intrinsics measures both), and whatever is left over is
   shared EQUALLY among the auto tracks unless an `fr` track is competing
   for it — see track-sizes for the measured numbers behind each of those
   three rules. An item spanning more than one column (`grid-column: 1 / 3`,
   or a multi-column grid-area) gets the combined width of every column it
   spans plus the gaps between them (span-width), and is measured against
   that combined width exactly like a plain single-column item is measured
   against its one column's width (so it stretches to fill the whole span
   when it has no explicit width of its own) — note this engine has no
   analogous auto-stretch for HEIGHT across a multi-row span; an item's own
   height is always its own (auto-content or explicit), never stretched to
   its row-span's combined height, matching the box-height convention
   grid-column/grid-row explicit placement already established.

   Explicit line-based placement (`grid-column`/`grid-row`, see
   parse-grid-placement): a plain 1-based line number (`grid-column: 2`), the
   two-value `<start> / <end>` shorthand (`grid-column: 1 / 3`), and
   `<start> / span <n>` (`grid-column: 2 / span 2`) are all supported for
   both axes. A negative line/index (`grid-column: -1`, 'the last column')
   is also supported as a deliberately pragmatic stretch goal. NOT
   supported: the `-start`/`-end` longhand properties and dense packing (see
   parse-grid-placement's own docstring for the precise grammar/arithmetic).
   An out-of-range column line (e.g. `grid-column: 5` with only 3 declared
   column tracks) does NOT implicitly create a new column track the way real
   CSS does (explicitly out of scope) — instead it's CLAMPED into the
   declared column range (clamp-col-range), landing on/overlapping the last
   column rather than indexing past col-widths/col-offsets or crashing.

   Named placement (`grid-template-areas`/`grid-area`, see
   parse-grid-template-areas/item-grid-placement): `grid-template-areas` is a
   sequence of quoted-string rows, each whitespace-separated token naming
   which area occupies that cell (`.` = intentionally empty); a repeated
   name spanning several adjacent cells occupies their combined rectangle.
   `grid-area: <name>` on an item places it at that name's own rectangle. An
   item with BOTH grid-area and an explicit grid-column/grid-row gets the
   explicit value on whichever axis/axes it declares (mirrors real CSS's
   longhand-conflict resolution, since grid-area is equivalent to setting
   the same four longhands grid-column/grid-row do — see item-grid-placement
   for the exact per-axis rule), falling back to grid-area's own range for
   any axis left undeclared. A `grid-area` name not found in the container's
   (or not present at all, or malformed/non-rectangular — see
   parse-grid-template-areas) `grid-template-areas` is an honest non-match:
   the item falls back to grid-column/grid-row if declared, else fully
   auto-placed — never a crash or a guessed-at cell. NOT supported: the
   4-value `grid-area: <row-start> / <col-start> / <row-end> / <col-end>`
   longhand shorthand (only a bare area-name reference is parsed).

   Auto-placed items that resolve neither axis via either of the two
   explicit mechanisms compose with explicitly-placed ones (from either
   mechanism) per the documented simplification in place-grid-items (short
   version: explicit items are placed first in DOM order, then auto items
   fill remaining single cells row-major, skipping whatever's already
   occupied — no sophisticated backfill).

   Row sizing: rows are auto-generated (row-major wrap, extended as needed
   by any explicit grid-row/grid-area placement that reaches further than
   auto-placement alone would) to fit however many rows are actually
   needed, regardless of how many grid-template-rows tracks (or
   grid-template-areas rows) were declared — there is no implicit-grid
   concept beyond 'add another row', and (unlike columns) no clamping: a row
   is not a fixed, finite axis in this engine to begin with, so an
   out-of-range grid-row line (or a grid-area whose own rows reach further
   than grid-template-rows declares) just means however many more (possibly
   empty, 0px-tall) rows are needed to reach it. Every row has a TRACK:
   whatever grid-template-rows declared for that index, then
   `grid-auto-rows` (cycled, as real CSS does) for the implicit rows past
   it, then `auto` when neither said anything — which is what every row got
   before grid-auto-rows was read at all, so nothing that worked before
   changes. A *fixed*-px row track uses that literal height. An `auto` row
   is sized to the tallest child whose placement STARTS in it (mirrors
   flexbox's own auto cross-axis convention elsewhere in this file; a
   multi-row-span item's height only contributes to its start row's
   auto-sizing, not any row it merely passes through — a documented
   simplification, row spans are not this feature's must-have), UNLESS the
   grid container has an explicit :height, in which case the whole row track
   list is resolved against that height the same way columns are: `fr` rows
   share it proportionally, `auto` rows stretch past their content to fill
   it (measured in Brave: `grid-template-rows: 30px` with three items in a
   200px-tall grid gives 30 / 85 / 85). Without an explicit container height
   there is no definite total to share, so `fr` and `auto` rows both fall
   back to content sizing — the one deliberate asymmetry versus columns in
   this subset, documented here rather than silently guessed at.

   Item alignment: `justify-items`/`justify-self` (inline axis) and
   `align-items`/`align-self` (block axis) both work, and both are a SIZE
   decision as much as a position one. `stretch` — the initial value — fills
   the whole track, which is why an item with no width is a full column wide
   and an item in a 60px row track is 60px tall. Any other value makes the
   item fit-content (max-content, clamped to the track) and positions it in
   the track via cross-offset, the same helper flexbox's cross axis uses.
   Measured in Brave, `justify-items: center` in a 120px column puts a
   one-character item at x=55.4 with a 9.2px box, where filling the track
   gave 0 and 120.

   Absolute-positioned children are NOT taken out of flow here — this
   matches layout-flex's current behavior (today only layout-children-block
   takes out-of-flow children out); a position:absolute child inside a grid
   container is placed as an ordinary grid item, the same limitation flex
   already has.

   `repeat(<integer>, <track>)`, `minmax(<px>, <px-or-1fr>)` and `auto` ARE
   supported and compose (e.g. `repeat(3, minmax(80px, 1fr))`) — see
   parse-track-list/parse-track-token/track-sizes. Explicitly out of scope:
   percentage tracks, `min-content`/`max-content`/`fit-content()` as track
   keywords (only bare `auto` is recognised; the others still degrade to a
   0px track), `repeat(auto-fill|auto-fit, ...)`, dense packing, the
   grid-column-start/grid-column-end/grid-row-start/grid-row-end longhand
   properties (only the grid-column/grid-row shorthand is parsed), the
   4-value grid-area longhand shorthand (only a bare area-name reference is
   parsed, see above), `justify-content`/`align-content` on the container
   (tracks are always placed from the start edge — see track-sizes for what
   that costs the `auto` stretch step), and implicit COLUMN creation in row
   flow: an out-of-range `grid-column` is still clamped into the declared
   range (clamp-col-range) rather than growing the grid, so
   `grid-auto-columns` only reaches the implicit tracks `grid-auto-flow:
   column` creates. Measured in Brave, `grid-template-columns: 60px` with a
   `grid-column: 2` item and `grid-auto-columns: 90px` makes a second 90px
   column where this engine puts the item back in the first one."
  [theme x y avail-width opacity inherited st node in-flow]
  (let [;; A grid item's containing block is this grid area, not whatever
        ;; block set the percentage-height basis on the way in -- same
        ;; reasoning, and same honest `auto`, as layout-flex's own dissoc.
        inherited (dissoc inherited :block/containing-height)
        ;; `display: inline-grid` is a grid container that is INLINE-level:
        ;; it sits in its parent's line box and shrink-wraps to its tracks
        ;; instead of filling its containing block -- exactly what
        ;; `inline-flex` already does for flex.
        inline? (= "inline-grid" (:display st))
        inset (content-inset st)
        cx (+ x (:margin st) inset)
        cy (+ y (:margin st) inset)
        ;; the two gap axes are separate now (`row-gap`/`column-gap`, see
        ;; node-style); `gap` alone still sets both, which is why every
        ;; previously-passing single-`gap` case is unaffected
        row-gap (:row-gap st)
        col-gap (:column-gap st)
        flow-column? (str/includes? (str/lower-case (str (:grid-auto-flow st))) "column")
        template-areas (parse-grid-template-areas (:grid-template-areas st))
        explicit-cols (parse-track-list (:grid-template-columns st))
        auto-col-tracks (parse-track-list (:grid-auto-columns st))
        row-tracks (parse-track-list (:grid-template-rows st))
        auto-row-tracks (parse-track-list (:grid-auto-rows st))
        n-row-tracks (count row-tracks)
        ;; the width available to measure and to size tracks against,
        ;; before an inline-level container shrink-wraps below it
        avail-w (resolve-width st avail-width)
        avail-cw (max 0 (- avail-w (* 2 inset)))
        declared-cols (cond
                        (seq explicit-cols) explicit-cols
                        template-areas (vec (repeat (:col-count template-areas) {:type :fr :size 1.0}))
                        :else [])
        placements (place-grid-items theme in-flow (max 1 (count declared-cols))
                                     n-row-tracks (:areas template-areas) flow-column?)
        total-rows (if (seq placements) (apply max 0 (map :row-end placements)) 0)
        ;; IMPLICIT columns exist only under `grid-auto-flow: column`, where
        ;; the column axis is the one that grows (in row flow the column
        ;; count is fixed and an out-of-range line is clamped instead --
        ;; see clamp-col-range).
        n-cols (max 1 (count declared-cols)
                    (if (seq placements) (apply max 0 (map :col-end placements)) 0))
        implicit-track (fn [tracks i]
                         (when (seq tracks) (nth tracks (mod i (count tracks)))))
        col-tracks (if (and (empty? declared-cols) (empty? auto-col-tracks) (= 1 n-cols))
                     ;; no tracks declared at all: one column, which fills
                     ;; the container for a block-level grid (the
                     ;; pre-existing fallback) and hugs its content for an
                     ;; inline-level one
                     [(if inline? {:type :auto} {:type :fixed :size avail-cw})]
                     (mapv (fn [i]
                             (or (nth declared-cols i nil)
                                 (implicit-track auto-col-tracks (- i (count declared-cols)))
                                 {:type :auto}))
                           (range n-cols)))
        ;; measuring every item twice is not free, so it only happens when
        ;; an `auto` track is actually present to need it
        col-intrinsics (if (some #(= :auto (:type %)) col-tracks)
                         (grid-track-intrinsics theme avail-cw opacity inherited
                                                in-flow placements n-cols)
                         [])
        ;; An inline-level grid's own width is its tracks' max-content sum
        ;; plus the gaps between them -- measured in Brave, an
        ;; `inline-grid` with `grid-template-columns: 30px 30px` inside a
        ;; sentence is 60px wide and stays on the line, where a
        ;; block-level box would have taken the whole 800px width and
        ;; broken the sentence into three lines.
        fr-intrinsic (apply max 0 (map-indexed (fn [i t] (if (fr-weight t)
                                                           (:max (nth col-intrinsics i nil) 0)
                                                           0))
                                               col-tracks))
        intrinsic-cw (+ (reduce + 0 (map-indexed
                                     (fn [i t]
                                       (case (:type t)
                                         :fixed (:size t)
                                         ;; every `fr` track equalises to the
                                         ;; widest of them under an
                                         ;; indefinite size
                                         :fr fr-intrinsic
                                         :minmax (max (:min t) (:max (nth col-intrinsics i nil) 0))
                                         (:max (nth col-intrinsics i nil) 0)))
                                     col-tracks))
                        (* col-gap (max 0 (dec (count col-tracks)))))
        cw (if (and inline? (nil? (:width st))) (max 0 (min avail-cw intrinsic-cw)) avail-cw)
        w (if (and inline? (nil? (:width st))) (+ cw (* 2 inset)) avail-w)
        col-widths (track-sizes col-tracks col-gap cw col-intrinsics)
        col-offsets (place-main-axis "flex-start" col-widths col-gap 0)
        explicit-h (resolve-height st)
        ;; Every row has a track now: whatever grid-template-rows declared,
        ;; then grid-auto-rows for the implicit ones beyond it, then `auto`
        ;; (content-sized) when neither said anything -- which is exactly
        ;; what every row got before grid-auto-rows was read at all.
        all-row-tracks (mapv (fn [i]
                               (or (nth row-tracks i nil)
                                   (implicit-track auto-row-tracks (- i n-row-tracks))
                                   {:type :auto}))
                             (range total-rows))
        span-w (fn [pl] (span-width col-widths col-gap (:col-start pl) (:col-end pl)))
        ;; A grid item fills its column track under `justify-items:
        ;; stretch` (the initial value) and is fit-content under anything
        ;; else, which is a SIZE difference and therefore has to be decided
        ;; before the item is measured -- exactly the shape layout-flex
        ;; already uses for a column container's cross axis.
        inline-align (fn [child]
                       (let [self (when (map? child) (:justify-self (node-style child theme)))]
                         (if (and self (not= "auto" self)) self (:justify-items st))))
        inline-aligns (mapv inline-align in-flow)
        measured (mapv (fn [child pl align]
                         (measure-child theme (span-w pl) opacity inherited child
                                        (not (contains? #{"stretch" "normal"} align))))
                       in-flow placements inline-aligns)
        row-content-h (mapv (fn [row-idx]
                              (let [hs (keep-indexed
                                        (fn [i pl]
                                          (when (= row-idx (:row-start pl))
                                            (:h (:box (nth measured i)))))
                                        placements)]
                                (if (seq hs) (apply max 0 hs) 0)))
                            (range total-rows))
        row-track-fr-sizes (when explicit-h
                             (track-sizes all-row-tracks row-gap explicit-h
                                          (mapv (fn [h] {:min h :max h}) row-content-h)))
        row-heights (mapv (fn [row-idx]
                            (let [track (nth all-row-tracks row-idx)]
                              (cond
                                (= :fixed (:type track)) (:size track)
                                row-track-fr-sizes (nth row-track-fr-sizes row-idx)
                                :else (nth row-content-h row-idx))))
                          (range total-rows))
        row-span-h (fn [pl] (reduce + 0 (map #(nth row-heights % 0)
                                             (range (:row-start pl) (:row-end pl)))))
        ;; A grid item STRETCHES to fill its row track (`align-items:
        ;; stretch` is the default), so an item in a `grid-template-rows:
        ;; 40px` track is 40px tall whatever its content needs. Under any
        ;; other `align-items`/`align-self` it keeps its own height and is
        ;; POSITIONED in the track instead (see block-aligns below):
        ;; measured in Brave, `align-items: center` on a 60px row leaves a
        ;; 20px item at y=20, where stretching it unconditionally gave a
        ;; 60px box at y=0.
        block-aligns (mapv #(item-cross-align theme st %) in-flow)
        measured (mapv (fn [pl m child align]
                         (let [rh (row-span-h pl)]
                           (if (and (map? child) (> rh (:h (:box m))) (= "stretch" align)
                                    (not (:height (node-style child theme))))
                             (measure-child theme (span-w pl) opacity inherited
                                            (force-cross-size false rh child) false)
                             m)))
                       placements measured in-flow block-aligns)
        row-offsets (place-main-axis "flex-start" row-heights row-gap 0)
        draws (vec (mapcat (fn [pl m child ja aa]
                             (let [[rdx rdy] (relative-item-offset theme child)]
                               (translate-ops
                                (+ cx (nth col-offsets (:col-start pl))
                                   (cross-offset ja (:w (:box m)) (span-w pl)) rdx)
                                (+ cy (nth row-offsets (:row-start pl))
                                   (cross-offset aa (:h (:box m)) (row-span-h pl)) rdy)
                                (:draw m))))
                           placements measured in-flow inline-aligns block-aligns))
        content-h (+ (reduce + 0 row-heights) (* row-gap (max 0 (dec (count row-heights)))))
        node-h (or explicit-h (+ content-h (* 2 inset)))]
    {:box-w w :box-h node-h :draws draws}))

(defn- relative-offset
  "Real CSS `position: relative`'s own offset -- unlike `position:
   absolute` (see `layout-absolute-children` above, which solves an
   UNKNOWN edge against the CONTAINING block's own size), a relatively
   positioned box's offset is always a direct pixel shift from its own
   normal (static) position: `top` shifts the box DOWN, `left` shifts it
   RIGHT (each winning over `bottom`/`right` respectively when both are
   present, matching this file's own established left/top-wins
   convention for absolute positioning above), with no containing-
   block-size-dependent math needed at all -- `bottom`/`right` alone
   shift the OPPOSITE physical direction (a positive `bottom` pulls the
   box UP, a positive `right` pulls it LEFT), matching real CSS exactly."
  ;; `basis-w`/`basis-h` are the containing block's content dimensions, for
  ;; percentage offsets (`left: 50%`). The 1-arity keeps the previous
  ;; behaviour for the flex/grid item call site, which does not have them
  ;; threaded yet -- a percentage offset on a flex item is therefore still
  ;; ignored rather than misread as pixels, and that is a smaller lie than
  ;; the pixels were.
  ([st] (relative-offset st nil nil))
  ([st basis-w basis-h]
   (let [left (length-or-percentage (:left st) basis-w)
         right (length-or-percentage (:right st) basis-w)
         top (length-or-percentage (:top st) basis-h)
         bottom (length-or-percentage (:bottom st) basis-h)]
     [(cond left left right (- right) :else 0)
      (cond top top bottom (- bottom) :else 0)])))

;; ---- inline formatting context ----

(def ^:private inline-level-tags
  "Tags whose real HTML5 UA stylesheet default is `display: inline`, i.e.
   the ones that must share a line box with adjacent text instead of
   stacking as their own block row (see layout-inline-run).

   This engine has no UA stylesheet of its own — cssom.core's cascade only
   ever resolves AUTHOR declarations, so `node-style`'s `:display` is
   simply nil for an element no author rule targets (confirmed by reading
   node-style: `(style node :display)` with a `[hidden]`-only fallback).
   That is why inline-level-ness has to be a tag-name set here rather than
   a `(= \"inline\" display)` check alone: a real `<b>`/`<a>`/`<span>` in
   real-world HTML almost never carries an explicit `display: inline`
   declaration, it just IS inline by UA default. An explicit author
   `display: inline` on any other element (e.g. a `<div>`) is honored too,
   and an explicit non-inline display on one of these tags (e.g.
   `span { display: block }`) correctly takes it back OUT of inline flow —
   see inline-level-element?.

   Replaced and form-control elements are inline-level too, but they are
   ATOMIC rather than text-like, so they live in inline-atomic-tags below
   and take a different path through the run."
  #{:a :abbr :b :bdi :bdo :br :cite :code :data :del :dfn :em :i :ins :kbd
    :label :mark :meter :output :progress :q :ruby :rt :rp :s :samp :small
    :span :strong :sub :sup :time :u :var :wbr})

(def ^:private inline-atomic-displays
  "The `display` values that make an ordinary element an ATOMIC inline: a
   box that lays its own children out internally by its own rules, but
   sits in its parent's line box like a word.

   `inline-block` is the original spelling of that concept;
   `inline-flex` is the same box with a flex formatting context inside
   it, and takes the identical path -- shrink-wrap to content
   (layout-flex's own `inline?` branch), then sit in the line. Measured
   in Brave, an `inline-flex` span between two words keeps all of it on
   one line (`before a b after`); this engine gave the span a block row
   of its own and produced three lines.

   `inline-grid` IS here now: layout-grid grew the shrink-to-fit branch
   this set used to be waiting on -- an inline-level grid sizes itself to
   its tracks' max-content sum plus the gaps between them (see
   layout-grid's `intrinsic-cw`), so admitting it puts a 60px two-track
   box in the line rather than a full-container-width one. Measured in
   Brave on `before <span style=\"display: inline-grid;
   grid-template-columns: 30px 30px\">...</span> after`: the span is 60px
   wide at x=58 and the whole sentence is ONE 20px line, where the block
   row this engine gave it made three lines."
  #{"inline-block" "inline-flex" "inline-grid"})

(defn- inline-atomic-element?
  "True for an element that participates in a line as one unbreakable box:
   a replaced/form-control tag (inline-atomic-tags), or ANY element an
   author gives one of the inline-atomic-displays.

   Before this, an `inline-block` span fell through to a block row and
   broke the sentence around it in two."
  [theme child]
  (and (map? child)
       (= :element (:node/type child))
       (or (contains? inline-atomic-tags (:tag child))
           (contains? inline-atomic-displays (:display (node-style child theme))))))

(def ^:private in-flow-positions
  "The `position` values that leave a box in normal flow, and so let an
   inline-level one join a line box.

   `relative` is HERE, and used to not be: it was excluded on the grounds
   that a positioned inline \"would need its own offset treatment inside
   the line\", which was true and is now implemented (inline-fragments
   accumulates the offset onto the owner stack, layout-inline-run applies
   it at paint time -- exactly the paint-only shift
   layout-children-block already gives a relative BLOCK row). Excluding it
   did far more damage than a missing offset: it took the element out of
   the inline path entirely, so an ordinary
   `<p>text <span style=\"position: relative\">anchor</span> tail</p>`
   collapsed into three full-width block rows. Measured in Brave that
   paragraph is ONE 20px line with the span at (35,2); this engine made it
   60px tall with the span at x=0, 800 wide -- and being the anchor for an
   absolutely positioned child is the single most common reason anyone
   writes `position: relative` at all.

   `sticky` is here for the reason `absolute?` already gives: its
   unscrolled position is legitimately its flow position, and this engine
   has no scroll-dependent re-layout.

   `absolute`/`fixed` are deliberately absent -- they are out of flow, and
   layout-children-block's own out-of-flow branch owns them."
  #{"static" "relative" "sticky"})

(defn- inline-level-element?
  "True when `child` is an element this file will flow into a line box:
   inline-level by author `display: inline` or by inline-level-tags UA
   default, in flow (in-flow-positions), and actually rendered."
  [theme child]
  (and (map? child)
       (= :element (:node/type child))
       (not (non-rendered-tag? (:tag child)))
       (let [st (node-style child theme)]
         (and (contains? in-flow-positions (:position st))
              (not= "none" (:display st))
              (if (:display st)
                (= "inline" (:display st))
                (contains? inline-level-tags (:tag child)))))))

(defn- float-child?
  "True when `child` is a `float: left|right` box.

   Hoisted out of the three places that used to spell this predicate
   inline (inline-flow-candidate?, inline-runs, layout-children-block)
   because all three have to agree about it exactly: a float is
   blockified, it does not join a line box, it does not SPLIT one, and it
   is positioned by its container's float machinery rather than by block
   flow. Three copies of the same `contains? #{\"left\" \"right\"}` is
   three chances for them to drift apart."
  [theme child]
  (and (map? child)
       (= :element (:node/type child))
       (contains? #{"left" "right"} (:float (node-style child theme)))))

(defn- inline-flow-text?
  [child]
  (or (some? (real-text-child child))
      (generated-node? child)))

(defn- inline-fragment-bearing?
  "True when `child` would actually contribute a FRAGMENT to a line box --
   some text, some generated content, or an atomic inline -- as opposed to
   being an inline element with nothing inside it.

   Only the lone-element widening in inline-runs consults this, and the
   reason is a real hole rather than a nicety: inline-fragments records an
   inline element as an OWNER when one of its pieces is emitted, so an
   element that emits no piece gets no `:node` draw-op at all. For
   `<div><span></span></div>` that is the difference between a box a
   hit-tester/accessibility projection can find and no box in the op
   stream whatsoever, and the block-row path this keeps such an element on
   does give it one.

   The same hole exists for an empty inline box inside a MULTI-child run
   (`<p>a<span></span>b</p>` emits no span op today either). That is
   pre-existing and NOT fixed here -- closing it needs inline-fragments to
   emit a zero-width marker piece that survives the tokenizer and the line
   breaker, which is real machinery rather than a predicate."
  [theme child]
  (cond
    (some? (real-text-child child)) (not (str/blank? (real-text-child child)))
    (generated-node? child) true
    (inline-atomic-element? theme child) true
    (and (map? child) (= :element (:node/type child)))
    (boolean (some #(inline-fragment-bearing? theme %)
                   (with-generated-content child (:children child))))
    :else false))

(defn- inline-flow-candidate?
  "True when `child` can participate in an inline formatting context (see
   layout-inline-run): a real text node, a generated ::before/::after
   node, or an inline-level element whose WHOLE subtree is itself made of
   nothing but those things.

   The whole-subtree requirement is the guard that keeps this feature
   honest: `<span><div>x</div></span>` is legal HTML, and real CSS handles
   it by splitting the inline box around the block child (the
   `block-in-inline` box-tree fixup). This engine does not implement that
   split, so rather than silently mis-nesting such a subtree into one line
   box, the whole element falls back to the pre-existing block-row path —
   exactly the behavior it had before this feature existed, no worse.

   `white-space` must be normal for the same class of reason: `pre`/
   `pre-wrap`/`pre-line`/`nowrap` each mean the run must preserve or
   re-interpret newlines and runs of spaces, which layout-text already
   implements per-property for a single text child; the inline tokenizer
   here collapses whitespace unconditionally, so anything declaring a
   non-normal `white-space` keeps the existing single-child path rather
   than being quietly re-collapsed."
  [theme child]
  (cond
    (inline-flow-text? child) true

    ;; A floated element is BLOCKIFIED and positioned by its container's
    ;; float machinery (see layout-children-block), so it never
    ;; participates in a line box even when its tag is inline-level.
    (float-child? theme child)
    false

    ;; An atomic inline (an <img>/<input>/<button>/<select>/<textarea>) has
    ;; no subtree requirement: whatever is inside it is laid out by its own
    ;; box, not flattened into this line, so `block-in-inline` cannot arise.
    ;; It still has to be in flow (in-flow-positions) and actually
    ;; displayed.
    (inline-atomic-element? theme child)
    (let [st (node-style child theme)]
      (and (contains? in-flow-positions (:position st))
           (not= "none" (:display st))
           (or (nil? (:display st))
               (= "inline" (:display st))
               (contains? inline-atomic-displays (:display st)))))

    (inline-level-element? theme child)
    (let [st (node-style child theme)]
      (and (contains? #{nil "normal"} (:white-space st))
           (every? (fn [c]
                     (or (inline-flow-text? c)
                         (and (map? c)
                              (= :element (:node/type c))
                              (non-rendered-tag? (:tag c)))
                         ;; an out-of-flow descendant contributes nothing
                         ;; to the line, so it cannot make its ancestor
                         ;; unflowable -- and an inline box whose child is
                         ;; positioned against it is the single most
                         ;; common reason to write `position: relative`
                         ;; at all, so refusing to flow it here would take
                         ;; the whole paragraph off the inline path
                         (absolute? theme c)
                         (inline-flow-candidate? theme c)))
                   (:children child))))

    :else false))

(def ^:private vertical-align-shift
  "How far `vertical-align` raises (positive) or lowers (negative) an inline
   box from its parent's baseline, as a fraction of the PARENT's font size.

   Measured in Chrome rather than guessed: `super` raises a 14px run by
   5.66px and `sub` lowers it by 3.79px, i.e. 0.404em and 0.271em. These are
   the font's own superscript/subscript offsets, which a real browser reads
   from the OS/2 table; this engine has no font tables, so the measured
   platform values are used and named as such.

   `top`/`bottom`/`middle` are deliberately ABSENT: each aligns against the
   final line box, which is not known until every other box on the line has
   been placed, so they need a second pass this file does not have. They
   keep the baseline default, which is what this engine did for every value
   before."
  {"super" 0.404 "sub" -0.271})

(defn- inline-inherited
  "The text style context an inline box (or a generated node) hands to its
   own children — the same `inherited` map shape, and the same
   own-declaration-wins-over-inherited resolution, layout-node's element
   branch already builds for block boxes, factored out so a nested
   `<span style=\"color:red\"><b>x</b></span>` resolves identically whether
   it is laid out as a block row (layout-node) or as a fragment inside a
   line box (inline-fragments)."
  [inherited st]
  (let [font-size (parse-int (:font-size st) (:font-size inherited))]
    (assoc inherited
           :color (or (:color st) (:color inherited))
           :font-size font-size
           :line-height (resolve-line-height (:line-height st) font-size
                                            (inherited-line-height inherited font-size)
                                            (boolean (:line-height/explicit? inherited)))
           :line-height/factor (line-height-factor (:line-height st) (:line-height/factor inherited))
           :font-weight (or (:font-weight st) (:font-weight inherited))
           :font-style (or (:font-style st) (:font-style inherited))
           :font-family (or (:font-family st) (:font-family inherited))
           :text-shadow-x (or (:text-shadow-x st) (:text-shadow-x inherited))
           :text-shadow-y (or (:text-shadow-y st) (:text-shadow-y inherited))
           :text-shadow-blur (or (:text-shadow-blur st) (:text-shadow-blur inherited))
           :text-shadow-color (or (:text-shadow-color st) (:text-shadow-color inherited))
           :text-decoration (or (:text-decoration st) (:text-decoration inherited))
           :text-transform (or (:text-transform st) (:text-transform inherited)))))

(defn- inline-fragments
  "Flattens an inline run (a vector of adjacent inline-flow-candidate?
   children) into a flat vector of atomic fragments in document order:

     {:kind :text   :text \"...\" :style <inherited-shaped map>
      :owners [<owner> ...] :opacity <n>}
     {:kind :break  :style ... :owners ... :opacity ...}         ; <br>
     {:kind :atomic :w <px> :h <px> :draw [<ops laid out at 0,0>]
      :owners ... :opacity ...}                                  ; <img>/<input>/...

   An `:atomic` fragment is measured HERE, by laying the element out at the
   origin through the ordinary layout-node path, so a replaced/form-control
   box reports the same size inside a line that it would as a block row —
   there is no second, inline-only size model to drift from it. Laying out
   at `0,0` and translating later is the same technique layout-flex/
   layout-grid/layout-absolute-children already use, and is safe for the
   same reason: layout-node only ever ADDS its `x`/`y` as an offset.

   `:owners` is the stack of enclosing inline ELEMENTS (outermost first)
   the fragment sits inside, each `{:idx <n> :node <element> :st <style>}`
   plus, when any of them is `position: relative`, a `:rel [dx dy]`
   carrying the offsets of every relative box from the outermost down to
   and including that one. A relative inline shifts ITSELF and everything
   inside it, and nothing else: the offsets are accumulated here (where
   the nesting is known) and added at paint time (where the coordinates
   are), so they never reach the line breaker and a relative inline
   therefore does not move the words after it -- real CSS's own
   `relative positioning affects painting only` rule, and the same
   division layout-children-block already makes for a relative BLOCK row.
   Absent entirely when nothing on the line is relative, which is the
   overwhelmingly common case.

   Returns `{:fragments [...] :out-of-flow [...]}`. An out-of-flow
   descendant contributes NO fragment (it is not on the line at all) but
   is not discarded either: it comes back as `{:node <element> :cb-idx
   <owner idx or nil>}`, where `:cb-idx` names the nearest POSITIONED
   inline box around it -- the containing block real CSS anchors it
   against. Only layout-inline-run can turn that index into a box, because
   an inline box has no geometry until its own fragments have been placed.

   The `:idx` is a per-run occurrence counter, NOT the element's
   `:node/id`: two sibling `<b>x</b><b>x</b>` elements in a hand-built
   tree can be entirely equal maps with no id at all, and the fragment→
   owner grouping in layout-inline-run must still keep their boxes apart.

   Nested inline elements recurse with their own resolved style context
   (inline-inherited) and their own multiplicative opacity/visibility
   accumulation — the same accumulator layout-node applies for block
   boxes, so a `visibility: hidden` inline box paints nothing while still
   occupying its space in the line. `display: none` and non-rendered tags
   (`<script>`/`<style>`/...) contribute no fragment at all. An inline
   element's own ::before/::after, implicit list markers, and
   `<details>`/`<summary>` visibility filtering go through the exact same
   with-generated-content/with-implicit-list-markers/with-details-visibility
   pipeline layout-node applies, so those features compose with inline
   flow instead of being bypassed by it."
  [theme inherited opacity content-w items]
  (let [counter (atom 0)
        oof (atom [])
        ;; the accumulated `position: relative` offset in force INSIDE the
        ;; owner stack -- see the docstring. `[0 0]` when nothing above is
        ;; relative, which is why the key is absent in that case.
        rel-of (fn [owners] (:rel (peek owners) [0 0]))
        rel+ (fn [[dx dy] st]
               (if (= "relative" (:position st))
                 (let [[ox oy] (relative-offset st content-w nil)]
                   [(+ dx ox) (+ dy oy)])
                 [dx dy]))]
    (letfn [(walk [items inherited opacity owners acc]
              (reduce
               (fn [acc child]
                 (cond
                   ;; out of flow: nothing on the line, but remembered
                   ;; with the innermost POSITIONED inline box around it,
                   ;; which is its containing block
                   (absolute? theme child)
                   (do (swap! oof conj
                              {:node child
                               :cb-idx (->> owners
                                            (filter #(not= "static" (:position (:st %))))
                                            last
                                            :idx)})
                       acc)

                   (inline-atomic-element? theme child)
                   (let [st (node-style child theme)
                         avail (atomic-intrinsic-width theme content-w opacity inherited child st)
                         {:keys [box draw]} (layout-node theme 0 0 avail opacity inherited child)
                         ;; An atomic inline's own MARGINS take part in the
                         ;; line: a checkbox's UA `margin: 3px 3px 3px 4px`
                         ;; is the gap a reader sees between the box and the
                         ;; label beside it. Measured, the browser puts the
                         ;; checkbox at x=4 y=3 where this engine had 0,1.
                         ml (margin-side st :left)
                         mr (margin-side st :right)
                         mt (margin-side st :top)
                         h (+ (:h box) mt (margin-side st :bottom))
                         ;; An atomic inline's BASELINE is not always its
                         ;; bottom edge. A replaced box (an <img>) does sit
                         ;; on the baseline, but a form control's baseline
                         ;; is the baseline of its own internal text --
                         ;; which is why a browser reports a line holding an
                         ;; <input> as exactly the input's height (21px),
                         ;; where treating the bottom edge as the baseline
                         ;; adds the strut's descent under it and gives 27.
                         baseline-offset
                         (cond
                           (= :img (:tag child))
                           ;; a REPLACED box sits ON the baseline
                           h

                           ;; An OPEN listbox is the one control a browser
                           ;; does NOT baseline-align: Chrome's UA sheet
                           ;; gives `select[multiple]` `vertical-align:
                           ;; text-bottom` (read straight off
                           ;; getComputedStyle, where every other control
                           ;; reports `baseline`), i.e. its BOTTOM edge
                           ;; meets the bottom of the surrounding text's
                           ;; content area -- one descent below the
                           ;; baseline. Measured: a 70px listbox in a 14px
                           ;; monospace paragraph sits at y=0 in a 73px
                           ;; line, which baseline-aligning its first row
                           ;; cannot produce (it puts the box 2.3px down
                           ;; and the line 4px short).
                           (select-multiple? child)
                           (let [{:keys [descent]} (font-metrics theme (:font-size inherited)
                                                                 nil nil nil)]
                             (max 0 (- h descent)))

                           ;; CSS 2.1 10.8.1's own exception: an
                           ;; inline-block whose `overflow` is not
                           ;; `visible`, or which has NO in-flow line box
                           ;; at all, is baselined on its BOTTOM MARGIN
                           ;; EDGE rather than on any text inside it.
                           ;; Both halves are measured in Brave:
                           ;;
                           ;; - a default `<textarea>` (UA `overflow:
                           ;;   auto`, so a scroll container) sits at y=0
                           ;;   in a 40px line box -- 34 of box with the
                           ;;   strut's own 6px of descent under it. Its
                           ;;   own last text row's baseline would have put
                           ;;   the line at 38 and the box 4px up.
                           ;; - an EMPTY `<span style="display:inline-
                           ;;   block;height:10px">` sits at y=4 on a 20px
                           ;;   line, i.e. its bottom edge on the baseline;
                           ;;   giving it the strut's baseline instead put
                           ;;   it at y=0.
                           ;;
                           ;; Restricted to non-control boxes on the
                           ;; second half deliberately: a browser gives an
                           ;; EMPTY `<input>`/`<button>` its internal text
                           ;; baseline all the same (measured, an empty
                           ;; input is still y=0 in a 21px line), because
                           ;; the control's inner editable box is a line
                           ;; box even with nothing in it.
                           (or (and (some? (:overflow st)) (not= "visible" (:overflow st)))
                               (= :textarea (:tag child))
                               (and (not (contains? form-control-tags (:tag child)))
                                    (not-any? #(= :text (:draw/op %)) draw)))
                           h

                           :else
                           ;; ...everything else -- an inline-block, a form
                           ;; control -- aligns by its own last line's
                           ;; baseline, which is its top inset plus that
                           ;; line's own `leading-ascent`. Measured, that
                           ;; is exactly what makes a browser report a line
                           ;; holding a 30px inline-block as 30px and one
                           ;; holding a 21px input as 21px, where treating
                           ;; the bottom edge as the baseline stacks the
                           ;; strut's descent underneath and gives 36 and
                           ;; 27.
                           (let [fs (parse-int (:font-size st) (:font-size inherited))
                                 {:keys [ascent descent]} (font-metrics theme fs (:font-weight st)
                                                                        (:font-style st) (:font-family st))
                                 lh (or (parse-int (:line-height st) nil) (inherited-line-height inherited fs) fs)]
                             (+ mt (or (:padding-top st) (:padding st) 0) (:border-width st)
                                (leading-ascent ascent descent lh))))]
                     (conj acc (cond-> {:kind :atomic
                                        :w (+ (:w box) ml mr) :h h :baseline-offset baseline-offset
                                        :ml ml :mt mt :draw draw
                                        :owners owners :opacity opacity}
                                 ;; an atomic inline is not an owner of
                                 ;; itself, so its OWN relative offset
                                 ;; rides on the fragment
                                 (not= [0 0] (rel+ (rel-of owners) st))
                                 (assoc :rel (rel+ (rel-of owners) st)))))

                   (generated-node? child)
                   ;; An OUTSIDE list marker is a fragment of its own KIND,
                   ;; not text: it is drawn in the run's font on the run's
                   ;; first baseline, but it is not IN the run -- no
                   ;; whitespace collapsing applies to it, it never wraps,
                   ;; and it advances nothing. See outside-marker-node? and,
                   ;; for each of those three, inline-tokens /
                   ;; inline-line-breaker / layout-inline-run.
                   (conj acc {:kind (if (outside-marker-node? child) :marker :text)
                              :text (:generated/text child)
                              :style (inline-inherited inherited (:generated/style child))
                              :owners owners
                              :opacity opacity})

                   (some? (real-text-child child))
                   (conj acc {:kind :text
                              :text (real-text-child child)
                              :style inherited
                              :owners owners
                              :opacity opacity
                              :shift (:vertical-align/shift inherited 0)})

                   (and (map? child) (= :element (:node/type child)))
                   (if (non-rendered-tag? (:tag child))
                     acc
                     (let [st (node-style child theme)]
                       (if (= "none" (:display st))
                         acc
                         (let [opacity (* opacity (:opacity st)
                                          (if (contains? #{"hidden" "collapse"} (:visibility st)) 0 1))
                               ;; the PARENT's font size, read before
                               ;; inline-inherited replaces it: `sub`/`super`
                               ;; raise and lower against the font the box is
                               ;; a sub/superscript OF, not against their own
                               ;; (UA `font-size: smaller`) face. Measured in
                               ;; Brave, `H<sub>2</sub>O X<sup>2</sup>` in a
                               ;; 14px paragraph puts the two 11.67px boxes
                               ;; exactly 9.453px apart -- which is
                               ;; (0.404 + 0.271) x 14, not x 11.67 (that
                               ;; would be 7.88). Charging the child's own
                               ;; size made the line box 1.5px short and put
                               ;; the subscript ~2.5px high.
                               parent-fs (:font-size inherited)
                               inherited (inline-inherited inherited st)
                               ;; a `vertical-align` on an inline box moves
                               ;; that box AND everything inside it
                               inherited (if-let [f (get vertical-align-shift (:vertical-align st))]
                                           (assoc inherited :vertical-align/shift
                                                  (* f parent-fs))
                                           inherited)
                               rel (rel+ (rel-of owners) st)
                               owners (conj owners (cond-> {:idx (swap! counter inc)
                                                            :node child :st st}
                                                     (not= [0 0] rel) (assoc :rel rel)))]
                           (if (= :br (:tag child))
                             (conj acc {:kind :break :style inherited :owners owners :opacity opacity})
                             (walk (with-nested-list-margins
                                     child
                                     (with-generated-content
                                       child
                                       (with-implicit-list-markers
                                         child
                                         (with-details-visibility child (:children child)))))
                                   inherited opacity owners acc))))))

                   :else acc))
               acc
               items))]
      {:fragments (walk items inherited opacity [] [])
       :out-of-flow @oof})))

(defn- inline-tokens
  "Turns inline-fragments' fragments into the word/break token stream the
   line breaker consumes, applying real CSS `white-space: normal`
   whitespace collapsing ACROSS fragment boundaries — the part a
   per-child layout can't express at all.

   Each `{:kind :word}` token carries `:space-before?`, true when at least
   one whitespace character separated it from the previous token in source
   order, whether that whitespace lived at the end of the previous
   fragment, at the start of this one, in a whitespace-ONLY fragment
   between them (the shape `<a>x</a>\\n  <a>y</a>` produces, and the reason
   those whitespace-only text nodes must not become their own boxes), or
   any combination — all of which collapse to exactly ONE space, as real
   CSS does. A leading space at the start of a line is dropped by the line
   breaker, matching real CSS's own line-start whitespace removal.

   `text-transform` is applied HERE, before wrapping, for the same reason
   layout-text applies it before its own word-wrap: it rewrites the
   characters that are actually measured, so wrapping must see the
   transformed text."
  [fragments]
  ;; `pending-style` doubles as the pending-space flag: it is the style of
  ;; the fragment whose OWN trailing whitespace is waiting to become the
  ;; next separator. Carrying the style matters -- a space is part of the
  ;; text run that contains it and is rendered in that run's font, so the
  ;; gap in `a <b>b</b>` is a space in the PARAGRAPH's font, not the
  ;; bold one. Measured against Chrome: it reports 7.00px there, while this
  ;; system's proportional bold space is 3.88px, so charging the incoming
  ;; fragment's font put every following inline box ~3px left of where the
  ;; browser draws it.
  (loop [frs fragments pending-style nil out []]
    (if-let [fr (first frs)]
      (cond
        (= :break (:kind fr))
        (recur (rest frs) nil (conj out fr))

        ;; An outside list marker is not part of the text stream: it passes
        ;; through whole (never split into words, never text-transformed
        ;; along with the line) and, crucially, leaves `pending-style`
        ;; exactly as it found it -- it can neither absorb a pending space
        ;; nor contribute one, because there is no whitespace between it and
        ;; the item's first word for CSS to collapse. See
        ;; outside-marker-node?.
        (= :marker (:kind fr))
        (recur (rest frs) pending-style (conj out fr))

        ;; An atomic inline is one indivisible token. It consumes any
        ;; pending whitespace as its own leading space (`text <img> text`
        ;; keeps a space on each side, exactly as a browser renders it) and
        ;; leaves none behind, so the space after it comes from the next
        ;; text fragment's own leading whitespace.
        (= :atomic (:kind fr))
        (recur (rest frs) nil (conj out (assoc fr :space-before? (some? pending-style)
                                                  :space-style pending-style)))

        :else
        (let [text (apply-text-transform (:text-transform (:style fr)) (str (:text fr)))
              lead? (boolean (re-find #"^\s" text))
              trail? (boolean (re-find #"\s$" text))
              words (remove str/blank? (str/split text #"\s+"))]
          (if (empty? words)
            (recur (rest frs)
                   (or pending-style (when (pos? (count text)) (:style fr)))
                   out)
            (recur (rest frs)
                   (when trail? (:style fr))
                   (into out
                         (map-indexed (fn [i word]
                                        (let [space-style (if (zero? i)
                                                            (or pending-style (when lead? (:style fr)))
                                                            (:style fr))]
                                          {:kind :word
                                           :text word
                                           :space-before? (some? space-style)
                                           :space-style space-style
                                           :style (:style fr)
                                           :owners (:owners fr)
                                           :opacity (:opacity fr)
                                           :shift (:shift fr 0)}))
                                      words))))))
      out)))

(defn- inline-line-breaker
  "Greedily packs inline-tokens into line boxes no wider than `content-w`,
   the same greedy word-packing rule text-lines/text-lines-measured
   already use for a single text child (as many words as fit, never split
   a word, an over-wide lone word gets its own overflowing line) —
   generalized so consecutive words can come from DIFFERENT fragments with
   DIFFERENT styles, which is the entire point of an inline formatting
   context.

   Adjacent words sharing the same style AND the same owner stack are
   merged into ONE piece (one eventual `:text` draw-op) rather than one op
   per word: a paragraph of 40 plain words stays a single draw-op the way
   it does today, so this feature does not multiply a real page's op count
   by its word count. A style or owner change starts a new piece, which is
   exactly the granularity a host needs to paint two different colors/
   weights on one line.

   Widths come from the host's real `:measure-text` when the theme
   supplies one, else this file's `(long (* 0.6 font-size))` per-character
   approximation — identical to layout-text's own `line-w`, so wrap
   decisions inside an inline run agree with wrap decisions for a plain
   text child at the same font size."
  [theme content-w tokens]
  (let [measure-text (:measure-text theme)
        w-of (fn [text st]
               (if measure-text
                 (measure-text text (:font-size st) (:font-weight st) (:font-style st) (:font-family st))
                 (* (count text) (long (* 0.6 (:font-size st))))))
        ;; An outside list marker is a piece on the line but not CONTENT on
        ;; it: every question this loop asks about "is there anything on the
        ;; line already" -- does a space separate me from it, must I wrap
        ;; before adding myself, may I merge into it -- has to answer no for
        ;; it alone, or the item's first word gets a leading space it never
        ;; had, wraps to a second line behind a marker sitting on an empty
        ;; first one, or is concatenated into the marker's own draw-op and
        ;; dragged outside the content edge with it.
        content? (fn [pieces] (boolean (some #(not= :marker (:kind %)) pieces)))
        flush (fn [lines pieces w style] (conj lines {:pieces pieces :w w :style style}))]
    (loop [ts tokens x 0 pieces [] lines []]
      (if-let [t (first ts)]
        (cond
          (= :break (:kind t))
          ;; The <br> itself keeps its owners on the line it ends, so
          ;; layout-inline-run can give it a real (zero-width) box. A
          ;; browser reports one there, and without it every <br> was a
          ;; missing element on the geometry axis.
          (recur (rest ts) 0 []
                 (conj lines {:pieces pieces :w x :style (:style t)
                              :break-owners (:owners t)}))

          ;; An OUTSIDE list marker gets a piece whose x is its own NEGATIVE
          ;; width and which does not move the pen: it is painted in the
          ;; space immediately before the line's content edge, and the first
          ;; real word still starts at x=0. Because the pen does not move,
          ;; the marker is also absent from this line's `:w` -- so it takes
          ;; no part in wrapping, in `text-align`, or in the run's
          ;; max-content width.
          ;;
          ;; `(- w)` is the item's content edge minus the marker, not the
          ;; pen minus the marker, and it is correct only because such a
          ;; marker is always the FIRST child of its item (with-implicit-
          ;; list-markers writes it as the item's ::before and nothing else
          ;; ever sets :generated/marker), so the pen is at 0 when it
          ;; arrives.
          (= :marker (:kind t))
          (let [w (w-of (:text t) (:style t))]
            (recur (rest ts) x (conj pieces (assoc t :x (- w) :w w)) lines))

          ;; An atomic inline never merges with a neighbouring piece and is
          ;; never split: it wraps to the next line whole, or overflows
          ;; alone, exactly like an over-wide single word.
          (= :atomic (:kind t))
          (let [sep (if (and (content? pieces) (:space-before? t))
                      (w-of " " (or (:space-style t)
                                    {:font-size (or (:font-size (:style (peek pieces))) 14)}))
                      0)
                piece (fn [x] (assoc (select-keys t [:owners :opacity :draw :h :ml :mt :baseline-offset])
                                     :kind :atomic :x x :w (:w t)))]
            (if (and (content? pieces) (> (+ x sep (:w t)) content-w))
              (recur (rest ts) (:w t) [(piece 0)] (flush lines pieces x nil))
              (recur (rest ts) (+ x sep (:w t)) (conj pieces (piece (+ x sep))) lines)))

          :else
          (let [st (:style t)
                word (:text t)
                ww (w-of word st)
                sep (if (and (content? pieces) (:space-before? t))
                      (w-of " " (or (:space-style t) st))
                      0)]
            (if (and (content? pieces) (> (+ x sep ww) content-w))
              (recur (rest ts) ww
                     [{:text word :style st :owners (:owners t) :opacity (:opacity t) :x 0 :w ww}]
                     (flush lines pieces x st))
              (let [last-piece (peek pieces)
                    merge? (and last-piece
                                (not= :marker (:kind last-piece))
                                (= (:style last-piece) st)
                                (= (:owners last-piece) (:owners t))
                                (= (:opacity last-piece) (:opacity t))
                                (= (:shift last-piece 0) (:shift t 0)))
                    x' (+ x sep ww)]
                (recur (rest ts) x'
                       (if merge?
                         (conj (pop pieces)
                               (assoc last-piece
                                      :text (str (:text last-piece) (if (pos? sep) " " "") word)
                                      :w (- x' (:x last-piece))))
                         (conj pieces {:text word :style st :owners (:owners t)
                                       :opacity (:opacity t) :x (+ x sep) :w ww
                                       :shift (:shift t 0)}))
                       lines))))
          )
        (cond
          (and (empty? pieces) (empty? lines)) []
          ;; A trailing <br> at the very end of a block does not leave an
          ;; empty line box behind it: measured, `<p>line<br></p>` is 20px
          ;; tall in the browser where this engine produced a second, empty
          ;; 20px line.
          (and (empty? pieces) (seq lines)) lines
          :else (flush lines pieces x nil))))))

(defn- font-metrics
  "The font's ascent and descent in pixels, from the host's optional
   `:font-metrics` theme hook -- the vertical counterpart of
   `:measure-text`, and the same bargain: this is a pure engine with no
   glyph tables, so a host that HAS real metrics can supply them and a host
   that does not gets a documented approximation.

   The default keeps this file's long-standing behaviour exactly: ascent =
   the font size (which is where dom-gpu's hosts paint the baseline, at
   `y + font-size`) and descent = 0.2em, so the content area stays the 1.2em
   this engine has always used.

   Real metrics matter because a line box is built from them: measured in
   Chrome, 14px monospace is ascent 12 / descent 3 (content 15, not 16.8),
   its BOLD face is 14 / 4 (content 18), and 24px is 21 / 5 (content 26).
   Those are the numbers that decide how tall a line is and where each
   inline box sits inside it."
  [theme font-size weight style family]
  (let [fs (or font-size (:font-size theme) 14)]
    (or (when-let [f (:font-metrics theme)] (f fs weight style family))
        {:ascent fs :descent (long (* 0.2 fs))})))

(defn- inline-line-metrics
  "One line box's own height and baseline offset, built the way real CSS
   builds one: from the STRUT (the block's own font at the block's own
   line-height, present on every line whether or not any text uses that
   font) plus every inline participant, all aligned on ONE baseline.

   Each participant reaches `leading-ascent` above the baseline and
   `line-height - leading-ascent` below it (see that function for the
   floor, and for why the descent side is derived rather than computed).
   The line box is the union: `above` is the largest ascent, `below` the
   largest descent, the height is their sum and the baseline sits `above`
   below the line's top edge. That union -- not a max over `line-height`s
   -- is why a 24px run inside a `line-height: 20px` block reports a 24px
   line in a browser, and why a 10px run inside the same block reports 21
   rather than 20 (its half-leading pushes 7px of descent under a strut
   that only asked for 6).

   Every piece then sits at `baseline - its own ascent`, which is what
   makes mixed font sizes share one baseline instead of one top edge, and
   what kotoba-lang/dom-gpu's WebGL/WebGPU hosts already assume: both
   paint a `:text` op's baseline at `y + font-size` (see webgl.cljs' own
   `:text` case).

   An ATOMIC inline (an `<img>`/`<input>`/`<button>`/inline-block) brings
   its own baseline (`:baseline-offset`, measured from its top margin
   edge by inline-fragments) rather than a font's: `vertical-align:
   baseline` puts THAT on the line's baseline, so the box contributes
   `:baseline-offset` above and `h - :baseline-offset` below. A 40px-tall
   button on a 14px line therefore pushes the baseline down and grows the
   line box to fit rather than being clipped by it."
  [line inherited theme]
  (let [pieces (:pieces line)
        fallback-fs (or (:font-size (:style line)) (:font-size inherited) (:font-size theme))
        fallback-lh (or (:line-height (:style line)) (:line-height inherited) (:line-height theme))
        spans (for [p pieces
                    :when (not= :atomic (:kind p))
                    :let [st (:style p)
                          fs (or (:font-size st) fallback-fs)
                          lh (or (:line-height st) fallback-lh fs)
                          {:keys [ascent descent]} (font-metrics theme fs (:font-weight st)
                                                                 (:font-style st) (:font-family st))
                          a (leading-ascent ascent descent lh)
                          ;; a raised/lowered box carries its whole span
                          ;; with it, growing the line in that direction
                          ;; (positive = raised, see vertical-align-shift)
                          shift (:shift p 0)]]
                [(+ a shift) (- (- lh a) shift)])
        atomic-hs (keep #(when (= :atomic (:kind %)) (or (:baseline-offset %) (:h %))) pieces)
        atomic-below (keep #(when (= :atomic (:kind %))
                              (- (:h %) (or (:baseline-offset %) (:h %))))
                           pieces)
        strut (let [{:keys [ascent descent]} (font-metrics theme fallback-fs nil nil nil)
                    lh (or fallback-lh fallback-fs)
                    a (leading-ascent ascent descent lh)]
                [a (- lh a)])
        above (apply max (concat (map first spans) atomic-hs [(first strut)]))
        below (apply max (concat (map second spans) atomic-below [(second strut)]))
        ascents (concat (keep #(:font-size (:style %)) pieces) atomic-hs)
        line-heights (keep #(:line-height (:style %)) pieces)
        max-ascent (if (seq ascents) (apply max ascents) fallback-fs)
        max-lh (if (seq line-heights) (apply max line-heights) fallback-lh)
        ;; The line box's HEIGHT comes from the line-heights on the line,
        ;; not from the font sizes: real CSS lets text OVERFLOW a line box
        ;; that its declared line-height made too small, rather than growing
        ;; the box. Measured: a 24px run inside a declared `line-height:
        ;; 20px` container reports a 20px box in the browser (with the text
        ;; spilling out of it), where this engine reported 24. An ATOMIC
        ;; inline is different -- a replaced box cannot overflow its line,
        ;; and a browser does grow the line to fit it.
        ]
    ;; With a host's real metrics, the line box is the UNION of the inline
    ;; boxes positioned by their own ascent/descent -- the real rule.
    ;; Without them this engine keeps its long-standing approximation
    ;; (line box = the tallest line-height, baseline one font-size down),
    ;; byte for byte: the same bargain `:measure-text` makes, since
    ;; inventing metrics would be worse than admitting there are none.
    ;; `:h` is still rounded UP so successive lines advance by whole pixels
    ;; (a fractional advance compounds down a long paragraph), but the
    ;; BASELINE is exact: rounding it up was the old model's way of paying
    ;; for the missing floor in `leading-ascent`, and doing both now puts
    ;; every inline box a pixel low again.
    (if (:font-metrics theme)
      {:h (long (Math/ceil (+ above below)))
       :baseline above}
      {:h (max max-lh (if (seq atomic-hs) (apply max atomic-hs) 0))
       :baseline max-ascent})))

(defn- inline-owner-ops
  "Background + `:node` draw-ops for the inline ELEMENTS a laid-out run
   passed through, derived from where their own text fragments actually
   landed.

   A `:node` op is what every downstream consumer uses to find an element
   on screen — kotoba-lang/browser's `session/node-at` click routing,
   dom-gpu's retained-tree hit testing, the accessibility projection — so
   an inline `<a>` that emitted only `:text` ops would be invisible to
   clicks. Each element gets ONE node op spanning the union of its
   fragments, plus one background rect PER LINE (a two-line link's
   background follows both line boxes rather than filling the rectangle
   around them). The single union node op for a wrapped inline box is an
   honest, documented approximation of real CSS's per-fragment box list:
   it over-covers the ragged edge of a multi-line inline box for hit
   testing, and is exactly right for the single-line case that is by far
   the common one.

   Padding/margin/border on an inline box are deliberately NOT applied —
   real CSS applies horizontal (but not vertical) padding/border to inline
   boxes, shifting the following text; this engine's box model resolves
   those only for block boxes (content-inset), so an inline box here paints
   only its background. Documented scope-cut, not an oversight."
  [theme rects]
  (let [ordered (sort-by key rects)]
    (vec
     (concat
      (mapcat (fn [[_ {:keys [node st opacity fragments]}]]
                (when-let [bg (default-bg (:tag node) st theme)]
                  (mapv (fn [r]
                          {:draw/op :rect :x (:x r) :y (:y r) :w (:w r) :h (:h r)
                           :color bg :tag (:tag node) :opacity opacity})
                        fragments)))
              ordered)
      (map (fn [[_ {:keys [node st opacity fragments]}]]
             (let [x0 (apply min (map :x fragments))
                   y0 (apply min (map :y fragments))
                   x1 (apply max (map #(+ (:x %) (:w %)) fragments))
                   y1 (apply max (map #(+ (:y %) (:h %)) fragments))]
               (merge {:draw/op :node :id (:node/id node) :tag (:tag node)
                       :x x0 :y y0 :w (- x1 x0) :h (- y1 y0)
                       :class (attr node :class) :listeners (listeners node)
                       :opacity opacity}
                      (style-passthrough st))))
           ordered)))))

(defn- layout-inline-run
  "Lays out one INLINE FORMATTING CONTEXT: a maximal run of adjacent
   inline-level children (text nodes, generated ::before/::after content,
   and inline elements) that share line boxes instead of each getting
   their own block row.

   This is the general inline flow this file spent its whole life without
   (the ns docstring's long-standing `<li>text<b>bold</b></li>` example) —
   text and an adjacent `<b>`/`<a>`/`<span>` now flow onto the SAME line,
   wrapping together at `content-w`, each fragment keeping its own
   color/font-size/weight/style/decoration, all sharing one baseline.

   Geometry deliberately mirrors layout-text's own conventions exactly so
   a run is interchangeable with the single text child it replaces: the
   theme's `:padding` insets the run on all four sides, lines advance by
   their own line-height, and `text-align` offsets each line individually
   by that line's own measured width within the same content width the
   line breaker wrapped against. Returns the `{:draw :h}` shape
   layout-children-block already advances on.

   Scope-cuts, all deliberate and each documented at the function that
   owns it: replaced/form-control elements are not inline-level here
   (inline-level-tags), an inline box containing a block box falls back to
   block rows (inline-flow-candidate?), non-normal `white-space` keeps the
   old path (inline-flow-candidate?), inline padding/border/margin are not
   applied and a wrapped inline box gets one union node op
   (inline-owner-ops), and `vertical-align` other than the baseline
   default is not modeled at all (inline-line-metrics)."
  [theme content-x content-y content-w opacity inherited items]
  (let [padding (:padding theme)
        inner-w (max 0 (- content-w (* 2 padding)))
        {fragments :fragments oof :out-of-flow} (inline-fragments theme inherited opacity inner-w items)
        lines (inline-line-breaker theme inner-w (inline-tokens fragments))
        text-align (:text-align inherited)
        ;; An out-of-flow descendant of one of this run's inline boxes,
        ;; ready for layout-absolute-children -- see inline-fragments for
        ;; how it got here and layout-children-block's own out-of-flow
        ;; branch for the block-level counterpart.
        ;;
        ;; `:cb` is real CSS's inline containing block: `<p>text <span
        ;; style="position: relative">anchor<span style="position:
        ;; absolute; left: 0; top: 20px">pop</span></span> tail</p>` puts
        ;; the inner span at (35,22) in Brave -- 35 is where the RELATIVE
        ;; span starts in the line, not the paragraph's content edge,
        ;; which is where this engine put it (x=0). CSS 2.1 10.1.4.1
        ;; builds it from the FIRST box's top-left and the LAST box's
        ;; bottom-right, which for the single-fragment case (by far the
        ;; common one, and the only one measured) is exactly that box.
        ;;
        ;; `:x`/`:y` -- the static position for an axis with no offset --
        ;; are the honest limit of this half: the run's own origin, not
        ;; the point IN THE LINE the box was written at (measured in
        ;; Brave, `text <span style="position:absolute">pop</span> tail`
        ;; puts it at x=31.38, right after `text `). Resolving that needs
        ;; the box to travel through the tokenizer and line breaker as a
        ;; zero-width marker so the line can report where it landed; until
        ;; it does, an inline out-of-flow box with no offsets lands at the
        ;; start of its containing block instead of its own place in the
        ;; line.
        finish-oof
        (fn [rects]
          (mapv (fn [{:keys [node cb-idx]}]
                  (let [frs (:fragments (get rects cb-idx))
                        cb (when (seq frs)
                             (let [f (first frs) l (peek frs)]
                               {:x (:x f) :y (:y f)
                                :w (- (+ (:x l) (:w l)) (:x f))
                                :h (- (+ (:y l) (:h l)) (:y f))}))]
                    (cond-> {:node node
                             :x (or (:x cb) content-x)
                             :y (or (:y cb) content-y)}
                      cb (assoc :cb cb))))
                oof))]
    (if (empty? lines)
      {:draw [] :h 0 :out-of-flow (finish-oof {})}
      (loop [ls lines
             y (+ content-y padding)
             text-draws []
             rects {}]
        (if-let [line (first ls)]
          (let [{line-h :h baseline-off :baseline} (inline-line-metrics line inherited theme)
                align-offset (case text-align
                               "center" (/ (max 0 (- inner-w (:w line))) 2)
                               "right" (max 0 (- inner-w (:w line)))
                               0)
                base-x (+ content-x padding align-offset)
                baseline (+ y baseline-off)
                ;; One inline ELEMENT's own box on this line, for every
                ;; owner the piece passed through. An inline box's height
                ;; is ITS OWN font's CONTENT AREA (ascent + descent) and
                ;; its top is one of its own ascents above the shared
                ;; baseline -- never the line box, and never the box of
                ;; whatever it happens to contain. Measured in Brave: a
                ;; 14px `<b>` on a 20px line is (y=1, h=18) and the `<span>`
                ;; around it is (y=2, h=15), each from its own face; a
                ;; `<label>` wrapping a 21px `<input>` is still (y=3, h=15),
                ;; NOT the input's box.
                ;;
                ;; Shared by the atomic and the text branch below because
                ;; that last measurement is exactly where they used to
                ;; disagree: the atomic branch handed its owners the
                ;; ATOMIC's box, so a `<label>` around a control reported
                ;; the control's height and sat 3px too high.
                ;; The FACE each owner is measured in is resolved down the
                ;; owner chain, not taken from the innermost piece: a
                ;; `<code>` inside an `<em>` is drawn in the italic face it
                ;; inherited, so its box is 18px tall in Brave where its own
                ;; (empty) declarations alone say 15. Reading only
                ;; `(:font-* (:st owner))` made every inheriting inline box
                ;; report the upright metrics -- `code h` 15 against 18 on
                ;; :inline/deep-nesting-four-levels.
                owner-fragments
                (fn [rects owners shift px0 w opacity]
                  (first
                   (reduce
                    (fn [[rects face] owner]
                      (let [ost (:st owner)
                            face {:fs (parse-int (:font-size ost) (:fs face))
                                  :weight (or (:font-weight ost) (:weight face))
                                  :style (or (:font-style ost) (:style face))
                                  :family (or (:font-family ost) (:family face))}
                            om (font-metrics theme (:fs face) (:weight face)
                                             (:style face) (:family face))
                            [odx ody] (:rel owner [0 0])]
                        [(update rects (:idx owner)
                                 (fn [entry]
                                   (-> (or entry {:node (:node owner) :st (:st owner)
                                                  :opacity opacity :fragments []})
                                       ;; the box follows its own
                                       ;; vertical-align shift, exactly
                                       ;; like the text inside it
                                       (update :fragments conj
                                               {:x (+ px0 odx)
                                                :y (+ (- baseline (:ascent om) shift) ody)
                                                :w w :h (+ (:ascent om) (:descent om))}))))
                         face]))
                    [rects {:fs (:font-size inherited) :weight (:font-weight inherited)
                            :style (:font-style inherited) :family (:font-family inherited)}]
                    owners)))
                [line-draws rects]
                (reduce
                 (fn [[draws rects] piece]
                   (cond
                     ;; An OUTSIDE list marker: painted on this line's
                     ;; baseline in the run's own font, at the negative x
                     ;; inline-line-breaker gave it, and that is ALL it does
                     ;; here. It contributes to no owner's box, deliberately:
                     ;; real CSS makes `::marker` a box of its own, and the
                     ;; oracle agrees -- Brave reports the `<li>` of
                     ;; `<ul><li><a>x</a></li></ul>` starting at its content
                     ;; edge, with the marker outside it, so folding the
                     ;; marker into the item's own fragments would report an
                     ;; `<li>`/`<a>` box wider and further left than the one
                     ;; a browser reports.
                     (= :marker (:kind piece))
                     (let [st (:style piece)]
                       [(conj draws
                              (cond-> {:draw/op :text
                                       :text (:text piece)
                                       :x (+ base-x (:x piece))
                                       :y (- baseline (:font-size st))
                                       :font-size (:font-size st)
                                       :color (:color st)
                                       :opacity (:opacity piece)}
                                (:font-weight st) (assoc :font-weight (:font-weight st))
                                (:font-style st) (assoc :font-style (:font-style st))
                                (:font-family st) (assoc :font-family (:font-family st))))
                        rects])

                     (= :atomic (:kind piece))
                     ;; Atomic inline: the element was already laid out at
                     ;; the origin by inline-fragments, so placing it is a
                     ;; translate -- its bottom edge onto the baseline, the
                     ;; real CSS `vertical-align: baseline` default.
                     ;; `position: relative` on this box (`:rel`) or on any
                     ;; inline box around it (the owner's own `:rel`) is a
                     ;; PAINT-time shift and nothing else -- the line
                     ;; breaker never saw it, so the words after this one
                     ;; do not move. Each owner's box follows the offsets
                     ;; in force at ITS OWN depth, which is why the
                     ;; accumulated value is read per owner rather than
                     ;; taken from the piece.
                     (let [[rdx rdy] (:rel piece [0 0])
                           px0 (+ base-x (:x piece) (:ml piece 0))
                           py0 (+ (- baseline (or (:baseline-offset piece) (:h piece)))
                                  (:mt piece 0))
                           px (+ px0 rdx)
                           py (+ py0 rdy)
                           rects (owner-fragments rects (:owners piece) 0
                                                  px0 (:w piece) (:opacity piece))]
                       [(into draws (translate-ops px py (:draw piece))) rects])

                     :else
                     (let [st (:style piece)
                           ;; the `position: relative` shift in force
                           ;; INSIDE the innermost inline box this text sits
                           ;; in -- see the atomic branch above
                           [rdx rdy] (:rel (peek (:owners piece)) [0 0])
                           px0 (+ base-x (:x piece))
                           py0 (- baseline (:font-size st) (:shift piece 0))
                           px (+ px0 rdx)
                           py (+ py0 rdy)
                           base (cond-> {:text (:text piece) :font-size (:font-size st) :opacity (:opacity piece)}
                                  (:font-weight st) (assoc :font-weight (:font-weight st))
                                  (:font-style st) (assoc :font-style (:font-style st))
                                  (:font-family st) (assoc :font-family (:font-family st)))
                           shadow-op (when (and (:text-shadow-color st) (not= "none" (:text-shadow-color st)))
                                       (assoc base :draw/op :text
                                              :x (+ px (or (:text-shadow-x st) 0))
                                              :y (+ py (or (:text-shadow-y st) 0))
                                              :color (:text-shadow-color st)))
                           main-op (cond-> (assoc base :draw/op :text :x px :y py :color (:color st))
                                     (:text-decoration st) (assoc :text-decoration (:text-decoration st)))
                           rects (owner-fragments rects (:owners piece) (:shift piece 0)
                                                  px0 (:w piece) (:opacity piece))]
                       [(cond-> draws
                          shadow-op (conj shadow-op)
                          true (conj main-op))
                        rects])))
                 [[] rects]
                 (:pieces line))]
            (recur (rest ls) (+ y line-h) (into text-draws line-draws)
                   ;; the <br>'s own zero-width box, at the end of the line
                   ;; it terminates -- the same content-area box on the
                   ;; same baseline every other inline element on this line
                   ;; gets, through the same function, rather than a
                   ;; 1.2em approximation centred in the line box.
                   ;; Measured in Brave, `<p>a<br>b</p>` reports the <br>
                   ;; at (7, 2, 0, 15) where the 1.2em rule gave h=16.
                   (owner-fragments rects (:break-owners line) 0
                                    (+ base-x (:w line)) 0 opacity)))
          {:draw (into (inline-owner-ops theme rects) text-draws)
           :h (+ (- y content-y) padding)
           :out-of-flow (finish-oof rects)})))))

;; ---- block-in-inline ----

(defn- split-block-in-inline
  "Real CSS's `block-in-inline` box-tree fixup: an inline box containing a
   BLOCK box is SPLIT around it -- the inline content before the block
   joins the preceding line, the block gets its own row, and the content
   after it starts a new line, all still styled by the same inline element.

   `<p>text <span>a <div>b</div> c</span> end</p>` renders as three lines in
   every browser (`text a` / `b` / `c end`); this engine refused to flow the
   whole `<span>` at all when it saw the block child (inline-flow-candidate?
   returns false), so the paragraph fell apart into five stacked rows.

   The split is expressed as data: the offending element is replaced by a
   clone holding each run of inline children, with the block children
   hoisted between them. Downstream, each clone is an ordinary inline
   element and each hoisted block an ordinary block row -- no new concept
   is needed anywhere else.

   Bounded v1: the block child must be a DIRECT child of the inline
   element. A block nested two inline levels deep (`<span><em><div>`) is
   left alone and keeps the pre-existing block-row fallback.

   An OUT-OF-FLOW child is not a block child for this purpose and does not
   split anything: it leaves the inline box's content entirely rather than
   interrupting it, and splitting around it would sever it from the very
   ancestor it is positioned against (see inline-fragments, which carries
   it out of the run with the owner stack it needs)."
  [theme children]
  (let [block-child? (fn [c] (and (map? c)
                                  (= :element (:node/type c))
                                  (not (absolute? theme c))
                                  (not (inline-flow-candidate? theme c))))]
    (vec (mapcat
          (fn [child]
            (if (and (inline-level-element? theme child)
                     (some block-child? (:children child)))
              (->> (:children child)
                   (partition-by block-child?)
                   (mapcat (fn [group]
                             (if (block-child? (first group))
                               group
                               [(assoc child :children (vec group))]))))
              [child]))
          children))))


(defn- inline-runs
  "Groups `children` into layout entries: each maximal run of adjacent
   inline-flow-candidate? children becomes one `{:inline/run [...]}` entry
   (laid out by layout-inline-run), everything else passes through as the
   plain child it already was.

   TWO OR MORE for a run that includes bare TEXT, one is enough for an
   ELEMENT. The text half of that threshold is the original and is
   deliberate: the single most common shape in this whole engine is a block
   whose only child is one text node, which stays on layout-text's exact
   pre-existing path, byte for byte, and routing it through the inline path
   would change every such geometry (and every test asserting it) for no
   benefit — a lone text child occupies its own line either way.

   A lone ELEMENT is not the same case, because it has a BOX of its own
   that a full-width block row gets wrong on all four numbers. Measured in
   Brave, `<td><a href=\"/x\">link</a></td>` reports the `<a>` at
   (0, 2, 28, 15) -- its own font's content area, sitting on the cell's
   baseline -- where the block-row fallback made it (0, 0, 800, 20); the
   same for `<dt><code>opt</code></dt>`, and a lone atomic inline
   (`<p><input></p>`) shrink-wraps to 153px in the browser where the block
   row filled the container.

   `inherited` is the container's own inherited text style, and it
   disqualifies the inline path exactly the way a child's own style does
   (see inline-flow-candidate?): the inline tokenizer collapses whitespace
   unconditionally and cannot truncate a line, so a non-normal
   `white-space` or a `text-overflow` in force from an ANCESTOR keeps
   everything here on the pre-existing path. Reading only the child's own
   declaration missed that, because both properties INHERIT --
   `<pre><span>x\\ny</span></pre>` collapsed to one line."
  ([theme inherited children] (inline-runs theme inherited children 2))
  ([theme inherited children min-items]
  (->> (split-block-in-inline theme children)
       ;; A whitespace-only text child that is not part of an inline run is
       ;; dropped: between two blocks it would otherwise become a stray row
       ;; of its own. The parser deliberately KEEPS such runs (they are the
       ;; space between inline elements, `<a>one</a> <a>two</a>`), and this
       ;; is the box-tree-aware half of that decision -- the same division
       ;; of labour as whitespace collapsing itself.
       ((fn [cs]
          (let [inline-neighbour? (fn [i]
                                    (some #(when-let [c (nth cs % nil)]
                                             (and (not (nil? c))
                                                  (or (some? (real-text-child c))
                                                      (and (map? c) (inline-flow-candidate? theme c)))))
                                          [(dec i) (inc i)]))]
            (vec (keep-indexed
                  (fn [i c]
                    (when-not (and (some? (real-text-child c))
                                   (str/blank? (real-text-child c))
                                   (not (inline-neighbour? i)))
                      c))
                  cs)))))
       (remove (fn [child]
                 ;; Children that render NOTHING are dropped before grouping
                 ;; rather than passed through as zero-height rows. Real CSS
                 ;; treats `display: none` and a `<script>`/`<style>` as
                 ;; absent from the box tree entirely, and keeping them here
                 ;; broke inline flow in a way a real page hits constantly:
                 ;; `keep <span style="display:none">gone</span> this` split
                 ;; into TWO one-child runs (neither reaching the two-child
                 ;; threshold), so `keep` and `this` stacked on separate
                 ;; lines -- found by conformance/run.cljs differential
                 ;; testing against a real Blink browser, which puts them on
                 ;; one line. Dropping them also removes the stray inter-row
                 ;; `gap` each one used to contribute to block flow.
                 (and (map? child)
                      (= :element (:node/type child))
                      (or (non-rendered-tag? (:tag child))
                          (= "none" (:display (node-style child theme)))))))
       ;; Floats and OUT-OF-FLOW boxes are TRANSPARENT to this grouping:
       ;; neither joins a line box, and neither SPLITS one. Leaving them in
       ;; the sequence would do the latter -- `text <span
       ;; style="float:left">F</span> more` would partition into two
       ;; one-child runs and stack `text` and `more` on separate lines,
       ;; where every browser keeps them on one. So each is lifted out, the
       ;; rest is grouped as before, and the lifted child is put back in
       ;; FRONT of the entry its following sibling landed in.
       ;;
       ;; An absolutely positioned child used to be removed from `children`
       ;; entirely (by a `partition-flow` in layout-block) before this
       ;; function ever saw it, which is why it could not split a run then
       ;; either. It travels WITH the flow now because its STATIC POSITION
       ;; -- where it would have been had it stayed in flow, which is what
       ;; real CSS uses for every axis with no offset -- is only knowable
       ;; from the running Y this grouping feeds (see
       ;; layout-children-block's own out-of-flow branch).
       ;;
       ;; Keeping them in the entry sequence at all (rather than hoisting
       ;; them all to the container's top, which is what this file used to
       ;; do) is what lets layout-children-block place a float at the flow
       ;; position it was WRITTEN at. A float anchored inside a run has no
       ;; position of its own within the line, so it is emitted before the
       ;; whole run -- which is the same y the line box gets.
       ((fn [cs]
          (let [;; `anchors` maps an index into the lifted-free `flow`
                ;; vector to the floats/out-of-flow boxes written
                ;; immediately before it; `pending` is left holding the ones
                ;; written after the last in-flow child, which have nothing
                ;; to anchor to and are emitted at the end.
                lifted? (fn [c] (or (absolute? theme c) (float-child? theme c)))
                ;; the container's own inherited text style either admits an
                ;; inline formatting context or it does not -- see the
                ;; docstring, and inline-flow-candidate? for the same two
                ;; properties read off a child's own declarations
                inline-context? (and (contains? #{nil "normal"} (:white-space inherited))
                                     (nil? (:text-overflow inherited)))
                {:keys [flow anchors] tail :pending}
                (reduce (fn [{:keys [flow anchors pending]} c]
                          (if (lifted? c)
                            {:flow flow :anchors anchors :pending (conj pending c)}
                            {:flow (conj flow c)
                             :anchors (if (seq pending)
                                        (assoc anchors (count flow) pending)
                                        anchors)
                             :pending []}))
                        {:flow [] :anchors {} :pending []}
                        cs)]
            (loop [groups (partition-by #(inline-flow-candidate? theme %) flow)
                   base 0
                   out []]
              (if-let [group (seq (first groups))]
                (let [group (vec group)
                      n (count group)
                      floats-at (fn [k] (get anchors k []))
                      run? (and (inline-flow-candidate? theme (first group))
                                (or (>= n min-items)
                                    ;; a LONE inline ELEMENT -- see the
                                    ;; docstring for the measurement, and
                                    ;; for why a lone TEXT child is not the
                                    ;; same case
                                    (and inline-context?
                                         (map? (first group))
                                         (= :element (:node/type (first group)))
                                         (inline-fragment-bearing? theme (first group)))
                                    ;; a LONE OUTSIDE LIST MARKER, i.e. an
                                    ;; `<li>` that is empty or whose content
                                    ;; is a block. Only the inline path can
                                    ;; place such a marker outside the
                                    ;; content edge -- the block-row path
                                    ;; (layout-node's generated-node? branch)
                                    ;; paints generated text at x=0 like any
                                    ;; other row, i.e. exactly where the
                                    ;; content it is supposed to sit BESIDE
                                    ;; starts. The row is the same height
                                    ;; either way.
                                    (and inline-context?
                                         (= 1 n)
                                         (outside-marker-node? (first group)))))
                      emitted (if run?
                                (conj (vec (mapcat #(floats-at %) (range base (+ base n))))
                                      {:inline/run group})
                                (vec (mapcat (fn [k c] (conj (vec (floats-at k)) c))
                                             (range base (+ base n))
                                             group)))]
                  (recur (rest groups) (+ base n) (into out emitted)))
                (into out tail))))))
       vec)))

;; ---- the float band ------------------------------------------------------
;;
;; Three tiny pure functions over ONE data shape, and every float rule in
;; this file is expressed in terms of them. A placed float is
;;
;;   {:x :y :w :h :right?}
;;
;; -- its MARGIN box, in the same absolute coordinates as the container's
;; content box, because every one of CSS's float rules is stated about the
;; margin box and nothing here ever wants the border box again. A float's
;; own margins used to be dropped entirely (measured in Brave: a
;; `float:left; margin:10px` box sits at (10,10) and this engine put it at
;; (0,0), and its NEIGHBOUR started 10px too early because the band was a
;; border box wide).
;;
;; A float narrows the line boxes of every box in its formatting context,
;; not just its own parent's: `<div><img float><h3>Title</h3><p>body</p>
;; </div>` puts the `<h3>`'s and the `<p>`'s LINES beside the float in
;; Brave while both BORDER BOXES stay full width. That used to be a
;; documented scope cut here ("layout-node does not carry a float context
;; down into a child"); layout-node now does, as an optional trailing
;; `intruding` argument (see layout-children-block's `---- floats ----`
;; section), and the cut is gone. It was not cosmetic: on
;; `:page/media-object` the harness's line axis reported ONE line for a
;; three-line page, because every line the engine put at x=0 was inside the
;; float's own box and was therefore attributed to the float rather than to
;; the paragraph it belongs to.
;;
;; What is still deliberately NOT here: a float never narrows a box that
;; establishes its own formatting context (correct -- CSS says the same),
;; and it does not SHRINK such a box to fit beside itself either, which CSS
;; does say. An `overflow: hidden` sibling of a float keeps its full width
;; and overlaps it here, where a browser would narrow it. That needs a
;; width-resolution pass that consults the band, not just a line-breaking
;; one, and is a separate measurement.

(defn- float-band
  "The `[left right]` content edges available on the scanline at `y`.

   A scanline rather than the line box's full vertical extent: a line box's
   height is not known until layout-inline-run has built it, and that
   function takes ONE content width for the whole run. Querying at the
   run's own top edge is the honest version of what the single width can
   express -- it is also why a paragraph that STARTS beside a float keeps
   the narrow width for the lines that continue below it (see the scope
   note above)."
  [floats content-x content-w y]
  (reduce (fn [[l r] f]
            (if (and (<= (:y f) y) (< y (+ (:y f) (:h f))))
              (if (:right? f)
                [l (min r (:x f))]
                [(max l (+ (:x f) (:w f))) r])
              [l r]))
          [content-x (+ content-x content-w)]
          floats))

(defn- float-clearance-y
  "The Y a box with this `clear` value may not start above: the lowest
   bottom margin edge among the floats on the cleared side(s), or nil when
   `clear` does not ask for anything.

   `:right?` is the float's own side, so `clear: left` looks at the floats
   whose `:right?` is false. Clearance never moves a box UP -- measured in
   Brave with two blocks before the cleared one, the float's bottom is 40
   but the flow has already reached 48 and the browser leaves it at 48."
  [floats clear]
  (when-let [sides (case clear
                     "both" #{true false}
                     "left" #{false}
                     "right" #{true}
                     nil)]
    (reduce (fn [acc f]
              (if (contains? sides (:right? f)) (max acc (+ (:y f) (:h f))) acc))
            0 floats)))

(defn- place-float
  "Where a new float's MARGIN box goes, as `[x y]`.

   CSS 9.5.1's placement rules, minus the ones about line boxes this
   engine has no way to observe: the float may not start above `y0` (the
   current flow position, and never above an earlier float's own top), and
   it is pushed DOWN until the band at its top edge is wide enough to hold
   it. Without the pushing, floats that do not fit simply overlapped or
   overflowed -- measured in Brave on two 120px floats in a 200px box, the
   second sits at (0,20) and this engine put it at (120,0), the -53px
   median `div x` divergence the corpus reported for that cluster.

   The candidate positions are `y0` and every existing float's bottom edge
   below it: the band only ever CHANGES at a float boundary, so scanning
   those is exhaustive, not a sample."
  [floats content-x content-w y0 mw right?]
  (let [candidates (cons y0 (->> floats
                                 (map #(+ (:y %) (:h %)))
                                 (filter #(> % y0))
                                 distinct
                                 sort))
        [y l r] (or (some (fn [y]
                            (let [[l r] (float-band floats content-x content-w y)]
                              (when (<= mw (- r l)) [y l r])))
                          candidates)
                    ;; wider than the widest band anywhere: CSS puts it at
                    ;; the lowest candidate and lets it overflow, the same
                    ;; 'let it overflow rather than invent a break' rule
                    ;; this file already uses for an over-wide word.
                    (let [y (last candidates)
                          [l r] (float-band floats content-x content-w y)]
                      [y l r]))]
    [(if right? (- r mw) l) y]))

;; ---- block (normal-flow) layout ----

(defn- layout-children-block
  "Stacks `children` into successive block-level rows, advancing the
   running Y offset by each entry's own full height afterward.

   `children` is first grouped by inline-runs: every maximal run of TWO OR
   MORE adjacent inline-level children becomes a single entry laid out as
   one inline formatting context (layout-inline-run) — one or more shared
   line boxes — instead of one row per child. Everything else (a block
   element, a lone text child, a replaced/form-control element, an inline
   box this engine cannot flow) is still exactly one row, and takes the
   identical path it always did. The two older string-level merges
   upstream in with-generated-content ((1) a run of adjacent real
   text-node siblings collapsed into ONE text child, see
   merge-adjacent-text-runs; (2) a ::before/::after generated node
   combined with one directly-adjacent real text-node sibling, see
   merge-generated-with-text) still run before this function sees
   `children`, and compose with inline flow rather than competing with it:
   they decide what belongs in one styled run, inline flow decides which
   runs share a line.

   A child with `position: relative` (see `relative-offset`) is
   translated by its own real CSS offset AFTER being measured/laid out
   at its normal static position -- purely a paint-time shift, deliberately
   NOT read into `advance` (computed from the child's own real, UNSHIFTED
   `child-h`), so a relatively positioned box never disturbs where
   FOLLOWING siblings stack, exactly matching real CSS's own 'relative
   positioning affects painting only, never layout' rule. Deliberately
   scoped to this plain block-flow case only -- a `position: relative`
   flex/grid item would need the identical treatment applied inside
   `layout-flex`/`layout-grid`'s own placement functions instead, an
   honest, documented scope-cut left for a future cycle.

   Returns `{:draw :h :margin/collapsed-top :margin/collapsed-bottom}`.
   The two margin keys are the margins that collapsed OUT of this box --
   see the `mt*`/`mb*` comments below and `layout-block`, which forwards
   them to its own parent so a collapsed-out margin still separates
   siblings instead of vanishing.

   `collapse-top?`/`collapse-bottom?` are decided PER SIDE by the caller
   (`layout-block`) because real CSS decides them per side: a container
   with `padding-bottom` still lets its FIRST child's top margin collapse
   through its top edge. They used to be one combined flag requiring both
   edges to be free, which was strictly more conservative than CSS.

   ---- floats ----

   A `float: left|right` child is taken OUT of normal flow: it does not
   advance the running Y, it does not take part in margin collapsing (real
   CSS: margins collapse straight THROUGH a float, which is why two
   paragraphs on either side of one are still 1em apart), and it is placed
   by `place-float` into the running float band instead. What it DOES do is
   narrow the line boxes that overlap it -- see `float-band`.

   This replaces a bounded v1 that hoisted every float to the container's
   TOP and modelled the band as one `{:h :left :right}` rectangle. That
   version named its own three exclusions and this cycle removes all
   three, each measured in Brave first:

   - a float written AFTER other content now sits at the flow position it
     was written at (`<p>lead</p><div style=\"float:left\">F</div><p>x</p>`:
     Brave puts the float 1em below the first paragraph's bottom, the
     collapsed margin between the two paragraphs, and the old code put it
     at y=0 ABOVE the paragraph it was written after);
   - floats that do not fit side by side now STACK (two 120px floats in a
     200px box: (0,0) and (0,20), not (0,0) and (120,0));
   - `clear` is implemented (see `float-clearance-y`).

   A float's own MARGINS are now part of its band box, which they were not
   before -- the band was a border box wide and the float painted at its
   container's edge regardless of `margin`.

   `contains-floats?` is the caller's answer to 'does this box establish a
   formatting context'. Only such a box grows to hold its floats. The old
   code did it unconditionally, which is the easy half of the rule and the
   wrong one for the common case: an ordinary `<div>` wrapping only a
   float is 0px tall in every browser, and that is exactly why authors
   reach for `overflow: hidden` / `display: flow-root` at all.

   A float that its own container does not contain does not stop there: it
   keeps rising until it reaches a box that DOES, which is what makes the
   `overflow: hidden` clearfix work on a wrapper two levels up from the
   float. So a float that escapes is returned as `:float/escaped` and
   `layout-block` hands it to ITS parent, exactly the way
   `:margin/collapsed-top`/`-bottom` already travel. The escaped boxes
   join the parent's own float list, so they narrow its line boxes, push
   its later floats down, and answer its `clear`s too -- one mechanism,
   not a special case for height. (This is why they are kept in ABSOLUTE
   coordinates throughout: the same numbers are meaningful at every level
   they pass through.)

   ---- floats that belong to an ANCESTOR's formatting context ----

   `intruding` is the float list of the formatting context this box takes
   part in, in the same ABSOLUTE coordinates every float here is kept in.
   A box that does not establish a formatting context of its own shares its
   ancestor's, so those floats narrow ITS line boxes too, push ITS floats
   down, and answer ITS `clear`s -- which is why they are simply seeded into
   the running float list rather than consulted through a second mechanism.
   They are tagged `:intruding?` for the two questions where they must NOT
   count: this box never grows to contain a float it does not own, and it
   never re-escapes one to its parent (the parent already has it, and a
   duplicate would push the next float down twice).

   Returns `:float/escaped` as well as the four keys above."
  ([theme content-x content-y content-w opacity inherited children]
   (layout-children-block theme content-x content-y content-w opacity inherited children false false false))
  ([theme content-x content-y content-w opacity inherited children collapse-top? collapse-bottom? contains-floats?]
   (layout-children-block theme content-x content-y content-w opacity inherited children
                          collapse-top? collapse-bottom? contains-floats? nil))
  ([theme content-x content-y content-w opacity inherited children collapse-top? collapse-bottom? contains-floats?
    intruding]
  (let [floated? #(float-child? theme %)]
  (loop [remaining (inline-runs theme inherited children
                                ;; With a float present even a LONE inline
                                ;; child must flow as a run: it has to sit
                                ;; beside the float in the narrowed band
                                ;; rather than take a full-width block row
                                ;; of its own (measured: the text beside a
                                ;; left float reported x=0 w=800 against
                                ;; the browser's x=7 w=70).
                                (if (or (seq intruding) (some floated? children)) 1 2))
         y content-y draws [] floats (vec intruding)
         height 0 prev-mb 0 first? true out-mt 0 oof []]
    (if-let [child (first remaining)]
      (cond
        ;; ---- out of flow: the flow yields only its STATIC POSITION ----
        ;;
        ;; An `absolute`/`fixed` box takes no part in block flow at all --
        ;; not the running Y, not margin collapsing, not the float band --
        ;; but real CSS still needs the flow to answer ONE question about
        ;; it: where it would have been if it had stayed. That is its
        ;; STATIC POSITION, and it is what every axis with no offset
        ;; (`top: auto`/`left: auto`, i.e. the default) resolves to. This
        ;; loop is the only place that answer exists, which is why the box
        ;; travels this far before being handed to layout-absolute-children.
        ;;
        ;; Measured in Brave, all four rules below:
        ;;
        ;; - `<p>flow</p><span style="position:absolute;left:40px">abs</span>`
        ;;   puts the span at y=34 -- the paragraph's bottom edge (20) plus
        ;;   its own 14px bottom margin -- where this engine put it at y=0.
        ;; - the box's OWN top margin is added on top of that:
        ;;   `<p>one</p><p style="position:absolute">abs</p>` reports y=48,
        ;;   not 34.
        ;; - and it does NOT collapse with the preceding sibling's bottom
        ;;   margin, which is the one rule that could not be guessed:
        ;;   margin-bottom 10 then margin-top 30 reports y=60 (20+10+30),
        ;;   and margin-bottom 30 then margin-top 10 reports the same 60
        ;;   (20+30+10). Collapsing would have given 80 for the first.
        ;; - as the FIRST child its own margin is added but not collapsed
        ;;   out either: `<div><p style="position:absolute">abs</p><p>after
        ;;   </p></div>` puts the absolute one at y=14 and the in-flow one
        ;;   (whose identical margin DOES collapse through the container's
        ;;   top edge) at y=0.
        ;;
        ;; `prev-mb`/`first?` are exactly the two the in-flow branch below
        ;; already maintains, so this is the same flow position a real
        ;; sibling would get, plus the box's own margin and minus the
        ;; collapsing a real sibling would take part in.
        ;;
        ;; Deliberately NOT modelled: `clear` on an out-of-flow box (real
        ;; CSS ignores it, and so does this), and the INLINE static
        ;; position -- a box written between two words is placed at the
        ;; container's content edge here, where a browser puts it at the
        ;; point in the line it was written at (measured: x=31.38 for
        ;; `text <span style="position:absolute">pop</span> tail`, and 0
        ;; here). That one needs the line box that
        ;; layout-inline-run builds, and only its BLOCK-level half is
        ;; implemented here -- see layout-inline-run's own `:out-of-flow`
        ;; return for the half that is.
        (absolute? theme child)
        (let [cst (node-style child theme)]
          (recur (rest remaining) y draws floats height prev-mb first? out-mt
                 (conj oof {:node child
                            :x (+ content-x (margin-side cst :left))
                            :y (+ y (if first? 0 prev-mb) (margin-side cst :top))})))

        ;; ---- a float: placed into the band, invisible to block flow ----
        (floated? child)
        (let [fst (node-style child theme)
              m (measure-child theme content-w opacity inherited child true)
              fmt (margin-side fst :top)
              fml (margin-side fst :left)
              ;; the MARGIN box, which is what every CSS float rule is
              ;; stated about (see the float-band ns comment)
              mw (+ fml (:w (:box m)) (margin-side fst :right))
              mh (+ fmt (:h (:box m)) (margin-side fst :bottom))
              right? (= "right" (:float fst))
              ;; CSS 9.5.1: never above the current flow position (which
              ;; includes the previous sibling's pending bottom margin --
              ;; measured, the float lands where the NEXT block would),
              ;; never above an earlier float's own top, and never above
              ;; whatever its own `clear` demands.
              y0 (max (+ y (if first? 0 prev-mb))
                      (reduce (fn [a f] (max a (:y f))) content-y floats)
                      (or (float-clearance-y floats (:clear fst)) content-y))
              [fx fy] (place-float floats content-x content-w y0 mw right?)]
          ;; NOTHING about block flow changes: not `y`, not `height`, not
          ;; `prev-mb`, not `first?`. A float neither separates its
          ;; siblings nor stops their margins collapsing through it.
          (recur (rest remaining) y
                 (into draws (translate-ops (+ fx fml) (+ fy fmt) (:draw m)))
                 (conj floats {:x fx :y fy :w mw :h mh :right? right?})
                 height prev-mb first? out-mt oof))

        (and (map? child) (:inline/run child))
        (let [run (:inline/run child)
              ;; The preceding sibling's pending bottom margin separates a
              ;; line box exactly as it separates a block box: CSS wraps
              ;; inline content in an ANONYMOUS block, and an anonymous
              ;; block has no margins of its own, so the collapsed gap is
              ;; simply whatever was pending. This branch used to drop it on
              ;; the floor -- and the reason that was not caught earlier is
              ;; that a LONE inline child never reaches this branch (see
              ;; inline-runs' minimum of 2): `<div><p>para</p>bare text
              ;; </div>` took the block path and got the gap right, while
              ;; `<div><p>para</p><span>a</span> <b>b</b></div>` came here
              ;; and lost it. Measured in Brave, the second is 55px tall
              ;; (20 + the <p>'s 14px margin + a 21px line box) and was 41
              ;; here. It is the whole of :page/login-form's `label y
              ;; -18.4`/`input y -17.4` residual: an <h2>'s 17.43px bottom
              ;; margin vanished before the first <label>/<input> line.
              gap-before (if first? 0 prev-mb)
              run-y (+ y gap-before)
              [bl br] (float-band floats content-x content-w run-y)
              {:keys [draw h] run-oof :out-of-flow}
              (layout-inline-run theme bl run-y (max 0 (- br bl))
                                 opacity inherited run)
              advance (+ gap-before h (:gap theme))]
          ;; a line box is real content: nothing collapses through it, so
          ;; `prev-mb` resets to 0 and (when it is the FIRST entry) no top
          ;; margin escapes this container either. An out-of-flow box
          ;; NESTED in one of the run's inline elements comes back with the
          ;; containing block that inline box turned out to be -- it joins
          ;; the same list this loop's own out-of-flow branch feeds, in
          ;; document order, and layout-absolute-children reads the
          ;; per-entry containing block from there.
          (recur (rest remaining) (+ y advance) (into draws draw) floats (+ height advance) 0 false out-mt
                 (into oof run-oof)))

        :else
        (let [cst (when (map? child) (node-style child theme))
              mt (if cst (margin-side cst :top) 0)
              mb (if cst (margin-side cst :bottom) 0)
              ml (if cst (margin-side cst :left) 0)
              mr (if cst (margin-side cst :right) 0)
              ;; An `auto` inline margin is not a length, so it contributes
              ;; NOTHING to the width the child is laid out in -- it absorbs
              ;; whatever is left over afterwards (see auto-dx below). Both
              ;; already read 0 through margin-side, because node-style's
              ;; parse-int turns `auto` into the same nil a missing margin
              ;; gives; these two flags are the part that coercion erased.
              ml-auto? (boolean (and cst (auto-margin? cst :left)))
              mr-auto? (boolean (and cst (auto-margin? cst :right)))
              ;; The child is laid out at the running `y` FIRST and shifted
              ;; down by `gap-before` afterwards, because the gap cannot be
              ;; known until the child has been laid out: a margin that
              ;; collapsed out of the CHILD's own top edge takes part in it
              ;; (see `mt*`). Laying a box out at one origin and translating
              ;; it is equivalent here for the reason layout-absolute-
              ;; children's docstring already establishes -- layout-node
              ;; only ever ADDS its x/y params as an offset.
              ;;
              ;; That equivalence is exactly what a float breaks, and it is
              ;; the ONLY thing that makes this a two-pass step: the band a
              ;; child's lines must avoid depends on where the child really
              ;; ends up, so floats handed down are expressed relative to
              ;; the origin the child is being laid out at (`y`), which is
              ;; `gap-before` above its final one. The first pass exists to
              ;; learn `gap-before`; when it turns out to be non-zero and
              ;; there is a band to avoid, the child is laid out again with
              ;; the band shifted to match. Bounded by construction -- the
              ;; second pass happens only for a block child that has floats
              ;; overlapping it AND a gap, and it passes no floats of its
              ;; own down a third time.
              band-for-child (when (seq floats) floats)
              lay (fn [dx dy]
                    (layout-node theme (+ content-x ml) y
                                 (max 0 (- content-w ml mr))
                                 opacity inherited child
                                 (when band-for-child
                                   (mapv #(-> % (update :x - dx) (update :y - dy)
                                              (assoc :intruding? true))
                                         band-for-child))))
              laid (lay 0 0)
              ;; Real CSS collapses ADJACENT vertical margins: the gap
              ;; between two siblings is the LARGER of the first's bottom
              ;; and the second's top, not their sum. Without this, UA
              ;; margins would double every gap between paragraphs and no
              ;; page would ever match a browser.
              ;; Parent-child collapsing: when the parent has no top
              ;; border or padding to separate them, the FIRST child's top
              ;; margin collapses THROUGH the parent and ends up outside it
              ;; -- which is why a browser reports the first `<p>` of a
              ;; container at the container's own top edge, not 1em below
              ;; it. Measured directly against Chrome, where an unstyled
              ;; wrapper's first paragraph sits at y=0.
              ;;
              ;; A collapsed-out margin does NOT disappear, though: it ends
              ;; up OUTSIDE the box and still separates it from its
              ;; siblings, which is the half this engine used to drop on
              ;; the floor. `mt*`/`mb*` are the child's EFFECTIVE outer
              ;; margins -- its own, or whatever collapsed out through its
              ;; edge, whichever is larger. Measured in Chrome on
              ;; `<div>x</div><div><p>y</p></div><div>z</div>`: the browser
              ;; reports the middle div at y=34 and the last at y=68, i.e.
              ;; the inner `<p>`'s 14px margins separating divs that have
              ;; no margins of their own. This engine stacked them flush at
              ;; 20/40.
              ;;
              ;; `collapse-margins` rather than `max` because a NEGATIVE
              ;; margin collapses too, and never wins a max -- see its own
              ;; docstring for the measurement.
              mt* (collapse-margins mt (:margin/collapsed-top laid 0))
              mb* (collapse-margins mb (:margin/collapsed-bottom laid 0))
              gap-before (cond
                           (and first? collapse-top?) 0
                           first? mt*
                           :else (collapse-margins prev-mb mt*))
              ;; `clear` on a BLOCK child: its top border edge is pushed
              ;; down to the bottom margin edge of the floats on the
              ;; cleared side. The extra distance is CLEARANCE -- real
              ;; layout, so it also makes the container taller, which is
              ;; the whole point of the `clear`ed-empty-div idiom. Never
              ;; negative: clearance only ever pushes a box DOWN.
              clearance (if-let [c (float-clearance-y floats (:clear cst))]
                          (max 0 (- c (+ y gap-before)))
                          0)
              gap-before (+ gap-before clearance)
              child-w (:w (:box laid))
              ;; ---- where the leftover inline space goes ----
              ;;
              ;; CSS 2.1 SS10.3.3: `margin-left + width + margin-right` must
              ;; equal the containing block's width. `free` is what that
              ;; equation has left over once the child has resolved its own
              ;; width -- which is why it is computed HERE, after the child
              ;; is laid out, rather than from its declared `width` (a table
              ;; shrink-wraps, a `min-width`/`max-width` clamps, and an
              ;; `auto` width has already absorbed the whole of `free` and
              ;; leaves 0 here, which is exactly the answer real CSS gives:
              ;; auto margins on an auto-width block are 0).
              ;;
              ;; Who gets it:
              ;;   both margins `auto`  -> split evenly, i.e. CENTRED
              ;;   one margin `auto`    -> that side takes all of it
              ;;   neither, ltr         -> margin-right, so the box stays left
              ;;   neither, rtl         -> margin-left, so the box goes right
              ;;
              ;; Over-constrained the other way (a declared width WIDER than
              ;; the container, so `free` is negative) an auto margin is 0
              ;; and the box overflows the end edge -- measured in Brave, a
              ;; 300px `margin: 0 auto` block in a 200px container sits at
              ;; x=0 and is 300 wide, NOT centred at x=-50.
              free (max 0 (- content-w ml mr child-w))
              rtl? (= "rtl" (:direction inherited))
              auto-dx (cond
                        (and ml-auto? mr-auto?) (quot free 2)
                        ml-auto? free
                        mr-auto? 0
                        rtl? free
                        :else 0)
              ;; second pass: see `lay` above. The band is expressed relative
              ;; to the origin the child was laid out at, so it has to be
              ;; shifted by the SAME `auto-dx`/`gap-before` the child's own
              ;; draw-ops are about to be. Only the draw ops, the escaped
              ;; floats and the child's HEIGHT can differ -- a narrowed line
              ;; makes a paragraph taller -- so `gap-before`/`mt*`/`mb*` and
              ;; `child-w` stay the ones the first pass produced, and
              ;; `child-h` is read from this one.
              laid (if (and band-for-child (or (pos? gap-before) (not (zero? auto-dx))))
                     (lay auto-dx gap-before)
                     laid)
              child-h (:h (:box laid))
              ;; Floats this child did not contain rise into THIS
              ;; container's band. Shifted by the same `gap-before` and
              ;; `auto-dx` the child's own draw-ops are, and by nothing else
              ;; -- a `position: relative` offset is paint-only, so it must
              ;; not move a float, which is layout.
              escaped (mapv #(-> % (update :y + gap-before) (update :x + auto-dx))
                            (:float/escaped laid []))
              advance (+ gap-before child-h (:gap theme))
              shifted (if (and (zero? gap-before) (zero? auto-dx))
                        (:draw laid)
                        (translate-ops auto-dx gap-before (:draw laid)))
              draw (if (and cst (= "relative" (:position cst)))
                     (let [[dx dy] (relative-offset cst content-w nil)]
                       (translate-ops dx dy shifted))
                     shifted)]
          (recur (rest remaining) (+ y advance) (into draws draw) (into floats escaped)
                 (+ height advance) mb* false
                 (if (and first? collapse-top?) mt* out-mt)
                 oof)))
      ;; ^ closes: if / recur / let / cond
      {:draw draws
       ;; A container grows to hold its floats ONLY when it establishes a
       ;; formatting context (see `contains-floats?`). Otherwise the float
       ;; escapes it -- measured in Brave, a plain `<div style="width:200px">`
       ;; whose only child is a 50x60 float reports height 0, and the same
       ;; div with `overflow: hidden` reports 60.
       ;; ...its OWN floats. A float that intruded from an ancestor's
       ;; formatting context is already contained (or escaping) up there;
       ;; growing to hold it here would report a box taller than any browser
       ;; does, and re-escaping it would deliver the parent a duplicate.
       :h (max (if contains-floats?
                 (reduce (fn [a f]
                           (if (:intruding? f) a (max a (- (+ (:y f) (:h f)) content-y))))
                         0 floats)
                 0)
               0
               (+ (- height (:gap theme))
                  (if collapse-bottom? 0 prev-mb)))
       :margin/collapsed-top out-mt
       :margin/collapsed-bottom (if collapse-bottom? prev-mb 0)
       ;; the floats this box did NOT contain, for its parent to keep
       ;; carrying up until something does
       :float/escaped (if contains-floats? [] (filterv (complement :intruding?) floats))
       ;; every out-of-flow child, in document order, each carrying the
       ;; static position the flow above just computed for it -- see the
       ;; out-of-flow branch. layout-block hands these straight to
       ;; layout-absolute-children, which is the only consumer.
       :out-of-flow oof})))))

(defn- layout-absolute-children
  "Real CSS `position: absolute` anchors a box's edges to its containing
   block's `left`/`top`/`right`/`bottom` -- this used to read ONLY `left`/
   `top`, so `right`/`bottom` (extremely common for corner-pinned badges,
   close buttons, and overlays) were silently ignored and the child always
   landed at `left:0;top:0`. Fixed by measuring the child at the origin
   first (the same 'measure, then `translate-ops`' technique
   `layout-flex`/`layout-grid` already use for cross-axis/track placement,
   safe here for the identical reason: `layout-node` only ever ADDS its
   `x`/`y` params as an offset, so laying out at `0,0` then translating by
   `dx`/`dy` is equivalent to laying out directly at `dx,dy`) so its real,
   resolved box `:w`/`:h` is known before solving for a `right`/`bottom`
   anchor, which needs the box size to compute (`dx = content-w - w -
   right`). When `left`/`top` is present it always wins over `right`/
   `bottom` (matching this engine's existing width/height resolution,
   which is already decided before this placement step runs and has no
   'stretch to fill left+right' auto-width solving -- a real but deeper
   CSS behavior deliberately out of scope here, the same kind of honest,
   documented cut this file already makes elsewhere, e.g. `hsl()`'s
   hue-unit scoping).

   Returns `{:below :above}` rather than one flat, sorted vector -- real
   bug this guards: a negative `z-index` on a positioned element must
   paint BEHIND its stacking context's own in-flow content (this is the
   entire, well-known point of a negative z-index -- pinning an element
   behind its container's other children), but `layout-block` used to
   splice this function's ENTIRE output after in-flow content
   unconditionally, regardless of z sign. Confirmed via direct REPL
   reproduction before touching source: a `z-index: -1` absolutely
   positioned red box painted LAST (topmost) over an in-flow green
   sibling instead of behind it. `layout-block` now interleaves `:below`
   right after its own background/border/outline (before in-flow
   content) and keeps `:above` where the old, single splice point sat --
   still simplistic versus real CSS's full stacking-context algorithm
   (no separate treatment for 0-vs-auto vs explicit-positive z-index, no
   nested stacking contexts of their own), but correctly resolves the
   one case that was silently backwards. Ties within each group still
   sort by `:z` ascending, same as before."
  ;; `children` are the `{:node :x :y}` entries layout-children-block's own
  ;; out-of-flow branch produced: `:x`/`:y` are that box's STATIC POSITION,
  ;; the place the normal flow would have put it, and they are deliberately
  ;; not the same origin as `pad-*`. With no offset on an axis, real CSS
  ;; leaves the box exactly there. Only an axis that HAS an offset resolves
  ;; against `pad-*`. Conflating the two moved every offsetless absolute/
  ;; fixed box by the ancestor's padding, which the
  ;; fixed-child-does-not-push-its-following-sibling-down test caught.
  ;;
  ;; An entry may also carry its own `:cb` -- a containing block that is
  ;; NOT this block box, which is what a `position: relative` INLINE
  ;; ancestor establishes (see layout-inline-run). Everything else about
  ;; the placement is identical, so the containing block is simply read per
  ;; entry rather than taken from the arguments.
  ;;
  ;; ---- `position: fixed` is anchored to the VIEWPORT ----
  ;;
  ;; Real CSS gives a fixed box the viewport as its containing block, and
  ;; no ancestor can take that away: an offsetting ancestor must not move
  ;; it, a `position: relative` ancestor must not capture it, and a `%`
  ;; offset resolves against the viewport's size rather than the
  ;; ancestor's. This engine used to run `fixed` through the ancestor
  ;; exactly like `absolute`, which is why a fixed header inside any
  ;; indented wrapper came out indented too.
  ;;
  ;; Measured in Brave, on a probe page shaped like the conformance
  ;; corpus's own (800px cases, 756px viewport):
  ;;
  ;;   ancestor at x=120, child `left: 0`    -> x=0    (was 120 here)
  ;;   ancestor at x=120, child `left: 10px` -> x=10   (was 130 here)
  ;;   200px ancestor,    child `left: 50%`  -> x=378  = 756/2, not 100
  ;;   200px ancestor,    child `right: 0`   -> x=749  = 756-7, not 193
  ;;
  ;; and a fixed box with NO offset on an axis stays at its STATIC
  ;; position on that axis (measured x=40 inside a `margin-left: 40px`
  ;; container) -- which is why only the offset branches below read the
  ;; viewport at all, and why an offsetless fixed box is unaffected by
  ;; this.
  ;;
  ;; The viewport is what `draw-ops` was told the root box is (`:x`/`:y`/
  ;; `:width`, plus an OPTIONAL `:height`). Two scope cuts, both stated
  ;; here rather than tuned away:
  ;;
  ;; - This is a containing block, not a fixed-positioning model. There
  ;;   is no scroll position in this engine, so a fixed box does not stay
  ;;   put while its page scrolls -- it is placed against a viewport that
  ;;   never moves. That is also why the conformance corpus cannot score
  ;;   the block axis of a fixed box at all: its cases share one long
  ;;   scrolling page, so the browser's own answer for a fixed box's `y`
  ;;   relative to a case is `-(that case's distance from the viewport
  ;;   top)` -- measured at -47.84 for `:position/fixed-leaves-flow`, and
  ;;   a different number as soon as a case is added above it.
  ;; - With no `:height` given there is no viewport height for `bottom`
  ;;   or a `%` block offset to resolve against, so those two keep
  ;;   resolving against the ancestor exactly as before rather than
  ;;   against a number nobody supplied. A host that knows its viewport
  ;;   height passes `:height` and gets the real rule.
  [theme pad-x pad-y pad-w pad-h opacity inherited children]
  (let [placed (mapv (fn [{child :node content-x :x content-y :y cb :cb}]
                        (let [cst (node-style child theme)
                              vp (when (= "fixed" (:position cst)) (:viewport theme))
                              pad-x (if vp (:x vp) (:x cb pad-x))
                              pad-y (if vp (:y vp) (:y cb pad-y))
                              pad-w (if vp (:w vp) (:w cb pad-w))
                              pad-h (if (:h vp) (:h vp) (:h cb pad-h))
                              ;; An absolutely positioned box with
                              ;; `width: auto` is SHRINK-TO-FIT, not
                              ;; fill-the-container: real CSS sizes it to
                              ;; its own content. Measured against the
                              ;; browser, a corner-pinned label reported 800
                              ;; here against its 21 -- so it also covered
                              ;; the entire row it was pinned over.
                              ;; `left` and `right` both set with `width:
                              ;; auto` SIZE the box: real CSS solves the
                              ;; over-constrained equation by giving the box
                              ;; whatever is left of the containing block.
                              ;; Measured in Brave, `left:20;right:20` inside
                              ;; a 300px box is 260 wide there and was 63
                              ;; here (shrink-to-fit around the text).
                              stretch-w (when (and (nil? (explicit-length (:width cst)))
                                                   (some? (length-or-percentage (:left cst) pad-w))
                                                   (some? (length-or-percentage (:right cst) pad-w)))
                                          (max 0 (- pad-w
                                                    (length-or-percentage (:left cst) pad-w)
                                                    (length-or-percentage (:right cst) pad-w))))
                              ;; the block-axis counterpart: `top` and
                              ;; `bottom` both set with `height: auto` size
                              ;; the box. There is no height argument to
                              ;; measure-child -- a box's height comes from
                              ;; its content -- so the resolved height is
                              ;; written onto the child as the used value,
                              ;; which is precisely what CSS says it is.
                              stretch-h (when (and (nil? (explicit-length (:height cst)))
                                                   (some? (length-or-percentage (:top cst) pad-h))
                                                   (some? (length-or-percentage (:bottom cst) pad-h)))
                                          (max 0 (- pad-h
                                                    (length-or-percentage (:top cst) pad-h)
                                                    (length-or-percentage (:bottom cst) pad-h))))
                              child (if (and stretch-h (map? child))
                                      (assoc-in child [:attrs :kotoba/used-height] stretch-h)
                                      child)
                              m (if stretch-w
                                  (measure-child theme stretch-w opacity inherited child false)
                                  (measure-child theme pad-w opacity inherited child true))
                              {:keys [w h]} (:box m)
                              ;; percentage offsets resolve against the
                              ;; containing block: the inline axis against
                              ;; its width, the block axis against its
                              ;; height. Measured against Brave, `left: 50%`
                              ;; inside a 200px box is 100px there and was
                              ;; 50px here.
                              left (length-or-percentage (:left cst) pad-w)
                              right (length-or-percentage (:right cst) pad-w)
                              top (length-or-percentage (:top cst) pad-h)
                              bottom (length-or-percentage (:bottom cst) pad-h)
                              dx (cond left (+ pad-x left)
                                       right (+ pad-x (- pad-w w right))
                                       :else content-x)
                              dy (cond top (+ pad-y top)
                                       bottom (+ pad-y (- pad-h h bottom))
                                       :else content-y)]
                          {:z (or (:z-index cst) 0) :draw (translate-ops dx dy (:draw m))}))
                      children)
        sorted (sort-by :z placed)
        {below true above false} (group-by #(neg? (:z %)) sorted)]
    {:below (vec (mapcat :draw below))
     :above (vec (mapcat :draw above))}))

(defn- option-label
  [node value]
  (some (fn [child]
          (when (and (map? child) (= :option (:tag child))
                     (= (str value) (str (get-in child [:attrs :value]))))
            (->> (:children child) (filter string?) (str/join ""))))
        (:children node)))

(defn- layout-form-control
  "Unlike `layout-block`, form controls previously had NO `default-bg`/
   `border-ops` draw-ops at all -- confirmed via a real draw-ops dump
   through the full real pipeline: an `<input>`/`<select>`/`<textarea>`
   with an EXPLICIT author `background`/`border` CSS rule silently
   painted neither, a real, visible rendering bug (not merely a missing
   UA-stylesheet default) since even author-authored styling had no
   effect at all. Fixed by reusing the exact same `border-ops`/
   `default-bg`/`:rect` construction `layout-block` already uses,
   verbatim -- same fallback behavior an ordinary `<div>` already gets
   (a real background/border-width of 0 paints nothing, exactly as
   before), so this only ever ADDS painting where a real declared style
   already existed and was being silently dropped. A dedicated, more
   opinionated UA-default 'text field' look (a white/light background +
   gray border baseline every unstyled real `<input>` gets, distinct
   from an ordinary `<div>`'s panel background) is a separate, more
   subjective design decision deliberately NOT invented here.

   The caret/selection ops (`sel-ops`) previously emitted `{:draw/op
   :text :caret? true ...}`/`{:draw/op :text :selection? true ...}` with
   NO `:text` key at all -- both real hosts' `:text` paint case
   unconditionally calls `(.fillText ... (:text op) ...)`, and JS's
   `fillText` coerces a missing/nil argument to the STRING `\"null\"`,
   so every focused `<input>`'s caret/selection painted the literal word
   \"null\" instead of a cursor bar or highlight, confirmed via a real
   draw-ops dump. Fixed by emitting `:draw/op :rect` instead (a caret is
   just a thin filled rect; a selection highlight is just a wider one) --
   reusing the SAME already-fully-implemented `:rect` case both hosts
   already paint backgrounds/borders with, needing zero host-side changes
   at all, the same 'reuse existing machinery' approach this fn's own
   background/border fix above already established. Also fixed two
   smaller bugs discovered alongside it: neither op added `inset` to
   `x`/`y` (so a caret/selection painted at the control's raw box edge,
   ignoring its own padding, inconsistent with `text-op`'s identical
   `(+ x inset)`/`(+ y inset)` positioning right above it), and neither
   accounted for the character OFFSET of `s`/`e` at all -- the caret
   always painted at the box's left edge regardless of cursor position,
   and the selection highlight's own left edge never moved past `x`
   either. Fixed by computing real pixel offsets via the same OPTIONAL
   `:measure-text` theme callback `layout-text` already established
   (falling back to the identical `0.6 * font-size` per-character
   estimate this fn's own selection-width calculation already used, so
   behavior is byte-for-byte unchanged for every existing caller with no
   `:measure-text` configured). The caret op's own raw `:caret` index key
   is kept alongside the new pixel `:x` (a downstream consumer,
   `browser.core-test`, already asserted on it, and it costs nothing to
   keep as introspection data even though the paint path itself no
   longer needs it). A dedicated `::selection` pseudo-element
   background color (real CSS lets an author style the highlight) is
   deliberately NOT implemented -- the highlight uses a fixed UA-default
   translucent blue, the same class of 'reasonable baseline, not full
   spec coverage' decision as this fn's own default-bg/default-border
   above.

   The `placeholder` attribute -- present on virtually every real
   `<input>`/`<textarea>` in the wild -- was previously read NOWHERE at
   all: `control-text` came purely from `value`, so an empty-valued
   control (the overwhelmingly common real case: an unfocused input a
   user hasn't typed into yet) painted as a totally silent, empty box no
   matter what a real page declared, confirmed via direct REPL
   reproduction. Fixed by falling back to `placeholder` whenever `value`
   is absent/empty, for the same two control shapes real HTML actually
   supports `placeholder` on (`<input>` types other than `checkbox`, and
   `<textarea>`, i.e. this fn's own default `case` branch) -- `<select>`
   and checkboxes have no `placeholder` concept in real CSS/HTML either,
   so they're deliberately excluded. Shown in a fixed UA-default dim
   gray (`#767676`, distinct from the real, cascade-computed `color` a
   genuine value would use) -- the same class of 'reasonable baseline,
   not a real ::placeholder pseudo-element selector' decision as this
   fn's own selection-highlight color above."
  [theme x y avail-width opacity st node]
  (let [tag (:tag node)
        w (resolve-width st avail-width)
        inset (content-inset st)
        control-font-size (parse-int (:font-size st) (:font-size theme))
        ;; A control's content box is `rows` LINE BOXES of its own font at
        ;; its own `line-height: normal` -- i.e. `rows * (ascent + descent)`
        ;; -- and NOT the page's line-height, which the control's UA `font:`
        ;; shorthand resets away (see ua-control-box, and node-style, which
        ;; is where that reset is applied). The cascade has already folded
        ;; any inherited value onto this node by the time layout sees it, so
        ;; the reset is applied unconditionally rather than by trying to
        ;; tell inherited from declared.
        ;;
        ;; This used to be the font SIZE rather than the font's content
        ;; area, with each control's UA padding then tuned on top of that
        ;; proxy to recover the right TOTAL -- an input measuring
        ;; 21 = 13+4+4 here against Chrome's 21 = 15+2+4. Same total,
        ;; different decomposition, and the decomposition is what a line box
        ;; is built from: the control's internal baseline sits at
        ;; `border + padding-top + ascent`, so the wrong split put the
        ;; baseline 1px low and the line box 3px tall. The re-derivation the
        ;; old note here called a separate task is done: all four paddings
        ;; are now the measured UA values (ua-control-box) and this term is
        ;; the real content area for every control, not just <select>.
        ;;
        ;; a <textarea> is `rows` lines tall (HTML's own default is 2),
        ;; where every other control is one line. Measured: the browser
        ;; reports 34px for a default textarea against a 21px input.
        control-rows (if (= :textarea tag)
                       (max 1 (parse-int (get-in node [:attrs :rows]) 2))
                       1)
        control-line-height
        (cond
          ;; An open listbox is `size` option rows tall (default 4) --
          ;; measured, `size="3"` gives 3 rows and nothing else, and a
          ;; `size="5"` select holding ONE option still reserves 5.
          (select-multiple? node)
          (* (select-rows node) (select-option-height control-font-size))

          ;; A checkbox/radio is a fixed-size platform WIDGET, 13x13 at
          ;; every font size (measured in Brave), not a box sized from a
          ;; font at all -- the same 13 the width path already uses.
          (:box (ua-control-box-for node))
          (:box (ua-control-box-for node))

          ;; Measured in Chrome across five sizes, a <select> is exactly
          ;; ascent+descent+4 tall -- 10px->15 (9+2), 12px->18 (11+3),
          ;; 13.3333px->19 (12+3), 16px->21 (14+3), 24px->31 (22+5).
          :else
          (let [{:keys [ascent descent]} (font-metrics theme control-font-size
                                                       (:font-weight st) (:font-style st)
                                                       (:font-family st))]
            (* control-rows (+ ascent descent))))
        ;; content + padding + BORDER: with `box-sizing: content-box` (the
        ;; default) the border sits outside the content box in the vertical
        ;; axis too. Without it the control came out exactly one border
        ;; short on each side -- 17px against the browser's 21.
        ;; Per-SIDE padding (rather than the uniform `inset`) so a control
        ;; whose UA block padding differs from its inline padding -- only
        ;; <select> today, see ua-control-box -- gets both right; for every
        ;; other control inset-side reduces to the same uniform value.
        ;;
        ;; The border used to be added HERE, under a `box-sizing` test,
        ;; because inset-side only carried it for a border-box box. It now
        ;; carries it in both modes (see inset-side), so adding it again
        ;; would charge a `<button>` -- the one control the UA gives
        ;; `border-box` -- four extra pixels of height. The two branches of
        ;; that old test summed to the same number, which is why removing
        ;; it changes no control: content-box got padding*2 + border*2 from
        ;; the two terms, border-box got the same from inset-side alone.
        h (clamp-height st (or (resolve-height st)
                               (+ control-line-height
                                  (inset-side st :top) (inset-side st :bottom))))
        value (attr node :value)
        checked (truthy-attr? (attr node :checked))
        input-type (str/lower-case (str (or (attr node :type) "text")))
        has-value? (boolean (seq (str value)))
        placeholder (attr node :placeholder)
        control-text (case tag
                       ;; an OPEN listbox shows every option on its own row
                       ;; (option-ops below), not one selected label
                       :select (when-not (select-multiple? node) (option-label node value))
                       :input (if (= "checkbox" input-type)
                                (if checked "[x]" "[ ]")
                                (if has-value? (str value) (str placeholder)))
                       (if has-value? (str value) (str placeholder)))
        showing-placeholder? (and (not has-value?)
                                  (not= :select tag)
                                  (not= "checkbox" input-type)
                                  (some? placeholder))
        ;; An open `<select multiple>` genuinely paints its `<option>`
        ;; children as boxes, and a browser reports a real box for each --
        ;; measured, the two options of `<select multiple>` sit at
        ;; (36, 1, 11.4219, 17) and (36, 18, ...) inside a select at
        ;; (35, 0, 13.4219, 70), i.e. inset by the 1px border and stacked by
        ;; the row height. This engine emitted NO option box at all, which
        ;; is what the harness reported as `option 0/6`.
        ;;
        ;; A CLOSED select's options are deliberately still absent: the
        ;; browser puts them in a detached popup, where it reports them as
        ;; zero-sized boxes at a y that changes from run to run (-14, -482
        ;; and -3958 were all observed for the SAME markup, since it tracks
        ;; the popup's scroll offset). There is no geometry there to agree
        ;; with -- emitting a box to chase those numbers would be fitting
        ;; the oracle's popup internals, not implementing CSS.
        option-ops
        (when (select-multiple? node)
          (let [bw (:border-width st)
                row-h (select-option-height control-font-size)
                options (filterv #(and (map? %) (= :option (:tag %))) (:children node))]
            (vec (mapcat
                  (fn [i option]
                    (let [oy (+ y bw (* i row-h))
                          ox (+ x bw)
                          label (->> (:children option) (filter string?) (str/join ""))]
                      (cond-> [{:draw/op :node :id (:node/id option) :tag :option
                                :x ox :y oy :w (max 0 (- w (* 2 bw))) :h row-h
                                :class (attr option :class) :opacity opacity}]
                        (seq label)
                        (conj {:draw/op :text :control? true :node/id (:node/id option)
                               :x (+ ox select-option-side-padding) :y oy
                               :text label :opacity opacity}))))
                  (range) options))))
        box-shadow-draws (or (box-shadow-ops st x y w h opacity) [])
        border-draws (or (border-ops st x y w h opacity) [])
        outline-draws (or (outline-ops st x y w h opacity) [])
        bg (default-bg tag st theme)
        rect (when bg [{:draw/op :rect :x x :y y :w w :h h :color bg :tag tag :opacity opacity}])
        text-op (when (seq (str control-text))
                  (cond-> {:draw/op :text :control? true :node/id (:node/id node)
                           :x (+ x inset) :y (+ y inset) :text control-text :opacity opacity}
                    showing-placeholder? (assoc :color "#767676")))
        selection-start (attr node :selection-start)
        selection-end (attr node :selection-end)
        ;; Previously gated to :input alone, even though browser.document-
        ;; input tracks :selection-start/:selection-end on <textarea>
        ;; exactly the same way (editable-node?/reset-control-state/
        ;; focus-editable's caret-placement path all already treat :input
        ;; and :textarea identically) -- so a focused, actively-typed-into
        ;; <textarea> had fully correct selection state in the DOM model,
        ;; but its caret/selection-highlight was silently never painted at
        ;; all, no matter what a real page did. control-text (above) was
        ;; already unaffected -- :textarea already falls through to the
        ;; same default text-rendering branch :input's own non-checkbox
        ;; case uses -- only this gate was too narrow.
        ;; selectable-text is the real VALUE, deliberately NOT control-text
        ;; (which falls back to placeholder when value is empty). A real
        ;; input's selection/caret is always relative to its own actual
        ;; value -- placeholder text is never selectable/editable in any
        ;; real browser. Previously sel-ops measured against control-text,
        ;; so an empty, placeholder-showing input with stale non-zero
        ;; selection-start/selection-end attrs (a real, reachable state:
        ;; the JS-facing `value` setter never resets selection, so a
        ;; common `input.select(); input.value = '';` "clear" idiom
        ;; leaves stale offsets behind) painted a caret/selection rect
        ;; positioned against the PLACEHOLDER's own characters instead of
        ;; correctly clamping to the empty value's own [0,0] range.
        ;; Confirmed via direct REPL reproduction before touching source.
        selectable-text (str value)
        sel-ops (when (and (contains? #{:input :textarea} tag) selection-start selection-end)
                  (let [len (count selectable-text)
                        clamp #(max 0 (min len %))
                        s (some-> (parse-int selection-start nil) clamp)
                        e (some-> (parse-int selection-end nil) clamp)
                        font-size (:font-size theme)
                        measure (if-let [mt (:measure-text theme)]
                                  #(mt % font-size nil nil nil)
                                  #(* (count %) (long (* 0.6 font-size))))]
                    (when (and s e)
                      (if (= s e)
                        [{:draw/op :rect :caret? true :caret s :node/id (:node/id node)
                          :x (+ x inset (measure (subs selectable-text 0 s))) :y (+ y inset)
                          :w 1 :h font-size :color (:fg theme) :opacity opacity}]
                        (let [lo (min s e) hi (max s e)]
                          [{:draw/op :rect :selection? true :node/id (:node/id node)
                            :selection/start lo :selection/end hi
                            :x (+ x inset (measure (subs selectable-text 0 lo))) :y (+ y inset)
                            :w (max 1 (measure (subs selectable-text lo hi))) :h font-size
                            :color "rgba(70,130,220,0.4)" :opacity opacity}])))))
        semantic (merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w w :h h
                         :class (attr node :class) :listeners (listeners node)
                         :opacity opacity :value value :checked checked}
                        (style-passthrough st))]
    {:box {:x x :y y :w w :h h}
     ;; box-shadow-draws BEFORE rect (background), which is itself BEFORE
     ;; border-draws -- see layout-block's own identical ordering fix for
     ;; why background must precede border-draws (else the background
     ;; would completely cover the thin border edge strips); box-shadow
     ;; is real CSS's own "paints BEHIND the element's own box" layer, so
     ;; it goes first of all three. outline-draws goes LAST -- it paints
     ;; OUTSIDE the box, on top of everything else this element paints.
     :draw (cond-> (vec (concat box-shadow-draws rect border-draws outline-draws [semantic]))
             text-op (conj text-op)
             sel-ops (into sel-ops)
             ;; after the control's own :node op, so the option rows paint
             ;; on top of its background exactly as a real listbox does
             option-ops (into option-ops))}))

(defn- fieldset-legend
  "The RENDERED legend of a `<fieldset>`: the box HTML lifts out of the
   fieldset's normal flow and into its block-start border band. Returns the
   child, or nil when this fieldset has none and is laid out like any other
   block.

   Every clause was measured in Brave 2026-08-04 rather than read off the
   spec, because three of the four are surprising:

   - it is the first `<legend>` DIRECT child, not the first child. A legend
     written after two paragraphs is still the one that gets lifted (the
     paragraphs then start below the band), and a SECOND legend is an
     ordinary full-width block inside the content.
   - a legend nested inside a `<div>` is NOT it: measured, it lays out as an
     ordinary block at the content top and the fieldset has no band at all.
   - `display: none`, `float: left|right` and `position: absolute` each take
     the legend out of the running -- the fieldset then measures exactly as
     if the legend were not there (65.641 tall against 83.641), and a
     FLOATED legend becomes an ordinary float inside the content, with the
     following paragraph's text flowing beside it.
   Scope cut, stated because the measurement says otherwise: in Brave,
   `display: flex` on the FIELDSET does not change any of this -- the legend
   is lifted first and only the remaining children become flex items. Here
   the lift lives in `layout-block`, so a `display: flex|grid|table`
   fieldset lays its legend out as an ordinary item and has no border band
   (measured: 20px tall against Brave's 83.641). Doing it properly means the
   lift has to happen before the display-driven branch in `layout-node`
   picks a formatting context, which is a different edit from this one and
   affects three more layout functions. Every fieldset in the conformance
   corpus, and every one in ordinary markup, is a block."
  [theme node]
  (when (= :fieldset (:tag node))
    (->> (:children node)
         (filter #(and (map? %) (= :legend (:tag %))))
         (remove #(let [lst (node-style % theme)]
                    (or (= "none" (:display lst))
                        (contains? #{"left" "right"} (:float lst))
                        (contains? #{"absolute" "fixed"} (:position lst)))))
         first)))

(defn- layout-block
  ([theme x y avail-width opacity inherited st node]
   (layout-block theme x y avail-width opacity inherited st node nil))
  ([theme x y avail-width opacity inherited st node intruding]
  (let [;; `width: auto` fills the containing block -- UNLESS a definite
        ;; height and an `aspect-ratio` between them already say how wide
        ;; this box is (aspect-ratio-block-width, which is nil in every
        ;; other case and leaves the fill answer alone). Clamped through
        ;; resolve-width's own min/max the same way its fill answer is.
        w (if-let [ar-w (aspect-ratio-block-width
                         st (:block/containing-height inherited))]
            (clamp-width st ar-w avail-width)
            (resolve-width st avail-width))
        inset (content-inset st)
        inset-l (inset-side st :left)
        inset-r (inset-side st :right)
        inset-t0 (inset-side st :top)
        inset-b (inset-side st :bottom)
        ;; `x`/`y` are the BORDER-BOX origin the parent already placed this
        ;; box at, margins included -- layout-children-block owns a child's
        ;; margins because collapsing can only be decided between siblings.
        ;; Adding them again here double-counted every margin; invisible
        ;; while every margin was 0, immediately visible once the UA
        ;; stylesheet gave `<p>` a real one (a paragraph's own text sat
        ;; 14px below its own box).
        content-x (+ x inset-l)
        content-w (max 0 (- w inset-l inset-r))
        ;; ---- <fieldset>/<legend> ----
        ;;
        ;; A rendered legend is not in the flow at all: it sits in the
        ;; fieldset's block-start BORDER, and the border band grows to hold
        ;; it. Measured in Brave at width 800 / font 14 (see fieldset-legend
        ;; for the shape rules and ua-em-box for the box constants):
        ;;
        ;;   band      = max(border-block-start-width, legend height +
        ;;               legend margin-block-END)
        ;;   legend y  = (band - that margin box) / 2, from the fieldset's
        ;;               own border-box top
        ;;   content   = band + padding-top, as usual, i.e. `band - border`
        ;;               more than an ordinary block
        ;;
        ;; Three readings that are not guesses and are easy to get wrong:
        ;; the legend's margin-block-START is IGNORED outright (`margin-top:
        ;; 10px` and `margin-top: 40px` both leave the fieldset 83.641 tall,
        ;; where `margin-bottom: 10px` makes it 93.641); the legend is
        ;; CENTRED in the band, which only shows up when the border is
        ;; thicker than the legend (`border-top-width: 40px` puts a 20px
        ;; legend at y=10); and its margin-INLINE is honoured normally
        ;; (`margin: 10px` moves it from x=14.5 to 24.5).
        ;;
        ;; Shrink-to-fit, through the same measure-child a float uses -- a
        ;; legend is 39px wide for `Group` and 242 for a sentence, and an
        ;; explicit `width` still resolves against its own content box (a
        ;; `width: 300px` legend is 304 wide, its 2px UA side padding
        ;; outside the 300).
        legend (fieldset-legend theme node)
        legend-st (when legend (node-style legend theme))
        legend-m (when legend
                   (measure-child theme content-w opacity inherited legend true))
        legend-h (if legend-m (:h (:box legend-m)) 0)
        legend-mb (if legend-st (margin-side legend-st :bottom) 0)
        band (max (:border-width st) (+ legend-h legend-mb))
        ;; how much taller than an ordinary block the block-start edge is.
        ;; Folded into the top inset rather than added to the height
        ;; separately, so content placement and the box's own height cannot
        ;; drift apart -- and so an EXPLICIT height on the fieldset keeps
        ;; meaning what it meant (this only moves the content box's top).
        legend-extra (if legend (max 0 (- band (:border-width st))) 0)
        inset-t (+ inset-t0 legend-extra)
        content-y (+ y inset-t)
        legend-draw (when legend-m
                      (translate-ops (+ content-x (margin-side legend-st :left))
                                     (+ y (/ (- band legend-h legend-mb) 2))
                                     (:draw legend-m)))
        node-children (if legend
                        (filterv #(not (identical? % legend)) (:children node))
                        (:children node))
        scroll-x (:scroll-left st)
        scroll-y (:scroll-top st)
        ;; The containing block's own content height, as this box's PARENT
        ;; resolved it -- the basis this box's percentage height resolves
        ;; against, and nil when the parent's height is not definite.
        cb-h (:block/containing-height inherited)
        explicit-h (used-block-height st cb-h)
        ;; ...and the basis this box hands its OWN children, replacing the
        ;; parent's before anything below lays a child out. Threaded on the
        ;; inherited map because that map is the one channel that reaches
        ;; every layout-node call site; it is not a text-inheritance
        ;; property, hence the `:block/` namespace on the key.
        inherited (assoc inherited :block/containing-height (definite-content-height st cb-h))
        ;; Margins collapse THROUGH this box's own edge only when nothing
        ;; separates the edge from the child: no padding on that side, no
        ;; border, and no formatting context of its own -- which is exactly
        ;; why authors reach for `overflow: hidden` to contain a child's
        ;; margins. Measured: a `<p>` inside an `overflow: hidden` div
        ;; starts at y=14 in the browser (its own margin intact) where this
        ;; engine collapsed it out to y=0, and the container came out 28px
        ;; short.
        ;;
        ;; Decided PER SIDE (this used to be a single flag requiring BOTH
        ;; edges free): real CSS lets a first child's top margin collapse
        ;; through a container that only has `padding-bottom`, and vice
        ;; versa. The bottom side additionally requires an AUTO height --
        ;; with an explicit height the box's bottom edge is fixed, so
        ;; nothing collapses across it.
        ;; `display: flow-root` added here for the same reason it appears in
        ;; contains-floats? below: it is the ONE display value whose entire
        ;; purpose is to establish a block formatting context, and a
        ;; formatting context is exactly what stops a margin collapsing
        ;; through an edge. Without it, `flow-root` got the containment half
        ;; of its job (it grew to hold its float) and not the margin half --
        ;; measured, its first `<p>` sat at the container's own top edge with
        ;; its margin collapsed out, level with the float, so the browser's
        ;; two lines came back as one.
        ;; A `<fieldset>`'s content box establishes an INDEPENDENT formatting
        ;; context, which is why it appears here and in contains-floats?
        ;; below. Not inferable from its border (an author can set
        ;; `border: 0`) -- measured on exactly that shape,
        ;; `<fieldset style="border:0;padding:0;margin:0"><legend>G</legend>
        ;; <p>inside</p></fieldset>`: Brave puts the `<p>` at y=34, i.e. its
        ;; own 14px margin INTACT below the 20px legend band, and reports the
        ;; fieldset 68px tall with the `<p>`'s bottom margin held inside too.
        ;; Both margins collapse out of an ordinary div.
        fieldset? (= :fieldset (:tag node))
        fc-free? (and (zero? (:border-width st))
                      (contains? #{nil "visible"} (:overflow st))
                      (not= "flow-root" (:display st))
                      (not fieldset?)
                      (not (:independent-fc? st)))
        collapse-top? (and fc-free? (zero? inset-t))
        collapse-bottom? (and fc-free? (zero? inset-b) (nil? explicit-h))
        ;; Does this box establish a BLOCK FORMATTING CONTEXT, and so grow
        ;; to contain its own floats? Deliberately NOT `(not fc-free?)`:
        ;; those are two different questions that happen to share two of
        ;; their answers. A `border-width` stops margins collapsing through
        ;; an edge (fc-free?) but does NOT establish a formatting context,
        ;; so a bordered div still lets its float escape -- and
        ;; `display: flow-root`, which exists for no other purpose than to
        ;; establish one, does not appear in fc-free? at all.
        ;;
        ;; Every entry here is CSS2.1 9.4.1's own list, restricted to the
        ;; ones that reach THIS function: `float` and out-of-flow
        ;; positioning are self-evident, `overflow` other than `visible` is
        ;; the idiom authors actually use, and `:independent-fc?` is the
        ;; flag measure-child already sets on a flex/grid item or an
        ;; inline-block. Flex and grid containers never get here (they take
        ;; layout-flex/layout-grid), so they are absent by construction
        ;; rather than by oversight.
        contains-floats? (or (boolean (:independent-fc? st))
                             fieldset?
                             (and (some? (:overflow st)) (not= "visible" (:overflow st)))
                             (contains? #{"flow-root" "inline-block" "table-cell" "table-caption"}
                                        (:display st))
                             (contains? #{"left" "right"} (:float st))
                             (contains? #{"absolute" "fixed"} (:position st)))
        {:keys [draw h out-of-flow] :margin/keys [collapsed-top collapsed-bottom]
         escaped-floats :float/escaped}
        (layout-children-block theme (- content-x scroll-x) (- content-y scroll-y)
                               content-w opacity inherited node-children
                               collapse-top? collapse-bottom? contains-floats?
                               ;; A box that establishes a formatting context
                               ;; of its own is NOT intruded on by its
                               ;; ancestors' floats -- that is what a
                               ;; formatting context means, and it is the one
                               ;; place this decision belongs, because
                               ;; `contains-floats?` is computed right here.
                               (when-not contains-floats? intruding))
        ;; content + padding + BORDER, for the same reason resolve-width
        ;; adds it horizontally: with `box-sizing: content-box` the border
        ;; sits outside the content box in both axes. Without it every
        ;; bordered block came out two borders short. The border arrives
        ;; through inset-t/inset-b now (see inset-side), in BOTH box-sizing
        ;; modes -- it used to be a separate `box-sizing`-gated term here,
        ;; whose two branches summed to the same number and which would now
        ;; charge every bordered block for its border twice.
        ;; With no explicit height, an `aspect-ratio` answers instead --
        ;; but only as a FLOOR against the content, never as a ceiling
        ;; over it: see aspect-ratio-block-height for the automatic
        ;; minimum size that makes `max` the right operator and the two
        ;; Brave measurements that show it. `explicit-h` still wins
        ;; outright, which is the ratio's own rule (a declared height and
        ;; a declared width leave the ratio nothing to solve for).
        node-h (clamp-height st (or explicit-h
                                    (let [content-h (+ h inset-t inset-b)]
                                      (if-let [ar-h (aspect-ratio-block-height st w)]
                                        (max ar-h content-h)
                                        content-h)))
                             cb-h)
        node-w w
        content-h (max 0 (- node-h (* 2 inset)))
        ;; An absolutely positioned descendant resolves against this box's
        ;; PADDING box, not its content box -- `left: 0` sits just inside
        ;; the border, with the ancestor's padding OUTSIDE it rather than
        ;; indenting it. Measured in Brave on a `padding:20px;border:5px`
        ;; ancestor: the corner-pinned child sits at (5,5) there and sat at
        ;; (20,20) here, off by exactly the padding. The padding box is the
        ;; border box inset by the border alone.
        bw (:border-width st)
        pad-x (+ x bw)
        pad-y (+ y bw)
        pad-w (max 0 (- node-w (* 2 bw)))
        pad-h (max 0 (- node-h (* 2 bw)))
        ;; The static positions came back in the SCROLLED coordinate space
        ;; the flow above ran in; `pad-*` is unscrolled, and so were the
        ;; static positions before this. Undoing the scroll here keeps the
        ;; two halves of a placement in one space, which is what this
        ;; engine has always done -- an out-of-flow box does not scroll
        ;; with its container's content here (a documented cut: it is one
        ;; more consequence of there being no scroll-independent viewport
        ;; model, the same one `absolute?` names for `position: fixed`).
        out-of-flow (if (and (zero? scroll-x) (zero? scroll-y))
                      out-of-flow
                      (mapv #(-> % (update :x + scroll-x) (update :y + scroll-y)) out-of-flow))
        {above-draws :above below-draws :below} (layout-absolute-children theme pad-x pad-y pad-w pad-h opacity inherited out-of-flow)
        box-shadow-draws (or (box-shadow-ops st x y node-w node-h opacity) [])
        border-draws (or (border-ops st x y node-w node-h opacity) [])
        outline-draws (or (outline-ops st x y node-w node-h opacity) [])
        bg (default-bg (:tag node) st theme)
        rect (when bg [{:draw/op :rect :x x :y y :w node-w :h node-h :color bg :tag (:tag node) :opacity opacity}])
        semantic [(merge {:draw/op :node :id (:node/id node) :tag (:tag node) :x x :y y :w node-w :h node-h
                          :class (attr node :class) :listeners (listeners node)
                          :opacity opacity}
                         (style-passthrough st))]
        clip? (and (:overflow st) (not= "visible" (:overflow st)))
        ;; Scope cut, measured 2026-08-05 and deliberately left: this clips
        ;; at the BORDER box, and a browser clips at the PADDING box -- the
        ;; border box inset by the border, with the padding INSIDE the
        ;; clip. Measured in Brave, `div{width:100px;height:40px;
        ;; overflow:hidden;border:6px;padding:5px}` has a 122x62 border box
        ;; and reports `clientWidth`/`clientHeight` 110x50, i.e. a scrollport
        ;; exactly one border in on each side. So an overflowing child is
        ;; painted 6px too far under the border here.
        ;;
        ;; Not folded into the inset-side change that landed with this
        ;; comment, even though it is the same border: the content inset
        ;; moves where children are LAID OUT and this moves what is
        ;; PAINTED, they are separate op streams, and nothing in the corpus
        ;; measures a clip edge (the geometry axis reads boxes, not clips,
        ;; and both sides report the same 122x62 border box for the shape
        ;; above). Changing it would be a paint change scored by nothing.
        clip-push (when clip? [{:draw/op :clip :clip/op :push :node/id (:node/id node)
                                :x x :y y :w node-w :h node-h}])
        clip-pop (when clip? [{:draw/op :clip :clip/op :pop :node/id (:node/id node)
                               :x x :y y :w node-w :h node-h}])]
    {:box {:x x :y y :w node-w :h node-h}
     ;; The margins that collapsed out through this box's own edges,
     ;; handed back to the PARENT's layout-children-block so they still
     ;; separate this box from its siblings (real CSS: a collapsed margin
     ;; moves outside the box, it does not evaporate). Suppressed when the
     ;; height was not content-driven after all -- an explicit or clamped
     ;; height re-fixes the bottom edge, so nothing crosses it.
     :margin/collapsed-top collapsed-top
     :margin/collapsed-bottom (if (= node-h (+ h inset-t inset-b))
                                collapsed-bottom
                                0)
     ;; A float this box did not contain keeps rising: handed to the
     ;; PARENT's layout-children-block, which adds it to its own band, the
     ;; same journey a collapsed-out margin makes just above. This is what
     ;; makes the `overflow: hidden` clearfix work on a wrapper that is not
     ;; the float's own parent -- measured, a 50x60 float two levels down
     ;; inside `<div overflow:hidden><div width:200px>` leaves the outer box
     ;; 60px tall in Brave, and without this it came out 0 and the paint-
     ;; order axis (which asks what a user would CLICK) reported all 25 of
     ;; that case's sample points landing on nothing.
     :float/escaped (or escaped-floats [])
     ;; rect (background) BEFORE border-draws, not after: the real
     ;; painter (kotoba-lang/dom-gpu's webgl.cljs/webgpu.cljs) draws
     ;; :rect ops strictly in array order with no z-index reordering of
     ;; its own, so whichever rect comes LATER paints on top. The
     ;; background rect spans the box's FULL x/y/w/h -- including the
     ;; thin edge strips border-draws paints -- so drawing border-draws
     ;; FIRST (as this used to) meant the background, painted second,
     ;; completely covered every border edge, hiding it entirely.
     ;; Confirmed via a real draw-ops dump through the full pipeline: an
     ;; ordinary <div> with both an explicit background AND border-width
     ;; genuinely never showed any border pixels at all before this fix.
     ;; box-shadow-draws goes first of all three -- real CSS paints a
     ;; non-inset box-shadow BEHIND the element's own box. outline-draws
     ;; goes right after border-draws -- it paints OUTSIDE the box, on
     ;; top of everything else this element paints.
     ;;
     ;; below-draws (negative z-index positioned children) is spliced in
     ;; HERE, right after this element's own background/border/outline
     ;; but before its in-flow content -- see layout-absolute-children's
     ;; own docstring for the real bug this fixes (a negative z-index
     ;; child previously always painted on TOP of in-flow content,
     ;; backwards from real CSS stacking order). above-draws (z-index >=
     ;; 0) keeps the original splice point, after in-flow content.
     ;; `legend-draw` goes with the in-flow content, not with the border it
     ;; sits in: a legend paints ON TOP of the fieldset's border (that is
     ;; the whole visual point of the notch), and it is NOT clipped by an
     ;; `overflow` on the fieldset because it is outside the content box --
     ;; hence outside clip-push/clip-pop.
     :draw (vec (concat box-shadow-draws rect border-draws outline-draws below-draws semantic
                        clip-push draw clip-pop legend-draw above-draws))})))

;; ---- CSS transforms ------------------------------------------------------
;;
;; A transform is a PAINT-time operation, and the whole of this section
;; exists downstream of layout for that reason. Nothing here is allowed to
;; reach a box's `:box` -- see apply-element-transform, which rewrites a
;; subtree's `:draw` ops and hands `:box`, `:float/escaped`, the collapsed
;; margins and every other layout-facing key back untouched. That is not a
;; simplification, it is the property real CSS has: a following sibling of a
;; `transform: translateX(50px)` box stays exactly where the UNtransformed
;; box left it, the parent is exactly as tall as it would have been, and a
;; float inside is still where flow put it.
;;
;; The second property this section is written around: a PERCENTAGE in
;; `translate` resolves against the ELEMENT's own border box, not the
;; containing block -- the one place in CSS where a percentage looks inward.
;; That is why transform-length takes the element's own `w`/`h` as its basis
;; where every other percentage in this file takes the containing block's.

(def ^:private identity-matrix
  "CSS's 2D matrix, in its own `matrix(a, b, c, d, e, f)` order, i.e. the
   affine map `(x, y) -> (a*x + c*y + e, b*x + d*y + f)`."
  [1.0 0.0 0.0 1.0 0.0 0.0])

(defn- matrix*
  "`m1` THEN `m2`, i.e. ordinary matrix product m1 x m2, which is the order
   a `transform` list composes in: measured in Brave,
   `translate(10px, 10px) scale(2)` computes to `matrix(2, 0, 0, 2, 10, 10)`
   (the translation untouched by the scale) while `scale(2)
   translate(10px, 10px)` computes to `matrix(2, 0, 0, 2, 20, 20)` (the
   translation scaled by it). The leftmost function is the OUTERMOST."
  [[a1 b1 c1 d1 e1 f1] [a2 b2 c2 d2 e2 f2]]
  [(+ (* a1 a2) (* c1 b2))
   (+ (* b1 a2) (* d1 b2))
   (+ (* a1 c2) (* c1 d2))
   (+ (* b1 c2) (* d1 d2))
   (+ (* a1 e2) (* c1 f2) e1)
   (+ (* b1 e2) (* d1 f2) f1)])

(defn- round-4
  "Four decimal places, which is what the oracle itself reports (a 45deg
   rotation of a 100x20 box gives 84.8528, not 84.85281374238569). Keeps
   float noise out of draw-op coordinates without pretending transforms
   produce integers -- they do not, and rounding them to integers would put
   a rotation 0.5px out before anything downstream saw it."
  [v]
  (/ (Math/round (* (double v) 10000.0)) 10000.0))

(defn- transform-number
  "A bare <number> argument (`scale(2)`, `matrix(2, 0, 0, 2, 10, 10)`), or
   nil when the token is not one."
  [v]
  (let [s (str/trim (str v))]
    (when (re-matches #"[-+]?(?:[0-9]*\.)?[0-9]+(?:[eE][-+]?[0-9]+)?" s)
      (parse-dbl s nil))))

(defn- transform-length
  "A <length-percentage> argument, as a DOUBLE, resolved against `basis` --
   which for every transform function that takes one is the element's OWN
   border-box dimension (see this section's header comment).

   `px` and `%` and a unitless zero, and nothing else. An `em`/`rem`/`vw`
   length returns nil, which makes transform-list-matrix drop the whole
   declaration: this file's general length reader (explicit-length) would
   happily read `2em` as 2 PIXELS via parse-int's leading-digit-run, and a
   silently 8x-too-small translation is a worse answer than an honestly
   absent one."
  [v basis]
  (let [s (str/trim (str v))]
    (cond
      (str/blank? s) nil
      (str/ends-with? s "%") (when basis
                               (some-> (transform-number (subs s 0 (dec (count s))))
                                       (* basis 0.01)))
      (str/ends-with? s "px") (transform-number (subs s 0 (- (count s) 2)))
      :else (when-let [n (transform-number s)] (when (zero? n) 0.0)))))

(defn- transform-angle
  "An <angle> argument in RADIANS. `deg`/`rad`/`grad`/`turn` and a unitless
   zero; nil otherwise. `grad` is tested before `rad` because it ends in
   it."
  [v]
  (let [s (str/trim (str v))
        n (fn [drop-n scale]
            (some-> (transform-number (subs s 0 (- (count s) drop-n))) (* scale)))]
    (cond
      (str/blank? s) nil
      (str/ends-with? s "grad") (n 4 (/ Math/PI 200.0))
      (str/ends-with? s "deg") (n 3 (/ Math/PI 180.0))
      (str/ends-with? s "rad") (n 3 1.0)
      (str/ends-with? s "turn") (n 4 (* 2.0 Math/PI))
      :else (when-let [bare (transform-number s)] (when (zero? bare) 0.0)))))

(defn- transform-function-matrix
  "One `transform` function as a matrix, or nil when this engine does not
   model it -- see transform-list-matrix for what nil then does to the
   whole declaration.

   The 2D functions are all here, including `matrix()` (six numbers is the
   canonical form the others reduce to, and it was measured against Brave
   like the rest: `matrix(2, 0, 0, 2, 10, 10)` on a 100x20 box reports
   (-40, 0, 200, 40), the same box `translate(10px, 10px) scale(2)` does).

   The Z-only 3D functions -- `translate3d`'s third argument, `translateZ`,
   `scale3d`'s third, `scaleZ`, `rotateZ` -- are accepted as their 2D
   projections, because without a `perspective` that projection IS what a
   browser reports: measured, `translate3d(10px, 20px, 30px)` puts the box
   at (10, 20) exactly as `translate(10px, 20px)` does.

   DELIBERATELY ABSENT, and returning nil rather than an approximation:
   `matrix3d`, `perspective`, `rotate3d`, `rotateX`, `rotateY`. Each of
   those genuinely changes the 2D projection of the box, so there is no
   honest 2D matrix for them, and this engine has no 3D pipeline to put
   them in. The related `transform-style`, `backface-visibility` and
   `perspective-origin` properties are not read at all for the same
   reason."
  [fname args w h]
  (let [arg (fn [i] (nth args i nil))
        n (count args)]
    (case fname
      "translate" (let [tx (transform-length (arg 0) w)
                        ty (if (> n 1) (transform-length (arg 1) h) 0.0)]
                    (when (and tx ty) [1.0 0.0 0.0 1.0 tx ty]))
      "translatex" (when-let [tx (transform-length (arg 0) w)]
                     [1.0 0.0 0.0 1.0 tx 0.0])
      "translatey" (when-let [ty (transform-length (arg 0) h)]
                     [1.0 0.0 0.0 1.0 0.0 ty])
      "translatez" (when (transform-length (arg 0) nil) identity-matrix)
      "translate3d" (let [tx (transform-length (arg 0) w)
                          ty (transform-length (arg 1) h)]
                      (when (and tx ty (= n 3)) [1.0 0.0 0.0 1.0 tx ty]))
      "scale" (let [sx (transform-number (arg 0))
                    sy (if (> n 1) (transform-number (arg 1)) sx)]
                (when (and sx sy) [sx 0.0 0.0 sy 0.0 0.0]))
      "scalex" (when-let [sx (transform-number (arg 0))]
                 [sx 0.0 0.0 1.0 0.0 0.0])
      "scaley" (when-let [sy (transform-number (arg 0))]
                 [1.0 0.0 0.0 sy 0.0 0.0])
      "scalez" (when (transform-number (arg 0)) identity-matrix)
      "scale3d" (let [sx (transform-number (arg 0))
                      sy (transform-number (arg 1))]
                  (when (and sx sy (= n 3) (transform-number (arg 2)))
                    [sx 0.0 0.0 sy 0.0 0.0]))
      ("rotate" "rotatez") (when-let [a (transform-angle (arg 0))]
                             (let [c (Math/cos a) s (Math/sin a)]
                               [c s (- s) c 0.0 0.0]))
      "skew" (let [ax (transform-angle (arg 0))
                   ay (if (> n 1) (transform-angle (arg 1)) 0.0)]
               (when (and ax ay)
                 [1.0 (Math/tan ay) (Math/tan ax) 1.0 0.0 0.0]))
      "skewx" (when-let [ax (transform-angle (arg 0))]
                [1.0 0.0 (Math/tan ax) 1.0 0.0 0.0])
      "skewy" (when-let [ay (transform-angle (arg 0))]
                [1.0 (Math/tan ay) 0.0 1.0 0.0 0.0])
      "matrix" (when (= n 6)
                 (let [vs (mapv transform-number args)]
                   (when (every? some? vs) vs)))
      nil)))

(def ^:private transform-function-pattern
  ;; No nesting: an argument containing its own parens (`calc(...)`, `var()`
  ;; the cascade could not substitute) does not match, so the declaration is
  ;; dropped whole rather than half-read.
  #"([a-zA-Z][a-zA-Z0-9]*)\s*\(([^()]*)\)")

(defn- transform-list-matrix
  "A whole `transform` declaration as one matrix, or nil for `none`, for an
   empty/unparseable value, and -- deliberately -- for a list containing ANY
   function this engine does not model (see transform-function-matrix).

   Dropping the WHOLE declaration on one unknown function, rather than
   composing the ones it did recognize, is the honest reading: a list is a
   single composed transform, and applying three of its four functions
   produces a box that is confidently in the wrong place. Reporting the
   untransformed box at least says, truthfully, that this engine did not
   transform it."
  [v w h]
  (let [s (str/trim (str v))]
    (when-not (or (str/blank? s) (= "none" s))
      (let [ms (re-seq transform-function-pattern s)]
        (when (and (seq ms)
                   ;; every character of the value has to be accounted for by
                   ;; the functions matched, or something unrecognized is in
                   ;; there (`transform: translateX(5px) garbage`)
                   (str/blank? (reduce (fn [acc [whole _ _]] (str/replace acc whole "")) s ms)))
          (reduce (fn [acc [_ fname argstr]]
                    (if-let [m (transform-function-matrix
                                (str/lower-case fname)
                                (mapv str/trim (str/split (str/trim argstr) #","))
                                w h)]
                      (matrix* acc m)
                      (reduced nil)))
                  identity-matrix ms))))))

(defn- transform-origin-point
  "`transform-origin` resolved to a point in the element's own border box,
   defaulting to its centre (`50% 50%`, CSS's initial value -- measured in
   Brave, `getComputedStyle` reports `50px 10px` for a 100x20 box and
   `57px 17px` for the same box with 5px padding and a 2px border, i.e. the
   BORDER box, which is the box this engine's `:node` op already reports).

   Percentages are of the border box, keywords resolve to 0/50/100%, the
   two components may be written in either order when both are keywords
   (`bottom right` = `right bottom`), and a third (Z) component is accepted
   and ignored -- it has no effect without a 3D pipeline. An unresolvable
   component falls back to the centre rather than to zero, so a
   `transform-origin` this engine cannot read leaves the transform where
   CSS's own default would have put it."
  [v w h]
  (let [toks (->> (str/split (str/trim (str v)) #"\s+")
                  (remove str/blank?)
                  (map str/lower-case)
                  vec)
        horiz #{"left" "right"}
        vert #{"top" "bottom"}
        [tx ty] (case (count toks)
                  0 ["center" "center"]
                  1 [(first toks) "center"]
                  ;; either order, but only keywords disambiguate it
                  (if (or (vert (first toks)) (horiz (second toks)))
                    [(second toks) (first toks)]
                    [(first toks) (second toks)]))
        resolve-1 (fn [t basis kw-0 kw-100]
                    (cond
                      (= t "center") (* 0.5 basis)
                      (= t kw-0) 0.0
                      (= t kw-100) (double basis)
                      :else (or (transform-length t basis) (* 0.5 basis))))]
    [(resolve-1 tx w "left" "right")
     (resolve-1 ty h "top" "bottom")]))

(defn- transformable?
  "Real CSS applies `transform` to TRANSFORMABLE elements: everything with
   a box except a non-replaced INLINE box and a table column/column-group.

   Measured in Brave, and the reason this predicate exists rather than
   being assumed: `<span style=\"transform: translateX(30px)\">` inside a
   sentence computes `matrix(1, 0, 0, 1, 30, 0)` and does NOT move -- its
   box is reported at exactly the x an untransformed span sits at. Applying
   the transform there would have moved a box every browser leaves alone.
   The corpus case that does measure an inline is `:transform/
   on-an-inline-block`, an `inline-block`, which IS transformable."
  [tag st]
  (let [d (:display st)]
    (and (not (contains? #{:col :colgroup} tag))
         (not (contains? #{"contents" "none" "table-column" "table-column-group"} d))
         (or (contains? inline-atomic-tags tag)              ; replaced / form control
             (contains? inline-atomic-displays d)            ; inline-block / -flex / -grid
             (if d
               (not= "inline" d)
               (not (contains? inline-level-tags tag)))))))

(defn- transform-ops
  "Maps a subtree's draw ops through `m`.

   This engine's draw ops are AXIS-ALIGNED: a `:rect`/`:node`/`:clip` is
   an x/y/w/h, and a `:text` is an origin plus a font size. There is no
   rotated-quad primitive here or in this engine's hosts, so the mapping is:

   - a RECT-shaped op becomes the axis-aligned bounding box of its
     transformed corners. For a translate, a scale, or any other
     axis-aligned matrix that is EXACT. For a rotation or a skew it is
     exact for the `:node` op -- `getBoundingClientRect`, which is what
     the conformance harness compares and what a hit-tester wants, reports
     that same bounding box -- and an over-covering approximation for a
     background/border fill, which will paint the bounding box where a
     browser paints a rotated rectangle inside it.
   - a TEXT op is placed at its transformed origin, with its font size
     scaled by sqrt(|det m|), the matrix's uniform scale factor. That
     factor is EXACT for a uniform scale (`scale(2)` -> 2), for a rotation
     and for a skew (both 1, and neither changes glyph size); it is a
     compromise for an anisotropic scale (`scale(2, 3)` -> 2.449), because
     a font size is one scalar and there is no anisotropic text here.
     Glyphs are never rotated -- the position is the true transformed
     position of the text's origin, the shaping is not.

   Nothing is scoped to the ops of one element: the WHOLE subtree is mapped,
   which is what makes nested transforms compose the way they do in CSS
   (measured: a `translateX(10px)` box inside a `scale(2)` box reports the
   inner translation doubled by the outer scale)."
  [[a b c d e f] ops]
  (let [px (fn [x y] (round-4 (+ (* a x) (* c y) e)))
        py (fn [x y] (round-4 (+ (* b x) (* d y) f)))
        scale (Math/sqrt (Math/abs (- (* a d) (* b c))))]
    (mapv (fn [op]
            (if (= :text (:draw/op op))
              (let [x (:x op 0) y (:y op 0)]
                (cond-> (assoc op :x (px x y) :y (py x y))
                  (:font-size op) (update :font-size #(round-4 (* % scale)))))
              (if (and (contains? op :x) (contains? op :y))
                (let [x (:x op 0) y (:y op 0)
                      w (:w op 0) h (:h op 0)
                      xs [(px x y) (px (+ x w) y) (px x (+ y h)) (px (+ x w) (+ y h))]
                      ys [(py x y) (py (+ x w) y) (py x (+ y h)) (py (+ x w) (+ y h))]
                      x0 (apply min xs) y0 (apply min ys)]
                  (cond-> (assoc op :x x0 :y y0)
                    (contains? op :w) (assoc :w (round-4 (- (apply max xs) x0)))
                    (contains? op :h) (assoc :h (round-4 (- (apply max ys) y0)))))
                op)))
          ops)))

(defn- apply-element-transform
  "Applies this element's own `transform` to what it just laid out, and to
   NOTHING else.

   `laid` is a layout result (`:box`, `:draw`, and for the block path the
   collapsed margins / escaped floats / out-of-flow list). Only `:draw` is
   rewritten. `:box` is handed back untransformed on purpose -- it is what
   the parent's flow reads to place the next sibling and to size itself,
   and a transform must not reach it (see this section's header).

   What is NOT modelled, each for a reason rather than by omission:

   - A transformed element establishes a STACKING CONTEXT in real CSS. It
     does not here; z-index handling (layout-absolute-children) is
     unchanged, so a transformed box does not lift its positioned
     descendants out of their existing painting order.
   - A transformed element is also a CONTAINING BLOCK for its absolutely
     positioned descendants, and measurably so: in Brave a
     `position: absolute; left: 5px; top: 5px` child of a static
     `transform: translate(20px, 10px)` box lands at (25, 15), while the
     same child of an untransformed static box escapes to the initial
     containing block. This engine already lands that child at (25, 15) --
     but for an unrelated reason, not because of this section: every block
     box here anchors its own out-of-flow children (layout-block hands its
     padding box straight to layout-absolute-children with no
     positioned-ancestor check at all), so the containing-block question
     never reaches a `position` test. The right answer by the wrong route
     is still worth stating out loud, because the day that broader
     simplification is fixed, THIS behaviour has to be added back
     deliberately."
  [st tag laid]
  (let [{:keys [x y w h]} (:box laid)]
    (if-not (and (:transform st) (transformable? tag st))
      laid
      (if-let [m (transform-list-matrix (:transform st) w h)]
        (if (= m identity-matrix)
          laid
          (let [[ox oy] (transform-origin-point (:transform-origin st) w h)
                ;; the transform is about the origin POINT, so it is
                ;; conjugated by the translation that puts that point at 0,0
                cx (+ x ox) cy (+ y oy)
                about (matrix* (matrix* [1.0 0.0 0.0 1.0 cx cy] m)
                               [1.0 0.0 0.0 1.0 (- cx) (- cy)])]
            (update laid :draw #(transform-ops about %))))
        laid))))

(defn layout-node
  "`intruding` (optional, 8th argument) is the float band of the formatting
   context this node takes part in, in absolute coordinates -- see
   layout-children-block's `---- floats that belong to an ANCESTOR's
   formatting context ----`. Every caller that has no band, and every caller
   outside this file, may keep using the 7-argument form. Only the block
   path reads it: a flex/grid/table container, a form control and a text
   node all establish their own formatting context (or hold no line boxes at
   all), so a float outside them cannot narrow anything inside them."
  ([node] (layout-node default-theme 0 0 320 1.0 {:color (:fg default-theme) :font-size (:font-size default-theme)
                                                  :line-height (:line-height default-theme)} node))
  ([theme x y avail-width opacity inherited node]
   (layout-node theme x y avail-width opacity inherited node nil))
  ([theme x y avail-width opacity inherited node intruding]
   (cond
     (nil? node)
     {:box {:x x :y y :w 0 :h 0} :draw []}

     (generated-node? node)
     (let [gstyle (:generated/style node)
           color (or (:color gstyle) (:color inherited))
           font-size (parse-int (:font-size gstyle) (:font-size inherited))
           line-height (resolve-line-height (:line-height gstyle) font-size
                                            (or (inherited-line-height inherited font-size) (:line-height theme)))
           font-weight (or (:font-weight gstyle) (:font-weight inherited))
           font-style (or (:font-style gstyle) (:font-style inherited))
           font-family (or (:font-family gstyle) (:font-family inherited))
           text-shadow-x (or (:text-shadow-x gstyle) (:text-shadow-x inherited))
           text-shadow-y (or (:text-shadow-y gstyle) (:text-shadow-y inherited))
           text-shadow-blur (or (:text-shadow-blur gstyle) (:text-shadow-blur inherited))
           text-shadow-color (or (:text-shadow-color gstyle) (:text-shadow-color inherited))
           text-decoration (or (:text-decoration gstyle) (:text-decoration inherited))
           text-align (or (:text-align gstyle) (:text-align inherited))
           text-transform (or (:text-transform gstyle) (:text-transform inherited))
           white-space (or (:white-space gstyle) (:white-space inherited))
           text-overflow (or (:text-overflow gstyle) (:text-overflow inherited))]
       (layout-text theme x y avail-width opacity color font-size line-height font-weight font-style font-family
                    {:x text-shadow-x :y text-shadow-y :blur text-shadow-blur :color text-shadow-color}
                    text-decoration text-align text-transform white-space text-overflow
                    (:overflow-wrap inherited) (:generated/text node)))

     (text-node? node)
     (layout-text theme x y avail-width opacity (:color inherited) (:font-size inherited) (:line-height inherited)
                  (:font-weight inherited) (:font-style inherited) (:font-family inherited)
                  {:x (:text-shadow-x inherited) :y (:text-shadow-y inherited)
                   :blur (:text-shadow-blur inherited) :color (:text-shadow-color inherited)}
                  (:text-decoration inherited)
                  (:text-align inherited) (:text-transform inherited) (:white-space inherited)
                  (:text-overflow inherited) (:overflow-wrap inherited) node)

     (= :text (:node/type node))
     (recur theme x y avail-width opacity inherited (:text node) intruding)

     (and (= :element (:node/type node)) (non-rendered-tag? (:tag node)))
     ;; <head>/<title>/<script>/<style>/<meta>/<link>: zero box, zero
     ;; draw-ops, and -- critically -- children are never walked, so a
     ;; <title>'s text content or a <script>'s raw JS source never reaches
     ;; layout-text/layout-block. Checked ahead of (and independent of) the
     ;; :display-driven branch below; see non-rendered-tags above.
     {:box {:x x :y y :w 0 :h 0} :draw []}

     (= :element (:node/type node))
     (let [st (node-style node theme)]
       (if (= "none" (:display st))
         {:box {:x x :y y :w 0 :h 0} :draw []}
         (let [;; visibility:hidden/collapse reserves layout space (unlike
               ;; display:none's zero-box branch above) but paints nothing --
               ;; reuses the SAME multiplicative opacity accumulator every
               ;; sub-layout fn and draw op already threads, so descendants
               ;; correctly inherit hidden-by-default too (0 * anything stays
               ;; 0). Honest, documented scope-cut: a descendant re-declaring
               ;; visibility:visible under a hidden ancestor is NOT un-hidden
               ;; by this approach -- real CSS visibility is invertible per-
               ;; descendant, unlike opacity/display, which this multiply-
               ;; only accumulator can't express.
               opacity (* opacity (:opacity st)
                          (if (contains? #{"hidden" "collapse"} (:visibility st)) 0 1))
               color (or (:color st) (:color inherited))
               font-size (parse-int (:font-size st) (:font-size inherited))
               line-height (resolve-line-height (:line-height st) font-size
                                                (or (inherited-line-height inherited font-size)
                                                    (:line-height theme))
                                                (boolean (:line-height/explicit? inherited)))
               line-height-ratio (line-height-factor (:line-height st) (:line-height/factor inherited))
               font-weight (or (:font-weight st) (:font-weight inherited))
               font-style (or (:font-style st) (:font-style inherited))
               font-family (or (:font-family st) (:font-family inherited))
               text-shadow-x (or (:text-shadow-x st) (:text-shadow-x inherited))
               text-shadow-y (or (:text-shadow-y st) (:text-shadow-y inherited))
               text-shadow-blur (or (:text-shadow-blur st) (:text-shadow-blur inherited))
               text-shadow-color (or (:text-shadow-color st) (:text-shadow-color inherited))
               text-decoration (or (:text-decoration st) (:text-decoration inherited))
               text-align (or (:text-align st) (:text-align inherited))
               text-transform (or (:text-transform st) (:text-transform inherited))
               white-space (or (:white-space st) (:white-space inherited))
               text-overflow (or (:text-overflow st) (:text-overflow inherited))
               overflow-wrap (or (:overflow-wrap st) (:word-break st) (:overflow-wrap inherited))
               ;; ---- how much of `direction: rtl` this engine implements ----
               ;;
               ;; `direction` is an inherited property, so it travels on the
               ;; same map every other inherited property does. What reads it
               ;; is layout-children-block, and ONLY for the block-level
               ;; question: which edge the leftover space of an
               ;; over-constrained block goes to. In an rtl containing block a
               ;; block narrower than its container sits against the RIGHT
               ;; edge, because CSS 2.1 SS10.3.3 solves the over-constrained
               ;; equation by ignoring the specified `margin-LEFT` under rtl
               ;; where it ignores `margin-right` under ltr. Measured in
               ;; Brave: a 60px block in a 200px rtl container is at x=140
               ;; there and was at x=0 here.
               ;;
               ;; What is NOT implemented, and is not a matter of wiring one
               ;; more property through: the Unicode bidirectional algorithm.
               ;; There is no reordering of inline content here at all -- no
               ;; resolved embedding levels, no neutral resolution, no
               ;; reversal of runs -- so a line of text in an rtl block comes
               ;; out in DOM order. `text/rtl-with-inline-elements` measures
               ;; exactly that gap and is expected to keep failing;
               ;; `text-align`'s own direction-relative `start`/`end` values
               ;; are deliberately left alone too, because right-aligning
               ;; an rtl line would make that case's `<b>` land near the
               ;; browser's x by symmetry (its text either side of the `<b>`
               ;; is the same width) while the words on the line were still
               ;; in the wrong order. A number improved by a coincidence is
               ;; worse than a number that says the gap is still there.
               direction (or (:direction st) (:direction inherited))
               inherited (assoc inherited
                                :direction direction
                                :line-height/explicit? (boolean (or (:line-height st)
                                                                    (:line-height/explicit? inherited)))
                                :line-height/factor line-height-ratio
                                :color color :font-size font-size :line-height line-height
                                :font-weight font-weight :font-style font-style :font-family font-family
                                :text-shadow-x text-shadow-x :text-shadow-y text-shadow-y
                                :text-shadow-blur text-shadow-blur :text-shadow-color text-shadow-color
                                :text-decoration text-decoration :text-align text-align
                                :text-transform text-transform :white-space white-space
                                :text-overflow text-overflow
                                :overflow-wrap overflow-wrap)
               tag (:tag node)
               children (laid-out-children theme node)]
           ;; ---- the one place a `transform` is applied ----
           ;; Wrapping the WHOLE dispatch, rather than each branch, is what
           ;; makes `transform` work on a block, a flex/grid container, a
           ;; table, a form control and an atomic inline alike -- every one
           ;; of them returns the same `{:box :draw}` shape here, and
           ;; apply-element-transform rewrites only the `:draw` half. See
           ;; its docstring (and the section above it) for why `:box` is
           ;; deliberately left alone.
           (apply-element-transform
            st tag
            (cond
             ;; `display: contents` generates NO box -- see
             ;; splice-display-contents, which has already promoted this
             ;; element's children into its parent's flow and emptied it.
             ;; The 0x0 op is kept so the element is still findable (a
             ;; hit-tester, an accessibility projection and the conformance
             ;; harness's tag-matched geometry axis all walk these ops).
             (= "contents" (:display st))
             {:box {:x x :y y :w 0 :h 0}
              :draw [(merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w 0 :h 0
                             :class (attr node :class) :listeners (listeners node)
                             :opacity opacity}
                            (style-passthrough st))]}

             (contains? #{:input :select :textarea} tag)
             (layout-form-control theme x y avail-width opacity st node)

             (or (= "table" (:display st))
                 (and (nil? (:display st)) (= :table tag)))
             (layout-table theme x y avail-width opacity inherited st (assoc node :children children))

             ;; `inline-flex` is the same formatting context as `flex` --
             ;; the difference is entirely OUTSIDE the box (it is
             ;; inline-level in its parent, and shrink-wraps rather than
             ;; filling), which layout-flex's own `inline?` branch and
             ;; inline-atomic-displays handle between them. There is no
             ;; second layout algorithm.
             (contains? #{"flex" "inline-flex"} (:display st))
             (let [{:keys [box-w box-h draws]} (layout-flex theme x y avail-width opacity inherited st node children)
                   box-h (clamp-height st box-h)]
               {:box {:x x :y y :w box-w :h box-h}
                ;; background rect BEFORE border-ops -- see layout-block's
                ;; own identical fix's comment for why (border-ops
                ;; painted first used to be completely hidden under the
                ;; full-box background rect painted second). box-shadow-ops
                ;; goes first of all: real CSS paints a non-inset box-shadow
                ;; BEHIND the element's own box. outline-ops goes right
                ;; after border-ops -- it paints OUTSIDE the box, on top of
                ;; everything else this element paints.
                :draw (vec (concat
                            (or (box-shadow-ops st x y box-w box-h opacity) [])
                            (when-let [bg (default-bg tag st theme)]
                              [{:draw/op :rect :x x :y y :w box-w :h box-h :color bg :tag tag :opacity opacity}])
                            (or (border-ops st x y box-w box-h opacity) [])
                            (or (outline-ops st x y box-w box-h opacity) [])
                            [(merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w box-w :h box-h
                                     :class (attr node :class) :listeners (listeners node)
                                     :opacity opacity}
                                    (style-passthrough st))]
                            draws))})

             ;; `inline-grid` takes the same path as `grid` -- the only
             ;; difference lives inside layout-grid, which shrink-wraps to
             ;; its tracks for the inline case. Reaching this branch at all
             ;; is what the inline path (inline-atomic-displays) already
             ;; arranged; without it an `inline-grid` fell through to
             ;; layout-block and laid its items out as stacked blocks.
             (contains? #{"grid" "inline-grid"} (:display st))
             (let [{:keys [box-w box-h draws]} (layout-grid theme x y avail-width opacity inherited st node children)
                   box-h (clamp-height st box-h)]
               {:box {:x x :y y :w box-w :h box-h}
                ;; background rect BEFORE border-ops -- see layout-block's
                ;; own identical fix's comment for why. box-shadow-ops goes
                ;; first of all: real CSS paints a non-inset box-shadow
                ;; BEHIND the element's own box. outline-ops goes right
                ;; after border-ops -- it paints OUTSIDE the box, on top of
                ;; everything else this element paints.
                :draw (vec (concat
                            (or (box-shadow-ops st x y box-w box-h opacity) [])
                            (when-let [bg (default-bg tag st theme)]
                              [{:draw/op :rect :x x :y y :w box-w :h box-h :color bg :tag tag :opacity opacity}])
                            (or (border-ops st x y box-w box-h opacity) [])
                            (or (outline-ops st x y box-w box-h opacity) [])
                            [(merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w box-w :h box-h
                                     :class (attr node :class) :listeners (listeners node)
                                     :opacity opacity}
                                    (style-passthrough st))]
                            draws))})

             :else
             (layout-block theme x y avail-width opacity inherited st (assoc node :children children)
                           intruding))))))

     :else
     (recur theme x y avail-width opacity inherited (str node) intruding))))

(defn draw-ops
  "Entry point: projects a kotoba.wasm.dom/tree to a flat vector of draw
   ops (see layout-node for the per-node-type breakdown). `opts` merges
   onto default-theme via its own `:theme` key (any subset of
   default-theme's keys -- :font-size/:line-height/:padding/:gap/
   :fg/:bg/:button-bg -- overrides that default), plus top-level :x/:y/
   :width for the root box.

   `opts`' `:theme` map also accepts an OPTIONAL `:measure-text` key -- a
   `(fn [text font-size font-weight font-style font-family] width-in-px)`
   real text-width function, e.g. one backed by a real browser's
   `CanvasRenderingContext2D.measureText` (with `.font` set to match all
   five args, so bold/italic/a real font-family measures its own real,
   wider/narrower metrics, not normal-weight/upright/system-default ones) --
   consulted by layout-text's word-wrap instead of this file's own
   char-w-per-character approximation (`(long (* 0.6 font-size))`, a
   monospace-like heuristic that can disagree with how a real,
   PROPORTIONAL system font actually renders, since a real host paints
   already-wrapped lines with its own real font metrics, not this
   engine's approximation -- see the ns docstring). This is a pure,
   host-independent layout engine with no glyph shaping of its own and no
   Canvas API available in every environment it runs in (e.g. the JVM test
   suite), so `:measure-text` is entirely OPTIONAL: when absent (the
   default -- every existing caller, including every test in this
   namespace, doesn't set it), word-wrap uses the exact same
   char-w-approximation code path (text-lines) this file has always used,
   completely unaffected. A host that DOES have a real measurement
   function available -- e.g. `kotoba-lang/dom-gpu`'s WebGL/WebGPU hosts
   already hold a real 2D canvas context (`text-ctx`) they use to actually
   paint text, and `kotoba-lang/browser`'s `browser.core/render-document`
   already threads its own `theme` argument straight into this same
   `opts` map -- can supply `:measure-text` to make this engine's
   word-wrap decisions agree with how the text will actually be painted,
   with no other call-site changes needed anywhere in that chain.

   `opts` also accepts an OPTIONAL `:height` -- the viewport's height.
   Nothing about normal flow needs it (a document is as tall as its
   content), and the one thing that does is `position: fixed`: a fixed
   box's containing block is the viewport, so `bottom` and a `%` block
   offset have nothing to resolve against without it. The viewport itself
   is assembled here from `:x`/`:y`/`:width`/`:height` and handed to
   layout-absolute-children on the theme -- see its own comment for what
   is and is not modelled, and note that a host with no `:height` loses
   only those two properties on fixed boxes, nothing else."
  ([tree] (draw-ops tree {}))
  ([tree opts]
   (let [x (or (:x opts) 0)
         y (or (:y opts) 0)
         width (or (:width opts) 320)
         theme (assoc (merge default-theme (:theme opts))
                      :viewport {:x x :y y :w width :h (:height opts)})
         inherited {:color (:fg theme) :font-size (:font-size theme)}]
     (:draw (layout-node theme x y width 1.0 inherited tree)))))

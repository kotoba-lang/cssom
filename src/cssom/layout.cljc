(ns cssom.layout
  "Box-model + flexbox + grid layout projection from a kotoba virtual DOM
   tree (kotoba.wasm.dom/tree) to renderer draw ops.

   Covers: padding/border/margin box model with min/max-width and
   content-box/border-box sizing; display:flex with flex-direction/
   flex-wrap/justify-content/align-items/gap; display:grid with
   grid-template-columns/grid-template-rows (fixed px + fr tracks, plus
   `repeat(<n>, <track>)` and `minmax(<px>, <px-or-1fr>)` composing over
   them, plus a constant, percentage-free `calc(...)` track --
   `calc(100px + 20px)`, not `calc(50% - 10px)`, see resolve-constant-calc
   -- a small local mirror of cssom.core's own same-scoped calc() support)
   and THREE composing item-placement mechanisms — per-item
   `grid-column`/`grid-row` explicit line-based placement, per-item
   `grid-area: <name>` named-area placement resolved against the
   container's own `grid-template-areas` quoted-string template, and
   auto-placement for everything else — see layout-grid for the exact
   subset and its documented limitations; position:relative/absolute with z-index
   stacking; opacity (multiplicatively inherited); background/
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
   This engine has NO general inline-flow layout at all — every child a
   block-level parent lays out (layout-children-block) gets its own row,
   full stop, whether it's an element, a real text node, or generated
   content, so a `<b>`/`<a>`/`<span>` sitting next to real text is stacked
   below it rather than flowing onto the same line the way real CSS
   inline-level boxes do; this is a real, general limitation, not
   something specific to generated content, and fixing it in general is
   out of scope here (it would be a large layout-engine feature in its own
   right, comparable in scope to this file's flexbox/grid support). The
   ONE bounded exception carved out of that limitation: a ::before
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
   already-combined run, not just its first fragment. Every other
   inline-adjacency shape — an ELEMENT instead of a text child (e.g.
   `<li>text<b>bold</b></li>`), ::before and ::after both wrapping one
   shared text child — is explicitly NOT covered by either exception and
   keeps behaving exactly as this whole-file limitation already made it
   behave, no worse. In particular, a text run broken by an intervening
   ELEMENT child (e.g. `<li>a<b>x</b>b</li>`) does NOT merge the `a`/`b`
   text fragments across the `<b>` — they are not adjacent in the children
   vector, so each surviving run (here, two separate one-node runs) merges
   independently, same as not merging at all. Real hosts can still swap
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
   cascade-resolved `:pseudo/before` attr, so it gets the SAME same-line
   merge with the `<li>`'s own real text (merge-generated-with-text) the
   explicit-CSS numbered-list idiom already gets. Numbering is a purely
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
   <li> children, matching real HTML5/browser semantics). Explicitly
   out of scope: the full `list-style-type` property (circle/square/
   roman/alpha/...), `list-style-position`, and `<menu>`.

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

(defn- layout-text
  "Word-wraps and lays out `text` as one or more :text draw-ops -- exactly
   the algorithm layout-node's real-DOM-text-node branch uses, factored out
   so generated ::before/::after content (see with-generated-content) can
   flow through the identical text-measurement/wrapping/paint path instead
   of a second, forked implementation. `color`/`font-size`/`font-weight`/
   `font-style` are taken as explicit args (rather than read off `inherited`
   internally) so a pseudo-element's own resolved style can override any of
   them while still falling back to whatever a real text child in the same
   spot would use.

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
   `theme` -- a `(fn [text font-size font-weight font-style] width-in-px)`
   -- see draw-ops' docstring for the full rationale (this file is a pure,
   host-independent layout engine with no real glyph shaping of its own; a
   real host that DOES have one, e.g. a browser's real Canvas 2D
   `measureText`, can supply it here so word-wrap decisions agree with how
   the text will actually be painted, INCLUDING its real bold/italic
   metrics). When `theme` has no `:measure-text` (the default -- absent
   from default-theme, and absent from every existing caller), this
   resolves to the EXACT SAME char-w-approximation code path
   (text-lines/char-w below) this file has always used, so today's
   word-wrap behavior is completely unaffected unless a host opts in."
  [theme x y avail-width opacity color font-size font-weight font-style text-decoration text-align text-transform white-space text]
  (let [line-height (:line-height theme)
        padding (:padding theme)
        measure-text (:measure-text theme)
        char-w (long (* 0.6 font-size))
        content-w (max 0 (- avail-width (* 2 padding)))
        text (apply-text-transform text-transform text)
        measure #(measure-text % font-size font-weight font-style)
        lines (cond
                (= "pre" white-space) (str/split (str text) #"\n" -1)
                (= "nowrap" white-space) [(str text)]
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
                measure-text (text-lines-measured measure content-w text)
                :else (text-lines char-w content-w text))
        line-w #(if measure-text (measure %) (* (count %) char-w))
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
     :draw (vec (map-indexed
                 (fn [i line]
                   (cond-> {:draw/op :text :x (+ x padding (align-offset line)) :y (+ y padding (* i line-height))
                            :text line :color color :font-size font-size :opacity opacity}
                     font-weight (assoc :font-weight font-weight)
                     font-style (assoc :font-style font-style)
                     text-decoration (assoc :text-decoration text-decoration)))
                 lines))}))

;; ---- per-node computed style bag ----

(defn- node-style [node theme]
  {:display (style node :display)
   :position (or (style node :position) "static")
   :left (style node :left)
   :top (style node :top)
   :z-index (parse-int (style node :z-index) 0)
   :width (style node :width)
   :height (style node :height)
   :min-width (style node :min-width)
   :max-width (style node :max-width)
   :box-sizing (or (style node :box-sizing) "content-box")
   :padding (parse-int (style node :padding) (:padding theme))
   :margin (parse-int (style node :margin) 0)
   :border-width (parse-int (style node :border-width) 0)
   :border-color (or (style node :border-color) "#000000")
   :background (or (style node :background) (style node :background-color))
   :color (style node :color)
   :font-size (style node :font-size)
   :font-weight (style node :font-weight)
   :font-style (style node :font-style)
   :text-decoration (style node :text-decoration)
   :text-align (style node :text-align)
   :text-transform (style node :text-transform)
   :white-space (or (style node :white-space) (when (= :pre (:tag node)) "pre"))
   :opacity (parse-dbl (style node :opacity) 1.0)
   :justify-content (or (style node :justify-content) "flex-start")
   :align-items (or (style node :align-items) "stretch")
   :flex-direction (or (style node :flex-direction) "row")
   :flex-wrap (or (style node :flex-wrap) "nowrap")
   :grid-template-columns (style node :grid-template-columns)
   :grid-template-rows (style node :grid-template-rows)
   :grid-template-areas (style node :grid-template-areas)
   :grid-column (style node :grid-column)
   :grid-row (style node :grid-row)
   :grid-area (style node :grid-area)
   :gap (parse-int (style node :gap) (:gap theme))
   :pointer-events (style node :pointer-events)
   :overflow (attr node :overflow)
   :scroll-top (parse-int (attr node :scroll-top) 0)
   :scroll-left (parse-int (attr node :scroll-left) 0)})

(defn- style-passthrough [st]
  {:display (:display st)
   :position (:position st)
   :z-index (:z-index st)
   :pointer-events (:pointer-events st)
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

(defn- resolve-width
  [st avail]
  (let [base (parse-int (:width st) avail)
        base (if-let [mn (explicit-length (:min-width st))] (max base mn) base)
        base (if-let [mx (explicit-length (:max-width st))] (min base mx) base)]
    base))

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
   here."
  [st]
  (explicit-length (:height st)))

(defn- content-inset
  [st]
  (+ (:padding st) (if (= "border-box" (:box-sizing st)) (:border-width st) 0)))

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

(defn- absolute? [theme child]
  (and (map? child) (= "absolute" (:position (node-style child theme)))))

(defn- partition-flow
  [theme children]
  (let [groups (group-by #(absolute? theme %) children)]
    {:in-flow (get groups false [])
     :out-of-flow (get groups true [])}))

;; ---- flexbox main-axis distribution / cross-axis alignment ----

(defn- place-main-axis
  [justify sizes gap container-size]
  (let [n (count sizes)]
    (cond
      (zero? n) []

      (= justify "space-between")
      (let [total (reduce + 0 sizes)
            free (max 0 (- container-size total))
            step (if (> n 1) (/ free (dec n)) 0)]
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

      :else
      (loop [i 0 pos 0 offsets []]
        (if (= i n)
          offsets
          (recur (inc i) (+ pos (nth sizes i) gap) (conj offsets pos)))))))

(defn- cross-offset
  [align child-cross container-cross]
  (case align
    "center" (quot (- container-cross child-cross) 2)
    "flex-end" (- container-cross child-cross)
    0))

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
   with an immediately-following `px` unit glued on with no space -- at
   index `idx` of calc() tokenizer input `s`. Returns `[token next-idx]`,
   or nil if `idx` isn't the start of one (a `%`/other unit, or stray
   text), signalling to tokenize-calc that this token isn't this engine's
   constant-calc() subset at all."
  [s idx]
  (when-let [num-str (re-find #"^[0-9]*\.?[0-9]+" (subs s idx))]
    (let [after (+ idx (count num-str))
          px? (and (<= (+ after 2) (count s)) (= "px" (subs s after (+ after 2))))
          end (if px? (+ after 2) after)]
      [{:calc/type :operand :calc/unit (if px? :px :number) :calc/value (parse-dbl num-str 0.0)}
       end])))

(defn- tokenize-calc
  "Tokenizes the inside of a `calc(...)` call into a flat token vector --
   bare operator/paren tokens plus number-or-px-length operand tokens (see
   calc-number-at) -- for parse-calc-level, skipping whitespace (ws-char?,
   the same helper split-tracks-toplevel already uses). Returns nil if any
   character isn't part of a recognized token (e.g. a `%`/`em`/other unit
   anywhere inside), the same 'stop, don't guess' contract every other
   token-matching helper in this file already uses (parse-track-token's
   :else, parse-minmax-token's fallbacks, ...)."
  [s]
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
          (if-let [[operand next-idx] (calc-number-at s idx)]
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
   call whose entire contents are this engine's constant-calc() subset
   (plain numbers/px lengths, `+`/`-`/`*`/`/`/parens -- no `%`/`em`/other
   relative unit), or nil otherwise (not a calc() call at all, a
   percentage/other-unit operand anywhere inside, an arithmetic-type
   violation, or a malformed expression) -- callers (parse-track-token,
   parse-length-px) treat nil exactly like any other unsupported token
   already degrades in this file (a 0px fixed track / an unconstrained
   1fr minmax() fallback), never guessing a number. An exact-integer
   result is returned as a plain integer (matching this file's other
   integer-pixel track sizes); a genuinely fractional result (e.g.
   `calc(100px / 3)`) is returned as a double rather than losing
   precision."
  [tok]
  (when-let [[_ inner] (re-matches calc-pattern tok)]
    (when-let [tokens (tokenize-calc inner)]
      (when-let [[node toks] (parse-calc-level tokens 0)]
        (when (empty? toks)
          (when-let [[value _unit] (eval-calc-node node)]
            (let [truncated (long value)]
              (if (== value truncated) truncated value))))))))

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

(defn- track-sizes
  "Resolves parsed tracks (see parse-track-list) to concrete pixel sizes.
   `definite-total` is the space available along this axis to distribute fr
   tracks against: always the container's content-width for columns; for
   rows it is the container's explicit :height if given, else nil. When nil,
   fr tracks (and the fr-space portion of a :minmax fr-max track) resolve
   to 0px extra here and layout-grid falls back to auto/content sizing for
   that row instead (mirroring flexbox's own auto cross-axis convention
   elsewhere in this file) — there is no definite total to share
   proportionally when the grid container's height is itself content-driven
   (a :minmax fr-max track still gets its `min` floor even then, since that
   floor never depended on fr-space).

   Every track resolves one of three ways (see fixed-contribution/
   fr-weight above for the exact per-type rules):
     - :fixed -- always its own px size, no fr participation.
     - :fr -- its proportional share of `remaining` (distribute-fr).
     - :minmax -- a fixed px max resolves like a :fixed track at
       max(min,max); an `fr` max reserves `min` px up front (subtracted
       from `remaining` alongside every other track's fixed contribution)
       and then ALSO gets a proportional fr-space share of whatever is left
       over once every reservation is subtracted — so its final size is
       `min` PLUS that share, never less than `min`."
  [tracks gap definite-total]
  (let [n (count tracks)
        gap-total (* gap (max 0 (dec n)))
        fixed-total (reduce + 0 (mapv fixed-contribution tracks))
        remaining (when definite-total (max 0 (- definite-total fixed-total gap-total)))
        fr-weights (keep fr-weight tracks)
        fr-sizes (if remaining (distribute-fr remaining fr-weights) [])]
    (loop [ts tracks frs fr-sizes out []]
      (if (empty? ts)
        out
        (let [t (first ts)]
          (if (some? (fr-weight t))
            (recur (rest ts) (rest frs)
                   (conj out (long (+ (if (= :minmax (:type t)) (:min t) 0) (or (first frs) 0)))))
            (recur (rest ts) frs (conj out (long (fixed-contribution t))))))))))

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
          (when-let [line (parse-grid-line-token (str/trim (first parts)))]
            (single line))

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
   backwards-compatibility guarantee, not a special case in the code."
  [theme children n-cols n-row-tracks areas]
  (let [n (count children)
        requests (mapv #(item-grid-placement theme % n-cols n-row-tracks areas) children)
        idx-range (range n)
        explicit? (fn [i] (let [{:keys [col row]} (nth requests i)] (boolean (or col row))))
        explicit-idxs (filter explicit? idx-range)
        auto-idxs (remove explicit? idx-range)
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
        phase2 (reduce
                (fn [{:keys [occupied placements cursor-row cursor-col]} i]
                  (loop [r cursor-row c cursor-col]
                    (if (contains? occupied [r c])
                      (if (< (inc c) n-cols)
                        (recur r (inc c))
                        (recur (inc r) 0))
                      (let [wrap? (>= (inc c) n-cols)]
                        {:occupied (conj occupied [r c])
                         :placements (assoc placements i {:col-start c :col-end (inc c)
                                                           :row-start r :row-end (inc r)})
                         :cursor-row (if wrap? (inc r) r)
                         :cursor-col (if wrap? 0 (inc c))}))))
                (assoc phase1 :cursor-row 0 :cursor-col 0)
                auto-idxs)]
    (:placements phase2)))

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
   rule lays out exactly as it did before this feature existed."
  [node pseudo-key]
  (let [style (pseudo-style node pseudo-key)]
    (when-let [content (:content style)]
      {:generated/pseudo pseudo-key
       :generated/text (str content)
       :generated/style style})))

(defn- generated-node?
  [node]
  (and (map? node) (boolean (:generated/pseudo node))))

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
        [before children] (if-let [t (and before (seq children) (real-text-child (first children)))]
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
            init-n (if reversed? (inc start) (dec start))]
        (first
         (reduce (fn [[out n] child]
                   (if (and (map? child) (= :li (:tag child)))
                     (let [n (or (parse-int (get-in child [:attrs :value]) nil) (step n))]
                       (if (or (list-style-none? child) (pseudo-content child :before))
                         [(conj out child) n]
                         [(conj out (assoc-in child [:attrs :pseudo/before]
                                               {:content (implicit-marker-content parent-tag n)}))
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

(defn- measure-child
  [theme content-w opacity inherited child]
  (let [child-avail (if (map? child) (resolve-width (node-style child theme) content-w) content-w)]
    (layout-node theme 0 0 child-avail opacity inherited child)))

(defn- layout-flex-wrap-row
  [theme cx cy cw opacity inherited st measured]
  (let [gap (:gap st)
        main-sizes (mapv #(:w (:box %)) measured)
        rows-idx (pack-rows main-sizes gap cw)
        row-cross-sizes (mapv (fn [idxs] (apply max 0 (mapv #(:h (:box (nth measured %))) idxs))) rows-idx)
        row-cross-offsets (loop [i 0 pos 0 offsets []]
                             (if (= i (count rows-idx))
                               offsets
                               (recur (inc i) (+ pos (nth row-cross-sizes i) gap) (conj offsets pos))))
        draws (mapcat
               (fn [idxs row-y]
                 (let [sizes (mapv #(nth main-sizes %) idxs)
                       offs (place-main-axis "flex-start" sizes gap cw)]
                   (mapcat (fn [child-idx off]
                             (let [m (nth measured child-idx)
                                   dx (+ cx off)
                                   dy (+ cy row-y)]
                               (translate-ops dx dy (:draw m))))
                           idxs offs)))
               rows-idx row-cross-offsets)
        total-cross (+ (reduce + 0 row-cross-sizes) (* gap (max 0 (dec (count rows-idx)))))]
    {:draws (vec draws) :main-total cw :cross-total total-cross}))

(defn- layout-flex
  [theme x y avail-width opacity inherited st node in-flow]
  (let [column? (= "column" (:flex-direction st))
        wrap? (and (not column?) (= "wrap" (:flex-wrap st)))
        w (resolve-width st avail-width)
        inset (content-inset st)
        cx (+ x (:margin st) inset)
        cy (+ y (:margin st) inset)
        cw (max 0 (- w (* 2 inset)))
        gap (:gap st)
        measured (mapv #(measure-child theme cw opacity inherited %) in-flow)]
    (if wrap?
      (let [{:keys [draws cross-total]} (layout-flex-wrap-row theme cx cy cw opacity inherited st measured)
            node-h (or (resolve-height st) (+ cross-total (* 2 inset)))]
        {:box-w w :box-h node-h :draws draws})
      (let [main-sizes (mapv (fn [m] (if column? (:h (:box m)) (:w (:box m)))) measured)
            cross-sizes (mapv (fn [m] (if column? (:w (:box m)) (:h (:box m)))) measured)
            auto-cross (if (seq cross-sizes) (apply max 0 cross-sizes) 0)
            cross-content (or (explicit-length (if column? (:width st) (:height st))) auto-cross)
            auto-main (+ (reduce + 0 main-sizes) (* gap (max 0 (dec (count main-sizes)))))
            main-content (or (explicit-length (if column? (:height st) (:width st))) auto-main)
            offsets (place-main-axis (:justify-content st) main-sizes gap main-content)
            draws (mapcat
                   (fn [m off]
                     (let [child-cross (if column? (:w (:box m)) (:h (:box m)))
                           c-off (cross-offset (:align-items st) child-cross cross-content)
                           dx (if column? (+ cx c-off) (+ cx off))
                           dy (if column? (+ cy off) (+ cy c-off))]
                       (translate-ops dx dy (:draw m))))
                   measured offsets)
            node-w (if column? (+ cross-content (* 2 inset)) (+ main-content (* 2 inset)))
            node-h (if column? (+ main-content (* 2 inset)) (+ cross-content (* 2 inset)))
            node-w (if (:width st) w node-w)]
        {:box-w node-w :box-h node-h :draws (vec draws)}))))

;; ---- grid layout ----

(defn- layout-grid
  "display:grid subset: explicit `grid-template-columns`/`grid-template-rows`
   track lists (fixed px + fr, plus `repeat()`/`minmax()` composing over
   them — see parse-track-list), THREE composing per-item placement
   mechanisms — explicit `grid-column`/`grid-row` line-based placement (see
   parse-grid-placement), named `grid-area` placement resolved against the
   container's own `grid-template-areas` (see parse-grid-template-areas), and
   auto-placement in DOM order, row-major (fills a row left-to-right before
   wrapping to the next row) for everything else — see place-grid-items/
   item-grid-placement for exactly how all three compose. `gap` — the same
   style key flex already reuses — spaces both rows and columns.

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
   track-sizes). An item spanning more than one column (`grid-column: 1 / 3`,
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
   empty, 0px-tall) rows are needed to reach it. A row whose index has an
   explicit *fixed*-px grid-template-rows track uses that literal height.
   Every other row — an `fr` row track, any row beyond the explicit track
   list, or an empty row nothing was placed into — is auto-sized to the
   tallest child whose placement STARTS in that row (mirrors flexbox's own
   auto cross-axis convention elsewhere in this file; a multi-row-span
   item's height only contributes to its start row's auto-sizing, not any
   row it merely passes through — a documented simplification, row spans
   are not this feature's must-have), UNLESS the grid container has an
   explicit :height, in which case the explicit row tracks (fixed + fr) are
   resolved proportionally against that height the same way columns are.
   Without an explicit container height there is no definite total to share
   `fr` row tracks against, so this is the one deliberate asymmetry versus
   columns in this subset — documented here rather than silently guessed at.

   Absolute-positioned children are NOT extracted via partition-flow here —
   this matches layout-flex's current behavior (today only layout-block
   partitions out-of-flow children); a position:absolute child inside a grid
   container is placed as an ordinary grid item, the same limitation flex
   already has.

   `repeat(<integer>, <track>)` and `minmax(<px>, <px-or-1fr>)` ARE
   supported and compose (e.g. `repeat(3, minmax(80px, 1fr))`) — see
   parse-track-list/parse-track-token/track-sizes. Explicitly out of scope:
   `auto` tracks, percentage tracks, `repeat(auto-fill|auto-fit, ...)` (real
   content-based auto-sizing this engine doesn't do), implicit track
   creation, dense packing, the grid-column-start/grid-column-end/
   grid-row-start/grid-row-end longhand properties (only the grid-column/
   grid-row shorthand is parsed), and the 4-value grid-area longhand
   shorthand (only a bare area-name reference is parsed, see above)."
  [theme x y avail-width opacity inherited st node in-flow]
  (let [w (resolve-width st avail-width)
        inset (content-inset st)
        cx (+ x (:margin st) inset)
        cy (+ y (:margin st) inset)
        cw (max 0 (- w (* 2 inset)))
        gap (:gap st)
        template-areas (parse-grid-template-areas (:grid-template-areas st))
        explicit-cols (parse-track-list (:grid-template-columns st))
        col-tracks (cond
                     (seq explicit-cols) explicit-cols
                     template-areas (vec (repeat (:col-count template-areas) {:type :fr :size 1.0}))
                     :else [{:type :fixed :size cw}])
        n-cols (count col-tracks)
        col-widths (track-sizes col-tracks gap cw)
        col-offsets (place-main-axis "flex-start" col-widths gap 0)
        row-tracks (parse-track-list (:grid-template-rows st))
        n-row-tracks (count row-tracks)
        explicit-h (resolve-height st)
        row-track-fr-sizes (when explicit-h (track-sizes row-tracks gap explicit-h))
        placements (place-grid-items theme in-flow n-cols n-row-tracks (:areas template-areas))
        total-rows (if (seq placements) (apply max 0 (map :row-end placements)) 0)
        measured (mapv (fn [child pl]
                          (let [item-w (span-width col-widths gap (:col-start pl) (:col-end pl))]
                            (measure-child theme item-w opacity inherited child)))
                        in-flow placements)
        row-heights (vec (map (fn [row-idx]
                                 (let [track (nth row-tracks row-idx nil)]
                                   (cond
                                     (and track (= :fixed (:type track)))
                                     (:size track)

                                     (and track row-track-fr-sizes)
                                     (nth row-track-fr-sizes row-idx)

                                     :else
                                     (let [hs (keep-indexed
                                               (fn [i pl]
                                                 (when (= row-idx (:row-start pl))
                                                   (:h (:box (nth measured i)))))
                                               placements)]
                                       (if (seq hs) (apply max 0 hs) 0)))))
                               (range total-rows)))
        row-offsets (place-main-axis "flex-start" row-heights gap 0)
        draws (vec (mapcat (fn [pl m]
                              (translate-ops (+ cx (nth col-offsets (:col-start pl)))
                                             (+ cy (nth row-offsets (:row-start pl)))
                                             (:draw m)))
                            placements measured))
        content-h (+ (reduce + 0 row-heights) (* gap (max 0 (dec (count row-heights)))))
        node-h (or explicit-h (+ content-h (* 2 inset)))]
    {:box-w w :box-h node-h :draws draws}))

;; ---- block (normal-flow) layout ----

(defn- layout-children-block
  "Stacks `children` into successive block-level rows: every entry (an
   element, a real text node, or a generated-content node -- see
   with-generated-content) gets its own row, full stop, advancing the
   running Y offset by that entry's own full height afterward. There is
   NO inline flow here (or anywhere in this file) -- multiple inline-level
   children never share one line box the way real CSS lays out e.g. text
   next to a `<b>`/`<a>`/`<span>` -- see this namespace's own docstring
   for why that's out of scope in general. The two narrow, already-merged
   exceptions -- (1) a RUN of two-or-more adjacent real text-node siblings
   collapsed into ONE text child (see merge-adjacent-text-runs), and (2) a
   ::before/::after generated node combined with ONE directly-adjacent
   (possibly already-collapsed-by-(1)) real text-node sibling into a
   single entry (see merge-generated-with-text) -- are both resolved
   upstream by with-generated-content before `children` ever reaches this
   function -- by the time a child arrives here it is already exactly one
   row, so this function itself needs no inline-flow concept at all to
   honor either exception correctly."
  [theme content-x content-y content-w opacity inherited children]
  (loop [remaining children y content-y draws [] height 0]
    (if-let [child (first remaining)]
      (let [child-margin (if (map? child) (:margin (node-style child theme)) 0)
            child-y (+ y child-margin)
            {:keys [box draw]} (layout-node theme (+ content-x child-margin) child-y content-w opacity inherited child)
            child-h (:h box)
            advance (+ child-margin child-h child-margin (:gap theme))]
        (recur (rest remaining) (+ y advance) (into draws draw) (+ height advance)))
      {:draw draws :h (max 0 (- height (:gap theme)))})))

(defn- layout-absolute-children
  [theme content-x content-y content-w opacity inherited children]
  (let [placed (mapv (fn [child]
                        (let [cst (node-style child theme)
                              left (or (explicit-length (:left cst)) 0)
                              top (or (explicit-length (:top cst)) 0)
                              cx (+ content-x left)
                              cy (+ content-y top)
                              m (layout-node theme cx cy content-w opacity inherited child)]
                          {:z (:z-index cst) :draw (:draw m)}))
                      children)
        sorted (sort-by :z placed)]
    (vec (mapcat :draw sorted))))

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
   above."
  [theme x y avail-width opacity st node]
  (let [tag (:tag node)
        w (resolve-width st avail-width)
        inset (content-inset st)
        h (or (resolve-height st) (+ (:line-height theme) (* 2 inset)))
        value (attr node :value)
        checked (truthy-attr? (attr node :checked))
        input-type (str/lower-case (str (or (attr node :type) "text")))
        control-text (case tag
                       :select (option-label node value)
                       :input (if (= "checkbox" input-type)
                                (if checked "[x]" "[ ]")
                                (str value))
                       (str value))
        border-draws (or (border-ops st x y w h opacity) [])
        bg (default-bg tag st theme)
        rect (when bg [{:draw/op :rect :x x :y y :w w :h h :color bg :tag tag :opacity opacity}])
        text-op (when (seq (str control-text))
                  {:draw/op :text :control? true :node/id (:node/id node)
                   :x (+ x inset) :y (+ y inset) :text control-text :opacity opacity})
        selection-start (attr node :selection-start)
        selection-end (attr node :selection-end)
        sel-ops (when (and (= tag :input) selection-start selection-end)
                  (let [len (count control-text)
                        clamp #(max 0 (min len %))
                        s (some-> (parse-int selection-start nil) clamp)
                        e (some-> (parse-int selection-end nil) clamp)
                        font-size (:font-size theme)
                        measure (if-let [mt (:measure-text theme)]
                                  #(mt % font-size nil nil)
                                  #(* (count %) (long (* 0.6 font-size))))]
                    (when (and s e)
                      (if (= s e)
                        [{:draw/op :rect :caret? true :caret s :node/id (:node/id node)
                          :x (+ x inset (measure (subs control-text 0 s))) :y (+ y inset)
                          :w 1 :h font-size :color (:fg theme) :opacity opacity}]
                        (let [lo (min s e) hi (max s e)]
                          [{:draw/op :rect :selection? true :node/id (:node/id node)
                            :selection/start lo :selection/end hi
                            :x (+ x inset (measure (subs control-text 0 lo))) :y (+ y inset)
                            :w (max 1 (measure (subs control-text lo hi))) :h font-size
                            :color "rgba(70,130,220,0.4)" :opacity opacity}])))))
        semantic (merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w w :h h
                         :class (attr node :class) :listeners (listeners node)
                         :opacity opacity :value value :checked checked}
                        (style-passthrough st))]
    {:box {:x x :y y :w w :h h}
     ;; rect (background) BEFORE border-draws, not after -- see
     ;; layout-block's own identical ordering fix for why: the background
     ;; rect spans the FULL box, including the thin edge strips
     ;; border-draws paints, so if border-draws were drawn (and thus
     ;; painted UNDER) first, the background would completely cover it,
     ;; hiding the border entirely.
     :draw (cond-> (vec (concat rect border-draws [semantic]))
             text-op (conj text-op)
             sel-ops (into sel-ops))}))

(defn- layout-block
  [theme x y avail-width opacity inherited st node]
  (let [w (resolve-width st avail-width)
        inset (content-inset st)
        content-x (+ x (:margin st) inset)
        content-y (+ y (:margin st) inset)
        content-w (max 0 (- w (* 2 inset)))
        scroll-x (:scroll-left st)
        scroll-y (:scroll-top st)
        {:keys [in-flow out-of-flow]} (partition-flow theme (:children node))
        {:keys [draw h]} (layout-children-block theme (- content-x scroll-x) (- content-y scroll-y) content-w opacity inherited in-flow)
        explicit-h (resolve-height st)
        node-h (or explicit-h (+ h (* 2 inset)))
        node-w w
        absolute-draws (layout-absolute-children theme content-x content-y content-w opacity inherited out-of-flow)
        border-draws (or (border-ops st x y node-w node-h opacity) [])
        bg (default-bg (:tag node) st theme)
        rect (when bg [{:draw/op :rect :x x :y y :w node-w :h node-h :color bg :tag (:tag node) :opacity opacity}])
        semantic [(merge {:draw/op :node :id (:node/id node) :tag (:tag node) :x x :y y :w node-w :h node-h
                          :class (attr node :class) :listeners (listeners node)
                          :opacity opacity}
                         (style-passthrough st))]
        clip? (and (:overflow st) (not= "visible" (:overflow st)))
        clip-push (when clip? [{:draw/op :clip :clip/op :push :node/id (:node/id node)
                                :x x :y y :w node-w :h node-h}])
        clip-pop (when clip? [{:draw/op :clip :clip/op :pop :node/id (:node/id node)
                               :x x :y y :w node-w :h node-h}])]
    {:box {:x x :y y :w node-w :h node-h}
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
     :draw (vec (concat rect border-draws semantic clip-push draw clip-pop absolute-draws))}))

(defn layout-node
  ([node] (layout-node default-theme 0 0 320 1.0 {:color (:fg default-theme) :font-size (:font-size default-theme)} node))
  ([theme x y avail-width opacity inherited node]
   (cond
     (nil? node)
     {:box {:x x :y y :w 0 :h 0} :draw []}

     (generated-node? node)
     (let [gstyle (:generated/style node)
           color (or (:color gstyle) (:color inherited))
           font-size (parse-int (:font-size gstyle) (:font-size inherited))
           font-weight (or (:font-weight gstyle) (:font-weight inherited))
           font-style (or (:font-style gstyle) (:font-style inherited))
           text-decoration (or (:text-decoration gstyle) (:text-decoration inherited))
           text-align (or (:text-align gstyle) (:text-align inherited))
           text-transform (or (:text-transform gstyle) (:text-transform inherited))
           white-space (or (:white-space gstyle) (:white-space inherited))]
       (layout-text theme x y avail-width opacity color font-size font-weight font-style text-decoration text-align text-transform white-space (:generated/text node)))

     (text-node? node)
     (layout-text theme x y avail-width opacity (:color inherited) (:font-size inherited)
                  (:font-weight inherited) (:font-style inherited) (:text-decoration inherited)
                  (:text-align inherited) (:text-transform inherited) (:white-space inherited) node)

     (= :text (:node/type node))
     (recur theme x y avail-width opacity inherited (:text node))

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
         (let [opacity (* opacity (:opacity st))
               color (or (:color st) (:color inherited))
               font-size (parse-int (:font-size st) (:font-size inherited))
               font-weight (or (:font-weight st) (:font-weight inherited))
               font-style (or (:font-style st) (:font-style inherited))
               text-decoration (or (:text-decoration st) (:text-decoration inherited))
               text-align (or (:text-align st) (:text-align inherited))
               text-transform (or (:text-transform st) (:text-transform inherited))
               white-space (or (:white-space st) (:white-space inherited))
               inherited (assoc inherited :color color :font-size font-size
                                :font-weight font-weight :font-style font-style
                                :text-decoration text-decoration :text-align text-align
                                :text-transform text-transform :white-space white-space)
               tag (:tag node)
               children (with-generated-content node (with-implicit-list-markers node (with-details-visibility node (:children node))))]
           (cond
             (contains? #{:input :select :textarea} tag)
             (layout-form-control theme x y avail-width opacity st node)

             (= "flex" (:display st))
             (let [{:keys [box-w box-h draws]} (layout-flex theme x y avail-width opacity inherited st node children)]
               {:box {:x x :y y :w box-w :h box-h}
                ;; background rect BEFORE border-ops -- see layout-block's
                ;; own identical fix's comment for why (border-ops
                ;; painted first used to be completely hidden under the
                ;; full-box background rect painted second).
                :draw (vec (concat
                            (when-let [bg (default-bg tag st theme)]
                              [{:draw/op :rect :x x :y y :w box-w :h box-h :color bg :tag tag :opacity opacity}])
                            (or (border-ops st x y box-w box-h opacity) [])
                            [(merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w box-w :h box-h
                                     :class (attr node :class) :listeners (listeners node)
                                     :opacity opacity}
                                    (style-passthrough st))]
                            draws))})

             (= "grid" (:display st))
             (let [{:keys [box-w box-h draws]} (layout-grid theme x y avail-width opacity inherited st node children)]
               {:box {:x x :y y :w box-w :h box-h}
                ;; background rect BEFORE border-ops -- see layout-block's
                ;; own identical fix's comment for why.
                :draw (vec (concat
                            (when-let [bg (default-bg tag st theme)]
                              [{:draw/op :rect :x x :y y :w box-w :h box-h :color bg :tag tag :opacity opacity}])
                            (or (border-ops st x y box-w box-h opacity) [])
                            [(merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w box-w :h box-h
                                     :class (attr node :class) :listeners (listeners node)
                                     :opacity opacity}
                                    (style-passthrough st))]
                            draws))})

             :else
             (layout-block theme x y avail-width opacity inherited st (assoc node :children children))))))

     :else
     (recur theme x y avail-width opacity inherited (str node)))))

(defn draw-ops
  "Entry point: projects a kotoba.wasm.dom/tree to a flat vector of draw
   ops (see layout-node for the per-node-type breakdown). `opts` merges
   onto default-theme via its own `:theme` key (any subset of
   default-theme's keys -- :font-size/:line-height/:padding/:gap/
   :fg/:bg/:button-bg -- overrides that default), plus top-level :x/:y/
   :width for the root box.

   `opts`' `:theme` map also accepts an OPTIONAL `:measure-text` key -- a
   `(fn [text font-size font-weight font-style] width-in-px)` real
   text-width function, e.g. one backed by a real browser's
   `CanvasRenderingContext2D.measureText` (with `.font` set to match all
   four args, so bold/italic text measures its own real, wider/narrower
   metrics, not normal-weight/upright ones) --
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
   with no other call-site changes needed anywhere in that chain."
  ([tree] (draw-ops tree {}))
  ([tree opts]
   (let [theme (merge default-theme (:theme opts))
         inherited {:color (:fg theme) :font-size (:font-size theme)}]
     (:draw (layout-node theme (or (:x opts) 0) (or (:y opts) 0) (or (:width opts) 320) 1.0 inherited tree)))))

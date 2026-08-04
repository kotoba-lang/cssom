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
   subset and its documented limitations; position:absolute (left/top/
   right/bottom anchored against the containing block) with z-index
   stacking; position:relative (top/left/right/bottom as a direct pixel
   shift from the box's own normal position, affecting painting only,
   never layout -- see relative-offset/layout-children-block; currently
   scoped to plain block-flow children only, not yet a flex/grid item,
   an honest, documented scope-cut); opacity (multiplicatively inherited); background/
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
   general INLINE FLOW (see layout-inline-run): a maximal run of two or
   more adjacent inline-level children — real text nodes, generated
   content, and inline-level elements (inline-level-tags, or any element
   an author gives `display: inline`) — shares line boxes instead of each
   getting its own block row, so `<li>text<b>bold</b></li>` renders on ONE
   line, wraps as one unit at the content width, collapses whitespace
   across fragment boundaries the way real CSS does, keeps each fragment's
   own color/font-size/weight/style/decoration as its own draw-op, and
   sits every fragment on one shared baseline. Replaced elements and form
   controls (`<img>`, `<input>`, `<button>`, `<select>`, `<textarea>`) flow
   in that line too, as ATOMIC inlines: laid out at their own intrinsic
   width (inline-atomic-avail-width) and sitting with their bottom edge on
   the text baseline, the real CSS `vertical-align: baseline` default.
   Bounded, documented cuts remain, each at the fn that owns it:
   `<svg>`/`<canvas>`/`<video>`/`<iframe>` are still not inline-level,
   because this engine cannot render them at all (inline-atomic-tags);
   `vertical-align` values other than the baseline default are not modeled;
   an inline box containing a BLOCK box keeps the
   old block-row path rather than being mis-nested, since real CSS's
   block-in-inline box split is not implemented (inline-flow-candidate?);
   a non-normal `white-space` keeps the old path (inline-flow-candidate?);
   inline padding/border/margin are not applied and a wrapped inline box
   reports one union `:node` box (inline-owner-ops); `vertical-align`
   other than the baseline default is not modeled (inline-line-metrics);
   and a LONE inline child deliberately stays on the pre-existing
   layout-text path, byte for byte (inline-runs). Two older, narrower
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
   text-decoration text-align text-transform white-space text-overflow text]
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
                measure-text (text-lines-measured measure content-w text)
                :else (text-lines char-w content-w text))
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
   indented every paragraph sideways as well."
  {:p 1.0 :blockquote 1.0 :ul 1.0 :ol 1.0 :dl 1.0 :pre 1.0 :figure 1.0
   :h1 0.67 :h2 0.83 :h3 1.0 :h4 1.33 :h5 1.67 :h6 2.33})

(def ^:private ua-box-sides
  "Horizontal UA-stylesheet box values -- the list indent every browser
   applies (`ul, ol { padding-left: 40px }`) and a blockquote's own side
   margins. Horizontal-only, for the same reason ua-margin-scale is
   vertical-only."
  {:ul {:padding-left 40} :ol {:padding-left 40}
   :blockquote {:margin-left 40 :margin-right 40}
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
   (see draw-ops' `:measure-text`) needs a family it can actually measure."
  {:family "Arial" :size 13})

(def ^:private ua-control-box
  "UA padding and border for form controls, measured in Chrome: an
   `<input>` is `padding: 2px; border: 2px`, a `<button>` `padding: 6px;
   border: 2px`, a `<select>` `border: 1px`. Without them a control's box
   was its content width exactly, where a browser reports 8px more."
  {:input {:padding 2 :border 2}
   :textarea {:padding 2 :border 2}
   ;; a button's padding is NOT uniform: 6px each side, 1px top and bottom.
   ;; Measured in Chrome (h=21 = 13px content + 2 + 4 border), and only
   ;; expressible at all since the box model gained per-side values.
   :button {:padding 1 :padding-left 6 :padding-right 6 :border 2
            :line-height :font-size}
   :select {:padding 0 :border 1 :line-height :font-size}})

(defn- ua-control-box-for
  "The UA box for one control, by tag AND -- for `<input>` -- by type: a
   checkbox or radio is a bare 13x13 square with no padding and no border
   at all, where a text input has 2px of each. Measured in Chrome."
  [node]
  (let [tag (:tag node)]
    (if (and (= :input tag)
             (contains? #{"checkbox" "radio"}
                        (str/lower-case (str (or (get-in node [:attrs :type]) "text")))))
      ;; ...and its own margins: Chrome's UA sheet gives a checkbox/radio
      ;; `margin: 3px 3px 3px 4px`, which is the gap a reader sees between
      ;; the box and the label beside it.
      {:padding 0 :border 0 :margin-top 3 :margin-right 3
       :margin-bottom 3 :margin-left 4}
      (get ua-control-box tag))))

(def ^:private ua-padding
  "UA-stylesheet padding defaults, in the uniform form this engine's box
   model can express. Real Chrome ships `td, th { padding: 1px }`; without
   it every table cell was 2px short in each axis, which the conformance
   harness's geometry axis reported as td 6/29."
  {:td 1 :th 1})

(def ^:private ua-font-style
  {:em "italic" :i "italic" :cite "italic" :dfn "italic" :var "italic" :address "italic"})

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
   are."
  [node theme]
  (when-let [scale (get ua-margin-scale (:tag node))]
    (let [fs (or (parse-int (get-in node [:attrs :style/font-size]) nil)
                 (when-let [heading-scale (get ua-font-scale (:tag node))]
                   (long (* heading-scale (:font-size theme))))
                 (:font-size theme))]
      (long (* scale fs)))))

(defn- node-style [node theme]
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
   :box-sizing (or (style node :box-sizing) "content-box")
   :padding (parse-int (style node :padding)
                       (or (:padding (ua-control-box-for node))
                           (get ua-padding (:tag node))
                           (:padding theme)))
   ;; The DECLARED padding only -- author or UA -- with no theme fallback.
   ;; The theme's uniform padding is a host decoration, not CSS: letting it
   ;; widen a content-box `width` would make `div{width:50px}` occupy 58px
   ;; because of a styling choice the author never made.
   :padding/declared (parse-int (style node :padding) (get ua-padding (:tag node)))
   :padding-top (parse-int (style node :padding-top) nil)
   :padding-right (parse-int (style node :padding-right)
                             (:padding-right (ua-control-box-for node)))
   :padding-bottom (parse-int (style node :padding-bottom) nil)
   :padding-left (parse-int (style node :padding-left)
                            (or (:padding-left (ua-control-box-for node))
                                (get-in ua-box-sides [(:tag node) :padding-left])))
   :margin-top (parse-int (style node :margin-top)
                          (or (:margin-top (ua-control-box-for node))
                              (ua-margin-y node theme)))
   :margin-bottom (parse-int (style node :margin-bottom)
                             (or (:margin-bottom (ua-control-box-for node))
                                 (ua-margin-y node theme)))
   :margin-left (parse-int (style node :margin-left)
                           (or (:margin-left (ua-control-box-for node))
                               (get-in ua-box-sides [(:tag node) :margin-left])))
   :margin-right (parse-int (style node :margin-right)
                            (or (:margin-right (ua-control-box-for node))
                                (get-in ua-box-sides [(:tag node) :margin-right])))
   ;; Real CSS's `border-spacing` defaults to 2px in every browser: cells
   ;; are separated by it AND the table is inset by it on all four sides.
   ;; Measured against Chrome, its absence was the single reason table/tr
   ;; geometry never matched -- a 2-cell table reported 49x20 here against
   ;; the browser's 59x26, an exactly-4px-per-axis difference plus the cell
   ;; padding above.
   :border-spacing (parse-int (style node :border-spacing) 2)
   :margin (parse-int (style node :margin) 0)
   :border-width (parse-int (style node :border-width)
                            (get (ua-control-box-for node) :border 0))
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
                    ;; a control's UA `font:` shorthand resets line-height to
                    ;; normal, so an inherited page line-height never applies
                    ;; to it -- see layout-form-control's own note
                    (when (= :font-size (:line-height (ua-control-box-for node)))
                      (or (style node :font-size) (:size ua-control-font))))
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
   :white-space (or (style node :white-space) (when (= :pre (:tag node)) "pre"))
   :text-overflow (style node :text-overflow)
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
   :float (style node :float)
   ;; Set by measure-child on a FLEX or GRID item: such a box establishes
   ;; its own formatting context, so margins never collapse through it --
   ;; the same rule `overflow` triggers, but decided by the PARENT, which is
   ;; why it arrives as an attr rather than a declaration.
   :independent-fc? (boolean (attr node :kotoba/independent-fc))
   :overflow (style node :overflow)
   :scroll-top (parse-int (attr node :scroll-top) 0)
   :scroll-left (parse-int (attr node :scroll-left) 0)})

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

(defn- clamp-width
  "The :width counterpart to clamp-height's own shared min/max clamp --
   split out so flex-item-main-width's shrink-to-fit natural width (which
   never runs through resolve-width's own avail-width fallback base) still
   gets the same min-width/max-width treatment an explicit or avail-
   defaulted width already does."
  [st width]
  (let [width (if-let [mn (explicit-length (:min-width st))] (max width mn) width)
        width (if-let [mx (explicit-length (:max-width st))] (min width mx) width)]
    width))

(defn- content-inset
  [st]
  (+ (:padding st) (if (= "border-box" (:box-sizing st)) (:border-width st) 0)))

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
   has always had, plus the border when box-sizing is border-box."
  [st side]
  (+ (or (get st (keyword (str "padding-" (name side)))) (:padding st))
     (if (= "border-box" (:box-sizing st)) (:border-width st) 0)))

(defn- margin-side
  "One side's margin: the per-side value when present (author or UA), else
   the uniform `:margin`."
  [st side]
  (or (get st (keyword (str "margin-" (name side)))) (:margin st)))

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
  (let [declared (parse-int (:width st) nil)]
    (clamp-width st
                 (cond
                   (nil? declared) avail
                   (= "border-box" (:box-sizing st)) declared
                   :else (+ declared
                            (declared-inset-side st :left)
                            (declared-inset-side st :right))))))

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
  [st height]
  (let [height (if-let [mn (explicit-length (:min-height st))] (max height mn) height)
        height (if-let [mx (explicit-length (:max-height st))] (min height mx) height)]
    height))

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
   a string is a raw multiplier (or `normal`/anything else unparseable,
   which reasonably falls back to the theme default the exact same way an
   absent `line-height` always has). Before this fix, `line-height` was
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
      (string? raw) (if-let [multiplier (parse-dbl raw nil)]
                      (long (* multiplier font-size))
                      normal)
      :else normal))))

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

   Routing `fixed` through the SAME partition-flow/layout-absolute-
   children machinery `absolute` already uses is an honest, documented
   scope-cut: this engine has no separate scroll-independent viewport
   model (see the namespace docstring), so a `fixed` element is anchored
   against its nearest containing block exactly like `absolute` is,
   rather than the real viewport -- real fixed-to-viewport decoupling
   under scrolling is a deeper, deliberately out-of-scope behavior, the
   same kind of honest cut this file already makes elsewhere (e.g.
   `layout-absolute-children`'s own `hsl()`-hue-unit-scoping comparison).
   `position: sticky` is deliberately NOT included here -- its
   unscrolled default position is legitimately identical to normal
   flow, so leaving it in-flow is correct, not a gap, for a rendering
   engine with no real scroll-position-dependent re-layout."
  [theme child]
  (and (map? child) (contains? #{"absolute" "fixed"} (:position (node-style child theme)))))

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

(defn- cross-offset
  [align child-cross container-cross]
  (case align
    "center" (quot (- container-cross child-cross) 2)
    "flex-end" (- container-cross child-cross)
    0))

(defn- stretch-eligible-child?
  "True when `child` is a real element (not a bare text-string flex item --
   see measure-child's own identical map? check) with no explicit cross-
   dimension of its own (`:height` for a row container, `:width` for a
   column container) under a container whose `:align-items` resolves to
   `\"stretch\"` (node-style's own real-CSS default when unauthored -- see
   node-style). Real CSS's align-items default -- stretch -- was never
   actually implemented as a SIZE change here; cross-offset above only ever
   REPOSITIONS a child within the cross axis, so `\"stretch\"` (not handled
   by cross-offset's own case) silently fell through to the same zero-
   offset, zero-resize behavior as `\"flex-start\"`, confirmed via a direct
   REPL reproduction before touching source: two 300px-wide flex-row items,
   one with an explicit height of 40 and one with none, and NO align-items
   declared (real CSS's own default is stretch) -- the auto-height item
   stayed at its own tiny 8px content height instead of stretching to match
   its 40px sibling, exactly like Chrome/Firefox never would."
  [column? st child]
  (and (map? child)
       (= "stretch" (:align-items st))
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
  (assoc-in child [:attrs (keyword "style" (if column? "width" "height"))] px))

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
        phase2 (reduce
                (fn [{:keys [occupied placements cursor-row cursor-col] :as state} i]
                  (if-let [p (nth placements i)]
                    (assoc state
                           :cursor-row (:row-start p)
                           :cursor-col (:col-end p))
                    (loop [r cursor-row c cursor-col]
                      (cond
                        (>= c n-cols) (recur (inc r) 0)
                        (contains? occupied [r c])
                        (if (< (inc c) n-cols) (recur r (inc c)) (recur (inc r) 0))
                        :else
                        (let [wrap? (>= (inc c) n-cols)]
                          (assoc state
                                 :occupied (conj occupied [r c])
                                 :placements (assoc placements i {:col-start c :col-end (inc c)
                                                                  :row-start r :row-end (inc r)})
                                 :cursor-row (if wrap? (inc r) r)
                                 :cursor-col (if wrap? 0 (inc c))))))))
                (assoc phase1 :cursor-row 0 :cursor-col 0)
                idx-range)]
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
                              :line-height (resolve-line-height (:line-height st) font-size (or (:line-height inherited) (:line-height theme)))
                              :font-weight (or (:font-weight st) (:font-weight inherited))
                              :font-style (or (:font-style st) (:font-style inherited))
                              :font-family (or (:font-family st) (:font-family inherited))
                              :text-transform (or (:text-transform st) (:text-transform inherited)))
        text-box (:box (layout-node theme 0 0 flex-item-shrink-to-fit-measure-width opacity text-inherited text))]
    (+ (:w text-box) (* 2 (content-inset st)))))

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

(declare inline-fragments inline-tokens inline-flow-candidate? inline-inherited
         inline-max-content-width font-metrics)

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

          (contains? #{:input :textarea} tag)
          (let [input-type (str/lower-case (str (or (get-in child [:attrs :type]) "text")))]
            (if (contains? #{"checkbox" "radio"} input-type)
              13
              (+ (* char-w (parse-int (get-in child [:attrs :size]) inline-atomic-default-input-chars))
                 inset-x)))

          (= :select tag)
          ;; Widest option label -- a <select> is as wide as the longest
          ;; thing it can display. Read straight off each <option>'s own
          ;; text children rather than through option-label, which answers
          ;; a different question (the label for one selected VALUE).
          (let [labels (->> (:children child)
                            (filter #(and (map? %) (= :option (:tag %))))
                            (map #(->> (:children %) (filter string?) (str/join ""))))]
            (+ (* char-w (apply max 1 (map count labels))) inset-x))

          ;; A <button> and any other atomic element with no intrinsic
          ;; rule of its own shrink-wraps to its content, exactly as a flex
          ;; item does. Inlined rather than delegating to
          ;; flex-item-main-width, which now consults THIS function for
          ;; atomic tags -- delegating would recurse forever.
          ;; A <button>'s label is measured in the CONTROL font, not the
          ;; inherited page font -- the same rule that gives every control
          ;; its own metrics (ua-control-font). Measuring it with the page
          ;; font left a button ~14px narrow against the browser.
          (contains? form-control-tags tag)
          (+ inset-x
             (let [label (->> (:children child) (keep real-text-child) (str/join ""))]
               (if measure-text
                 (measure-text label font-size (:font-weight st) (:font-style st) (:font-family st))
                 (* (count label) char-w))))

          :else
          (let [cs (:children child)]
            (cond
              (and (= 1 (count cs)) (string? (first cs)))
              (flex-item-natural-text-width theme opacity inherited st (first cs))

              ;; MIXED inline content counts too: `<button>save <b>now</b>
              ;; </button>` fell through to the container width, so a button
              ;; with any markup in its label swallowed the whole line and
              ;; pushed the text after it onto the next one.
              (and (seq cs) (every? #(inline-flow-candidate? theme %) cs))
              (inline-max-content-width theme content-w opacity inherited st cs)

              :else content-w)))]
    (max 0 (min content-w natural))))

(defn- inline-max-content-width
  "The width an inline run would occupy on ONE line -- real CSS's
   max-content size for a box whose children are all inline-level.

   Reuses the inline machinery rather than approximating: the same
   fragments, the same tokenizer (so whitespace collapses exactly as it
   will when the run is really laid out), and the same per-character
   measurement the line breaker uses."
  [theme content-w opacity inherited st children]
  (let [inherited (inline-inherited inherited st)
        tokens (inline-tokens (inline-fragments theme inherited opacity content-w children))
        measure-text (:measure-text theme)
        w-of (fn [text style]
               (if measure-text
                 (measure-text text (:font-size style) (:font-weight style)
                               (:font-style style) (:font-family style))
                 (* (count text) (long (* 0.6 (:font-size style 14))))))]
    (reduce (fn [total t]
              (case (:kind t)
                :break total
                :atomic (+ total (:w t))
                (+ total
                   (w-of (:text t) (:style t))
                   (if (:space-before? t) (w-of " " (or (:space-style t) (:style t))) 0))))
            (* 2 (content-inset st))
            tokens)))

(defn- flex-item-main-width
  "Real CSS flex-basis:auto (the default) falls back to an item's own
   explicit width if set, else shrink-wraps to its own preferred
   (max-content) width -- NOT resolve-width's own block-default fallback
   to the full available width, which is only correct for an ordinary
   block child (previously applied uniformly to flex children too,
   confirmed via direct REPL reproduction: two unstyled <button> flex
   children each rendered at the FULL flex container width instead of
   shrink-wrapping to their own short labels, ballooning the container
   itself to fit them). Only handles the single-text-child leaf shape
   (see flex-item-natural-text-width) -- a flex item with more complex
   nested content (multiple children, or a single child that is itself
   an element) falls back to the pre-existing fill-available-width
   behavior, an honest, disclosed scope-cut rather than a half-correct
   guess. Clamps the natural width to both min/max-width AND whatever
   main-axis space is actually available, so an overly-wide label still
   shrinks to fit rather than overflowing un-shrunk. Deliberately does
   not implement flex-grow/flex-shrink/an explicit flex-basis -- with the
   real default flex-grow:0, an item simply stays at this natural size
   regardless (leftover main-axis space is real CSS's own default
   behavior too, governed by justify-content), an honest, separate
   scope-cut."
  [theme content-w opacity inherited child st]
  (let [cs (:children child)
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
                  (inline-max-content-width theme content-w opacity inherited st cs)

                  :else content-w)]
    (min content-w (clamp-width st natural))))

(defn- measure-child
  [theme content-w opacity inherited child shrink-to-fit?]
  (let [child (if (map? child)
                (assoc-in child [:attrs :kotoba/independent-fc] true)
                child)
        child-avail (if (map? child)
                       (let [st (node-style child theme)]
                         (if (and shrink-to-fit? (not (:width st)))
                           (flex-item-main-width theme content-w opacity inherited child st)
                           (resolve-width st content-w)))
                       content-w)]
    (layout-node theme 0 0 child-avail opacity inherited child)))

(defn- layout-flex-wrap-row
  [theme cx cy cw opacity inherited st in-flow measured]
  (let [gap (:gap st)
        main-sizes (mapv #(:w (:box %)) measured)
        rows-idx (pack-rows main-sizes gap cw)
        row-cross-sizes (mapv (fn [idxs] (apply max 0 (mapv #(:h (:box (nth measured %))) idxs))) rows-idx)
        row-cross-offsets (loop [i 0 pos 0 offsets []]
                             (if (= i (count rows-idx))
                               offsets
                               (recur (inc i) (+ pos (nth row-cross-sizes i) gap) (conj offsets pos))))
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
                                (if (stretch-eligible-child? false st child)
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
                                   child-cross (:h (:box m))
                                   c-off (cross-offset (:align-items st) child-cross row-cross-size)
                                   dx (+ cx off)
                                   dy (+ cy row-y c-off)]
                               (translate-ops dx dy (:draw m))))
                           idxs offs)))
               rows-idx row-cross-offsets row-cross-sizes)
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
        measured (mapv #(measure-child theme cw opacity inherited % (not column?)) in-flow)]
    (if wrap?
      (let [{:keys [draws cross-total]} (layout-flex-wrap-row theme cx cy cw opacity inherited st in-flow measured)
            node-h (or (resolve-height st) (+ cross-total (* 2 inset)))]
        {:box-w w :box-h node-h :draws draws})
      (let [main-sizes (mapv (fn [m] (if column? (:h (:box m)) (:w (:box m)))) measured)
            cross-sizes (mapv (fn [m] (if column? (:w (:box m)) (:h (:box m)))) measured)
            auto-cross (if (seq cross-sizes) (apply max 0 cross-sizes) 0)
            cross-content (or (explicit-length (if column? (:width st) (:height st))) auto-cross)
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
                             (if column? auto-main cw))
            ;; align-items:stretch pass -- see stretch-eligible-child?/
            ;; force-cross-size. Deliberately AFTER cross-content is
            ;; determined from the ORIGINAL (unstretched) measurements,
            ;; matching real flexbox's own algorithm order (the flex line's
            ;; cross size is settled first, from the tallest natural item or
            ;; the container's own explicit size; only THEN do stretch-
            ;; eligible items get resized to fill it -- a stretched item
            ;; never feeds back into cross-content's own computation). Main-
            ;; axis sizes (main-sizes/offsets, already computed above) are
            ;; unaffected by this: injecting a cross-dimension never touches
            ;; resolve-width/flex-item-main-width, confirmed via direct REPL
            ;; check.
            measured (mapv (fn [child m]
                              (if (stretch-eligible-child? column? st child)
                                (measure-child theme cw opacity inherited
                                               (force-cross-size column? cross-content child)
                                               (not column?))
                                m))
                            in-flow measured)
            offsets (place-main-axis (:justify-content st) main-sizes gap main-content)
            draws (mapcat
                   (fn [m off]
                     (let [child-cross (if column? (:w (:box m)) (:h (:box m)))
                           c-off (cross-offset (:align-items st) child-cross cross-content)
                           dx (if column? (+ cx c-off) (+ cx off))
                           dy (if column? (+ cy off) (+ cy c-off))]
                       (translate-ops dx dy (:draw m))))
                   measured offsets)
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
            node-w w]
        {:box-w node-w :box-h node-h :draws (vec draws)}))))

;; ---- grid layout ----

;; ---- table layout ----

(def ^:private table-row-group-tags
  "The wrappers a real HTML parser puts between `<table>` and its `<tr>`s.
   `<tbody>` in particular is INSERTED by the parser even when the author
   never wrote it, so a table layout that only looked at direct `<tr>`
   children of `<table>` would find no rows at all on most real markup."
  #{:thead :tbody :tfoot})

(def ^:private table-cell-tags #{:td :th})

(defn- table-rows
  "Every `<tr>` under `node`, in document order, as `{:row <tr> :group
   <thead|tbody|tfoot or nil>}`.

   The rows are flattened out of their `<thead>`/`<tbody>`/`<tfoot>`
   wrappers -- a real HTML parser INSERTS `<tbody>` even when the author
   never wrote one, so looking only at direct `<tr>` children finds no rows
   at all on most real markup -- but each row REMEMBERS its group, so
   layout-table can still emit a box for the group itself. A row group with
   no box of its own was measurable: the geometry axis of the conformance
   harness reported `tbody 0/9`, because the browser has a box there and
   this engine had nothing to match it with."
  [node]
  (vec (mapcat (fn [child]
                 (cond
                   (not (map? child)) nil
                   (= :tr (:tag child)) [{:row child :group nil}]
                   (contains? table-row-group-tags (:tag child))
                   (for [r (:children child)
                         :when (and (map? r) (= :tr (:tag r)))]
                     {:row r :group child})
                   :else nil))
               (:children node))))

(defn- table-cells [row]
  (vec (filter #(and (map? %) (contains? table-cell-tags (:tag %))) (:children row))))

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

   Returns `[{:cell :row :col :colspan :rowspan :natural} ...]` in document
   order, where `:natural` is the cell's own shrink-to-fit width."
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
                             :natural (:w (:box (measure-child theme content-w opacity
                                                               inherited cell true)))})
                  (into occupied cells)
                  (+ col colspan)]))
             [acc occupied 0]
             (table-cells row))]
        [acc' occupied']))
    [[] #{}]
    (map-indexed vector rows))))

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

   Deliberately NOT implemented: `table-layout: fixed`, `<col>`/`<colgroup>`
   widths, and border collapsing."
  [content-w spacing assigns]
  (let [n-cols (apply max 0 (map #(+ (:col %) (:colspan %)) assigns))
        base (vec (for [col (range n-cols)]
                    (apply max 1
                           (for [a assigns
                                 :when (and (= 1 (:colspan a)) (= col (:col a)))]
                             (:natural a)))))
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

   Honest scope-cuts, all of them real CSS features this does NOT do:
   `colspan`/`rowspan` (a spanning cell occupies one column/row),
   `table-layout: fixed`, `<col>`/`<colgroup>` sizing, border collapsing,
   `border-spacing`, `<caption>` placement (a caption is laid out as an
   ordinary block row above the rows), row-group boxes, and vertical
   alignment within a cell. Before this existed a table rendered as one
   stacked column of every cell in document order -- the two conformance
   cases scored 0/2 -- so this is a large step from nothing, not a
   complete table implementation."
  [theme x y avail-width opacity inherited st node]
  (let [inset (content-inset st)
        avail-content (max 0 (- (resolve-width st avail-width) (* 2 inset)))
        caption (first (filter #(and (map? %) (= :caption (:tag %))) (:children node)))
        rows (table-rows node)
        assigns (assign-table-cells theme avail-content opacity inherited rows)
        base-widths (table-column-widths avail-content (:border-spacing st) assigns)
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
        ;; Real CSS: a table with `width: auto` is SHRINK-TO-FIT -- it is as
        ;; wide as its columns need, not as wide as its container. Filling
        ;; the container (what resolve-width does, correctly, for an
        ;; ordinary block) put every `<table>`, `<tr>` and row-group box in
        ;; the wrong place at once: the geometry axis reported table 0/9 and
        ;; tr 0/15 purely because of this one decision.
        spacing (:border-spacing st)
        n-cols (count widths)
        natural-w (+ (reduce + 0 widths) (* spacing (inc n-cols)))
        w (if (:width st)
            (resolve-width st avail-width)
            (min (resolve-width st avail-width) (+ natural-w (* 2 inset))))
        content-x (+ x (:margin st) inset)
        content-y (+ y (:margin st) inset)
        content-w (max 0 (- w (* 2 inset)))
        col-offsets (vec (reductions (fn [acc cw] (+ acc cw spacing))
                                     spacing
                                     widths))
        caption-layout (when caption
                         (layout-node theme content-x content-y content-w opacity inherited caption))
        rows-y0 (+ content-y spacing (if caption-layout (:h (:box caption-layout)) 0))
        ;; Row heights: single-row cells set their own row, then a
        ;; rowspan cell grows its LAST row if the rows it covers cannot
        ;; already hold it -- the same shortfall rule colspan uses across
        ;; columns.
        n-rows (count rows)
        laid-cells (mapv (fn [a]
                           (let [cw (+ (reduce + 0 (map #(nth widths % 0)
                                                        (range (:col a) (+ (:col a) (:colspan a)))))
                                       (* spacing (dec (:colspan a))))
                                 m (layout-node theme 0 0 cw opacity inherited (:cell a))]
                             (assoc a :w cw :h (:h (:box m)) :draw (:draw m))))
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
         (fn [{:keys [draws groups]} {:keys [row-idx row group]}]
           (let [row-y (+ rows-y0 (nth row-offsets row-idx 0))
                 row-h (nth row-heights row-idx 0)
                 rst (node-style row theme)
                 row-op (merge {:draw/op :node :id (:node/id row) :tag :tr
                                :x (+ content-x spacing) :y row-y
                                :w (max 0 (- content-w (* 2 spacing))) :h row-h
                                :class (attr row :class) :listeners (listeners row)
                                :opacity opacity}
                               (style-passthrough rst))
                 row-bg (when-let [bg (:background rst)]
                          [{:draw/op :rect :x (+ content-x spacing) :y row-y
                            :w (max 0 (- content-w (* 2 spacing))) :h row-h
                            :color bg :tag :tr :opacity opacity}])
                 cells (filter #(= row-idx (:row %)) laid-cells)
                 cell-draws (mapcat
                             (fn [c]
                               ;; A table cell's UA default is
                               ;; `vertical-align: middle`, so its content
                               ;; is centred in the cell box -- which is
                               ;; what makes a `rowspan` cell sit BETWEEN
                               ;; the rows it covers rather than at the top
                               ;; of the first one. Measured: the browser
                               ;; renders `tall` (rowspan 2) on its own line
                               ;; between `a` and `b`, where this engine put
                               ;; it beside `a`.
                               (let [cell-h (+ (reduce + 0 (map #(nth row-heights % 0)
                                                                (range (:row c) (+ (:row c) (:rowspan c)))))
                                               (* spacing (dec (:rowspan c))))
                                     dy (max 0 (quot (- cell-h (:h c)) 2))
                                     cell-id (:node/id (:cell c))]
                                 (->> (translate-ops (+ content-x (nth col-offsets (:col c) 0))
                                                     (+ row-y dy)
                                                     (:draw c))
                                      ;; the cell's OWN box spans every row
                                      ;; it covers, even though its content
                                      ;; is centred inside that box
                                      (mapv (fn [op]
                                              (if (and (= :node (:draw/op op))
                                                       (= cell-id (:id op)))
                                                (assoc op :y row-y :h cell-h)
                                                op))))))
                             cells)]
             {:draws (vec (concat draws row-bg [row-op] cell-draws))
              :groups (if group
                        (update groups group
                                (fn [g] {:y (min (:y g row-y) row-y)
                                         :h (+ (:h g 0) row-h spacing)}))
                        groups)}))
         {:draws [] :groups {}}
         (map-indexed (fn [i r] (assoc r :row-idx i)) rows))
        height (+ (reduce + 0 row-heights) (* spacing (count row-heights)))
        group-ops (mapv (fn [[g {:keys [y h]}]]
                          (merge {:draw/op :node :id (:node/id g) :tag (:tag g)
                                  :x (+ content-x spacing) :y y
                                  :w (max 0 (- content-w (* 2 spacing))) :h (max 0 (- h spacing))
                                  :class (attr g :class) :listeners (listeners g)
                                  :opacity opacity}
                                 (style-passthrough (node-style g theme))))
                        groups)
        table-h (clamp-height st (+ (- rows-y0 y) height inset))
        table-w w]
    {:box {:x x :y y :w table-w :h table-h}
     :draw (vec (concat
                 (or (box-shadow-ops st x y table-w table-h opacity) [])
                 (when-let [bg (default-bg :table st theme)]
                   [{:draw/op :rect :x x :y y :w table-w :h table-h :color bg :tag :table :opacity opacity}])
                 (or (border-ops st x y table-w table-h opacity) [])
                 (or (outline-ops st x y table-w table-h opacity) [])
                 [(merge {:draw/op :node :id (:node/id node) :tag :table
                          :x x :y y :w table-w :h table-h
                          :class (attr node :class) :listeners (listeners node)
                          :opacity opacity}
                         (style-passthrough st))]
                 (:draw caption-layout)
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
                            (measure-child theme item-w opacity inherited child false)))
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
  [st]
  (let [left (explicit-length (:left st))
        right (explicit-length (:right st))
        top (explicit-length (:top st))
        bottom (explicit-length (:bottom st))]
    [(cond left left right (- right) :else 0)
     (cond top top bottom (- bottom) :else 0)]))

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

(defn- inline-atomic-element?
  "True for an element that participates in a line as one unbreakable box:
   a replaced/form-control tag (inline-atomic-tags), or ANY element an
   author gives `display: inline-block`.

   `inline-block` is exactly this concept in CSS — a box that lays its own
   children out internally as a block, but sits in its parent's line like a
   word — so it needs no separate machinery here, only admission to the
   same atomic path. Before this, an `inline-block` span fell through to a
   block row and broke the sentence around it in two."
  [theme child]
  (and (map? child)
       (= :element (:node/type child))
       (or (contains? inline-atomic-tags (:tag child))
           (= "inline-block" (:display (node-style child theme))))))

(defn- inline-level-element?
  "True when `child` is an element this file will flow into a line box:
   inline-level by author `display: inline` or by inline-level-tags UA
   default, statically positioned, and actually rendered.

   `position` must be `static`: a `relative`/`absolute`/`fixed` inline box
   would need its own offset/anchoring treatment inside the line, which
   layout-children-block/layout-absolute-children already implement for
   block rows — routing it through the inline path instead would silently
   drop that, so a positioned element always stays on the existing path."
  [theme child]
  (and (map? child)
       (= :element (:node/type child))
       (not (non-rendered-tag? (:tag child)))
       (let [st (node-style child theme)]
         (and (= "static" (:position st))
              (not= "none" (:display st))
              (if (:display st)
                (= "inline" (:display st))
                (contains? inline-level-tags (:tag child)))))))

(defn- inline-flow-text?
  [child]
  (or (some? (real-text-child child))
      (generated-node? child)))

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
    (and (map? child)
         (= :element (:node/type child))
         (contains? #{"left" "right"} (:float (node-style child theme))))
    false

    ;; An atomic inline (an <img>/<input>/<button>/<select>/<textarea>) has
    ;; no subtree requirement: whatever is inside it is laid out by its own
    ;; box, not flattened into this line, so `block-in-inline` cannot arise.
    ;; It still has to be statically positioned and actually displayed.
    (inline-atomic-element? theme child)
    (let [st (node-style child theme)]
      (and (= "static" (:position st))
           (not= "none" (:display st))
           (contains? #{nil "inline" "inline-block"} (:display st))))

    (inline-level-element? theme child)
    (let [st (node-style child theme)]
      (and (contains? #{nil "normal"} (:white-space st))
           (every? (fn [c]
                     (or (inline-flow-text? c)
                         (and (map? c)
                              (= :element (:node/type c))
                              (non-rendered-tag? (:tag c)))
                         (inline-flow-candidate? theme c)))
                   (:children child))))

    :else false))

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
           :line-height (resolve-line-height (:line-height st) font-size (:line-height inherited)
                                            (boolean (:line-height/explicit? inherited)))
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
   the fragment sits inside, each `{:idx <n> :node <element> :st <style>}`.
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
  (let [counter (atom 0)]
    (letfn [(walk [items inherited opacity owners acc]
              (reduce
               (fn [acc child]
                 (cond
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
                         (if (contains? form-control-tags (:tag child))
                           (let [{:keys [descent]} (font-metrics theme (parse-int (:font-size st) nil)
                                                                 (:font-weight st) (:font-style st)
                                                                 (:font-family st))]
                             (max 0 (- h (or (:padding-bottom st) (:padding st) 0)
                                       (:border-width st) descent (margin-side st :bottom))))
                           h)]
                     (conj acc {:kind :atomic
                                :w (+ (:w box) ml mr) :h h :baseline-offset baseline-offset
                                :ml ml :mt mt :draw draw
                                :owners owners :opacity opacity}))

                   (generated-node? child)
                   (conj acc {:kind :text
                              :text (:generated/text child)
                              :style (inline-inherited inherited (:generated/style child))
                              :owners owners
                              :opacity opacity})

                   (some? (real-text-child child))
                   (conj acc {:kind :text
                              :text (real-text-child child)
                              :style inherited
                              :owners owners
                              :opacity opacity})

                   (and (map? child) (= :element (:node/type child)))
                   (if (non-rendered-tag? (:tag child))
                     acc
                     (let [st (node-style child theme)]
                       (if (= "none" (:display st))
                         acc
                         (let [opacity (* opacity (:opacity st)
                                          (if (contains? #{"hidden" "collapse"} (:visibility st)) 0 1))
                               inherited (inline-inherited inherited st)
                               owners (conj owners {:idx (swap! counter inc) :node child :st st})]
                           (if (= :br (:tag child))
                             (conj acc {:kind :break :style inherited :owners owners :opacity opacity})
                             (walk (with-generated-content
                                     child
                                     (with-implicit-list-markers
                                       child
                                       (with-details-visibility child (:children child))))
                                   inherited opacity owners acc))))))

                   :else acc))
               acc
               items))]
      (walk items inherited opacity [] []))))

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
                                           :opacity (:opacity fr)}))
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

          ;; An atomic inline never merges with a neighbouring piece and is
          ;; never split: it wraps to the next line whole, or overflows
          ;; alone, exactly like an over-wide single word.
          (= :atomic (:kind t))
          (let [sep (if (and (seq pieces) (:space-before? t))
                      (w-of " " (or (:space-style t)
                                    {:font-size (or (:font-size (:style (peek pieces))) 14)}))
                      0)
                piece (fn [x] (assoc (select-keys t [:owners :opacity :draw :h :ml :mt :baseline-offset])
                                     :kind :atomic :x x :w (:w t)))]
            (if (and (seq pieces) (> (+ x sep (:w t)) content-w))
              (recur (rest ts) (:w t) [(piece 0)] (flush lines pieces x nil))
              (recur (rest ts) (+ x sep (:w t)) (conj pieces (piece (+ x sep))) lines)))

          :else
          (let [st (:style t)
                word (:text t)
                ww (w-of word st)
                sep (if (and (seq pieces) (:space-before? t))
                      (w-of " " (or (:space-style t) st))
                      0)]
            (if (and (seq pieces) (> (+ x sep ww) content-w))
              (recur (rest ts) ww
                     [{:text word :style st :owners (:owners t) :opacity (:opacity t) :x 0 :w ww}]
                     (flush lines pieces x st))
              (let [last-piece (peek pieces)
                    merge? (and last-piece
                                (= (:style last-piece) st)
                                (= (:owners last-piece) (:owners t))
                                (= (:opacity last-piece) (:opacity t)))
                    x' (+ x sep ww)]
                (recur (rest ts) x'
                       (if merge?
                         (conj (pop pieces)
                               (assoc last-piece
                                      :text (str (:text last-piece) (if (pos? sep) " " "") word)
                                      :w (- x' (:x last-piece))))
                         (conj pieces {:text word :style st :owners (:owners t)
                                       :opacity (:opacity t) :x (+ x sep) :w ww}))
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
  "One line box's own height and baseline offset. Height is the tallest
   `line-height` among the line's own pieces (real CSS's own
   max-of-the-inline-boxes line box height, minus the strut/half-leading
   subtleties this engine does not model); the baseline sits one
   MAX-font-size below the line top.

   That baseline rule is what makes mixed font sizes on one line line up
   the way a reader expects: kotoba-lang/dom-gpu's WebGL/WebGPU hosts both
   paint a `:text` op at `(+ y font-size)` (a real, checked convention —
   see webgl.cljs' `:text` case), i.e. `:y` is the top of the em box and
   `y + font-size` is the baseline, so giving each piece
   `y = baseline - its own font-size` makes every piece on the line share
   ONE baseline instead of one top edge. For a line whose pieces all share
   one font size (the overwhelmingly common case, and every pre-existing
   single-text-child layout) this reduces EXACTLY to `y = line-top`,
   which is byte-for-byte what layout-text already emits.

   An ATOMIC inline (an `<img>`/`<input>`/`<button>`) contributes its whole
   BOX HEIGHT as ascent, because real CSS `vertical-align: baseline` puts a
   replaced box's bottom margin edge on the text baseline. A 40px-tall
   button on a 14px line therefore pushes the baseline down to 40 and grows
   the line box to fit, rather than being clipped by it or overlapping the
   line above."
  [line inherited theme]
  (let [pieces (:pieces line)
        fallback-fs (or (:font-size (:style line)) (:font-size inherited) (:font-size theme))
        fallback-lh (or (:line-height (:style line)) (:line-height inherited) (:line-height theme))
        ;; Each inline box occupies [baseline - ascent - halfLeading,
        ;; baseline + descent + halfLeading], where halfLeading is
        ;; (line-height - (ascent + descent)) / 2 and CAN BE NEGATIVE -- a
        ;; declared line-height smaller than the font's own content area
        ;; makes the box overflow the line rather than grow it. The line box
        ;; is the union of those spans, which is what makes a 24px run
        ;; inside a `line-height: 20px` container report 24 in a browser
        ;; while the line-height rule alone says 20.
        spans (for [p pieces
                    :when (not= :atomic (:kind p))
                    :let [st (:style p)
                          fs (or (:font-size st) fallback-fs)
                          lh (or (:line-height st) fallback-lh fs)
                          {:keys [ascent descent]} (font-metrics theme fs (:font-weight st)
                                                                 (:font-style st) (:font-family st))
                          half (/ (- lh (+ ascent descent)) 2)]]
                [(+ ascent half) (+ descent half)])
        atomic-hs (keep #(when (= :atomic (:kind %)) (or (:baseline-offset %) (:h %))) pieces)
        atomic-below (keep #(when (= :atomic (:kind %))
                              (- (:h %) (or (:baseline-offset %) (:h %))))
                           pieces)
        strut (let [{:keys [ascent descent]} (font-metrics theme fallback-fs nil nil nil)
                    half (/ (- (or fallback-lh fallback-fs) (+ ascent descent)) 2)]
                [(+ ascent half) (+ descent half)])
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
    (if (:font-metrics theme)
      {:h (long (Math/ceil (+ above below)))
       :baseline (long (Math/ceil above))}
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
        fragments (inline-fragments theme inherited opacity inner-w items)
        lines (inline-line-breaker theme inner-w (inline-tokens fragments))
        text-align (:text-align inherited)]
    (if (empty? lines)
      {:draw [] :h 0}
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
                [line-draws rects]
                (reduce
                 (fn [[draws rects] piece]
                   (if (= :atomic (:kind piece))
                     ;; Atomic inline: the element was already laid out at
                     ;; the origin by inline-fragments, so placing it is a
                     ;; translate -- its bottom edge onto the baseline, the
                     ;; real CSS `vertical-align: baseline` default.
                     (let [px (+ base-x (:x piece) (:ml piece 0))
                           py (+ (- baseline (or (:baseline-offset piece) (:h piece)))
                                 (:mt piece 0))
                           rects (reduce (fn [rects owner]
                                           (update rects (:idx owner)
                                                   (fn [entry]
                                                     (-> (or entry {:node (:node owner) :st (:st owner)
                                                                    :opacity (:opacity piece) :fragments []})
                                                         (update :fragments conj
                                                                 {:x px :y py :w (:w piece) :h (:h piece)})))))
                                         rects
                                         (:owners piece))]
                       [(into draws (translate-ops px py (:draw piece))) rects])
                     (let [st (:style piece)
                           px (+ base-x (:x piece))
                           py (- baseline (:font-size st))
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
                           ;; An inline box's own height is the font's
                           ;; CONTENT AREA (~1.2em), vertically centered in
                           ;; the line box by half-leading -- NOT the line
                           ;; box itself, which is what this reported
                           ;; before. Measured against Chrome: a 14px <b>
                           ;; on a 20px line reports y=1 h=18, where this
                           ;; engine reported y=0 h=20, so every inline
                           ;; element's box missed on both axes at once.
                           {:keys [ascent descent]} (font-metrics theme (:font-size st)
                                                                   (:font-weight st) (:font-style st)
                                                                   (:font-family st))
                           content-h (if (:font-metrics theme)
                                       (+ ascent descent)
                                       (long (* 1.2 (:font-size st))))
                           ;; the box sits ON the baseline, ascent above it
                           half-leading (if (:font-metrics theme)
                                          (max 0 (- baseline y ascent))
                                          (max 0 (quot (- line-h content-h) 2)))
                           rects (reduce (fn [rects owner]
                                           (update rects (:idx owner)
                                                   (fn [entry]
                                                     (-> (or entry {:node (:node owner) :st (:st owner)
                                                                    :opacity (:opacity piece) :fragments []})
                                                         (update :fragments conj
                                                                 {:x px :y (+ y half-leading)
                                                                  :w (:w piece) :h content-h})))))
                                         rects
                                         (:owners piece))]
                       [(cond-> draws
                          shadow-op (conj shadow-op)
                          true (conj main-op))
                        rects])))
                 [[] rects]
                 (:pieces line))]
            (recur (rest ls) (+ y line-h) (into text-draws line-draws)
                   ;; the <br>'s own zero-width box, at the end of the line
                   ;; it terminates
                   (reduce (fn [rects owner]
                             (update rects (:idx owner)
                                     (fn [entry]
                                       (-> (or entry {:node (:node owner) :st (:st owner)
                                                      :opacity opacity :fragments []})
                                           ;; same content-area box every
                                           ;; other inline element reports
                                           (update :fragments conj
                                                   (let [ch (long (* 1.2 (or (:font-size (:style line))
                                                                             (:font-size inherited)
                                                                             (:font-size theme))))]
                                                     {:x (+ base-x (:w line))
                                                      :y (+ y (max 0 (quot (- line-h ch) 2)))
                                                      :w 0 :h ch}))))))
                           rects
                           (:break-owners line))))
          {:draw (into (inline-owner-ops theme rects) text-draws)
           :h (+ (- y content-y) padding)})))))

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
   left alone and keeps the pre-existing block-row fallback."
  [theme children]
  (let [block-child? (fn [c] (and (map? c)
                                  (= :element (:node/type c))
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
  "Groups `children` into layout entries: each maximal run of TWO OR MORE
   adjacent inline-flow-candidate? children becomes one
   `{:inline/run [...]}` entry (laid out by layout-inline-run), everything
   else passes through as the plain child it already was.

   The two-or-more threshold is deliberate. A LONE inline child already
   occupies its own line either way, so routing it through the inline path
   would change nothing a reader can see while changing every existing
   single-text-child geometry (and every test asserting it) for no benefit
   — including the single most common case in this whole engine, a block
   whose only child is one text node, which stays on layout-text's exact
   pre-existing path, byte for byte. Inline flow only engages where the
   old behavior was genuinely WRONG: two or more inline things that real
   CSS puts on one line and this file used to stack."
  ([theme children] (inline-runs theme children 2))
  ([theme children min-items]
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
       (partition-by #(inline-flow-candidate? theme %))
       (mapcat (fn [group]
                 (if (and (>= (count group) min-items)
                          (inline-flow-candidate? theme (first group)))
                   [{:inline/run (vec group)}]
                   group)))
       vec)))

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
   honest, documented scope-cut left for a future cycle."
  ([theme content-x content-y content-w opacity inherited children]
   (layout-children-block theme content-x content-y content-w opacity inherited children false))
  ([theme content-x content-y content-w opacity inherited children collapse-through?]
  (let [;; ---- floats ----
        ;; A `float: left|right` box is taken out of normal flow, placed
        ;; against its container's corresponding edge, and NARROWS the
        ;; content beside it. This engine had no float concept at all: a
        ;; floated span simply stayed inline where it was written, so a
        ;; right-floated badge sat at the START of the text instead of the
        ;; end (measured: x=0 against the browser's 233 in a 240px box).
        ;;
        ;; Bounded v1, documented rather than pretended: floats are placed
        ;; at the TOP of their container (the overwhelmingly common
        ;; authoring shape -- a float is written before the text it should
        ;; sit beside), and the band they exclude applies to content within
        ;; their own height. Floats appearing after other content, floats
        ;; that stack vertically when they do not fit side by side, and
        ;; `clear` are NOT implemented.
        floated? (fn [c] (and (map? c) (= :element (:node/type c))
                              (contains? #{"left" "right"} (:float (node-style c theme)))))
        floats (filterv floated? children)
        children (filterv (complement floated?) children)
        laid-floats
        (first (reduce (fn [[acc left-x right-x] f]
                         (let [fst (node-style f theme)
                               m (measure-child theme content-w opacity inherited f true)
                               fw (:w (:box m))
                               fh (:h (:box m))
                               right? (= "right" (:float fst))
                               x (if right? (- right-x fw) left-x)]
                           [(conj acc {:w fw :h fh :right? right?
                                       :draw (translate-ops x content-y (:draw m))})
                            (if right? left-x (+ left-x fw))
                            (if right? (- right-x fw) right-x)]))
                       [[] content-x (+ content-x content-w)]
                       floats))
        band {:h (apply max 0 (map :h laid-floats))
              :left (reduce + 0 (map :w (remove :right? laid-floats)))
              :right (reduce + 0 (map :w (filter :right? laid-floats)))}
        float-draws (vec (mapcat :draw laid-floats))]
  (loop [remaining (inline-runs theme children
                                ;; With a float band present even a LONE
                                ;; inline child must flow as a run: it has
                                ;; to sit beside the float in the narrowed
                                ;; band rather than take a full-width block
                                ;; row of its own (measured: the text beside
                                ;; a left float reported x=0 w=800 against
                                ;; the browser's x=7 w=70).
                                (if (pos? (:h band)) 1 2))
         y content-y draws float-draws
         height 0 prev-mb 0 first? true]
    (if-let [child (first remaining)]
      (if-let [run (and (map? child) (:inline/run child))]
        (let [in-band? (< (- y content-y) (:h band))
              {:keys [draw h]} (layout-inline-run theme
                                                  (+ content-x (if in-band? (:left band) 0))
                                                  y
                                                  (- content-w (if in-band? (+ (:left band) (:right band)) 0))
                                                  opacity inherited run)
              advance (+ h (:gap theme))]
          (recur (rest remaining) (+ y advance) (into draws draw) (+ height advance) 0 false))
        (let [cst (when (map? child) (node-style child theme))
              mt (if cst (margin-side cst :top) 0)
              mb (if cst (margin-side cst :bottom) 0)
              ml (if cst (margin-side cst :left) 0)
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
              gap-before (cond
                           (and first? collapse-through?) 0
                           first? mt
                           :else (max prev-mb mt))
              mr (if cst (margin-side cst :right) 0)
              child-y (+ y gap-before)
              {:keys [box draw]} (layout-node theme (+ content-x ml) child-y
                                              (max 0 (- content-w ml mr))
                                              opacity inherited child)
              child-h (:h box)
              advance (+ gap-before child-h (:gap theme))
              draw (if (and cst (= "relative" (:position cst)))
                     (let [[dx dy] (relative-offset cst)]
                       (translate-ops dx dy draw))
                     draw)]
          (recur (rest remaining) (+ y advance) (into draws draw) (+ height advance) mb false)))
      {:draw draws
       ;; the container is at least as tall as its floats -- real CSS only
       ;; does this for a container that establishes a formatting context,
       ;; another documented simplification
       :h (max (:h band)
               (max 0 (+ (- height (:gap theme))
                         (if collapse-through? 0 prev-mb))))})))))

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
  [theme content-x content-y content-w content-h opacity inherited children]
  (let [placed (mapv (fn [child]
                        (let [cst (node-style child theme)
                              ;; An absolutely positioned box with
                              ;; `width: auto` is SHRINK-TO-FIT, not
                              ;; fill-the-container: real CSS sizes it to
                              ;; its own content. Measured against the
                              ;; browser, a corner-pinned label reported 800
                              ;; here against its 21 -- so it also covered
                              ;; the entire row it was pinned over.
                              m (measure-child theme content-w opacity inherited child true)
                              {:keys [w h]} (:box m)
                              left (explicit-length (:left cst))
                              right (explicit-length (:right cst))
                              top (explicit-length (:top cst))
                              bottom (explicit-length (:bottom cst))
                              dx (+ content-x (cond left left
                                                     right (- content-w w right)
                                                     :else 0))
                              dy (+ content-y (cond top top
                                                     bottom (- content-h h bottom)
                                                     :else 0))]
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
        ;; A control's content box is one FONT-SIZE tall, not one line-box:
        ;; measured, Chrome reports a 21px <input> = 13px content + 2px
        ;; padding + 2px border per side, where using the line-height gave
        ;; 24. A control is a replaced-ish box with its own metrics, not a
        ;; block of flowing text.
        ;; ...and the UA `font:` shorthand RESETS line-height to normal for
        ;; a control, so an inherited page line-height does not apply to it
        ;; either. The cascade has already folded the inherited value onto
        ;; this node by the time layout sees it, so the reset is applied
        ;; here unconditionally rather than by trying to tell inherited from
        ;; declared -- documented, and matching every measurement taken
        ;; against the browser.
        control-line-height control-font-size
        ;; content + padding + BORDER: with `box-sizing: content-box` (the
        ;; default) the border sits outside the content box in the vertical
        ;; axis too. Without it the control came out exactly one border
        ;; short on each side -- 17px against the browser's 21.
        h (clamp-height st (or (resolve-height st)
                               (+ control-line-height (* 2 inset)
                                  (if (= "border-box" (:box-sizing st))
                                    0
                                    (* 2 (:border-width st))))))
        value (attr node :value)
        checked (truthy-attr? (attr node :checked))
        input-type (str/lower-case (str (or (attr node :type) "text")))
        has-value? (boolean (seq (str value)))
        placeholder (attr node :placeholder)
        control-text (case tag
                       :select (option-label node value)
                       :input (if (= "checkbox" input-type)
                                (if checked "[x]" "[ ]")
                                (if has-value? (str value) (str placeholder)))
                       (if has-value? (str value) (str placeholder)))
        showing-placeholder? (and (not has-value?)
                                  (not= :select tag)
                                  (not= "checkbox" input-type)
                                  (some? placeholder))
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
             sel-ops (into sel-ops))}))

(defn- layout-block
  [theme x y avail-width opacity inherited st node]
  (let [w (resolve-width st avail-width)
        inset (content-inset st)
        inset-l (inset-side st :left)
        inset-r (inset-side st :right)
        inset-t (inset-side st :top)
        inset-b (inset-side st :bottom)
        ;; `x`/`y` are the BORDER-BOX origin the parent already placed this
        ;; box at, margins included -- layout-children-block owns a child's
        ;; margins because collapsing can only be decided between siblings.
        ;; Adding them again here double-counted every margin; invisible
        ;; while every margin was 0, immediately visible once the UA
        ;; stylesheet gave `<p>` a real one (a paragraph's own text sat
        ;; 14px below its own box).
        content-x (+ x inset-l)
        content-y (+ y inset-t)
        content-w (max 0 (- w inset-l inset-r))
        scroll-x (:scroll-left st)
        scroll-y (:scroll-top st)
        {:keys [in-flow out-of-flow]} (partition-flow theme (:children node))
        {:keys [draw h]} (layout-children-block theme (- content-x scroll-x) (- content-y scroll-y)
                                               content-w opacity inherited in-flow
                                               (and (zero? inset-t) (zero? inset-b)
                                                    (zero? (:border-width st))
                                                    ;; A box that establishes its own
                                                    ;; formatting context does NOT collapse
                                                    ;; margins with its children -- which is
                                                    ;; exactly why authors reach for
                                                    ;; `overflow: hidden` to contain them.
                                                    ;; Measured: a `<p>` inside an
                                                    ;; `overflow: hidden` div starts at y=14
                                                    ;; in the browser (its own margin intact)
                                                    ;; where this engine collapsed it out to
                                                    ;; y=0, and the container came out 28px
                                                    ;; short.
                                                    (contains? #{nil "visible"} (:overflow st))
                                                    (not (:independent-fc? st))))
        explicit-h (resolve-height st)
        ;; content + padding + BORDER, for the same reason resolve-width
        ;; adds it horizontally: with `box-sizing: content-box` the border
        ;; sits outside the content box in both axes. Without it every
        ;; bordered block came out two borders short.
        node-h (clamp-height st (or explicit-h
                                    (+ h inset-t inset-b
                                       (if (= "border-box" (:box-sizing st))
                                         0
                                         (* 2 (:border-width st))))))
        node-w w
        content-h (max 0 (- node-h (* 2 inset)))
        {above-draws :above below-draws :below} (layout-absolute-children theme content-x content-y content-w content-h opacity inherited out-of-flow)
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
     :draw (vec (concat box-shadow-draws rect border-draws outline-draws below-draws semantic clip-push draw clip-pop above-draws))}))

(defn layout-node
  ([node] (layout-node default-theme 0 0 320 1.0 {:color (:fg default-theme) :font-size (:font-size default-theme)
                                                  :line-height (:line-height default-theme)} node))
  ([theme x y avail-width opacity inherited node]
   (cond
     (nil? node)
     {:box {:x x :y y :w 0 :h 0} :draw []}

     (generated-node? node)
     (let [gstyle (:generated/style node)
           color (or (:color gstyle) (:color inherited))
           font-size (parse-int (:font-size gstyle) (:font-size inherited))
           line-height (resolve-line-height (:line-height gstyle) font-size (or (:line-height inherited) (:line-height theme)))
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
                    text-decoration text-align text-transform white-space text-overflow (:generated/text node)))

     (text-node? node)
     (layout-text theme x y avail-width opacity (:color inherited) (:font-size inherited) (:line-height inherited)
                  (:font-weight inherited) (:font-style inherited) (:font-family inherited)
                  {:x (:text-shadow-x inherited) :y (:text-shadow-y inherited)
                   :blur (:text-shadow-blur inherited) :color (:text-shadow-color inherited)}
                  (:text-decoration inherited)
                  (:text-align inherited) (:text-transform inherited) (:white-space inherited) (:text-overflow inherited) node)

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
                                                (or (:line-height inherited) (:line-height theme))
                                                (boolean (:line-height/explicit? inherited)))
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
               inherited (assoc inherited
                                :line-height/explicit? (boolean (or (:line-height st)
                                                                    (:line-height/explicit? inherited)))
                                :color color :font-size font-size :line-height line-height
                                :font-weight font-weight :font-style font-style :font-family font-family
                                :text-shadow-x text-shadow-x :text-shadow-y text-shadow-y
                                :text-shadow-blur text-shadow-blur :text-shadow-color text-shadow-color
                                :text-decoration text-decoration :text-align text-align
                                :text-transform text-transform :white-space white-space
                                :text-overflow text-overflow)
               tag (:tag node)
               children (with-generated-content node (with-implicit-list-markers node (with-details-visibility node (:children node))))]
           (cond
             (contains? #{:input :select :textarea} tag)
             (layout-form-control theme x y avail-width opacity st node)

             (or (= "table" (:display st))
                 (and (nil? (:display st)) (= :table tag)))
             (layout-table theme x y avail-width opacity inherited st (assoc node :children children))

             (= "flex" (:display st))
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

             (= "grid" (:display st))
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
   with no other call-site changes needed anywhere in that chain."
  ([tree] (draw-ops tree {}))
  ([tree opts]
   (let [theme (merge default-theme (:theme opts))
         inherited {:color (:fg theme) :font-size (:font-size theme)}]
     (:draw (layout-node theme (or (:x opts) 0) (or (:y opts) 0) (or (:width opts) 320) 1.0 inherited tree)))))

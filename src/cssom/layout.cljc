(ns cssom.layout
  "Box-model + flexbox + grid layout projection from a kotoba virtual DOM
   tree (kotoba.wasm.dom/tree) to renderer draw ops.

   Covers: padding/border/margin box model with min/max-width and
   content-box/border-box sizing; display:flex with flex-direction/
   flex-wrap/justify-content/align-items/gap; display:grid with
   grid-template-columns/grid-template-rows (fixed px + fr tracks, plus
   `repeat(<n>, <track>)` and `minmax(<px>, <px-or-1fr>)` composing over
   them) and per-item `grid-column`/`grid-row` explicit placement composing
   with auto-placement for everything else — see layout-grid for the exact
   subset and its documented limitations; position:relative/absolute with z-index
   stacking; opacity (multiplicatively inherited); background/
   background-color; borders; overflow+scroll-top/scroll-left clipping;
   form-control value/checked/selected-option-label projection; text input
   caret/selection; grid item explicit placement via `grid-column`/
   `grid-row` composing with auto-placement for everything else (see
   layout-grid/parse-grid-placement/place-grid-items for the exact subset);
   ::before/::after generated `content` (see
   with-generated-content) — cssom.core's cascade already resolves each
   element's ::before/::after style onto its :attrs (:pseudo/before /
   :pseudo/after, e.g. `{:content \"→ \" :color \"red\"}`); this namespace
   reads that and synthesizes a layout-only child that flows through the
   exact same text-wrapping/paint path (layout-text) real text already
   uses, positioned immediately before/after the element's real children.
   Real hosts can still swap this for text shaping/WebGPU buffers etc — the
   draw-ops data boundary is unchanged.

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
   hyphenation)."
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

(defn- layout-text
  "Word-wraps and lays out `text` as one or more :text draw-ops -- exactly
   the algorithm layout-node's real-DOM-text-node branch uses, factored out
   so generated ::before/::after content (see with-generated-content) can
   flow through the identical text-measurement/wrapping/paint path instead
   of a second, forked implementation. `color`/`font-size` are taken as
   explicit args (rather than read off `inherited` internally) so a
   pseudo-element's own resolved style can override either one while still
   falling back to whatever a real text child in the same spot would use."
  [theme x y avail-width opacity color font-size text]
  (let [line-height (:line-height theme)
        padding (:padding theme)
        char-w (long (* 0.6 font-size))
        content-w (max 0 (- avail-width (* 2 padding)))
        lines (text-lines char-w content-w text)
        max-line-w (apply max 0 (map #(* (count %) char-w) lines))
        w (min avail-width (+ max-line-w (* 2 padding)))
        h (+ (* (count lines) line-height) (* 2 padding))]
    {:box {:x x :y y :w w :h h}
     :draw (vec (map-indexed
                 (fn [i line]
                   {:draw/op :text :x (+ x padding) :y (+ y padding (* i line-height))
                    :text line :color color :font-size font-size :opacity opacity})
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
   :opacity (parse-dbl (style node :opacity) 1.0)
   :justify-content (or (style node :justify-content) "flex-start")
   :align-items (or (style node :align-items) "stretch")
   :flex-direction (or (style node :flex-direction) "row")
   :flex-wrap (or (style node :flex-wrap) "nowrap")
   :grid-template-columns (style node :grid-template-columns)
   :grid-template-rows (style node :grid-template-rows)
   :grid-column (style node :grid-column)
   :grid-row (style node :grid-row)
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

(defn- resolve-width
  [st avail]
  (let [base (parse-int (:width st) avail)
        base (if-let [mn (:min-width st)] (max base mn) base)
        base (if-let [mx (:max-width st)] (min base mx) base)]
    base))

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

(defn- parse-length-px
  "Parses a single px length or bare-integer token -- the two plain-length
   forms this file accepts everywhere a track size is expected -- to a
   pixel value, or nil if `tok` is neither. Used for minmax()'s `min`
   argument (always a plain length in this engine's honestly-scoped
   subset, never `fr`) and for a `max` argument that isn't `Nfr`."
  [tok]
  (cond
    (re-matches #"-?[0-9]*\.?[0-9]+px" tok) (parse-int (subs tok 0 (- (count tok) 2)) 0)
    (re-matches #"-?[0-9]*\.?[0-9]+" tok) (parse-int tok 0)
    :else nil))

(declare parse-track-token)

(defn- parse-track-list
  "Parses a `grid-template-columns`/`grid-template-rows` value into a vector
   of track specs, e.g. \"100px 1fr 2fr\" -> [{:type :fixed :size 100}
   {:type :fr :size 1} {:type :fr :size 2}].

   Deliberately a small, local parser rather than an extension to
   cssom.core's cascade — mirrors how this file already owns its own
   parse-int/parse-dbl numeric coercion instead of depending on cssom.core
   for it. cssom.core/parse-declarations (parse-style-value) passes any
   value that isn't a bare integer or a single `Npx` token through
   untouched as a raw string, so a multi-token track list like
   \"100px 1fr 2fr\" always arrives here exactly as the CSS author wrote it
   (verified against cssom.core/parse-style-value, which only special-cases
   a whole-value match of `-?\\d+` or `-?\\d+px`). A bare integer input
   (cssom.core coerces a lone single-track `Npx` value, e.g.
   `grid-template-columns: 200px`, to the plain integer 200) is treated as
   a single fixed-px track for symmetry with that string case.

   Supports fixed `Npx` and fractional `Nfr` tracks, plus `repeat(...)` and
   `minmax(...)` (see parse-track-token for the full per-token grammar and
   each helper's own docstring for the honestly-scoped subset it supports).
   Splits on top-level whitespace only (split-tracks-toplevel), so
   whitespace nested inside a repeat(...) argument list doesn't fracture
   that call into separate tokens. Any token this file can't make sense of
   degrades to a 0px fixed track rather than throwing, so an unsupported
   keyword never crashes layout — see parse-track-token's :else branch.

   nil/blank input returns [] — callers decide the no-explicit-tracks
   fallback (layout-grid falls back to a single full-width column when
   grid-template-columns is absent)."
  [v]
  (cond
    (integer? v) [{:type :fixed :size v}]
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
   (parse-repeat-token) and `minmax(min, max)` (parse-minmax-token). Any
   other token — `auto`, percentages, `repeat(auto-fill, ...)`, anything
   malformed — degrades to a single 0px fixed track rather than throwing,
   so an unsupported keyword never crashes layout."
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
;; of scope: the `-start`/`-end` longhand properties, `grid-template-areas`
;; name references, dense packing, and implicit track creation for an
;; out-of-range line (see clamp-col-range for the fallback used instead).
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

(defn- item-grid-placement
  "The child's own explicit grid-column/grid-row placement request, each
   parsed to a 0-based [start end) range or nil for an axis it doesn't
   declare (see parse-grid-placement). A non-element child (e.g. a raw text
   node, which can't carry a :style/* attr) always gets {:col nil :row nil}
   -- i.e. treated as fully auto-placed, same as before this feature
   existed. `theme` is only needed because node-style requires it (neither
   grid-column nor grid-row depend on any theme value)."
  [theme child n-cols n-row-tracks]
  (if (map? child)
    (let [cst (node-style child theme)]
      {:col (parse-grid-placement (:grid-column cst) n-cols)
       :row (parse-grid-placement (:grid-row cst) n-row-tracks)})
    {:col nil :row nil}))

(defn- place-grid-items
  "Resolves every in-flow grid child's [row-start row-end) x [col-start
   col-end) cell range, honoring explicit grid-column/grid-row declarations
   (parse-grid-placement) and auto-placing everything else in DOM order.
   Returns a vector, parallel to `children` (DOM order preserved regardless
   of placement order), of {:row-start :row-end :col-start :col-end}.

   Simplification (this engine's documented subset -- real CSS Grid's own
   auto-placement-around-explicit-items algorithm, CSS Grid section 8.5, is
   genuinely complex and deliberately NOT replicated here):

     1. Every child with an explicit grid-column and/or grid-row is placed
        FIRST, in DOM order among themselves, before any fully-auto child --
        mirroring real CSS's own two-phase placement (explicit items are
        placed before auto-placement runs at all, regardless of where they
        fall in DOM order relative to auto items). A child with only ONE
        axis explicit gets the OTHER axis resolved by searching for the
        first free row (find-free-row, if grid-column was given) or column
        (find-free-col, if grid-row was given) against only the explicit
        items placed so far -- not a full 2D bin-pack, but enough to avoid
        colliding with an earlier explicit item on the same row/column.

     2. Every remaining (fully auto -- neither axis declared) child is then
        placed, in DOM order among themselves, into the next unoccupied
        SINGLE cell (1 col x 1 row, exactly this engine's original
        pre-explicit-placement auto-placement grain) found by scanning
        row-major from (row 0, col 0), via a cursor that only ever advances
        (never revisits a cell). Cells already claimed by an explicit item
        are skipped -- this is the 'auto-placed items skip
        explicitly-occupied cells but don't attempt sophisticated backfill'
        simplification: once the scan has moved past a gap, it never
        backtracks into it, even if a later cell the scan reaches is itself
        a dead end (the loop's occupied-cell check keeps advancing until it
        finds a free cell, so it can't get stuck, but it also can't go
        backwards).

   When there are NO explicitly-placed items at all, this degenerates to
   exactly the row-major scan every auto item always got before this
   feature existed (the cursor never encounters an already-occupied cell,
   so it always accepts the first cell it lands on) -- a pure
   backwards-compatibility guarantee, not a special case in the code."
  [theme children n-cols n-row-tracks]
  (let [n (count children)
        requests (mapv #(item-grid-placement theme % n-cols n-row-tracks) children)
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
   usable `content` value for it (a quoted string literal, including the
   empty string -- see cssom.core/parse-content-literal; attr()/counter()/
   url()/none/absent all leave no :content key). Returns nil when there's
   nothing to generate, so a node with no matching ::before/::after rule
   lays out exactly as it did before this feature existed."
  [node pseudo-key]
  (let [style (pseudo-style node pseudo-key)]
    (when-let [content (:content style)]
      {:generated/pseudo pseudo-key
       :generated/text (str content)
       :generated/style style})))

(defn- generated-node?
  [node]
  (and (map? node) (boolean (:generated/pseudo node))))

(defn- with-generated-content
  "Returns `children` with `node`'s ::before/::after generated-content nodes
   (see generated-content-node) spliced in as the first/last entries
   respectively -- generated content is always positioned immediately
   before a node's real children and immediately after them, mirroring how
   real CSS pseudo-elements are always the first/last box in their
   originating element's box tree."
  [node children]
  (let [before (generated-content-node node :before)
        after (generated-content-node node :after)
        children (vec children)
        children (if before (into [before] children) children)
        children (if after (conj children after) children)]
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
   stop, no override mechanism -- see the section comment above."
  #{:head :title :script :style :meta :link})

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
            node-h (or (:height st) (+ cross-total (* 2 inset)))]
        {:box-w w :box-h node-h :draws draws})
      (let [main-sizes (mapv (fn [m] (if column? (:h (:box m)) (:w (:box m)))) measured)
            cross-sizes (mapv (fn [m] (if column? (:w (:box m)) (:h (:box m)))) measured)
            auto-cross (if (seq cross-sizes) (apply max 0 cross-sizes) 0)
            cross-content (or (if column? (:width st) (:height st)) auto-cross)
            auto-main (+ (reduce + 0 main-sizes) (* gap (max 0 (dec (count main-sizes)))))
            main-content (or (if column? (:height st) (:width st)) auto-main)
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
   them — see parse-track-list), per-item explicit placement via
   `grid-column`/`grid-row` (see parse-grid-placement), and auto-placement
   in DOM order, row-major (fills a row left-to-right before wrapping to the
   next row) for everything else — see place-grid-items for exactly how
   explicit and auto placement compose. `gap` — the same style key flex
   already reuses — spaces both rows and columns.

   Column count = the number of parsed grid-template-columns tracks. With no
   (or a blank) grid-template-columns, this falls back to a single
   full-content-width column — i.e. behaves like a vertical stack, a
   reasonable default that also keeps `display:grid` usable with only
   grid-template-rows set. Column track sizes are always resolved against
   the container's definite content-width (`cw`), exactly like flexbox's
   main-axis sizing whenever the main size is known — so `fr` columns are
   always well-defined (see track-sizes). An item spanning more than one
   column (`grid-column: 1 / 3`) gets the combined width of every column it
   spans plus the gaps between them (span-width), and is measured against
   that combined width exactly like a plain single-column item is measured
   against its one column's width (so it stretches to fill the whole span
   when it has no explicit width of its own).

   Explicit placement (`grid-column`/`grid-row`, see parse-grid-placement):
   a plain 1-based line number (`grid-column: 2`), the two-value `<start> /
   <end>` shorthand (`grid-column: 1 / 3`), and `<start> / span <n>`
   (`grid-column: 2 / span 2`) are all supported for both axes. A negative
   line/index (`grid-column: -1`, 'the last column') is also supported as a
   deliberately pragmatic stretch goal. NOT supported: the `-start`/`-end`
   longhand properties, `grid-template-areas` name references, and dense
   packing (see parse-grid-placement's own docstring for the precise
   grammar/arithmetic). An out-of-range column line (e.g. `grid-column: 5`
   with only 3 declared column tracks) does NOT implicitly create a new
   column track the way real CSS does (explicitly out of scope) — instead
   it's CLAMPED into the declared column range (clamp-col-range), landing
   on/overlapping the last column rather than indexing past
   col-widths/col-offsets or crashing. Auto-placed items that don't declare
   either property compose with explicitly-placed ones per the
   documented simplification in place-grid-items (short version: explicit
   items are placed first in DOM order, then auto items fill remaining
   single cells row-major, skipping whatever's already occupied — no
   sophisticated backfill).

   Row sizing: rows are auto-generated (row-major wrap, extended as needed
   by any explicit grid-row placement that reaches further than
   auto-placement alone would) to fit however many rows are actually
   needed, regardless of how many grid-template-rows tracks were given —
   there is no implicit-grid concept beyond 'add another row', and (unlike
   columns) no clamping: a row is not a fixed, finite axis in this engine to
   begin with, so an out-of-range grid-row line just means however many
   more (possibly empty, 0px-tall) rows are needed to reach it. A row whose
   index has an explicit *fixed*-px grid-template-rows track uses that
   literal height. Every other row — an `fr` row track, any row beyond the
   explicit track list, or an empty row nothing was placed into — is
   auto-sized to the tallest child whose placement STARTS in that row
   (mirrors flexbox's own auto cross-axis convention elsewhere in this
   file; a multi-row-span item's height only contributes to its start row's
   auto-sizing, not any row it merely passes through — a documented
   simplification, row spans are not this feature's must-have), UNLESS the
   grid container has an explicit :height, in which case the explicit row
   tracks (fixed + fr) are resolved proportionally against that height the
   same way columns are. Without an explicit container height there is no
   definite total to share `fr` row tracks against, so this is the one
   deliberate asymmetry versus columns in this subset — documented here
   rather than silently guessed at.

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
   creation, grid-template-areas, dense packing, and the grid-column-start/
   grid-column-end/grid-row-start/grid-row-end longhand properties (only the
   grid-column/grid-row shorthand is parsed)."
  [theme x y avail-width opacity inherited st node in-flow]
  (let [w (resolve-width st avail-width)
        inset (content-inset st)
        cx (+ x (:margin st) inset)
        cy (+ y (:margin st) inset)
        cw (max 0 (- w (* 2 inset)))
        gap (:gap st)
        explicit-cols (parse-track-list (:grid-template-columns st))
        col-tracks (if (seq explicit-cols) explicit-cols [{:type :fixed :size cw}])
        n-cols (count col-tracks)
        col-widths (track-sizes col-tracks gap cw)
        col-offsets (place-main-axis "flex-start" col-widths gap 0)
        row-tracks (parse-track-list (:grid-template-rows st))
        n-row-tracks (count row-tracks)
        explicit-h (:height st)
        row-track-fr-sizes (when explicit-h (track-sizes row-tracks gap explicit-h))
        placements (place-grid-items theme in-flow n-cols n-row-tracks)
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
                              left (or (:left cst) 0)
                              top (or (:top cst) 0)
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
  [theme x y avail-width opacity st node]
  (let [tag (:tag node)
        w (resolve-width st avail-width)
        inset (content-inset st)
        h (or (:height st) (+ (:line-height theme) (* 2 inset)))
        value (attr node :value)
        checked (true? (attr node :checked))
        input-type (str/lower-case (str (or (attr node :type) "text")))
        control-text (case tag
                       :select (option-label node value)
                       :input (if (= "checkbox" input-type)
                                (if checked "[x]" "[ ]")
                                (str value))
                       (str value))
        text-op (when (seq (str control-text))
                  {:draw/op :text :control? true :node/id (:node/id node)
                   :x (+ x inset) :y (+ y inset) :text control-text :opacity opacity})
        selection-start (attr node :selection-start)
        selection-end (attr node :selection-end)
        sel-ops (when (and (= tag :input) selection-start selection-end)
                  (let [s (parse-int selection-start nil)
                        e (parse-int selection-end nil)]
                    (when (and s e)
                      (if (= s e)
                        [{:draw/op :text :caret? true :node/id (:node/id node) :caret s :w 1
                          :x x :y y :opacity opacity}]
                        [{:draw/op :text :selection? true :node/id (:node/id node)
                          :selection/start s :selection/end e
                          :w (max 1 (* (- e s) (long (* 0.6 (:font-size theme)))))
                          :x x :y y :opacity opacity}]))))
        semantic (merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w w :h h
                         :class (attr node :class) :listeners (listeners node)
                         :opacity opacity :value value :checked checked}
                        (style-passthrough st))]
    {:box {:x x :y y :w w :h h}
     :draw (cond-> [semantic]
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
        explicit-h (:height st)
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
     :draw (vec (concat border-draws rect semantic clip-push draw clip-pop absolute-draws))}))

(defn layout-node
  ([node] (layout-node default-theme 0 0 320 1.0 {:color (:fg default-theme) :font-size (:font-size default-theme)} node))
  ([theme x y avail-width opacity inherited node]
   (cond
     (nil? node)
     {:box {:x x :y y :w 0 :h 0} :draw []}

     (generated-node? node)
     (let [gstyle (:generated/style node)
           color (or (:color gstyle) (:color inherited))
           font-size (parse-int (:font-size gstyle) (:font-size inherited))]
       (layout-text theme x y avail-width opacity color font-size (:generated/text node)))

     (text-node? node)
     (layout-text theme x y avail-width opacity (:color inherited) (:font-size inherited) node)

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
               inherited (assoc inherited :color color :font-size font-size)
               tag (:tag node)
               children (with-generated-content node (:children node))]
           (cond
             (contains? #{:input :select :textarea} tag)
             (layout-form-control theme x y avail-width opacity st node)

             (= "flex" (:display st))
             (let [{:keys [box-w box-h draws]} (layout-flex theme x y avail-width opacity inherited st node children)]
               {:box {:x x :y y :w box-w :h box-h}
                :draw (vec (concat
                            (or (border-ops st x y box-w box-h opacity) [])
                            (when-let [bg (default-bg tag st theme)]
                              [{:draw/op :rect :x x :y y :w box-w :h box-h :color bg :tag tag :opacity opacity}])
                            [(merge {:draw/op :node :id (:node/id node) :tag tag :x x :y y :w box-w :h box-h
                                     :class (attr node :class) :listeners (listeners node)
                                     :opacity opacity}
                                    (style-passthrough st))]
                            draws))})

             (= "grid" (:display st))
             (let [{:keys [box-w box-h draws]} (layout-grid theme x y avail-width opacity inherited st node children)]
               {:box {:x x :y y :w box-w :h box-h}
                :draw (vec (concat
                            (or (border-ops st x y box-w box-h opacity) [])
                            (when-let [bg (default-bg tag st theme)]
                              [{:draw/op :rect :x x :y y :w box-w :h box-h :color bg :tag tag :opacity opacity}])
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
  ([tree] (draw-ops tree {}))
  ([tree opts]
   (let [theme (merge default-theme (:theme opts))
         inherited {:color (:fg theme) :font-size (:font-size theme)}]
     (:draw (layout-node theme (or (:x opts) 0) (or (:y opts) 0) (or (:width opts) 320) 1.0 inherited tree)))))

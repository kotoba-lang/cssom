(ns cssom.layout
  "Box-model + flexbox + grid layout projection from a kotoba virtual DOM
   tree (kotoba.wasm.dom/tree) to renderer draw ops.

   Covers: padding/border/margin box model with min/max-width and
   content-box/border-box sizing; display:flex with flex-direction/
   flex-wrap/justify-content/align-items/gap; display:grid with
   grid-template-columns/grid-template-rows (fixed px + fr tracks,
   auto-placement only — see layout-grid for the exact subset and its
   documented limitations); position:relative/absolute with z-index
   stacking; opacity (multiplicatively inherited); background/
   background-color; borders; overflow+scroll-top/scroll-left clipping;
   form-control value/checked/selected-option-label projection; text input
   caret/selection; ::before/::after generated `content` (see
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

   Supports fixed `Npx` and fractional `Nfr` tracks only. Explicitly out of
   scope: `auto`, `minmax()`, `repeat()`, percentage tracks — any token that
   doesn't match `Npx`/`Nfr` degrades to a 0px fixed track rather than
   throwing, so an unsupported keyword doesn't crash layout.

   nil/blank input returns [] — callers decide the no-explicit-tracks
   fallback (layout-grid falls back to a single full-width column when
   grid-template-columns is absent)."
  [v]
  (cond
    (integer? v) [{:type :fixed :size v}]
    (string? v)
    (->> (str/split (str/trim v) #"\s+")
         (remove str/blank?)
         (mapv (fn [tok]
                 (cond
                   (re-matches #"-?[0-9]*\.?[0-9]+fr" tok)
                   {:type :fr :size (parse-dbl (subs tok 0 (- (count tok) 2)) 1.0)}

                   (re-matches #"-?[0-9]*\.?[0-9]+px" tok)
                   {:type :fixed :size (parse-int (subs tok 0 (- (count tok) 2)) 0)}

                   (re-matches #"-?[0-9]*\.?[0-9]+" tok)
                   {:type :fixed :size (parse-int tok 0)}

                   :else
                   {:type :fixed :size 0}))))
    :else []))

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

(defn- track-sizes
  "Resolves parsed tracks (see parse-track-list) to concrete pixel sizes.
   `definite-total` is the space available along this axis to distribute fr
   tracks against: always the container's content-width for columns; for
   rows it is the container's explicit :height if given, else nil. When nil,
   fr tracks resolve to 0px here and layout-grid falls back to auto/content
   sizing for that row instead (mirroring flexbox's own auto cross-axis
   convention elsewhere in this file) — there is no definite total to share
   proportionally when the grid container's height is itself content-driven."
  [tracks gap definite-total]
  (let [n (count tracks)
        gap-total (* gap (max 0 (dec n)))
        fixed-total (reduce + 0 (keep #(when (= :fixed (:type %)) (:size %)) tracks))
        remaining (when definite-total (max 0 (- definite-total fixed-total gap-total)))
        fr-sizes (if remaining
                   (distribute-fr remaining (mapv :size (filter #(= :fr (:type %)) tracks)))
                   [])]
    (loop [ts tracks frs fr-sizes out []]
      (if (empty? ts)
        out
        (let [t (first ts)]
          (if (= :fixed (:type t))
            (recur (rest ts) frs (conj out (long (:size t))))
            (recur (rest ts) (rest frs) (conj out (long (or (first frs) 0))))))))))

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
   track lists (fixed px + fr — see parse-track-list) with auto-placement in
   DOM order, row-major (fills a row left-to-right before wrapping to the
   next row). `gap` — the same style key flex already reuses — spaces both
   rows and columns.

   Column count = the number of parsed grid-template-columns tracks. With no
   (or a blank) grid-template-columns, this falls back to a single
   full-content-width column — i.e. behaves like a vertical stack, a
   reasonable default that also keeps `display:grid` usable with only
   grid-template-rows set. Column track sizes are always resolved against
   the container's definite content-width (`cw`), exactly like flexbox's
   main-axis sizing whenever the main size is known — so `fr` columns are
   always well-defined (see track-sizes).

   Row sizing: rows are auto-generated (row-major wrap) to fit however many
   children there are, regardless of how many grid-template-rows tracks were
   given — there is no implicit-grid concept beyond 'add another row'. A row
   whose index has an explicit *fixed*-px grid-template-rows track uses that
   literal height. Every other row — an `fr` row track, or any row beyond
   the explicit track list — is auto-sized to the tallest child placed in
   that row (mirrors flexbox's own auto cross-axis convention elsewhere in
   this file), UNLESS the grid container has an explicit :height, in which
   case the explicit row tracks (fixed + fr) are resolved proportionally
   against that height the same way columns are. Without an explicit
   container height there is no definite total to share `fr` row tracks
   against, so this is the one deliberate asymmetry versus columns in this
   subset — documented here rather than silently guessed at.

   Absolute-positioned children are NOT extracted via partition-flow here —
   this matches layout-flex's current behavior (today only layout-block
   partitions out-of-flow children); a position:absolute child inside a grid
   container is placed as an ordinary grid item, the same limitation flex
   already has.

   Explicitly out of scope: `auto`/`minmax()`/`repeat()`/percentage tracks,
   explicit grid-column/grid-row placement, grid-template-areas, dense
   packing."
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
        explicit-h (:height st)
        row-track-fr-sizes (when explicit-h (track-sizes row-tracks gap explicit-h))
        rows-of-children (partition-all n-cols in-flow)
        measured-rows (mapv (fn [row-children]
                              (vec (map-indexed
                                    (fn [col-idx child]
                                      (measure-child theme (nth col-widths col-idx cw) opacity inherited child))
                                    row-children)))
                            rows-of-children)
        row-heights (vec (map-indexed
                          (fn [row-idx measured]
                            (let [track (nth row-tracks row-idx nil)]
                              (cond
                                (and track (= :fixed (:type track)))
                                (:size track)

                                (and track row-track-fr-sizes)
                                (nth row-track-fr-sizes row-idx)

                                :else
                                (apply max 0 (mapv #(:h (:box %)) measured)))))
                          measured-rows))
        row-offsets (place-main-axis "flex-start" row-heights gap 0)
        draws (vec (mapcat (fn [measured row-y]
                             (mapcat
                              (fn [col-idx m]
                                (translate-ops (+ cx (nth col-offsets col-idx)) (+ cy row-y) (:draw m)))
                              (range) measured))
                           measured-rows row-offsets))
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

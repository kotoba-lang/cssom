(ns cssom.layout-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [cssom.core :as css]
            [cssom.layout :as layout]
            [kotoba.wasm.dom :as dom]))

(deftest draw-ops-projects-button-and-text
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [button doc] (dom/create-element doc :button)
        doc (dom/append-child doc root button)
        [text doc] (dom/create-text-node doc "Counter")
        doc (dom/append-child doc button text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (is (some #(and (= :rect (:draw/op %)) (= :button (:tag %))) ops))
    (is (some #(and (= :text (:draw/op %)) (= "Counter" (:text %))) ops))))

(deftest block-background-paints-before-border-not-hidden-under-it
  ;; The confirmed repro from the bug report: a background rect spans an
  ;; element's FULL box, including the thin edge strips border-ops paints
  ;; -- painting border-ops FIRST (as this used to) meant the background,
  ;; painted second (the real webgl.cljs/webgpu.cljs painter draws :rect
  ;; ops strictly in array order, no z-index reordering of its own),
  ;; completely covered every border pixel. Confirmed via a real draw-ops
  ;; dump through the full pipeline before this fix existed: an ordinary
  ;; <div> with both an explicit background AND border-width never
  ;; actually showed any border pixels at all.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:border-width 3 :border-color "#00ff00"
                                     :background "#ff0000"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))
        top-border (first (filter #(and (= :rect (:draw/op %)) (:border? %) (= :top (:edge %))) ops))]
    (is (some? bg-rect))
    (is (some? top-border))
    (is (< (.indexOf ops bg-rect) (.indexOf ops top-border))
        "background must paint BEFORE border, not after")))

(deftest flex-container-background-paints-before-border-not-hidden-under-it
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :border-width 3 :border-color "#00ff00"
                                     :background "#ff0000"})
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc div span)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))
        top-border (first (filter #(and (= :rect (:draw/op %)) (:border? %) (= :top (:edge %))) ops))]
    (is (some? bg-rect))
    (is (some? top-border))
    (is (< (.indexOf ops bg-rect) (.indexOf ops top-border))
        "background must paint BEFORE border, not after, same convention as block")))

(def ^:private inherited-text
  {:color (:fg layout/default-theme) :font-size (:font-size layout/default-theme)})

(deftest short-text-still-produces-a-single-line
  ;; Regression guard: text that already fits in avail-width must stay
  ;; byte-for-byte the same single :text draw-op as before wrapping existed.
  (let [text "Hello world"
        {:keys [box draw]} (layout/layout-node layout/default-theme 0 0 480 1.0 inherited-text text)
        {:keys [padding line-height]} layout/default-theme]
    (is (= 1 (count draw)))
    (is (= :text (:draw/op (first draw))))
    (is (= text (:text (first draw))))
    (is (= padding (:x (first draw))))
    (is (= padding (:y (first draw))))
    (is (= (+ line-height (* 2 padding)) (:h box)))))

(deftest long-text-wraps-onto-multiple-narrower-lines
  (let [avail 100
        text "the quick brown fox jumps over the lazy dog"
        {:keys [box draw]} (layout/layout-node layout/default-theme 0 0 avail 1.0 inherited-text text)
        {:keys [padding line-height font-size]} layout/default-theme
        char-w (long (* 0.6 font-size))]
    (is (= ["the quick" "brown fox" "jumps over" "the lazy" "dog"]
           (mapv :text draw)))
    (is (> (count draw) 1))
    (is (every? #(= :text (:draw/op %)) draw))
    ;; Every line is narrower than the container it was wrapped into.
    (is (every? #(< (* (count (:text %)) char-w) avail) draw))
    ;; Greedy word-wrap must not lose or reorder any words.
    (is (= (str/split text #"\s+") (mapcat #(str/split (:text %) #"\s+") draw)))
    ;; Lines are stacked at successive y offsets spaced by line-height.
    (is (= (mapv #(+ padding (* % line-height)) (range (count draw)))
           (mapv :y draw)))
    ;; Box height grows with the number of lines actually produced.
    (is (= (+ (* (count draw) line-height) (* 2 padding)) (:h box)))))

(deftest single-overlong-word-is-not-split-or-dropped
  (let [avail 40
        word "Supercalifragilisticexpialidocious"
        {:keys [box draw]} (layout/layout-node layout/default-theme 0 0 avail 1.0 inherited-text word)]
    ;; Doesn't hang, doesn't vanish, doesn't get mid-word split: exactly one
    ;; line with the full word, even though it overflows avail-width.
    (is (= 1 (count draw)))
    (is (= word (:text (first draw))))
    ;; Box width is clamped to avail-width (the box model's existing
    ;; overflow convention), not silently expanded to fit the long word.
    (is (= avail (:w box)))))

;; ---- injectable :measure-text (real-host text measurement, e.g. a real
;; browser's CanvasRenderingContext2D.measureText) ----
;;
;; layout-text's word-wrap normally assumes every character is exactly
;; `(long (* 0.6 font-size))` px wide (a monospace-like approximation --
;; see text-lines) since this file is a pure, host-independent layout
;; engine with no real glyph shaping and no Canvas API available in every
;; environment it runs in (e.g. this very JVM test suite). A real host
;; (a real browser's Canvas 2D context, or kotoba-lang/dom-gpu's WebGL/
;; WebGPU hosts, which already hold one) can instead supply an OPTIONAL
;; `:measure-text` function on `theme` -- `(fn [text font-size]
;; width-in-px)` -- consulted instead (text-lines-measured). The two
;; tests below prove both halves of that contract: (1) omitting
;; `:measure-text` is byte-for-byte the SAME char-w-approximation code
;; path this file has always used (default-theme has no :measure-text key
;; at all, and every test above this comment -- unmodified by this
;; feature -- already proves that path's exact output), and (2) supplying
;; one is genuinely CONSULTED, not silently ignored.

(deftest default-theme-has-no-measure-text-so-every-existing-caller-is-unaffected
  ;; The non-regression contract, made explicit and self-documenting:
  ;; default-theme (what draw-ops/layout-node fall back to whenever a
  ;; caller doesn't override :theme) simply has no :measure-text key, so
  ;; `(:measure-text theme)` is nil for every caller that predates this
  ;; feature -- layout-text's (if measure-text ... (text-lines ...)) then
  ;; always takes the untouched text-lines/char-w branch, exactly as
  ;; before this feature existed.
  (is (nil? (:measure-text layout/default-theme))))

(defn- fake-proportional-measure
  "A FAKE stand-in for a real browser's `CanvasRenderingContext2D.
   measureText` -- an honest substitution for a real proportional font in
   this JVM test environment (which has no real Canvas API to call),
   *not* a mock of the feature under test (text-lines-measured/layout-text
   genuinely call this fn; nothing about the wrap algorithm itself is
   stubbed out). Assigns each character a per-character px width that
   mimics a genuinely proportional (non-monospace) font -- 'W' is much
   wider than 'i' -- unlike the production char-w approximation, which
   assumes every character is the same width regardless of which letter
   it is."
  [text _font-size]
  (reduce + 0 (map (fn [c] (case c \W 16 \i 3 \space 6 8)) text)))

(deftest measure-text-is-genuinely-consulted-not-ignored
  ;; Two strings with the IDENTICAL character count, word count, and
  ;; per-word length (two 5-char words joined by one space, 11 characters
  ;; total either way) -- so the production char-w approximation (which
  ;; only ever counts characters) cannot tell them apart and must wrap
  ;; them identically. A real proportional font renders them very
  ;; differently widths apart though: "WWWWW WWWWW" is far wider than
  ;; "iiiii iiiii". This proves layout-text's `:measure-text` is
  ;; genuinely consulted (the wrap decision tracks the FAKE font's real
  ;; per-character widths), not merely threaded through and ignored.
  (let [text-w "WWWWW WWWWW"
        text-i "iiiii iiiii"
        avail 108 ;; content-w (avail - 2*padding) = 100 px
        measured-theme (assoc layout/default-theme :measure-text fake-proportional-measure)
        {draw-w :draw} (layout/layout-node measured-theme 0 0 avail 1.0 inherited-text text-w)
        {draw-i :draw} (layout/layout-node measured-theme 0 0 avail 1.0 inherited-text text-i)
        {draw-w-default :draw} (layout/layout-node layout/default-theme 0 0 avail 1.0 inherited-text text-w)
        {draw-i-default :draw} (layout/layout-node layout/default-theme 0 0 avail 1.0 inherited-text text-i)]
    ;; With the injected proportional measure fn: the W-heavy text is too
    ;; wide for 100px (WWWWW=80 + space=6 + WWWWW=80 = 166px) and wraps
    ;; onto two lines, one word per line...
    (is (= ["WWWWW" "WWWWW"] (mapv :text draw-w)))
    ;; ...while the i-heavy text of the SAME character count comfortably
    ;; fits (iiiii=15 + space=6 + iiiii=15 = 36px) and stays on one line.
    (is (= ["iiiii iiiii"] (mapv :text draw-i)))
    ;; Genuinely different wrap OUTCOMES for same-length strings is the
    ;; proof the injected fn drives the decision, not just character count.
    (is (not= (count draw-w) (count draw-i)))
    ;; Sanity check on the OTHER half of the contract: without
    ;; :measure-text, the default char-w approximation can't tell these
    ;; two same-character-count strings apart at all -- both fit on a
    ;; single line (char-w for font-size 14 is 8px; 11 chars * 8 = 88 <=
    ;; 100), unlike the measured case above where they genuinely diverge.
    (is (= ["WWWWW WWWWW"] (mapv :text draw-w-default)))
    (is (= ["iiiii iiiii"] (mapv :text draw-i-default)))
    ;; Same wrap SHAPE (both a single unwrapped line) for both strings --
    ;; unlike the measured case above, where the same two strings genuinely
    ;; diverge (one line vs. two).
    (is (= (count draw-w-default) (count draw-i-default)))))

(deftest measure-text-flows-through-the-public-draw-ops-entry-point
  ;; The same proof as measure-text-is-genuinely-consulted-not-ignored,
  ;; but through the actual public entry point (draw-ops, called with a
  ;; real kotoba.wasm.dom tree) instead of the lower-level layout-node --
  ;; proving the plumbing draw-ops' docstring documents (opts' :theme
  ;; merges :measure-text same as every other theme key) genuinely works
  ;; end-to-end, exactly how kotoba-lang/browser's render-document already
  ;; threads its own `theme` argument into this same opts map today.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [text-node doc] (dom/create-text-node doc "WWWWW WWWWW")
        doc (dom/append-child doc root text-node)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 116
                                   :theme {:measure-text fake-proportional-measure}})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["WWWWW" "WWWWW"] (mapv :text text-ops)))))

;; ---- display:grid ----

(defn- grid-tree
  "Builds a :main display:grid element with `container-style` (merged onto
   {:display \"grid\"}) and one :span child per entry in `child-sizes`.
   Each entry is either a [w h] pair (either may be nil to leave that style
   unset -- an unset width lets the child stretch to fill its column, the
   same way a plain block child already fills its container's
   content-width) or a [w h extra-style] triple, where `extra-style` is
   merged onto that child's style map on top of :width/:height -- used by
   the explicit grid-column/grid-row placement tests below to set
   e.g. {:grid-column 2 :grid-row \"1 / 3\"} directly on one child without
   every other grid-tree caller needing to know about it. Sets style attrs
   directly via kotoba.wasm.dom, bypassing cssom.core's cascade entirely --
   same convention the rest of this test file already uses (so an integer
   grid-column/grid-row value here is a genuine integer the way
   cssom.core's parse-style-value would already have coerced a bare-integer
   declaration to, and a string value like \"1 / 3\" is the genuine raw
   string cssom.core would pass through unchanged -- both forms this test
   file bypasses cssom.core to set directly are exactly what
   cssom.layout/parse-grid-placement expects to receive)."
  [container-style child-sizes]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        doc (dom/set-style doc root (merge {:display "grid"} container-style))
        doc (reduce (fn [doc [w h extra]]
                      (let [[child doc] (dom/create-element doc :span)
                            doc (dom/set-style doc child (merge (cond-> {}
                                                                   w (assoc :width w)
                                                                   h (assoc :height h))
                                                                 extra))]
                        (dom/append-child doc root child)))
                    doc
                    child-sizes)
        [_ doc] (dom/consume-ops doc)]
    (dom/tree doc)))

(defn- node-ops
  "The :draw/op :node entries from a draw-ops result, in the same order
   layout emits them: the container first, then each child in placement
   order (DOM/auto-placement order, for this grid subset)."
  [ops]
  (filterv #(= :node (:draw/op %)) ops))

(deftest grid-fixed-px-tracks-place-items-at-exact-rects
  ;; grid-template-columns "50px 80px" / grid-template-rows "20px 30px", no
  ;; gap/padding/border: exact column x-offsets (0, 50), exact FIXED row
  ;; heights (20, 30) even though every child in those rows is shorter than
  ;; its row's track -- the explicit track wins, children keep their own
  ;; (unstretched) height -- and the 3rd item wraps into row 2.
  (let [tree (grid-tree {:grid-template-columns "50px 80px"
                          :grid-template-rows "20px 30px"
                          :gap 0 :padding 0 :width 130}
                         [[nil 10] [nil 12] [nil 8]])
        ops (layout/draw-ops tree {:width 130})
        [container a b c] (node-ops ops)]
    (is (= 130 (:w container)))
    (is (= 50 (:h container)))                              ; 20 + 30, no gap/padding
    (is (= {:x 0 :y 0 :w 50 :h 10} (select-keys a [:x :y :w :h])))
    (is (= {:x 50 :y 0 :w 80 :h 12} (select-keys b [:x :y :w :h])))
    ;; Wraps to row 2 at the row-1 explicit 20px offset, not at its own
    ;; (shorter) content height.
    (is (= {:x 0 :y 20 :w 50 :h 8} (select-keys c [:x :y :w :h])))))

(deftest grid-fr-tracks-proportion-remaining-space
  ;; 300px container, columns "1fr 2fr", 10px gap (1 gap between 2 columns)
  ;; -> remaining space = 300 - 10 = 290px, split 1:2 via integer division:
  ;; floor(290*1/3) = 96, floor(290*2/3) = 193; the 1px lost to rounding
  ;; (96 + 193 = 289 =/= 290) is assigned to the last (2fr) column -> 194.
  ;; Final column widths: 96px / 194px (96 + 10 + 194 = 300, exact).
  (let [tree (grid-tree {:grid-template-columns "1fr 2fr" :gap 10 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [container a b] (node-ops ops)]
    (is (= 300 (:w container)))
    (is (= {:x 0 :y 0 :w 96 :h 20} (select-keys a [:x :y :w :h])))
    (is (= {:x 106 :y 0 :w 194 :h 20} (select-keys b [:x :y :w :h])))
    (is (= 290 (+ (:w a) (:w b))))))

(deftest grid-gap-spaces-both-rows-and-columns
  ;; 2x2 grid of 40px columns / 20px rows with a shared gap:6 of 5px reused
  ;; from the same :gap style key flex already uses, applied along both axes.
  (let [tree (grid-tree {:grid-template-columns "40px 40px"
                          :grid-template-rows "20px 20px"
                          :gap 5 :padding 0 :width 85}
                         [[nil 10] [nil 10] [nil 10] [nil 10]])
        ops (layout/draw-ops tree {:width 85})
        [container a b c d] (node-ops ops)]
    (is (= 85 (:w container)))
    (is (= 45 (:h container)))                              ; 20 + 5 + 20
    (is (= {:x 0 :y 0} (select-keys a [:x :y])))
    (is (= {:x 45 :y 0} (select-keys b [:x :y])))            ; column-gap: 40 + 5
    (is (= {:x 0 :y 25} (select-keys c [:x :y])))            ; row-gap: 20 + 5
    (is (= {:x 45 :y 25} (select-keys d [:x :y])))))

(deftest grid-wraps-into-new-rows-when-columns-are-full
  ;; 2-column explicit grid with 5 auto-height items: wraps into 3 rows
  ;; (2, 2, 1) and each auto row's height is the tallest child placed in it
  ;; -- the same auto-cross-axis convention flexbox already uses.
  (let [tree (grid-tree {:grid-template-columns "30px 30px" :gap 0 :padding 0 :width 60}
                         [[nil 10] [nil 14] [nil 8] [nil 20] [nil 6]])
        ops (layout/draw-ops tree {:width 60})
        [container i0 i1 i2 i3 i4] (node-ops ops)]
    (is (= 60 (:w container)))
    (is (= 40 (:h container)))                              ; 14 + 20 + 6
    (is (= {:x 0 :y 0 :w 30 :h 10} (select-keys i0 [:x :y :w :h])))
    (is (= {:x 30 :y 0 :w 30 :h 14} (select-keys i1 [:x :y :w :h])))
    (is (= {:x 0 :y 14 :w 30 :h 8} (select-keys i2 [:x :y :w :h])))
    (is (= {:x 30 :y 14 :w 30 :h 20} (select-keys i3 [:x :y :w :h])))
    ;; Odd item count: the 5th item has no row partner but still wraps to
    ;; its own new row instead of being dropped or overlapping row 2.
    (is (= {:x 0 :y 34 :w 30 :h 6} (select-keys i4 [:x :y :w :h])))))

(deftest grid-container-border-and-background-match-block-flex-convention
  ;; Same border-ops/default-bg plumbing every other display mode in this
  ;; file already gets: 4 edge border rects + 1 background rect, computed
  ;; from the container's OUTER box (x/y/w/h) ahead of its own semantic
  ;; :node op -- not something grid skips just because it's "extra".
  (let [tree (grid-tree {:grid-template-columns "50px" :gap 0 :padding 4
                          :border-width 2 :border-color "#112233"
                          :background "#445566"}
                         [[nil 10]])
        ops (layout/draw-ops tree {:width 100})
        border-rects (filterv #(and (= :rect (:draw/op %)) (:border? %)) ops)
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))
        by-edge (into {} (map (juxt :edge identity)) border-rects)
        [container child] (node-ops ops)]
    (is (= 100 (:w container)))
    (is (= 18 (:h container)))                              ; content 10 + 2*padding(4)
    (is (= 4 (count border-rects)))
    (is (= {:x 0 :y 0 :w 100 :h 2} (select-keys (:top by-edge) [:x :y :w :h])))
    (is (= {:x 98 :y 0 :w 2 :h 18} (select-keys (:right by-edge) [:x :y :w :h])))
    (is (= {:x 0 :y 16 :w 100 :h 2} (select-keys (:bottom by-edge) [:x :y :w :h])))
    (is (= {:x 0 :y 0 :w 2 :h 18} (select-keys (:left by-edge) [:x :y :w :h])))
    (is (= "#445566" (:color bg-rect)))
    (is (= {:x 0 :y 0 :w 100 :h 18} (select-keys bg-rect [:x :y :w :h])))
    ;; Content is inset by padding only -- content-box box-sizing, the same
    ;; content-inset convention every display mode in this file already
    ;; uses (border-width only offsets content when box-sizing: border-box).
    (is (= {:x 4 :y 4 :w 50 :h 10} (select-keys child [:x :y :w :h])))
    (is (< (.indexOf ops bg-rect) (.indexOf ops (:top by-edge)))
        "background must paint BEFORE border -- the background rect spans
         the full box, including the border's own thin edge strips, so
         painting it AFTER would completely hide the border")))

;; ---- grid-template-columns: repeat() / minmax() ----

(deftest grid-repeat-expands-identically-to-writing-tracks-out
  ;; grid-template-columns: repeat(3, 140px) must place items identically to
  ;; writing out "140px 140px 140px" by hand -- repeat() is a pure expansion
  ;; at parse time (see parse-repeat-token), not a separate sizing path.
  (let [repeat-tree (grid-tree {:grid-template-columns "repeat(3, 140px)"
                                 :gap 0 :padding 0 :width 420}
                                [[nil 10] [nil 10] [nil 10]])
        spelled-out-tree (grid-tree {:grid-template-columns "140px 140px 140px"
                                      :gap 0 :padding 0 :width 420}
                                     [[nil 10] [nil 10] [nil 10]])
        rects (fn [ops] (mapv #(select-keys % [:x :y :w :h]) (node-ops ops)))
        repeat-rects (rects (layout/draw-ops repeat-tree {:width 420}))
        spelled-out-rects (rects (layout/draw-ops spelled-out-tree {:width 420}))]
    (is (= spelled-out-rects repeat-rects))
    (is (= [{:x 0 :y 0 :w 140 :h 10} {:x 140 :y 0 :w 140 :h 10} {:x 280 :y 0 :w 140 :h 10}]
           (rest repeat-rects)))))

(deftest grid-repeat-fr-splits-space-into-equal-tracks
  ;; repeat(2, 1fr) in a 300px container with no gap -> two equal 150px
  ;; columns, same as writing "1fr 1fr" by hand.
  (let [tree (grid-tree {:grid-template-columns "repeat(2, 1fr)" :gap 0 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [container a b] (node-ops ops)]
    (is (= 300 (:w container)))
    (is (= {:x 0 :y 0 :w 150 :h 20} (select-keys a [:x :y :w :h])))
    (is (= {:x 150 :y 0 :w 150 :h 20} (select-keys b [:x :y :w :h])))))

(deftest grid-repeat-with-multi-track-argument-expands-the-whole-pattern
  ;; Stretch goal: repeat(2, 100px 1fr) expands the whole 2-track *pattern*
  ;; twice (100px 1fr 100px 1fr = 4 tracks), not a single repeated track.
  (let [tree (grid-tree {:grid-template-columns "repeat(2, 100px 1fr)" :gap 0 :padding 0 :width 400}
                         [[nil 5] [nil 5] [nil 5] [nil 5]])
        ops (layout/draw-ops tree {:width 400})
        [container a b c d] (node-ops ops)]
    (is (= 400 (:w container)))
    (is (= {:x 0 :w 100} (select-keys a [:x :w])))
    (is (= {:x 100 :w 100} (select-keys b [:x :w])))
    (is (= {:x 200 :w 100} (select-keys c [:x :w])))
    (is (= {:x 300 :w 100} (select-keys d [:x :w])))))

(deftest grid-repeat-malformed-form-does-not-crash-layout
  ;; repeat(auto-fill, ...) is explicitly out of scope (needs real
  ;; content-based auto-sizing this engine doesn't do) -- it must degrade
  ;; gracefully (a single dropped/zero-width placeholder track, the same
  ;; convention any other unparseable token already uses) rather than throw.
  (let [tree (grid-tree {:grid-template-columns "repeat(auto-fill, 100px)" :gap 0 :padding 0 :width 200}
                         [[nil 10] [nil 10] [nil 10]])
        ops (layout/draw-ops tree {:width 200})]
    (is (= 4 (count (node-ops ops))))                    ; container + 3 children, no crash
    (is (= 200 (:w (first (node-ops ops)))))))

(deftest grid-minmax-reserves-floor-and-grows-into-remaining-space
  ;; minmax(100px, 1fr) alongside a 50px fixed column in a 300px container:
  ;; the minmax track reserves its 100px floor, then gets 100% of whatever
  ;; fr-space is left after every fixed contribution (including that floor)
  ;; is subtracted: 300 - 50 - 100 = 150 leftover, all of it going to the
  ;; single fr-weight track -> final size 100 + 150 = 250.
  (let [tree (grid-tree {:grid-template-columns "minmax(100px, 1fr) 50px" :gap 0 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [container a b] (node-ops ops)]
    (is (= 300 (:w container)))
    (is (= {:x 0 :y 0 :w 250 :h 20} (select-keys a [:x :y :w :h])))
    (is (= {:x 250 :y 0 :w 50 :h 20} (select-keys b [:x :y :w :h])))))

(deftest grid-minmax-floors-at-min-when-space-is-tight
  ;; The same minmax(100px, 1fr) track alongside a 400px fixed column packed
  ;; into a 300px container leaves 0px of remaining fr-space (300 - 400 -
  ;; 100 clamps to 0) -- the track's size never drops below its 100px floor
  ;; even though the row now honestly overflows the container, the same
  ;; "let it overflow" convention this file already uses elsewhere.
  (let [tree (grid-tree {:grid-template-columns "minmax(100px, 1fr) 400px" :gap 0 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [_ a b] (node-ops ops)]
    (is (= {:x 0 :w 100} (select-keys a [:x :w])))
    (is (= {:x 100 :w 400} (select-keys b [:x :w])))))

(deftest grid-minmax-with-fixed-max-clamps-like-a-fixed-track
  ;; minmax(100px, 200px): both args are plain px lengths, so the track
  ;; resolves to max(min,max) = 200px, same as writing a plain 200px fixed
  ;; track (see fixed-contribution) -- the 50px leftover from the 300px
  ;; container (200 + 50 = 250, 50px short of 300) is simply unused, the
  ;; same "no fr track to soak up leftover space" convention any all-fixed
  ;; track list already has in this engine.
  (let [tree (grid-tree {:grid-template-columns "minmax(100px, 200px) 50px" :gap 0 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [_ a b] (node-ops ops)]
    (is (= {:x 0 :w 200} (select-keys a [:x :w])))
    (is (= {:x 200 :w 50} (select-keys b [:x :w])))))

(deftest grid-repeat-and-minmax-compose
  ;; repeat(3, minmax(80px, 1fr)) -- the extremely common "responsive
  ;; equal-width grid with a floor" pattern. 390px container, no gap: each
  ;; track reserves its 80px floor (240px total), leaving 150px of
  ;; fr-space split evenly 3 ways (50px each) -> three 130px columns
  ;; exactly filling 390px.
  (let [tree (grid-tree {:grid-template-columns "repeat(3, minmax(80px, 1fr))" :gap 0 :padding 0 :width 390}
                         [[nil 10] [nil 10] [nil 10]])
        ops (layout/draw-ops tree {:width 390})
        [container a b c] (node-ops ops)]
    (is (= 390 (:w container)))
    (is (= {:x 0 :y 0 :w 130 :h 10} (select-keys a [:x :y :w :h])))
    (is (= {:x 130 :y 0 :w 130 :h 10} (select-keys b [:x :y :w :h])))
    (is (= {:x 260 :y 0 :w 130 :h 10} (select-keys c [:x :y :w :h])))))

;; ---- grid-template-columns: calc() (constant, percentage-free subset) ----
;;
;; cssom.core's parse-style-value never touches a multi-token track list
;; like "calc(100px + 20px) 1fr" at all (it only ever coerces a WHOLE
;; declaration value -- see parse-track-list's docstring), so a calc()
;; track needs this file's OWN local calc() resolver (resolve-constant-calc,
;; a small mirror of cssom.core's own same-scoped subset) -- these tests use
;; the grid-tree helper (bypasses cssom.core's cascade, same convention
;; every other grid-template-columns test in this file already uses) since
;; the point under test is cssom.layout's own track-token parsing, not the
;; cascade.

(deftest grid-calc-constant-track-resolves-to-its-arithmetic-result
  ;; calc(100px + 20px) is this engine's bounded, ALWAYS layout-independent
  ;; calc() subset (constant px/number arithmetic only) -- a single 120px
  ;; fixed track, then a 1fr track soaking up whatever's left in a 300px
  ;; container: 300 - 120 = 180.
  (let [tree (grid-tree {:grid-template-columns "calc(100px + 20px) 1fr" :gap 0 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [container a b] (node-ops ops)]
    (is (= 300 (:w container)))
    (is (= {:x 0 :y 0 :w 120 :h 20} (select-keys a [:x :y :w :h])))
    (is (= {:x 120 :y 0 :w 180 :h 20} (select-keys b [:x :y :w :h])))))

(deftest grid-calc-with-a-percentage-degrades-to-a-zero-width-track-not-a-crash
  ;; calc(50% - 10px) needs this container's own resolved size to mean
  ;; anything -- real layout, which this file's own track-list parser
  ;; deliberately does not attempt (see resolve-constant-calc/ns docstring)
  ;; -- so it degrades to the same 0px fixed-track placeholder any other
  ;; unparseable track token already falls back to (parse-track-token's
  ;; :else), never a guessed number.
  (let [tree (grid-tree {:grid-template-columns "calc(50% - 10px) calc(50% - 10px)" :gap 0 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [container a b] (node-ops ops)]
    (is (= 300 (:w container)))
    (is (= {:x 0 :w 0} (select-keys a [:x :w])))
    (is (= {:x 0 :w 0} (select-keys b [:x :w])))))

(deftest grid-malformed-calc-track-does-not-crash-layout
  ;; calc(100px +) -- a dangling operator with no right-hand operand --
  ;; degrades to a 0px placeholder track exactly like any other malformed
  ;; token already does, rather than throwing; the 1fr sibling track still
  ;; gets the container's full remaining space.
  (let [tree (grid-tree {:grid-template-columns "calc(100px +) 1fr" :gap 0 :padding 0 :width 200}
                         [[nil 10] [nil 10]])
        ops (layout/draw-ops tree {:width 200})
        [container a b] (node-ops ops)]
    (is (= 200 (:w container)))
    (is (= {:x 0 :w 0} (select-keys a [:x :w])))
    (is (= {:x 0 :w 200} (select-keys b [:x :w])))))

(deftest grid-minmax-accepts-a-constant-calc-min-argument
  ;; minmax(calc(50px + 30px), 1fr) -- calc() resolving inside a minmax()
  ;; argument too (parse-length-px), not just a standalone track. Same
  ;; arithmetic as grid-minmax-reserves-floor-and-grows-into-remaining-space
  ;; above but with an 80px floor (50 + 30) instead of a plain 100px one:
  ;; 300 - 50 (fixed sibling) - 80 (floor) = 170 leftover fr-space, all to
  ;; the single fr-weight track -> final size 80 + 170 = 250.
  (let [tree (grid-tree {:grid-template-columns "minmax(calc(50px + 30px), 1fr) 50px" :gap 0 :padding 0 :width 300}
                         [[nil 20] [nil 20]])
        ops (layout/draw-ops tree {:width 300})
        [container a b] (node-ops ops)]
    (is (= 300 (:w container)))
    (is (= {:x 0 :w 250} (select-keys a [:x :w])))
    (is (= {:x 250 :w 50} (select-keys b [:x :w])))))

;; ---- grid-column / grid-row explicit placement ----

(deftest grid-explicit-single-line-column-and-row-place-item-at-exact-track
  ;; grid-column: 2 / grid-row: 2 (plain 1-based line numbers, matching real
  ;; CSS's own grid-line numbering) must occupy 0-based column track 1 (the
  ;; 80px track) and 0-based row track 1 (the 30px track) -- not row/col 0.
  (let [tree (grid-tree {:grid-template-columns "50px 80px 30px"
                          :grid-template-rows "20px 30px"
                          :gap 0 :padding 0 :width 160}
                         [[nil 10 {:grid-column 2 :grid-row 2}]])
        ops (layout/draw-ops tree {:width 160})
        [container item] (node-ops ops)]
    (is (= 160 (:w container)))
    (is (= 50 (:h container)))                              ; 20 + 30, no gap/padding
    (is (= {:x 50 :y 20 :w 80 :h 10} (select-keys item [:x :y :w :h])))))

(deftest grid-column-two-value-span-occupies-combined-track-width
  ;; grid-column: 1 / 3 spans column lines 1->3, i.e. 0-based column tracks
  ;; 0 and 1 (50px + 80px = 130px combined) -- a real, extremely common way
  ;; to make an item span multiple columns. No grid-row declared, so the
  ;; row is auto-placed (lands in row 0, same as any fully-auto item would).
  (let [tree (grid-tree {:grid-template-columns "50px 80px 30px 20px" :gap 0 :padding 0 :width 180}
                         [[nil 10 {:grid-column "1 / 3"}]])
        ops (layout/draw-ops tree {:width 180})
        [container item] (node-ops ops)]
    (is (= 180 (:w container)))
    (is (= {:x 0 :y 0 :w 130 :h 10} (select-keys item [:x :y :w :h])))))

(deftest grid-column-start-span-n-shorthand
  ;; grid-column: 2 / span 2 -- starts at line 2 (0-based track 1) and spans
  ;; 2 tracks (0-based tracks 1 and 2, 50px + 60px = 110px), the extremely
  ;; common `span` keyword form real stylesheets use.
  (let [tree (grid-tree {:grid-template-columns "40px 50px 60px 70px" :gap 0 :padding 0 :width 220}
                         [[nil 10 {:grid-column "2 / span 2"}]])
        ops (layout/draw-ops tree {:width 220})
        [container item] (node-ops ops)]
    (is (= {:x 40 :y 0 :w 110 :h 10} (select-keys item [:x :y :w :h])))))

(deftest grid-negative-column-line-means-last-column
  ;; Stretch goal: grid-column: -1 resolves to the last declared column
  ;; track (0-based index 2 of 3, the 60px track) -- real CSS's own
  ;; "counts from the end" negative-line convention, in this engine's
  ;; pragmatic single-value form (see parse-grid-placement's docstring for
  ;; exactly why the single-value case resolves differently than the
  ;; two-value case's negative end).
  (let [tree (grid-tree {:grid-template-columns "40px 50px 60px" :gap 0 :padding 0 :width 150}
                         [[nil 10 {:grid-column -1}]])
        ops (layout/draw-ops tree {:width 150})
        [container item] (node-ops ops)]
    (is (= {:x 90 :y 0 :w 60 :h 10} (select-keys item [:x :y :w :h])))))

(deftest grid-explicit-item-does-not-collide-with-auto-placed-siblings
  ;; 2-column grid, 3 children in DOM order: auto A, explicit B pinned at
  ;; grid-column:2/grid-row:1 (0-based col1/row0 -- the SAME cell A's
  ;; auto-placement would otherwise land in first), auto C. Auto-placed
  ;; items must skip the cell B explicitly claims: A takes (row0,col0), C
  ;; is pushed past B's occupied (row0,col1) into (row1,col0) -- proving
  ;; explicit and auto placement compose without overlap.
  (let [tree (grid-tree {:grid-template-columns "40px 40px" :gap 0 :padding 0 :width 80}
                         [[nil 10]
                          [nil 10 {:grid-column 2 :grid-row 1}]
                          [nil 10]])
        ops (layout/draw-ops tree {:width 80})
        [container a b c] (node-ops ops)]
    (is (= {:x 0 :y 0 :w 40 :h 10} (select-keys a [:x :y :w :h])))   ; auto -> row0 col0
    (is (= {:x 40 :y 0 :w 40 :h 10} (select-keys b [:x :y :w :h])))  ; explicit -> row0 col1
    (is (= {:x 0 :y 10 :w 40 :h 10} (select-keys c [:x :y :w :h])))  ; auto skips row0 col1, wraps to row1 col0
    ;; No two items share the same (x,y).
    (is (= 3 (count (distinct [(select-keys a [:x :y]) (select-keys b [:x :y]) (select-keys c [:x :y])]))))))

(deftest grid-out-of-range-column-clamps-while-out-of-range-row-grows
  ;; grid-column: 5 with only 2 declared column tracks doesn't crash and
  ;; doesn't implicitly create a new track (out of scope) -- it clamps to
  ;; the last column (0-based index 1). grid-row: 5 with NO declared row
  ;; tracks isn't clamped at all (rows are not a fixed axis in this engine)
  ;; -- it simply grows however many (empty, 0px-tall) rows are needed to
  ;; reach row index 4, each still consuming one `gap` -- so the item lands
  ;; at y = 4 * (0 + gap) = 8 with gap:2, not at row 0.
  (let [tree (grid-tree {:grid-template-columns "50px 80px" :gap 2 :padding 0 :width 130}
                         [[nil 10 {:grid-column 5 :grid-row 5}]])
        ops (layout/draw-ops tree {:width 130})
        [container item] (node-ops ops)]
    (is (= 2 (count (node-ops ops))))                        ; container + 1 child, no crash
    (is (= {:x 52 :y 8 :w 80 :h 10} (select-keys item [:x :y :w :h])))))

;; ---- grid-template-areas / grid-area named placement ----

(deftest grid-template-areas-canonical-sidebar-header-main-footer-layout
  ;; The canonical real-CSS example this feature exists for:
  ;;   grid-template-columns: 200px 1fr;
  ;;   grid-template-rows: 60px 1fr 40px;
  ;;   grid-template-areas: "sidebar header" "sidebar main" "sidebar footer";
  ;; with .header/.main/.footer/.sidebar each declaring grid-area: <name>.
  ;; Container width 400 (200 + 1fr-resolved-200) / height 300
  ;; (60 + 1fr-resolved-200 + 40) makes every track size concrete: column
  ;; widths [200 200], row heights [60 200 40]. Each item's own height is
  ;; set explicitly (per this engine's documented "height never auto-stretches
  ;; across a row span" convention -- see layout-grid's docstring) to exactly
  ;; the combined height of the rows its area spans, so the assertions below
  ;; prove BOTH that x/y/w land on the real resolved track offsets/sizes AND
  ;; that the sidebar's single item spans the full 3-row combined rectangle.
  (let [tree (grid-tree {:grid-template-columns "200px 1fr"
                          :grid-template-rows "60px 1fr 40px"
                          :grid-template-areas "\"sidebar header\" \"sidebar main\" \"sidebar footer\""
                          :gap 0 :padding 0 :width 400 :height 300}
                         [[nil 60 {:grid-area "header"}]
                          [nil 200 {:grid-area "main"}]
                          [nil 40 {:grid-area "footer"}]
                          [nil 300 {:grid-area "sidebar"}]])
        ops (layout/draw-ops tree {:width 400})
        [container header main footer sidebar] (node-ops ops)]
    (is (= 400 (:w container)))
    (is (= 300 (:h container)))
    ;; header: row 0, col 1 (the 1fr->200px column) -> x=200 (past the 200px
    ;; sidebar column), y=0 (row 0's offset), w=200 (the 1fr column's
    ;; resolved width), h=60 (its own declared height, matching the 60px row
    ;; track it occupies).
    (is (= {:x 200 :y 0 :w 200 :h 60} (select-keys header [:x :y :w :h])))
    ;; main: row 1, col 1 -> y=60 (past row 0's 60px), w=200, h=200 (matching
    ;; the 1fr row track's own resolved 200px height).
    (is (= {:x 200 :y 60 :w 200 :h 200} (select-keys main [:x :y :w :h])))
    ;; footer: row 2, col 1 -> y=260 (past rows 0+1: 60+200), w=200, h=40
    ;; (matching the 40px row track).
    (is (= {:x 200 :y 260 :w 200 :h 40} (select-keys footer [:x :y :w :h])))
    ;; sidebar: ONE item spanning the union of every row in column 0 (rows
    ;; 0..3) -> x=0, y=0 (starts at the very first row), w=200 (the sidebar's
    ;; own 200px column, not spanning any other column), h=300 (the full
    ;; combined height of all 3 row tracks: 60+200+40).
    (is (= {:x 0 :y 0 :w 200 :h 300} (select-keys sidebar [:x :y :w :h])))))

(deftest grid-template-areas-named-area-spans-combined-multi-row-multi-col-rectangle
  ;; grid-template-areas "box box skip" / "box box skip": "box" spans BOTH
  ;; rows and the first two columns -- proving parse-grid-template-areas
  ;; computes the union of every same-named cell as a single combined
  ;; rectangle (not e.g. only the first row/column it happens to appear in)
  ;; and place-grid-items places exactly ONE item there, at the union's own
  ;; combined width (auto-filled across both spanned columns, exactly like
  ;; a multi-column grid-column span already does).
  (let [tree (grid-tree {:grid-template-columns "40px 50px 60px"
                          :grid-template-rows "10px 20px"
                          :grid-template-areas "\"box box skip\" \"box box skip\""
                          :gap 0 :padding 0 :width 150}
                         [[nil 30 {:grid-area "box"}]])
        ops (layout/draw-ops tree {:width 150})
        [container item] (node-ops ops)]
    (is (= 150 (:w container)))
    (is (= 30 (:h container)))                              ; 10 + 20, no gap/padding
    ;; Combined rectangle: col0+col1 (40+50=90) wide, starting at row0/col0 --
    ;; ONE item, not two, occupying the full 2-row x 2-col cell union.
    (is (= {:x 0 :y 0 :w 90 :h 30} (select-keys item [:x :y :w :h])))
    (is (= 2 (count (node-ops ops))))))                     ; container + exactly 1 item

(deftest grid-area-unrecognized-name-falls-back-to-auto-placement
  ;; grid-area: "nonexistent" doesn't match any name declared in the
  ;; container's grid-template-areas ("a"/"b" only) -- must NOT crash, and
  ;; the item must be honestly auto-placed (into the first free cell,
  ;; row-major) rather than guessing at a nonsense rectangle.
  (let [tree (grid-tree {:grid-template-columns "40px 40px"
                          :grid-template-areas "\"a b\""
                          :gap 0 :padding 0 :width 80}
                         [[nil 10 {:grid-area "nonexistent"}]])
        ops (layout/draw-ops tree {:width 80})
        [container item] (node-ops ops)]
    (is (= 2 (count (node-ops ops))))                       ; container + 1 item, no crash
    (is (= {:x 0 :y 0 :w 40 :h 10} (select-keys item [:x :y :w :h])))))

(deftest grid-template-areas-inconsistent-row-lengths-degrades-to-no-op
  ;; Malformed/inconsistent grid-template-areas (rows with different token
  ;; counts -- real CSS requires every row to declare the same number of
  ;; columns) falls back to "no template" (parse-grid-template-areas returns
  ;; nil) rather than crashing or guessing at a shape: a grid-area reference
  ;; to a name inside it (even "a", which unambiguously appears) falls back
  ;; to auto-placement, the same treatment an unrecognized name gets.
  (let [tree (grid-tree {:grid-template-columns "40px 40px"
                          :grid-template-areas "\"a b\" \"a b c\""
                          :gap 0 :padding 0 :width 80}
                         [[nil 10 {:grid-area "a"}]])
        ops (layout/draw-ops tree {:width 80})
        [container item] (node-ops ops)]
    (is (= 2 (count (node-ops ops))))                       ; no crash
    (is (= {:x 0 :y 0 :w 40 :h 10} (select-keys item [:x :y :w :h])))))

(deftest grid-template-areas-composes-with-a-fully-auto-sibling
  ;; grid-template-areas "a ." / ". .": "a" claims row0/col0 only; row0/col1,
  ;; row1/col0, row1/col1 are NOT claimed by any area name ("." is real
  ;; CSS's own 'intentionally empty cell' marker, not a name any item can
  ;; reference). A second, fully-auto child (no grid-area/grid-column/
  ;; grid-row at all) composes exactly like it already does alongside
  ;; grid-column/grid-row explicit placement (see
  ;; grid-explicit-item-does-not-collide-with-auto-placed-siblings above):
  ;; the auto item's row-major scan skips (0,0) (claimed by "a") and lands
  ;; in the very next free cell, (0,1).
  (let [tree (grid-tree {:grid-template-columns "40px 40px"
                          :grid-template-areas "\"a .\" \". .\""
                          :gap 0 :padding 0 :width 80}
                         [[nil 10 {:grid-area "a"}]
                          [nil 12]])
        ops (layout/draw-ops tree {:width 80})
        [container a auto] (node-ops ops)]
    (is (= {:x 0 :y 0 :w 40 :h 10} (select-keys a [:x :y :w :h])))
    (is (= {:x 40 :y 0 :w 40 :h 12} (select-keys auto [:x :y :w :h])))))

(deftest grid-template-areas-establishes-column-count-when-no-explicit-tracks
  ;; No grid-template-columns declared at all: the areas template's own
  ;; column count (2, from "left right") establishes the grid's column
  ;; shape (equal-width 1fr fallback tracks) instead of this engine's usual
  ;; "no tracks declared" single-full-width-column fallback -- so "left" and
  ;; "right" land in two DISTINCT, evenly-split columns rather than
  ;; collapsing onto one full-width column.
  (let [tree (grid-tree {:grid-template-areas "\"left right\""
                          :gap 0 :padding 0 :width 100}
                         [[nil 10 {:grid-area "left"}]
                          [nil 10 {:grid-area "right"}]])
        ops (layout/draw-ops tree {:width 100})
        [container left right] (node-ops ops)]
    (is (= {:x 0 :y 0 :w 50 :h 10} (select-keys left [:x :y :w :h])))
    (is (= {:x 50 :y 0 :w 50 :h 10} (select-keys right [:x :y :w :h])))))

(deftest grid-column-explicit-on-item-wins-over-grid-area-same-axis
  ;; When an item declares BOTH grid-area AND an explicit grid-column (a
  ;; contradictory-but-real-possible declaration set), this engine's
  ;; documented precedence (see item-grid-placement) is per-axis: the
  ;; explicit grid-column wins for the COLUMN axis (mirroring real CSS's
  ;; longhand-conflict resolution, since grid-area ultimately resolves to
  ;; the same four longhands grid-column/grid-row do), while grid-area's own
  ;; ROW range (not overridden by any explicit grid-row here) still applies.
  (let [tree (grid-tree {:grid-template-columns "40px 40px 40px"
                          :grid-template-rows "10px 20px"
                          :grid-template-areas "\"a a b\" \"a a b\""
                          :gap 0 :padding 0 :width 120}
                         [[nil 5 {:grid-area "a" :grid-column 3}]])
        ops (layout/draw-ops tree {:width 120})
        [container item] (node-ops ops)]
    ;; Column: explicit grid-column:3 wins -> 0-based col index 2 (x=80,
    ;; w=40), NOT area "a"'s own col range (0..2, which would've been x=0
    ;; w=80).
    ;; Row: grid-area "a" still supplies the row range (rows 0..2, spanning
    ;; both the 10px and 20px row tracks) since no explicit grid-row was
    ;; declared.
    (is (= {:x 80 :y 0 :w 40 :h 5} (select-keys item [:x :y :w :h])))))

;; ---- ::before / ::after generated content ----
;;
;; Unlike the rest of this file (which sets style attrs directly via
;; kotoba.wasm.dom, bypassing cssom.core's cascade -- see grid-tree's
;; docstring above), these run the real end-to-end pipeline: CSS text ->
;; cssom.core/parse-rules + apply-cascade -> kotoba.wasm.dom tree ->
;; cssom.layout/draw-ops. That's the only way to prove the two namespaces
;; are actually wired together, not just that cssom.layout can read a
;; hand-set attr.

(deftest before-and-after-generated-content-renders-as-real-text-around-children
  ;; p's real content is a SINGLE text child sandwiched between ::before
  ;; and ::after -- this exercises with-generated-content's documented
  ;; "::before and ::after both wrapping one shared real text child" tie
  ;; -break: ::before (checked first) merges with that one real text
  ;; child onto one shared line; by the time ::after is checked there is
  ;; no real text child left for it to see, so ::after stays its own
  ;; separate, unmerged row -- never a three-way merge of all of
  ;; ::before+text+::after into a single run.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [child doc] (dom/create-text-node doc "middle")
        doc (dom/append-child doc p child)
        rules (css/parse-rules
               "p { color: black }
                p::before { content: \"→ \"; color: red }
                p::after { content: \" ←\" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= 2 (count text-ops))
        "::before merges with the real child text into ONE draw-op; ::after
         stays a separate second draw-op (the real text child it would
         otherwise adjoin was already consumed by the ::before merge) --
         2 :text draw-ops total, not 3 (unmerged) and not 1 (a three-way
         merge this feature deliberately does not attempt)")
    (is (= ["→ middle" " ←"] (mapv :text text-ops))
        "generated ::before text is concatenated with the real child text
         it merged with, in document order; generated ::after text
         remains on its own, separate, in document order after that")
    (is (= "red" (:color (first text-ops)))
        "the merged ::before+text run paints with ::before's own declared
         color, not the element's inherited black -- see
         merge-generated-with-text's documented single-color-per-merged-run
         simplification")
    (is (= "black" (:color (second text-ops)))
        "::after (unmerged here) still inherits the element's own color,
         exactly like any real child would, unaffected by the merge
         feature")
    (is (< (:y (first text-ops)) (:y (second text-ops)))
        "::after still lays out as its own separate row BELOW the merged
         ::before+text run, in this engine's vertical block flow -- the
         merge only ever collapses a pseudo with ITS OWN adjacent real
         text onto one shared line, it doesn't turn ::after into part of
         that same line")))

(deftest before-content-empty-string-still-produces-a-real-empty-text-draw-op
  ;; content: ""; is a common icon-only generated-content idiom -- still a
  ;; box, just with no visible text.
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: \"\" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops)))
    (is (= "" (:text (first text-ops))))))

(deftest no-content-declared-produces-no-generated-text-box
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        [child doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc span child)
        rules (css/parse-rules "span::before { color: red }") ; no content declared
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops))
        "no content declared -> no generated-content box, just the real text")
    (is (= "hi" (:text (first text-ops))))))

(deftest unparseable-content-value-does-not-crash-and-produces-no-box
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: url(icon.png) }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})]
    (is (empty? (filterv #(= :text (:draw/op %)) ops))
        "url()/none content is out of scope -- must not crash layout, and
         produces no generated-content box")))

;; ---- ::before/::after generated `content: attr(name)` ----
;;
;; Same real end-to-end pipeline as the section above (CSS text ->
;; cssom.core/parse-rules + apply-cascade -> kotoba.wasm.dom tree ->
;; cssom.layout/draw-ops) -- proves attr() resolves against a REAL element's
;; REAL attribute and paints through the identical layout-text path a
;; quoted `content: \"...\"` literal already uses, not a forked one.

(deftest before-content-attr-renders-the-elements-own-attribute-value-as-real-text
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        doc (dom/set-attribute doc p :data-x "hello")
        [child doc] (dom/create-text-node doc "middle")
        doc (dom/append-child doc p child)
        rules (css/parse-rules "p::before { content: attr(data-x); color: red }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    ;; p's only real child is a text node directly adjacent to ::before,
    ;; so this is exactly the bounded merge case with-generated-content
    ;; implements (see its docstring) -- one merged :text draw-op sharing
    ;; one line, not two stacked block rows.
    (is (= 1 (count text-ops))
        "::before's resolved attr() text is immediately followed by the
         element's own real text child with nothing else in between, so
         they merge into ONE :text draw-op sharing one line")
    (is (= ["hellomiddle"] (mapv :text text-ops))
        "content: attr(data-x) renders the element's own real data-x
         attribute value as real, painted text -- not the literal
         'attr(data-x)' source text, and not nothing -- concatenated with
         the adjacent real text child by the merge")
    (is (= "red" (:color (first text-ops)))
        "the merged run paints with ::before's own declared color (the
         merge keeps the generated node's own :generated/style, see
         merge-generated-with-text) -- a documented simplification versus
         real CSS's separate-per-run coloring, since this file's :text
         draw-op has no way to paint two colors on one line")))

(deftest before-content-attr-missing-attribute-still-produces-an-empty-text-draw-op
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: attr(data-missing); }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops))
        "a missing attr() attribute is still a real generated-content box --
         same as content: \"\" -- not the same as no content at all")
    (is (= "" (:text (first text-ops))))))

(deftest before-content-composes-string-literal-and-attr-reference-into-real-text
  ;; Stretch goal, end to end: `content: \"Price: \" attr(data-price);`
  ;; renders as one concatenated real :text draw-op.
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        doc (dom/set-attribute doc span :data-price "10")
        rules (css/parse-rules "span::before { content: \"Price: \" attr(data-price); }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops)))
    (is (= "Price: 10" (:text (first text-ops)))
        "the quoted literal and the resolved attr() value concatenate into
         one real painted text run")))

;; ---- ::before/::after generated `content: counter(name)` ----
;;
;; Same real end-to-end pipeline (CSS text -> cssom.core/parse-rules +
;; apply-cascade -> kotoba.wasm.dom tree -> cssom.layout/draw-ops). Unlike
;; attr(), a counter's value is the cumulative effect of every
;; counter-reset/counter-increment declaration on every element preceding
;; it in document tree order -- these tests prove that running total is
;; genuinely computed across REAL sibling elements, not faked or resolved
;; independently per node.

(deftest three-sibling-list-items-render-sequential-counter-numbers-as-real-text
  ;; THE canonical real-world CSS-counters use case: automatic sequential
  ;; numbering across sibling <li> elements, purely from CSS -- and THE
  ;; exact pattern (confirmed live in kotoba-lang/browser's own
  ;; `#step-counter` demo) that exposed the "generated content renders as
  ;; a separate stacked line instead of sharing one line with the
  ;; element's own text" bug with-generated-content's adjacent-text merge
  ;; now fixes: each <li>'s ::before is immediately followed by that same
  ;; <li>'s own real text child, so each pair merges into ONE :text
  ;; draw-op (three total, one per <li>), not six stacked block rows.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [li1-text doc] (dom/create-text-node doc "one")
        doc (dom/append-child doc li1 li1-text)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [li2-text doc] (dom/create-text-node doc "two")
        doc (dom/append-child doc li2 li2-text)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li3)
        [li3-text doc] (dom/create-text-node doc "three")
        doc (dom/append-child doc li3 li3-text)
        rules (css/parse-rules
               "li { counter-increment: item }
                li::before { content: counter(item) \". \"; }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. one" "2. two" "3. three"] (mapv :text text-ops))
        "each <li>'s ::before renders that <li>'s OWN incremented counter
         value (\"1. \"/\"2. \"/\"3. \"), MERGED with its own real text
         child (\"one\"/\"two\"/\"three\") into one run, in document order
         -- a genuine running total across siblings, not three
         independently-resolved copies of the same value, and (the fix
         under test) sharing one line with the real text rather than
         sitting on a separate stacked line above it")
    (is (apply < (mapv :y text-ops))
        "the three <li>s still stack as three separate lines (one merged
         run per <li>) -- this merge only collapses a ::before with its
         OWN adjacent real text onto one line, it does not affect normal
         block stacking across sibling elements")))

(deftest counter-never-reset-or-incremented-still-produces-a-real-zero-text-draw-op
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: counter(untouched); }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops))
        "a counter reference is still a real generated-content box even
         when the counter was never touched")
    (is (= "0" (:text (first text-ops)))
        "real CSS: an un-reset, un-incremented counter reads as 0, not
         nothing and not a crash")))

(deftest generated-content-wraps-long-text-through-the-same-word-wrap-path
  ;; Proves ::before/::after content flows through the exact same
  ;; text-lines word-wrapping real text uses, not a forked one-line-only
  ;; implementation.
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules
               "span::before { content: \"the quick brown fox jumps over\" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (> (count text-ops) 1)
        "long generated content wraps onto multiple lines, same as real text at this width")
    (is (= (str/split "the quick brown fox jumps over" #"\s+")
           (mapcat #(str/split (:text %) #"\s+") text-ops))
        "word-wrapping must not lose or reorder any words")))

;; ---- ::before/::after merged with ONE directly-adjacent real text-node
;;      sibling (see with-generated-content/merge-generated-with-text) ----
;;
;; This is the fix for a real bug found via kotoba-lang/browser's own live
;; demo (public/browser-demo.html): `#step-counter li::before { content:
;; counter(step) \". \" }` immediately followed by that <li>'s own real
;; text child rendered as TWO SEPARATE stacked block rows (draw-ops
;; `{:text \"1. \" :y 828}` / `{:text \"...\" :y 860}`, 32px apart -- a
;; full extra line) instead of real CSS's ONE shared line. The existing
;; smoke test covering that demo only ever asserted on generated content's
;; COMPUTED STRING value, never its actual on-screen line position, so
;; this divergence from real CSS was invisible until verified against
;; live draw-ops. These tests prove the narrow, bounded merge fix (see
;; with-generated-content's docstring for its exact scope) and that
;; everything explicitly OUT of that scope is unaffected.

(deftest before-immediately-followed-by-real-text-merges-onto-one-shared-line
  ;; The general (non-counter, non-attr) case of the bug above: a literal
  ;; ::before content string immediately followed by the element's own
  ;; real text child, nothing else in between.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [child doc] (dom/create-text-node doc "world")
        doc (dom/append-child doc p child)
        rules (css/parse-rules "p::before { content: \"hello \" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)
        node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops)
        ;; Reference: an otherwise-identical <p> with no ::before at all,
        ;; whose own real text is the SAME already-concatenated string --
        ;; a genuine, unambiguous single line. The merged case's box
        ;; height must match this exactly (not be taller by a whole extra
        ;; line, which is exactly what the real bug produced).
        [p2 doc2] (dom/create-element dom/empty-document :p)
        doc2 (dom/set-root doc2 p2)
        [child2 doc2] (dom/create-text-node doc2 "hello world")
        doc2 (dom/append-child doc2 p2 child2)
        [_ doc2] (dom/consume-ops doc2)
        tree2 (dom/tree doc2)
        ops2 (layout/draw-ops tree2 {:width 480})
        ref-node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops2)]
    (is (= 1 (count text-ops))
        "::before immediately followed by the element's own real text
         child merges into ONE :text draw-op -- not two separate block
         rows the way this engine renders every other pair of children")
    (is (= "hello world" (:text (first text-ops)))
        "the generated ::before string and the real text node's string
         are concatenated in document order into a single text run")
    (is (= (:h ref-node-op) (:h node-op))
        "the element's own content box is exactly as tall as a plain <p>
         whose only child is the SAME already-concatenated text -- proving
         this really is one shared line box, not two stacked lines (the
         real bug produced a content box a whole extra line-height taller
         than this reference)")))

(deftest after-immediately-preceded-by-real-text-merges-onto-one-shared-line
  ;; The symmetric ::after case: a real text child immediately followed by
  ;; ::after generated content, with no ::before involved at all (unlike
  ;; before-and-after-generated-content-renders-as-real-text-around-children,
  ;; which covers the ::before-and-::after-together tie-break instead).
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [child doc] (dom/create-text-node doc "hello")
        doc (dom/append-child doc p child)
        rules (css/parse-rules "p::after { content: \" world\" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)
        node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops)
        ;; Same reference-box comparison as the ::before-side test above.
        [p2 doc2] (dom/create-element dom/empty-document :p)
        doc2 (dom/set-root doc2 p2)
        [child2 doc2] (dom/create-text-node doc2 "hello world")
        doc2 (dom/append-child doc2 p2 child2)
        [_ doc2] (dom/consume-ops doc2)
        tree2 (dom/tree doc2)
        ops2 (layout/draw-ops tree2 {:width 480})
        ref-node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops2)]
    (is (= 1 (count text-ops))
        "the real text child immediately followed by ::after merges into
         ONE :text draw-op, same as the ::before-side merge")
    (is (= "hello world" (:text (first text-ops)))
        "the real text node's string and the generated ::after string are
         concatenated in document order (real text first) into a single
         text run")
    (is (= (:h ref-node-op) (:h node-op))
        "one shared line box, exactly like the ::before-side merge")))

(deftest before-content-with-first-real-child-an-element-does-not-merge
  ;; Explicit out-of-scope case #1: the element's FIRST real child is
  ;; itself an ELEMENT (a <span> with its own nested text), not a text
  ;; node -- real-text-child returns nil for it, so ::before does NOT
  ;; merge with anything here. This must stay IDENTICAL to this engine's
  ;; pre-existing (still-broken, still out of scope) two-separate-rows
  ;; behavior: no regression, not a fix, just unchanged.
  (let [[li doc] (dom/create-element dom/empty-document :li)
        doc (dom/set-root doc li)
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc li span)
        [span-text doc] (dom/create-text-node doc "nested")
        doc (dom/append-child doc span span-text)
        rules (css/parse-rules "li::before { content: \"X \" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["X " "nested"] (mapv :text text-ops))
        "::before and the <span>'s own nested text stay two genuinely
         separate draw-ops -- no merge fires when the immediately
         adjacent real child is an element, not a text node")
    (is (< (:y (first text-ops)) (:y (second text-ops)))
        "still two stacked rows, exactly as before this fix existed --
         this case is a deliberate, documented scope boundary, not a
         regression")))

(deftest before-merges-with-first-adjacent-text-but-a-later-element-sibling-is-unaffected
  ;; Explicit out-of-scope case #2: `<li>text<b>bold</b></li>` with a
  ;; ::before -- the FIRST real child ('text') IS a genuine adjacent text
  ;; node, so ::before DOES merge with it (that's this fix, working
  ;; exactly as scoped); the <b>bold</b> element that follows is NOT part
  ;; of that merge (this feature only ever combines a pseudo with ONE
  ;; directly-adjacent real text-node sibling, never a whole run of mixed
  ;; children) and keeps stacking as its own separate block row below,
  ;; exactly as broken/unmerged as it already was -- proving the merge is
  ;; correctly bounded to just the first pair, not a slippery slope into
  ;; general inline flow.
  (let [[li doc] (dom/create-element dom/empty-document :li)
        doc (dom/set-root doc li)
        [t doc] (dom/create-text-node doc "text")
        doc (dom/append-child doc li t)
        [b doc] (dom/create-element doc :b)
        doc (dom/append-child doc li b)
        [b-text doc] (dom/create-text-node doc "bold")
        doc (dom/append-child doc b b-text)
        rules (css/parse-rules "li::before { content: \"X \" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["X text" "bold"] (mapv :text text-ops))
        "::before merges with the immediately-adjacent real text child
         ('text') into one run; the <b>'s own text ('bold') is a
         genuinely separate draw-op, unaffected by the merge")
    (is (< (:y (first text-ops)) (:y (second text-ops)))
        "the merged run and the <b> element still stack as two separate
         rows -- this fix narrows the FIRST gap (pseudo-to-adjacent-text)
         without attempting to also merge the remaining text-vs-element
         gap, which stays exactly as unmerged as this engine's general
         lack of inline flow already made it (see this namespace's own
         docstring)")))

;; ---- adjacent real DOM text-node siblings merging onto one shared line
;;      (see merge-adjacent-text-runs) ----
;;
;; A SECOND, independent narrow exception to this file's general lack of
;; inline flow (see the ns docstring and layout-children-block's own
;; docstring): a RUN of two-or-more consecutive real text-node DOM
;; children -- nothing but each other in between, no element boundary --
;; is collapsed into ONE text child before layout. This is a REAL shape
;; kotoba-lang/htmldom's own tokenizer produces (its comment handling
;; discards an HTML comment as contributing no token at all, so
;; `<p>Hello <!--c-->world</p>` parses to a <p> with two adjacent sibling
;; :text DOM children -- confirmed directly against htmldom's own
;; tokenize/parse-into-document), not a hypothetical shape invented just
;; to exercise this code path.

(deftest two-adjacent-real-text-nodes-merge-onto-one-shared-line
  ;; The minimal shape: no ::before/::after involved at all, just two real
  ;; DOM text-node siblings with nothing else in between -- exactly what
  ;; htmldom's comment-discarding tokenizer produces for
  ;; `<p>Hello <!--c-->world</p>`.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [t1 doc] (dom/create-text-node doc "Hello ")
        doc (dom/append-child doc p t1)
        [t2 doc] (dom/create-text-node doc "world")
        doc (dom/append-child doc p t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)
        node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops)
        ;; Reference: a <p> whose only child is the SAME already-
        ;; concatenated string as ONE real text node -- a genuine,
        ;; unambiguous single line. The merged case's box height must
        ;; match this exactly (not be taller by a whole extra line, which
        ;; is exactly what this engine's general lack of inline flow would
        ;; otherwise produce for two stacked block rows).
        [p2 doc2] (dom/create-element dom/empty-document :p)
        doc2 (dom/set-root doc2 p2)
        [child2 doc2] (dom/create-text-node doc2 "Hello world")
        doc2 (dom/append-child doc2 p2 child2)
        [_ doc2] (dom/consume-ops doc2)
        tree2 (dom/tree doc2)
        ops2 (layout/draw-ops tree2 {:width 480})
        ref-node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops2)]
    (is (= 1 (count text-ops))
        "two adjacent real text-node siblings merge into ONE :text
         draw-op -- not two separate stacked block rows, this engine's
         general (and otherwise still correct) behavior for every other
         pair of sibling children")
    (is (= "Hello world" (:text (first text-ops)))
        "the two real text nodes' own strings concatenate, in document
         order, with no extra separator inserted -- exactly as if the
         source HTML had been one single text node all along")
    (is (= (:h ref-node-op) (:h node-op))
        "the element's own content box is exactly as tall as a plain <p>
         whose only child is the SAME already-concatenated text -- proving
         this really is one shared line box, not two stacked lines")))

(deftest three-adjacent-real-text-nodes-merge-as-a-whole-run-not-just-a-pair
  ;; More than one HTML comment in a row (`<p>a<!--1-->b<!--2-->c</p>`)
  ;; produces a run of THREE adjacent text-node DOM children, not just
  ;; two -- proving merge-adjacent-text-runs really walks a whole run,
  ;; not a fixed pairwise merge.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [t1 doc] (dom/create-text-node doc "a")
        doc (dom/append-child doc p t1)
        [t2 doc] (dom/create-text-node doc "b")
        doc (dom/append-child doc p t2)
        [t3 doc] (dom/create-text-node doc "c")
        doc (dom/append-child doc p t3)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops))
        "all three adjacent real text-node siblings merge into ONE :text
         draw-op, not two (a fixed pairwise merge would have stopped at
         the first two, leaving the third stacked separately)")
    (is (= "abc" (:text (first text-ops))))))

(deftest text-run-interrupted-by-an-element-does-not-merge-across-it
  ;; `<li>a<b>x</b>b</li>` -- the two real text-node fragments ('a' and
  ;; 'b') are NOT adjacent to each other (a <b> element sits between
  ;; them), so they must NOT merge across it -- each stays its own
  ;; one-node run, exactly as unmerged as this engine's general lack of
  ;; inline flow already made it. Proves merge-adjacent-text-runs only
  ;; ever combines children that are genuinely adjacent in the children
  ;; vector, never text nodes anywhere else on the same element.
  (let [[li doc] (dom/create-element dom/empty-document :li)
        doc (dom/set-root doc li)
        [ta doc] (dom/create-text-node doc "a")
        doc (dom/append-child doc li ta)
        [b doc] (dom/create-element doc :b)
        doc (dom/append-child doc li b)
        [bx doc] (dom/create-text-node doc "x")
        doc (dom/append-child doc b bx)
        [tb doc] (dom/create-text-node doc "b")
        doc (dom/append-child doc li tb)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["a" "x" "b"] (mapv :text text-ops))
        "three genuinely separate draw-ops -- the <b> element boundary
         blocks the 'a'/'b' text fragments from merging with each other,
         even though both are real text-node children of the same <li>")
    (is (apply < (mapv :y text-ops))
        "still three stacked rows -- this engine's general lack of inline
         flow, unaffected by the text-run merge")))

(deftest mixed-bare-string-and-map-shaped-text-nodes-still-merge-as-one-run
  ;; real-text-child accepts two shapes a text-node child can have by the
  ;; time layout.cljc sees it: a bare string (what kotoba.wasm.dom/tree
  ;; unwraps every real text node to) and the `{:node/type :text :text
  ;; ...}` map shape layout-node's own dispatch also still recurs
  ;; through. merge-adjacent-text-runs must merge a run mixing both
  ;; shapes, not just a run of bare strings -- exercised directly here
  ;; (rather than through dom/tree, which only ever produces the bare-
  ;; string shape) since this is otherwise impossible to reach through
  ;; the normal DOM-building pipeline every other test in this file uses.
  (let [{:keys [draw]} (layout/layout-node
                        layout/default-theme 0 0 480 1.0 inherited-text
                        {:node/id 1 :node/type :element :tag :p :attrs {}
                         :children ["Hello " {:node/type :text :text "world"}]})
        text-ops (filterv #(= :text (:draw/op %)) draw)]
    (is (= 1 (count text-ops))
        "a bare-string text child immediately adjacent to a map-shaped
         `{:node/type :text ...}` text child still merge into ONE run")
    (is (= "Hello world" (:text (first text-ops))))))

;; ---- composition: ::before/::after generated content immediately
;;      adjacent to a RUN of several real text-node children ----
;;
;; Both this file's narrow inline-adjacency exceptions -- the
;; ::before/::after<->text merge (merge-generated-with-text, landed in
;; the previous fix) and this file's own adjacent-real-text-run merge
;; (merge-adjacent-text-runs, this fix) -- must compose correctly:
;; with-generated-content runs merge-adjacent-text-runs FIRST, so a
;; ::before/::after directly bordering what was originally several real
;; text-node siblings sees the WHOLE already-combined run as its one
;; adjacent text child, not just the nearest fragment of it.

(deftest before-content-composes-with-multiple-adjacent-real-text-children-into-one-line
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [t1 doc] (dom/create-text-node doc "hello ")
        doc (dom/append-child doc p t1)
        [t2 doc] (dom/create-text-node doc "world")
        doc (dom/append-child doc p t2)
        rules (css/parse-rules "p::before { content: \"X \" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)
        node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops)
        ;; Reference: an otherwise-identical <p> with no ::before at all,
        ;; whose own real text is the SAME fully-concatenated string as
        ;; ONE text node -- a genuine, unambiguous single line.
        [p2 doc2] (dom/create-element dom/empty-document :p)
        doc2 (dom/set-root doc2 p2)
        [child2 doc2] (dom/create-text-node doc2 "X hello world")
        doc2 (dom/append-child doc2 p2 child2)
        [_ doc2] (dom/consume-ops doc2)
        tree2 (dom/tree doc2)
        ops2 (layout/draw-ops tree2 {:width 480})
        ref-node-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops2)]
    (is (= 1 (count text-ops))
        "::before merges with the WHOLE two-node adjacent real text run,
         not just the first fragment -- ONE :text draw-op total")
    (is (= "X hello world" (:text (first text-ops)))
        "generated ::before text concatenated with BOTH real text
         children's own strings, in document order")
    (is (= (:h ref-node-op) (:h node-op))
        "exactly one line's worth of height -- proving the composition of
         both merges really produces a single shared line, not two (or
         three) stacked rows")))

;; ---- non-rendered (metadata) elements ----
;;
;; <head>/<title>/<script>/<style>/<meta>/<link> are never part of a real
;; browser's visual rendering tree, full stop -- independent of any
;; `display` a stylesheet declares for them. Confirmed against a real
;; rendering bug: loading kotoba-lang/browser's own demo page in an actual
;; Chrome tab showed a real background rect for <head>/<title> and the
;; <script> tag's raw JS source painted as garbled visible text.

(deftest head-and-title-contribute-zero-draw-ops
  ;; <head> as the document root, with a real, non-empty <title> text
  ;; child -- proves the exclusion recurses into (and blocks) children too,
  ;; not just the immediate element.
  (let [[head doc] (dom/create-element dom/empty-document :head)
        doc (dom/set-root doc head)
        [title doc] (dom/create-element doc :title)
        doc (dom/append-child doc head title)
        [text doc] (dom/create-text-node doc "Page Title")
        doc (dom/append-child doc title text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (is (= [] ops)
        "head (and its title child, and the title's own real, non-empty
         text content) contribute zero draw-ops of any kind -- no rect, no
         text, no :node")))

(deftest script-raw-source-contributes-zero-draw-ops
  ;; A <script>'s raw JS source is real, multi-line, and contains a
  ;; distinctive string that would obviously show up as garbled visible text
  ;; if this exclusion regressed.
  (let [[script doc] (dom/create-element dom/empty-document :script)
        doc (dom/set-root doc script)
        [text doc] (dom/create-text-node
                    doc
                    "document.title = 'DISTINCTIVE_MARKER_STRING_998877';\nconsole.log('hi');")
        doc (dom/append-child doc script text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (is (= [] ops))
    (is (not (some #(and (:text %) (str/includes? (:text %) "DISTINCTIVE_MARKER_STRING_998877")) ops))
        "the script's raw source never reaches a :text draw-op")))

(deftest style-block-contributes-zero-draw-ops
  (let [[style-el doc] (dom/create-element dom/empty-document :style)
        doc (dom/set-root doc style-el)
        [text doc] (dom/create-text-node doc "body { color: red; } .stage { width: 760px; }")
        doc (dom/append-child doc style-el text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (is (= [] ops))))

(deftest meta-and-link-contribute-zero-draw-ops
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [meta-el doc] (dom/create-element doc :meta)
        doc (dom/set-attribute doc meta-el :charset "utf-8")
        doc (dom/append-child doc root meta-el)
        [link-el doc] (dom/create-element doc :link)
        doc (dom/set-attribute doc link-el :rel "stylesheet")
        doc (dom/append-child doc root link-el)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (is (not (some #(contains? #{:meta :link} (:tag %)) ops))
        "no draw-op of any kind traces back to meta/link")))

(deftest excluded-sibling-does-not-disturb-normal-sibling-rendering
  ;; A <script> immediately before a real <p> must not change the <p>'s own
  ;; box/position, and the <p> must still render completely normally.
  ;; Whatever "does a hidden box still reserve flow space" quirk
  ;; display:none already has in this engine's block layout, the tag-name
  ;; exclusion must reproduce EXACTLY -- so this compares against an
  ;; explicit display:none sibling rather than assuming zero-space
  ;; reservation one way or the other.
  (let [build (fn [make-first-child]
                (let [[root doc] (dom/create-element dom/empty-document :main)
                      doc (dom/set-root doc root)
                      [first-child doc] (make-first-child doc)
                      doc (dom/append-child doc root first-child)
                      [p doc] (dom/create-element doc :p)
                      doc (dom/append-child doc root p)
                      [text doc] (dom/create-text-node doc "Z")
                      doc (dom/append-child doc p text)
                      [_ doc] (dom/consume-ops doc)]
                  (dom/tree doc)))
        script-tree (build (fn [doc]
                              (let [[el doc] (dom/create-element doc :script)
                                    [t doc] (dom/create-text-node doc "var x = 1;")
                                    doc (dom/append-child doc el t)]
                                [el doc])))
        hidden-div-tree (build (fn [doc]
                                  (let [[el doc] (dom/create-element doc :div)
                                        doc (dom/set-style doc el {:display "none"})
                                        [t doc] (dom/create-text-node doc "hidden")
                                        doc (dom/append-child doc el t)]
                                    [el doc])))
        script-ops (layout/draw-ops script-tree {:width 480})
        hidden-div-ops (layout/draw-ops hidden-div-tree {:width 480})
        node-ops-for (fn [ops] (filterv #(= :node (:draw/op %)) ops))
        text-ops-for (fn [ops] (filterv #(= :text (:draw/op %)) ops))]
    ;; The real <p>Z</p> sibling still renders normally: its own :node box
    ;; plus its text draw-op, exactly as if there were no excluded sibling.
    (is (some #(= :p (:tag %)) (node-ops-for script-ops)))
    (is (= ["Z"] (mapv :text (text-ops-for script-ops))))
    ;; No draw-op at all traces back to the <script> itself.
    (is (not (some #(= :script (:tag %)) script-ops)))
    ;; The <script> sibling positions/sizes the real <p> IDENTICALLY to how
    ;; an explicit display:none sibling already does here -- the exclusion
    ;; doesn't leak into (or diverge from existing display:none behavior
    ;; for) sibling positioning.
    (let [p-box #(select-keys (second (node-ops-for %)) [:x :y :w :h])]
      (is (= (p-box hidden-div-ops) (p-box script-ops))))))

(deftest head-title-script-excluded-body-p-renders-normally
  ;; Mirrors the real, visually-confirmed bug shape:
  ;; <head><title>X</title></head><body><script>Y</script><p>Z</p></body>.
  ;; X (title text) and Y (script source) must produce zero draw-ops of any
  ;; kind; Z (the real <p> content) must still render normally.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [head doc] (dom/create-element doc :head)
        doc (dom/append-child doc root head)
        [title doc] (dom/create-element doc :title)
        doc (dom/append-child doc head title)
        [title-text doc] (dom/create-text-node doc "X")
        doc (dom/append-child doc title title-text)
        [body doc] (dom/create-element doc :body)
        doc (dom/append-child doc root body)
        [script doc] (dom/create-element doc :script)
        doc (dom/append-child doc body script)
        [script-text doc] (dom/create-text-node doc "Y")
        doc (dom/append-child doc script script-text)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc body p)
        [p-text doc] (dom/create-text-node doc "Z")
        doc (dom/append-child doc p p-text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["Z"] (mapv :text text-ops))
        "X (title text) and Y (script source) produce no :text draw-ops at
         all -- only Z, the real <p> content, does")
    (is (not (some #(contains? #{:head :title :script} (:tag %)) ops))
        "no draw-op of any kind (rect/text/node) traces back to
         head/title/script")))

;; ---- calc() width/padding through the real cascade -> layout pipeline ----
;;
;; Unlike the grid-template-columns calc() tests above (which deliberately
;; bypass cssom.core's cascade the same way every other grid-tree test in
;; this file does, since the point under test there is cssom.layout's OWN
;; local calc() resolver), these run the real end-to-end pipeline: CSS text
;; -> cssom.core/parse-rules + apply-cascade -> kotoba.wasm.dom tree ->
;; cssom.layout/draw-ops -- the only way to prove a `calc(100px + 20px)`-
;; shaped `width`/`padding` declaration resolves to the correct real pixel
;; value in actual draw-ops, not just cssom.core's intermediate
;; cascade-resolved :style/* map (mirrors the ::before/::after generated-
;; content tests' own real-pipeline convention above).

(deftest calc-constant-width-and-padding-resolve-to-correct-real-pixels-in-draw-ops
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "box")
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc div child)
        rules (css/parse-rules ".box { width: calc(100px + 20px); padding: calc(2 * 8px) }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)
        child-op (some #(and (= :node (:draw/op %)) (= :span (:tag %)) %) ops)]
    (is (= 120 (:w div-op))
        "width: calc(100px + 20px) resolves to a real 120px box width in
         draw-ops, cascade -> layout, end to end")
    (is (= 16 (:x child-op))
        "padding: calc(2 * 8px) resolves to a real 16px content inset --
         the child (no margin of its own) starts 16px in from its
         padding: calc(2 * 8px) parent's own x origin")))

(deftest calc-with-a-percentage-does-not-resolve-a-width-through-the-real-pipeline
  ;; calc(100% - 20px) is outside this engine's bounded constant-calc()
  ;; subset (it needs this div's own resolved size, which only real layout
  ;; -- not the cascade -- could ever supply) -- parse-style-value leaves
  ;; the whole declaration as the same raw, unparsed string it always fell
  ;; through as before calc() support existed: cssom.core NEVER resolves it
  ;; to a number, which is the actual thing under test here. (cssom.layout's
  ;; OWN resolve-width then applies its pre-existing, calc()-independent
  ;; parse-int -- the same permissive leading-digit-run extraction a plain
  ;; unresolvable `width: 50%` already goes through today, unrelated to
  ;; this feature -- so the box still doesn't crash, it just isn't the
  ;; interesting assertion for a calc()-specific test.)
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules ".box { width: calc(100% - 20px) }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)]
    (is (= "calc(100% - 20px)" (get-in doc [:nodes div :attrs :style/width]))
        "stays the raw unparsed string in the cascade-resolved style map --
         cssom.core never guesses a number for it")
    (is (number? (:w div-op))
        "layout never crashes on the unresolved raw string")))

(deftest malformed-calc-width-does-not-crash-the-real-pipeline
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules ".box { width: calc(100px +) }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)]
    (is (= "calc(100px +)" (get-in doc [:nodes div :attrs :style/width]))
        "a dangling operator with no right-hand operand never resolves at
         the cascade level -- stays the raw unparsed string")
    (is (number? (:w div-op))
        "layout never crashes on the unresolved raw string")))

;; ---- explicit :height/:min-width/:max-width/:left/:top defensive numeric
;; coercion (resolve-height/explicit-length, mirroring resolve-width's own
;; pre-existing parse-int precedent proven by the calc()-with-a-percentage
;; test above) ----
;;
;; cssom.core's parse-style-value intentionally leaves a percentage (or any
;; other value outside this engine's bounded numeric subset, e.g. `auto`) as
;; a raw, unparsed string on an element's own :style/* attrs -- proven above
;; for :width. cssom.layout's resolve-width has always defended against that
;; raw string reaching arithmetic (its own `parse-int` call on :width) -- but
;; :height had NO equivalent defense at all before resolve-height was added:
;; every reader of an explicit :height used the raw cascade value directly,
;; so a raw string reaching :height threw a ClassCastException (String
;; cannot be cast to Number) the instant it reached arithmetic. The most
;; user-visible way that happened: TWO sibling block elements, each given an
;; explicit (percentage, or otherwise cascade-unresolved) height -- the
;; first sibling's raw-string box height got added directly into the
;; running y-offset layout-children-block uses to stack the second sibling.
;; resolve-width's own :min-width/:max-width clamp (`max`/`min` called
;; directly on the raw cascade value, with no coercion at all -- unlike
;; :width itself, one line above in the very same function) and
;; layout-absolute-children's :left/:top (added directly into content-x/
;; content-y) turned out to be two more instances of the identical bug
;; class, fixed by the same explicit-length helper resolve-height wraps.

(deftest explicit-percentage-height-on-sibling-blocks-stacks-correctly-not-a-crash
  ;; Real end-to-end pipeline (CSS text -> cssom.core/parse-rules +
  ;; apply-cascade -> kotoba.wasm.dom tree -> cssom.layout/draw-ops), same
  ;; convention as the calc() tests above. `height: 40%`/`height: 60%` are
  ;; NOT in this engine's numeric subset, so :style/height stays the raw
  ;; string "40%"/"60%" on each div -- exactly the shape that used to reach
  ;; layout-children-block's sibling-stacking `+` as a String and throw.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [div1 doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div1)
        doc (dom/set-attribute doc div1 :class "a")
        [div2 doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div2)
        doc (dom/set-attribute doc div2 :class "b")
        rules (css/parse-rules ".a { height: 40% } .b { height: 60% }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)]
    (is (= "40%" (get-in doc [:nodes div1 :attrs :style/height]))
        "stays the raw unparsed string in the cascade-resolved style map --
         cssom.core never guesses a number for a percentage, same as width")
    ;; Must not throw -- this is the actual regression under test: before
    ;; resolve-height existed, this ClassCastException'd inside
    ;; layout-children-block's sibling-stacking `+`.
    (let [ops (layout/draw-ops tree {:width 480})
          [a b] (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops)]
      ;; cssom.layout's own permissive digit-run parse-int (the SAME
      ;; leading-digit-run extraction resolve-width already applies to a raw
      ;; :width -- e.g. "50%" -> 50, see the calc()-with-a-percentage test's
      ;; own docstring above) reads "40%"/"60%" as 40px/60px.
      (is (= 40 (:h a)))
      (is (= 60 (:h b)))
      ;; The second sibling's y REFLECTS the first's real (coerced) height
      ;; -- not a crash, and not stacked at some bogus/zero offset: default
      ;; theme padding (4, root's own content inset) + div a's own 40px
      ;; height + default gap (4).
      (is (= 4 (:y a)))
      (is (= (+ 4 40 4) (:y b))))))

(deftest explicit-percentage-min-width-does-not-crash-resolve-width
  ;; resolve-width's own :min-width clamp used to call `max` directly on the
  ;; raw cascade value with no coercion at all, unlike :width itself (the
  ;; line right above it in the very same function) -- crashing the same way
  ;; the :height bug above did the moment :min-width was ever a percentage/
  ;; otherwise-unresolved value.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules ".box { width: 50px; min-width: 90% }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)]
    (is (= "90%" (get-in doc [:nodes div :attrs :style/min-width])))
    ;; Same permissive digit-run parse-int as :width's own precedent:
    ;; "90%" -> 90, clamping the box up from its declared 50px width.
    (is (= 90 (:w div-op)))))

(deftest explicit-percentage-max-width-does-not-crash-resolve-width
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules ".box { width: 500px; max-width: 20% }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)]
    (is (= "20%" (get-in doc [:nodes div :attrs :style/max-width])))
    ;; "20%" -> 20, clamping the box down from its declared 500px width.
    (is (= 20 (:w div-op)))))

(deftest explicit-percentage-left-top-on-absolute-child-does-not-crash
  ;; layout-absolute-children added a position:absolute child's raw :left/
  ;; :top straight into content-x/content-y with no coercion at all --
  ;; the same bug class, on a different pair of properties.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules ".box { position: absolute; left: 10%; top: 5% }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)]
    (is (= "10%" (get-in doc [:nodes div :attrs :style/left])))
    ;; root :main's own default padding (4, its content inset) + the
    ;; permissive digit-run parse of "10%"/"5%" -> 10/5.
    (is (= (+ 4 10) (:x div-op)))
    (is (= (+ 4 5) (:y div-op)))))

;; ---- implicit <ul>/<ol> default `<li>` markers (with-implicit-list-markers)
;;      ----
;;
;; Every real browser renders a bullet ("•") before every <li> inside a bare
;; <ul>, and an auto-incrementing decimal number ("1.", "2.", ...) before
;; every <li> inside a bare <ol> -- purely from the UA stylesheet, with ZERO
;; author CSS required. Before this feature, this engine had no
;; list-style/marker/tag-name-default concept at all, so a bare
;; `<ul><li>Apple</li></ul>` rendered ONLY the literal text "Apple" -- no
;; marker of any kind (confirmed directly against kotoba-lang/browser's own
;; live demo before this feature existed). These tests use the SAME real
;; end-to-end pipeline every other section of this file uses (a real
;; kotoba.wasm.dom tree -> cssom.layout/draw-ops, several also through the
;; real cssom.core cascade for the CSS-driven suppression cases), never a
;; hand-rolled stub of with-implicit-list-markers itself.

(deftest plain-ul-renders-bullet-marker-before-each-li-sharing-one-line
  ;; No CSS at all -- proves the marker is genuinely IMPLICIT, not merely
  ;; the pre-existing explicit-::before idiom under a different name.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [t1 doc] (dom/create-text-node doc "Apple")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [t2 doc] (dom/create-text-node doc "Banana")
        doc (dom/append-child doc li2 t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)
        li-node-ops (filterv #(and (= :node (:draw/op %)) (= :li (:tag %))) ops)
        ;; Reference: a <ul> whose <li> children's own real text is ALREADY
        ;; the bullet-prefixed string, as ONE text node each -- a genuine,
        ;; unambiguous single line per <li>. The marker case's per-<li> box
        ;; height must match this exactly (not be a whole extra line
        ;; taller, which is what NOT sharing one line would produce).
        [ul2 doc2] (dom/create-element dom/empty-document :ul)
        doc2 (dom/set-root doc2 ul2)
        [rli1 doc2] (dom/create-element doc2 :li)
        doc2 (dom/append-child doc2 ul2 rli1)
        [rt1 doc2] (dom/create-text-node doc2 "• Apple")
        doc2 (dom/append-child doc2 rli1 rt1)
        [_ doc2] (dom/consume-ops doc2)
        tree2 (dom/tree doc2)
        ops2 (layout/draw-ops tree2 {:width 480})
        ref-li-op (some #(and (= :node (:draw/op %)) (= :li (:tag %)) %) ops2)]
    (is (= ["• Apple" "• Banana"] (mapv :text text-ops))
        "each <li>'s implicit bullet marker is merged onto ONE shared line
         with that <li>'s own real text -- not a separate stacked line, and
         not shared across <li> siblings")
    (is (= (:h ref-li-op) (:h (first li-node-ops)))
        "the first <li>'s own content box is exactly as tall as a <li>
         whose only child is the SAME already-prefixed text as one real
         text node -- proving this really is one shared line, not two
         stacked lines")))

(deftest plain-ol-renders-sequential-decimal-markers-starting-at-one
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "First")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        [t2 doc] (dom/create-text-node doc "Second")
        doc (dom/append-child doc li2 t2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li3)
        [t3 doc] (dom/create-text-node doc "Third")
        doc (dom/append-child doc li3 t3)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. First" "2. Second" "3. Third"] (mapv :text text-ops))
        "sequential, 1-based decimal markers, one per <li>, merged onto
         each <li>'s own shared line, with zero author CSS")))

(deftest nested-ol-inside-li-of-outer-ol-numbers-independently-from-one
  ;; `<ol><li>Outer1<ol><li>Inner1</li><li>Inner2</li></ol></li>
  ;;  <li>Outer2</li></ol>` -- the INNER <ol>'s own <li> children must
  ;; number 1, 2 from their own parent's perspective, completely
  ;; unaffected by the outer <ol>'s own position (the inner <ol> lives
  ;; inside the OUTER <ol>'s first <li>, at outer position 1 -- if
  ;; numbering leaked across nesting levels the inner pair might wrongly
  ;; start from 2, not 1).
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "Outer1")
        doc (dom/append-child doc li1 t1)
        [inner-ol doc] (dom/create-element doc :ol)
        doc (dom/append-child doc li1 inner-ol)
        [inner-li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc inner-ol inner-li1)
        [it1 doc] (dom/create-text-node doc "Inner1")
        doc (dom/append-child doc inner-li1 it1)
        [inner-li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc inner-ol inner-li2)
        [it2 doc] (dom/create-text-node doc "Inner2")
        doc (dom/append-child doc inner-li2 it2)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        [t2 doc] (dom/create-text-node doc "Outer2")
        doc (dom/append-child doc li2 t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. Outer1" "1. Inner1" "2. Inner2" "2. Outer2"] (mapv :text text-ops))
        "the outer <ol> numbers its own two direct <li> children 1/2
         (Outer1/Outer2); the inner <ol> -- nested one level inside the
         outer <ol>'s FIRST <li> -- independently numbers its own two
         direct <li> children 1/2 (Inner1/Inner2) from its own position 1,
         never continuing or being perturbed by the outer list's count")))

(deftest ol-start-attribute-shifts-every-marker-by-a-constant-offset
  ;; A real, common HTML pattern: resuming a numbered list at an arbitrary
  ;; number, e.g. splitting one logical numbered list across two <ol>
  ;; elements around an aside/image.
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        doc (dom/set-attribute doc ol :start "5")
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "Five")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        [t2 doc] (dom/create-text-node doc "Six")
        doc (dom/append-child doc li2 t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["5. Five" "6. Six"] (mapv :text text-ops))
        "start=5 shifts both markers by +4 -- position 1 displays as 5,
         position 2 as 6 -- while still counting positions 1/2 internally")))

(deftest ol-start-accepts-a-real-negative-value-per-html5-semantics
  ;; `<ol start="-2">` is real, legal HTML5 -- a negative or zero start is
  ;; not an error case to clamp or reject, it's ordinary arithmetic.
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        doc (dom/set-attribute doc ol :start "-2")
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "NegTwo")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        [t2 doc] (dom/create-text-node doc "NegOne")
        doc (dom/append-child doc li2 t2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li3)
        [t3 doc] (dom/create-text-node doc "Zero")
        doc (dom/append-child doc li3 t3)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["-2. NegTwo" "-1. NegOne" "0. Zero"] (mapv :text text-ops)))))

(deftest ol-malformed-start-falls-back-to-one-not-a-crash
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        doc (dom/set-attribute doc ol :start "not-a-number")
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "Fallback")
        doc (dom/append-child doc li1 t1)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. Fallback"] (mapv :text text-ops))
        "a malformed start= behaves exactly as if it were absent -- a
         graceful fallback, not a crash or a corrupted marker")))

(deftest nested-ol-start-is-independent-of-outer-ols-own-start
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        doc (dom/set-attribute doc ol :start "3")
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "Outer3")
        doc (dom/append-child doc li1 t1)
        [inner-ol doc] (dom/create-element doc :ol)
        doc (dom/append-child doc li1 inner-ol)
        [inner-li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc inner-ol inner-li1)
        [it1 doc] (dom/create-text-node doc "Inner1")
        doc (dom/append-child doc inner-li1 it1)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["3. Outer3" "1. Inner1"] (mapv :text text-ops))
        "the outer <ol>'s start=3 offset must not leak into the inner
         <ol>'s own, unrelated numbering -- the inner list has no start=
         of its own, so it correctly still begins at the plain default 1")))

;; ---- checkbox <input checked> visual rendering ----

(defn- checkbox-draw-text
  [checked-attr-value]
  (let [[input doc] (dom/create-element dom/empty-document :input)
        doc (dom/set-root doc input)
        doc (dom/set-attribute doc input :type "checkbox")
        doc (cond-> doc
              (some? checked-attr-value) (dom/set-attribute input :checked checked-attr-value))
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (:text (first (filter :control? ops)))))

(deftest checkbox-checked-explicit-xhtml-form-renders-as-checked
  ;; The confirmed repro from the bug report: checked="checked" (a real,
  ;; common, valid HTML5 boolean-attribute form -- the same class of bug
  ;; already fixed this session in browser.browser-use/truthy? for a
  ;; different consumer of the identical semantics) previously rendered
  ;; as UNCHECKED here, since the old check was a bare (true? ...) that
  ;; only ever matches htmldom's own bare-attribute sentinel value, not
  ;; any string form at all.
  (is (= "[x]" (checkbox-draw-text "checked"))
      "checked=\"checked\" must render checked, not unchecked"))

(deftest checkbox-checked-bare-attribute-still-renders-as-checked
  (is (= "[x]" (checkbox-draw-text true))
      "a bare `checked` attribute (htmldom parses this to Clojure `true`) -- pre-existing, unaffected behavior"))

(deftest checkbox-checked-empty-string-renders-as-checked
  (is (= "[x]" (checkbox-draw-text ""))
      "checked=\"\" is real, valid HTML for a present boolean attribute"))

(deftest checkbox-unchecked-when-attribute-absent
  (is (= "[ ]" (checkbox-draw-text nil))))

(deftest checkbox-checked-literal-false-string-renders-as-unchecked
  (is (= "[ ]" (checkbox-draw-text "false"))
      "checked=\"false\" is a real, if unusual, corner case -- the literal
       string \"false\" must NOT be treated as present"))

;; ---- form-control background/border painting ----

(deftest form-control-border-and-background-match-block-flex-convention
  ;; The confirmed repro from the bug report: <input>/<select>/<textarea>
  ;; had NO border-ops/default-bg draw-ops at all, unlike every other
  ;; display mode this file already covers (see grid's own identically-
  ;; named test above) -- an explicit author background/border-width/
  ;; border-color on a real <input> was silently painted as nothing.
  (let [[input doc] (dom/create-element dom/empty-document :input)
        doc (dom/set-root doc input)
        doc (dom/set-attribute doc input :value "hi")
        doc (dom/set-style doc input {:border-width 2 :border-color "#112233"
                                       :background "#445566"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        border-rects (filterv #(and (= :rect (:draw/op %)) (:border? %)) ops)
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))
        by-edge (into {} (map (juxt :edge identity)) border-rects)]
    (is (= 4 (count border-rects)))
    (is (= "#112233" (:color (:top by-edge))))
    (is (= "#445566" (:color bg-rect)))
    (is (some? bg-rect) "the background rect must exist at all, not just have the right color")
    (is (< (.indexOf ops bg-rect) (.indexOf ops (:top by-edge)))
        "background must paint BEFORE border, same convention as layout-block/flex/grid")))

(deftest form-control-with-no-author-style-still-gets-the-same-ua-default-background-every-other-element-gets
  ;; Mirrors default-bg's own existing "everything else gets the theme's
  ;; panel background" convention (already true for a plain <div>) --
  ;; form controls get the identical treatment, not a special case, so an
  ;; unstyled <input> is no longer a complete visual void.
  (let [[input doc] (dom/create-element dom/empty-document :input)
        doc (dom/set-root doc input)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))
        border-rects (filterv #(and (= :rect (:draw/op %)) (:border? %)) ops)]
    (is (= (:bg layout/default-theme) (:color bg-rect)))
    (is (= 0 (count border-rects))
        "no border-width was ever set -- zero border rects, exactly like an unstyled <div>")))

(deftest select-and-textarea-also-get-border-and-background-painting
  (doseq [tag [:select :textarea]]
    (let [[el doc] (dom/create-element dom/empty-document tag)
          doc (dom/set-root doc el)
          doc (dom/set-style doc el {:border-width 1 :border-color "#000000" :background "#ffffff"})
          [_ doc] (dom/consume-ops doc)
          tree (dom/tree doc)
          ops (layout/draw-ops tree {:width 100})
          border-rects (filterv #(and (= :rect (:draw/op %)) (:border? %)) ops)
          bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))]
      (is (= 4 (count border-rects)) (str tag " must get border rects too, not just <input>"))
      (is (= "#ffffff" (:color bg-rect)) (str tag " must get a background rect too")))))

;; ---- <details>/<summary> default disclosure hiding ----

(deftest closed-details-renders-only-the-first-summary
  ;; The confirmed repro from the bug report: before this feature, a bare
  ;; <details><summary>...</summary><p>...</p></details> rendered BOTH the
  ;; summary AND the content, always, permanently -- since this engine had
  ;; no notion of <details>'s default disclosure hiding at all.
  (let [[details doc] (dom/create-element dom/empty-document :details)
        doc (dom/set-root doc details)
        [summary doc] (dom/create-element doc :summary)
        doc (dom/append-child doc details summary)
        [st doc] (dom/create-text-node doc "Click me")
        doc (dom/append-child doc summary st)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc details p)
        [pt doc] (dom/create-text-node doc "Hidden content")
        doc (dom/append-child doc p pt)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["Click me"] (mapv :text text-ops))
        "only the summary renders; the <p> content is hidden by default")))

(deftest open-details-renders-both-summary-and-content
  (let [[details doc] (dom/create-element dom/empty-document :details)
        doc (dom/set-root doc details)
        doc (dom/set-attribute doc details :open true)
        [summary doc] (dom/create-element doc :summary)
        doc (dom/append-child doc details summary)
        [st doc] (dom/create-text-node doc "Click me")
        doc (dom/append-child doc summary st)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc details p)
        [pt doc] (dom/create-text-node doc "Shown content")
        doc (dom/append-child doc p pt)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["Click me" "Shown content"] (mapv :text text-ops))
        "a real open attribute renders everything, exactly like a
         <details> with no hiding concept at all")))

(deftest closed-details-drops-a-bare-text-node-child-too
  ;; A bare-text-node child (real, plausible HTML: literal text written
  ;; directly inside <details>...</details>, outside any wrapping element)
  ;; has no :attrs map to write :style/display onto -- must be dropped
  ;; from the children vector entirely, not silently left rendering.
  (let [[details doc] (dom/create-element dom/empty-document :details)
        doc (dom/set-root doc details)
        [summary doc] (dom/create-element doc :summary)
        doc (dom/append-child doc details summary)
        [st doc] (dom/create-text-node doc "Click me")
        doc (dom/append-child doc summary st)
        [loose doc] (dom/create-text-node doc "loose text")
        doc (dom/append-child doc details loose)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["Click me"] (mapv :text text-ops)))))

(deftest closed-details-with-two-summaries-renders-only-the-first
  (let [[details doc] (dom/create-element dom/empty-document :details)
        doc (dom/set-root doc details)
        [summary1 doc] (dom/create-element doc :summary)
        doc (dom/append-child doc details summary1)
        [t1 doc] (dom/create-text-node doc "First")
        doc (dom/append-child doc summary1 t1)
        [summary2 doc] (dom/create-element doc :summary)
        doc (dom/append-child doc details summary2)
        [t2 doc] (dom/create-text-node doc "Second")
        doc (dom/append-child doc summary2 t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["First"] (mapv :text text-ops))
        "real HTML5: only the FIRST direct <summary> child is ever the
         disclosure widget -- a second <summary> is just another hidden
         child, like any other non-summary content, when closed")))

(deftest li-list-style-none-suppresses-only-that-lis-marker-without-renumbering
  ;; Real CSS: `list-style: none` on one <li> only hides THAT marker box --
  ;; it does not renumber the <li>s around it, since real CSS's list
  ;; numbering is driven by the (independent) list-item counter, not by
  ;; which markers happen to be visible.
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        doc (dom/set-attribute doc li1 :class "suppressed")
        [t1 doc] (dom/create-text-node doc "First")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        [t2 doc] (dom/create-text-node doc "Second")
        doc (dom/append-child doc li2 t2)
        rules (css/parse-rules ".suppressed { list-style: none }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["First" "2. Second"] (mapv :text text-ops))
        "the suppressed first <li> gets no marker of any kind (its own real
         text renders alone); the second <li> still reads '2. Second', not
         renumbered down to '1. Second' -- suppressing one marker does not
         shift the numbering of the <li>s around it")))

(deftest ul-list-style-type-none-suppresses-every-direct-lis-marker
  ;; Real CSS normally sets `list-style-type: none` on the <ul>/<ol> itself
  ;; and INHERITS it to every <li> -- this engine has no general arbitrary-
  ;; property inheritance to lean on, so with-implicit-list-markers checks
  ;; the CONTAINER's own style directly instead (see its docstring), which
  ;; is the one place a real author's `list-style-type: none` almost always
  ;; actually appears in source.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [t1 doc] (dom/create-text-node doc "Apple")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [t2 doc] (dom/create-text-node doc "Banana")
        doc (dom/append-child doc li2 t2)
        rules (css/parse-rules "ul { list-style-type: none }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["Apple" "Banana"] (mapv :text text-ops))
        "list-style-type: none on the <ul> itself suppresses every one of
         its direct <li> children's implicit markers, not just the first")))

(deftest bare-li-with-no-ul-or-ol-parent-gets-no-implicit-marker
  ;; This feature is specifically the <ul>/<ol> UA-stylesheet default, not
  ;; a generic "every <li> gets a marker" rule -- an <li> sitting directly
  ;; under something else (malformed/manually-styled markup) must render
  ;; completely unaffected, exactly as it did before this feature existed.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        [li doc] (dom/create-element doc :li)
        doc (dom/append-child doc div li)
        [t doc] (dom/create-text-node doc "Orphan")
        doc (dom/append-child doc li t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["Orphan"] (mapv :text text-ops))
        "no bullet, no number, no marker of any kind -- a bare <li> with no
         <ul>/<ol> DIRECT parent renders its own text completely
         unaffected")))

(deftest li-own-explicit-before-content-is-left-alone-not-doubled-with-implicit-marker
  ;; If a page author ALSO writes their own explicit `<li>::before { content:
  ;; ... }`, the implicit marker must not fire at all for that <li> -- since
  ;; :pseudo/before is a single-value attrs key, unconditionally writing an
  ;; implicit marker there too would SILENTLY DELETE the author's own
  ;; explicit content rather than combining with it (there is no way to
  ;; represent both in one :text draw-op). The explicit ::before is left
  ;; completely alone, applying exactly as it already did before this
  ;; feature existed -- no doubled/garbled line, no implicit marker
  ;; competing with it.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li)
        [t doc] (dom/create-text-node doc "Custom")
        doc (dom/append-child doc li t)
        rules (css/parse-rules "li::before { content: \"★ \" }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["★ Custom"] (mapv :text text-ops))
        "the author's own explicit ::before content renders exactly as it
         already did -- one merged line, the author's own marker, no
         implicit bullet also competing for the same :pseudo/before slot")))

(deftest explicit-counter-numbered-list-idiom-still-works-unchanged-alongside-implicit-feature
  ;; Regression guard, end to end: the pre-existing explicit-CSS numbered-
  ;; list idiom (`li { counter-increment: item } li::before { content:
  ;; counter(item) \". \"; }`, already covered by
  ;; three-sibling-list-items-render-sequential-counter-numbers-as-real-text
  ;; above) must render IDENTICALLY now that the implicit-marker feature
  ;; exists -- the explicit ::before content on each <li> makes
  ;; with-implicit-list-markers skip every one of them, so this is not a
  ;; double-numbered "1. 1. one" -- just the author's own explicit numbering,
  ;; unaffected.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [t1 doc] (dom/create-text-node doc "one")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [t2 doc] (dom/create-text-node doc "two")
        doc (dom/append-child doc li2 t2)
        rules (css/parse-rules
               "li { counter-increment: item }
                li::before { content: counter(item) \". \"; }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. one" "2. two"] (mapv :text text-ops))
        "the author's own explicit counter-driven numbering renders exactly
         as before this feature existed -- not doubled, not replaced by the
         implicit bullet this <ul>'s <li>s would otherwise get")))

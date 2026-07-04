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
    (is (= {:x 4 :y 4 :w 50 :h 10} (select-keys child [:x :y :w :h])))))

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
    (is (= 3 (count text-ops))
        "::before text + the real child text + ::after text = 3 :text draw-ops")
    (is (= ["→ " "middle" " ←"] (mapv :text text-ops))
        "generated ::before text precedes the real child text, which
         precedes generated ::after text -- document order, real content")
    (is (= "red" (:color (first text-ops)))
        "::before paints with its own declared color, not the element's
         inherited black")
    (is (= "black" (:color (second text-ops)))
        "the real text node still inherits the element's own color, unaffected")
    (is (= "black" (:color (nth text-ops 2)))
        "::after with no declared color of its own inherits the element's
         color, exactly like any real child would")
    (is (< (:y (first text-ops)) (:y (second text-ops)))
        "::before is positioned above (before) the real child in this
         engine's vertical block flow")
    (is (< (:y (second text-ops)) (:y (nth text-ops 2)))
        "::after is positioned below (after) the real child")))

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
    (is (= 2 (count text-ops))
        "::before's resolved attr() text + the real child text = 2 :text draw-ops")
    (is (= ["hello" "middle"] (mapv :text text-ops))
        "content: attr(data-x) renders the element's own real data-x
         attribute value as real, painted text -- not the literal
         'attr(data-x)' source text, and not nothing")
    (is (= "red" (:color (first text-ops)))
        "::before still paints with its own declared color")))

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
  ;; numbering across sibling <li> elements, purely from CSS, painted as
  ;; three genuinely distinct :text draw-ops.
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
    (is (= ["1. " "one" "2. " "two" "3. " "three"] (mapv :text text-ops))
        "each <li>'s ::before renders that <li>'s OWN incremented counter
         value (\"1. \"/\"2. \"/\"3. \"), immediately before its own real
         text child, in document order -- a genuine running total across
         siblings, not three independently-resolved copies of the same
         value")))

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

(ns cssom.layout-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
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
   {:display \"grid\"}) and one :span child per [w h] pair in `child-sizes`
   (either may be nil to leave that style unset -- an unset width lets the
   child stretch to fill its column, the same way a plain block child
   already fills its container's content-width). Sets style attrs directly
   via kotoba.wasm.dom, bypassing cssom.core's cascade entirely -- same
   convention the rest of this test file already uses."
  [container-style child-sizes]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        doc (dom/set-style doc root (merge {:display "grid"} container-style))
        doc (reduce (fn [doc [w h]]
                      (let [[child doc] (dom/create-element doc :span)
                            doc (dom/set-style doc child (cond-> {}
                                                            w (assoc :width w)
                                                            h (assoc :height h)))]
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

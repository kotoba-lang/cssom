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

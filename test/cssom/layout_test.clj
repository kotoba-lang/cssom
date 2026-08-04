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

;; ---- font-weight/font-style pass-through onto :text draw-ops ----

(deftest text-draw-op-carries-real-font-weight-and-font-style
  ;; The confirmed repro from the bug report: font-weight: bold/font-
  ;; style: italic already resolved correctly in the real cascade
  ;; (:style/font-weight/:style/font-style existed on the node), but
  ;; layout's own :text draw-op never carried either at all -- bold/
  ;; italic CSS had ZERO visual effect no matter what a real author
  ;; wrote, confirmed via a real draw-ops dump through the full pipeline.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:font-weight "bold" :font-style "italic"})
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= "bold" (:font-weight text-op)))
    (is (= "italic" (:font-style text-op)))))

(deftest text-draw-op-has-no-font-weight-or-style-keys-when-never-set
  ;; Exact backward compatibility: an unstyled element's :text draw-op
  ;; must look byte-for-byte the same as before this feature existed --
  ;; no :font-weight/:font-style key at all, not even a "normal" one.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (not (contains? text-op :font-weight)))
    (is (not (contains? text-op :font-style)))))

(deftest font-weight-and-style-are-real-inheritable-properties
  ;; A child overriding font-weight back to "normal" must win over its
  ;; parent's own bold -- real CSS inheritance, the same shape color/
  ;; font-size already have.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:font-weight "bold"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        doc (dom/set-style doc child {:font-weight "normal"})
        [t doc] (dom/create-text-node doc "child")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= "normal" (:font-weight text-op))
        "the child's own explicit font-weight: normal must win over the inherited bold")))

;; ---- font-family: the exact same "cascade-resolved but silently dropped
;; at the draw-op step" bug already fixed for font-weight/font-style/
;; text-decoration/line-height above ----

(deftest text-draw-op-carries-real-font-family
  ;; :style/font-family already resolved correctly in the real cascade,
  ;; but layout's own :text draw-op never carried it at all -- a real
  ;; author font-family had ZERO visual effect no matter what a real
  ;; page declared, confirmed via a real draw-ops dump through the full
  ;; pipeline.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:font-family "Georgia, serif"})
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= "Georgia, serif" (:font-family text-op)))))

(deftest text-draw-op-has-no-font-family-key-when-never-set
  ;; Exact backward compatibility: an unstyled element's :text draw-op
  ;; must look byte-for-byte the same as before this feature existed --
  ;; no :font-family key at all.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (not (contains? text-op :font-family)))))

(deftest font-family-is-a-real-inheritable-property
  ;; A child overriding font-family back to its own value must win over
  ;; its parent's -- real CSS inheritance, the same shape color/font-size/
  ;; font-weight already have.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:font-family "Georgia, serif"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        doc (dom/set-style doc child {:font-family "monospace"})
        [t doc] (dom/create-text-node doc "child")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= "monospace" (:font-family text-op))
        "the child's own explicit font-family must win over the inherited Georgia, serif")))

(deftest font-family-inherits-down-through-an-uninvolved-child
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:font-family "Georgia, serif"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "child")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= "Georgia, serif" (:font-family text-op))
        "a child with no font-family of its own must inherit the parent's")))

;; ---- text-shadow: previously stored verbatim as a single unrecognized
;; :style/text-shadow string, so NO shadow :text draw-op was ever emitted
;; no matter what a real page declared, confirmed via direct REPL
;; reproduction ----

(deftest text-draw-op-carries-a-real-text-shadow-as-an-extra-shadow-colored-op
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:text-shadow-x 2 :text-shadow-y 3 :text-shadow-color "#000000"})
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-ops (filter #(= :text (:draw/op %)) ops)]
    (is (= 2 (count text-ops))
        "a real shadow op plus the main text op")
    (let [[shadow-op main-op] text-ops]
      (is (= "#000000" (:color shadow-op)))
      (is (= (+ 2 (:x main-op)) (:x shadow-op)))
      (is (= (+ 3 (:y main-op)) (:y shadow-op)))
      (is (not= "#000000" (:color main-op))
          "the main text op's own color must be untouched by the shadow"))))

(deftest text-draw-op-has-only-one-op-when-text-shadow-never-set
  ;; Exact backward compatibility: an unstyled element's :text draw-ops
  ;; must look byte-for-byte the same as before this feature existed --
  ;; exactly one op per line, no shadow op inserted.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-ops (filter #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops)))))

(deftest text-shadow-is-a-real-inheritable-property
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:text-shadow-x 2 :text-shadow-y 2 :text-shadow-color "red"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "child")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-ops (filter #(= :text (:draw/op %)) ops)]
    (is (= 2 (count text-ops))
        "a child with no text-shadow of its own must inherit the parent's")
    (is (= "red" (:color (first text-ops))))))

(deftest text-shadow-none-cancels-an-inherited-shadow-instead-of-falling-back-to-it
  ;; text-shadow genuinely inherits in real CSS, so a child explicitly
  ;; writing text-shadow: none must WIN over its parent's real shadow --
  ;; not silently keep showing it, the same real-inheritance-override
  ;; shape font-weight/font-family already prove for their own properties.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:text-shadow-x 2 :text-shadow-y 2 :text-shadow-color "red"})
        [child doc] (dom/create-element doc :span)
        doc (dom/set-style doc child {:text-shadow-color "none"})
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "child")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-ops (filter #(= :text (:draw/op %)) ops)]
    (is (= 1 (count text-ops))
        "the child's own text-shadow: none must cancel the inherited shadow, not just fall back to it")))

;; ---- line-height: previously read NOWHERE from the cascade at all --
;; every single line of text anywhere used the same fixed theme constant
;; regardless of any real author CSS, confirmed via direct REPL
;; reproduction: line-height: 60/line-height: 100/no declaration at all
;; produced the identical box height. ----

(deftest line-height-scales-both-per-line-spacing-and-overall-box-height
  ;; Exactly long-text-wraps-onto-multiple-narrower-lines's own shape
  ;; (real word-wrap forced by a narrow avail-width) with a non-default
  ;; :line-height threaded through `inherited` this time, rather than the
  ;; theme constant.
  (let [avail 100
        text "the quick brown fox jumps over the lazy dog"
        line-height 60
        inherited {:color (:fg layout/default-theme) :font-size (:font-size layout/default-theme)
                   :line-height line-height}
        {:keys [box draw]} (layout/layout-node layout/default-theme 0 0 avail 1.0 inherited text)]
    (is (> (count draw) 1) "sanity: the text actually wrapped onto multiple real lines")
    (is (every? #(= line-height %) (map - (map :y (rest draw)) (map :y draw)))
        "every consecutive line must be spaced exactly at the real declared line-height, not the theme default")
    (is (= (+ (* (count draw) line-height) (* 2 (:padding layout/default-theme))) (:h box))
        "the box's own height must grow to fit every real line at the real line-height, not the fixed default")))

(deftest line-height-defaults-to-the-theme-constant-when-absent-or-unparseable
  (let [box-h (fn [style]
                (let [[div doc] (dom/create-element dom/empty-document :div)
                      doc (dom/set-root doc div)
                      doc (dom/set-style doc div style)
                      [t doc] (dom/create-text-node doc "hi")
                      doc (dom/append-child doc div t)
                      [_ doc] (dom/consume-ops doc)
                      tree (dom/tree doc)]
                  (:h (:box (layout/layout-node tree)))))]
    (is (= (box-h {}) (box-h {:line-height "normal"}))
        "an unparseable keyword like normal must degrade to the exact same baseline as no declaration at all, not crash or zero out")))

(deftest unitless-line-height-is-a-real-per-element-multiplier-of-font-size-not-a-literal-pixel-count
  ;; Real CSS's own most common line-height form -- a bare unitless number
  ;; -- is a MULTIPLIER of that element's own font-size, not a literal
  ;; pixel count. cssom.core/parse-style-value only ever coerces a value
  ;; to a number for a bare integer or an integer px length -- a decimal
  ;; like 1.5 survives the cascade as the untouched STRING "1.5", which
  ;; is exactly how cssom.layout/resolve-line-height tells the two real
  ;; forms apart.
  (let [box-h (fn [style]
                (let [[div doc] (dom/create-element dom/empty-document :div)
                      doc (dom/set-root doc div)
                      doc (dom/set-style doc div style)
                      [t doc] (dom/create-text-node doc "hi")
                      doc (dom/append-child doc div t)
                      [_ doc] (dom/consume-ops doc)
                      tree (dom/tree doc)]
                  (:h (:box (layout/layout-node tree)))))]
    (is (= (box-h {:font-size 40 :line-height 60})
           (box-h {:font-size 40 :line-height "1.5"}))
        "line-height: 1.5 at font-size: 40 must resolve to the SAME effective 60px line-height as an explicit absolute line-height: 60")
    (is (not= (box-h {:font-size 20 :line-height "1.5"})
              (box-h {:font-size 40 :line-height "1.5"}))
        "the SAME 1.5 multiplier must scale with a DIFFERENT font-size, proving it is genuinely re-resolved as a multiplier, not cached/misread as a literal pixel value")))

(deftest line-height-is-a-real-inheritable-property
  ;; The child SPAN declares no line-height of its own at all -- its own
  ;; wrapped lines' y-spacing must reflect the PARENT's inherited 60px,
  ;; not the theme default, the same shape font-weight-and-style-are-
  ;; real-inheritable-properties above already proves for font-weight.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:line-height 60})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "the quick brown fox jumps over the lazy dog")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-ops (filter #(= :text (:draw/op %)) ops)]
    (is (> (count text-ops) 1) "sanity: the child's own text actually wrapped onto multiple real lines")
    (is (every? #(= 60 %) (map - (map :y (rest text-ops)) (map :y text-ops)))
        "the child's own line spacing must reflect the parent's inherited line-height, not the theme default")))

;; ---- text-decoration pass-through onto :text draw-ops ----

(deftest text-draw-op-carries-real-text-decoration
  ;; Direct follow-up to the font-weight/font-style bug: text-decoration:
  ;; underline/line-through/overline already resolved fine in the cascade
  ;; (this file's style resolution has no allowlist of known property
  ;; names) but layout's own :text draw-op never carried any of them at
  ;; all -- text-decoration had ZERO visual effect no matter what a real
  ;; author wrote, confirmed via a real draw-ops dump through the full
  ;; pipeline.
  (doseq [value ["underline" "line-through" "overline"]]
    (let [[div doc] (dom/create-element dom/empty-document :div)
          doc (dom/set-root doc div)
          doc (dom/set-style doc div {:text-decoration value})
          [t doc] (dom/create-text-node doc "hi")
          doc (dom/append-child doc div t)
          [_ doc] (dom/consume-ops doc)
          tree (dom/tree doc)
          ops (layout/draw-ops tree {:width 100})
          text-op (first (filter #(= :text (:draw/op %)) ops))]
      (is (= value (:text-decoration text-op))))))

(deftest text-draw-op-has-no-text-decoration-key-when-never-set
  ;; Exact backward compatibility: an unstyled element's :text draw-op
  ;; must look byte-for-byte the same as before this feature existed --
  ;; no :text-decoration key at all.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (not (contains? text-op :text-decoration)))))

(deftest text-decoration-is-inherited-and-none-stops-it
  ;; This engine deliberately models text-decoration as an ordinary
  ;; inherited-with-override property (the same shape color/font-weight/
  ;; font-style already have) rather than real CSS's own more subtle
  ;; non-overridable propagation -- a child with no explicit value
  ;; inherits the parent's decoration, and a child explicitly setting
  ;; text-decoration: none correctly stops its own line.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:text-decoration "line-through"})
        [inheriting-child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent inheriting-child)
        [t1 doc] (dom/create-text-node doc "inherits")
        doc (dom/append-child doc inheriting-child t1)
        [overriding-child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent overriding-child)
        doc (dom/set-style doc overriding-child {:text-decoration "none"})
        [t2 doc] (dom/create-text-node doc "overrides")
        doc (dom/append-child doc overriding-child t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        inherits-op (first (filter #(= "inherits" (:text %)) ops))
        overrides-op (first (filter #(= "overrides" (:text %)) ops))]
    (is (= "line-through" (:text-decoration inherits-op))
        "a child with no explicit text-decoration inherits the parent's")
    (is (= "none" (:text-decoration overrides-op))
        "a child's own explicit text-decoration: none must win over the inherited line-through")))

;; ---- text-align offsets each line's x within the real content width ----

(deftest text-align-center-and-right-offset-x-by-real-line-width
  ;; Unlike font-weight/font-style/text-decoration, text-align is a REAL,
  ;; spec-accurate inherited CSS property here -- no simplification. An
  ;; ordinary block with no explicit :width already fills its full
  ;; available width (resolve-width's `avail` fallback), so centering/
  ;; right-aligning has a real, visible effect with no styling beyond
  ;; text-align itself, matching a real browser.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        left-x (:x (first (filter #(= :text (:draw/op %)) (layout/draw-ops tree {:width 200}))))]
    (is (= 8 left-x) "default (left) alignment is unchanged from before this feature"))
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:text-align "center"})
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        center-x (:x (first (filter #(= :text (:draw/op %)) (layout/draw-ops tree {:width 200}))))]
    (is (= 92 center-x) "centered within the real 200px content width, not left-aligned"))
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:text-align "right"})
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        right-x (:x (first (filter #(= :text (:draw/op %)) (layout/draw-ops tree {:width 200}))))]
    (is (= 176 right-x) "pushed to the far right of the real 200px content width")))

(deftest text-align-justify-falls-back-to-left
  ;; This engine has no per-space stretch-justification of its own --
  ;; justify degrades to left rather than guessing a wrong stretch.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:text-align "justify"})
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= 8 (:x text-op)))))

(deftest text-align-is-a-real-inheritable-property
  ;; A child with no explicit text-align inherits its parent's -- the
  ;; same shape color/font-size already have, and matches real CSS
  ;; (text-align genuinely is an inherited property, unlike
  ;; text-decoration above).
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:text-align "center"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "child")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        text-op (first (filter #(= :text (:draw/op %)) ops))
        plain-tree (do
                     (let [[parent2 doc2] (dom/create-element dom/empty-document :div)
                           doc2 (dom/set-root doc2 parent2)
                           [child2 doc2] (dom/create-element doc2 :span)
                           doc2 (dom/append-child doc2 parent2 child2)
                           [t2 doc2] (dom/create-text-node doc2 "child")
                           doc2 (dom/append-child doc2 child2 t2)
                           [_ doc2] (dom/consume-ops doc2)]
                       (dom/tree doc2)))
        plain-x (:x (first (filter #(= :text (:draw/op %)) (layout/draw-ops plain-tree {:width 200}))))]
    (is (not= plain-x (:x text-op))
        "the inherited center alignment must move the child's text away from its default left-aligned x")))

;; ---- text-transform rewrites the real rendered text, not just metadata ----

(defn- text-op-text [text-transform text]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (if text-transform (dom/set-style doc div {:text-transform text-transform}) doc)
        [t doc] (dom/create-text-node doc text)
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})]
    (:text (first (filter #(= :text (:draw/op %)) ops)))))

(deftest text-transform-rewrites-the-real-draw-op-text
  ;; Unlike font-weight/font-style/text-decoration/text-align (all
  ;; threaded onto the draw-op as separate paint metadata), text-
  ;; transform actually rewrites the rendered text content itself.
  (is (= "HELLO WORLD" (text-op-text "uppercase" "hello world")))
  (is (= "hello world" (text-op-text "lowercase" "HELLO WORLD")))
  (is (= "Hello World" (text-op-text "capitalize" "hello world"))))

(deftest text-transform-none-or-absent-leaves-text-unchanged
  ;; Exact backward compatibility: no text-transform at all, and an
  ;; explicit "none", must both leave the original text untouched.
  (is (= "Hello World" (text-op-text nil "Hello World")))
  (is (= "Hello World" (text-op-text "none" "Hello World"))))

(deftest text-transform-is-a-real-inheritable-property
  ;; A child with no explicit text-transform inherits its parent's --
  ;; text-transform genuinely is an inherited CSS property, the same
  ;; shape color/text-align already have.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:text-transform "uppercase"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "child text")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= "CHILD TEXT" (:text text-op))
        "the inherited uppercase must transform the child's own text too")))

;; ---- white-space: pre/nowrap -- the last item on the text-styling survey ----

(defn- text-ops-of [tag style text width]
  (let [[el doc] (dom/create-element dom/empty-document tag)
        doc (dom/set-root doc el)
        doc (if style (dom/set-style doc el style) doc)
        [t doc] (dom/create-text-node doc text)
        doc (dom/append-child doc el t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)]
    (filter #(= :text (:draw/op %)) (layout/draw-ops tree {:width width}))))

(deftest pre-splits-embedded-newlines-into-separate-draw-op-lines
  ;; The confirmed repro: BEFORE this fix, a text node with an embedded
  ;; \n either vanished into a single :text draw-op whose string still
  ;; had the \n baked in (a raw newline inside one real Canvas 2D
  ;; fillText call does NOT create a visual line break) or got the
  ;; newline silently collapsed away by ordinary word-wrapping -- either
  ;; way a real <pre> block's line structure was never actually visible.
  (let [ops (text-ops-of :div {:white-space "pre"} "a\nb\nc" 800)]
    (is (= ["a" "b" "c"] (map :text ops)))
    (is (apply < (map :y ops)) "each split line must be positioned strictly below the previous one")))

(deftest pre-tag-gets-a-ua-default-white-space-pre-with-no-author-css-at-all
  ;; Matches every real browser's own default UA stylesheet (pre { white-
  ;; space: pre; }) -- a bare, unstyled <pre> must show its real line
  ;; structure without requiring an author to write explicit CSS for it.
  (let [ops (text-ops-of :pre nil "line one\nline two" 800)]
    (is (= ["line one" "line two"] (map :text ops)))))

(deftest ordinary-element-without-white-space-pre-is-unaffected
  ;; Documented architectural limitation: kotoba-lang/htmldom's own
  ;; parse-time whitespace collapsing is HTML-structural (raw-text tags /
  ;; a real <pre> ancestor), not CSS-driven, so an embedded \n in an
  ;; ordinary element's source HTML is already gone before cssom ever
  ;; sees the text -- explicit white-space: pre on a plain <div> cannot
  ;; recover a newline this test constructs directly (bypassing the HTML
  ;; parser), but a <div> with NO white-space at all must still behave
  ;; exactly as before this feature existed: byte-for-byte unaffected.
  (let [ops (text-ops-of :div nil "a\nb\nc" 800)]
    (is (= ["a b c"] (map :text ops))
        "an element WITHOUT `white-space: pre` collapses its newlines into
         single spaces, exactly as real CSS `normal` does. This asserted
         the newline survived into the draw-op, which was only true while
         the parser destroyed newlines for everyone -- with the parser now
         keeping them (so `pre-line`/`pre-wrap` are implementable), the
         collapse belongs here and this is what a browser paints")))

(deftest nowrap-keeps-long-text-on-one-line-overflowing-a-narrow-box
  (let [wrapped (text-ops-of :div nil "this is a long line of text that would normally wrap" 100)
        nowrapped (text-ops-of :div {:white-space "nowrap"} "this is a long line of text that would normally wrap" 100)]
    (is (> (count wrapped) 1) "sanity check: the same text without nowrap really does wrap in this narrow box")
    (is (= 1 (count nowrapped)) "nowrap must keep the whole text on a single, overflowing line")))

(deftest white-space-is-a-real-inheritable-property
  ;; A <span> nested inside a <pre> inherits white-space: pre and splits
  ;; its OWN text on embedded newlines too -- the same inherited-with-
  ;; override shape every other text-styling property in this file has.
  (let [[pre doc] (dom/create-element dom/empty-document :pre)
        doc (dom/set-root doc pre)
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc pre span)
        [t doc] (dom/create-text-node doc "x\ny")
        doc (dom/append-child doc span t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (filter #(= :text (:draw/op %)) (layout/draw-ops tree {:width 800}))]
    (is (= ["x" "y"] (map :text ops)))))

;; ---- text-overflow: ellipsis -- only ever acts on an already-nowrap line ----

(deftest text-overflow-ellipsis-truncates-an-overflowing-nowrap-line
  (let [ops (text-ops-of :div {:white-space "nowrap" :text-overflow "ellipsis"}
                          "this is a long line of text that would normally wrap" 100)]
    (is (= 1 (count ops)) "ellipsis, like plain nowrap, must still keep everything on one line")
    (is (str/ends-with? (:text (first ops)) "…")
        "an overflowing nowrap line with ellipsis declared must end with the real ellipsis glyph")
    (is (< (count (:text (first ops)))
           (count "this is a long line of text that would normally wrap"))
        "the truncated text must be strictly shorter than the original overflowing text")))

(deftest text-overflow-ellipsis-leaves-a-line-that-already-fits-unchanged
  (let [ops (text-ops-of :div {:white-space "nowrap" :text-overflow "ellipsis"} "short" 800)]
    (is (= ["short"] (map :text ops))
        "a nowrap line that already fits within its box must be left byte-for-byte untouched, not truncated")))

(deftest text-overflow-has-no-effect-without-nowrap
  ;; Real CSS's own requirement: text-overflow only ever takes effect on a
  ;; non-wrapping block -- a wrapped, multi-line paragraph has no single
  ;; "the line" to truncate, so ellipsis must be silently ignored here.
  (let [ops (text-ops-of :div {:text-overflow "ellipsis"}
                          "this is a long line of text that would normally wrap" 100)]
    (is (> (count ops) 1) "the text must still wrap onto multiple lines exactly as if text-overflow were absent")
    (is (not-any? #(str/ends-with? % "…") (map :text ops))
        "none of the wrapped lines may be ellipsized -- text-overflow must be a pure no-op here")))

(deftest text-overflow-absent-or-clip-does-not-truncate-a-nowrap-line
  ;; Baseline, unaffected by this feature: nowrap with no text-overflow (or
  ;; any value other than the literal "ellipsis") keeps overflowing exactly
  ;; as it always has -- the pre-existing behavior this fix must not disturb.
  (let [no-value (text-ops-of :div {:white-space "nowrap"}
                               "this is a long line of text that would normally wrap" 100)
        clip-value (text-ops-of :div {:white-space "nowrap" :text-overflow "clip"}
                                 "this is a long line of text that would normally wrap" 100)]
    (is (= "this is a long line of text that would normally wrap" (:text (first no-value))))
    (is (= "this is a long line of text that would normally wrap" (:text (first clip-value)))
        "clip is not the literal string \"ellipsis\", so it must fall through to the same untruncated overflow")))

(deftest text-overflow-degrades-to-a-bare-ellipsis-when-even-that-alone-does-not-fit
  ;; Honest degrade path: a box too narrow to fit even a single "…" must
  ;; still produce the ellipsis alone, never an empty string or a crash.
  (let [ops (text-ops-of :div {:white-space "nowrap" :text-overflow "ellipsis"}
                          "this is a long line of text that would normally wrap" 1)]
    (is (= ["…"] (map :text ops)))))

(deftest text-overflow-is-a-real-inheritable-property
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:white-space "nowrap" :text-overflow "ellipsis"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "this is a long line of text that would normally wrap")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (filter #(= :text (:draw/op %)) (layout/draw-ops tree {:width 100}))]
    (is (= 1 (count ops)))
    (is (str/ends-with? (:text (first ops)) "…")
        "the child inherits both nowrap and text-overflow from its parent and must truncate too")))

;; ---- white-space: pre-wrap -- pre's newline-splitting + normal's re-wrap ----

(deftest pre-wrap-re-wraps-a-too-long-segment-but-leaves-a-short-one-alone
  (let [ops (text-ops-of :div {:white-space "pre-wrap"}
                          "short\nthis is a genuinely long line that needs wrapping" 150)]
    (is (= "short" (first (map :text ops)))
        "a segment that already fits gets no re-wrapping at all -- WITHOUT this fix, an unrecognized \"pre-wrap\" value falls back to ordinary word-wrap on the WHOLE string (the embedded newline treated as just another whitespace run), jumbling \"short\" together with the second segment's own words instead of keeping it on its own untouched first line")
    (is (> (count ops) 2)
        "the long segment must be broken into more than one sub-line in this narrow box")
    (is (apply < (map :y ops)) "every resulting line must be positioned strictly below the previous one"))
  ;; Direct comparison against plain `pre` for the identical input: `pre`
  ;; never re-wraps a segment no matter how long (it overflows instead),
  ;; `pre-wrap` must actually break the same long segment into sub-lines
  ;; -- this is the one assertion that most directly proves the two
  ;; white-space values are genuinely different code paths, not aliases.
  (let [pre-ops (text-ops-of :div {:white-space "pre"}
                              "this is a genuinely long line that needs wrapping" 150)
        pre-wrap-ops (text-ops-of :div {:white-space "pre-wrap"}
                                   "this is a genuinely long line that needs wrapping" 150)]
    (is (= 1 (count pre-ops)) "plain pre never re-wraps -- it just overflows")
    (is (> (count pre-wrap-ops) 1) "pre-wrap must actually break the same long line into sub-lines")))

(deftest pre-wrap-preserves-verbatim-spacing-in-a-segment-that-fits-without-wrapping
  ;; The documented compromise: a segment that already fits on one line
  ;; keeps its exact original spacing (text-lines' own fast path returns
  ;; the string completely unmodified) -- only a segment that genuinely
  ;; needs re-wrapping loses its exact inter-word spacing. Combined with
  ;; an embedded newline so this genuinely exercises pre-wrap's own
  ;; split-then-preserve mechanism: WITHOUT this fix, an unrecognized
  ;; "pre-wrap" value falls back to text-lines on the WHOLE string
  ;; (including the raw \n) -- since "a  b\nc   d" is short enough to
  ;; fit on one line by the char-width heuristic, that fallback's own
  ;; fits-verbatim fast path would return it as a SINGLE line with the
  ;; literal \n still baked in, not two separately-split lines.
  (let [ops (text-ops-of :div {:white-space "pre-wrap"} "a  b\nc   d" 800)]
    (is (= ["a  b" "c   d"] (map :text ops)))))

(deftest pre-wrap-preserves-a-blank-line-between-two-segments
  (let [ops (text-ops-of :div {:white-space "pre-wrap"} "a\n\nb" 800)]
    (is (= ["a" "" "b"] (map :text ops)))))

;; ---- white-space: pre-line -- pre-wrap's newline-split + normal's collapse ----

(deftest pre-line-collapses-internal-whitespace-but-preserves-hard-breaks
  ;; WITHOUT this fix, an unrecognized "pre-line" value falls back to
  ;; text-lines on the WHOLE string (including the raw \n) -- since
  ;; "a  b\nc   d" is short enough to fit on one line by the char-width
  ;; heuristic, that fallback's own fits-verbatim fast path would return
  ;; it as a SINGLE line with the literal \n still baked in, not two
  ;; separately-split, collapsed lines.
  (let [pre-line-ops (text-ops-of :div {:white-space "pre-line"} "a  b\nc   d" 800)
        pre-wrap-ops (text-ops-of :div {:white-space "pre-wrap"} "a  b\nc   d" 800)]
    (is (= ["a b" "c d"] (map :text pre-line-ops))
        "pre-line collapses each segment's internal whitespace to single spaces")
    (is (not= (map :text pre-line-ops) (map :text pre-wrap-ops))
        "pre-line and pre-wrap must genuinely differ on the identical input -- pre-wrap preserves the same multiple spaces verbatim")))

(deftest pre-line-still-wraps-a-too-long-segment-but-leaves-a-short-one-alone
  (let [ops (text-ops-of :div {:white-space "pre-line"}
                          "short\nthis is a genuinely long line that needs wrapping" 150)]
    (is (= "short" (first (map :text ops)))
        "a segment that already fits gets no re-wrapping at all -- WITHOUT this fix, the embedded newline is treated as just another whitespace run to collapse away, jumbling \"short\" together with the second segment's own words")
    (is (> (count ops) 2)
        "the long segment must be broken into more than one sub-line in this narrow box")
    (is (apply < (map :y ops)) "every resulting line must be positioned strictly below the previous one")))

(deftest pre-line-preserves-a-blank-line-between-two-segments
  (let [ops (text-ops-of :div {:white-space "pre-line"} "a\n\nb" 800)]
    (is (= ["a" "" "b"] (map :text ops)))))

(deftest pre-line-is-a-real-inheritable-property
  ;; A child inherits its parent's white-space: pre-line and both
  ;; splits on its OWN embedded newline and collapses its OWN internal
  ;; whitespace -- the same inherited-with-override shape every other
  ;; text-styling property in this file has.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:white-space "pre-line"})
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        [t doc] (dom/create-text-node doc "x  y\nz")
        doc (dom/append-child doc child t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (filter #(= :text (:draw/op %)) (layout/draw-ops tree {:width 800}))]
    (is (= ["x y" "z"] (map :text ops)))))

(deftest border-width-without-border-style-draws-and-occupies-nothing
  ;; Real CSS resolves the USED border width through `border-style`, whose
  ;; initial value is `none` -- and a `none`/`hidden` border is 0px wide
  ;; however many pixels `border-width` declares. Measured in Chrome:
  ;; `<div style="border-width: 1px">` reports `border-top-width: 0px` and
  ;; `border-top-style: none`, so such a div wrapping one <p> is 20px tall
  ;; there; this engine made it 50px, because the phantom border also
  ;; stopped margins collapsing through the box.
  ;;
  ;; Two things are asserted together on purpose: that nothing is PAINTED,
  ;; and that nothing is OCCUPIED. A border that paints nothing but still
  ;; takes up space is the harder half of the bug and the one that moved
  ;; boxes that had no border anywhere near them.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:border-width 4 :border-color "#00ff00"
                                    :padding 10 :width 200})
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400})
        borders (filter #(and (= :rect (:draw/op %)) (:border? %)) ops)
        box (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))]
    (is (empty? borders) "a border with no border-style paints nothing")
    ;; 200 content + 10 padding per side, and no border: Chrome reports
    ;; 220x40 for exactly this markup.
    (is (= 220 (:w box)))))

(deftest border-width-with-border-style-still-draws-and-occupies
  ;; The other side of the same rule -- the gate must not have turned
  ;; borders off in general.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:border-style "solid" :border-width 4
                                    :border-color "#00ff00"
                                    :padding 10 :width 200})
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400})
        borders (filter #(and (= :rect (:draw/op %)) (:border? %)) ops)
        box (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))]
    (is (= 4 (count borders)) "one rect per edge")
    (is (= 228 (:w box)) "200 content + 10 padding + 4 border, per side")))

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
        doc (dom/set-style doc div {:border-style "solid" :border-width 3 :border-color "#00ff00"
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
        doc (dom/set-style doc div {:display "flex" :border-style "solid" :border-width 3 :border-color "#00ff00"
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

;; ---- box-shadow: basic offset + color, no dom-gpu changes needed ----

(deftest box-shadow-paints-before-background-and-border-not-after
  ;; Real CSS: a non-inset box-shadow paints BEHIND the element's own box
  ;; -- confirmed via direct REPL reproduction before touching source that
  ;; box-shadow was previously read nowhere at all, so this ordering never
  ;; had a chance to matter (no shadow rect was ever emitted).
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:box-shadow-x 4 :box-shadow-y 4 :box-shadow-color "#000000"
                                     :border-style "solid" :border-width 3 :border-color "#00ff00" :background "#ff0000"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %)) (not (:box-shadow? %))) ops))
        top-border (first (filter #(and (= :rect (:draw/op %)) (:border? %) (= :top (:edge %))) ops))]
    (is (some? shadow-rect))
    (is (some? bg-rect))
    (is (some? top-border))
    (is (< (.indexOf ops shadow-rect) (.indexOf ops bg-rect))
        "box-shadow must paint BEFORE the background, not after")
    (is (< (.indexOf ops bg-rect) (.indexOf ops top-border))
        "background must still paint before border, same pre-existing convention")))

(deftest box-shadow-is-offset-and-shares-the-elements-own-box-size
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:box-shadow-x 6 :box-shadow-y 9 :box-shadow-color "#123456"
                                     :width 80 :height 40})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))]
    (is (= 6 (:x shadow-rect)))
    (is (= 9 (:y shadow-rect)))
    (is (= 80 (:w shadow-rect)))
    (is (= 40 (:h shadow-rect)))
    (is (= "#123456" (:color shadow-rect)))))

(deftest box-shadow-spread-radius-expands-the-shadow-rect-outward
  ;; A positive spread-radius grows the shadow past the element's own box
  ;; edges on all four sides, before the x/y offset is applied -- the exact
  ;; real-world 5-token box-shadow: 0 1px 2px 0 rgba(...) shape (previously
  ;; corrupting/dropping the color entirely) now also renders the spread.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:box-shadow-x 0 :box-shadow-y 2 :box-shadow-spread 3
                                     :box-shadow-color "#123456"
                                     :width 80 :height 40})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))]
    (is (= -3 (:x shadow-rect)))
    (is (= -1 (:y shadow-rect)))
    (is (= 86 (:w shadow-rect)))
    (is (= 46 (:h shadow-rect)))))

;; ---- box-shadow-x/y/spread/blur and text-shadow-x/y/blur are eagerly
;; coerced to numbers, not left as raw strings ----
;;
;; node-style previously left these six properties as whatever raw value
;; reached :attrs, unlike sibling properties (:padding/:margin/:border-
;; width) already parse-int'd a few lines above in the same function.
;; The ordinary CSS-authored shorthand path (a real box-shadow/text-
;; shadow declaration, via either a stylesheet rule or an inline style=
;; attribute) already numeric-coerces these before they ever reach
;; :attrs, so this gap was invisible for ordinary authored CSS -- but a
;; script mutating an INDIVIDUAL style property directly (dom/set-style
;; below simulates exactly what that uncoerced path delivers) could
;; still reach box-shadow-ops'/layout-text's own raw (+ x dx (- spread))
;; arithmetic with a genuine STRING value, crashing with
;; ClassCastException on the JVM (confirmed via direct REPL reproduction
;; before this fix) or silently producing JS string-concatenation
;; garbage coordinates on this engine's real ClojureScript/JS target,
;; where + compiles straight to the native, type-unchecked JS operator.

(deftest box-shadow-string-coordinates-are-coerced-to-numbers-not-left-raw
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:box-shadow-x "8" :box-shadow-y "8" :box-shadow-color "#000000"
                                     :width 100 :height 20})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))]
    (is (= 8 (:x shadow-rect))
        "a STRING \"8\" must resolve to the real number 8, not JS string-concatenation garbage or a crash")
    (is (= 8 (:y shadow-rect)))))

(deftest box-shadow-spread-as-a-string-is-also-coerced
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:box-shadow-x "0" :box-shadow-y "2" :box-shadow-spread "3"
                                     :box-shadow-color "#123456"
                                     :width 80 :height 40})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))]
    (is (= -3 (:x shadow-rect)))
    (is (= -1 (:y shadow-rect)))
    (is (= 86 (:w shadow-rect)))
    (is (= 46 (:h shadow-rect)))
    "identical to box-shadow-spread-radius-expands-the-shadow-rect-outward above, but every value authored as a string"))

(deftest text-shadow-string-coordinates-are-coerced-to-numbers-not-left-raw
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:text-shadow-x "3" :text-shadow-y "3" :text-shadow-color "#000000"
                                     :width 100})
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        text-ops (filterv #(= :text (:draw/op %)) ops)
        [shadow-op real-op] text-ops]
    (is (= 2 (count text-ops))
        "a real text-shadow op must still be emitted alongside the real text, not silently dropped/crashed away")
    (is (= 11 (:x shadow-op) (+ 3 (:x real-op)))
        "the shadow op's own x must be the real text's x offset by the (coerced-from-string) 3px, not garbage")
    (is (= 11 (:y shadow-op) (+ 3 (:y real-op))))))

(deftest no-box-shadow-declared-produces-no-shadow-rect-at-all
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:background "#ff0000"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})]
    (is (not-any? :box-shadow? ops))))

(deftest box-shadow-color-none-produces-no-shadow-rect-either
  ;; A defensive guard, not just an absence check: a direct
  ;; :style/box-shadow-color "none" write (bypassing expand-box-shadow-
  ;; shorthand, which never itself produces this value for box-shadow --
  ;; unlike text-shadow, there is no ancestor value to cancel) must still
  ;; be treated the same as no shadow at all, the same defensive check
  ;; text-shadow's own shadow-op emission already makes.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:box-shadow-x 4 :box-shadow-y 4 :box-shadow-color "none"
                                     :background "#ff0000"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})]
    (is (not-any? :box-shadow? ops))))

(deftest box-shadow-is-not-a-real-inherited-property
  ;; Unlike text-shadow, box-shadow does NOT inherit in real CSS -- a
  ;; child with no box-shadow of its own must never see its parent's.
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:box-shadow-x 4 :box-shadow-y 4 :box-shadow-color "#000000"
                                        :width 200 :height 100})
        [child doc] (dom/create-element doc :span)
        doc (dom/set-style doc child {:background "#0000ff" :width 40 :height 20})
        doc (dom/append-child doc parent child)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        shadow-rects (filter :box-shadow? ops)]
    (is (= 1 (count shadow-rects))
        "exactly the parent's own single box-shadow rect -- the child must not have inherited a second one")))

(deftest flex-container-box-shadow-paints-before-background
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :box-shadow-x 4 :box-shadow-y 4 :box-shadow-color "#000000"
                                     :background "#ff0000"})
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc div span)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %)) (not (:box-shadow? %))) ops))]
    (is (some? shadow-rect))
    (is (< (.indexOf ops shadow-rect) (.indexOf ops bg-rect))
        "box-shadow must paint before the flex container's own background, same convention as block")))

(deftest grid-container-box-shadow-paints-before-background
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "grid" :box-shadow-x 4 :box-shadow-y 4 :box-shadow-color "#000000"
                                     :background "#ff0000"})
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc div span)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %)) (not (:box-shadow? %))) ops))]
    (is (some? shadow-rect))
    (is (< (.indexOf ops shadow-rect) (.indexOf ops bg-rect))
        "box-shadow must paint before the grid container's own background, same convention as block/flex")))

(deftest form-control-box-shadow-paints-before-background
  (let [[input doc] (dom/create-element dom/empty-document :input)
        doc (dom/set-root doc input)
        doc (dom/set-style doc input {:box-shadow-x 4 :box-shadow-y 4 :box-shadow-color "#000000"
                                       :background "#445566"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter #(and (= :rect (:draw/op %)) (:box-shadow? %)) ops))
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %)) (not (:box-shadow? %))) ops))]
    (is (some? shadow-rect))
    (is (< (.indexOf ops shadow-rect) (.indexOf ops bg-rect))
        "box-shadow must paint before a form control's own background, same convention as block/flex/grid")))

;; ---- outline: a non-layout-affecting ring painted OUTSIDE the box ----

(deftest outline-paints-after-border-not-before
  ;; Real CSS: outline is the OUTERMOST box decoration, painted on top of
  ;; everything else this element paints -- confirmed via direct REPL
  ;; reproduction before touching source that outline was previously read
  ;; nowhere at all, so this ordering never had a chance to matter (no
  ;; outline rect was ever emitted).
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:outline-width 2 :outline-color "#ff0000"
                                     :border-style "solid" :border-width 3 :border-color "#00ff00" :background "#0000ff"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        outline-rect (first (filter #(and (= :rect (:draw/op %)) (:outline? %) (= :top (:edge %))) ops))
        border-rect (first (filter #(and (= :rect (:draw/op %)) (:border? %) (= :top (:edge %))) ops))
        bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %)) (not (:outline? %))) ops))]
    (is (some? outline-rect))
    (is (some? border-rect))
    (is (some? bg-rect))
    (is (< (.indexOf ops bg-rect) (.indexOf ops border-rect))
        "background must still paint before border, unaffected pre-existing convention")
    (is (< (.indexOf ops border-rect) (.indexOf ops outline-rect))
        "outline must paint AFTER border, not before -- it's the outermost decoration")))

(deftest outline-with-zero-offset-sits-directly-against-the-box-edge
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:outline-width 2 :outline-color "#ff0000" :width 100 :height 50})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        by-edge (into {} (map (juxt :edge identity)) (filter #(and (= :rect (:draw/op %)) (:outline? %)) ops))]
    (is (= -2 (:x (:top by-edge))) "the outline's own thickness (2) sits immediately outside x=0 with no offset")
    (is (= -2 (:y (:top by-edge))))
    (is (= 104 (:w (:top by-edge))) "spans the full box width (100) plus 2x the outline thickness")
    (is (= 2 (:h (:top by-edge))))
    (is (= 100 (:x (:right by-edge))) "the right edge's inner face touches the box's own right edge (x=100) directly")))

(deftest outline-offset-pushes-the-ring-further-out
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:outline-width 2 :outline-offset 3 :outline-color "#ff0000"
                                     :width 100 :height 50})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        by-edge (into {} (map (juxt :edge identity)) (filter #(and (= :rect (:draw/op %)) (:outline? %)) ops))]
    (is (= -5 (:x (:top by-edge))) "gap (3) + thickness (2) = 5px outside the box edge")
    (is (= -5 (:y (:top by-edge))))
    (is (= 110 (:w (:top by-edge))) "box width (100) plus 2x (offset+thickness) = 100 + 10")))

(deftest outline-negative-offset-pulls-the-ring-inward
  ;; A real, legal outline-offset value -- unlike border, outline is not
  ;; part of the box, so pulling it back toward (or even inside) the
  ;; border is valid CSS, handled here by the same offset+thickness
  ;; arithmetic with no special-casing at all.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:outline-width 2 :outline-offset -1 :outline-color "#ff0000"
                                     :width 100 :height 50})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        by-edge (into {} (map (juxt :edge identity)) (filter #(and (= :rect (:draw/op %)) (:outline? %)) ops))]
    (is (= -1 (:x (:top by-edge))) "gap (-1 + 2 = 1) pulls the ring 1px inward from the box edge")
    (is (= 102 (:w (:top by-edge))))))

(deftest no-outline-declared-produces-no-outline-rect-at-all
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:background "#ff0000"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})]
    (is (not-any? :outline? ops))))

(deftest outline-is-not-a-real-inherited-property
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        doc (dom/set-style doc parent {:outline-width 2 :outline-color "#ff0000" :width 200 :height 100})
        [child doc] (dom/create-element doc :span)
        doc (dom/set-style doc child {:background "#0000ff" :width 40 :height 20})
        doc (dom/append-child doc parent child)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        outline-rects (filter :outline? ops)]
    (is (= 4 (count outline-rects))
        "exactly the parent's own 4 outline edges -- the child must not have inherited a second set")))

;; ---- currentColor keyword resolution ----
;;
;; Unlike the rest of this file (which sets style attrs directly via
;; kotoba.wasm.dom, bypassing cssom.core's cascade -- see grid-tree's
;; docstring above), these run the real end-to-end pipeline: CSS text ->
;; cssom.core/parse-rules + apply-cascade -> kotoba.wasm.dom tree ->
;; cssom.layout/draw-ops. currentColor is resolved in cssom.core's
;; style-element (the single place that writes the canonical :style/*
;; attrs both this file's rendering AND a live page's getComputedStyle()
;; read), not in cssom.layout -- so exercising it here through the real
;; cascade is the only way to prove the fix lives where it needs to,
;; rather than just that cssom.layout can read an already-resolved attr.

(deftest border-color-currentcolor-resolves-to-the-elements-own-color
  ;; Real CSS: currentColor used in any color-valued property other than
  ;; `color` itself resolves to that same element's own computed `color`
  ;; -- previously entirely unsupported, the literal string "currentColor"
  ;; reached dom-gpu's ->rgba unresolved, which doesn't recognize it as
  ;; hex/rgb/hsl/named, silently painting fully transparent instead of the
  ;; real color, confirmed via direct REPL reproduction.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules "div { color: #ff0000; border: 2px solid currentColor;
                                       width: 80px; height: 40px }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        border-rect (first (filter :border? ops))]
    (is (= "#ff0000" (:color border-rect)))))

(deftest box-shadow-color-currentcolor-resolves-to-the-elements-own-color
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules "div { color: #00ff00; box-shadow: 2px 2px currentColor;
                                       width: 80px; height: 40px }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        shadow-rect (first (filter :box-shadow? ops))]
    (is (= "#00ff00" (:color shadow-rect)))))

(deftest outline-color-currentcolor-is-case-insensitive
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules "div { color: #0000ff; outline: 2px solid CURRENTCOLOR;
                                       width: 80px; height: 40px }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        outline-rect (first (filter :outline? ops))]
    (is (= "#0000ff" (:color outline-rect)))))

(deftest text-shadow-color-currentcolor-resolves-to-the-elements-own-color
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules "div { color: #123456; text-shadow: 1px 1px currentColor }")
        doc (css/apply-cascade doc rules)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc div t)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        [shadow-op _main-op] (filter #(= :text (:draw/op %)) ops)]
    (is (= "#123456" (:color shadow-op)))))

(deftest explicit-color-value-is-not-touched-by-currentcolor-resolution
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules "div { color: #00ff00; border: 2px solid #0000ff;
                                       width: 80px; height: 40px }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        border-rect (first (filter :border? ops))]
    (is (= "#0000ff" (:color border-rect))
        "an explicit color value must be left completely untouched")))

(deftest border-color-currentcolor-with-no-own-color-declared-is-left-unresolved
  ;; Honest scope-cut (documented on cssom.core/resolve-current-color):
  ;; this namespace has no general property-inheritance machinery, so an
  ;; element that only INHERITS its color (rather than declaring its own)
  ;; can't have its currentColor resolved here -- it's left as the literal
  ;; string, exactly as before this fix, rather than silently resolved to
  ;; nil.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules "div { border: 2px solid currentColor; width: 80px; height: 40px }")
        doc (css/apply-cascade doc rules)
        node (get-in doc [:nodes div])]
    (is (= "currentColor" (get-in node [:attrs :style/border-color]))
        "left as the unresolved literal, not silently defaulted to nil")))

(deftest flex-container-outline-paints-after-border
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :outline-width 2 :outline-color "#ff0000"
                                     :border-style "solid" :border-width 3 :border-color "#00ff00"})
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc div span)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        outline-rect (first (filter #(and (= :rect (:draw/op %)) (:outline? %)) ops))
        border-rect (first (filter #(and (= :rect (:draw/op %)) (:border? %)) ops))]
    (is (some? outline-rect))
    (is (< (.indexOf ops border-rect) (.indexOf ops outline-rect))
        "outline must paint after the flex container's own border, same convention as block")))

;; ---- flex item shrink-to-fit main-axis sizing (flex-basis:auto default) ----

(deftest unstyled-flex-row-children-shrink-wrap-to-their-own-text-instead-of-filling-the-container
  ;; Real CSS: flex-basis:auto (the default) falls back to an item's own
  ;; content-based (shrink-to-fit) size, not resolve-width's own block-
  ;; default fallback to the FULL available width -- previously applied
  ;; uniformly to flex children too, confirmed via direct REPL
  ;; reproduction before touching source: two unstyled <button> flex
  ;; children each rendered at the full container width (472px each in a
  ;; 480px container) instead of shrink-wrapping to their own short
  ;; labels, ballooning the container itself to fit them.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :gap 8})
        [b1 doc] (dom/create-element doc :button)
        doc (dom/append-child doc div b1)
        [t1 doc] (dom/create-text-node doc "OK")
        doc (dom/append-child doc b1 t1)
        [b2 doc] (dom/create-element doc :button)
        doc (dom/append-child doc div b2)
        [t2 doc] (dom/create-text-node doc "Cancel")
        doc (dom/append-child doc b2 t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        buttons (filterv #(and (= :rect (:draw/op %)) (= :button (:tag %))) ops)
        container-rect (first (filterv #(and (= :rect (:draw/op %)) (= :div (:tag %))) ops))]
    (is (= 2 (count buttons)))
    (is (< (:w (first buttons)) 100)
        (str "\"OK\" must shrink-wrap to a compact width, not fill the 480px container -- got "
             (:w (first buttons))))
    (is (< (:w (second buttons)) (:w container-rect))
        "each button must be narrower than the flex container itself")
    (is (not= (:w (first buttons)) (:w (second buttons)))
        "\"OK\" and \"Cancel\" have different label lengths, so they must NOT resolve to the identical width")))

(defn- space-between-item-x-offsets
  "Builds a flex row with the given per-item width/gap, all under an
   explicit 300px container width so main-axis free-space math is
   deterministic, and returns each in-flow child's own :x offset (a plain
   :draw/op :node for a background-less <span>, not a :rect -- unlike a
   styled <button>, a bare span paints no rect of its own)."
  [item-width gap]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :justify-content "space-between"
                                     :gap gap :width 300})
        make-item (fn [doc]
                    (let [[item doc] (dom/create-element doc :span)
                          doc (dom/append-child doc div item)
                          doc (dom/set-style doc item {:width item-width})]
                      [item doc]))
        [_ doc] (make-item doc)
        [_ doc] (make-item doc)
        [_ doc] (make-item doc)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})]
    (mapv :x (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops))))

(deftest space-between-reserves-gap-as-a-minimum-inter-item-spacing
  ;; Real CSS: `gap` is a MINIMUM inter-item spacing that `justify-content:
  ;; space-between` must still honor, not just a fallback for when free
  ;; space runs out. Previously the space-between branch of place-main-axis
  ;; had no gap term in its free-space computation at all (unlike the
  ;; sibling center/flex-end branch), so a nonzero gap was silently
  ;; ignored whenever the container wasn't dramatically larger than the
  ;; summed item sizes -- confirmed via a direct REPL reproduction before
  ;; touching source: with three 90px-wide items, gap:20px, and a 300px
  ;; container, this produced the exact same offsets as gap:0.
  (is (= [4 112.0 220.0] (space-between-item-x-offsets 90 20))
      "three 90px items plus two 20px gaps want 310px of a 292px content
         area, so with `flex-shrink: 1` -- the DEFAULT -- they shrink to fit
         rather than overflowing, and the gap stays exactly 20px between
         them. This asserted the pre-shrink offsets, from when this engine
         froze every item at its base size"))

(deftest space-between-still-distributes-extra-free-space-beyond-the-gap-floor
  ;; Once free space genuinely exceeds what the gap alone needs, real CSS
  ;; space-between still distributes the REMAINING extra space between
  ;; items, on top of the gap floor -- not instead of it.
  (is (= [4 129 254] (space-between-item-x-offsets 50 20))
      "150px of items + 40px gap floor leaves 110px extra free space, split 55/55 between the two gaps on top of the 20px floor each (plus the container's own 4px padding)"))

(deftest space-between-with-zero-gap-is-unaffected-by-this-fix
  (is (= [4 109 214] (space-between-item-x-offsets 90 0))))

;; ---- justify-content: space-around / space-evenly ----
;;
;; place-main-axis previously had NO branch for either of these two other
;; spec-mandated (CSS Flexible Box Layout SS8.3) distribution keywords --
;; both silently fell through to :else's flush-start packing, identical to
;; flex-start. Confirmed via a direct REPL reproduction before touching
;; source: three 50px-wide items under justify-content:space-around (or
;; :space-evenly) produced the exact same offsets as flex-start. Fixed by
;; mirroring space-between's own gap-as-minimum-floor pattern above:
;; space-around gives each item a full free/n share split half-lead/half-
;; trail; space-evenly divides free space into n+1 equal gaps (before the
;; first item, between each pair, and after the last).

(defn- space-distribution-item-x-offsets
  "space-between-item-x-offsets's own space-around/space-evenly
   counterpart: builds a flex row of three same-width items under the
   given justify-content/gap, all under an explicit 300px container width."
  [justify item-width gap]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :justify-content justify
                                     :gap gap :width 300})
        make-item (fn [doc]
                    (let [[item doc] (dom/create-element doc :span)
                          doc (dom/append-child doc div item)
                          doc (dom/set-style doc item {:width item-width})]
                      [item doc]))
        [_ doc] (make-item doc)
        [_ doc] (make-item doc)
        [_ doc] (make-item doc)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})]
    (mapv :x (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops))))

(deftest space-around-distributes-free-space-as-half-shares-around-each-item
  (is (= [12 112 212] (space-distribution-item-x-offsets "space-around" 84 0))
      "each item gets a full 100px/3 share split 50/50 lead/trail: item 1 leads by half a share (50), adjacent items' half-shares combine into one full share (100) between them"))

(deftest space-around-reserves-gap-as-a-minimum-inter-item-spacing
  (is (= [5.333333333333333 112.0 218.66666666666666]
         (space-distribution-item-x-offsets "space-around" 90 20))
      "the items shrink to fit (flex-shrink defaults to 1), and
         space-around then distributes what the gap floor leaves --
         previously they overflowed at their base size"))

(deftest space-evenly-distributes-free-space-into-n-plus-1-equal-gaps
  (is (= [16 112 208] (space-distribution-item-x-offsets "space-evenly" 84 0))
      "4 equal gaps (before/between x2/after) of 40px each -- genuinely different from space-around's [12 112 212] at the identical item width, not an alias"))

(deftest space-evenly-reserves-gap-as-a-minimum-inter-item-spacing
  (is (= [6 112 218] (space-distribution-item-x-offsets "space-evenly" 84 20))
      "the 20px author gap is still honored as a floor between items, on top of space-evenly's own equal-gap distribution"))

(defn- single-item-x-offset
  [justify item-width]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :justify-content justify :width 300})
        [item doc] (dom/create-element doc :span)
        doc (dom/append-child doc div item)
        doc (dom/set-style doc item {:width item-width})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})]
    (first (mapv :x (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops)))))

(deftest space-around-and-space-evenly-both-center-a-single-item-matching-plain-center
  ;; With exactly one item, both distribution models degenerate to a
  ;; single lead and a single trail gap of equal size -- i.e. centering,
  ;; matching real CSS and matching this file's own plain justify-
  ;; content:center behavior for the same input.
  (is (= 104 (single-item-x-offset "space-around" 100)))
  (is (= 104 (single-item-x-offset "space-evenly" 100)))
  (is (= 104 (single-item-x-offset "center" 100))
      "sanity check: matches plain center exactly, confirming the single-item degeneration is correct, not coincidental"))

(deftest explicit-width-on-a-flex-child-is-not-touched-by-shrink-to-fit
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex"})
        [b1 doc] (dom/create-element doc :button)
        doc (dom/append-child doc div b1)
        doc (dom/set-style doc b1 {:width 200})
        [t1 doc] (dom/create-text-node doc "OK")
        doc (dom/append-child doc b1 t1)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        button-rect (first (filter #(= :button (:tag %)) ops))]
    (is (= 216 (:w button-rect))
        "an explicit :width must win outright, exactly like real CSS
         flex-basis:auto falling back to an explicit width first. 200 of
         CONTENT plus the button's own UA box (6px padding each side, 2px
         border each side) is a 216px border box -- content-box sizing,
         and the UA padding/border a browser gives a <button>")))

(deftest flex-item-shrink-to-fit-still-clamps-to-available-space
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex"})
        [b1 doc] (dom/create-element doc :button)
        doc (dom/append-child doc div b1)
        [t1 doc] (dom/create-text-node doc "This is a very very very very very long button label indeed")
        doc (dom/append-child doc b1 t1)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        button-rect (first (filter #(= :button (:tag %)) ops))]
    (is (<= (:w button-rect) 200)
        "a long label must still clamp to the actually-available main-axis space, not overflow un-shrunk")))

(deftest flex-item-with-mixed-inline-content-shrink-wraps-to-its-max-content
  ;; This test used to pin the OPPOSITE ("falls back to filling available
  ;; width"), a scope-cut that only leaf-single-text items got real
  ;; shrink-to-fit. Mixed inline content has a real max-content width too --
  ;; everything on one line -- computed by reusing the inline fragments and
  ;; tokenizer, so whitespace collapses exactly as it will when the run is
  ;; really laid out. The geometry axis made the cut untenable: a table
  ;; column holding one `<b>` filled 800px where a browser shrink-wraps to
  ;; 72.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex"})
        [wrap doc] (dom/create-element doc :span)
        doc (dom/append-child doc div wrap)
        [icon doc] (dom/create-element doc :i)
        doc (dom/append-child doc wrap icon)
        [t1 doc] (dom/create-text-node doc "Label")
        doc (dom/append-child doc wrap t1)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        span-rect (first (filter #(= :span (:tag %)) ops))]
    (is (< (:w span-rect) 200)
        "the item is as wide as its icon plus its label on one line, not
         the whole 480px container")))

(deftest column-direction-flex-items-are-unaffected-by-row-direction-shrink-to-fit
  ;; column direction's main axis is height, which this file's :height
  ;; resolution already defaults to content-driven sizing -- the row-
  ;; direction-only shrink-to-fit fix above must not change column
  ;; behavior at all (an unstyled child's cross-axis WIDTH still fills
  ;; available space, same as before this fix).
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :flex-direction "column"})
        [b1 doc] (dom/create-element doc :button)
        doc (dom/append-child doc div b1)
        [t1 doc] (dom/create-text-node doc "OK")
        doc (dom/append-child doc b1 t1)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        button-rect (first (filter #(= :button (:tag %)) ops))]
    (is (> (:w button-rect) 400)
        "column-direction cross-axis (width) must still fill available space, unaffected by the row-direction fix")))

;; ---- align-items:stretch ----
;;
;; Real CSS's own align-items DEFAULT (real browsers stretch cross-axis-
;; auto-sized children to match the tallest/widest sibling whenever
;; align-items is unset) was never actually implemented as a size change
;; here -- cross-offset only ever repositioned a child within the cross
;; axis, so "stretch" (not handled by its own case) silently behaved
;; exactly like "flex-start": zero resize, zero offset. Confirmed via a
;; direct REPL reproduction before touching source: two 300px-wide flex-
;; row items, one 50px wide with no explicit height and one 50px wide with
;; an explicit height of 40, and NO align-items declared (so it defaults
;; to stretch) -- the auto-height item stayed at its own tiny natural
;; height instead of stretching to match its 40px sibling.

(defn- flex-span-boxes
  "Builds a flex row/column with two <span> children (no text content, so
   each one's own natural size comes purely from resolve-width/resolve-
   height's own defaults, not text metrics) and returns each child's own
   [x y w h] draw box, in source order."
  [container-style a-style b-style]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div (merge {:display "flex"} container-style))
        [a doc] (dom/create-element doc :span)
        doc (dom/append-child doc div a)
        doc (dom/set-style doc a a-style)
        [b doc] (dom/create-element doc :span)
        doc (dom/append-child doc div b)
        doc (dom/set-style doc b b-style)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        spans (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops)]
    (mapv (fn [s] [(:x s) (:y s) (:w s) (:h s)]) spans)))

(deftest flex-row-align-items-default-stretches-an-auto-height-child-to-match-its-sibling
  (let [[a b] (flex-span-boxes {:width 300} {:width 50} {:width 50 :height 40})]
    (is (= 40 (nth a 3)) "A has no explicit height, so it must stretch to match B's 40px height, matching real CSS's own unauthored align-items default")
    (is (= 40 (nth b 3)) "B's own explicit height is untouched")
    (is (= 4 (nth a 1)) "A's stretched box starts flush at the content-area top, not centered/offset")))

(deftest flex-row-align-items-stretch-is-a-no-op-for-a-child-with-its-own-explicit-height
  (let [[a b] (flex-span-boxes {:width 300} {:width 50 :height 10} {:width 50 :height 40})]
    (is (= 10 (nth a 3)) "an explicit cross-size always wins over stretch, exactly like real CSS -- A must NOT be forced up to match B")))

(deftest flex-row-align-items-center-is-unaffected-by-the-stretch-fix
  (let [[a b] (flex-span-boxes {:align-items "center" :width 300} {:width 50} {:width 50 :height 40})]
    (is (= 8 (nth a 3)) "A keeps its own natural (unstretched) height under an explicit non-stretch align-items")
    (is (= 20 (nth a 1)) "A is vertically centered within B's 40px cross size: (40-8)/2 = 16, plus the container's own 4px content-area top inset")))

(deftest flex-row-align-items-flex-start-is-unaffected-by-the-stretch-fix
  (let [[a b] (flex-span-boxes {:align-items "flex-start" :width 300} {:width 50} {:width 50 :height 40})]
    (is (= 8 (nth a 3)) "flex-start must not stretch A -- this must stay the exact same as before this fix")))

(deftest flex-row-align-items-stretch-with-an-explicit-container-height-stretches-to-that-height
  (let [[a b] (flex-span-boxes {:width 300 :height 60} {:width 50} {:width 50})]
    (is (= 60 (nth a 3)))
    (is (= 60 (nth b 3)) "both auto-height children stretch to the CONTAINER's own explicit height, not just to each other")))

(deftest flex-row-align-items-stretch-still-respects-a-childs-own-max-height
  (let [[a b] (flex-span-boxes {:width 300} {:width 50 :max-height 20} {:width 50 :height 40})]
    (is (= 20 (nth a 3))
        "real CSS still clamps a stretched item to its own max-height -- A must NOT overshoot to 40 just because that's what stretch would otherwise produce")))

;; ---- flex-wrap:wrap + align-items ----
;;
;; layout-flex-wrap-row previously ignored align-items ENTIRELY -- not even
;; the pre-existing center/flex-end cross-axis offsets the non-wrap branch
;; already had before this fix, let alone stretch -- every wrapped child
;; was unconditionally top-aligned to its own row's own top edge. Confirmed
;; via a direct REPL reproduction before touching source: two flex-
;; wrap:wrap children (heights 20/80, align-items:center) both stayed at
;; y=4, while the identical style with flex-wrap OMITTED (the already-
;; correct non-wrap path) correctly put the shorter child at y=34,
;; properly centered within the taller sibling's own 80px height.

(defn- flex-wrap-span-boxes
  "flex-span-boxes's own wrap-mode counterpart: builds a flex-wrap:wrap row
   with N <span> children (each own [width height-or-nil] pair) under an
   explicit gap:0 (deterministic row-packing math, unaffected by this
   file's own default nonzero theme gap) and returns each child's own
   [x y w h] draw box, in source order."
  [align container-width items]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div (merge {:display "flex" :flex-wrap "wrap" :width container-width :gap 0}
                                           (when align {:align-items align})))
        doc (reduce (fn [doc [w h]]
                      (let [[item doc] (dom/create-element doc :span)
                            doc (dom/append-child doc div item)]
                        (dom/set-style doc item (cond-> {:width w} h (assoc :height h)))))
                    doc items)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width container-width})
        spans (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops)]
    (mapv (fn [s] [(:x s) (:y s) (:w s) (:h s)]) spans)))

(deftest flex-wrap-align-items-center-vertically-centers-a-shorter-child-within-its-own-row
  (let [[a b] (flex-wrap-span-boxes "center" 300 [[50 20] [50 80]])]
    (is (= [4 34 50 20] a) "A (height 20) centered within B's 80px row-cross-size: (80-20)/2 = 30, plus the container's own 4px content-area top inset")
    (is (= [54 4 50 80] b) "B (the row's own tallest child) stays flush at the row's own top")))

(deftest flex-wrap-align-items-default-stretches-an-auto-height-child-within-its-own-row
  (let [[a b] (flex-wrap-span-boxes nil 300 [[50 nil] [50 40]])]
    (is (= 40 (nth a 3)) "A has no explicit height, so it must stretch to match B's 40px row-cross-size, matching real CSS's own unauthored align-items default")
    (is (= 4 (nth a 1)) "a fully-stretched child sits flush at the row's own top, zero offset")))

(deftest flex-wrap-align-items-stretch-is-scoped-to-each-rows-own-cross-size-not-the-whole-container
  ;; cw at container-width 300 with this file's own 4px default padding on
  ;; each side is 292 -- two 140px-wide items fit one row (280 <= 292), a
  ;; third 140px item wraps to its own second row. Row 1's own tallest
  ;; child (60) must NOT influence row 2's independent, smaller stretch
  ;; target -- each wrapped line stretches to ITS OWN cross size, matching
  ;; real CSS's own per-line stretch model.
  (let [[a b c] (flex-wrap-span-boxes nil 300 [[140 nil] [140 60] [140 30]])]
    (is (= [4 4 140 60] a) "A (no explicit height) stretches to row 1's own 60px cross size")
    (is (= [144 4 140 60] b) "B's own explicit height is untouched")
    (is (= [4 64 140 30] c) "C, alone in row 2, keeps its own explicit 30px height -- unaffected by row 1's larger 60px cross size")))

(deftest flex-wrap-align-items-flex-start-is-unaffected-by-this-fix
  (let [[a b] (flex-wrap-span-boxes "flex-start" 300 [[50 20] [50 80]])]
    (is (= [4 4 50 20] a) "flex-start must stay byte-identical to this function's own pre-fix (align-items-ignoring) top-aligned behavior")))

;; ---- flex-wrap:wrap + justify-content ----
;;
;; layout-flex-wrap-row's own main-axis packing hardcoded the literal
;; "flex-start" instead of reading (:justify-content st), unlike layout-
;; flex's own non-wrap path a few hundred lines away, which already reads
;; it correctly. Confirmed via a direct REPL reproduction before touching
;; source: two flex-wrap:wrap children under justify-content:flex-end (or
;; :center) both stayed at their flex-start offsets, identical to omitting
;; justify-content entirely, while the identical style with flex-wrap
;; OMITTED (the already-correct non-wrap path) correctly right-aligned/
;; centered them.

(defn- flex-wrap-justify-boxes
  "flex-wrap-span-boxes's own justify-content counterpart: builds a
   flex-wrap:wrap row with N <span> children (each own [width height]
   pair) under an explicit gap:0 and returns each child's own [x y w h]
   draw box, in source order."
  [justify container-width items]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div (merge {:display "flex" :flex-wrap "wrap" :width container-width :gap 0}
                                           (when justify {:justify-content justify})))
        doc (reduce (fn [doc [w h]]
                      (let [[item doc] (dom/create-element doc :span)
                            doc (dom/append-child doc div item)]
                        (dom/set-style doc item {:width w :height h})))
                    doc items)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width container-width})
        spans (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops)]
    (mapv (fn [s] [(:x s) (:y s) (:w s) (:h s)]) spans)))

(deftest flex-wrap-justify-content-flex-end-right-aligns-a-single-row
  (let [[a b] (flex-wrap-justify-boxes "flex-end" 300 [[100 20] [100 20]])]
    (is (= [96 4 100 20] a) "row content (200) right-aligned within the 292px content area: 292-200=92, plus the container's own 4px content-area left inset")
    (is (= [196 4 100 20] b))))

(deftest flex-wrap-justify-content-center-centers-a-single-row
  (let [[a b] (flex-wrap-justify-boxes "center" 300 [[100 20] [100 20]])]
    (is (= [50 4 100 20] a) "row content (200) centered within the 292px content area: (292-200)/2=46, plus the container's own 4px content-area left inset")
    (is (= [150 4 100 20] b))))

(deftest flex-wrap-justify-content-flex-start-is-unaffected-by-this-fix
  (let [[a b] (flex-wrap-justify-boxes "flex-start" 300 [[100 20] [100 20]])]
    (is (= [4 4 100 20] a) "flex-start must stay byte-identical to this function's own pre-fix (justify-content-ignoring) start-packed behavior")
    (is (= [104 4 100 20] b))))

(deftest flex-wrap-justify-content-is-scoped-to-each-rows-own-content-width-not-the-whole-container
  ;; Mirrors the sibling align-items per-row-independence test above: each
  ;; wrapped row must center against ITS OWN row content width, not the
  ;; container's, or a shared whole-container width. Row 1 (two 140px
  ;; items, 280 total) and row 2 (one 140px item, alone) each get their
  ;; own, independently-computed centering offset.
  (let [[a b c] (flex-wrap-justify-boxes "center" 300 [[140 20] [140 20] [140 20]])]
    (is (= [10 4 140 20] a) "row 1 (280 content) centered within 292: (292-280)/2=6, plus the 4px inset")
    (is (= [150 4 140 20] b))
    (is (= [80 24 140 20] c) "row 2, alone, centered on ITS OWN 140px content within 292: (292-140)/2=76, plus the 4px inset -- not influenced by row 1's own, different offset")))

;; ---- flexbox beyond the defaults: order / align-self / the reversing
;; ---- directions / flex-basis / the automatic minimum / align-content
;;
;; Every one of the properties below was measured against a real headless
;; Brave before it was implemented, and every number asserted here is the
;; engine's own answer for the shape the browser was measured on (the
;; browser's absolute coordinates cannot be asserted directly -- this
;; engine approximates glyph advances, see the ns docstring -- but the
;; STRUCTURE of the answer, which item is where relative to which, is
;; exactly what the browser was consulted for).

(defn- flex-item-boxes
  "flex-span-boxes generalised to N children with arbitrary per-child
   styles: builds a flex container and returns each child's own
   [x y w h] draw box, in DRAW order (which is the order the items are
   painted in, and therefore `order`-modified rather than source order --
   see order-flex-items)."
  [container-style item-styles]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div (merge {:display "flex"} container-style))
        doc (reduce (fn [doc s]
                      (let [[item doc] (dom/create-element doc :span)
                            doc (dom/append-child doc div item)]
                        (dom/set-style doc item s)))
                    doc item-styles)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        spans (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops)]
    (mapv (fn [s] [(:x s) (:y s) (:w s) (:h s)]) spans)))

(deftest flex-order-reorders-items-and-their-paint-order
  (let [[first-drawn second-drawn]
        (flex-item-boxes {:width 300} [{:width 50 :height 10 :order 2}
                                       {:width 60 :height 10 :order 1}])]
    (is (= [0 0 60 10] first-drawn)
        "the `order: 1` item comes FIRST in the line even though it is second in the document")
    (is (= [60 0 50 10] second-drawn)
        "and the `order: 2` item follows it -- real CSS reorders painting as well as placement, so the draw-op order changes too")))

(deftest flex-order-ties-are-broken-by-document-order
  (let [boxes (flex-item-boxes {:width 300} [{:width 10 :height 10 :order 3}
                                             {:width 20 :height 10}
                                             {:width 30 :height 10 :order -1}
                                             {:width 40 :height 10 :order 3}])]
    (is (= [30 20 10 40] (mapv #(nth % 2) boxes))
        "ascending order (-1, 0, 3, 3) with the two `order: 3` items still in their own document order")))

(deftest flex-align-self-overrides-the-containers-align-items
  (let [[a b c] (flex-item-boxes {:height 60 :align-items "flex-start"}
                                 [{:width 10 :height 20}
                                  {:width 10 :height 20 :align-self "flex-end"}
                                  {:width 10 :height 20 :align-self "center"}])]
    (is (= 0 (nth a 1)) "no align-self: the container's flex-start applies")
    (is (= 40 (nth b 1)) "align-self:flex-end puts it at the bottom of the 60px cross size")
    (is (= 20 (nth c 1)) "align-self:center puts it in the middle")))

(deftest flex-align-self-stretch-overrides-a-non-stretch-container
  (let [[a b] (flex-item-boxes {:height 60 :align-items "center"}
                               [{:width 10 :height 20}
                                {:width 10 :align-self "stretch"}])]
    (is (= 20 (nth a 3)) "the container's align-items:center leaves A at its own height")
    (is (= 60 (nth b 3)) "align-self:stretch is a SIZE change even when the container does not stretch")))

(deftest flex-align-self-auto-defers-to-the-container
  (let [[a] (flex-item-boxes {:height 60 :align-items "center"}
                             [{:width 10 :height 20 :align-self "auto"}])]
    (is (= 20 (nth a 1))
        "`auto` is the initial value and means 'use align-items', not an alignment of its own")))

(deftest flex-direction-row-reverse-lays-the-line-out-from-the-right
  (let [boxes (flex-item-boxes {:width 300 :flex-direction "row-reverse"}
                               [{:width 50 :height 10} {:width 50 :height 10}])]
    (is (= [250 200] (mapv first boxes))
        "the FIRST item takes the right edge and the line runs back towards the left")))

(deftest flex-direction-row-reverse-flips-justify-content-too
  (let [boxes (flex-item-boxes {:width 300 :flex-direction "row-reverse" :justify-content "flex-end"}
                               [{:width 50 :height 10} {:width 50 :height 10}])]
    (is (= [50 0] (mapv first boxes))
        "flex-end is flex-RELATIVE: in a reversed row it packs against the physical left")))

(deftest flex-direction-column-reverse-is-a-column-not-a-row
  (let [boxes (flex-item-boxes {:height 90 :flex-direction "column-reverse"}
                               [{:width 50 :height 20} {:width 50 :height 20}])]
    (is (= [70 50] (mapv second boxes))
        "items stack vertically from the BOTTOM -- before this, `column-reverse` was not recognised as a column at all and laid its items out side by side")
    (is (= [0 0] (mapv first boxes)) "and share the same cross-axis start")))

(deftest flex-wrap-reverse-stacks-the-lines-from-the-bottom
  (let [boxes (flex-item-boxes {:width 200 :flex-wrap "wrap-reverse" :gap 0}
                               [{:width 120 :height 20} {:width 120 :height 20}
                                {:width 120 :height 20}])]
    (is (= [40 20 0] (mapv second boxes))
        "line 1 goes to the far edge of the cross axis and each later line above it")))

(deftest flex-wrap-reverse-flips-align-items-within-each-line
  (let [boxes (flex-item-boxes {:width 200 :height 100 :flex-wrap "wrap-reverse"
                                :align-items "flex-start" :gap 0}
                               [{:width 120 :height 10} {:width 120 :height 30}])]
    (is (= [90 30] (mapv second boxes))
        "a reversed cross axis makes `flex-start` mean each line's own FAR edge -- both items sit at the bottom of their (align-content-stretched) line")))

(deftest flex-basis-length-replaces-the-items-own-measured-size
  (let [boxes (flex-item-boxes {:width 300}
                               [{:width 10 :height 10 :flex-basis 100}
                                {:width 10 :height 10 :flex-basis 50 :flex-grow 1}])]
    (is (= [100 200] (mapv #(nth % 2) boxes))
        "the declared basis is what the free space is distributed around: 100 stays, and the 150px of leftover all goes to the growing item")))

(deftest flex-basis-auto-keeps-the-items-own-size
  (let [boxes (flex-item-boxes {:width 300}
                               [{:width 40 :height 10 :flex-basis "auto"}
                                {:width 60 :height 10}])]
    (is (= [40 60] (mapv #(nth % 2) boxes))
        "`auto` -- the initial value -- means 'use the item's own main size', which is what an item with no basis at all already did")))

(deftest flex-shrink-actually-resizes-an-item-that-declares-its-own-width
  (let [boxes (flex-item-boxes {:width 200}
                               [{:width 150 :height 10} {:width 150 :height 10}])]
    (is (= [100 100] (mapv #(nth % 2) boxes))
        "both items shrink to 100 -- re-laying the item out against a narrower AVAILABLE width is not enough on its own, since a declared width resolves to itself no matter how little room it is given")
    (is (= [0.0 100.0] (mapv (comp double first) boxes))
        "and the second one starts where the first now ends")))

(deftest flex-shrink-zero-leaves-its-own-item-and-overflows-the-siblings
  (let [boxes (flex-item-boxes {:width 200}
                               [{:width 150 :height 10 :flex-shrink 0}
                                {:width 150 :height 10}])]
    (is (= [150 50] (mapv #(nth % 2) boxes))
        "a `flex-shrink: 0` item is frozen before the first pass, so the whole overflow comes off its sibling")))

(deftest flex-align-content-distributes-the-lines-of-a-definite-cross-size
  (let [boxes (flex-item-boxes {:width 200 :height 120 :flex-wrap "wrap" :gap 0
                                :align-content "space-between"}
                               [{:width 120 :height 20} {:width 120 :height 20}])]
    (is (= [0 100] (mapv second boxes))
        "two 20px lines in a 120px container: one at each end")))

(deftest flex-align-content-stretch-grows-the-lines-themselves
  (let [boxes (flex-item-boxes {:width 200 :height 80 :flex-wrap "wrap" :gap 0}
                               [{:width 120} {:width 120}])]
    (is (= [40 40] (mapv #(nth % 3) boxes))
        "the initial align-content is `stretch`, which is a SIZE change: two lines split an 80px container, and align-items:stretch then fills each line")
    (is (= [0 40] (mapv second boxes)))))

(deftest flex-align-content-does-nothing-without-a-definite-cross-size
  (let [boxes (flex-item-boxes {:width 200 :flex-wrap "wrap" :gap 0
                                :align-content "space-between"}
                               [{:width 120 :height 20} {:width 120 :height 20}])]
    (is (= [0 20] (mapv second boxes))
        "an auto-height container is sized BY its lines, so there is no free space for align-content to distribute and the lines stay packed")))

(deftest flex-auto-main-margin-absorbs-the-free-space
  (let [boxes (flex-item-boxes {:width 300}
                               [{:width 40 :height 10}
                                {:width 60 :height 10 :margin-left "auto"}])]
    (is (= [0 240] (mapv first boxes))
        "all 200px of free space goes into the one auto margin, which is how a toolbar pushes its last item to the end")))

(deftest flex-auto-margins-on-both-sides-center-the-item
  (let [boxes (flex-item-boxes {:width 300}
                               [{:width 100 :height 10 :margin-left "auto" :margin-right "auto"}])]
    (is (= [100] (mapv first boxes))
        "two auto margins split the 200px of free space equally")))

(deftest flex-auto-margin-outranks-justify-content
  (let [boxes (flex-item-boxes {:width 300 :justify-content "center"}
                               [{:width 40 :height 10}
                                {:width 60 :height 10 :margin-left "auto"}])]
    (is (= [0 240] (mapv first boxes))
        "real CSS gives the auto margins the free space FIRST, leaving justify-content nothing to distribute")))

(deftest a-column-flex-container-shrink-wraps-a-non-stretched-item
  (let [boxes (flex-item-boxes {:width 200 :flex-direction "column" :align-items "center"}
                               [{:height 10} {:height 10}])]
    (is (= [0 0] (mapv #(nth % 2) boxes))
        "a column item's CROSS axis is its width, and a cross axis is only FILLED when it stretches -- under any other alignment the item is fit-content, which for an empty <span> is nothing. Measuring every column item at the container width instead made align-items look unimplemented for columns")
    (is (= [100 100] (mapv first boxes))
        "and the zero-width items are centred in the container's own 200px cross size")))

(deftest a-column-flex-container-centers-against-its-own-width-not-the-widest-item
  (let [boxes (flex-item-boxes {:width 200 :flex-direction "column" :align-items "center"}
                               [{:width 40 :height 10} {:width 80 :height 10}])]
    (is (= [80 60] (mapv first boxes))
        "(200-40)/2 and (200-80)/2 -- sizing the cross axis from the widest item instead would centre inside the 80px item")))

(deftest inline-flex-is-inline-level
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [before doc] (dom/create-text-node doc "before")
        doc (dom/append-child doc p before)
        [box doc] (dom/create-element doc :span)
        doc (dom/append-child doc p box)
        doc (dom/set-style doc box {:display "inline-flex"})
        [inner doc] (dom/create-element doc :span)
        doc (dom/append-child doc box inner)
        doc (dom/set-style doc inner {:width 30 :height 10})
        [after doc] (dom/create-text-node doc "after")
        doc (dom/append-child doc p after)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        texts (filterv #(= :text (:draw/op %)) ops)
        outer (first (filterv #(and (= :node (:draw/op %)) (= "inline-flex" (:display %))) ops))]
    (is (some? outer) "the inline-flex box is laid out as a flex container")
    (is (= 30 (:w outer))
        "and shrink-wraps to its items rather than filling the 400px line, which is the whole difference from `display: flex`")
    (is (= 1 (count (distinct (map :y texts))))
        "`before` and `after` stay on ONE line with the box between them -- a block-level flex container would have broken the sentence into three")))

(deftest flex-item-does-not-shrink-below-its-min-content-width
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "flex" :width 120})
        mk (fn [doc text]
             (let [[item doc] (dom/create-element doc :span)
                   doc (dom/append-child doc div item)
                   doc (dom/set-style doc item {:flex-grow 1 :flex-shrink 1 :flex-basis 0})
                   [t doc] (dom/create-text-node doc text)]
               (dom/append-child doc item t)))
        doc (mk doc "averylongunbrokenword")
        doc (mk doc "b")
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        widths (mapv :w (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops))]
    (is (< 120 (first widths))
        "the long word's own min-content width is a floor the item refuses to shrink below, so the line OVERFLOWS the 120px container exactly as a browser's does")
    (is (< (second widths) 20)
        "and the space the first item refused to give up is taken back off the second, which falls to its own floor -- a single distribute-then-clamp pass would have left it at half the container")))

(deftest grid-container-outline-paints-after-border
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div {:display "grid" :outline-width 2 :outline-color "#ff0000"
                                     :border-style "solid" :border-width 3 :border-color "#00ff00"})
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc div span)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        outline-rect (first (filter #(and (= :rect (:draw/op %)) (:outline? %)) ops))
        border-rect (first (filter #(and (= :rect (:draw/op %)) (:border? %)) ops))]
    (is (some? outline-rect))
    (is (< (.indexOf ops border-rect) (.indexOf ops outline-rect))
        "outline must paint after the grid container's own border, same convention as block/flex")))

(deftest form-control-outline-paints-after-border
  (let [[input doc] (dom/create-element dom/empty-document :input)
        doc (dom/set-root doc input)
        doc (dom/set-style doc input {:outline-width 2 :outline-color "#ff0000"
                                       :border-style "solid" :border-width 3 :border-color "#00ff00"})
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 100})
        outline-rect (first (filter #(and (= :rect (:draw/op %)) (:outline? %)) ops))
        border-rect (first (filter #(and (= :rect (:draw/op %)) (:border? %)) ops))]
    (is (some? outline-rect))
    (is (< (.indexOf ops border-rect) (.indexOf ops outline-rect))
        "outline must paint after a form control's own border, same convention as block/flex/grid")))

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
;; `:measure-text` function on `theme` -- `(fn [text font-size font-weight
;; font-style] width-in-px)` -- consulted instead (text-lines-measured).
;; The tests below prove several halves of that contract: (1) omitting
;; `:measure-text` is byte-for-byte the SAME char-w-approximation code
;; path this file has always used (default-theme has no :measure-text key
;; at all, and every test above this comment -- unmodified by this
;; feature -- already proves that path's exact output), (2) supplying
;; one is genuinely CONSULTED, not silently ignored, and (3) font-weight/
;; font-style genuinely REACH the callback (not merely threaded through
;; the theme and dropped) -- the closed half of the gap flagged across
;; several earlier cycles this session and in ADR-2607061100.

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
  [text _font-size _font-weight _font-style _font-family]
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

(defn- fake-weight-aware-measure
  "A FAKE measure fn that ALSO widens its result for bold text --
   proves font-weight genuinely reaches the :measure-text callback (not
   merely threaded through the theme and dropped), closing the gap
   flagged across several earlier cycles this session and in
   ADR-2607061100: the callback's OLDER `(fn [text font-size]
   width-in-px)` 2-arg contract had no way to receive font-weight/
   font-style at all -- a fake fn declared with this 4-arg signature
   would throw an arity exception if layout-text still called it with
   only 2 args, which is exactly what happens if this fix is reverted."
  [text _font-size font-weight _font-style _font-family]
  (cond-> (* (count text) 8)
    (= "bold" font-weight) (* 2)))

(deftest measure-text-callback-genuinely-receives-font-weight
  ;; The identical string, at the identical available width: normal
  ;; weight measures narrow enough to wrap onto 2 lines, but the SAME
  ;; string in bold measures wide enough (per the fake's own doubling)
  ;; to need a 3rd line -- a real, different wrap OUTCOME driven purely
  ;; by font-weight reaching the measure callback, not by anything else
  ;; about the input changing.
  (let [text "aaaa bbbb cccc"
        measured-theme (assoc layout/default-theme :measure-text fake-weight-aware-measure)
        normal-inherited (assoc inherited-text :font-weight "normal")
        bold-inherited (assoc inherited-text :font-weight "bold")
        {normal-ops :draw} (layout/layout-node measured-theme 0 0 108 1.0 normal-inherited text)
        {bold-ops :draw} (layout/layout-node measured-theme 0 0 108 1.0 bold-inherited text)]
    (is (= 2 (count normal-ops)))
    (is (= 3 (count bold-ops))
        "bold text must measure wider and wrap onto an extra line, proving font-weight reached the fake measure fn")))

(defn- fake-family-aware-measure
  "A FAKE measure fn that ALSO widens its result for \"monospace\" --
   proves font-family genuinely reaches the :measure-text callback (not
   merely threaded through the theme and dropped), the same shape
   fake-weight-aware-measure above already proves for font-weight."
  [text _font-size _font-weight _font-style font-family]
  (cond-> (* (count text) 8)
    (= "monospace" font-family) (* 2)))

(deftest measure-text-callback-genuinely-receives-font-family
  ;; The identical string, at the identical available width: the default
  ;; font-family measures narrow enough to wrap onto 2 lines, but the
  ;; SAME string in "monospace" measures wide enough (per the fake's own
  ;; doubling) to need a 3rd line -- a real, different wrap OUTCOME driven
  ;; purely by font-family reaching the measure callback.
  (let [text "aaaa bbbb cccc"
        measured-theme (assoc layout/default-theme :measure-text fake-family-aware-measure)
        default-inherited (dissoc inherited-text :font-family)
        monospace-inherited (assoc inherited-text :font-family "monospace")
        {default-ops :draw} (layout/layout-node measured-theme 0 0 108 1.0 default-inherited text)
        {monospace-ops :draw} (layout/layout-node measured-theme 0 0 108 1.0 monospace-inherited text)]
    (is (= 2 (count default-ops)))
    (is (= 3 (count monospace-ops))
        "monospace text must measure wider and wrap onto an extra line, proving font-family reached the fake measure fn")))

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

(deftest grid-row-span-auto-places-the-item-and-occupies-both-rows
  ;; `grid-row: span 2` with no start line: the item stays AUTO-placed and
  ;; only its height is declared. Until this worked, it was the single
  ;; input in a 292-case differential corpus that made cssom.layout THROW
  ;; rather than answer -- `span-only?` checked only the column axis, so a
  ;; row span was taken for an explicit placement and its `{:span 2}` map
  ;; was destructured as a [start end] vector (`nth not supported on this
  ;; type`).
  ;;
  ;; The expected rects are Brave's, read off the conformance oracle for
  ;; `:grid/row-span-two`: a 70x60 item spanning both 30px rows, then the
  ;; two remaining items stacked in column 2.
  (let [tree (grid-tree {:grid-template-columns "70px 70px"
                          :grid-template-rows "30px 30px"
                          :gap 0 :padding 0 :width 140}
                         [[nil nil {:grid-row "span 2"}] [nil nil] [nil nil]])
        ops (layout/draw-ops tree {:width 140})
        [_ a b c] (node-ops ops)]
    (is (= {:x 0 :y 0 :w 70 :h 60} (select-keys a [:x :y :w :h]))
        "spans both row tracks")
    (is (= {:x 70 :y 0 :w 70 :h 30} (select-keys b [:x :y :w :h])))
    (is (= {:x 70 :y 30 :w 70 :h 30} (select-keys c [:x :y :w :h]))
        "the second auto item does NOT land under the spanning item")))

(deftest grid-row-span-does-not-overlap-an-occupied-cell
  ;; The whole rectangle has to be free, not just its first row: an item
  ;; spanning two rows must skip a slot whose lower row is already taken by
  ;; an explicitly placed item, or the two silently overlap.
  (let [tree (grid-tree {:grid-template-columns "40px 40px"
                          :grid-template-rows "20px 20px 20px"
                          :gap 0 :padding 0 :width 80}
                         [[nil nil {:grid-column 1 :grid-row 2}]
                          [nil nil {:grid-row "span 2"}]])
        ops (layout/draw-ops tree {:width 80})
        [_ explicit spanning] (node-ops ops)]
    (is (= {:x 0 :y 20} (select-keys explicit [:x :y])))
    (is (not= [0 0] [(:x spanning) (:y spanning)])
        "column 1 rows 0-1 is not free, because row 1 is taken")
    (is (= 40 (:h spanning)) "still spans two 20px rows wherever it lands")))

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
                          :border-style "solid" :border-width 2 :border-color "#112233"
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
    (is (= ["→ middle" "←"] (mapv :text text-ops))
        "generated ::before text is concatenated with the real child text
         it merged with, in document order; generated ::after text
         remains its own separate draw-op, in document order after that.
         Its leading space is gone from the op's own :text because the
         inline formatting context (layout-inline-run) now collapses the
         whitespace BETWEEN the two runs into the inter-piece gap on the
         shared line, exactly as real CSS does -- the space is still
         rendered, as horizontal distance rather than as a leading space
         character inside the second op")
    (is (= "red" (:color (first text-ops)))
        "the merged ::before+text run paints with ::before's own declared
         color, not the element's inherited black -- see
         merge-generated-with-text's documented single-color-per-merged-run
         simplification")
    (is (= "black" (:color (second text-ops)))
        "::after (unmerged here) still inherits the element's own color,
         exactly like any real child would, unaffected by the merge
         feature")
    (is (= (:y (first text-ops)) (:y (second text-ops)))
        "::after shares ONE line box with the merged ::before+text run
         (same :y, different :x) -- real CSS renders `→ middle ←` on one
         line, and since layout-inline-run landed (this file's general
         inline formatting context) so does this engine. This assertion
         previously required the opposite (::after stacked on its own row
         BELOW) and was correct only as a statement of the missing-inline-
         flow limitation it documented, not of real CSS")
    (is (< (:x (first text-ops)) (:x (second text-ops)))
        "document order on that shared line runs left to right: the
         merged ::before+text run first, then ::after after it")))

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
    (is (= ["X" "nested"] (mapv :text text-ops))
        "::before and the <span>'s own nested text stay two genuinely
         separate draw-ops -- no STRING merge fires when the immediately
         adjacent real child is an element, not a text node (each keeps
         its own style context, which is exactly why they must stay two
         ops). ::before's trailing space now lives in the inter-piece gap
         the inline formatting context computes, not in the op's :text")
    (is (= (:y (first text-ops)) (:y (second text-ops)))
        "the two ops share ONE line box (same :y) -- what used to be two
         stacked rows here was the missing-inline-flow limitation, closed
         by layout-inline-run; real CSS renders `X nested` on one line")
    (is (< (:x (first text-ops)) (:x (second text-ops)))
        "and in document order left to right on that line")))

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
    (is (= (:y (first text-ops)) (:y (second text-ops)))
        "the merged run and the <b> now share ONE line box -- the
         text-vs-element gap this test used to pin as permanently
         unmerged is exactly what layout-inline-run closed. They remain
         two separate draw-ops (the <b> carries its own style context),
         which is what an inline formatting context is FOR: same line,
         separate painted runs")
    (is (< (:x (first text-ops)) (:x (second text-ops)))
        "with the <b>'s run placed after the merged run on that line")))

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
    (is (apply = (mapv :y text-ops))
        "all three runs share ONE line box -- `a<b>x</b>b` is one line in
         real CSS, and is one line here since layout-inline-run landed.
         merge-adjacent-text-runs' own scope is unchanged by that: it
         still refuses to merge 'a' and 'b' into one STRING across the
         <b> boundary (they are not adjacent children), and inline flow
         is what puts the three separate runs on one line instead")
    (is (apply < (mapv :x text-ops))
        "laid out left to right in document order on that line")))

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

(deftest template-content-contributes-zero-draw-ops
  ;; The confirmed repro: a real <template> holding a row/row prototype for
  ;; later JS cloning (an extremely common modern pattern) previously
  ;; rendered its content exactly like an ordinary element -- confirmed via
  ;; direct REPL reproduction that a real, distinctive marker string inside
  ;; a <template> leaked straight into the real draw-ops.
  (let [[template doc] (dom/create-element dom/empty-document :template)
        doc (dom/set-root doc template)
        [li doc] (dom/create-element doc :li)
        doc (dom/set-attribute doc li :class "row")
        doc (dom/append-child doc template li)
        [text doc] (dom/create-text-node doc "DISTINCTIVE_TEMPLATE_MARKER_554433")
        doc (dom/append-child doc li text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (is (= [] ops)
        "template (and its li child, and the li's own real, non-empty
         text content) contribute zero draw-ops of any kind -- no rect,
         no text, no :node")))

(deftest template-excluded-real-sibling-still-renders-normally
  ;; Mirrors head-title-script-excluded-body-p-renders-normally's own
  ;; shape: a <template> row prototype sits right next to a real,
  ;; genuinely-rendered <li> in the SAME real <ul> -- only the template's
  ;; own content must be excluded, the real sibling must render exactly
  ;; as if the template weren't there.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [template doc] (dom/create-element doc :template)
        doc (dom/append-child doc ul template)
        [proto-li doc] (dom/create-element doc :li)
        doc (dom/append-child doc template proto-li)
        [proto-text doc] (dom/create-text-node doc "proto")
        doc (dom/append-child doc proto-li proto-text)
        [real-li doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul real-li)
        [real-text doc] (dom/create-text-node doc "real row")
        doc (dom/append-child doc real-li real-text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["• real row"] (mapv :text text-ops))
        "the template's own \"proto\" text never renders -- only the real
         sibling <li> does, with its normal implicit list-marker intact")))

(deftest template-with-no-children-is-a-safe-baseline
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [template doc] (dom/create-element doc :template)
        doc (dom/append-child doc root template)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc root p)
        [text doc] (dom/create-text-node doc "ok")
        doc (dom/append-child doc p text)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["ok"] (mapv :text text-ops)))))

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
    (is (= 152 (:w div-op))
        "width: calc(100px + 20px) resolves to a real 120px CONTENT width,
         and the box is 120 + 2x16 padding = 152px wide -- real CSS's
         default `box-sizing: content-box`, where a declared width is the
         content width and padding adds outside it. This assertion read 120
         until the conformance harness caught the engine treating a
         declared width as the border box (a card shape reported 300px wide
         with 268px of content where the browser reports 332 and 300); the
         calc() resolution this test exists for is unchanged")
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
    ;; 90% of the 480px containing block = 432, clamping the box up from its
    ;; declared 50px width.
    ;;
    ;; This assertion used to read 90, pinning the engine's old
    ;; approximation: parse-int's leading-digit run turned "90%" into 90
    ;; PIXELS. The test's subject is that a percentage does not CRASH
    ;; resolve-width, and that still holds; the number changed because
    ;; percentages now resolve against the containing block, which is what
    ;; the browser does (measured across nine corpus cases).
    (is (= 432 (:w div-op)))))

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
    ;; 20% of the 480px containing block = 96, clamping the box down from
    ;; its declared 500px width. Was 20, for the same reason as the
    ;; min-width test above.
    (is (= 96 (:w div-op)))))

;; ---- min-height/max-height (previously entirely unimplemented -- unlike
;;      min-width/max-width, which resolve-width already clamped, height
;;      had zero min/max handling anywhere in this file) ----

(defn- min-max-height-box
  [rules-str]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div)
        doc (dom/set-attribute doc div :class "box")
        [text doc] (dom/create-text-node doc "x")
        doc (dom/append-child doc div text)
        rules (css/parse-rules rules-str)
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)))

(deftest min-height-applies-to-a-content-driven-auto-height-box
  ;; Real CSS: min-height applies regardless of whether the height came
  ;; from an explicit declaration or content-driven auto-sizing -- a very
  ;; common pattern (reserving space for an otherwise-empty/loading card).
  (is (= 200 (:h (min-max-height-box ".box { min-height: 200 }")))))

(deftest max-height-clamps-an-explicit-height
  (is (= 10 (:h (min-max-height-box ".box { height: 100; max-height: 10 }")))))

(deftest min-height-below-an-already-larger-explicit-height-is-a-no-op
  (is (= 100 (:h (min-max-height-box ".box { height: 100; min-height: 10 }")))))

(deftest min-height-and-max-height-together-both-real-constraints
  (is (= 50 (:h (min-max-height-box ".box { min-height: 20; max-height: 50; height: 200 }")))))

(deftest no-min-max-height-declared-is-an-unaffected-baseline
  (is (= 36 (:h (min-max-height-box "")))))

(deftest malformed-min-height-value-degrades-to-a-no-op-not-a-crash
  (is (= 36 (:h (min-max-height-box ".box { min-height: bogus }")))))

(deftest min-height-applies-to-a-flex-box-too
  (is (= 150 (:h (min-max-height-box ".box { display: flex; min-height: 150 }")))))

(deftest min-height-applies-to-a-grid-box-too
  (is (= 150 (:h (min-max-height-box ".box { display: grid; min-height: 150 }")))))

(deftest min-height-applies-to-a-form-control-too
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [input doc] (dom/create-element doc :input)
        doc (dom/append-child doc root input)
        doc (dom/set-attribute doc input :class "field")
        rules (css/parse-rules ".field { min-height: 80 }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        input-op (some #(and (= :node (:draw/op %)) (= :input (:tag %)) %) ops)]
    (is (= 80 (:h input-op)))))

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
    ;; 10% of the containing block's width. The containing block is now the
    ;; ancestor's PADDING box (480 wide, origin 0, since :main has no
    ;; border), so 48 -- not the content box's 472 offset by its 4px
    ;; padding, and certainly not the 10px this engine used to read out of
    ;; the string "10%".
    ;;
    ;; The numbers here have moved twice for two separate reasons, both
    ;; recorded rather than silently re-pinned: percentages stopped being
    ;; read as pixels, then the containing block stopped being the content
    ;; box. The test's subject -- that a percentage offset does not crash
    ;; -- is unchanged by either.
    (is (= 48 (:x div-op)))
    ;; 5% of the containing block's HEIGHT, which is 8 here: :main holds
    ;; only its own 4px padding top and bottom, because its single child is
    ;; out of flow. 5% of 8 rounds to 0.
    ;;
    ;; KNOWN SIMPLIFICATION, worth stating where it is visible: a real
    ;; browser resolves this box against the INITIAL containing block (the
    ;; viewport), because none of its ancestors is positioned -- so it would
    ;; use 5% of the viewport height, not of :main. This engine uses the
    ;; nearest ancestor either way. The conformance corpus's own
    ;; `:position/*` cases all give the abs box a positioned ancestor, so
    ;; nothing there distinguishes the two; this unit test is the only place
    ;; the difference is currently visible.
    (is (= 0 (:y div-op)))))

;; ---- position:absolute right/bottom (layout-absolute-children previously
;;      read ONLY left/top; right/bottom -- extremely common for
;;      corner-pinned badges, close buttons, overlays -- were silently
;;      ignored and the child always landed at left:0;top:0) ----

(defn- absolute-child-op
  "Shared harness: a :main root with an explicit width/height/zero-padding
   container (clean, hand-computable numbers) and a single position:absolute
   :div child styled by `box-style`; returns the child's real :node draw-op."
  [box-style]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        doc (dom/set-attribute doc root :class "container")
        [div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules
               (str ".container { width: 200; height: 200; padding: 0 } "
                    ".box { position: absolute; " box-style " }"))
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)))

(deftest absolute-left-and-right-together-size-the-box
  ;; With `width: auto`, setting BOTH left and right is not over-constrained
  ;; -- real CSS solves the equation by giving the box whatever is left of
  ;; the containing block. Measured in Brave: `left:20;right:20` inside a
  ;; 300px box is 260 wide there; this engine shrink-wrapped it around its
  ;; text and reported 63.
  (let [div-op (absolute-child-op "left: 20; right: 20; height: 20")]
    (is (= 20 (:x div-op)))
    (is (= 160 (:w div-op)) "200 container - 20 left - 20 right")))

(deftest absolute-top-and-bottom-together-size-the-box
  ;; The block-axis counterpart. A box's height normally comes from its
  ;; content, so the resolved height is written onto the child as its USED
  ;; value -- which is exactly what CSS says it is.
  (let [div-op (absolute-child-op "top: 30; bottom: 50; width: 40")]
    (is (= 30 (:y div-op)))
    (is (= 120 (:h div-op)) "200 container - 30 top - 50 bottom")))

(deftest absolute-child-with-no-offset-keeps-its-static-position
  ;; The containing block for an absolute child is the ancestor's PADDING
  ;; box -- but only for an axis that actually HAS an offset. With no offset
  ;; the box stays where it would have been in flow: its STATIC POSITION.
  ;;
  ;; Conflating the two is a real regression, not a hypothetical: making the
  ;; padding box the origin on every axis moved every offsetless absolute
  ;; and fixed box by the ancestor's padding.
  ;;
  ;; The static position is now computed by the flow itself (see
  ;; layout-children-block's out-of-flow branch) rather than approximated
  ;; by the ancestor's content origin; for THIS shape -- a lone first
  ;; child with no margin of its own -- the two answers coincide, which is
  ;; why the numbers below are unchanged. The tests right below vary the
  ;; two inputs that make them differ (a preceding sibling, and a margin).
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules ".box { position: absolute; width: 40; height: 10 }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)]
    ;; :main's own default 4px padding: the static position, not 0.
    (is (= 4 (:x div-op)))
    (is (= 4 (:y div-op)))))

;; ---- the static position is the FLOW's answer, not the container's
;;      origin (an offsetless absolute box used to drop to the containing
;;      block's content corner, wherever the flow had actually reached) ----

(defn- static-position-op
  "Shared harness: a :main root holding an optional in-flow :div sibling
   FIRST (styled by `before-style`, nil for none) and then a
   position:absolute :div (styled by `box-style`); returns the absolute
   box's own :node draw-op.

   :main's own default 4px padding is the content origin, so every
   expected number below starts from 4 rather than 0."
  [before-style box-style]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [doc] (if before-style
                (let [[before doc] (dom/create-element doc :div)
                      doc (dom/append-child doc root before)]
                  [(dom/set-attribute doc before :class "before")])
                [doc])
        [div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules
               (str (when before-style (str ".before { " before-style " } "))
                    ".box { position: absolute; width: 40; height: 10; " box-style " }"))
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 480})]
    (some #(and (= :node (:draw/op %)) (= :div (:tag %)) (= "box" (:class %)) %) ops)))

(deftest static-position-follows-the-preceding-siblings-flow
  ;; Measured in Brave: `<p>flow</p><span style="position:absolute;
  ;; left:40px">abs</span>` puts the span at y=34 -- the paragraph's
  ;; bottom edge plus its own 14px bottom margin -- where this engine put
  ;; it at y=0, the containing block's corner.
  ;;
  ;; Here: content origin 4 + the sibling's 20px height + this engine's
  ;; own 4px inter-row theme gap = 28.
  (let [div-op (static-position-op "height: 20" "")]
    (is (= 4 (:x div-op)))
    (is (= 28 (:y div-op)))))

(deftest static-position-adds-the-preceding-siblings-bottom-margin
  ;; 4 + 20 (sibling) + 4 (theme gap) + 10 (the sibling's bottom margin,
  ;; which is where the next in-flow box would start too).
  (let [div-op (static-position-op "height: 20; margin-bottom: 10" "")]
    (is (= 38 (:y div-op)))))

(deftest static-position-adds-the-boxs-own-top-margin-without-collapsing-it
  ;; The one rule here that could not be guessed, so it was measured in
  ;; Brave directly: the out-of-flow box's own top margin is added to the
  ;; flow position AND does not collapse with the preceding sibling's
  ;; bottom margin. `margin-bottom: 10` then `margin-top: 30` reports
  ;; y=60 there (20 + 10 + 30), and `margin-bottom: 30` then
  ;; `margin-top: 10` reports the same 60 (20 + 30 + 10) -- collapsing
  ;; would have made the first 80 and the two differ.
  ;;
  ;; Both numbers below are that sum plus this engine's content origin (4)
  ;; and its own inter-row theme gap (4).
  (is (= 68 (:y (static-position-op "height: 20; margin-bottom: 10"
                                    "margin-top: 30"))))
  (is (= 68 (:y (static-position-op "height: 20; margin-bottom: 30"
                                    "margin-top: 10")))))

(deftest static-position-of-a-first-child-keeps-its-own-top-margin
  ;; Measured: `<div><p style="position:absolute">abs</p><p>after</p></div>`
  ;; puts the absolute paragraph at y=14 (its own margin) while the
  ;; in-flow one -- whose identical margin DOES collapse out through the
  ;; container's top edge -- sits at y=0. An out-of-flow box takes no part
  ;; in margin collapsing at all, so nothing collapses its margin away.
  (is (= 16 (:y (static-position-op nil "margin-top: 12")))))

(deftest static-position-includes-the-boxs-own-left-margin
  ;; Measured: `<span style="position:absolute;margin-left:25px">` sits at
  ;; x=25, not at the container's content edge.
  (is (= 29 (:x (static-position-op nil "margin-left: 25")))))

(deftest an-offset-axis-still-wins-over-the-static-position
  ;; The static position is the fallback for an `auto` offset, not a new
  ;; origin: `left` still resolves against the containing block's padding
  ;; box while `top: auto` keeps the flow's answer, and the two axes are
  ;; decided independently.
  (let [div-op (static-position-op "height: 20" "left: 40")]
    (is (= 40 (:x div-op)) "left: 40 against :main's padding box, origin 0")
    (is (= 28 (:y div-op)) "top: auto -- still the static position")))

(deftest an-out-of-flow-child-does-not-split-an-inline-run
  ;; The out-of-flow box now travels through inline-runs' own grouping
  ;; (that is how it learns its static position), and a box that takes no
  ;; part in a line box must not SPLIT one either -- the same rule
  ;; float-child? already has. `text <span absolute>x</span> more` is one
  ;; line in every browser; grouping around the absolute span would make
  ;; `text` and `more` two one-child runs and stack them.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [t1 doc] (dom/create-text-node doc "text ")
        doc (dom/append-child doc p t1)
        [span doc] (dom/create-element doc :span)
        doc (dom/append-child doc p span)
        doc (dom/set-attribute doc span :class "box")
        [st doc] (dom/create-text-node doc "x")
        doc (dom/append-child doc span st)
        [t2 doc] (dom/create-text-node doc " more")
        doc (dom/append-child doc p t2)
        rules (css/parse-rules ".box { position: absolute }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 480})
        p-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %) ops)
        flow-text (->> ops
                       (filterv #(= :text (:draw/op %)))
                       (remove #(= "x" (:text %)))
                       (mapv :y))
        ;; Reference: the same <p> with the absolute span deleted rather
        ;; than taken out of flow -- one genuine, unambiguous line. Written
        ;; as a comparison rather than a pinned number because the height
        ;; of a line box is the theme's business (padding/gap), and this
        ;; test is about how many lines there are.
        [p2 doc2] (dom/create-element dom/empty-document :p)
        doc2 (dom/set-root doc2 p2)
        [r1 doc2] (dom/create-text-node doc2 "text ")
        doc2 (dom/append-child doc2 p2 r1)
        [r2 doc2] (dom/create-text-node doc2 " more")
        doc2 (dom/append-child doc2 p2 r2)
        [_ doc2] (dom/consume-ops doc2)
        ref-op (some #(and (= :node (:draw/op %)) (= :p (:tag %)) %)
                     (layout/draw-ops (dom/tree doc2) {:width 480}))]
    (is (= 1 (count (distinct flow-text)))
        "the text on either side of the absolute span shares ONE line box")
    (is (= (:h ref-op) (:h p-op))
        "the paragraph is exactly as tall as the same paragraph without
         the absolute span in it -- one line, not two one-child rows with
         the span's own row between them")))

(deftest absolute-child-right-anchors-to-container-right-edge
  ;; 200 (container content-w) - 40 (child width) - 10 (right) = 150.
  (let [div-op (absolute-child-op "right: 10; width: 40; height: 20")]
    (is (= 150 (:x div-op)))
    (is (= 0 (:y div-op)))))

(deftest absolute-child-bottom-anchors-to-container-bottom-edge
  ;; 200 (container content-h) - 20 (child height) - 5 (bottom) = 175.
  (let [div-op (absolute-child-op "bottom: 5; width: 40; height: 20")]
    (is (= 0 (:x div-op)))
    (is (= 175 (:y div-op)))))

(deftest absolute-child-right-and-bottom-together
  (let [div-op (absolute-child-op "right: 10; bottom: 5; width: 40; height: 20")]
    (is (= 150 (:x div-op)))
    (is (= 175 (:y div-op)))))

(deftest absolute-child-left-wins-over-right-when-both-present
  ;; This engine's width resolution is already decided before this
  ;; placement step runs (no "stretch to fill left+right" auto-width
  ;; solving -- a deliberate, documented scope cut), so left simply wins
  ;; and right is ignored, matching layout-absolute-children's docstring.
  (let [div-op (absolute-child-op "left: 15; right: 10; width: 40; height: 20")]
    (is (= 15 (:x div-op)))))

(deftest absolute-child-top-wins-over-bottom-when-both-present
  (let [div-op (absolute-child-op "top: 8; bottom: 5; width: 40; height: 20")]
    (is (= 8 (:y div-op)))))

(deftest absolute-child-malformed-right-degrades-to-default-no-crash
  ;; Mirrors this file's existing degrade-don't-guess convention: a
  ;; non-numeric right/bottom (here "auto") is simply not enforced,
  ;; falling through to the pre-existing left:0;top:0-equivalent default.
  (let [div-op (absolute-child-op "right: auto; width: 40; height: 20")]
    (is (= 0 (:x div-op)))))

;; ---- position:fixed (previously NOT recognized by `absolute?` at all --
;;      a position:fixed child fell all the way through to being treated
;;      like position:static: it stayed in normal flow and, unlike real
;;      CSS, still occupied layout space and pushed its following siblings
;;      down. Routed through the SAME partition-flow/layout-absolute-
;;      children machinery position:absolute already uses -- see
;;      absolute?'s own docstring for the honest containing-block-not-
;;      real-viewport scope cut this implies.) ----

(deftest fixed-child-does-not-push-its-following-sibling-down
  ;; Real bug this guards, confirmed via direct REPL reproduction before
  ;; touching source: the second, static sibling landed at y=28 (pushed
  ;; down by the first, fixed child's own height) instead of y=4.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [fixed-el doc] (dom/create-element doc :div)
        doc (dom/append-child doc root fixed-el)
        doc (dom/set-attribute doc fixed-el :class "fixed-box")
        [static-el doc] (dom/create-element doc :div)
        doc (dom/append-child doc root static-el)
        doc (dom/set-attribute doc static-el :class "static-box")
        rules (css/parse-rules
               ".fixed-box { position: fixed; width: 50px; height: 20px }
                .static-box { width: 50px; height: 20px }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        node-ops (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops)]
    (is (= 2 (count node-ops)))
    (is (every? #(= 4 (:y %)) node-ops)
        "both the fixed child and its sibling must land at the SAME y --
         the fixed child pulled out of flow, exactly like position:absolute
         already does, not pushing the sibling down")))

(deftest fixed-child-anchors-to-right-and-bottom-like-absolute-does
  (let [div-op (absolute-child-op "position: fixed; right: 10; bottom: 5; width: 40; height: 20")]
    (is (= 150 (:x div-op)))
    (is (= 175 (:y div-op)))))

(deftest fixed-child-honors-top-and-left-like-absolute-does
  (let [div-op (absolute-child-op "position: fixed; top: 8; left: 15; width: 40; height: 20")]
    (is (= 15 (:x div-op)))
    (is (= 8 (:y div-op)))))

(deftest sticky-child-still-stays-in-normal-flow-unlike-fixed
  ;; Regression guard: this fix must NOT accidentally sweep position:sticky
  ;; into the same out-of-flow treatment -- an unscrolled sticky element's
  ;; correct position IS normal flow, so it should still push its
  ;; following sibling down exactly like position:static would.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [sticky-el doc] (dom/create-element doc :div)
        doc (dom/append-child doc root sticky-el)
        doc (dom/set-attribute doc sticky-el :class "sticky-box")
        [static-el doc] (dom/create-element doc :div)
        doc (dom/append-child doc root static-el)
        doc (dom/set-attribute doc static-el :class "static-box")
        rules (css/parse-rules
               ".sticky-box { position: sticky; width: 50px; height: 20px }
                .static-box { width: 50px; height: 20px }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        node-ops (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops)]
    (is (= [4 28] (mapv :y node-ops)))))

;; ---- negative z-index on a positioned child must paint BEHIND its
;;      stacking context's own in-flow content (layout-absolute-children
;;      previously spliced its ENTIRE output -- regardless of z sign --
;;      after in-flow content, so a z-index:-1 element always painted on
;;      TOP of everything, backwards from real CSS stacking order) ----

(defn- z-index-stacking-colors
  "Shared harness: a position:relative container with an in-flow sibling
   and an absolutely-positioned, fully-overlapping child styled by
   `abs-extra-style` (must include its own z-index); returns the real
   paint-order colors (background, sibling, positioned child)."
  [abs-extra-style]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        doc (dom/set-attribute doc root :class "container")
        [sibling doc] (dom/create-element doc :div)
        doc (dom/append-child doc root sibling)
        doc (dom/set-attribute doc sibling :class "sibling")
        [abs-el doc] (dom/create-element doc :div)
        doc (dom/append-child doc root abs-el)
        doc (dom/set-attribute doc abs-el :class "positioned")
        rules (css/parse-rules
               (str ".container { position: relative; width: 200; height: 100; padding: 0; background: blue } "
                    ".sibling { width: 200; height: 100; background: green } "
                    ".positioned { position: absolute; top: 0; left: 0; width: 200; height: 100; background: red; "
                    abs-extra-style " }"))
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (mapv :color (filterv #(= :rect (:draw/op %)) ops))))

(deftest negative-z-index-positioned-child-paints-behind-in-flow-content
  ;; Real bug this guards, confirmed via direct REPL reproduction before
  ;; touching source: a z-index:-1 absolutely positioned red box painted
  ;; LAST (topmost) over the in-flow green sibling instead of behind it.
  (is (= ["blue" "red" "green"] (z-index-stacking-colors "z-index: -1"))))

(deftest positive-z-index-positioned-child-still-paints-on-top
  ;; Regression guard: this fix must not flip the already-correct
  ;; positive-z-index case.
  (is (= ["blue" "green" "red"] (z-index-stacking-colors "z-index: 1"))))

(deftest zero-z-index-positioned-child-still-paints-on-top
  (is (= ["blue" "green" "red"] (z-index-stacking-colors "z-index: 0"))))

(deftest absent-z-index-positioned-child-still-paints-on-top
  ;; Regression guard: node-style's own z-index default (0) must still
  ;; resolve to the "paints on top" group when no z-index is declared at
  ;; all, not just when it's explicitly written as 0.
  (is (= ["blue" "green" "red"] (z-index-stacking-colors ""))))

;; ---- position:relative top/left/right/bottom (layout-children-block
;;      previously read NEITHER at all -- position:relative had
;;      byte-identical layout to position:static/unset, even though this
;;      namespace's own docstring already claimed "position:relative/
;;      absolute with z-index stacking" was covered) ----

(defn- relative-sibling-rects
  "Shared harness: a real block-flow parent with two direct :div children,
   the first styled by `first-child-style` (typically position:relative
   plus an offset), the second a plain, unstyled sibling used to prove
   relative positioning shifts painting only, never layout. Returns
   `[first-rect second-rect]` -- each child's own real background :rect
   draw-op."
  [first-child-style]
  (let [[parent doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc parent)
        [a doc] (dom/create-element doc :div)
        doc (dom/set-style doc a (merge {:background "#ff0000" :width 50 :height 50} first-child-style))
        doc (dom/append-child doc parent a)
        [b doc] (dom/create-element doc :div)
        doc (dom/set-style doc b {:background "#00ff00" :width 50 :height 50})
        doc (dom/append-child doc parent b)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 300})
        rect-for-color (fn [color] (some #(and (= :rect (:draw/op %)) (= color (:color %)) %) ops))]
    [(rect-for-color "#ff0000") (rect-for-color "#00ff00")]))

(deftest relative-top-and-left-shift-the-painted-position
  (let [[a-before _] (relative-sibling-rects {})
        [a-after _] (relative-sibling-rects {:position "relative" :top 10 :left 20})]
    (is (= (+ 20 (:x a-before)) (:x a-after)))
    (is (= (+ 10 (:y a-before)) (:y a-after)))))

(deftest relative-bottom-and-right-shift-the-opposite-physical-direction
  (let [[a-before _] (relative-sibling-rects {})
        [a-after _] (relative-sibling-rects {:position "relative" :bottom 5 :right 8})]
    (is (= (- (:x a-before) 8) (:x a-after))
        "a positive right pulls the box LEFT, not right")
    (is (= (- (:y a-before) 5) (:y a-after))
        "a positive bottom pulls the box UP, not down")))

(deftest relative-left-wins-over-right-when-both-present
  (let [[a-before _] (relative-sibling-rects {})
        [a-after _] (relative-sibling-rects {:position "relative" :left 15 :right 999})]
    (is (= (+ 15 (:x a-before)) (:x a-after)))))

(deftest relative-top-wins-over-bottom-when-both-present
  (let [[a-before _] (relative-sibling-rects {})
        [a-after _] (relative-sibling-rects {:position "relative" :top 8 :bottom 999})]
    (is (= (+ 8 (:y a-before)) (:y a-after)))))

(deftest relative-positioning-does-not-disturb-a-following-siblings-layout
  ;; The real CSS rule this fix must honor exactly: position:relative
  ;; shifts PAINTING only -- a later sibling must stack as if the
  ;; relatively positioned box were still at its own normal, unshifted
  ;; position.
  (let [[_ b-static] (relative-sibling-rects {})
        [a-shifted b-after-shift] (relative-sibling-rects {:position "relative" :top 50 :left 30})]
    (is (= (+ 30 (:x b-static)) (:x a-shifted)) "sanity check: a itself really did shift")
    (is (= b-static b-after-shift)
        "b's own position must be byte-identical whether or not a was shifted -- a real, common bug shape where painting-only offsets leak into sibling layout math")))

(deftest no-position-declared-is-an-unaffected-baseline
  (let [[a-static _] (relative-sibling-rects {})]
    (is (= 4 (:x a-static)))
    (is (= 4 (:y a-static)))))

(deftest position-absolute-is-not-shifted-by-this-relative-offset-logic
  ;; Confirms the new relative-offset translate is correctly gated on
  ;; position:relative specifically -- an absolutely positioned child (a
  ;; wholly separate placement mechanism, see layout-absolute-children)
  ;; must not ALSO receive this block-flow relative-offset treatment.
  (let [div-op (absolute-child-op "left: 15; top: 8; width: 40; height: 20")]
    (is (= 15 (:x div-op)))
    (is (= 8 (:y div-op)))))

;; ---- overflow:hidden/auto clipping (node-style previously read :overflow
;;      via the raw, non-namespaced DOM attribute -- which nothing in real
;;      HTML/CSS ever sets, since overflow is exclusively a CSS property --
;;      instead of the cascade-resolved :style/overflow every other
;;      property here reads. A stylesheet-authored `overflow: hidden` (the
;;      overwhelmingly common way authors set it) silently never clipped.
;;      ----

(defn- overflow-container-clip-ops
  [overflow-decl]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-style doc div (merge {:width 100 :height 40} overflow-decl))
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})]
    (filter #(= :clip (:draw/op %)) ops)))

(deftest overflow-hidden-produces-a-clip-push-and-pop-pair
  (let [clip-ops (overflow-container-clip-ops {:overflow "hidden"})]
    (is (= [:push :pop] (mapv :clip/op clip-ops)))
    (is (every? #(= 100 (:w %)) clip-ops))
    (is (every? #(= 40 (:h %)) clip-ops))))

(deftest overflow-auto-also-clips-same-as-hidden
  (is (= [:push :pop] (mapv :clip/op (overflow-container-clip-ops {:overflow "auto"})))))

(deftest overflow-visible-does-not-clip
  (is (empty? (overflow-container-clip-ops {:overflow "visible"}))))

(deftest no-overflow-declared-at-all-does-not-clip
  (is (empty? (overflow-container-clip-ops {}))))

(deftest overflow-hidden-through-the-real-cascade-still-clips
  ;; Unlike the direct dom/set-style tests above, this runs the real
  ;; end-to-end pipeline (CSS text -> parse-rules + apply-cascade ->
  ;; kotoba.wasm.dom tree -> draw-ops) to prove the fix holds for the
  ;; actual authoring path, not just an already-":style/"-namespaced attr.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules "div { width: 100px; height: 40px; overflow: hidden; }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})
        clip-ops (filter #(= :clip (:draw/op %)) ops)]
    (is (= [:push :pop] (mapv :clip/op clip-ops)))))

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

(deftest li-value-attribute-overrides-that-items-number-and-shifts-later-siblings
  ;; Real, common HTML: an <li value=N> overrides that one item's own
  ;; displayed number directly, and every LATER sibling with no value=
  ;; of its own continues counting from value+1, not from the position
  ;; it would otherwise have occupied -- real HTML5 semantics, distinct
  ;; from start= (which only offsets the very first item).
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "First")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        doc (dom/set-attribute doc li2 :value "5")
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
    (is (= ["1. First" "5. Second" "6. Third"] (mapv :text text-ops))
        "the second item's value=5 overrides its own number, and the third
         item correctly continues from 6, not from its original position 3")))

(deftest li-value-and-ol-start-are-independent-mechanisms
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        doc (dom/set-attribute doc ol :start "10")
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "A")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        doc (dom/set-attribute doc li2 :value "1")
        [t2 doc] (dom/create-text-node doc "B")
        doc (dom/append-child doc li2 t2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li3)
        [t3 doc] (dom/create-text-node doc "C")
        doc (dom/append-child doc li3 t3)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["10. A" "1. B" "2. C"] (mapv :text text-ops))
        "start=10 sets the first item's number, but the second item's own
         value=1 completely overrides it regardless, and the third item
         continues from THAT (2), not from start's own number space")))

(deftest li-negative-value-is-real-legal-html5-not-clamped
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "A")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        doc (dom/set-attribute doc li2 :value "-2")
        [t2 doc] (dom/create-text-node doc "B")
        doc (dom/append-child doc li2 t2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li3)
        [t3 doc] (dom/create-text-node doc "C")
        doc (dom/append-child doc li3 t3)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. A" "-2. B" "-1. C"] (mapv :text text-ops)))))

(deftest li-malformed-value-falls-back-to-plain-continuation-not-a-crash
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "A")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        doc (dom/set-attribute doc li2 :value "not-a-number")
        [t2 doc] (dom/create-text-node doc "B")
        doc (dom/append-child doc li2 t2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li3)
        [t3 doc] (dom/create-text-node doc "C")
        doc (dom/append-child doc li3 t3)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. A" "2. B" "3. C"] (mapv :text text-ops))
        "a malformed value= behaves exactly as if it were absent -- a
         graceful fallback to plain +1 continuation, not a crash")))

(deftest li-value-on-ul-has-no-numbering-effect
  ;; <ul> markers are always a bare bullet -- value= is an :ol-only HTML5
  ;; attribute (the HTML spec doesn't even define it for <ul>), so it
  ;; must have zero effect here, matching implicit-marker-content's own
  ;; :ul branch never consulting `number` at all.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [t1 doc] (dom/create-text-node doc "First")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        doc (dom/set-attribute doc li2 :value "5")
        [t2 doc] (dom/create-text-node doc "Second")
        doc (dom/append-child doc li2 t2)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["• First" "• Second"] (mapv :text text-ops)))))

(deftest li-value-on-a-suppressed-li-still-shifts-later-siblings
  ;; CSS list-style:none only hides that ONE marker BOX -- it must not
  ;; remove the <li> from HTML5's own value=/position counting, matching
  ;; how a suppressed <li>'s plain POSITION already keeps counting today.
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [t1 doc] (dom/create-text-node doc "A")
        doc (dom/append-child doc li1 t1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        doc (dom/set-attribute doc li2 :value "5")
        doc (dom/set-style doc li2 {:list-style "none"})
        [t2 doc] (dom/create-text-node doc "B")
        doc (dom/append-child doc li2 t2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li3)
        [t3 doc] (dom/create-text-node doc "C")
        doc (dom/append-child doc li3 t3)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-ops (filterv #(= :text (:draw/op %)) ops)]
    (is (= ["1. A" "B" "6. C"] (mapv :text text-ops))
        "the suppressed second item shows no marker at all, but its own
         value=5 still shifts the third item's number to 6")))

(defn- reversed-ol-markers
  "Builds a real <ol> with `ol-attrs` and one <li> per `[text li-attrs]`
   pair in `li-specs`, then returns the real merged marker+text draw-op
   strings, in document order -- a small helper mirroring the shape of
   this file's own repeated ol/li-construction tests above, needed here
   since ol-reversed has more scenario combinations to cover than the
   earlier start=/value= tests did."
  [ol-attrs li-specs]
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        doc (reduce (fn [doc [k v]] (dom/set-attribute doc ol k v)) doc ol-attrs)
        doc (reduce (fn [doc [text attrs]]
                      (let [[li doc] (dom/create-element doc :li)
                            doc (dom/append-child doc ol li)
                            doc (reduce (fn [doc [k v]] (dom/set-attribute doc li k v)) doc attrs)
                            [t doc] (dom/create-text-node doc text)
                            doc (dom/append-child doc li t)]
                        doc))
                    doc li-specs)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (mapv :text (filterv #(= :text (:draw/op %)) ops))))

(deftest ol-reversed-with-no-start-defaults-to-li-count-and-counts-down
  ;; Real HTML5/browser semantics: reversed with no explicit start=
  ;; defaults start to the TOTAL COUNT of direct <li> children, then
  ;; counts down -- a 3-item reversed list numbers 3, 2, 1, not 1, 2, 3.
  (is (= ["3. a" "2. b" "1. c"]
         (reversed-ol-markers {:reversed true} [["a" {}] ["b" {}] ["c" {}]]))))

(deftest ol-reversed-with-explicit-start-counts-down-from-it
  (is (= ["10. a" "9. b"]
         (reversed-ol-markers {:reversed true :start "10"} [["a" {}] ["b" {}]]))))

(deftest ol-reversed-recognizes-the-real-xhtml-explicit-form
  ;; reversed="reversed" must be recognized identically to a bare
  ;; reversed -- the same truthy-attr? real-boolean-attribute check
  ;; already established for checked/open, reused here rather than a
  ;; naive (true? ...) that only recognizes htmldom's own bare-attribute
  ;; sentinel value.
  (is (= ["3. a" "2. b" "1. c"]
         (reversed-ol-markers {:reversed "reversed"} [["a" {}] ["b" {}] ["c" {}]]))))

(deftest ol-reversed-composes-with-a-mid-list-value-override
  ;; value= always wins regardless of direction, and a later plain <li>
  ;; continues counting DOWN from that override, not back to the
  ;; original reversed sequence.
  (is (= ["3. a" "20. b" "19. c"]
         (reversed-ol-markers {:reversed true} [["a" {}] ["b" {:value "20"}] ["c" {}]]))))

(deftest ol-without-reversed-is-unaffected-baseline
  (is (= ["1. a" "2. b" "3. c"]
         (reversed-ol-markers {} [["a" {}] ["b" {}] ["c" {}]]))))

(deftest ol-reversed-with-malformed-start-falls-back-to-li-count-not-one
  ;; A malformed start= on a REVERSED list must fall back to the
  ;; reversed-aware default (li-count), not silently revert to the
  ;; ordinary default of 1 -- a graceful, direction-aware fallback.
  (is (= ["3. a" "2. b" "1. c"]
         (reversed-ol-markers {:reversed true :start "nope"} [["a" {}] ["b" {}] ["c" {}]]))))

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
        doc (dom/set-style doc input {:border-style "solid" :border-width 2 :border-color "#112233"
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
    (is (= 4 (count border-rects))
        "an <input> paints the 2px border its UA stylesheet gives it -- one
         rect per edge. This asserted ZERO borders while this engine gave
         controls no UA box at all; a browser draws one, and the control
         was 8px narrower than the browser's for the same reason")))

(deftest select-and-textarea-also-get-border-and-background-painting
  (doseq [tag [:select :textarea]]
    (let [[el doc] (dom/create-element dom/empty-document tag)
          doc (dom/set-root doc el)
          doc (dom/set-style doc el {:border-style "solid" :border-width 1 :border-color "#000000" :background "#ffffff"})
          [_ doc] (dom/consume-ops doc)
          tree (dom/tree doc)
          ops (layout/draw-ops tree {:width 100})
          border-rects (filterv #(and (= :rect (:draw/op %)) (:border? %)) ops)
          bg-rect (first (filter #(and (= :rect (:draw/op %)) (not (:border? %))) ops))]
      (is (= 4 (count border-rects)) (str tag " must get border rects too, not just <input>"))
      (is (= "#ffffff" (:color bg-rect)) (str tag " must get a background rect too")))))

;; ---- <input>/<textarea> placeholder rendering ----

(defn- control-text-op-for
  [attrs]
  (let [[el doc] (dom/create-element dom/empty-document (:tag attrs :input))
        doc (dom/set-root doc el)
        doc (reduce-kv (fn [doc k v] (dom/set-attribute doc el k v))
                       doc
                       (dissoc attrs :tag))
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 200})]
    (first (filter #(= :text (:draw/op %)) ops))))

(deftest input-with-empty-value-shows-its-placeholder-attribute
  ;; The confirmed repro from the bug report: placeholder was read
  ;; NOWHERE at all -- an empty-valued <input>/<textarea> (the
  ;; overwhelmingly common real case, an unfocused control a user hasn't
  ;; typed into yet) painted as a totally silent, empty box no matter
  ;; what a real page declared.
  (is (= "Search..." (:text (control-text-op-for {:placeholder "Search..."})))))

(deftest input-with-a-real-value-shows-the-value-not-the-placeholder
  (is (= "hello" (:text (control-text-op-for {:value "hello" :placeholder "Search..."})))))

(deftest placeholder-text-uses-a-dim-ua-default-color-distinct-from-a-real-values-color
  (is (= "#767676" (:color (control-text-op-for {:placeholder "Search..."}))))
  (is (not (contains? (control-text-op-for {:value "hello"}) :color))
      "a real value must NOT get the placeholder's dim color -- byte-for-byte the same as before this feature existed"))

(deftest checkbox-placeholder-attribute-is-ignored-shows-its-checked-state-glyph-instead
  (is (= "[ ]" (:text (control-text-op-for {:type "checkbox" :placeholder "ignored"}))))
  (is (= "[x]" (:text (control-text-op-for {:type "checkbox" :checked "true" :placeholder "ignored"})))))

(deftest textarea-with-empty-value-shows-its-placeholder-attribute
  (is (= "Type here" (:text (control-text-op-for {:tag :textarea :placeholder "Type here"})))))

(deftest input-with-no-value-and-no-placeholder-produces-no-text-op-at-all
  ;; Exact backward compatibility: unchanged from before this feature
  ;; existed.
  (is (nil? (control-text-op-for {}))))

;; ---- <input> caret/selection painting ----

(defn- fake-char-measure
  [text font-size _weight _style _family]
  (* (count text) 7))

(defn- input-ops
  [{:keys [value start end theme tag placeholder] :or {tag :input}}]
  (let [[input doc] (dom/create-element dom/empty-document tag)
        doc (dom/set-root doc input)
        doc (dom/set-attribute doc input :value value)
        doc (cond-> doc
              start (dom/set-attribute input :selection-start start)
              end (dom/set-attribute input :selection-end end)
              placeholder (dom/set-attribute input :placeholder placeholder))
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)]
    (layout/draw-ops tree (cond-> {:width 200} theme (assoc :theme theme)))))

(deftest input-caret-renders-as-a-real-rect-not-literal-null-text
  ;; The confirmed repro from the bug report: the caret op was
  ;; `{:draw/op :text ...}` with NO `:text` key at all, and both real
  ;; hosts' `:text` paint case unconditionally calls `.fillText` with
  ;; whatever `(:text op)` is -- JS coerces the missing/nil argument to
  ;; the literal STRING "null", so every focused <input>'s caret actually
  ;; painted the word "null" instead of a cursor bar.
  (let [ops (input-ops {:value "hello" :start "3" :end "3"})
        caret-op (first (filter :caret? ops))]
    (is (= :rect (:draw/op caret-op))
        "a caret is a thin filled rect, not a :text op with nothing to draw")
    (is (not (contains? caret-op :text)))))

(deftest input-selection-renders-as-a-real-rect-not-literal-null-text
  (let [ops (input-ops {:value "hello world" :start "1" :end "4"})
        sel-op (first (filter :selection? ops))]
    (is (= :rect (:draw/op sel-op)))
    (is (not (contains? sel-op :text)))
    (is (str/starts-with? (:color sel-op) "rgba")
        "a translucent highlight so the text underneath stays legible")))

;; ---- selection/caret geometry must be measured against the real value,
;; never the placeholder ----
;;
;; Real bug this guards: sel-ops previously measured len/clamp/subs
;; against control-text, which falls back to placeholder whenever value
;; is empty. A real, reachable state -- the JS-facing value SETTER never
;; resets selection-start/selection-end, so a common
;; `input.select(); input.value = '';` "clear" idiom leaves stale
;; non-zero selection offsets on a now-empty, placeholder-showing input
;; -- painted a selection highlight positioned against the PLACEHOLDER's
;; own characters instead of correctly clamping to the empty value's own
;; [0,0] range. Confirmed via direct REPL reproduction before touching
;; source.

(deftest input-selection-on-an-empty-placeholder-showing-value-clamps-to-zero-not-the-placeholder
  (let [ops (input-ops {:value "" :placeholder "Search the whole wide web"
                        :start "2" :end "4"})
        sel-op (first (filter :selection? ops))
        caret-op (first (filter :caret? ops))]
    (is (nil? sel-op)
        "an empty value has nothing to select -- stale selection-start/end must clamp to [0,0], not paint a highlight against the placeholder's own characters")
    (is (= 0 (:caret caret-op))
        "the collapsed [0,0] range still paints a real caret, at the start of the (empty) value")
    (is (= 2 (:x caret-op))
        (str "the caret must sit at the box's own inset (position 0 of the
         empty value), not offset into the placeholder text." "\n         ;; The control's own UA box changed with the platform defaults\n         ;; (padding 4 -> 2, border 0 -> 2, font 14 -> 13 Arial): a browser\n         ;; does not inherit the page font into a form control, and gives\n         ;; it its own padding and border. These numbers follow that box;\n         ;; the behaviour under test is unchanged."))))

(deftest input-selection-on-a-non-empty-value-is-unaffected-by-this-fix
  (let [ops (input-ops {:value "hello" :placeholder "Search" :start "1" :end "3"})
        sel-op (first (filter :selection? ops))]
    (is (= 1 (:selection/start sel-op)))
    (is (= 3 (:selection/end sel-op)))))

(deftest input-caret-keeps-its-raw-character-index-alongside-the-pixel-x
  ;; browser.core-test's own form-control-caret-and-selection-project-
  ;; into-draw-ops asserts on this raw index directly -- kept as harmless
  ;; introspection data even though the paint path itself only needs the
  ;; computed pixel :x now.
  (let [caret-op (first (filter :caret? (input-ops {:value "hello" :start "2" :end "2"})))]
    (is (= 2 (:caret caret-op)))))

(deftest input-caret-x-offset-uses-real-measure-text-when-configured
  ;; Previously the caret always painted at the control's raw box edge
  ;; (`:x x`) regardless of the cursor's actual character position --
  ;; confirmed via direct reproduction that a caret at index 3 and a
  ;; caret at index 0 painted at the IDENTICAL x. Fixed by measuring the
  ;; substring up to the caret via the same optional `:measure-text`
  ;; theme callback `layout-text` already established.
  (let [at-0 (first (filter :caret? (input-ops {:value "hello" :start "0" :end "0"
                                                :theme {:measure-text fake-char-measure}})))
        at-3 (first (filter :caret? (input-ops {:value "hello" :start "3" :end "3"
                                                :theme {:measure-text fake-char-measure}})))]
    (is (= 2 (:x at-0)) "just the control's own inset, zero characters in")
    (is (= (+ 2 (* 3 7)) (:x at-3)) "inset plus 3 characters at 7px each")))

(deftest input-caret-x-offset-falls-back-to-the-average-char-width-heuristic
  ;; With no :measure-text configured (every pre-existing caller), the
  ;; offset must fall back to the SAME 0.6*font-size-per-character
  ;; estimate this fn's own selection-width calculation already used --
  ;; not simply stay at the box edge like before this fix.
  (let [caret-op (first (filter :caret? (input-ops {:value "hello" :start "3" :end "3"})))]
    (is (= (+ 2 (* 3 (long (* 0.6 (:font-size layout/default-theme))))) (:x caret-op)))))

(deftest input-selection-reversed-indices-normalize-to-a-valid-forward-range
  ;; A real, if unusual, corner case: selection-start > selection-end
  ;; (e.g. a right-to-left drag). Previously left completely unswapped --
  ;; :selection/start and :selection/end were literally backwards and the
  ;; width calc's own (- e s) went negative, silently clamped to a
  ;; meaningless 1px sliver via the pre-existing (max 1 ...) guard.
  (let [sel-op (first (filter :selection? (input-ops {:value "hello world" :start "4" :end "1"
                                                       :theme {:measure-text fake-char-measure}})))]
    (is (= 1 (:selection/start sel-op)))
    (is (= 4 (:selection/end sel-op)))
    (is (= (* 3 7) (:w sel-op)) "a real 3-character width, not the old negative-clamped 1px sliver")))

(deftest input-selection-out-of-range-index-clamps-not-a-crash
  (let [sel-op (first (filter :selection? (input-ops {:value "hi" :start "0" :end "999"
                                                       :theme {:measure-text fake-char-measure}})))]
    (is (= 2 (:selection/end sel-op))
        "clamped to the control's own text length, not left at the raw out-of-range value")))

(deftest input-with-no-selection-attrs-produces-no-caret-or-selection-ops
  (let [ops (input-ops {:value "hi"})]
    (is (empty? (filter :caret? ops)))
    (is (empty? (filter :selection? ops)))))

;; ---- <textarea> caret/selection painting -- previously gated to :input
;; alone, even though browser.document-input tracks :selection-start/
;; :selection-end on <textarea> exactly the same way as :input (editable-
;; node?/reset-control-state/focus-editable's caret-placement path all
;; already treat the two identically). A focused, actively-typed-into
;; <textarea> had fully correct selection state in the DOM model, but its
;; caret/selection-highlight was silently never painted at all. Confirmed
;; via direct REPL reproduction before touching source. ----

(deftest textarea-caret-renders-as-a-real-rect-just-like-input
  (let [ops (input-ops {:tag :textarea :value "hello" :start "3" :end "3"})
        caret-op (first (filter :caret? ops))]
    (is (= :rect (:draw/op caret-op)))
    (is (not (contains? caret-op :text)))))

(deftest textarea-selection-renders-as-a-real-rect-just-like-input
  (let [ops (input-ops {:tag :textarea :value "hello world" :start "1" :end "4"})
        sel-op (first (filter :selection? ops))]
    (is (= :rect (:draw/op sel-op)))
    (is (str/starts-with? (:color sel-op) "rgba"))))

(deftest textarea-with-no-selection-attrs-produces-no-caret-or-selection-ops
  (let [ops (input-ops {:tag :textarea :value "hi"})]
    (is (empty? (filter :caret? ops)))
    (is (empty? (filter :selection? ops)))))

(deftest select-still-never-gets-a-caret-or-selection-even-with-the-attrs-present
  ;; Regression guard: the fix widens the gate from :input alone to
  ;; #{:input :textarea} -- it must NOT also start affecting :select,
  ;; which never has a real text-selection concept at all.
  (let [ops (input-ops {:tag :select :start "1" :end "3"})]
    (is (empty? (filter :caret? ops)))
    (is (empty? (filter :selection? ops)))))

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

;; ---- the `hidden` global boolean attribute (node-style previously never
;;      consulted it at all -- an extremely common show/hide idiom that
;;      silently rendered normally) ----

(defn- hidden-attr-text-op
  [attrs-fn rules-str]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div)
        doc (dom/set-attribute doc div :class "box")
        doc (attrs-fn doc div)
        [text doc] (dom/create-text-node doc "MARKER_TEXT")
        doc (dom/append-child doc div text)
        rules (css/parse-rules rules-str)
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})]
    (some #(and (= :text (:draw/op %)) %) ops)))

(deftest hidden-attribute-suppresses-rendering-with-no-css-involved
  (is (nil? (hidden-attr-text-op #(dom/set-attribute %1 %2 :hidden "") ""))))

(deftest hidden-attribute-xhtml-explicit-form-also-suppresses
  (is (nil? (hidden-attr-text-op #(dom/set-attribute %1 %2 :hidden "hidden") ""))))

(deftest hidden-attribute-literal-false-string-does-not-suppress
  ;; Mirrors truthy-attr?'s own existing convention for every other
  ;; boolean attribute (checked/required/reversed/open, etc.).
  (is (some? (hidden-attr-text-op #(dom/set-attribute %1 %2 :hidden "false") ""))))

(deftest absent-hidden-attribute-renders-normally
  (is (some? (hidden-attr-text-op (fn [doc _] doc) ""))))

(deftest author-display-declaration-overrides-hidden-attribute
  ;; Real HTML5's [hidden] { display: none } is an ordinary, low-priority
  ;; UA-stylesheet rule, not !important -- an author's own :display always
  ;; wins over it, a common real override pattern (e.g. using `hidden` as
  ;; a state marker while a stylesheet controls actual visibility).
  (is (some? (hidden-attr-text-op #(dom/set-attribute %1 %2 :hidden "")
                                   ".box { display: block }"))))

(deftest unrelated-author-rule-does-not-defeat-hidden-attribute
  (is (nil? (hidden-attr-text-op #(dom/set-attribute %1 %2 :hidden "")
                                  ".box { color: red }"))))

;; ---- the CSS `visibility` property (previously entirely unimplemented --
;;      no :visibility key anywhere in node-style; an element rendered
;;      exactly as if visibility were never set) ----

(defn- visibility-box-and-text-opacity
  [rules-str]
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root div)
        doc (dom/set-attribute doc div :class "box")
        [text doc] (dom/create-text-node doc "MARKER_TEXT")
        doc (dom/append-child doc div text)
        rules (css/parse-rules rules-str)
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        div-op (some #(and (= :node (:draw/op %)) (= :div (:tag %)) %) ops)
        text-op (some #(and (= :text (:draw/op %)) %) ops)]
    {:w (:w div-op) :h (:h div-op) :text-opacity (:opacity text-op)
     :visibility (:visibility div-op)}))

(deftest visibility-hidden-reserves-layout-space-but-paints-nothing
  ;; Unlike display:none (a zero-box, nothing-walked branch), visibility:
  ;; hidden must still occupy its full layout box -- only its own paint is
  ;; suppressed.
  (let [result (visibility-box-and-text-opacity
                ".box { visibility: hidden; width: 100; height: 50 }")]
    (is (= {:w 100 :h 50} (select-keys result [:w :h])))
    (is (= 0.0 (:text-opacity result)))))

(deftest visibility-collapse-behaves-the-same-as-hidden
  (is (= 0.0 (:text-opacity (visibility-box-and-text-opacity
                             ".box { visibility: collapse; width: 100; height: 50 }")))))

(deftest visibility-visible-explicit-is-a-no-op-baseline
  (is (= 1.0 (:text-opacity (visibility-box-and-text-opacity
                             ".box { visibility: visible; width: 100; height: 50 }")))))

(deftest no-visibility-declared-defaults-to-fully-visible
  (is (= 1.0 (:text-opacity (visibility-box-and-text-opacity
                             ".box { width: 100; height: 50 }")))))

(deftest visibility-hidden-combines-multiplicatively-with-explicit-opacity
  (is (= 0.0 (:text-opacity (visibility-box-and-text-opacity
                             ".box { visibility: hidden; opacity: 0.5; width: 100; height: 50 }")))))

(deftest malformed-visibility-value-degrades-to-visible-not-enforced
  (is (= 1.0 (:text-opacity (visibility-box-and-text-opacity
                             ".box { visibility: bogus; width: 100; height: 50 }")))))

;; ---- :visibility on the :node draw-op itself (previously absent from
;; style-passthrough entirely -- the property already correctly zeroed
;; paint opacity above, but the raw value never reached the draw-op a
;; hit-tester (browser.session/node-at, dom-gpu's retained/hit-test)
;; actually scans, so neither could tell a visibility:hidden box apart
;; from an ordinary opaque one for pointer-event purposes -- a real
;; click-through, the same class of bug already fixed for pointer-
;; events:none. Confirmed via direct REPL reproduction before touching
;; source. ----

(deftest node-draw-op-carries-its-own-visibility-value
  (is (= "hidden" (:visibility (visibility-box-and-text-opacity
                                ".box { visibility: hidden; width: 100; height: 50 }"))))
  (is (= "collapse" (:visibility (visibility-box-and-text-opacity
                                  ".box { visibility: collapse; width: 100; height: 50 }"))))
  (is (= "visible" (:visibility (visibility-box-and-text-opacity
                                 ".box { visibility: visible; width: 100; height: 50 }"))))
  (is (nil? (:visibility (visibility-box-and-text-opacity
                          ".box { width: 100; height: 50 }")))
      "no visibility declared at all leaves the field absent, matching every other style-passthrough field's own convention"))

(deftest visibility-hidden-child-with-no-own-visibility-inherits-hidden-by-default
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [outer doc] (dom/create-element doc :div)
        doc (dom/append-child doc root outer)
        doc (dom/set-attribute doc outer :class "outer")
        [inner doc] (dom/create-element doc :span)
        doc (dom/append-child doc outer inner)
        [text doc] (dom/create-text-node doc "CHILD_TEXT")
        doc (dom/append-child doc inner text)
        rules (css/parse-rules ".outer { visibility: hidden }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-op (some #(and (= :text (:draw/op %)) %) ops)]
    (is (= 0.0 (:opacity text-op)))))

(deftest visibility-visible-cannot-un-hide-a-descendant-under-a-hidden-ancestor
  ;; DOCUMENTED, HONEST SCOPE-CUT: real CSS visibility is invertible per
  ;; descendant (a child re-declaring visibility:visible under a hidden
  ;; ancestor genuinely becomes visible again), but this engine reuses the
  ;; SAME multiplicative opacity accumulator every other opacity/inherited
  ;; property already threads -- 0 multiplied by anything stays 0, so this
  ;; is a real, pinned-down limitation, not an oversight.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [outer doc] (dom/create-element doc :div)
        doc (dom/append-child doc root outer)
        doc (dom/set-attribute doc outer :class "outer")
        [inner doc] (dom/create-element doc :span)
        doc (dom/append-child doc outer inner)
        doc (dom/set-attribute doc inner :class "inner")
        [text doc] (dom/create-text-node doc "CHILD_TEXT")
        doc (dom/append-child doc inner text)
        rules (css/parse-rules ".outer { visibility: hidden } .inner { visibility: visible }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-op (some #(and (= :text (:draw/op %)) %) ops)]
    (is (= 0.0 (:opacity text-op)))))

;; ---- opacity is clamped to [0,1] in computed values (CSS Color Module
;; Level 4: "Opacity values outside the range [0,1]... are clamped to the
;; range [0,1] in computed values") -- previously read via parse-dbl with
;; no clamp at all, so an out-of-range author value reached the
;; multiplicative opacity accumulator unclamped: a parent's own opacity:2
;; multiplied a child's correctly-declared opacity:0.5 up to 1.0 instead
;; of the real, spec-clamped 0.5, and opacity:-1 propagated a negative
;; alpha into dom-gpu's paint backends instead of the spec-mandated fully
;; transparent 0. Confirmed via direct REPL reproduction before touching
;; source. ----

(deftest opacity-above-one-is-clamped-to-one
  (is (= 1.0 (:text-opacity (visibility-box-and-text-opacity
                             ".box { opacity: 2; width: 100; height: 50 }")))))

(deftest opacity-below-zero-is-clamped-to-zero
  (is (= 0.0 (:text-opacity (visibility-box-and-text-opacity
                             ".box { opacity: -1; width: 100; height: 50 }")))))

(deftest opacity-in-range-is-unaffected-by-clamping
  (is (= 0.7 (:text-opacity (visibility-box-and-text-opacity
                             ".box { opacity: 0.7; width: 100; height: 50 }")))))

(deftest parent-opacity-above-one-does-not-inflate-a-childs-own-in-range-opacity
  ;; The real regression: a parent's out-of-range opacity must clamp to 1
  ;; BEFORE combining with a child's own opacity, not multiply the child's
  ;; correct value up past what it declared.
  (let [[root doc] (dom/create-element dom/empty-document :main)
        doc (dom/set-root doc root)
        [parent doc] (dom/create-element doc :div)
        doc (dom/append-child doc root parent)
        doc (dom/set-attribute doc parent :class "parent")
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc parent child)
        doc (dom/set-attribute doc child :class "child")
        [text doc] (dom/create-text-node doc "CHILD_TEXT")
        doc (dom/append-child doc child text)
        rules (css/parse-rules ".parent { opacity: 2; width: 100; height: 50 } .child { opacity: 0.5 }")
        doc (css/apply-cascade doc rules)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-op (some #(and (= :text (:draw/op %)) %) ops)]
    (is (= 0.5 (:opacity text-op)))))

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

;; ---- general inline formatting context (layout-inline-run) ----
;;
;; The limitation this whole file's ns docstring used to call permanent --
;; "This engine has NO general inline-flow layout at all -- every child a
;; block-level parent lays out gets its own row" -- is what these tests
;; cover being closed: text and adjacent inline-level elements now share
;; line boxes, wrap together, keep their own per-fragment style, and sit
;; on one shared baseline. The two pre-existing narrow exceptions
;; (merge-adjacent-text-runs' string merge, with-generated-content's
;; pseudo-plus-adjacent-text merge) still exist and still do exactly what
;; they did -- they are STRING merges inside one styled run, which inline
;; flow neither replaces nor duplicates.
;;
;; These two helpers exist only in this section: the explicit
;; create-element/append-child ladder every older test in this file writes
;; out by hand stops being readable at the ~5-node trees an inline run
;; needs (text, element, nested element, text again), and every one of
;; these tests would otherwise repeat 12 near-identical binding lines.

(defn- build-inline-children
  [doc parent specs]
  (reduce (fn [doc spec]
            (if (string? spec)
              (let [[t doc] (dom/create-text-node doc spec)]
                (dom/append-child doc parent t))
              (let [[tag style & kids] spec
                    ;; a few keys are real ATTRIBUTES, not style: the table
                    ;; spans and the form-control ones layout reads directly
                    attr-keys #{:colspan :rowspan :type :value :size :href :alt :name :multiple}
                    attrs (select-keys style attr-keys)
                    style (apply dissoc style attr-keys)
                    [el doc] (dom/create-element doc tag)
                    doc (dom/append-child doc parent el)
                    doc (reduce-kv (fn [d k v] (dom/set-attribute d el k v)) doc attrs)
                    doc (if (seq style) (dom/set-style doc el style) doc)]
                (build-inline-children doc el kids))))
          doc
          specs))

(defn- inline-ops
  "draw-ops for a `<div>` whose children are `specs` -- a vector of strings
   (real text nodes) and `[tag style & children]` vectors (real elements
   with real cascade-shaped inline style)."
  ([specs] (inline-ops specs {} {:width 480}))
  ([specs p-style opts]
   ;; a <div>, not a <p>: since the UA stylesheet landed a <p> carries real
   ;; vertical margins, which would shift every absolute coordinate in this
   ;; section for reasons that have nothing to do with inline flow.
   (let [[p doc] (dom/create-element dom/empty-document :div)
         doc (dom/set-root doc p)
         doc (if (seq p-style) (dom/set-style doc p p-style) doc)
         doc (build-inline-children doc p specs)
         [_ doc] (dom/consume-ops doc)]
     (layout/draw-ops (dom/tree doc) opts))))

(defn- text-draw-ops [ops] (filterv #(= :text (:draw/op %)) ops))

(deftest inline-element-shares-one-line-box-with-adjacent-text
  ;; The canonical case: `<p>hello <b>world</b></p>`. Before
  ;; layout-inline-run this rendered as two stacked rows.
  (let [t (text-draw-ops (inline-ops ["hello " [:b {} "world"]]))]
    (is (= ["hello" "world"] (mapv :text t))
        "two separate draw-ops (each fragment keeps its own style context)
         but ONE inline run -- the trailing space of the text node is now
         the inter-piece gap, not a character inside the op")
    (is (apply = (mapv :y t))
        "same line box")
    (is (= [8 56] (mapv :x t))
        "laid out at the content origin (block inset 4 + run padding 4)
         then advanced by the measured width of 'hello' (5 chars x the
         engine's own (long (* 0.6 14)) = 8px char width = 40) plus one
         collapsed space (8) -- 8 + 40 + 8 = 56")))

(deftest each-inline-fragment-keeps-its-own-resolved-style
  (let [t (text-draw-ops (inline-ops ["plain " [:b {:color "#ff0000" :font-weight "bold"} "loud"]]))]
    (is (= "#e6ebf5" (:color (first t)))
        "the bare text keeps the inherited theme foreground")
    (is (= "#ff0000" (:color (second t)))
        "the <b> fragment paints in its OWN declared color on the same
         line -- the exact thing merge-generated-with-text's docstring
         called impossible for a single :text op, and the reason inline
         flow keeps fragments as separate ops instead of concatenating")
    (is (= "bold" (:font-weight (second t))))
    (is (nil? (:font-weight (first t))))))

(deftest inline-run-wraps-across-fragment-boundaries
  ;; Wrapping is decided over the WHOLE run, not per child: 'aaaa bbbb'
  ;; fits, adding the <b> does not, so the <b> starts the second line.
  (let [t (text-draw-ops (inline-ops ["aaaa bbbb " [:b {} "cccc"]] {} {:width 100}))]
    (is (= ["aaaa bbbb" "cccc"] (mapv :text t))
        "the two same-style words stay ONE draw-op (adjacent pieces
         sharing style + owners are merged, so a real paragraph does not
         become one op per word)")
    (is (= [8 8] (mapv :x t)))
    (is (= [8 28] (mapv :y t))
        "second line advanced by the line box height (theme line-height 20)")))

(deftest br-forces-a-new-line-box
  (let [t (text-draw-ops (inline-ops ["a" [:br {}] "b"]))]
    (is (= ["a" "b"] (mapv :text t)))
    (is (= [8 28] (mapv :y t)))
    (is (= [8 8] (mapv :x t)))))

(deftest whitespace-only-text-nodes-between-inline-elements-collapse
  ;; The shape a real HTML parser produces for indented source:
  ;; `<a>one</a>\n  <a>two</a>` -- three children, the middle one pure
  ;; whitespace. Before inline flow that middle node became its own
  ;; (blank, space-consuming) row and the two links stacked.
  (let [ops (inline-ops [[:a {} "one"] "\n  " [:a {} "two"]])
        t (text-draw-ops ops)]
    (is (= ["one" "two"] (mapv :text t))
        "the whitespace-only node contributes no draw-op of its own")
    (is (apply = (mapv :y t)))
    (is (= [8 40] (mapv :x t))
        "collapsed to exactly ONE space between the two links: 8 + 24 + 8")))

(deftest inline-element-emits-a-node-draw-op-for-hit-testing
  ;; Without this an inline <a> would be unclickable: browser's
  ;; session/node-at and dom-gpu's retained hit testing both scan :node ops.
  (let [ops (inline-ops ["click " [:a {} "here"]])
        a-op (first (filter #(and (= :node (:draw/op %)) (= :a (:tag %))) ops))]
    (is (some? a-op) "the inline <a> still gets its own :node draw-op")
    (is (= {:x 56 :y 8 :w 32 :h 16} (select-keys a-op [:x :y :w :h]))
        "box spans exactly the fragment it painted, and its HEIGHT is the
         font's content area (1.2em) centred in the line box by
         half-leading -- not the line box itself. Measured against Chrome,
         which reports y=1 h=18 for a 14px inline on a 20px line where this
         engine used to report y=0 h=20, missing on both axes at once")))

(deftest wrapped-inline-box-gets-per-line-backgrounds-and-one-union-node-op
  (let [ops (inline-ops ["x " [:a {:background "#123456"} "aaaa bbbb cccc"]] {} {:width 100})
        rects (filterv #(and (= :rect (:draw/op %)) (= "#123456" (:color %))) ops)
        a-op (first (filter #(and (= :node (:draw/op %)) (= :a (:tag %))) ops))]
    (is (= 2 (count rects))
        "the wrapped inline box's background follows BOTH line boxes
         rather than filling one rectangle around them")
    (is (= [8 28] (mapv :y rects))
        "each line's background sits at that line's content area, not its
         full line box")
    (is (= {:x 8 :y 8 :h 36} (select-keys a-op [:x :y :h]))
        "one union :node op covering both fragments -- the documented
         over-covering approximation of real CSS's per-fragment box list")))

(deftest nested-inline-elements-inherit-and-override
  (let [t (text-draw-ops
           (inline-ops ["x " [:span {:color "#00ff00"} "outer " [:b {:font-weight "bold"} "inner"]]]))]
    (is (= ["x" "outer" "inner"] (mapv :text t)))
    (is (apply = (mapv :y t)) "all three on one line")
    (is (= ["#e6ebf5" "#00ff00" "#00ff00"] (mapv :color t))
        "the nested <b> inherits its parent <span>'s declared color")
    (is (= [nil nil "bold"] (mapv :font-weight t))
        "while adding its own weight on top of it")))

(deftest mixed-font-sizes-share-one-baseline
  ;; dom-gpu's hosts paint a :text op at (+ y font-size), so equal :y
  ;; would mean MIS-aligned baselines for mixed sizes. Each piece's :y is
  ;; offset so (+ y font-size) is identical across the line.
  (let [t (text-draw-ops (inline-ops ["small " [:span {:font-size 28} "big"]]))]
    (is (= [22 8] (mapv :y t)))
    (is (apply = (map (fn [op] (+ (:y op) (:font-size op))) t))
        "one shared baseline at y=36")))

(deftest text-align-centers-a-mixed-inline-line
  (let [t (text-draw-ops (inline-ops ["a " [:b {} "bc"]] {:text-align "center"} {:width 480}))]
    (is (apply = (mapv :y t)))
    (is (= [224 240] (mapv :x t))
        "the WHOLE line (8 + 8 + 16 = 32px wide) is centered in the 464px
         content width as one unit -- (464 - 32) / 2 = 216, + the 8px
         content origin")))

(deftest a-block-child-inside-an-inline-element-falls-back-to-block-rows
  ;; Real CSS splits the inline box around the block child
  ;; (block-in-inline); this engine does not implement that, so the whole
  ;; run keeps its pre-existing block-row behavior rather than being
  ;; silently mis-nested into one line box.
  (let [t (text-draw-ops (inline-ops ["text " [:span {} [:div {} "block"]]]))]
    (is (= ["text " "block"] (mapv :text t))
        "unchanged legacy path, trailing space and all")
    (is (apply < (mapv :y t)) "still two stacked rows")))

(deftest non-normal-white-space-keeps-the-pre-existing-path
  (let [t (text-draw-ops (inline-ops ["a " [:span {:white-space "pre"} "b"]]))]
    (is (apply < (mapv :y t))
        "a `white-space: pre` inline box opts out of the collapsing
         tokenizer entirely rather than being quietly re-collapsed")))

(deftest a-form-control-is-an-atomic-inline-on-the-same-line-as-its-label
  ;; This test previously pinned the opposite ("an <input> keeps its own
  ;; block row -- documented scope-cut"), which the Blink conformance
  ;; harness scored as inline-replaced 0/3. Form controls and replaced
  ;; elements are inline-level in real CSS, and now here too.
  (let [ops (inline-ops ["label " [:input {}]])
        t (text-draw-ops ops)
        input-op (first (filter #(and (= :node (:draw/op %)) (= :input (:tag %))) ops))]
    (is (= 8 (:y input-op))
        "the control sits at the top of the line box it made taller")
    (is (= 13 (:y (first t)))
        "and the label text is pushed down so both sit on one baseline:
         the control's own INTERNAL baseline (its text's, not its bottom
         edge -- see inline-fragments' baseline-offset) and the label's
         baseline (9 + 14) coincide -- real CSS `vertical-align: baseline`.
         The control is 24 tall rather than 28 because it now carries the
         UA box a browser gives it (13px Arial, 2px padding, 2px border)
         instead of inheriting the page font")
    (is (< (:x (first t)) (:x input-op))
        "label first, control after it, on that one line")
    (is (< (:w input-op) 200)
        "and the control takes its INTRINSIC width (20 characters, HTML's
         own default `size`) rather than the full container width a block
         child would fill -- the fix that made a form control fit on a
         line at all")))

(deftest explicit-display-block-takes-a-span-out-of-inline-flow
  (let [t (text-draw-ops (inline-ops ["x " [:span {:display "block"} "y"]]))]
    (is (apply < (mapv :y t))
        "author display wins over the inline-level tag default")))

(deftest a-lone-text-child-is-byte-for-byte-unchanged
  ;; The single most common shape in the whole engine stays on
  ;; layout-text's exact pre-existing path -- inline flow only engages
  ;; for runs of two or more inline children (see inline-runs).
  (let [t (text-draw-ops (inline-ops ["hello world"]))]
    (is (= 1 (count t)))
    (is (= {:text "hello world" :x 8 :y 8 :font-size 14} (select-keys (first t) [:text :x :y :font-size])))))

(deftest a-display-none-element-does-not-split-an-inline-run
  ;; Found by conformance/run.cljs differential testing against a real
  ;; Blink browser, not by hand: `keep <span style="display:none">gone
  ;; </span> this` used to produce TWO one-child inline runs (neither
  ;; reaching inline-runs' two-child threshold), so the two visible words
  ;; stacked on separate lines while every real browser puts them on one.
  (let [t (text-draw-ops (inline-ops ["keep " [:span {:display "none"} "gone"] " this"]))]
    (is (= ["keep this"] (mapv :text t))
        "the hidden element contributes no draw-op, and the text that
         surrounded it becomes one contiguous run on one line -- exactly
         what a real browser renders. Before the fix these were two
         one-child runs and therefore two stacked rows")))

(deftest a-script-element-does-not-split-an-inline-run
  (let [t (text-draw-ops (inline-ops ["keep " [:script {} "var x = 1"] " this"]))]
    (is (= ["keep this"] (mapv :text t))
        "same for a non-rendered tag: <script> source never reaches layout
         and never breaks the line around it")))

;; ---- position: relative on an INLINE box (it used to be BLOCKIFIED:
;;      inline-level-element? required `position: static`, so the element
;;      left the inline path entirely and the whole line fell apart into
;;      full-width block rows -- far worse than the missing offset the
;;      exclusion was written to avoid) ----

(deftest a-relative-inline-stays-on-the-line-with-the-text-around-it
  ;; Measured in Brave: `<p>text <span style="position: relative">anchor
  ;; </span> tail</p>` is ONE 20px line with the span at (35,2). This
  ;; engine made the paragraph 60px tall with the span on a row of its
  ;; own, 800px wide.
  ;;
  ;; Written as a comparison against the identical markup with a STATIC
  ;; span: with no offsets declared, `position: relative` changes nothing
  ;; a reader can see, and that is the whole assertion.
  (let [rel (text-draw-ops (inline-ops ["text " [:span {:position "relative"} "anchor"] " tail"]))
        static (text-draw-ops (inline-ops ["text " [:span {} "anchor"] " tail"]))]
    (is (= ["text" "anchor" "tail"] (mapv :text rel)))
    (is (apply = (mapv :y rel)) "one shared line box")
    (is (= (mapv (juxt :text :x :y) static) (mapv (juxt :text :x :y) rel))
        "an offsetless relative inline lays out exactly like a static one")))

(deftest a-relative-inlines-offset-moves-it-and-nothing-else
  ;; Real CSS: relative positioning affects PAINTING only. The offset is
  ;; accumulated onto the owner stack in inline-fragments and added at
  ;; paint time, so it never reaches the line breaker -- the words after
  ;; the span therefore do not move, exactly as layout-children-block
  ;; already arranges for a relative BLOCK row.
  (let [ops (inline-ops ["text " [:span {:position "relative" :left "5" :top "3"} "anchor"] " tail"])
        t (text-draw-ops ops)
        base (text-draw-ops (inline-ops ["text " [:span {} "anchor"] " tail"]))
        span-op (some #(and (= :node (:draw/op %)) (= :span (:tag %)) %) ops)
        base-span (some #(and (= :node (:draw/op %)) (= :span (:tag %)) %)
                        (inline-ops ["text " [:span {} "anchor"] " tail"]))]
    (is (= [(+ 5 (:x (nth base 1))) (+ 3 (:y (nth base 1)))]
           [(:x (nth t 1)) (:y (nth t 1))])
        "the span's own text moves by exactly its declared offset")
    (is (= [(+ 5 (:x base-span)) (+ 3 (:y base-span))] [(:x span-op) (:y span-op)])
        "and so does the box a click/hit-test sees")
    (is (= [((juxt :x :y) (nth base 0)) ((juxt :x :y) (nth base 2))]
           [((juxt :x :y) (nth t 0)) ((juxt :x :y) (nth t 2))])
        "the text on either side of it does not move at all")))

(deftest a-relative-inline-is-the-containing-block-of-its-absolute-child
  ;; The reason anyone writes `position: relative` in the first place.
  ;; Measured in Brave: `<p>text <span style="position: relative">anchor
  ;; <span style="position: absolute; left: 0; top: 20px">pop</span>
  ;; </span> tail</p>` puts the inner span at (35,22) -- 35 being where
  ;; the RELATIVE span starts in the line, not the paragraph's content
  ;; edge, which is where this engine put it (x=0).
  ;;
  ;; The absolute child is written as an offset FROM the anchor's own box
  ;; rather than as fixed numbers, because the anchor's x is the measured
  ;; width of the text before it and this test is about the anchoring.
  (let [ops (inline-ops ["text " [:span {:position "relative"}
                                  "anchor"
                                  [:span {:position "absolute" :left "0" :top "20"} "pop"]]
                         " tail"])
        anchor (some #(and (= :node (:draw/op %)) (= :span (:tag %))
                           (= "relative" (:position %)) %)
                     ops)
        pop-op (some #(and (= :node (:draw/op %)) (= :span (:tag %))
                           (= "absolute" (:position %)) %)
                     ops)
        t (text-draw-ops ops)]
    (is (= ["text" "anchor" "tail" "pop"] (mapv :text t))
        "the absolute child contributes nothing to the LINE -- but its
         ancestor still flows, which it could not do while an out-of-flow
         child counted as a block child (split-block-in-inline) or made
         its parent unflowable (inline-flow-candidate?). It paints last,
         after the in-flow content, which is where an out-of-flow box with
         no negative z-index belongs")
    (is (= (:x anchor) (:x pop-op))
        "left: 0 resolves against the relative INLINE's own box -- the
         paragraph's content edge, where this used to land, is 44px to
         the left of it here")
    (is (= (+ 20 (:y anchor)) (:y pop-op))
        "and top: 20 against the same box's top edge")
    (is (apply = (mapv :y (remove #(= "pop" (:text %)) t)))
        "the three in-flow words still share one line")))

(deftest nested-relative-inlines-accumulate-their-offsets
  ;; A relative box moves everything inside it, INCLUDING another relative
  ;; box, whose own offset is then measured from there.
  (let [ops (inline-ops ["a "
                         [:span {:position "relative" :left "10"}
                          "outer "
                          [:span {:position "relative" :left "4"} "inner"]]])
        t (text-draw-ops ops)
        base (text-draw-ops (inline-ops ["a " [:span {} "outer " [:span {} "inner"]]]))]
    (is (= (+ 10 (:x (nth base 1))) (:x (nth t 1)))
        "the outer span's own text moves by its own offset")
    (is (= (+ 14 (:x (nth base 2))) (:x (nth t 2)))
        "the inner one moves by both")))

(deftest an-img-flows-inline-at-its-presentational-size
  ;; <img width/height> are presentational hints real UA stylesheets map
  ;; onto CSS width/height. Without that mapping the image resolved to the
  ;; full container width and forced a line break after every image.
  (let [ops (inline-ops ["before " [:img {}] " after"]
                        {} {:width 480})
        img-op (first (filter #(and (= :node (:draw/op %)) (= :img (:tag %))) ops))]
    (is (some? img-op)))
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [t1 doc] (dom/create-text-node doc "before ")
        doc (dom/append-child doc p t1)
        [img doc] (dom/create-element doc :img)
        doc (dom/set-attribute doc img :width "10")
        doc (dom/set-attribute doc img :height "10")
        doc (dom/append-child doc p img)
        [t2 doc] (dom/create-text-node doc " after")
        doc (dom/append-child doc p t2)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 480})
        t (text-draw-ops ops)
        img-op (first (filter #(and (= :node (:draw/op %)) (= :img (:tag %))) ops))]
    (is (= [10 10] [(:w img-op) (:h img-op)])
        "the width/height attributes size the box")
    (is (apply = (mapv :y t))
        "text on both sides shares one line")
    (is (< (:x (first t)) (:x img-op) (:x (second t)))
        "with the image between them in document order")
    (is (= (+ (:y img-op) (:h img-op)) (+ (:y (first t)) (:font-size (first t))))
        "and the image's BOTTOM edge on the text baseline, real CSS's
         `vertical-align: baseline` for a replaced box")))

(deftest a-button-shrink-wraps-to-its-label-inside-a-line
  (let [ops (inline-ops ["hit " [:button {} "go"] " now"])
        t (text-draw-ops ops)
        b (first (filter #(and (= :node (:draw/op %)) (= :button (:tag %))) ops))]
    (is (= ["hit" "go" "now"] (mapv :text t)))
    (is (< (:w b) 100)
        "the button is as wide as its own label, not the container")
    (is (= (:y (first t)) (:y (last t)))
        "the text before and after the button is on one line")))

(deftest an-atomic-inline-wraps-whole-rather-than-splitting
  ;; A narrow line: the button cannot fit after the text, so it moves to
  ;; the next line intact instead of being broken or overlapping.
  (let [ops (inline-ops ["aaaa bbbb " [:button {} "wide-label-here"]] {} {:width 140})
        t (text-draw-ops ops)
        b (first (filter #(and (= :node (:draw/op %)) (= :button (:tag %))) ops))]
    (is (< (:y (first t)) (:y b))
        "the atomic box wrapped to a later line")))

;; ---- table layout ----

(defn- table-ops
  "draw-ops for a `<table>` built from the same `[tag style & children]`
   spec vectors the inline tests use, with the engine's own theme insets
   zeroed so the assertions read as plain geometry."
  [rows]
  (let [[table doc] (dom/create-element dom/empty-document :table)
        doc (dom/set-root doc table)
        doc (build-inline-children doc table rows)
        [_ doc] (dom/consume-ops doc)]
    (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})))

(deftest table-cells-share-a-row
  ;; Before layout-table existed, a table rendered as ONE stacked column of
  ;; every cell in document order -- the conformance corpus scored tables
  ;; 0/2 against a real browser.
  (let [ops (table-ops [[:tr {} [:td {} "a"] [:td {} "b"]] [:tr {} [:td {} "c"] [:td {} "d"]]])
        t (text-draw-ops ops)]
    (is (= ["a" "b" "c" "d"] (mapv :text t)))
    (is (= (:y (nth t 0)) (:y (nth t 1)))
        "first row's two cells share a line")
    (is (= (:y (nth t 2)) (:y (nth t 3)))
        "and so do the second row's")
    (is (< (:y (nth t 0)) (:y (nth t 2)))
        "with the second row below the first")
    (is (< (:x (nth t 0)) (:x (nth t 1)))
        "cells advance across the row")))

(deftest table-flattens-thead-and-tbody
  ;; A real HTML parser INSERTS <tbody> even when the author never wrote
  ;; it, so a layout that only looked for direct <tr> children would find
  ;; no rows on most real markup.
  (let [t (text-draw-ops
           (table-ops [[:thead {} [:tr {} [:th {} "h1"] [:th {} "h2"]]]
                       [:tbody {} [:tr {} [:td {} "a"] [:td {} "b"]]]]))]
    (is (= ["h1" "h2" "a" "b"] (mapv :text t)))
    (is (= (:y (nth t 0)) (:y (nth t 1))))
    (is (= (:y (nth t 2)) (:y (nth t 3))))
    (is (< (:y (nth t 0)) (:y (nth t 2))))))

(deftest table-columns-size-to-their-widest-cell
  (let [ops (table-ops [[:tr {} [:td {} "wide-content"] [:td {} "x"]] [:tr {} [:td {} "a"] [:td {} "b"]]])
        cells (filterv #(and (= :node (:draw/op %)) (= :td (:tag %))) ops)
        col0 (mapv :w (take-nth 2 cells))
        col1 (mapv :w (take-nth 2 (rest cells)))]
    (is (apply = col0) "a column has ONE width, shared by every cell in it")
    (is (apply = col1))
    (is (> (first col0) (first col1))
        "and it is the width of the widest cell in that column")))

(deftest table-emits-row-and-cell-node-ops-for-hit-testing
  (let [ops (table-ops [[:tr {} [:td {} "a"] [:td {} "b"]]])]
    (is (= 1 (count (filter #(and (= :node (:draw/op %)) (= :table (:tag %))) ops))))
    (is (= 1 (count (filter #(and (= :node (:draw/op %)) (= :tr (:tag %))) ops))))
    (is (= 2 (count (filter #(and (= :node (:draw/op %)) (= :td (:tag %))) ops)))
        "click routing and the accessibility projection see a real table
         structure, not a flat pile of text")))

(deftest table-cell-contents-use-the-ordinary-layout-path
  ;; A cell is laid out through layout-node at its own column width, so
  ;; inline flow inside a cell behaves exactly as it does anywhere else.
  (let [t (text-draw-ops (table-ops [[:tr {} [:td {} "go " [:b {} "now"]] [:td {} "x"]]]))]
    (is (= ["go" "now" "x"] (mapv :text t)))
    (is (apply = (mapv :y t))
        "the cell's own inline run shares the line, and so does the next cell")))

(deftest ua-stylesheet-defaults-apply-without-author-css
  ;; This engine had NO user-agent stylesheet: <b> was not bold, <em> was
  ;; not italic, and every heading rendered at body size. Authors never
  ;; write those rules -- the UA does -- so the whole class was invisible
  ;; until the conformance harness gained a geometry axis.
  (let [t (text-draw-ops (inline-ops ["plain " [:b {} "strong"]]))]
    (is (= [nil "bold"] (mapv :font-weight t))
        "<b> is bold with no author CSS at all"))
  (let [t (text-draw-ops (inline-ops ["plain " [:em {} "stressed"]]))]
    (is (= [nil "italic"] (mapv :font-style t))))
  (let [[h1 doc] (dom/create-element dom/empty-document :h1)
        doc (dom/set-root doc h1)
        [t doc] (dom/create-text-node doc "title")
        doc (dom/append-child doc h1 t)
        [_ doc] (dom/consume-ops doc)
        op (first (text-draw-ops (layout/draw-ops (dom/tree doc) {:width 400})))]
    (is (= 28 (:font-size op)) "h1 is 2em of the theme's base size")
    (is (= "bold" (:font-weight op)))))

(deftest normal-line-height-follows-font-size
  ;; `line-height: normal` is ~1.2x the font size in every real browser,
  ;; not a fixed pixel count. With the theme's flat 20px default an <h1> at
  ;; 28px got a 20px line box, so its text overflowed and the NEXT block
  ;; painted on top of it -- caught by the harness the same hour heading
  ;; sizes landed.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        doc (build-inline-children doc root [[:h1 {} "title"] [:p {} "body"]])
        [_ doc] (dom/consume-ops doc)
        t (text-draw-ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}}))]
    (is (= ["title" "body"] (mapv :text t)))
    (is (>= (- (:y (second t)) (:y (first t))) 28)
        "the paragraph clears the heading's own line box rather than
         overlapping it")))

(deftest table-uses-border-spacing-and-cell-padding-defaults
  ;; Measured against Chrome: `border-spacing: 2px` (cells separated by it
  ;; AND the table inset by it on all four sides) and `td { padding: 1px }`
  ;; are UA defaults. Their absence was the single reason table geometry
  ;; never matched -- a two-cell table reported 49x20 here against the
  ;; browser's 59x26.
  (let [ops (table-ops [[:tr {} [:td {} "a"] [:td {} "b"]]])
        table (first (filter #(and (= :node (:draw/op %)) (= :table (:tag %))) ops))
        cells (filterv #(and (= :node (:draw/op %)) (= :td (:tag %))) ops)]
    (is (= 2 (:x (first cells))) "first cell inset by the border-spacing")
    (is (= 2 (:y (first cells))))
    (is (= (+ 2 (:w (first cells)) 2) (:x (second cells)))
        "and cells separated by it")
    (is (= (+ (:x (second cells)) (:w (second cells)) 2) (:w table))
        "with the table closing on a trailing spacing too")))

(deftest a-table-shrink-wraps-to-its-columns
  ;; Real CSS: a table with `width: auto` is shrink-to-fit, not
  ;; fill-the-container the way an ordinary block is.
  (let [ops (table-ops [[:tr {} [:td {} "a"] [:td {} "b"]]])
        table (first (filter #(and (= :node (:draw/op %)) (= :table (:tag %))) ops))]
    (is (< (:w table) 100)
        "narrow content means a narrow table, not a 400px-wide one")))

(deftest a-colspan-cell-covers-its-columns
  ;; Found by the geometry axis: the line-structure axis cannot see colspan
  ;; at all (a spanning cell is alone on its row either way), and this
  ;; engine placed it in ONE column -- making that column as wide as the
  ;; spanning content and leaving the next one holding only its own.
  (let [ops (table-ops [[:tr {} [:td {:colspan "2"} "spanning-header"]]
                        [:tr {} [:td {} "a"] [:td {} "b"]]])
        cells (filterv #(and (= :node (:draw/op %)) (= :td (:tag %))) ops)
        spanning (first cells)
        [c1 c2] (rest cells)]
    (is (>= (:w spanning) (:w c1))
        "the spanning cell covers BOTH columns rather than sitting in one.
         The exact arithmetic -- both columns plus the border-spacing that
         no longer separates anything between them -- is checked against a
         REAL BROWSER by conformance/cases.edn's
         :table/colspan-line-structure-only, which went from 1/7 boxes in
         agreement to 7/7 with this change; asserting it a second time here
         in absolute pixels would only pin this engine's own arithmetic")
    (is (= (:x c1) (:x spanning))
        "and starts at the first column it covers")
    (is (< (:x c1) (:x c2))
        "while the row below still has two independent cells")))

(deftest a-form-control-has-an-intrinsic-width-outside-a-line-too
  ;; Intrinsic sizing used to live only on the inline path, so an <input>
  ;; as a flex item took the whole container: the geometry axis reported
  ;; 800px against the browser's 153.
  (let [[row doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc row)
        doc (dom/set-style doc row {:display "flex"})
        doc (build-inline-children doc row [[:label {} "name"] [:input {}]])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 800 :theme {:padding 0 :gap 0}})
        input (first (filter #(and (= :node (:draw/op %)) (= :input (:tag %))) ops))]
    (is (< (:w input) 250)
        "the control takes its own intrinsic width, not the container's")))

(deftest a-flex-container-is-block-level
  ;; `display: flex` makes a BLOCK-level flex container: it fills its
  ;; containing block and only its ITEMS shrink-to-fit. This engine
  ;; shrink-wrapped the container itself, so a row of three one-character
  ;; items was 21px wide where a browser reports 800 -- and every
  ;; justify-content computation then distributed space inside that 21px.
  ;; The line-structure axis scored all of those cases as passes; the
  ;; geometry axis reported div w -750 across ten boxes.
  (let [[row doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc row)
        doc (dom/set-style doc row {:display "flex"})
        doc (build-inline-children doc row [[:div {} "a"] [:div {} "b"] [:div {} "c"]])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 800 :theme {:padding 0 :gap 0}})
        container (first (filter #(and (= :node (:draw/op %)) (= :div (:tag %))) ops))]
    (is (= 800 (:w container)))))

(deftest an-inherited-explicit-line-height-survives-into-inline-boxes
  ;; The `normal` floor (1.2em) must not override a line-height the page
  ;; actually declared -- including for a LARGER inline inside it, which
  ;; overflows the line box in a real browser rather than growing it.
  (let [[wrap doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc wrap)
        doc (dom/set-style doc wrap {:line-height 20})
        doc (build-inline-children doc wrap ["small " [:span {:font-size 24} "big"] " tail"])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 800 :theme {:padding 0 :gap 0}})
        wrap-op (first (filter #(and (= :node (:draw/op %)) (= :div (:tag %))) ops))]
    (is (= 20 (:h wrap-op))
        "the line box is the DECLARED 20px: real CSS sizes a line box from
         the line-heights on it, not from the font sizes, and lets a larger
         run OVERFLOW rather than growing the box. (A browser reports 24
         here, because it positions each inline box by the font's real
         ascent/descent and takes their union -- metrics this engine does
         not model at all. The 20 is right for the rule this engine can
         actually express; the remaining gap is recorded in
         conformance/README.md rather than fitted with a guessed constant.)")))

;; ---- floats ----

(deftest a-right-float-sits-at-the-container-edge
  ;; This engine had no float concept at all: a floated span stayed inline
  ;; where it was written, so a right-floated badge sat at the START of the
  ;; text. Measured against the browser: x=0 here against its 233 in a
  ;; 240px box.
  (let [[box doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc box)
        doc (dom/set-style doc box {:width 240})
        doc (build-inline-children doc box [[:span {:float "right"} "R"] "alpha beta"])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        floated (first (filter #(and (= :node (:draw/op %)) (= :span (:tag %))) ops))
        ;; the float paints its OWN label too, so pick the flow text by name
        text (first (filter #(= "alpha beta" (:text %)) (text-draw-ops ops)))]
    (is (= (- 240 (:w floated)) (:x floated))
        "flush against the container's right edge")
    (is (< (:x text) (:x floated))
        "with the text before it, not after")))

(deftest a-left-float-narrows-the-text-beside-it
  (let [[box doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc box)
        doc (dom/set-style doc box {:width 200})
        doc (build-inline-children doc box [[:span {:float "left" :width 60 :height 60} "F"]
                                            "alpha beta gamma delta epsilon"])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        floated (first (filter #(and (= :node (:draw/op %)) (= :span (:tag %))) ops))
        t (text-draw-ops ops)]
    (is (= 0 (:x floated)) "the float is at the left edge")
    (is (every? #(>= (:x %) 60) (remove #(= "F" (:text %)) t))
        "and every run of the flow text -- the float's own label aside --
         starts after it, in the narrowed band")))

(deftest a-floated-element-leaves-the-inline-run
  ;; `float` blockifies: a floated box is positioned by its container, so
  ;; it must not be treated as inline-level content even when its tag is.
  (let [[box doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc box)
        doc (build-inline-children doc box [[:span {:float "left"} "F"] "text"])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        floated (first (filter #(and (= :node (:draw/op %)) (= :span (:tag %))) ops))]
    (is (= 20 (:h floated))
        "it reports a BLOCK box (one line-height tall), not an inline box's
         1.2em content area")))

;; ---- floats: placement, stacking, margins, clear, containment ----
;;
;; Every number asserted below was read out of a real headless Brave 151
;; over CDP FIRST (conformance/cdp_dump.cljs, one isolating case per
;; behaviour) and only then implemented. Where the browser's own figure
;; differs from the one here it is because the browser's default font is
;; 16px and this suite's line box is 20px; the RELATIONSHIPS -- which edge,
;; which stacking order, which box grows -- are the browser's exactly.

(defn- float-ops
  "draw-ops for a `<div>` of `width` (plus any extra style) whose children
   are `specs`, at a zero-inset theme so the coordinates that come back are
   the CSS ones rather than the host theme's 4px padding/gap."
  ([width specs] (float-ops width {} specs))
  ([width extra-style specs]
   (let [[box doc] (dom/create-element dom/empty-document :div)
         doc (dom/set-root doc box)
         doc (dom/set-style doc box (merge {:width width} extra-style))
         doc (build-inline-children doc box specs)
         [_ doc] (dom/consume-ops doc)]
     (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}}))))

(defn- div-boxes
  "The `<div>` element boxes in document order, as [x y w h] vectors."
  [ops]
  (->> ops
       (filter #(and (= :node (:draw/op %)) (= :div (:tag %))))
       (mapv (juxt :x :y :w :h))))

(deftest floats-that-do-not-fit-side-by-side-stack
  ;; Two 120px floats cannot share a 200px line. CSS 9.5.1 pushes the
  ;; second DOWN until the band is wide enough again; this engine used to
  ;; run a single left cursor and simply put it at x=120, overflowing.
  ;; Brave: (0,0) and (0,20), and the divergence the conformance corpus
  ;; charged for that cluster was a -53px median on `div x`.
  (let [[_root a b tail]
        (div-boxes (float-ops 200 [[:div {:float "left" :width 120 :height 20} "A"]
                                   [:div {:float "left" :width 120 :height 20} "B"]
                                   [:div {} "tail"]]))]
    (is (= [0 0 120 20] a) "the first float takes the left edge")
    (is (= [0 20 120 20] b)
        "the second does not fit beside it, so it drops to the first's
         bottom edge and takes the left edge there")
    (is (= [0 0 200 20] tail)
        "a BLOCK box is not narrowed by a float -- only its line boxes are,
         so the border box still starts at the container's own left edge
         and spans its full width")))

(deftest a-float-is-placed-and-measured-by-its-margin-box
  ;; A float's own margins used to be dropped on the floor: the box painted
  ;; at the container's edge and the band it excluded was a BORDER box wide,
  ;; so the content beside it started 10px too early. Brave puts this one at
  ;; (10,10) with the text beside it at x=80.
  (let [ops (float-ops 300 [[:div {:float "left" :width 60 :height 30 :margin 10} "F"]
                            "text beside the float"])
        [_root f] (div-boxes ops)
        beside (first (filter #(= "text beside the float" (:text %)) (text-draw-ops ops)))]
    (is (= [10 10 60 30] f)
        "the BORDER box sits one margin in from the container's own corner")
    (is (= 80 (:x beside))
        "and the band the text avoids is the MARGIN box: 10 + 60 + 10")))

(deftest a-float-narrows-its-own-containers-lines-and-not-a-descendants
  ;; The documented boundary of this implementation, pinned so it is a
  ;; recorded scope-cut rather than a silent wrong answer. The float band
  ;; lives in layout-children-block and is consulted by the line boxes of
  ;; the float's OWN container; layout-node does not carry a float context
  ;; down into a child, so a block DESCENDANT lays its lines out at full
  ;; width. Brave narrows them: on this shape it puts the inner div's text
  ;; at x=80 while leaving the inner div's BORDER box at x=0 spanning the
  ;; full 300.
  ;;
  ;; The border box -- the thing the conformance corpus's geometry axis
  ;; actually compares -- is right either way, so what this costs is a wrap
  ;; point: text long enough to break differs from the browser's. Fixing it
  ;; means threading a float context through layout-node, which is a
  ;; larger change than this one.
  (let [ops (float-ops 300 [[:div {:float "left" :width 80 :height 40} "F"]
                            [:div {} "beside"]])
        [_root _f inner] (div-boxes ops)
        beside (first (filter #(= "beside" (:text %)) (text-draw-ops ops)))]
    (is (= [0 0 300 20] inner)
        "the descendant's border box is full width and starts at the
         container's left edge -- which is what the browser reports too")
    (is (= 0 (:x beside))
        "but its LINE is not narrowed by the float, where a browser puts it
         at 80. Known cut; see this test's comment")))

(deftest a-float-starts-at-the-flow-position-it-was-written-at
  ;; The v1 float implementation's own headline exclusion: every float was
  ;; hoisted to the container's TOP, so a float written after a paragraph
  ;; moved up ABOVE the paragraph it followed. Brave puts it at the
  ;; paragraph's bottom plus the margin collapsed between the two <p>s --
  ;; y=34 here (20px line box + a <p>'s 14px UA margin), y=0 before.
  (let [ops (float-ops 300 [[:p {} "leading text"]
                            [:div {:float "left" :width 60 :height 30} "F"]
                            [:p {} "beside the float"]])
        [_root f] (div-boxes ops)
        ps (->> ops (filter #(and (= :node (:draw/op %)) (= :p (:tag %)))) (mapv :y))]
    (is (= [0 34] ps) "the two paragraphs are one collapsed margin apart")
    (is (= 34 (second f))
        "and the float sits where the SECOND paragraph starts, which is
         the flow position it was written at -- a float does not separate
         its siblings, and their margins collapse straight through it")))

(deftest clear-pushes-a-block-below-the-floats-on-that-side
  ;; `clear` was read nowhere at all. Brave on this shape: the cleared div
  ;; at y=40 (the float's bottom margin edge) and the container 64px tall.
  (let [ops (float-ops 300 [[:div {:float "left" :width 80 :height 40} "F"]
                            [:div {} "beside"]
                            [:div {:clear "left"} "below"]])
        [root f beside below] (div-boxes ops)]
    (is (= [0 0 80 40] f))
    (is (= 0 (second beside)) "the uncleared block still starts at the top")
    (is (= 40 (second below))
        "the cleared block starts at the float's bottom margin edge")
    (is (= 60 (nth root 3))
        "and the clearance is real layout, not a paint offset: it makes the
         container taller (20 beside + 20 clearance + 20 below)")))

(deftest clear-both-clears-the-lower-of-the-two-sides
  (let [[_root _l r after]
        (div-boxes (float-ops 300 [[:div {:float "left" :width 60 :height 30} "L"]
                                   [:div {:float "right" :width 60 :height 50} "R"]
                                   [:div {:clear "both"} "after"]]))]
    (is (= [240 0 60 50] r) "the right float is flush against the right edge")
    (is (= 50 (second after))
        "clear:both takes the LOWER of the two bottom edges (50, not 30)")))

(deftest clear-only-ever-pushes-a-box-down
  ;; Measured in Brave with two blocks ahead of the cleared one: the float's
  ;; bottom is 40 but the flow has already reached 48, and the browser
  ;; leaves the cleared box at 48. Clearance is a floor, not a position.
  (let [[_root _f _a _b below]
        (div-boxes (float-ops 300 [[:div {:float "left" :width 80 :height 40} "F"]
                                   [:div {} "one"] [:div {} "two"]
                                   [:div {:clear "left"} "below"]]))]
    (is (= 40 (second below))
        "the flow is already at 40 (two 20px rows), so clearing to the
         float's bottom edge of 40 moves nothing")))

(deftest clear-ignores-floats-on-the-other-side
  (let [[_root _f cleared]
        (div-boxes (float-ops 300 [[:div {:float "left" :width 80 :height 40} "F"]
                                   [:div {:clear "right"} "r"]]))]
    (is (= 0 (second cleared))
        "clear:right has nothing to clear against a LEFT float")))

(deftest an-ordinary-parent-does-not-contain-its-float
  ;; This engine used to make EVERY container at least as tall as its
  ;; floats, which is the easy half of the rule and the wrong one for the
  ;; common case. Brave reports 0 for the plain div and 60 once it
  ;; establishes a formatting context -- which is the entire reason the
  ;; `overflow: hidden` clearfix idiom exists.
  (let [[plain] (div-boxes (float-ops 200 [[:div {:float "left" :width 50 :height 60} "F"]]))
        [bfc] (div-boxes (float-ops 200 {:overflow "hidden"}
                                    [[:div {:float "left" :width 50 :height 60} "F"]]))
        [root-flow] (div-boxes (float-ops 200 {:display "flow-root"}
                                          [[:div {:float "left" :width 50 :height 60} "F"]]))]
    (is (= 0 (nth plain 3)) "the float escapes an ordinary block")
    (is (= 60 (nth bfc 3)) "`overflow: hidden` contains it")
    (is (= 60 (nth root-flow 3)) "and so does `display: flow-root`")))

(deftest an-escaping-float-keeps-rising-until-something-contains-it
  ;; The clearfix idiom does not require the `overflow: hidden` box to be
  ;; the float's own parent, and a first cut of the containment rule that
  ;; only looked at a container's DIRECT float children got this wrong: the
  ;; float stopped at the plain inner div, which does not contain it, and
  ;; then existed for nobody. Brave leaves the outer box 60px tall.
  ;;
  ;; Caught by the paint-order axis rather than by geometry: with the outer
  ;; box 0px tall, all 25 of that case's sample points landed on nothing at
  ;; all, which is the question "what would a user click" answering `none`
  ;; for a page that visibly has a float in it.
  (let [ops (float-ops 400 {:overflow "hidden"}
                       [[:div {:width 200}
                         [:div {:float "left" :width 50 :height 60} "F"]]])
        [outer inner f] (div-boxes ops)]
    (is (= 60 (nth outer 3))
        "the outer box establishes the formatting context, so it grows to
         hold a float two levels down")
    (is (= 0 (nth inner 3))
        "while the plain div in between still does not contain it")
    (is (= [0 0 50 60] f)))

  ;; ...and an escaped float is a full member of the band it rises into,
  ;; not merely a height contribution: it is there to be cleared, exactly
  ;; like one written at that level.
  (let [ops (float-ops 300 {:overflow "hidden"}
                       [[:div {} [:div {:float "left" :width 80 :height 40} "F"]]
                        "beside the escaped float"
                        [:div {:clear "left"} "below"]])
        beside (first (filter #(= "beside the escaped float" (:text %)) (text-draw-ops ops)))
        below (last (div-boxes ops))]
    (is (= 40 (second below)) "`clear` at the level it rose to sees it")
    ;; The one thing it does not get, and why. Whether a LONE inline child
    ;; flows as a run (and so consults the band) or takes a full-width
    ;; block row of its own is decided ONCE, before the loop, from whether
    ;; this container has a float CHILD -- and an escaped float is not a
    ;; child, it appears partway through the loop that is already running.
    ;; Deciding it correctly means asking "does any descendant hold a float
    ;; that will escape into me", which is a recursive re-derivation of the
    ;; formatting-context rule over the whole subtree, for a shape the
    ;; corpus does not contain. A float written at THIS level narrows a
    ;; lone text child correctly (see
    ;; a-float-is-placed-and-measured-by-its-margin-box); so does an
    ;; escaped one as soon as there are two inline children to flow.
    (is (= 0 (:x beside))
        "a LONE text child beside a RISEN float is not narrowed by it.
         Known cut; see this comment")))

(deftest a-float-does-not-split-the-inline-run-it-sits-inside
  ;; A float is blockified, so it never JOINS a line box -- but it must not
  ;; SPLIT one either. Grouping with the float still in the sequence would
  ;; partition `text <float> more` into two one-child runs and stack them
  ;; on two lines, where every browser keeps `text more` on one.
  (let [ops (float-ops 300 ["text " [:span {:float "left" :width 40 :height 20} "F"] " more"])
        flow (remove #(= "F" (:text %)) (text-draw-ops ops))]
    (is (= 1 (count (distinct (map :y flow))))
        "the text on either side of the float stays on ONE line")
    (is (every? #(= 40 (:x %)) flow)
        "beside the float, in the band it narrowed")))

(deftest an-inline-box-splits-around-a-block-child
  ;; Real CSS's `block-in-inline` fixup. `<p>text <span>a <div>b</div>
  ;; c</span> end</p>` is three lines in every browser -- `text a` / `b` /
  ;; `c end` -- because the inline box is split around the block. This
  ;; engine refused to flow the whole <span> at all once it saw the block
  ;; child, so the paragraph fell apart into five stacked rows.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        doc (build-inline-children doc p ["text " [:span {} "a " [:div {} "b"] " c"] " end"])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        t (text-draw-ops ops)
        lines (->> t (group-by :y) (sort-by key) (mapv (fn [[_ ops]] (->> ops (sort-by :x) (map :text) (str/join " ")))))]
    (is (= ["text a" "b" "c end"] lines)
        "the inline content before the block joins the preceding line, the
         block gets its own row, and the content after it starts a new
         line -- all still inside the same <span>")))

(deftest a-rowspan-cell-covers-its-rows
  ;; A spanning cell occupies the same column in the rows below it, and
  ;; those rows must SKIP that column. Without it the cells after it
  ;; shifted left into space a browser reserves, and the spanning cell was
  ;; only ever one row tall.
  (let [ops (table-ops [[:tr {} [:td {:rowspan "2"} "tall"] [:td {} "a"]]
                        [:tr {} [:td {} "b"]]])
        cells (filterv #(and (= :node (:draw/op %)) (= :td (:tag %))) ops)
        tall (first cells)
        [a b] (rest cells)]
    (is (> (:h tall) (:h a))
        "the spanning cell is taller than a single-row cell")
    (is (= (:x a) (:x b))
        "and the cell in the row below lands in the SECOND column, under
         `a`, because the first is still occupied by the spanning cell")
    (is (< (:x tall) (:x a)))))

(deftest a-table-cell-centres-its-content-vertically
  ;; `vertical-align: middle` is the UA default for a table cell, which is
  ;; what makes a rowspan cell sit BETWEEN the rows it covers rather than at
  ;; the top of the first one.
  (let [ops (table-ops [[:tr {} [:td {:rowspan "2"} "tall"] [:td {} "a"]]
                        [:tr {} [:td {} "b"]]])
        t (text-draw-ops ops)
        y-of (fn [s] (:y (first (filter #(= s (:text %)) t))))]
    (is (< (y-of "a") (y-of "tall") (y-of "b"))
        "the spanning cell's text sits between the two rows' own text")))

(deftest a-control-label-is-measured-in-the-control-font
  ;; A <button>'s label is measured in the CONTROL font (ua-control-font),
  ;; not the inherited page font -- measuring it with the page font left a
  ;; button ~14px narrow against the browser -- and its width includes the
  ;; UA horizontal padding (6px a side) and border, not the uniform value.
  (let [ops (inline-ops ["hit " [:button {} "go"] " now"] {} {:width 400})
        b (first (filter #(and (= :node (:draw/op %)) (= :button (:tag %))) ops))]
    (is (<= 26 (:w b) 36)
        "about a browser's 30.8px for `go`: the label plus 6px padding a
         side plus a 2px border a side")))

(deftest an-atomic-inline-carries-its-own-margins
  ;; A checkbox's UA `margin: 3px 3px 3px 4px` is the gap a reader sees
  ;; between the box and the label beside it. The browser puts it at x=4
  ;; y=3; this engine had it at 0,0 with the margins counted in the line's
  ;; advance but never applied to the box itself.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        [i doc] (dom/create-element doc :input)
        doc (dom/append-child doc p i)
        doc (dom/set-attribute doc i :type "checkbox")
        [t doc] (dom/create-text-node doc " agree")
        doc (dom/append-child doc p t)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        box (first (filter #(and (= :node (:draw/op %)) (= :input (:tag %))) ops))]
    (is (= {:x 4 :y 3 :w 13 :h 13} (select-keys box [:x :y :w :h]))
        "a bare 13x13 square, offset by its own UA margins")))

(deftest sub-and-sup-are-raised-and-lowered
  ;; `sub { vertical-align: sub }` / `sup { vertical-align: super }` are UA
  ;; rules -- an author writes the tag, never the declaration -- so without
  ;; them a subscript and a superscript sat on the same baseline as the text
  ;; around them, which is the entire visual point of both tags.
  (let [t (text-draw-ops (inline-ops ["H" [:sub {} "low"] "O and x" [:sup {} "high"] " end"]))
        y-of (fn [s] (:y (first (filter #(= s (:text %)) t))))]
    (is (< (y-of "high") (y-of "H"))
        "the superscript sits ABOVE the surrounding text...")
    (is (> (y-of "low") (y-of "H"))
        "...and the subscript below it")
    (is (< (y-of "high") (y-of "low")))))

(deftest an-empty-cell-does-not-swallow-its-table
  ;; A box with NO children took the whole container width for want of a
  ;; natural-width rule, so one empty `<td>` made its table fill the page:
  ;; the browser gives that cell 2px (its padding), this engine gave it 782.
  (let [ops (table-ops [[:tr {} [:td {} "a"] [:td {}] [:td {} "c"]]])
        cells (filterv #(and (= :node (:draw/op %)) (= :td (:tag %))) ops)
        table (first (filter #(and (= :node (:draw/op %)) (= :table (:tag %))) ops))]
    (is (< (:w (second cells)) 8)
        "the empty cell is its own padding wide, nothing more")
    (is (< (:w table) 60)
        "so the table shrink-wraps to its real content")))

(deftest a-nested-table-keeps-its-own-width
  ;; A `<td>` holding a nested `<table>` also fell back to the container
  ;; width. Measuring the cell's single element child fixes that -- but a
  ;; TABLE must be laid out rather than recursed into, because it already
  ;; shrink-wraps itself and recursion loses its border-spacing (37px
  ;; against the browser's 41).
  (let [ops (table-ops [[:tr {}
                         [:td {} [:table {} [:tr {} [:td {} "inner"]]]]
                         [:td {} "outer"]]])
        tables (filterv #(and (= :node (:draw/op %)) (= :table (:tag %))) ops)
        [outer inner] tables]
    (is (< (:w outer) 200) "the outer table shrink-wraps")
    (is (< (:w inner) (:w outer)))
    (is (> (:w inner) 20)
        "and the inner one keeps its own columns plus its border-spacing")))

(deftest a-bare-grid-span-keeps-auto-placement
  ;; `grid-column: span 2` declares only a WIDTH: the item stays
  ;; auto-placed and occupies two tracks, and the cursor resumes after it.
  (let [[g doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc g)
        doc (dom/set-style doc g {:display "grid" :grid-template-columns "80px 80px 80px"})
        doc (build-inline-children doc g [[:div {:grid-column "span 2"} "wide"] [:div {} "c"] [:div {} "d"]])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        items (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops)
        [_ wide c d] items]
    (is (= 160 (:w wide)) "two 80px tracks")
    (is (= 160 (:x c)) "the next item resumes after it, in the third track")
    (is (= 0 (:x d)) "and the one after that wraps to the next row")
    (is (< (:y c) (:y d)))))

(deftest position-relative-moves-a-flex-item
  ;; A paint-time shift from the item's own normal position, exactly as for
  ;; a block child. This engine applied it only in block flow -- a
  ;; scope-cut documented since relative positioning landed.
  (let [[row doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc row)
        doc (dom/set-style doc row {:display "flex"})
        doc (build-inline-children doc row [[:div {:position "relative" :top 8} "moved"] [:div {} "still"]])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        items (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops)
        [_ moved still] items]
    (is (= 8 (:y moved)))
    (is (= 0 (:y still)) "and its sibling does not move with it")))

(deftest overflow-wrap-breaks-a-word-that-cannot-fit
  ;; A long unbroken string -- a URL, a hash, a compound word -- is made to
  ;; fit a narrow column instead of overflowing it. Without this the engine
  ;; put the whole word on one overflowing line: a 90px column reported
  ;; 40px of height where the browser needs 60.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        doc (dom/set-style doc p {:overflow-wrap "break-word" :width 90})
        [t doc] (dom/create-text-node doc "short aaaaaaaaaaaaaaaaaaaa")
        doc (dom/append-child doc p t)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        t (text-draw-ops ops)]
    (is (> (count t) 2)
        "the long word is split across lines rather than overflowing on one")
    (is (every? #(<= (count (:text %)) 12) t)
        "and every piece fits the column")))

(deftest a-grid-item-stretches-to-its-track
  (let [[g doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc g)
        doc (dom/set-style doc g {:display "grid" :grid-template-columns "80px 80px"
                                  :grid-template-rows "40px 40px"})
        doc (build-inline-children doc g [[:div {} "a"] [:div {} "b"] [:div {} "c"] [:div {} "d"]])
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})
        items (rest (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops))]
    (is (every? #(= 40 (:h %)) items)
        "`align-items: stretch` is the default, so an item in a 40px track
         is 40px tall whatever its content needs")))

;; ---- margin collapsing: a collapsed-out margin still separates siblings ----

(defn- block-boxes
  "Every element :node box in `specs` (see build-inline-children), laid out
   as children of a root <div> with the engine's own theme padding/gap
   turned off so the numbers are pure CSS."
  [specs]
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        doc (build-inline-children doc root specs)
        [_ doc] (dom/consume-ops doc)
        ops (layout/draw-ops (dom/tree doc) {:width 400 :theme {:padding 0 :gap 0}})]
    (mapv #(select-keys % [:tag :x :y :w :h])
          (rest (filterv #(= :node (:draw/op %)) ops)))))

(deftest a-margin-that-collapses-out-of-a-box-still-separates-it-from-its-siblings
  ;; Measured in Chrome on this exact markup: the middle <div> is at y=34
  ;; and the last at y=68, i.e. the inner <p>'s 1em (14px at the harness's
  ;; 14px font) margins separating three divs that have no margin of their
  ;; own. A collapsed margin MOVES outside its box in real CSS; this engine
  ;; used to drop it, stacking the divs flush at 20 and 40.
  (let [[a b p c] (block-boxes [[:div {} "x"] [:div {} [:p {} "y"]] [:div {} "z"]])]
    (is (= 0 (:y a)))
    (is (= 20 (:h a)))
    (is (= 34 (:y b))
        "the inner <p>'s top margin collapses THROUGH its parent and pushes
         that parent 14px away from the sibling above it (20 + 14), which
         is the browser's own number for this markup")
    (is (= (:y b) (:y p))
        "...and the <p> itself still sits at its parent's own top edge")
    (is (= 68 (:y c))
        "the same <p>'s BOTTOM margin collapses out the other side and
         separates its parent from the sibling below (34 + 20 + 14)")))

(deftest a-margin-does-not-collapse-out-through-padding-or-a-border
  (let [[wrapper p] (block-boxes [[:div {:padding-top 5} [:p {} "y"]]])]
    (is (= 0 (:y wrapper)))
    (is (= 19 (:y p))
        "5px of padding separates the edges, so the <p>'s own margin stays
         INSIDE its parent instead of collapsing through it"))
  ;; ...and the two sides are decided independently: padding on the BOTTOM
  ;; does not stop the top margin collapsing through the top edge, which is
  ;; what the single combined flag this replaced used to do.
  (let [[wrapper p] (block-boxes [[:div {:padding-bottom 5} [:p {} "y"]]])]
    (is (= 0 (:y wrapper)))
    (is (= 0 (:y p))
        "padding-BOTTOM has nothing to do with the top edge")))

;; ---- UA stylesheet: a nested list has no vertical margin ----

(deftest a-nested-list-has-no-vertical-margin-of-its-own

  ;; Chrome's UA sheet cancels it (`:is(ul,ol) ul { margin-block: 0 }`).
  ;; Measured on <ul><li>a<ul><li>b</li></ul></li></ul>: the inner <li>
  ;; sits at y=20, directly under the "a" line, where this engine put it at
  ;; y=34 -- a full 1em margin the browser does not have.
  (let [boxes (block-boxes [[:ul {} [:li {} "a" [:ul {} [:li {} "b"]]]]])
        inner-ul (nth boxes 2)
        inner-li (nth boxes 3)]
    (is (= :ul (:tag inner-ul)))
    (is (= 20 (:y inner-ul)) "the sublist starts directly under its own <li>'s text")
    (is (= 20 (:y inner-li)))))

(deftest the-nested-list-rule-is-a-descendant-rule-not-a-child-rule

  ;; `:is(ul,ol) ul` matches through ANY intervening element, so a sublist
  ;; wrapped in a <div> inside the <li> is zeroed too -- which is why the
  ;; mark is inherited down rather than written onto direct children only.
  (let [boxes (block-boxes [[:ul {} [:li {} "a" [:div {} [:ul {} [:li {} "b"]]]]]])
        inner-ul (first (filter #(and (= :ul (:tag %)) (pos? (:y %))) boxes))]
    (is (= 20 (:y inner-ul))))
  ;; ...while a TOP-LEVEL list keeps its 1em margins, which is what makes
  ;; the rule a rule and not a blanket removal.
  (let [[ul _li] (block-boxes [[:ul {} [:li {} "a"]]])]
    (is (= 0 (:y ul)) "its own top margin collapses out to the root")
    (is (= 20 (:h ul)))))

;; ---- <select>: the UA box a real browser gives it ----

(defn- select-boxes
  "Element boxes for a `<select>` laid out as an INLINE atomic, which is
   what gives it its intrinsic size -- a block-level form control still
   fills its container (documented in atomic-intrinsic-width), so the
   surrounding text is load-bearing, not decoration."
  [attrs option-labels]
  (rest (block-boxes [[:div {} "a "
                       (into [:select attrs] (map (fn [l] [:option {} l]) option-labels))
                       " b"]])))

(deftest a-closed-select-is-its-widest-option-plus-a-fixed-dropdown-arrow

  ;; Measured in Chrome: an EMPTY select is 22px wide at every font size,
  ;; and each option label adds ceil() of its own rendered width -- 22+33
  ;; for `alpha` (32.63px in the control font). 22 = 2 borders + a 20px
  ;; arrow. This engine used to charge a per-character estimate with no
  ;; arrow at all, which made every select several px too narrow.
  (let [[empty-select] (select-boxes {} [])
        [one-option] (select-boxes {} ["abcd"])]
    (is (== 22 (:w empty-select))
        "border + arrow with no text at all")
    (is (> (:w one-option) 22)
        "and an option label adds its own measured width on top")))

(deftest an-open-listbox-is-size-rows-tall-and-paints-a-box-per-option

  ;; Measured in Chrome, a `<select multiple>` is a completely different
  ;; box: no dropdown arrow, `size` option ROWS tall (HTML's default 4 --
  ;; a `size="5"` listbox holding ONE option still reserves 5), and every
  ;; <option> gets a real box inset by the 1px border. This engine reported
  ;; no option box at all.
  (let [boxes (select-boxes {:multiple true :size "2"} ["a" "b"])
        [sel o1 o2] boxes]
    (is (= :select (:tag sel)))
    (is (= :option (:tag o1)))
    (is (= :option (:tag o2)))
    (is (= 2 (count (filter #(= :option (:tag %)) boxes))))
    (is (= (+ (:y sel) 1) (:y o1)) "first row sits just inside the border")
    (is (= (- (:y o2) (:y o1)) (:h o1)) "rows stack by exactly one row height")
    (is (= (:h sel) (+ 2 (* 2 (:h o1))))
        "size=\"2\" reserves exactly two rows plus the two 1px borders"))
  (let [[sel] (select-boxes {:multiple true :size "5"} ["a"])
        [two-row] (select-boxes {:multiple true :size "2"} ["a"])]
    (is (> (:h sel) (:h two-row))
        "the reserved height follows `size`, not the number of options")))

;; ---- a control keeps its own UA border/box ----

(deftest a-control-keeps-the-ua-border-and-box-a-browser-gives-it

  ;; The UA control box is what makes these agree with a browser at all --
  ;; an empty <select> is 22px wide (2 borders + a 20px arrow) and an
  ;; <input> 21px tall, both measured in Chrome.
  (let [[empty-select] (select-boxes {} [])
        [_div input] (block-boxes [[:div {} "a " [:input {:value "hi"}] " b"]])]
    (is (== 22 (:w empty-select)))
    (is (= :input (:tag input)))
    (is (= 21 (:h input)))))

;; ---- grid: auto tracks, implicit tracks, column flow, item alignment ----
;;
;; Every number below was measured in a real headless Brave over CDP before
;; the behaviour was written (the conformance corpus carries the same shapes
;; as `grid/auto-*`, `grid/justify-*`, `grid/align-*`); these pin the
;; arithmetic that produced them so a regression names itself.

(defn- grid-item-boxes
  "Element :node boxes for a display:grid `<div>` with `container-style` and
   one `<div>` per `specs` entry, with the engine's own theme padding/gap
   turned off so the numbers are pure CSS. The container's own box is
   dropped -- callers here are always asking about the items."
  ([container-style specs] (grid-item-boxes container-style specs 400))
  ([container-style specs width]
   (let [[g doc] (dom/create-element dom/empty-document :div)
         doc (dom/set-root doc g)
         doc (dom/set-style doc g (merge {:display "grid"} container-style))
         doc (build-inline-children doc g specs)
         [_ doc] (dom/consume-ops doc)
         ops (layout/draw-ops (dom/tree doc) {:width width :theme {:padding 0 :gap 0}})]
     (mapv #(select-keys % [:x :y :w :h])
           (rest (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops))))))

(deftest auto-tracks-grow-to-max-content-then-share-the-rest-equally
  ;; Brave: `auto auto` in a 400px grid holding `short` (max-content
  ;; 41.6px) and `a much longer cell` (145.3px) comes out 148.2 / 251.8 --
  ;; each track's own max-content plus exactly half of the leftover. The
  ;; two tracks were ZERO wide before `auto` was a track type at all.
  (let [[a b] (grid-item-boxes {:grid-template-columns "auto auto" :width 400}
                               [[:div {} "short"] [:div {} "a much longer cell"]])]
    (is (pos? (:w a)) "an auto track is not zero-width")
    (is (= 400 (+ (:w a) (:w b))) "the two tracks fill the container")
    (is (= (:x b) (:w a)) "and abut with no gap")
    (is (< (:w a) (:w b)) "the track holding more text is the wider one")
    (is (= (- (:w b) (:w a))
           (- (- (:w b) (quot (- 400 (+ (:w a) (:w b)) 0) 2))
              (- (:w a) (quot (- 400 (+ (:w a) (:w b)) 0) 2))))
        "the leftover was shared EQUALLY, so the difference between the two
         tracks is exactly the difference between their contents")))

(deftest an-auto-track-does-not-stretch-when-an-fr-track-competes-for-the-space
  ;; Measured in Brave: `auto 1fr` at 400px leaves the auto track at its own
  ;; 41.6px max-content and gives the fr track the other 358. With `auto
  ;; 100px` -- no fr -- the same auto track stretches to 300 instead.
  (let [[with-fr] (grid-item-boxes {:grid-template-columns "auto 1fr" :width 400}
                                   [[:div {} "short"] [:div {} "x"]])
        [with-fixed] (grid-item-boxes {:grid-template-columns "auto 100px" :width 400}
                                      [[:div {} "short"] [:div {} "x"]])]
    (is (< (:w with-fr) 200) "the fr track takes the leftover")
    (is (= 300 (:w with-fixed)) "with no fr track the auto one stretches to fill")))

(deftest an-auto-track-floors-at-min-content-and-overflows-a-narrow-grid
  ;; A browser overflows rather than crushing an unbreakable word: two auto
  ;; tracks holding 41.6px and 50.1px of word are 91.7px of content in a
  ;; 60px box. An engine that only divides the available width gives 30/30.
  (let [[a b] (grid-item-boxes {:grid-template-columns "auto auto" :width 60}
                               [[:div {} "short"] [:div {} "a much longer cell"]] 200)]
    (is (> (+ (:w a) (:w b)) 60) "the tracks overflow the container")
    (is (> (:w a) 30) "neither is crushed to an equal share")))

(deftest grid-auto-flow-column-fills-a-column-before-moving-right
  ;; Brave puts three items at x=0/70/140 on ONE row; this engine stacked
  ;; them vertically at the container's full width.
  (let [[a b c] (grid-item-boxes {:grid-auto-flow "column" :grid-auto-columns "70px"}
                                 [[:div {} "a"] [:div {} "b"] [:div {} "c"]])]
    (is (= [0 70 140] [(:x a) (:x b) (:x c)]))
    (is (= [0 0 0] [(:y a) (:y b) (:y c)]))
    (is (every? #(= 70 (:w %)) [a b c]) "each implicit column takes grid-auto-columns"))
  ;; With two explicit ROWS the flow fills a column top-to-bottom first.
  (let [[a b c] (grid-item-boxes {:grid-auto-flow "column"
                                  :grid-template-rows "30px 30px"
                                  :grid-auto-columns "50px"}
                                 [[:div {} "a"] [:div {} "b"] [:div {} "c"]])]
    (is (= [0 0] [(:x a) (:y a)]))
    (is (= [0 30] [(:x b) (:y b)]) "second item goes DOWN, not right")
    (is (= [50 0] [(:x c) (:y c)]) "third starts the next column")))

(deftest grid-auto-rows-sizes-the-implicit-rows
  ;; `grid-auto-rows: 40px` was read nowhere: two items in a single 60px
  ;; column were 20px tall each where the browser reports 40.
  (let [[a b] (grid-item-boxes {:grid-template-columns "60px" :grid-auto-rows "40px"}
                               [[:div {} "a"] [:div {} "b"]])]
    (is (= 40 (:h a)))
    (is (= 40 (:y b)))
    (is (= 40 (:h b))))
  ;; and only the IMPLICIT ones -- the explicit 20px track keeps its size
  (let [[a b c] (grid-item-boxes {:grid-template-columns "60px"
                                  :grid-template-rows "20px"
                                  :grid-auto-rows "40px"}
                                 [[:div {} "a"] [:div {} "b"] [:div {} "c"]])]
    (is (= 20 (:h a)))
    (is (= [20 40] [(:y b) (:h b)]))
    (is (= [60 40] [(:y c) (:h c)]))))

(deftest auto-rows-stretch-to-fill-a-definite-container-height
  ;; Brave: `grid-template-rows: 30px` with three items in a 200px-tall grid
  ;; gives 30 / 85 / 85 -- the explicit track keeps its size and the two
  ;; implicit auto rows share the remaining 170 equally.
  (let [[a b c] (grid-item-boxes {:grid-template-columns "60px"
                                  :grid-template-rows "30px" :height 200}
                                 [[:div {} "a"] [:div {} "b"] [:div {} "c"]])]
    (is (= 30 (:h a)))
    (is (= (:h b) (:h c)) "the two implicit rows share the rest equally")
    (is (= 200 (+ (:h a) (:h b) (:h c))))
    (is (= [30 115] [(:y b) (:y c)])))
  ;; but an fr row takes the leftover instead of the auto row stretching
  (let [[a b] (grid-item-boxes {:grid-template-columns "60px"
                                :grid-template-rows "auto 1fr" :height 200}
                               [[:div {} "a"] [:div {} "b"]])]
    (is (= 20 (:h a)) "the auto row stays at its content height")
    (is (= 180 (:h b)))))

(deftest row-gap-and-column-gap-longhands-space-the-two-axes-independently
  ;; Brave puts the second column at x=68 and the second row at y=44 for
  ;; `row-gap: 24px; column-gap: 8px`. Both longhands were unread, so the
  ;; grid used the theme gap of 0 on both axes.
  (let [[_ b c _] (grid-item-boxes {:grid-template-columns "60px 60px"
                                    :row-gap 24 :column-gap 8}
                                   [[:div {} "a"] [:div {} "b"] [:div {} "c"] [:div {} "d"]])]
    (is (= 68 (:x b)))
    (is (= 44 (:y c))))
  ;; `gap: <row> <column>` is the same two numbers, row first
  (let [[_ b c _] (grid-item-boxes {:grid-template-columns "60px 60px" :gap "24px 8px"}
                                   [[:div {} "a"] [:div {} "b"] [:div {} "c"] [:div {} "d"]])]
    (is (= 68 (:x b)))
    (is (= 44 (:y c))))
  ;; and a one-value `gap` still sets both, which is what every
  ;; previously-passing single-gap case relies on
  (let [[_ b c _] (grid-item-boxes {:grid-template-columns "60px 60px" :gap 10}
                                   [[:div {} "a"] [:div {} "b"] [:div {} "c"] [:div {} "d"]])]
    (is (= 70 (:x b)))
    (is (= 30 (:y c)))))

(deftest justify-items-sizes-the-item-to-its-content-and-places-it-in-the-track
  ;; Brave: a one-character item in a 120px column is 9.2px wide, at x=55.4
  ;; under `center` and x=110.8 under `end`. Under the default `stretch` it
  ;; is the full 120 at x=0, which is what this engine always gave.
  (let [[stretched] (grid-item-boxes {:grid-template-columns "120px"} [[:div {} "a"]])
        [centred] (grid-item-boxes {:grid-template-columns "120px" :justify-items "center"}
                                   [[:div {} "a"]])
        [ended] (grid-item-boxes {:grid-template-columns "120px" :justify-items "end"}
                                 [[:div {} "a"]])]
    (is (= [0 120] [(:x stretched) (:w stretched)]))
    (is (< (:w centred) 120) "a non-stretch item is fit-content, not track-width")
    (is (= (:x centred) (quot (- 120 (:w centred)) 2)))
    (is (= (:x ended) (- 120 (:w ended))))
    (is (= (:w centred) (:w ended)) "the size does not depend on where it lands")))

(deftest justify-self-overrides-justify-items-for-one-item-only
  (let [[own other] (grid-item-boxes {:grid-template-columns "120px 120px"}
                                     [[:div {:justify-self "center"} "a"] [:div {} "bb"]])]
    (is (< (:w own) 120))
    (is (= (:x own) (quot (- 120 (:w own)) 2)))
    (is (= [120 120] [(:x other) (:w other)])
        "its sibling keeps the container's stretch")))

(deftest align-items-positions-an-item-in-a-taller-row-instead-of-stretching-it
  ;; Brave: `align-items: center` on a 60px row leaves a 20px item at y=20;
  ;; `end` at y=40. Stretch (the default) makes it 60 tall at y=0.
  (let [[stretched] (grid-item-boxes {:grid-template-columns "80px" :grid-template-rows "60px"}
                                     [[:div {} "mid"]])
        [centred] (grid-item-boxes {:grid-template-columns "80px" :grid-template-rows "60px"
                                    :align-items "center"}
                                   [[:div {} "mid"]])
        [ended] (grid-item-boxes {:grid-template-columns "80px" :grid-template-rows "60px"
                                  :align-items "end"}
                                 [[:div {} "mid"]])
        [self] (grid-item-boxes {:grid-template-columns "80px" :grid-template-rows "60px"}
                                [[:div {:align-self "center"} "mid"]])]
    (is (= [0 60] [(:y stretched) (:h stretched)]))
    (is (= 20 (:h centred)) "a non-stretch item keeps its own height")
    (is (= (:y centred) (quot (- 60 (:h centred)) 2)))
    (is (= (:y ended) (- 60 (:h ended))))
    (is (= [(:y centred) (:h centred)] [(:y self) (:h self)])
        "align-self resolves the same way when the container says nothing")))

(deftest an-explicit-row-does-not-move-the-auto-placement-cursor
  ;; CSS Grid 8.5 runs the auto-placement cursor in step 4, which only ever
  ;; sees items with a definite COLUMN or none at all -- an item locked to a
  ;; row is placed in step 2 and never touches it. Measured in Brave,
  ;; `grid-row: 2` on the first of two items leaves the second at row 1
  ;; column 1; this engine advanced the cursor past the explicit item and
  ;; put them on the same row.
  (let [[t b] (grid-item-boxes {:grid-template-columns "70px 70px"
                                :grid-template-rows "30px 30px"}
                               [[:div {:grid-row 2} "t"] [:div {} "b"]])]
    (is (= [0 30] [(:x t) (:y t)]))
    (is (= [0 0] [(:x b) (:y b)]) "the auto item starts at the first cell"))
  ;; an explicit COLUMN still moves it, which is the behaviour this must
  ;; not have broken
  (let [[t b c] (grid-item-boxes {:grid-template-columns "70px 70px"}
                                 [[:div {:grid-column 2} "t"] [:div {} "b"] [:div {} "c"]])]
    (is (= 70 (:x t)))
    (is (= [0 20] [(:x b) (:y b)]) "the next auto item wraps past the cursor")
    (is (= [70 20] [(:x c) (:y c)]))))

(deftest inline-grid-shrink-wraps-to-its-tracks-and-stays-on-the-line
  ;; Brave keeps `before <inline-grid> after` on one 20px line with the grid
  ;; 60px wide (two 30px tracks). This engine gave it a block row of its own
  ;; and produced three lines -- the grid side of the gap `inline-flex`
  ;; closed earlier.
  (let [ops (inline-ops ["before "
                         [:span {:display "inline-grid" :grid-template-columns "30px 30px"}
                          [:span {} "a"] [:span {} "b"]]
                         " after"]
                        {}
                        {:width 480 :theme {:padding 0 :gap 0}})
        container (first (filterv #(and (= :node (:draw/op %)) (= :div (:tag %))) ops))
        grid (first (filterv #(and (= :node (:draw/op %)) (= :span (:tag %))) ops))
        sentence (filterv #(contains? #{"before" "after"} (:text %)) (text-draw-ops ops))]
    (is (= 60 (:w grid)) "two 30px tracks, not the container's width")
    (is (= 20 (:h container)) "the whole sentence is ONE line box, not three")
    (is (apply = (map :y sentence)) "and the words either side of it share it")
    (is (< (:x (first sentence)) (:x grid) (:x (second sentence)))
        "with the grid between them rather than above or below")))

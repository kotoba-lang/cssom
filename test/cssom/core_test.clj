(ns cssom.core-test
  (:require [cssom.core :as css]
            [cssom.layout :as layout]
            [clojure.test :refer [deftest is]]
            [htmldom.core :as html]
            [kotoba.wasm.dom :as dom]))

(deftest parses-simple-selector-rules
  (let [rules (css/parse-rules "main.note, #hero { color: red; padding: 8px } .muted { color: gray }")]
    (is (= 2 (count rules)))
    (is (= {:color "red" :padding 8
            ;; `padding: 8px` now also expands to its four per-side
            ;; longhands (real CSS's 1-to-4 value rule), which is what makes
            ;; the one-axis UA rules (`p { margin: 1em 0 }`) expressible at
            ;; all. The uniform key stays for every existing reader.
            :padding-top 8 :padding-right 8 :padding-bottom 8 :padding-left 8}
           (:rule/declarations (first rules))))
    (is (= :main (-> rules first :rule/selectors first :selector/parts first :selector/tag)))
    (is (= ["note"] (-> rules first :rule/selectors first :selector/parts first :selector/classes)))))

(deftest parses-attribute-selectors-and-important-declarations
  (let [rules (css/parse-rules "input[required], [data-mode=\"edit\"] { border-width: 2px !important; color: red }")]
    ;; `border-width` is a 1-to-4 shorthand over the four sides, like
    ;; `margin`/`padding`, so it expands and keeps the uniform key beside
    ;; the longhands (see `expand-border-box-shorthand`).
    (is (= {:border-width 2 :border-top-width 2 :border-right-width 2
            :border-bottom-width 2 :border-left-width 2 :color "red"}
           (:rule/declarations (first rules))))
    (is (= [{:attr/name :required :attr/operator nil :attr/value nil :attr/case-insensitive? false}]
           (-> rules first :rule/selectors first :selector/parts first :selector/attrs)))
    (is (= [{:attr/name :data-mode :attr/operator "=" :attr/value "edit" :attr/case-insensitive? false}]
           (-> rules first :rule/selectors second :selector/parts first :selector/attrs)))
    (is (= true (get-in (first rules) [:rule/declaration-meta :border-width :important?])))))

;; ---- `border` shorthand expansion ----

(defn- border-longhands
  "The twelve per-side border longhands a `border` shorthand writes, as one
   map, so a test can name the three values once rather than twelve times."
  [w st c]
  (into {} (for [side ["top" "right" "bottom" "left"]
                 [sub v] [["width" w] ["style" st] ["color" c]]]
             [(keyword (str "border-" side "-" sub)) v])))

(deftest border-shorthand-expands-into-its-three-longhands
  ;; The confirmed repro from the bug report: before this, `border` was
  ;; stored verbatim as a single :border key, which border-ops's own
  ;; :border-width/:border-color lookups never recognize -- a real,
  ;; extremely common author pattern like `border: 2px solid red`
  ;; silently painted no border at all.
  ;;
  ;; Since 2026-08-06 it writes the twelve PER-SIDE longhands as well as
  ;; the three uniform keys, which is what real CSS's `border` sets. Those
  ;; twelve are not decoration: they are how declaration ORDER resolves,
  ;; and without them a `border` could not overwrite an earlier
  ;; `border-top` -- see `expand-border-shorthand-with-sides`.
  (let [rules (css/parse-rules "#f { border: 2px solid #00ff00 }")]
    (is (= (merge {:border-width 2 :border-style "solid" :border-color "#00ff00"}
                  (border-longhands 2 "solid" "#00ff00"))
           (:rule/declarations (first rules))))
    (is (not (contains? (:rule/declarations (first rules)) :border))
        "no bare :border key should remain -- it's fully expanded")))

(deftest border-shorthand-is-order-independent-per-real-css-grammar
  (let [rules (css/parse-rules "#f { border: red 3px dashed }")]
    (is (= (merge {:border-color "red" :border-width 3 :border-style "dashed"}
                  (border-longhands 3 "dashed" "red"))
           (:rule/declarations (first rules))))))

(deftest border-shorthand-omits-whichever-longhands-it-does-not-specify
  (let [rules (css/parse-rules "#f { border: solid red }")]
    (is (= (merge {:border-style "solid" :border-color "red"}
                  ;; the UNIFORM keys record only what was written -- but
                  ;; each SIDE gets all three, because a shorthand resets
                  ;; the components it omits to their initial values.
                  ;; Measured in Brave 151 on 2026-08-06: `border: solid`
                  ;; on a 300px block is 306 wide with its <p> at (3,3),
                  ;; i.e. the omitted width IS `medium` = 3px, not 0.
                  (border-longhands "medium" "solid" "red"))
           (:rule/declarations (first rules)))
        "a real, legal border shorthand may omit the width entirely")))

(deftest per-side-border-shorthands-expand-into-that-sides-three-longhands
  ;; The declaration cssom.layout could not see at all before 2026-08-06:
  ;; `border-top` fell through to the generic path and was stored as the
  ;; raw string "10px solid", which nothing reads. Brave 151: a 300px block
  ;; with `border-top: 10px solid` is 26.797 tall with its <p> at y=10.
  (is (= {:border-top-width 10 :border-top-style "solid" :border-top-color "#000"}
         (:rule/declarations (first (css/parse-rules "#f { border-top: 10px solid #000 }")))))
  ;; all three of the side's longhands are written even when the value
  ;; names one, because that is what a shorthand does. Brave:
  ;; `border-top: 10px` is 300x16.797 (style `none` zeroes the width) and
  ;; `border-top: solid` is 300x19.797 (width `medium` = 3px).
  (is (= {:border-top-width 10 :border-top-style "none" :border-top-color "currentcolor"}
         (:rule/declarations (first (css/parse-rules "#f { border-top: 10px }")))))
  (is (= {:border-top-width "medium" :border-top-style "solid" :border-top-color "currentcolor"}
         (:rule/declarations (first (css/parse-rules "#f { border-top: solid }")))))
  ;; ...which is what makes a LATER shorthand overwrite an earlier
  ;; longhand. Brave resolves this pair to 2px, not 9px.
  (is (= 2 (:border-top-width
            (:rule/declarations
             (first (css/parse-rules "#f { border-top-width: 9px; border-top: 2px solid }")))))))

(deftest border-width-style-and-color-are-one-to-four-shorthands
  ;; Measured in Brave 151 on 2026-08-06: `border-width: 10px 5px` with
  ;; `border-style: solid` gives 10/5/10/5 and a 310x36.797 box, and
  ;; `border-style: solid none` with `border-width: 10px` gives 10/0/10/0 --
  ;; the STYLE shorthand carries per side too and zeroes the width on the
  ;; sides it says `none` on.
  (is (= {:border-width 10 :border-top-width 10 :border-right-width 5
          :border-bottom-width 10 :border-left-width 5}
         (:rule/declarations (first (css/parse-rules "#f { border-width: 10px 5px }")))))
  (is (= {:border-style "solid" :border-top-style "solid" :border-right-style "none"
          :border-bottom-style "solid" :border-left-style "none"}
         (:rule/declarations (first (css/parse-rules "#f { border-style: solid none }")))))
  (is (= {:border-color "red" :border-top-color "red" :border-right-color "blue"
          :border-bottom-color "red" :border-left-color "blue"}
         (:rule/declarations (first (css/parse-rules "#f { border-color: red blue }")))))
  ;; the three named <line-width> values ride through as KEYWORDS -- this
  ;; namespace holds specified values and cssom.layout resolves them.
  ;; Brave: `border-width: thin medium thick 0` reports 1px/3px/5px/0px.
  (is (= {:border-width "thin" :border-top-width "thin" :border-right-width "medium"
          :border-bottom-width "thick" :border-left-width 0}
         (:rule/declarations (first (css/parse-rules "#f { border-width: thin medium thick 0 }")))))
  ;; a value with a token this cannot classify is left untouched for the
  ;; generic path, the same degrade-don't-guess posture as every other
  ;; expander here
  (is (= {:border-style "wobbly"}
         (:rule/declarations (first (css/parse-rules "#f { border-style: wobbly }"))))))

(deftest border-shorthand-importance-applies-to-every-expanded-longhand
  (let [rules (css/parse-rules "#f { border: 2px solid red !important }")]
    (is (= true (get-in (first rules) [:rule/declaration-meta :border-width :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :border-style :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :border-color :important?])))))

(deftest border-longhands-declared-separately-are-unaffected-by-shorthand-expansion
  (let [rules (css/parse-rules "#f { border-width: 2px; border-color: #00ff00 }")]
    (is (= {:border-width 2 :border-top-width 2 :border-right-width 2
            :border-bottom-width 2 :border-left-width 2
            :border-color "#00ff00" :border-top-color "#00ff00"
            :border-right-color "#00ff00" :border-bottom-color "#00ff00"
            :border-left-color "#00ff00"}
           (:rule/declarations (first rules))))
    (is (not (contains? (:rule/declarations (first rules)) :border-style))
        "neither shorthand names a style, and neither invents one")))

;; ---- `text-shadow` shorthand expansion ----

(deftest text-shadow-shorthand-expands-into-its-four-longhands
  ;; The confirmed repro from the bug report: before this, text-shadow
  ;; was stored verbatim as a single :text-shadow key, which
  ;; cssom.layout's layout-text never recognizes -- a real, common author
  ;; pattern like `text-shadow: 2px 2px 4px #000000` silently painted no
  ;; shadow at all.
  (let [rules (css/parse-rules "#f { text-shadow: 2px 3px 4px #000000 }")]
    (is (= {:text-shadow-x 2 :text-shadow-y 3 :text-shadow-blur 4 :text-shadow-color "#000000"}
           (:rule/declarations (first rules))))
    (is (not (contains? (:rule/declarations (first rules)) :text-shadow))
        "no bare :text-shadow key should remain -- it's fully expanded")))

(deftest text-shadow-shorthand-is-order-independent-per-real-css-grammar
  (let [rules (css/parse-rules "#f { text-shadow: red 2px 3px }")]
    (is (= {:text-shadow-color "red" :text-shadow-x 2 :text-shadow-y 3}
           (:rule/declarations (first rules))))))

(deftest text-shadow-shorthand-omits-whichever-longhands-it-does-not-specify
  (let [rules (css/parse-rules "#f { text-shadow: 2px 3px red }")]
    (is (= {:text-shadow-x 2 :text-shadow-y 3 :text-shadow-color "red"}
           (:rule/declarations (first rules)))
        "a real, legal text-shadow shorthand may omit the blur radius entirely")))

(deftest text-shadow-none-expands-to-a-real-sentinel-not-an-empty-declaration
  ;; text-shadow genuinely inherits in real CSS (unlike box-shadow), so
  ;; `text-shadow: none` must produce a REAL, present value an descendant
  ;; can see -- an empty declaration here would be indistinguishable from
  ;; text-shadow never having been declared at all, silently leaving an
  ;; ancestor's real shadow showing through when the intent was to cancel it.
  (let [rules (css/parse-rules "#f { text-shadow: none }")]
    (is (= {:text-shadow-color "none"}
           (:rule/declarations (first rules))))))

(deftest text-shadow-shorthand-importance-applies-to-every-expanded-longhand
  (let [rules (css/parse-rules "#f { text-shadow: 2px 3px 4px red !important }")]
    (is (= true (get-in (first rules) [:rule/declaration-meta :text-shadow-x :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :text-shadow-y :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :text-shadow-blur :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :text-shadow-color :important?])))))

;; ---- `box-shadow` shorthand expansion ----

(deftest box-shadow-shorthand-expands-into-its-four-longhands
  ;; The confirmed repro from the bug report: before this, box-shadow was
  ;; stored verbatim as a single :box-shadow key, which cssom.layout never
  ;; recognizes -- a real, common author pattern like
  ;; `box-shadow: 4px 4px 8px #000000` silently painted no shadow at all.
  (let [rules (css/parse-rules "#f { box-shadow: 4px 5px 8px #000000 }")]
    (is (= {:box-shadow-x 4 :box-shadow-y 5 :box-shadow-blur 8 :box-shadow-color "#000000"}
           (:rule/declarations (first rules))))
    (is (not (contains? (:rule/declarations (first rules)) :box-shadow))
        "no bare :box-shadow key should remain -- it's fully expanded")))

(deftest box-shadow-shorthand-is-order-independent-per-real-css-grammar
  (let [rules (css/parse-rules "#f { box-shadow: red 2px 3px }")]
    (is (= {:box-shadow-color "red" :box-shadow-x 2 :box-shadow-y 3}
           (:rule/declarations (first rules))))))

(deftest box-shadow-shorthand-omits-whichever-longhands-it-does-not-specify
  (let [rules (css/parse-rules "#f { box-shadow: 2px 3px red }")]
    (is (= {:box-shadow-x 2 :box-shadow-y 3 :box-shadow-color "red"}
           (:rule/declarations (first rules)))
        "a real, legal box-shadow shorthand may omit the blur radius entirely")))

(deftest box-shadow-none-expands-to-an-empty-declaration-unlike-text-shadow
  ;; Unlike text-shadow, box-shadow is NOT a real inherited CSS property --
  ;; there is no ancestor value to cancel, so `box-shadow: none` resolving
  ;; to an empty declaration (rather than text-shadow's own real, PRESENT
  ;; sentinel) is correct here, not an inconsistency.
  (let [rules (css/parse-rules "#f { box-shadow: none }")]
    (is (= {} (:rule/declarations (first rules))))))

(deftest box-shadow-shorthand-importance-applies-to-every-expanded-longhand
  (let [rules (css/parse-rules "#f { box-shadow: 2px 3px 4px red !important }")]
    (is (= true (get-in (first rules) [:rule/declaration-meta :box-shadow-x :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :box-shadow-y :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :box-shadow-blur :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :box-shadow-color :important?])))))

(deftest box-shadow-shorthand-fifth-token-is-spread-radius-not-color
  ;; The real bug this fixes: before this, a 4th length-shaped token (the
  ;; extremely common real-world 5-token box-shadow: 0 1px 2px 0 rgba(...)
  ;; shape -- Tailwind's/Material's/Bootstrap's own default shadows all use
  ;; exactly this) fell through into :box-shadow-color, and the REAL
  ;; trailing color token was then silently DROPPED entirely.
  (let [rules (css/parse-rules "#f { box-shadow: 0 1px 2px 0 rgba(0,0,0,0.1) }")]
    (is (= {:box-shadow-x 0 :box-shadow-y 1 :box-shadow-blur 2 :box-shadow-spread 0
            :box-shadow-color "rgba(0,0,0,0.1)"}
           (:rule/declarations (first rules)))
        "the 4th length token must become spread-radius, and the real color must survive")))

(deftest box-shadow-shorthand-with-a-nonzero-spread-radius
  (let [rules (css/parse-rules "#f { box-shadow: 2px 2px 4px 3px #ff0000 }")]
    (is (= {:box-shadow-x 2 :box-shadow-y 2 :box-shadow-blur 4 :box-shadow-spread 3
            :box-shadow-color "#ff0000"}
           (:rule/declarations (first rules))))))

;; ---- `outline` shorthand expansion ----

(deftest outline-shorthand-expands-into-its-three-longhands
  ;; The confirmed repro from the bug report: before this, outline was
  ;; never read at all -- a repo-wide grep found nothing but a handful of
  ;; unrelated comments -- so a real, common author pattern like
  ;; `outline: 2px solid #ff0000` silently painted no outline at all.
  (let [rules (css/parse-rules "#f { outline: 2px solid #ff0000 }")]
    (is (= {:outline-width 2 :outline-style "solid" :outline-color "#ff0000"}
           (:rule/declarations (first rules))))
    (is (not (contains? (:rule/declarations (first rules)) :outline))
        "no bare :outline key should remain -- it's fully expanded")))

(deftest outline-shorthand-is-order-independent-per-real-css-grammar
  (let [rules (css/parse-rules "#f { outline: red 3px dashed }")]
    (is (= {:outline-color "red" :outline-width 3 :outline-style "dashed"}
           (:rule/declarations (first rules))))))

(deftest outline-shorthand-omits-whichever-longhands-it-does-not-specify
  (let [rules (css/parse-rules "#f { outline: solid red }")]
    (is (= {:outline-style "solid" :outline-color "red"}
           (:rule/declarations (first rules)))
        "a real, legal outline shorthand may omit the width entirely")))

(deftest outline-shorthand-recognizes-the-auto-keyword-unique-to-outline-style
  ;; outline-style's own keyword set differs from border-style's --
  ;; "auto" (the UA-native focus-ring style) is valid for outline but not
  ;; border, and "hidden" is valid for border but not outline.
  (let [rules (css/parse-rules "#f { outline: auto }")]
    (is (= {:outline-style "auto"} (:rule/declarations (first rules))))))

(deftest outline-shorthand-importance-applies-to-every-expanded-longhand
  (let [rules (css/parse-rules "#f { outline: 2px solid red !important }")]
    (is (= true (get-in (first rules) [:rule/declaration-meta :outline-width :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :outline-style :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :outline-color :important?])))))

(deftest outline-longhands-declared-separately-are-unaffected-by-shorthand-expansion
  (let [rules (css/parse-rules "#f { outline-width: 2px; outline-color: #ff0000 }")]
    (is (= {:outline-width 2 :outline-color "#ff0000"}
           (:rule/declarations (first rules))))))

;; ---- `inset` shorthand expansion ----

(deftest inset-shorthand-expands-into-the-four-bare-side-longhands
  ;; `inset` is the shorthand for top/right/bottom/left, and its longhands
  ;; are the BARE side names -- not `inset-top`, which is why it does not
  ;; go through expand-box-side-shorthand. Before this it was stored
  ;; verbatim under an :inset key nothing reads, so an absolutely
  ;; positioned box declaring it fell back to its static position.
  ;; Measured in Brave 151: a `position:absolute; inset:10px 20px` box in
  ;; a 300x60 relative parent is at x=20 y=10 w=260 h=40, and this engine
  ;; reported 0,0,7x20 (`:position/inset-shorthand` in the conformance
  ;; corpus).
  (let [rules (css/parse-rules "#f { inset: 10px 20px }")]
    (is (= {:top 10 :right 20 :bottom 10 :left 20}
           (:rule/declarations (first rules))))
    (is (not (contains? (:rule/declarations (first rules)) :inset))
        "no bare :inset key should remain -- it's fully expanded")))

(deftest inset-shorthand-uses-the-same-one-to-four-value-rule
  (is (= {:top 5 :right 5 :bottom 5 :left 5}
         (:rule/declarations (first (css/parse-rules "#f { inset: 5px }")))))
  (is (= {:top 1 :right 2 :bottom 3 :left 2}
         (:rule/declarations (first (css/parse-rules "#f { inset: 1px 2px 3px }")))))
  (is (= {:top 1 :right 2 :bottom 3 :left 4}
         (:rule/declarations (first (css/parse-rules "#f { inset: 1px 2px 3px 4px }"))))))

(deftest inset-shorthand-admits-auto-which-is-its-initial-value
  ;; `inset: 0 auto` is a real authored form and `auto` is the property's
  ;; own initial value, so it is expanded and travels as a raw string --
  ;; exactly as a directly-declared `top: auto` already does.
  (is (= {:top 0 :right "auto" :bottom 0 :left "auto"}
         (:rule/declarations (first (css/parse-rules "#f { inset: 0 auto }"))))))

(deftest inset-shorthand-expands-a-percentage-as-the-raw-value
  ;; REVERSED on 2026-08-06, and the reason it was written the other way
  ;; round is gone rather than merely overruled. The old assertion said a
  ;; percentage must NOT be expanded, "the same reason margin/padding
  ;; decline one" -- and margin/padding declined one because nothing
  ;; downstream could resolve it. Both halves of that changed: cssom.layout
  ;; resolves a percentage margin/padding against the containing block's
  ;; inline size (`resolve-box-percentages`), and it has resolved a
  ;; percentage `top`/`left` against the containing block since
  ;; `:position/absolute-percentage-offsets` landed. So each side now
  ;; carries the raw `"10%"` exactly as `auto` already does, and the value
  ;; is resolved where the containing block exists.
  ;;
  ;; What is NOT claimed here: that the four sides resolve against the same
  ;; basis. They do not -- measured in Brave 151, `left: 50%` of a 200x60
  ;; containing block is 100 and `top: 50%` is 30, each against its own
  ;; axis, where a percentage MARGIN is of the inline size on all four
  ;; sides. Expansion is per-side either way.
  (is (= {:top "10%" :right "10%" :bottom "10%" :left "10%"}
         (:rule/declarations (first (css/parse-rules "#f { inset: 10% }")))))
  (is (= {:top "10%" :right "20%" :bottom "10%" :left "20%"}
         (:rule/declarations (first (css/parse-rules "#f { inset: 10% 20% }"))))))

(deftest inset-shorthand-still-declines-a-value-it-cannot-resolve
  ;; The degrade-don't-guess posture itself is unchanged: a token that is
  ;; neither a length, a percentage, a calc(), a var() nor `auto` leaves
  ;; the whole shorthand unexpanded rather than contributing a guess.
  (let [decls (:rule/declarations (first (css/parse-rules "#f { inset: 1px solid }")))]
    (is (not (contains? decls :top)))
    (is (contains? decls :inset))))

(deftest inset-longhands-declared-separately-are-unaffected-by-shorthand-expansion
  (is (= {:top 4 :left 8}
         (:rule/declarations (first (css/parse-rules "#f { top: 4px; left: 8px }"))))))

(deftest inset-shorthand-importance-applies-to-every-expanded-longhand
  (let [rules (css/parse-rules "#f { inset: 3px !important }")]
    (doseq [k [:top :right :bottom :left]]
      (is (= true (get-in (first rules) [:rule/declaration-meta k :important?]))))))

(deftest font-shorthand-expands-into-its-five-longhands
  ;; The confirmed repro: before this, font was never expanded at all --
  ;; stored verbatim as a single unrecognized :font key -- so a real,
  ;; common author pattern like `font: italic bold 14px/1.5 sans-serif`
  ;; silently set none of the 5 real longhands this shorthand expands to.
  (let [rules (css/parse-rules "#f { font: italic bold 14px/1.5 sans-serif }")]
    (is (= {:font-style "italic" :font-weight "bold" :font-size 14
            :line-height "1.5" :font-family "sans-serif"}
           (:rule/declarations (first rules))))
    (is (not (contains? (:rule/declarations (first rules)) :font))
        "no bare :font key should remain -- it's fully expanded")))

(deftest font-shorthand-is-order-independent-for-its-leading-style-and-weight-tokens
  (let [rules (css/parse-rules "#f { font: bold italic 16px monospace }")]
    (is (= {:font-weight "bold" :font-style "italic" :font-size 16 :font-family "monospace"}
           (:rule/declarations (first rules))))))

(deftest font-shorthand-omits-whichever-leading-longhands-it-does-not-specify
  (let [rules (css/parse-rules "#f { font: 12px Arial, sans-serif }")]
    (is (= {:font-size 12 :font-family "Arial, sans-serif"}
           (:rule/declarations (first rules)))
        "a real, legal font shorthand may omit style/weight/line-height entirely")))

(deftest font-shorthand-recognizes-a-numeric-font-weight
  (let [rules (css/parse-rules "#f { font: 700 16px monospace }")]
    (is (= {:font-weight 700 :font-size 16 :font-family "monospace"}
           (:rule/declarations (first rules)))
        "a bare 100-900 weight number is coerced the same way a plain font-weight: 700 declaration is")))

(deftest font-shorthand-skips-normal-and-variant-stretch-keywords-without-consuming-family
  (let [rules (css/parse-rules "#f { font: normal small-caps condensed 16px monospace }")]
    (is (= {:font-size 16 :font-family "monospace"}
           (:rule/declarations (first rules)))
        "normal/font-variant/font-stretch keywords are consumed and dropped, not mis-parsed as family")))

(deftest font-shorthand-preserves-a-multi-word-quoted-family-and-comma-separated-fallbacks
  (let [rules (css/parse-rules "#f { font: bold 12px/1.4 'Times New Roman', serif }")]
    (is (= {:font-weight "bold" :font-size 12 :line-height "1.4" :font-family "'Times New Roman', serif"}
           (:rule/declarations (first rules))))))

(deftest font-shorthand-missing-a-mandatory-font-size-degrades-to-no-op
  (let [rules (css/parse-rules "#f { font: sans-serif }")]
    (is (= {} (:rule/declarations (first rules)))
        "a real font shorthand missing its mandatory font-size is entirely invalid -- dropped, not partially applied")))

(deftest font-shorthand-importance-applies-to-every-expanded-longhand
  (let [rules (css/parse-rules "#f { font: italic bold 14px/1.5 sans-serif !important }")]
    (is (= true (get-in (first rules) [:rule/declaration-meta :font-style :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :font-weight :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :font-size :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :line-height :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :font-family :important?])))))

(deftest font-longhands-declared-separately-are-unaffected-by-shorthand-expansion
  (let [rules (css/parse-rules "#f { font-size: 18px; font-family: Georgia }")]
    (is (= {:font-size 18 :font-family "Georgia"}
           (:rule/declarations (first rules))))))

;; ---- the `flex` shorthand ----
;;
;; `flex: 1` is the single most-used flex declaration on the real web, and
;; it is NOT `flex-grow: 1` -- the one-number form resets the BASIS to
;; zero, so the items split the container evenly regardless of what is in
;; them. Nothing expanded it before, so it reached cssom.layout as an
;; unread `:flex` key: measured against a real headless Brave, two
;; `flex: 1` items in a 300px row came out 7px and 70px against the
;; browser's 150 and 150.

(deftest flex-shorthand-one-number-zeroes-the-basis
  (let [rules (css/parse-rules "#f { flex: 1 }")]
    (is (= {:flex-grow 1 :flex-shrink 1 :flex-basis 0}
           (:rule/declarations (first rules)))
        "`flex: 1` is `1 1 0%`, which is why it splits a row evenly -- not `flex-grow: 1`, which leaves the basis at auto")
    (is (not (contains? (:rule/declarations (first rules)) :flex))
        "no bare :flex key should remain -- it's fully expanded")))

(deftest flex-shorthand-two-numbers-are-grow-and-shrink
  (is (= {:flex-grow 2 :flex-shrink 3 :flex-basis 0}
         (:rule/declarations (first (css/parse-rules "#f { flex: 2 3 }"))))))

(deftest flex-shorthand-three-values-are-grow-shrink-basis
  (is (= {:flex-grow 0 :flex-shrink 0 :flex-basis 40}
         (:rule/declarations (first (css/parse-rules "#f { flex: 0 0 40px }"))))))

(deftest flex-shorthand-a-lone-length-is-the-basis
  (is (= {:flex-grow 1 :flex-shrink 1 :flex-basis 100}
         (:rule/declarations (first (css/parse-rules "#f { flex: 100px }"))))
      "a value with a UNIT lands in the basis slot; the same token without one would be a grow factor"))

(deftest flex-shorthand-named-forms-are-their-spec-defined-triples
  (is (= {:flex-grow 0 :flex-shrink 0 :flex-basis "auto"}
         (:rule/declarations (first (css/parse-rules "#f { flex: none }")))))
  (is (= {:flex-grow 1 :flex-shrink 1 :flex-basis "auto"}
         (:rule/declarations (first (css/parse-rules "#f { flex: auto }")))))
  (is (= {:flex-grow 0 :flex-shrink 1 :flex-basis "auto"}
         (:rule/declarations (first (css/parse-rules "#f { flex: initial }"))))))

(deftest flex-shorthand-outside-the-grammar-degrades-to-no-op
  (is (= {:flex "1 solid nonsense"}
         (:rule/declarations (first (css/parse-rules "#f { flex: 1 solid nonsense }"))))
      "degrade-don't-guess, the same posture the box shorthand takes: a value this cannot parse must not become a guessed grow factor"))

(deftest flex-shorthand-importance-applies-to-every-expanded-longhand
  (let [rules (css/parse-rules "#f { flex: 1 !important }")]
    (is (= true (get-in (first rules) [:rule/declaration-meta :flex-grow :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :flex-shrink :important?])))
    (is (= true (get-in (first rules) [:rule/declaration-meta :flex-basis :important?])))))

(deftest declaration-order-decides-between-the-flex-shorthand-and-its-longhands
  (is (= 5 (:flex-grow (:rule/declarations (first (css/parse-rules "#f { flex: 1; flex-grow: 5 }")))))
      "a later longhand wins")
  (is (= 1 (:flex-grow (:rule/declarations (first (css/parse-rules "#f { flex-grow: 5; flex: 1 }")))))
      "and a later shorthand resets it -- which only works because expansion happens at declaration-parse time, not after the cascade has merged"))

(deftest parses-attribute-selector-operators
  (let [rules (css/parse-rules "[class~=\"primary\"] { color: red }
                                [lang|=\"en\"] { font-size: 12px }
                                [href^=\"https://\"] { padding: 1px }
                                [href$=\".pdf\"] { margin: 2px }
                                [data-route*=\"admin\"] { border-width: 3px }")]
    (is (= ["~=" "|=" "^=" "$=" "*="]
           (mapv #(-> % :rule/selectors first :selector/parts first :selector/attrs first :attr/operator)
                 rules)))))

(deftest selector-list-split-ignores-commas-inside-attribute-values
  (let [selectors (css/split-selector-list "[data-label=\"a,b\"], input:required, [title='x,y']")
        rules (css/parse-rules "[data-label=\"a,b\"], input:required { color: red }")]
    (is (= ["[data-label=\"a,b\"]" "input:required" "[title='x,y']"] selectors))
    (is (= 2 (count (:rule/selectors (first rules)))))
    (is (= "a,b" (-> rules first :rule/selectors first :selector/parts first :selector/attrs first :attr/value)))))

(deftest selector-tokenization-ignores-whitespace-inside-attribute-values
  (let [tokens (css/selector-tokens "main [title=\"hello world\"] > a[href*='report pdf']")
        selector (css/parse-selector "main [title=\"hello world\"] > a[href*='report pdf']")]
    (is (= ["main" "[title=\"hello world\"]" ">" "a[href*='report pdf']"] tokens))
    (is (= "hello world"
           (-> selector :selector/parts second :selector/attrs first :attr/value)))
    (is (= "report pdf"
           (-> selector :selector/parts (nth 2) :selector/attrs first :attr/value)))))

;; ---- attribute selector case-sensitivity flag ([attr=val i] / [attr=val s]) ----
;;
;; Real CSS Selectors Level 4: an attribute selector may end in an optional
;; `i`/`I` (force case-INSENSITIVE matching, regardless of whether HTML
;; happens to define that attribute as case-sensitive by default) or `s`/`S`
;; (the explicit, no-op case-SENSITIVE default) flag, separated from the
;; value by whitespace -- e.g. `[type="text" i]`. These tests exercise both
;; the parser (`parse-simple-selector`) and the real parse-rules ->
;; apply-cascade pipeline, the same discipline the :root/:empty/:lang()
;; tests above use.

(deftest parses-attribute-selector-case-insensitivity-flag
  (let [i-sel (css/parse-simple-selector "[type=\"text\" i]")
        upper-i-sel (css/parse-simple-selector "[type=\"text\" I]")
        s-sel (css/parse-simple-selector "[type=\"text\" s]")
        no-flag-sel (css/parse-simple-selector "[type=\"text\"]")
        extra-ws-sel (css/parse-simple-selector "[type=\"text\"   i ]")
        presence-only-sel (css/parse-simple-selector "[required]")]
    (is (true? (-> i-sel :selector/attrs first :attr/case-insensitive?))
        "a lowercase `i` flag sets :attr/case-insensitive? true")
    (is (true? (-> upper-i-sel :selector/attrs first :attr/case-insensitive?))
        "the flag letter is itself case-insensitive -- `I` behaves the same as `i`")
    (is (false? (-> s-sel :selector/attrs first :attr/case-insensitive?))
        "`s` is the explicit, already-default case-sensitive behavior -- it
         parses successfully but is a no-op")
    (is (false? (-> no-flag-sel :selector/attrs first :attr/case-insensitive?))
        "no flag at all defaults to case-sensitive, same as before this
         feature existed")
    (is (true? (-> extra-ws-sel :selector/attrs first :attr/case-insensitive?))
        "extra whitespace around the flag, on either side, is tolerated")
    (is (= "text" (-> extra-ws-sel :selector/attrs first :attr/value))
        "the flag's surrounding whitespace doesn't leak into the captured value")
    (is (= [{:attr/name :required :attr/operator nil :attr/value nil :attr/case-insensitive? false}]
           (:selector/attrs presence-only-sel))
        "a bare presence-only [attr] selector (no value at all, so no flag
         position exists in the grammar) still parses fine -- this feature
         must not have broken the no-operator regex path")))

(deftest attribute-selector-unquoted-value-ending-in-a-flag-letter-is-not-misparsed-as-having-a-flag
  ;; The critical edge case: an unquoted value's own trailing `i`/`s`
  ;; character must never be misread as a whitespace-less flag token --
  ;; there is no whitespace separating them, so this can never legitimately
  ;; be a flag in real CSS's own grammar either (see
  ;; attribute-selector-pattern/parse-attribute-selector's own docstrings
  ;; for why requiring `\s+`, not `\s*`, before the flag is essential here).
  (let [sel (css/parse-simple-selector "[data-x=abcs]")]
    (is (= "abcs" (-> sel :selector/attrs first :attr/value))
        "the whole unquoted value is captured, not truncated to \"abc\"")
    (is (false? (-> sel :selector/attrs first :attr/case-insensitive?))
        "no flag was actually present -- the trailing `s` belongs to the value")))

(deftest attribute-selector-case-insensitive-flag-matches-regardless-of-which-side-is-uppercase
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [lower-el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc lower-el :type "text")
        doc (dom/append-child doc root lower-el)
        [upper-el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc upper-el :type "TEXT")
        doc (dom/append-child doc root upper-el)
        rules (css/parse-rules "[type=\"TEXT\" i] { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes lower-el :attrs :style/color]))
        "type=\"text\" matches [type=\"TEXT\" i] -- the flag forces
         case-insensitivity on the ACTUAL attribute value, not just the
         selector's own value")
    (is (= "red" (get-in doc [:nodes upper-el :attrs :style/color]))
        "type=\"TEXT\" also matches -- symmetric regardless of which side
         happens to be uppercase")))

(deftest attribute-selector-without-the-flag-the-same-mismatched-case-selector-does-not-match
  ;; Proves the flag above is genuinely doing something, not that this
  ;; engine's attribute matching was accidentally always case-insensitive.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "text")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "[type=\"TEXT\"] { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes el :attrs :style/color]))
        "without the `i` flag, type=\"text\" does NOT match [type=\"TEXT\"]
         -- this engine's attribute matching is case-sensitive by default,
         exactly like real CSS")))

(deftest attribute-selector-case-insensitive-flag-combines-with-non-equality-operators
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [has-class doc] (dom/create-element doc :div)
        doc (dom/set-attribute doc has-class :class "Foo bar")
        doc (dom/append-child doc root has-class)
        [has-href doc] (dom/create-element doc :a)
        doc (dom/set-attribute doc has-href :href "HTTP://example.com")
        doc (dom/append-child doc root has-href)
        rules (css/parse-rules
               "[class~=\"foo\" i] { color: red }
                [href^=\"http\" i] { padding: 3px }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes has-class :attrs :style/color]))
        "[class~=\"foo\" i] matches class=\"Foo bar\" -- ~='s own
         whitespace-split tokens are compared case-insensitively too, not
         just the whole attribute value as one string")
    (is (= 3 (get-in doc [:nodes has-href :attrs :style/padding]))
        "[href^=\"http\" i] matches href=\"HTTP://example.com\"")))

(deftest parses-form-state-pseudo-classes
  (let [rules (css/parse-rules "input:disabled, input:enabled, input:checked, input:required, input:optional, input:read-only, input:read-write, input:invalid, input:valid, input:focus { color: red }")
        pseudos (mapv #(-> % :selector/parts first :selector/pseudos first)
                      (-> rules first :rule/selectors))]
    (is (= [:disabled :enabled :checked :required :optional :read-only :read-write :invalid :valid :focus] pseudos))
    (is (= [0 1 1] (css/specificity (-> rules first :rule/selectors first))))))

;; ---- :invalid/:valid honoring a real `pattern` attribute (real HTML5
;; patternMismatch) -- previously read NOWHERE at all in this repo's own
;; constraint-invalid?, confirmed via direct REPL reproduction that an
;; out-of-pattern value resolved to the identical :valid green as an
;; in-pattern one. Fixed together with the identical gaps in
;; kotoba-lang/browser's own document_input.cljc validation-reason and
;; quickjs_wasm.cljc's JS-facing __kotobaValidityState. ----

(deftest pattern-mismatch-input-matches-invalid-not-valid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :pattern "[0-9]+")
        doc (dom/set-attribute doc el :value "abc")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes el :attrs :style/color]))
        "a value not matching its own pattern must match :invalid")))

(deftest pattern-match-input-matches-valid-not-invalid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :pattern "[0-9]+")
        doc (dom/set-attribute doc el :value "123")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "a value matching its own pattern must match :valid")))

;; ---- minlength/maxlength are real HTML5's own restriction to text/
;; search/url/tel/email/password <input>s and <textarea> -- NOT
;; number/range/color/date/datetime-local/month/week/time, and not
;; <select>/checkbox/radio either. constraint-invalid? previously had
;; no type guard on minlength/maxlength at all, so a real, common
;; shape like <input type="number" value="12345" maxlength="3">
;; spuriously matched :invalid instead of :valid. Confirmed via direct
;; REPL reproduction through the real cascade before touching source.
;; Fixed together with the identical gap in kotoba-lang/browser's own
;; document_input.cljc validation-reason and quickjs_wasm.cljc's
;; JS-facing __kotobaValidationReason. ----

(deftest length-constraints-do-not-apply-to-non-text-like-controls
  (let [rules (css/parse-rules
               "input:invalid, textarea:invalid, select:invalid { color: red } input:valid, textarea:valid, select:valid { color: green }")
        build (fn [tag attrs]
                (let [[root doc] (dom/create-element dom/empty-document :div)
                      doc (dom/set-root doc root)
                      [el doc] (dom/create-element doc tag)
                      doc (reduce-kv #(dom/set-attribute %1 el %2 %3) doc attrs)
                      doc (dom/append-child doc root el)
                      doc (css/apply-cascade doc rules)]
                  (get-in doc [:nodes el :attrs :style/color])))
        ;; <select> has no `value` content attribute of its own -- its
        ;; computed value comes from its selected (or, absent any
        ;; explicit `selected`, first) <option>'s own value, so
        ;; genuinely exercising the :select guard needs a real option
        ;; child with a value longer than maxlength, not a bare
        ;; attribute on the <select> itself.
        build-select (fn [select-attrs option-value]
                       (let [[root doc] (dom/create-element dom/empty-document :div)
                             doc (dom/set-root doc root)
                             [el doc] (dom/create-element doc :select)
                             doc (reduce-kv #(dom/set-attribute %1 el %2 %3) doc select-attrs)
                             [opt doc] (dom/create-element doc :option)
                             doc (dom/set-attribute doc opt :value option-value)
                             doc (dom/append-child doc el opt)
                             doc (dom/append-child doc root el)
                             doc (css/apply-cascade doc rules)]
                         (get-in doc [:nodes el :attrs :style/color])))]
    (is (= "green" (build :input {:type "number" :value "12345" :maxlength "3"}))
        "maxlength must be ignored entirely for type=number")
    (is (= "green" (build :input {:type "range" :value "99" :min "0" :max "100" :maxlength "1"}))
        "maxlength must be ignored entirely for type=range")
    (is (= "green" (build :input {:type "date" :value "2026-07-10" :maxlength "1"}))
        "maxlength must be ignored entirely for type=date")
    (is (= "green" (build-select {:maxlength "3"} "abcdef"))
        "maxlength must be ignored entirely for a <select> -- previously entirely unguarded")
    (is (= "red" (build :input {:type "text" :value "12345" :maxlength "3"}))
        "maxlength must still apply to a real text-like input, unaffected by this fix")
    (is (= "red" (build :textarea {:value "12345" :maxlength "3"}))
        "maxlength must still apply to <textarea>, unaffected by this fix")
    (is (= "red" (build :input {:type "email" :value "a" :minlength "5"}))
        "minlength must still apply to a real text-like input, unaffected by this fix")))

(deftest pattern-on-a-blank-optional-value-does-not-force-invalid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :pattern "[0-9]+")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "pattern is not required's concern -- a blank, non-required value must still match :valid")))

(deftest malformed-pattern-is-not-enforced-by-invalid-valid-matching
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :pattern "[")
        doc (dom/set-attribute doc el :value "abc")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "an illegal regex must NOT be enforced, matching this file's degrade-don't-guess convention")))

(deftest pattern-on-a-textarea-has-no-effect-on-invalid-valid-matching
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :textarea)
        doc (dom/set-attribute doc el :pattern "[0-9]+")
        doc (dom/set-attribute doc el :value "abc")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "textarea:invalid { color: red } textarea:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "pattern is real HTML5's own input-only restriction -- it must have zero effect on a <textarea>")))

;; ---- :invalid/:valid honoring real type="email"/"url" format checking
;; (real HTML5 typeMismatch) -- the other half of the same scope-cut
;; pattern-mismatch closed above. ----

(deftest malformed-email-matches-invalid-not-valid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "email")
        doc (dom/set-attribute doc el :value "not-an-email")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes el :attrs :style/color]))
        "a malformed email value must match :invalid")))

(deftest well-formed-email-matches-valid-not-invalid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "email")
        doc (dom/set-attribute doc el :value "user@example.com")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "a well-formed email value must match :valid")))

(deftest malformed-url-matches-invalid-not-valid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "url")
        doc (dom/set-attribute doc el :value "not a url")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes el :attrs :style/color]))
        "a malformed url value must match :invalid")))

(deftest well-formed-url-matches-valid-not-invalid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "url")
        doc (dom/set-attribute doc el :value "https://example.com/path")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "a well-formed url value must match :valid")))

(deftest blank-email-does-not-force-invalid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "email")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "typeMismatch is not required's concern -- a blank, non-required email value must still match :valid")))

(deftest malformed-email-on-a-text-input-has-no-effect
  ;; type-mismatch only ever applies to type="email"/"url" -- a plain
  ;; text input with an email-shaped-but-irrelevant pattern-mismatching
  ;; value must be unaffected.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "text")
        doc (dom/set-attribute doc el :value "not-an-email")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "type=\"text\" must never be subject to email/url format checking")))

;; ---- :invalid/:valid honoring a real `step` attribute (real HTML5
;; stepMismatch) -- the last member of the constraint-validation family
;; started with range/pattern/type checking above. ----

(deftest step-mismatch-matches-invalid-not-valid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "number")
        doc (dom/set-attribute doc el :step "2")
        doc (dom/set-attribute doc el :value "3")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes el :attrs :style/color]))
        "a value not reachable via step from 0 must match :invalid")))

(deftest step-match-matches-valid-not-invalid
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "number")
        doc (dom/set-attribute doc el :step "2")
        doc (dom/set-attribute doc el :value "4")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "a value reachable via step from 0 must match :valid")))

(deftest default-step-rejects-a-fractional-value
  ;; A genuinely common surprise, matching real browsers: with no step
  ;; attribute at all, the default step is 1, so a fractional value is
  ;; real HTML5 INVALID.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "number")
        doc (dom/set-attribute doc el :value "3.5")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes el :attrs :style/color]))
        "a fractional value with no step attribute at all must match :invalid (default step is 1)")))

(deftest step-any-disables-the-check
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "number")
        doc (dom/set-attribute doc el :step "any")
        doc (dom/set-attribute doc el :value "3.5")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "step=\"any\" must disable the step check entirely, matching :valid")))

(deftest blank-numeric-value-does-not-force-step-mismatch
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "number")
        doc (dom/set-attribute doc el :step "2")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "step-mismatch is not required's concern -- a blank, non-required numeric value must still match :valid")))

(deftest step-mismatch-only-applies-to-number-and-range
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc el :type "text")
        doc (dom/set-attribute doc el :step "2")
        doc (dom/set-attribute doc el :value "3")
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes el :attrs :style/color]))
        "type=\"text\" must never be subject to step checking")))

;; ---- :in-range / :out-of-range pseudo-classes ----

(defn- range-check-doc
  [attrs]
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc :input)
        doc (reduce (fn [d [k v]] (dom/set-attribute d el k v)) doc attrs)
        doc (dom/append-child doc root el)
        rules (css/parse-rules "input:out-of-range { color: red } input:in-range { color: green }")]
    (get-in (css/apply-cascade doc rules) [:nodes el :attrs :style/color])))

(deftest out-of-range-value-matches-out-of-range-not-in-range
  (is (= "red" (range-check-doc {:type "number" :min "1" :max "10" :value "15"}))
      "a numeric value above max must match :out-of-range"))

(deftest in-range-value-matches-in-range-not-out-of-range
  (is (= "green" (range-check-doc {:type "number" :min "1" :max "10" :value "5"}))
      "a numeric value within min/max must match :in-range"))

(deftest below-min-value-matches-out-of-range
  (is (= "red" (range-check-doc {:type "number" :min "1" :max "10" :value "0"}))
      "a numeric value below min must match :out-of-range"))

(deftest range-type-out-of-range-also-matches
  (is (= "red" (range-check-doc {:type "range" :min "0" :max "100" :value "150"}))
      "type=\"range\" is subject to the same range-limitation matching as type=\"number\""))

(deftest no-min-or-max-matches-neither-in-range-nor-out-of-range
  (is (nil? (range-check-doc {:type "number" :value "5"}))
      "a control with no range limitations at all must match NEITHER pseudo-class, not :in-range by default"))

(deftest non-numeric-type-never-matches-in-range-or-out-of-range
  (is (nil? (range-check-doc {:type "text" :min "1" :max "10" :value "abc"}))
      "min/max on a non-number/range type input confer no range limitations"))

(deftest malformed-min-is-not-enforced-by-in-range-out-of-range-matching
  (is (= "green" (range-check-doc {:type "number" :min "abc" :max "10" :value "5"}))
      "a malformed min is not enforced, matching range-invalid?'s own degrade-don't-guess convention -- max alone still applies"))

(deftest blank-value-with-range-limitations-matches-in-range
  (is (= "green" (range-check-doc {:type "number" :min "1" :max "10" :value ""}))
      "a blank value is not suffering overflow/underflow, so it matches :in-range like real HTML5"))

(deftest disabled-out-of-range-control-matches-neither-pseudo-class
  ;; The answer is the USER-AGENT grey, not nil: neither author rule
  ;; matched (which is what this test is about), and the UA sheet's own
  ;; `input:disabled { color: #545454 }` -- measured in Brave 151 on
  ;; 2026-08-05 -- then stands, exactly as it does in a browser. Before
  ;; that rule existed this read nil for the same reason.
  (is (= "#545454" (range-check-doc {:type "number" :min "1" :max "10" :value "15" :disabled "disabled"}))
      "constraint validation (and so :in-range/:out-of-range) does not apply
       to a disabled control -- neither author rule wins, leaving the UA
       disabled colour"))

;; ---- the user-agent sheet's `:disabled` colours ----

(defn- control-color
  "`tag`'s resolved colour with `attrs` on it, cascaded against no author
   CSS at all -- so what comes back is the user-agent sheet's own answer."
  [tag attrs]
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [el doc] (dom/create-element doc tag)
        doc (reduce (fn [d [k v]] (dom/set-attribute d el k v)) doc attrs)
        doc (dom/append-child doc root el)]
    (get-in (css/apply-cascade doc []) [:nodes el :attrs :style/color])))

(deftest disabled-controls-take-one-of-three-user-agent-colours
  ;; Measured in Brave 151 on 2026-08-05, every control in the page twice,
  ;; once bare and once `disabled`. THREE distinct greys, keyed on the
  ;; control's type -- an engine with one of them would be wrong about the
  ;; other two.
  (is (= "#545454" (control-color :input {:disabled "disabled"})))
  (is (= "#545454" (control-color :input {:type "number" :disabled "disabled"})))
  (is (= "#545454" (control-color :textarea {:disabled "disabled"})))
  (is (= "#808080" (control-color :select {:disabled "disabled"})))
  (is (= "rgba(16, 16, 16, 0.3)" (control-color :button {:disabled "disabled"})))
  (is (= "rgba(16, 16, 16, 0.3)" (control-color :input {:type "submit" :disabled "disabled"})))
  ;; and the negatives, each of which a coarser rule would get wrong
  (is (nil? (control-color :input {})) "an enabled control is not greyed")
  (is (nil? (control-color :input {:readonly "readonly"}))
      "readonly is not disabled -- Brave reports plain black")
  (is (nil? (control-color :p {:disabled "disabled"}))
      "`disabled` on a non-control does nothing"))

(deftest dialog-gets-the-user-agent-padding-and-border
  ;; Measured in Brave 151 on 2026-08-05 in the corpus's own 14px page:
  ;; `padding` 14px on all four sides (`1em`), `border-top-width` 3px
  ;; (`border: solid`, i.e. `medium`), style solid. As four padding
  ;; LONGHANDS, because `padding: 1em` is not a length at declaration time
  ;; and so expands to nothing but the uniform key -- which is invisible to
  ;; every per-side reader, `getComputedStyle` included.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [d doc] (dom/create-element doc :dialog)
        doc (dom/set-attribute doc d :open "")
        doc (dom/append-child doc root d)
        doc (css/apply-cascade doc [] {:base-font-size 14})
        attrs (get-in doc [:nodes d :attrs])]
    (is (= [14 14 14 14] [(:style/padding-top attrs) (:style/padding-right attrs)
                          (:style/padding-bottom attrs) (:style/padding-left attrs)]))
    (is (= 3 (:style/border-width attrs)))
    (is (= "solid" (:style/border-style attrs)))
    (is (= "block" (:style/display attrs))))
  ;; and a dialog with no `open` is still `display: none`, which the sheet
  ;; already said and the padding must not have disturbed
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [d doc] (dom/create-element doc :dialog)
        doc (dom/append-child doc root d)
        doc (css/apply-cascade doc [])]
    (is (= "none" (get-in doc [:nodes d :attrs :style/display])))))

(deftest disabled-colour-follows-a-disabled-fieldset-not-just-the-attribute
  ;; The rule is `:disabled`, not `[disabled]`: Brave greys an <input>
  ;; inside a <fieldset disabled> that has no attribute of its own.
  (let [[root doc] (dom/create-element dom/empty-document :form)
        doc (dom/set-root doc root)
        [fs doc] (dom/create-element doc :fieldset)
        doc (dom/set-attribute doc fs :disabled "disabled")
        doc (dom/append-child doc root fs)
        [el doc] (dom/create-element doc :input)
        doc (dom/append-child doc fs el)
        doc (css/apply-cascade doc [])]
    (is (= "#545454" (get-in doc [:nodes el :attrs :style/color])))))

;; ---- radio button groups honor real form ownership, not the literal
;; :form attribute string (https://html.spec.whatwg.org/multipage/input.html
;; #radio-button-group) -- previously radio-group-node-ids compared only
;; the literal :form attribute (both sides defaulting to "" when absent),
;; so two same-named required radios in two DIFFERENT <form> elements,
;; neither carrying an explicit form= attribute (the overwhelmingly
;; common authoring shape: relying on the ancestor <form>, not the form=
;; attribute), were incorrectly treated as ONE shared group -- checking
;; the radio in form A silently satisfied form B's own required radio
;; too. browser.document-input's own radio-group-node-ids already gets
;; this right via ancestor-form-id; this mirrors that fix. ----

(deftest same-named-required-radios-in-different-forms-are-independent-groups
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [form-a doc] (dom/create-element doc :form)
        doc (dom/append-child doc root form-a)
        [radio-a doc] (dom/create-element doc :input)
        doc (dom/append-child doc form-a radio-a)
        doc (dom/set-attribute doc radio-a :type "radio")
        doc (dom/set-attribute doc radio-a :name "color")
        doc (dom/set-attribute doc radio-a :checked "checked")
        [form-b doc] (dom/create-element doc :form)
        doc (dom/append-child doc root form-b)
        [radio-b doc] (dom/create-element doc :input)
        doc (dom/append-child doc form-b radio-b)
        doc (dom/set-attribute doc radio-b :type "radio")
        doc (dom/set-attribute doc radio-b :name "color")
        doc (dom/set-attribute doc radio-b :required "required")
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes radio-b :attrs :style/color]))
        "form B's own required radio must stay :invalid -- form A's checked radio must not satisfy it")))

(deftest same-named-required-radios-in-the-same-form-still-share-a-group
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [form-a doc] (dom/create-element doc :form)
        doc (dom/append-child doc root form-a)
        [radio-a1 doc] (dom/create-element doc :input)
        doc (dom/append-child doc form-a radio-a1)
        doc (dom/set-attribute doc radio-a1 :type "radio")
        doc (dom/set-attribute doc radio-a1 :name "color")
        doc (dom/set-attribute doc radio-a1 :checked "checked")
        [radio-a2 doc] (dom/create-element doc :input)
        doc (dom/append-child doc form-a radio-a2)
        doc (dom/set-attribute doc radio-a2 :type "radio")
        doc (dom/set-attribute doc radio-a2 :name "color")
        doc (dom/set-attribute doc radio-a2 :required "required")
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes radio-a2 :attrs :style/color]))
        "two radios genuinely in the same form still share a group -- radio-a1 being checked satisfies radio-a2's requirement")))

(deftest explicit-form-attribute-radio-groups-with-the-referenced-forms-own-radios
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [form-x doc] (dom/create-element doc :form)
        doc (dom/append-child doc root form-x)
        doc (dom/set-attribute doc form-x :id "shared-form")
        [radio-in doc] (dom/create-element doc :input)
        doc (dom/append-child doc form-x radio-in)
        doc (dom/set-attribute doc radio-in :type "radio")
        doc (dom/set-attribute doc radio-in :name "size")
        doc (dom/set-attribute doc radio-in :checked "checked")
        [radio-out doc] (dom/create-element doc :input)
        doc (dom/append-child doc root radio-out)
        doc (dom/set-attribute doc radio-out :type "radio")
        doc (dom/set-attribute doc radio-out :name "size")
        doc (dom/set-attribute doc radio-out :form "shared-form")
        doc (dom/set-attribute doc radio-out :required "required")
        rules (css/parse-rules "input:invalid { color: red } input:valid { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes radio-out :attrs :style/color]))
        "an explicit form= attribute pointing at radio-in's real owner form joins the same group even though radio-out sits outside that form in the tree")))

;; ---- sibling combinators (+ / ~) ----

(deftest parses-adjacent-and-general-sibling-combinators
  (let [selector (css/parse-selector "h1 + p ~ span")]
    (is (= [nil :next-sibling :subsequent-sibling]
           (mapv :selector/combinator (:selector/parts selector))))))

(deftest matches-adjacent-and-general-sibling-combinators
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [h1 doc] (dom/create-element doc :h1)
        doc (dom/append-child doc section h1)
        [p1 doc] (dom/create-element doc :p)
        doc (dom/append-child doc section p1)
        [_span doc] (dom/create-element doc :span)
        doc (dom/append-child doc section _span)
        [p2 doc] (dom/create-element doc :p)
        doc (dom/append-child doc section p2)
        rules (css/parse-rules "h1 + p { color: red } h1 ~ p { border-width: 1px }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p1 :attrs :style/color]))
        "adjacent sibling matches the immediately following element")
    (is (nil? (get-in doc [:nodes p2 :attrs :style/color]))
        "adjacent sibling must not match a non-immediate later sibling")
    (is (= 1 (get-in doc [:nodes p1 :attrs :style/border-width]))
        "general sibling also matches the immediately following element")
    (is (= 1 (get-in doc [:nodes p2 :attrs :style/border-width]))
        "general sibling matches any later sibling, not just the adjacent one")))

;; ---- ::before / ::after pseudo-elements ----

(deftest parses-before-and-after-pseudo-elements
  (let [double-colon (css/parse-selector "p.note::before")
        legacy-single-colon (css/parse-selector "p:after")]
    (is (= :before (-> double-colon :selector/parts first :selector/pseudo-element)))
    (is (= ["note"] (-> double-colon :selector/parts first :selector/classes))
        "pseudo-element must not swallow the class it follows")
    (is (empty? (-> double-colon :selector/parts first :selector/pseudos))
        "before/after must not also leak into generic pseudo-classes")
    (is (= :after (-> legacy-single-colon :selector/parts first :selector/pseudo-element))
        "legacy single-colon :after spelling is also recognized")))

(deftest resolves-before-and-after-declarations-into-a-synthetic-pseudo-style
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules
               "p { color: black }
                p::before { content: \"*\"; color: red }
                p::after { content: \"!\" }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes p :attrs])]
    (is (= "black" (:style/color attrs))
        "the real element's own color is unaffected by ::before/::after rules")
    (is (not (contains? attrs :style/content))
        "pseudo-element declarations must not leak onto the real element")
    (is (= "red" (:color (:pseudo/before attrs))))
    (is (some? (:content (:pseudo/before attrs))))
    (is (some? (:content (:pseudo/after attrs))))
    (is (not (contains? (:pseudo/after attrs) :color))
        "::after only carries its own declarations")))

;; ---- ::before/::after generated `content` value parsing ----

(deftest before-content-quoted-string-is-parsed-into-a-plain-string
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules
               "p::before { content: \"→ \"; color: red }
                p::after { content: '!' }")
        node (dom/node doc p)
        style-before (css/pseudo-element-style-for doc rules node :before)
        style-after (css/pseudo-element-style-for doc rules node :after)]
    (is (= "→ " (:content style-before))
        "a double-quoted content literal is unquoted, not stored with its
         literal quote characters")
    (is (= "red" (:color style-before)))
    (is (= "!" (:content style-after))
        "single-quoted content literals are supported too")))

;; ---- generated quotes: `content: open-quote` / `close-quote` ----
;;
;; Measured in Brave 151 on 2026-08-05, in the conformance corpus's own
;; 14px monospace page. The characters are not read off a spec: the same
;; markup was rendered with `quotes: auto` and with
;; `quotes: "\201C" "\201D" "\2018" "\2019"` and every `<q>` box came out
;; byte-identical, which is what identifies them. See `quote-marks`.

(defn- nest-tags
  "Builds `tags` as a chain of nested elements under a root <p> and returns
   `[document ids]`, ids outermost first. Built with the DOM API rather
   than parsed, because this namespace must not depend on htmldom -- cssom
   is htmldom's dependency, not the other way round."
  [tags]
  (let [[root doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc root)
        [doc ids _] (reduce (fn [[doc ids parent] tag]
                              (let [[id doc] (dom/create-element doc tag)
                                    doc (dom/append-child doc parent id)]
                                [doc (conj ids id) id]))
                            [doc [] root]
                            tags)]
    [doc ids]))

(defn- q-quotes
  "The generated ::before/::after text of `depth` nested `<q>` elements,
   outermost first, after a real cascade with no author CSS."
  [depth]
  (let [[doc ids] (nest-tags (repeat depth :q))
        doc (css/apply-cascade doc [])
        [_ doc] (dom/consume-ops doc)]
    (mapv (fn [id] [(get-in doc [:nodes id :attrs :pseudo/before :content])
                    (get-in doc [:nodes id :attrs :pseudo/after :content])])
          ids)))

(deftest a-q-gets-its-quotation-marks-from-the-user-agent-sheet
  ;; The rule is `q::before { content: open-quote }`, which needed the UA
  ;; origin to be matched per PSEUDO-ELEMENT -- it used to be skipped for
  ;; pseudo-elements outright, which was only correct while the sheet had
  ;; no ::before rule in it.
  (is (= [["“" "”"]] (q-quotes 1))))

(deftest a-nested-q-uses-the-second-quote-level
  ;; Brave: the outer <q> is 91px wide and the inner 35 on this page, which
  ;; is only consistent with two DIFFERENT pairs -- the four characters all
  ;; advance 14px here where an ASCII `"` advances 7.
  (is (= [["“" "”"] ["‘" "’"]] (q-quotes 2)))
  ;; and a third level reuses the second's pair, which is CSS's own rule
  ;; for a depth deeper than the `quotes` list. Measured: 147 / 91 / 35.
  (is (= [["“" "”"] ["‘" "’"] ["‘" "’"]] (q-quotes 3))))

(deftest quote-depth-counts-only-the-quote-generating-ancestors
  ;; A `<q>` inside a `<span>` is still at level 1 -- measured in Brave, a
  ;; `<q>` inside a `<span>` and one inside a `<blockquote>` are both 28px
  ;; wider than their text, exactly like a bare one.
  (let [[doc ids] (nest-tags [:span :q])
        doc (css/apply-cascade doc [])
        [_ doc] (dom/consume-ops doc)]
    (is (= "“" (get-in doc [:nodes (second ids) :attrs :pseudo/before :content])))))

(deftest an-author-can-suppress-the-generated-quotes
  ;; `content: none` is not a value this engine renders (see
  ;; `parse-content-value`), so an author rule beating the UA one removes
  ;; the quote entirely -- measured in Brave, the same `<q>` is then 35px
  ;; wide rather than 63.
  (let [[doc ids] (nest-tags [:q])
        doc (css/apply-cascade doc (css/parse-rules "q::before, q::after { content: none }"))
        [_ doc] (dom/consume-ops doc)
        q (first ids)]
    (is (nil? (get-in doc [:nodes q :attrs :pseudo/before :content])))
    (is (nil? (get-in doc [:nodes q :attrs :pseudo/after :content])))))

(deftest the-ua-pseudo-rule-does-not-leak-onto-the-element-itself
  ;; `ua-style-for` answers what the UA sheet says about the ELEMENT, and
  ;; `q::before { content: open-quote }` says nothing about a `<q>`.
  (is (nil? (:content (css/ua-style-for {:node/type :element :tag :q :attrs {}}))))
  (let [[doc ids] (nest-tags [:q])
        doc (css/apply-cascade doc [])
        [_ doc] (dom/consume-ops doc)]
    (is (nil? (get-in doc [:nodes (first ids) :attrs :style/content])))))

(deftest before-content-empty-string-is-a-real-declared-value-not-absent
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: \"\" }")
        style (css/pseudo-element-style-for doc rules (dom/node doc span) :before)]
    (is (contains? style :content)
        "content: \"\" is a common icon-only generated-content idiom -- it
         must still be present, not dropped like genuinely-absent content")
    (is (= "" (:content style)))))

(deftest unsupported-content-forms-are-dropped-rather-than-stored-raw
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: url(icon.png); color: blue }")
        style (css/pseudo-element-style-for doc rules (dom/node doc span) :before)]
    (is (not (contains? style :content))
        "url()/none and other unsupported content forms are out of scope --
         dropped rather than crashing or being stored as an unusable raw
         string")
    (is (= "blue" (:color style))
        "other declared properties on the same pseudo-element rule are
         unaffected by an unsupported content value")))

;; ---- ::before/::after generated `content: attr(name)` ----
;;
;; attr() is the single most common real-world non-string-literal `content`
;; value (e.g. the `[title]::after { content: \" (\" attr(title) \")\"; }`
;; tooltip idiom, or numbering/labeling off a data attribute) -- unlike a
;; quoted literal, its actual text depends on the SPECIFIC element the
;; declaration ends up applied to, so these tests exercise it via a real
;; `node` with real attrs, not just the parser in isolation.

(deftest before-content-attr-resolves-to-the-elements-own-attribute-value
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        doc (dom/set-attribute doc span :data-foo "bar")
        rules (css/parse-rules "span::before { content: attr(data-foo); color: red }")
        style (css/pseudo-element-style-for doc rules (dom/node doc span) :before)]
    (is (= "bar" (:content style))
        "content: attr(data-foo) resolves to the real, current value of the
         element's own data-foo HTML attribute")
    (is (= "red" (:color style))
        "other declared properties on the same rule are unaffected")))

(deftest before-content-attr-missing-attribute-resolves-to-empty-string-not-absent
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: attr(data-missing); }")
        style (css/pseudo-element-style-for doc rules (dom/node doc span) :before)]
    (is (contains? style :content)
        "a missing attribute is real CSS's attr() 'empty string' case, not
         the same as content being absent altogether -- still a real
         generated-content box, just with empty text")
    (is (= "" (:content style)))))

(deftest before-content-attr-honors-specificity-like-any-other-content-value
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        doc (dom/set-attribute doc p :id "lead")
        doc (dom/set-attribute doc p :data-a "A")
        doc (dom/set-attribute doc p :data-b "B")
        rules (css/parse-rules
               "p::before { content: attr(data-a); color: red }
                #lead::before { content: attr(data-b); color: blue }")
        style (css/pseudo-element-style-for doc rules (dom/node doc p) :before)]
    (is (= "B" (:content style))
        "the higher-specificity #lead::before rule's attr() reference wins
         the cascade -- attr() reference markers flow through the exact
         same cascade-priority sort as any other content value, resolved
         only after the winner is picked")
    (is (= "blue" (:color style)))))

(deftest before-content-composes-string-literal-and-attr-reference
  ;; Stretch goal: mixing a quoted literal and attr() in one declaration --
  ;; a real, common pattern (e.g. `content: \"Price: \" attr(data-price);`).
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        doc (dom/set-attribute doc span :data-price "10")
        rules (css/parse-rules "span::before { content: \"Price: \" attr(data-price); }")
        style (css/pseudo-element-style-for doc rules (dom/node doc span) :before)]
    (is (= "Price: 10" (:content style))
        "a string literal and an attr() reference in the same declaration
         concatenate in source order, the attr() term resolved against the
         real element")))

;; ---- `counter-reset`/`counter-increment` + ::before/::after `content:
;; counter(name)` ----
;;
;; Unlike attr() (purely local to one element), a counter's value is the
;; CUMULATIVE effect of every counter-reset/counter-increment declaration on
;; every element preceding this point in document tree order -- so, unlike
;; every attr() test above, these tests exercise the value through a real
;; `apply-cascade` tree walk (the only code path that can resolve it
;; correctly), not `pseudo-element-style-for` alone.

(deftest parses-counter-reset-and-counter-increment-declarations
  (let [rules (css/parse-rules
               "li { counter-reset: item 5; counter-increment: item }
                ol { counter-reset: section; }
                div { counter-reset: a 1 b 2; }")]
    (is (= [["item" 5]] (:counter-reset (:rule/declarations (first rules))))
        "an explicit integer after the counter name overrides the default
         reset value")
    (is (= [["item" 1]] (:counter-increment (:rule/declarations (first rules))))
        "counter-increment with no explicit integer defaults to 1, real
         CSS's own default amount")
    (is (= [["section" 0]] (:counter-reset (:rule/declarations (second rules))))
        "counter-reset with no explicit integer defaults to 0, real CSS's
         own default reset value")
    (is (= [["a" 1] ["b" 2]] (:counter-reset (:rule/declarations (nth rules 2))))
        "a single declaration may reset more than one counter, each with its
         own optional integer")))

(deftest content-counter-reference-parses-into-an-unresolved-marker
  (let [rules (css/parse-rules "li::before { content: counter(item); }")]
    (is (= {:content/counter-name "item"}
           (:content (:rule/declarations (first rules))))
        "content: counter(name) parses into an unresolved marker at parse
         time -- its actual numeric value depends on the whole document's
         accumulated counter state, not anything the declaration itself
         carries, so it cannot be a plain string yet")))

(deftest single-element-counter-increment-is-seen-by-its-own-before-content
  ;; The core semantic this feature hinges on: a node's OWN
  ;; counter-increment must already have applied by the time that SAME
  ;; node's ::before content: counter(...) resolves (not the pre-increment
  ;; value) -- verified against real CSS behavior, not guessed.
  (let [[li doc] (dom/create-element dom/empty-document :li)
        doc (dom/set-root doc li)
        rules (css/parse-rules
               "li { counter-increment: item }
                li::before { content: counter(item); }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes li :attrs])]
    (is (= "1" (:content (:pseudo/before attrs)))
        "a lone <li> with counter-increment: item sees the INCREMENTED
         value (1), not the pre-increment value (0)")))

(deftest three-sibling-list-items-produce-sequential-counter-values
  ;; THE canonical real-world use case: an ordered-list-like numbering
  ;; produced purely by CSS counters across sibling elements.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li3)
        rules (css/parse-rules
               "li { counter-increment: item }
                li::before { content: counter(item) \". \"; }")
        doc (css/apply-cascade doc rules)
        content-for #(:content (:pseudo/before (get-in doc [:nodes % :attrs])))]
    (is (= "1. " (content-for li1)))
    (is (= "2. " (content-for li2))
        "the second <li> sees the counter as of AFTER the first <li>'s own
         increment -- a genuine running total across siblings, not each
         node resolved independently")
    (is (= "3. " (content-for li3)))))

(deftest counter-never-reset-or-incremented-reads-as-zero
  (let [[span doc] (dom/create-element dom/empty-document :span)
        doc (dom/set-root doc span)
        rules (css/parse-rules "span::before { content: counter(untouched); }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes span :attrs])]
    (is (= "0" (:content (:pseudo/before attrs)))
        "a counter that was never counter-reset/counter-increment anywhere
         in the document still reads as 0, real CSS's own default, not nil
         or a crash")))

(deftest counter-reset-establishes-a-starting-value-counter-increment-adds-to-it
  (let [[li doc] (dom/create-element dom/empty-document :li)
        doc (dom/set-root doc li)
        rules (css/parse-rules
               "li { counter-reset: item 5; counter-increment: item 2 }
                li::before { content: counter(item); }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes li :attrs])]
    (is (= "7" (:content (:pseudo/before attrs)))
        "counter-reset: item 5 sets the starting value, then
         counter-increment: item 2 adds to it -- reset applies before
         increment, matching real CSS's own declaration order")))

(deftest content-counter-composes-with-string-literals-across-siblings
  (let [[ol doc] (dom/create-element dom/empty-document :ol)
        doc (dom/set-root doc ol)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ol li2)
        rules (css/parse-rules
               "li { counter-increment: item }
                li::before { content: \"Item \" counter(item) \":\"; }")
        doc (css/apply-cascade doc rules)
        content-for #(:content (:pseudo/before (get-in doc [:nodes % :attrs])))]
    (is (= "Item 1:" (content-for li1)))
    (is (= "Item 2:" (content-for li2)))))

(deftest pseudo-element-style-for-standalone-cannot-resolve-counter-reference
  ;; Documented, honest limitation: pseudo-element-style-for (and
  ;; computed-style) called standalone -- no real apply-cascade tree walk
  ;; behind them -- have no running counters map to resolve counter()
  ;; against, so they leave :content unset rather than guessing a number.
  ;; Even though counter() IS a supported content form now (see the tests
  ;; above, all going through apply-cascade), this narrow entry point
  ;; genuinely cannot resolve it.
  (let [[li doc] (dom/create-element dom/empty-document :li)
        doc (dom/set-root doc li)
        rules (css/parse-rules
               "li { counter-increment: item }
                li::before { content: counter(item); color: red }")
        style (css/pseudo-element-style-for doc rules (dom/node doc li) :before)]
    (is (not (contains? style :content))
        "no apply-cascade tree walk means no counters context -- counter()
         is honestly left unresolved rather than defaulting to a wrong
         guess like 0")
    (is (= "red" (:color style))
        "other declared properties on the same rule are still resolved
         normally")))

(deftest pseudo-element-style-for-returns-empty-map-when-no-rule-targets-it
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules "p { color: black }")
        node (dom/node doc p)]
    (is (= {} (css/pseudo-element-style-for doc rules node :before)))
    (is (= {} (css/pseudo-element-style-for rules node :after))
        "the 3-arity (document-less) form works too, mirroring
         computed-style's own two arities")))

(deftest pseudo-element-style-for-honors-specificity-like-any-other-cascade
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        doc (dom/set-attribute doc p :id "lead")
        rules (css/parse-rules
               "p::before { content: \"*\"; color: red }
                #lead::before { content: \"→\"; color: blue }")
        style (css/pseudo-element-style-for doc rules (dom/node doc p) :before)]
    (is (= "→" (:content style))
        "the higher-specificity #lead::before rule wins the cascade, same
         as it would for a real element")
    (is (= "blue" (:color style)))))

;; ---- @media (min-width/max-width) ----

(deftest parses-media-blocks-and-tags-nested-rules-with-their-condition
  (let [rules (css/parse-rules
               "p { color: black }
                @media (min-width: 600px) { p { color: blue } .a { color: green } }")]
    (is (= 3 (count rules)))
    (is (nil? (:rule/media (first rules))))
    (is (= "(min-width: 600px)" (:rule/media (second rules))))
    (is (= "(min-width: 600px)" (:rule/media (nth rules 2))))
    (is (= [0 1 2] (mapv :rule/order rules))
        "rule order stays stable across plain and @media-wrapped rules")))

(deftest media-min-and-max-width-conditionally-apply-rules
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules
               "div { color: black }
                @media (min-width: 600px) { div { color: blue } }
                @media (max-width: 400px) { div { color: red } }")]
    (is (= "red" (get-in (css/apply-cascade doc rules {:viewport-width 320})
                         [:nodes div :attrs :style/color]))
        "only the max-width rule matches a narrow viewport")
    (is (= "blue" (get-in (css/apply-cascade doc rules {:viewport-width 1024})
                          [:nodes div :attrs :style/color]))
        "only the min-width rule matches a wide viewport")
    (is (= "blue" (get-in (css/apply-cascade doc rules)
                          [:nodes div :attrs :style/color]))
        "the default viewport width (800px) behaves like a desktop-sized viewport")))

;; ---- @media (prefers-color-scheme) ----

(deftest media-prefers-color-scheme-conditionally-applies-rules
  ;; Before this feature, ANY @media feature other than min-width/max-width
  ;; was unrecognized and defaulted to always-matching -- meaning a real
  ;; page's light AND dark variants (the single most common real-world
  ;; prefers-color-scheme pattern) would BOTH apply simultaneously,
  ;; regardless of the actual host color scheme.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules
               "div { background: white }
                @media (prefers-color-scheme: dark) { div { background: black } }")]
    (is (= "white" (get-in (css/apply-cascade doc rules {:color-scheme "light"})
                           [:nodes div :attrs :style/background]))
        "a real light color-scheme must not match a dark-only media query")
    (is (= "black" (get-in (css/apply-cascade doc rules {:color-scheme "dark"})
                           [:nodes div :attrs :style/background]))
        "a real dark color-scheme must match its own media query")
    (is (= "white" (get-in (css/apply-cascade doc rules)
                           [:nodes div :attrs :style/background]))
        "the default color-scheme (light) behaves like a real light-mode host")))

(deftest media-prefers-color-scheme-composes-with-screen-and-min-width
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules
               "div { background: white }
                @media screen and (prefers-color-scheme: dark) and (min-width: 600px) { div { background: black } }")]
    (is (= "black" (get-in (css/apply-cascade doc rules {:viewport-width 1024 :color-scheme "dark"})
                           [:nodes div :attrs :style/background]))
        "all three AND-combined conditions genuinely matching must apply the rule")
    (is (= "white" (get-in (css/apply-cascade doc rules {:viewport-width 320 :color-scheme "dark"})
                           [:nodes div :attrs :style/background]))
        "dark scheme alone is not enough if the min-width condition in the same AND chain fails")
    (is (= "white" (get-in (css/apply-cascade doc rules {:viewport-width 1024 :color-scheme "light"})
                           [:nodes div :attrs :style/background]))
        "a wide viewport alone is not enough if the color-scheme condition in the same AND chain fails")))

;; ---- CSS custom properties (--foo) and var() ----

(deftest resolves-custom-properties-inherited-from-an-ancestor
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        doc (dom/set-attribute doc section :class "theme")
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc section p)
        rules (css/parse-rules
               ".theme { --gap: 8px; --accent: teal }
                p { padding: var(--gap); color: var(--accent); margin: var(--missing, 3px) }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes p :attrs])]
    (is (= 8 (:style/padding attrs))
        "var() resolves an ancestor's custom property, coercing px like a literal declaration")
    (is (= "teal" (:style/color attrs)))
    (is (= 3 (:style/margin attrs))
        "var() falls back to its second argument when the custom property is undefined")))

(deftest own-element-custom-property-is-visible-to-its-own-declarations
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules "p { --size: 12px; font-size: var(--size) }")
        doc (css/apply-cascade doc rules)]
    (is (= 12 (get-in doc [:nodes p :attrs :style/font-size])))))

(deftest var-fallback-containing-a-nested-function-call-still-resolves
  ;; Previously `var-ref-pattern`'s fallback capture group (`[^()]*`)
  ;; excluded ANY paren whatsoever, so a fallback containing a nested
  ;; function call -- `rgba(...)`, `calc(...)`, or another `var(--y, ...)`
  ;; -- meant the WHOLE var() reference failed to match at all, leaving
  ;; the literal, unresolved text in the computed value instead of the
  ;; resolved fallback. This is an ordinary, common custom-property
  ;; idiom (`background: var(--btn-bg, rgba(0,0,0,.1))`), not a
  ;; contrived case. Confirmed via a real cljw run against the actual
  ;; resolve-value fn before touching source: both cases below returned
  ;; the raw, unresolved var(...) text.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules
               "p { background: var(--missing-a, rgba(0,0,0,0.5));
                    border-color: var(--missing-b, var(--missing-c, red));
                    width: var(--missing-d, calc(1px + 2px));
                    margin: 1px solid var(--missing-e, 3px) dashed }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes p :attrs])]
    (is (= "rgba(0,0,0,0.5)" (:style/background attrs))
        "a fallback containing a nested rgba() call resolves instead of staying literal text")
    (is (= "red" (:style/border-color attrs))
        "a fallback that is itself another var() reference with its own plain fallback resolves recursively")
    (is (= 3 (:style/width attrs))
        "a fallback containing a nested calc() call resolves all the way through to a plain evaluated number, exactly like an ordinary standalone calc() declaration")
    (is (= "1px solid 3px dashed" (:style/margin attrs))
        "an embedded var() with a plain (paren-free) fallback, surrounded by other text on both sides, still substitutes correctly without over- or under-consuming neighboring text -- a pre-existing-behavior regression guard, not itself discriminating for this fix (a plain fallback never hit the bug)")))

(deftest box-shorthand-holding-a-var-reference-still-expands-to-per-side-longhands
  ;; Measured, not hypothesised: the conformance harness's computed-style
  ;; axis attributed a `padding-left 0 -> 20px` mismatch on
  ;; `:cascade/custom-property` to the cascade. `expand-box-side-shorthand`
  ;; runs when a declaration is PARSED, but `var()` is not substituted until
  ;; `style-element` knows the element's inherited environment -- so
  ;; `padding: var(--pad)` was correctly declined as "not a length yet" and
  ;; then nothing ever re-expanded it. The element ended up with a lone
  ;; `:style/padding 20` and no per-side longhands, and this engine's box
  ;; model reads only the longhands.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "vars")
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc div p)
        rules (css/parse-rules ".vars { --pad: 20px } .vars p { padding: var(--pad) }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes p :attrs])]
    (is (= 20 (:style/padding attrs)) "the uniform key resolves as it always did")
    (is (= [20 20 20 20]
           [(:style/padding-top attrs) (:style/padding-right attrs)
            (:style/padding-bottom attrs) (:style/padding-left attrs)])
        "and the four per-side longhands the box model actually reads now exist")))

(deftest a-custom-property-holding-a-whole-box-shorthand-is-sliced-per-side
  ;; The follow-on case the fix above would otherwise get wrong: each side
  ;; carries the var() reference verbatim, so when the custom property's own
  ;; value is ITSELF a multi-value shorthand, every side would be left
  ;; holding the entire substituted string. Real CSS re-parses the
  ;; substituted shorthand, so the 1-to-4 rule has to be applied AFTER
  ;; substitution (`reslice-substituted-box-shorthands`).
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "vars")
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc div p)
        rules (css/parse-rules ".vars { --box: 4px 8px } .vars p { margin: var(--box) }")
        doc (css/apply-cascade doc rules)
        attrs (get-in doc [:nodes p :attrs])]
    (is (= [4 8 4 8]
           [(:style/margin-top attrs) (:style/margin-right attrs)
            (:style/margin-bottom attrs) (:style/margin-left attrs)])
        "two values are vertical/horizontal, exactly as if written literally")
    (is (= 4 (:style/margin attrs))
        "the uniform key keeps meaning the first written value")))

(deftest declaration-order-still-decides-between-a-box-shorthand-and-its-longhands
  ;; Regression guard for the shape this cycle deliberately did NOT adopt.
  ;; Re-expanding a shorthand after the cascade has already merged would be
  ;; simpler, and wrong: it would clobber a longhand that legitimately won
  ;; by coming later. Expansion therefore has to stay at declaration-parse
  ;; time, where the cascade's own ordering still applies to each longhand
  ;; independently.
  (let [probe (fn [css]
                (let [[div doc] (dom/create-element dom/empty-document :div)
                      doc (dom/set-root doc div)
                      doc (dom/set-attribute doc div :class "a")
                      doc (css/apply-cascade doc (css/parse-rules css))]
                  (get-in doc [:nodes div :attrs :style/padding-left])))]
    (is (= 0 (probe ".a { padding: 12px; padding-left: 0 }"))
        "a longhand written after the shorthand wins")
    (is (= 12 (probe ".a { padding-left: 0; padding: 12px }"))
        "a shorthand written after the longhand wins")))

(deftest var-fallback-nested-two-levels-deep-is-a-documented-scope-cut
  ;; Bounded, honest scope cut mirroring this file's calc()/hsl() cuts
  ;; elsewhere: one level of nested parens in a fallback resolves (see
  ;; the sibling test above), but TWO levels deep -- a function call
  ;; nested inside another function call, both inside the fallback --
  ;; does not match this pattern at all and is left as literal,
  ;; unresolved text. Real recursive-descent parsing would be needed for
  ;; arbitrary nesting depth; this documents the current, deliberate
  ;; boundary rather than silently leaving it undiscovered.
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules
               "p { background: var(--missing, rgba(0,0,0, calc(1 + 1))) }")
        doc (css/apply-cascade doc rules)]
    (is (= "var(--missing, rgba(0,0,0, calc(1 + 1)))"
           (get-in doc [:nodes p :attrs :style/background])))))

;; ---- @layer cascade layers ----

(deftest parses-layer-blocks-and-tags-nested-rules-with-their-name
  (let [rules (css/parse-rules
               "p { color: black }
                @layer base { p { color: blue } .a { color: green } }")]
    (is (= 3 (count rules)))
    (is (nil? (:rule/layer (first rules))))
    (is (= "base" (:rule/layer (second rules))))
    (is (= "base" (:rule/layer (nth rules 2))))
    (is (= [0 1 2] (mapv :rule/order rules))
        "rule order stays stable across plain and @layer-wrapped rules")))

(deftest resolves-layer-names-to-priority-indices-honoring-encounter-order
  (let [rules (css/parse-rules
               "@layer base { p { color: red } }
                @layer theme { p { color: blue } }
                p { color: green }")]
    (is (= 0 (:rule/layer-priority (first rules)))
        "base is encountered first -> lowest priority")
    (is (= 1 (:rule/layer-priority (second rules)))
        "theme is encountered second -> higher priority than base")
    (is (= 2 (:rule/layer-priority (nth rules 2)))
        "the unlayered rule resolves one past the highest named layer's index")))

(deftest later-declared-layer-beats-earlier-layer-regardless-of-specificity
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        rules (css/parse-rules
               "@layer base { #hero { color: red } }
                @layer override { div { color: blue } }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes div :attrs :style/color]))
        "the later-declared layer (override) wins even though base's #hero
         selector has higher specificity -- the defining behavior of
         cascade layers, which specificity alone would get wrong")))

(deftest unlayered-rule-beats-every-layered-rule-regardless-of-specificity
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        rules (css/parse-rules
               "@layer base { #hero { color: red } }
                @layer override { #hero { color: blue } }
                div { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes div :attrs :style/color]))
        "an unlayered declaration wins over every layered declaration, no
         matter the layer or specificity of the layered rules")))

(deftest bare-layer-order-statement-fixes-priority-even-when-blocks-appear-later-in-a-different-order
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules
               "@layer a, b;
                @layer b { div { color: blue } }
                @layer a { div { color: red } }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes div :attrs :style/color]))
        "the bare `@layer a, b;` statement declares b after a, so b's rule
         wins even though b's own block appears before a's block later in
         the stylesheet")))

(deftest specificity-still-decides-ties-within-a-single-layer
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        rules (css/parse-rules
               "@layer base { div { color: red } #hero { color: blue } }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes div :attrs :style/color]))
        "within the same layer, the higher-specificity #hero selector still
         wins -- ordinary cascade resolution is unchanged by cascade layers")))

;; ---- `!important` reverses cascade-layer order (CSS Cascading and
;;      Inheritance Level 5) ----

(deftest important-declarations-reverse-layer-order-earlier-layer-wins
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules
               "@layer a { p { color: red !important } }
                @layer b { p { color: blue !important } }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p :attrs :style/color]))
        "b is declared after a, so a plain (non-important) declaration in b
         would beat a -- but because both declarations are !important, real
         CSS reverses layer order for importance purposes, so the
         earlier-declared layer (a) wins instead")))

(deftest later-declared-layer-still-wins-for-non-important-declarations-alongside-the-reversal-fix
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules
               "@layer a { p { color: red } }
                @layer b { p { color: blue } }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes p :attrs :style/color]))
        "non-important declarations are unaffected by the !important
         reversal -- the later-declared layer (b) still wins, exactly as
         before")))

;; ---- an unlayered !important declaration LOSES to every layered
;; !important declaration -- previously this file's own implementation
;; and this exact test both encoded the OPPOSITE, confirmed via direct
;; REPL reproduction before touching source. Real CSS Cascading and
;; Inheritance Level 5: `!important` doesn't just reverse layer order
;; among layers, it also flips unlayered from "wins over everything" (its
;; normal-declaration behavior, still correctly unaffected here) to
;; "loses to everything layered" -- a well-documented, unintuitive
;; interaction (MDN/web.dev both call it out explicitly). Among the
;; layered !important rules themselves, the earliest-declared layer wins
;; (the reversal `important-declarations-reverse-layer-order-earlier-
;; layer-wins` above already covers), so layer `a` (declared before `b`)
;; wins here, and both beat the unlayered rule. ----

(deftest layered-important-declarations-beat-an-unlayered-important-declaration
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        rules (css/parse-rules
               "@layer a { #hero { color: red !important } }
                @layer b { #hero { color: blue !important } }
                div { color: green !important }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes div :attrs :style/color]))
        "layer a is the earliest-declared layer, so it wins among the
         layered !important rules -- and both layered !important
         declarations correctly beat the unlayered !important one")))

(deftest important-still-beats-non-important-across-layers-after-the-reversal-fix
  (let [[p doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p)
        rules (css/parse-rules
               "@layer b { p { color: blue } }
                @layer a { p { color: red !important } }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p :attrs :style/color]))
        "importance still trumps layer order overall -- a's !important
         declaration wins even though a is declared (and its layer would
         normally be considered lower-priority) before b's plain
         declaration")))

(deftest specificity-still-decides-ties-within-a-single-layer-for-important-declarations
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        rules (css/parse-rules
               "@layer base { div { color: red !important } #hero { color: blue !important } }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes div :attrs :style/color]))
        "within the same layer, higher specificity still breaks ties for
         !important declarations too -- the layer-order reversal only
         affects cross-layer comparisons, not within-layer specificity")))

;; ---- inline style `!important` (real per-property importance, read via
;;      :style-inline-important -- see resolve-style-for's own docstring) ----

(deftest inline-important-beats-a-rule-based-important-declaration
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        doc (dom/set-attribute doc div :style-inline {:color "red"})
        doc (dom/set-attribute doc div :style-inline-important #{:color})
        rules (css/parse-rules "#hero { color: blue !important }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes div :attrs :style/color]))
        "an inline !important declaration is still treated as the highest
         possible specificity within its importance group, so it beats a
         rule-based !important declaration -- matching real CSS")))

(deftest inline-important-beats-a-layered-important-declaration-unaffected-by-the-unlayered-reversal-fix
  ;; Real CSS: inline is exempt from the unlayered-loses-to-layered
  ;; reversal the fix above introduced for plain (rule-based) unlayered
  ;; !important declarations -- an inline !important declaration still
  ;; wins even over a LAYERED !important rule, since :inline? is compared
  ;; before :layer in the sort tuple and decides the comparison first.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        doc (dom/set-attribute doc div :style-inline {:color "purple"})
        doc (dom/set-attribute doc div :style-inline-important #{:color})
        rules (css/parse-rules "@layer a { #hero { color: red !important } }")
        doc (css/apply-cascade doc rules)]
    (is (= "purple" (get-in doc [:nodes div :attrs :style/color]))
        "inline !important beats even a layered !important rule, unlike a
         plain unlayered !important rule-based declaration")))

(deftest rule-based-important-beats-a-plain-inline-declaration
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        doc (dom/set-attribute doc div :style-inline {:color "red"})
        rules (css/parse-rules "#hero { color: blue !important }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes div :attrs :style/color]))
        "a plain (non-important) inline declaration -- :style-inline-important
         entirely absent, the common real-world case -- still loses to a
         rule-based !important declaration")))

(deftest plain-inline-declaration-still-beats-a-plain-rule-unaffected-by-this-fix
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        doc (dom/set-attribute doc div :style-inline {:color "red"})
        rules (css/parse-rules "#hero { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes div :attrs :style/color]))
        "pre-existing, unaffected behavior: an ordinary inline declaration
         still beats an ordinary rule-based one regardless of importance
         wiring")))

(deftest inline-important-set-only-affects-the-properties-actually-marked
  ;; A single inline style commonly mixes important and non-important
  ;; properties (e.g. `style="color: red !important; padding: 4px"") --
  ;; the importance set must be per-property, not all-or-nothing for the
  ;; whole inline declaration.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        doc (dom/set-attribute doc div :style-inline {:color "red" :padding 4})
        doc (dom/set-attribute doc div :style-inline-important #{:color})
        rules (css/parse-rules "#hero { color: blue !important; padding: 8px !important }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes div :attrs :style/color]))
        "color was marked !important inline, so it still wins over the
         rule's own !important color")
    (is (= 8 (get-in doc [:nodes div :attrs :style/padding]))
        "padding was NOT marked !important inline, so the rule's
         !important padding wins, exactly like the no-importance case")))

;; ---- @container (min-width/max-width/width, optionally named) ----
;;
;; See cssom.core's own namespace docstring (@container paragraph) and
;; apply-cascade's docstring for the bounded, two-cascade-pass mechanism
;; these tests exercise: a container's OWN explicit, literal width (never
;; auto/percentage/flex-or-grid-computed) is resolved in a first pass, then
;; @container rules are matched against that in a second pass -- no real
;; layout, no relayout loop.

(deftest parses-container-blocks-with-and-without-a-name
  (let [rules (css/parse-rules
               ".card-title { font-size: 16px }
                @container (min-width: 400px) { .card-title { font-size: 24px } }
                @container sidebar (min-width: 300px) { .card-title { font-size: 20px } }")]
    (is (= 3 (count rules)))
    (is (nil? (:rule/container (first rules))))
    (is (nil? (:rule/container-name (first rules))))
    (is (= "(min-width: 400px)" (:rule/container (second rules))))
    (is (nil? (:rule/container-name (second rules))))
    (is (= "(min-width: 300px)" (:rule/container (nth rules 2))))
    (is (= "sidebar" (:rule/container-name (nth rules 2))))
    (is (= [0 1 2] (mapv :rule/order rules))
        "rule order stays stable across plain and @container-wrapped rules")))

(deftest container-condition-matches-supports-min-max-and-exact-width-features
  (is (true? (css/container-condition-matches? "(min-width: 300px)" 400)))
  (is (false? (css/container-condition-matches? "(min-width: 300px)" 200)))
  (is (true? (css/container-condition-matches? "(max-width: 300px)" 200)))
  (is (false? (css/container-condition-matches? "(max-width: 300px)" 400)))
  (is (true? (css/container-condition-matches? "(width: 400px)" 400)))
  (is (false? (css/container-condition-matches? "(width: 400px)" 401)))
  (is (true? (css/container-condition-matches? "(min-width: 300px) and (max-width: 500px)" 400)))
  (is (false? (css/container-condition-matches? "(min-width: 300px) and (max-width: 350px)" 400))))

(deftest container-condition-matches-is-false-when-the-known-width-is-nil
  (is (false? (css/container-condition-matches? "(min-width: 300px)" nil))
      "an unresolvable container size means the condition honestly does not
       match -- a deliberate divergence from @media's own 'unrecognized
       feature still matches' convention, since here the queried VALUE
       itself (not just the feature keyword) is what's unknown"))

(deftest container-query-applies-when-ancestor-has-an-explicit-width-that-satisfies-it
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :class "card")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".card { container-type: inline-size; width: 400px }
                .card-title { font-size: 16px }
                @container (min-width: 300px) { .card-title { font-size: 24px } }")
        doc (css/apply-cascade doc rules)]
    (is (= 400 (get-in doc [:nodes card :attrs :style/width])))
    (is (= 24 (get-in doc [:nodes title :attrs :style/font-size]))
        "the container's explicit 400px width satisfies (min-width: 300px),
         so the @container-gated rule wins over the unconditional 16px one")))

(deftest container-query-does-not-apply-when-ancestor-width-fails-the-condition
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :class "card")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".card { container-type: inline-size; width: 200px }
                .card-title { font-size: 16px }
                @container (min-width: 300px) { .card-title { font-size: 24px } }")
        doc (css/apply-cascade doc rules)]
    (is (= 16 (get-in doc [:nodes title :attrs :style/font-size]))
        "the container's explicit 200px width does NOT satisfy
         (min-width: 300px), so only the unconditional rule applies")))

(deftest container-query-does-not-apply-when-ancestor-width-is-not-resolvable
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :class "card")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".card { container-type: inline-size }
                .card-title { font-size: 16px }
                @container (min-width: 300px) { .card-title { font-size: 24px } }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes card :attrs :style/width]))
        "the container never declared its own width at all")
    (is (= 16 (get-in doc [:nodes title :attrs :style/font-size]))
        "with no resolvable container width -- this engine runs no real
         layout to find one -- the @container rule honestly does NOT apply;
         it is not guessed at either way")))

(deftest container-query-does-not-apply-when-ancestor-width-is-a-percentage
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :class "card")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".card { container-type: inline-size; width: 50% }
                .card-title { font-size: 16px }
                @container (min-width: 300px) { .card-title { font-size: 24px } }")
        doc (css/apply-cascade doc rules)]
    (is (= "50%" (get-in doc [:nodes card :attrs :style/width]))
        "a percentage width is outside this engine's numeric-literal
         subset -- it stays a raw string, never coerced to a number")
    (is (= 16 (get-in doc [:nodes title :attrs :style/font-size]))
        "a non-numeric container width is exactly as unresolvable as no
         width at all")))

(deftest container-known-width-is-clamped-by-the-containers-own-explicit-max-width
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :class "card")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".card { container-type: inline-size; width: 500px; max-width: 250px }
                .card-title { font-size: 16px }
                @container (min-width: 300px) { .card-title { font-size: 24px } }
                @container (max-width: 250px) { .card-title { font-size: 12px } }")
        doc (css/apply-cascade doc rules)]
    (is (= 12 (get-in doc [:nodes title :attrs :style/font-size]))
        "the container's own max-width:250px clamps its known width down
         from 500 to 250 -- mirroring cssom.layout/resolve-width's own
         clamp -- so (min-width: 300px) does NOT match but
         (max-width: 250px) does")))

(deftest container-query-with-a-name-only-matches-a-container-declaring-that-name
  (let [[outer doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc outer)
        doc (dom/set-attribute doc outer :class "outer")
        [inner doc] (dom/create-element doc :div)
        doc (dom/append-child doc outer inner)
        doc (dom/set-attribute doc inner :class "inner")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc inner title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".outer { container-type: inline-size; container-name: sidebar; width: 400px }
                .inner { container-type: inline-size; width: 100px }
                .card-title { font-size: 16px }
                @container sidebar (min-width: 300px) { .card-title { font-size: 24px } }")
        doc (css/apply-cascade doc rules)]
    (is (= 24 (get-in doc [:nodes title :attrs :style/font-size]))
        "the nearer container (.inner, 100px) doesn't carry the queried
         `sidebar` name, so matching skips past it to the outer container
         that does (400px, satisfies min-width: 300px)")))

(deftest container-query-with-a-name-does-not-match-when-no-ancestor-declares-that-name
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :class "card")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".card { container-type: inline-size; width: 400px }
                .card-title { font-size: 16px }
                @container sidebar (min-width: 300px) { .card-title { font-size: 24px } }")
        doc (css/apply-cascade doc rules)]
    (is (= 16 (get-in doc [:nodes title :attrs :style/font-size]))
        "no ancestor declares container-name: sidebar at all, so the named
         query never finds a matching container and honestly does not
         apply")))

(deftest computed-style-standalone-cannot-resolve-a-container-query
  ;; Documented, honest limitation mirroring
  ;; pseudo-element-style-for-standalone-cannot-resolve-counter-reference
  ;; above: computed-style called standalone -- no real apply-cascade
  ;; two-pass tree walk behind it -- has no container-ctx (containers +
  ;; parent-index) to resolve a nearest matching container against, so an
  ;; @container-gated declaration is honestly never applied rather than
  ;; guessed at either way.
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :style/container-type "inline-size")
        doc (dom/set-attribute doc card :style/width 400)
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        rules (css/parse-rules
               "div { font-size: 16px }
                @container (min-width: 300px) { div { font-size: 24px } }")
        style (css/computed-style doc rules (dom/node doc title))]
    (is (= 16 (:font-size style))
        "no container-ctx at all outside apply-cascade's own tree walk, so
         the @container-gated declaration never applies here, even though
         the DOM already carries a container-type/width that WOULD satisfy
         it were this going through apply-cascade")))

(deftest container-composes-with-media-and-layer-nesting
  ;; Deliberately no unconditional `.card-title { font-size: ... }` baseline
  ;; here -- one always sitting alongside a *layered* rule would trivially
  ;; win via the pre-existing, unrelated "unlayered always beats layered of
  ;; the same importance" cascade-layers rule (see
  ;; important-still-beats-non-important-across-layers-after-the-reversal-fix
  ;; above), which would mask whether @media/@container/@layer nesting
  ;; itself actually composed correctly. So this test checks presence/
  ;; absence of :style/font-size instead of a fallback value.
  (let [[card doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc card)
        doc (dom/set-attribute doc card :class "card")
        [title doc] (dom/create-element doc :div)
        doc (dom/append-child doc card title)
        doc (dom/set-attribute doc title :class "card-title")
        rules (css/parse-rules
               ".card { container-type: inline-size; width: 400px }
                @media (min-width: 320px) {
                  @container (min-width: 300px) {
                    @layer boosted {
                      .card-title { font-size: 28px }
                    }
                  }
                }")]
    (is (= 1 (count (filter #(= "(min-width: 300px)" (:rule/container %)) rules)))
        "the nested @container rule parsed and carries its own condition")
    (let [doc-wide (css/apply-cascade doc rules {:viewport-width 800})
          doc-narrow (css/apply-cascade doc rules {:viewport-width 200})]
      (is (= 28 (get-in doc-wide [:nodes title :attrs :style/font-size]))
          "media (800 >= 320) AND container (400 >= 300) both hold, and the
           further-nested @layer rule still applies")
      (is (nil? (get-in doc-narrow [:nodes title :attrs :style/font-size]))
          "the outer @media (min-width: 320px) fails at a 200px viewport, so
           the whole nested block -- including its @container rule -- never
           applies, regardless of the container's own width"))))

;; ---- :not() / :is() / :where() selector-function pseudo-classes ----
;;
;; Before this feature existed, `:not(.special)` and `:is(.special)` were
;; silently misparsed: the plain class regex picked up `.special` (the
;; argument INSIDE the parens) as though it were a class on the OUTER
;; compound selector, and `:not`/`:is` themselves fell through to
;; `matches-pseudo?`'s unrecognized-pseudo-class default of `false` -- so
;; neither selector ever matched anything at all, regardless of the
;; element. These tests exercise the real fix through the full
;; `parse-rules` -> `apply-cascade` pipeline (not just the parser in
;; isolation), the same discipline the rest of this file already uses.

(deftest not-and-is-resolve-correctly-through-the-real-cascade-pipeline
  ;; The exact repro that exposed the gap in the first place: a <p> with
  ;; class "special" and a childless <p> without it.
  (let [[p1 doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p1)
        doc (dom/set-attribute doc p1 :class "special")
        [p2 doc] (dom/create-element doc :p)
        doc (dom/append-child doc p1 p2)
        rules (css/parse-rules "p:not(.special) { color: red } p:is(.special) { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes p1 :attrs :style/color]))
        "p1 has class \"special\" -- :is(.special) matches it, :not(.special)
         must NOT (so it doesn't get overwritten to red)")
    (is (= "red" (get-in doc [:nodes p2 :attrs :style/color]))
        "p2 has no class at all -- :not(.special) matches it, :is(.special)
         must NOT (so it doesn't get overwritten to blue)")))

(deftest not-pseudo-class-with-a-comma-separated-selector-list-excludes-every-alternative
  ;; Real CSS 4 allows a full selector LIST inside :not() -- :not(.a, .b)
  ;; must exclude an element matching EITHER .a OR .b, not just a single
  ;; selector.
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [d1 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d1)
        doc (dom/set-attribute doc d1 :class "a")
        [d2 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d2)
        doc (dom/set-attribute doc d2 :class "b")
        [d3 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d3)
        doc (dom/set-attribute doc d3 :class "c")
        rules (css/parse-rules "div:not(.a, .b) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes d1 :attrs :style/color])) "a .a element is excluded")
    (is (nil? (get-in doc [:nodes d2 :attrs :style/color])) "a .b element is also excluded")
    (is (= "red" (get-in doc [:nodes d3 :attrs :style/color]))
        "a .c element matches neither listed alternative, so :not(.a, .b)
         matches it")))

(deftest not-pseudo-class-composes-with-a-leading-compound-selector
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [card1 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section card1)
        doc (dom/set-attribute doc card1 :class "card")
        [card2 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section card2)
        doc (dom/set-attribute doc card2 :class "card disabled")
        rules (css/parse-rules ".card:not(.disabled) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes card1 :attrs :style/color]))
        "a plain .card (no .disabled) matches .card:not(.disabled)")
    (is (nil? (get-in doc [:nodes card2 :attrs :style/color]))
        "a .card.disabled must NOT match .card:not(.disabled) -- the
         .disabled class it also carries excludes it")))

(deftest is-pseudo-class-matches-any-of-several-alternatives
  (let [[h1 doc] (dom/create-element dom/empty-document :h1)
        doc (dom/set-root doc h1)
        [h2 doc] (dom/create-element doc :h2)
        doc (dom/append-child doc h1 h2)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc h1 p)
        rules (css/parse-rules ":is(h1, h2, h3) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes h1 :attrs :style/color])) "h1 is one of the listed alternatives")
    (is (= "red" (get-in doc [:nodes h2 :attrs :style/color])) "h2 is another listed alternative")
    (is (nil? (get-in doc [:nodes p :attrs :style/color]))
        "p matches none of h1/h2/h3, so :is(h1, h2, h3) does not match it")))

(deftest where-pseudo-class-matches-identically-to-is
  (let [[h1 doc] (dom/create-element dom/empty-document :h1)
        doc (dom/set-root doc h1)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc h1 p)
        rules (css/parse-rules ":where(h1, h2) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes h1 :attrs :style/color])))
    (is (nil? (get-in doc [:nodes p :attrs :style/color])))))

(deftest parses-not-is-where-into-per-occurrence-selector-groups-without-leaking-into-the-outer-compound
  (let [not-sel (css/parse-simple-selector "p:not(.a, .b)")
        is-sel (css/parse-simple-selector "h1:is(.big, .huge)")
        where-sel (css/parse-simple-selector ".card:where(.featured)")
        group-classes (fn [groups] (mapv (fn [group] (mapv #(first (:selector/classes %)) group)) groups))]
    (is (= :p (:selector/tag not-sel)))
    (is (empty? (:selector/classes not-sel))
        "the :not() argument's own classes must NOT leak onto the outer
         compound selector -- the exact bug this whole feature fixes")
    (is (= [["a" "b"]] (group-classes (:selector/not not-sel)))
        "the argument is parsed as a comma-separated selector list, reusing
         split-selector-list")
    (is (= [["big" "huge"]] (group-classes (:selector/is is-sel))))
    (is (= [["featured"]] (group-classes (:selector/where where-sel))))))

;; ---- :not()/:is() DO count toward specificity (their most specific
;;      argument); :where() NEVER does -- always zero. ----

(deftest not-and-is-contribute-the-specificity-of-their-most-specific-argument
  (let [rules (css/parse-rules
               "p:not(.a) { color: red }
                p:is(#id, .a) { color: blue }")]
    (is (= [0 1 1] (css/specificity (-> rules first :rule/selectors first)))
        ":not(.a) adds .a's own specificity (0 class, contributing to the
         middle column) on top of the bare `p` tag selector's own (0 0 1)")
    (is (= [1 0 1] (css/specificity (-> rules second :rule/selectors first)))
        ":is(#id, .a) adds the MOST specific of its two arguments --
         #id's (1 0 0), which beats .a's lower (0 1 0) -- not an average or
         the first-listed one")))

(deftest not-pseudo-class-specificity-wins-a-real-cascade-tie-break-despite-losing-on-source-order
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        rules (css/parse-rules
               "div:not(#nonexistent) { color: red }
                div { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes div :attrs :style/color]))
        "div:not(#nonexistent) has HIGHER specificity than plain `div`
         ((1 0 1) beats (0 0 1), because :not()'s #nonexistent argument
         contributes id-level specificity regardless of whether the element
         actually has that id), so it wins the cascade even though it is
         declared FIRST -- source order alone would otherwise favor the
         later-declared plain `div` rule")))

(deftest where-pseudo-class-always-contributes-zero-specificity-even-with-an-id-argument
  ;; THE single most important, easy-to-get-wrong test in this whole
  ;; feature: a naive implementation might give :where() the same
  ;; specificity treatment as :is() (since they match identically) -- this
  ;; proves that would be wrong, using a rule that would WIN under that
  ;; bug but LOSES under the correct real-CSS-4 behavior.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "big")
        rules (css/parse-rules
               "div:where(#big) { color: red }
                div { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (= [0 0 1] (css/specificity (-> rules first :rule/selectors first)))
        ":where(#big) contributes ZERO specificity regardless of the #big
         id argument -- the compound's specificity is exactly `div`'s own
         (0 0 1), not (1 0 1) like :is(#big) would give (see the mirror test
         below)")
    (is (= "blue" (get-in doc [:nodes div :attrs :style/color]))
        "the div genuinely HAS id=\"big\", so div:where(#big) really does
         match it -- but it TIES with plain `div` on specificity (both
         (0 0 1)), so the cascade falls through to source order and the
         later-declared plain `div` rule wins. A naive implementation that
         (wrongly) gave :where() the same specificity treatment as :is()
         would make div:where(#big) win instead ((1 0 1) beats (0 0 1))
         regardless of declaration order -- this assertion fails under that
         bug")))

(deftest is-pseudo-class-with-the-identical-argument-does-get-the-specificity-where-deliberately-does-not
  ;; The mirror image of the :where() test above, using the exact same
  ;; #big argument and the exact same tie-breaking setup, to prove the
  ;; divergence is real and specific to :where() -- not a general
  ;; :not()/:is()/:where() bug.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "big")
        rules (css/parse-rules
               "div:is(#big) { color: red }
                div { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (= [1 0 1] (css/specificity (-> rules first :rule/selectors first)))
        ":is(#big) DOES contribute #big's id-level specificity, unlike
         :where(#big) above")
    (is (= "red" (get-in doc [:nodes div :attrs :style/color]))
        "div:is(#big) has HIGHER specificity than plain `div` ((1 0 1)
         beats (0 0 1)), so it wins despite being declared FIRST -- the
         opposite outcome of the :where() test above, for an otherwise
         identical setup")))

;; ---- paren-depth tokenization/splitting for :not()/:is()/:where() args ----

(deftest selector-tokenization-ignores-whitespace-and-commas-inside-functional-pseudo-class-arguments
  (let [tokens (css/selector-tokens ".card:is(.a, .b) > p")]
    (is (= [".card:is(.a, .b)" ">" "p"] tokens)
        "the space after the comma inside :is(...) must not split this into
         two separate compound-selector tokens")))

(deftest selector-list-split-ignores-commas-inside-functional-pseudo-class-arguments
  (let [selectors (css/split-selector-list "p:not(.a, .b), div")]
    (is (= ["p:not(.a, .b)" "div"] selectors)
        "the comma inside :not(...) must not split the top-level selector
         list -- only the comma separating the two top-level selectors
         should")))

;; ---- :not()/:is()/:where() with a NESTED parenthesized argument, e.g.
;;      :not(:nth-child(1)) ----
;;
;; Before this fix, functional-pseudo-class-pattern's argument capture was
;; a strict [^()]* -- unable to contain ANY parens at all -- so the WHOLE
;; :not()/:is()/:where() occurrence failed to match whenever its argument
;; contained a parenthesized pseudo-class like :nth-child()/:nth-of-type()/
;; :lang(). This silently broke a common real-world idiom entirely, not
;; just mis-parsed it: confirmed via direct REPL reproduction before this
;; fix that li:not(:nth-child(1)) matched ZERO real <li> siblings at all
;; (not just the wrong ones -- every one, including the ones that should
;; have matched). These tests exercise the real fix through the full
;; parse-rules -> apply-cascade pipeline, the same discipline the rest of
;; this file already uses.

(deftest not-pseudo-class-with-a-nested-nth-child-argument-matches-correctly
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li3)
        rules (css/parse-rules "li:not(:nth-child(1)) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes li1 :attrs :style/color]))
        "li1 IS the first child, so :not(:nth-child(1)) must NOT match it")
    (is (= "red" (get-in doc [:nodes li2 :attrs :style/color]))
        "li2 is not the first child, so :not(:nth-child(1)) matches it")
    (is (= "red" (get-in doc [:nodes li3 :attrs :style/color]))
        "li3 is not the first child, so :not(:nth-child(1)) matches it")))

(deftest is-pseudo-class-with-a-nested-nth-child-argument-matches-correctly
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [li3 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li3)
        rules (css/parse-rules ":is(:nth-child(1), :nth-child(3)) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes li1 :attrs :style/color])) "li1 matches the first alternative")
    (is (nil? (get-in doc [:nodes li2 :attrs :style/color])) "li2 matches neither alternative")
    (is (= "red" (get-in doc [:nodes li3 :attrs :style/color])) "li3 matches the second alternative")))

(deftest where-pseudo-class-with-a-nested-nth-child-argument-matches-correctly
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        rules (css/parse-rules ":where(:nth-child(2)) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes li1 :attrs :style/color])))
    (is (= "red" (get-in doc [:nodes li2 :attrs :style/color])))))

(deftest not-is-where-without-any-nested-parens-are-unaffected-by-this-fix
  ;; Regression guard: every already-working non-nested form must stay
  ;; byte-for-byte identical.
  (let [[p1 doc] (dom/create-element dom/empty-document :p)
        doc (dom/set-root doc p1)
        doc (dom/set-attribute doc p1 :class "special")
        [p2 doc] (dom/create-element doc :p)
        doc (dom/append-child doc p1 p2)
        rules (css/parse-rules "p:not(.special) { color: red } p:is(.special) { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (= "blue" (get-in doc [:nodes p1 :attrs :style/color])))
    (is (= "red" (get-in doc [:nodes p2 :attrs :style/color])))))

(deftest is-with-a-once-nested-not-argument-also-starts-working-as-a-bonus-of-this-fix
  ;; :is(:not(.a)) -- a nested FUNCTIONAL pseudo-class, not just a nested
  ;; :nth-child() -- was previously documented as out of scope, but the
  ;; same one-level-of-nesting fix incidentally makes this work too, since
  ;; the recursive parse-simple-selector call on the now-correctly-
  ;; captured inner text ":not(.a)" runs through this same fixed pattern
  ;; again. Confirmed via direct REPL check this is a genuine improvement,
  ;; not a coincidence -- documented as a bonus, not the fix's own primary
  ;; target.
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [d1 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d1)
        [d2 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d2)
        doc (dom/set-attribute doc d2 :class "a")
        rules (css/parse-rules "div:is(:not(.a)) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes d1 :attrs :style/color])) "d1 has no class \"a\", so :not(.a) matches it, so :is(:not(.a)) matches it")
    (is (nil? (get-in doc [:nodes d2 :attrs :style/color])) "d2 has class \"a\", so :not(.a) does NOT match it, so :is(:not(.a)) does not match it")))

;; ---- structural pseudo-classes: :first-child/:last-child/:only-child/
;;      :nth-child(), and their same-tag :first-of-type/:last-of-type/
;;      :nth-of-type() counterparts, plus :nth-child()'s/:nth-of-type()'s
;;      own from-the-end mirrors :nth-last-child()/:nth-last-of-type() ----
;;
;; Before this feature existed, ALL of these matched nothing at all, for
;; any element whatsoever: their bare pseudo-class names were captured
;; into :selector/pseudos just fine (pseudo-class-pattern doesn't care what
;; follows the name), but matches-pseudo? had no case for any of them and
;; fell through to its unrecognized-pseudo-class default of `false`, and
;; any :nth-child()/:nth-of-type() argument text was silently discarded.
;; These tests exercise the real fix through the full `parse-rules` ->
;; `apply-cascade` pipeline, the same discipline the rest of this file uses.

(deftest first-child-last-child-and-nth-child-match-correctly-among-three-real-li-siblings
  ;; The exact repro that exposed the gap in the first place.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        mk (fn [doc] (dom/create-element doc :li))
        [li1 doc] (mk doc)
        doc (dom/append-child doc ul li1)
        [li2 doc] (mk doc)
        doc (dom/append-child doc ul li2)
        [li3 doc] (mk doc)
        doc (dom/append-child doc ul li3)
        rules (css/parse-rules
               "li:first-child { color: red }
                li:last-child { color: blue }
                li:nth-child(2) { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes li1 :attrs :style/color]))
        "li1 is the first element child -- matched by :first-child")
    (is (= "green" (get-in doc [:nodes li2 :attrs :style/color]))
        "li2 is the 2nd element child -- matched by :nth-child(2), not
         :first-child or :last-child")
    (is (= "blue" (get-in doc [:nodes li3 :attrs :style/color]))
        "li3 is the last element child -- matched by :last-child")
    (is (= [0 1 1] (css/specificity (-> rules first :rule/selectors first)))
        "li:first-child -- the tag `li` contributes 1 to the 3rd column,
         and :first-child contributes ordinary pseudo-class specificity (1
         to the 2nd column), same as :hover/:disabled/etc.")))

(deftest only-child-matches-a-lone-element-child-but-not-when-siblings-exist
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [solo doc] (dom/create-element doc :p)
        doc (dom/append-child doc section solo)
        rules (css/parse-rules "p:only-child { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes solo :attrs :style/color]))
        "a lone element child matches :only-child"))
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [p1 doc] (dom/create-element doc :p)
        doc (dom/append-child doc section p1)
        [p2 doc] (dom/create-element doc :p)
        doc (dom/append-child doc section p2)
        rules (css/parse-rules "p:only-child { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes p1 :attrs :style/color]))
        "two element siblings -- neither is :only-child")
    (is (nil? (get-in doc [:nodes p2 :attrs :style/color])))))

(deftest text-node-siblings-do-not-shift-first-child-or-nth-child-position
  ;; Real CSS: text nodes never count toward sibling position. A preceding
  ;; text-node sibling must not push the first REAL element down to
  ;; position 2.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [txt doc] (dom/create-text-node doc "   ")
        doc (dom/append-child doc ul txt)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        rules (css/parse-rules
               "li:first-child { color: red }
                li:nth-child(1) { border-width: 1px }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes li1 :attrs :style/color]))
        "li1 is still element-position 1 despite the preceding text node")
    (is (= 1 (get-in doc [:nodes li1 :attrs :style/border-width]))
        ":nth-child(1) agrees -- li1 is position 1")
    (is (nil? (get-in doc [:nodes li2 :attrs :style/color]))
        "li2 is position 2 -- not :first-child")
    (is (nil? (get-in doc [:nodes li2 :attrs :style/border-width]))
        "li2 is position 2 -- not :nth-child(1)")))

(deftest nth-child-an-b-forms-match-the-correct-full-subset-among-five-real-siblings
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        mk (fn [doc] (dom/create-element doc :li))
        [li1 doc] (mk doc) doc (dom/append-child doc ul li1)
        [li2 doc] (mk doc) doc (dom/append-child doc ul li2)
        [li3 doc] (mk doc) doc (dom/append-child doc ul li3)
        [li4 doc] (mk doc) doc (dom/append-child doc ul li4)
        [li5 doc] (mk doc) doc (dom/append-child doc ul li5)
        lis [li1 li2 li3 li4 li5]
        rules (css/parse-rules
               "li:nth-child(2) { color: red }
                li:nth-child(even) { border-width: 1px }
                li:nth-child(odd) { border-width: 2px }
                li:nth-child(2n+1) { padding: 3px }
                li:nth-child(-n+2) { margin: 4px }")
        doc (css/apply-cascade doc rules)
        matching-1-indexed-positions
        (fn [prop expected-val]
          (set (keep-indexed (fn [idx id]
                                (when (= expected-val (get-in doc [:nodes id :attrs prop]))
                                  (inc idx)))
                              lis)))]
    (is (= #{2} (matching-1-indexed-positions :style/color "red"))
        ":nth-child(2) matches only the 2nd element")
    (is (= #{2 4} (matching-1-indexed-positions :style/border-width 1))
        ":nth-child(even) matches exactly the 2nd and 4th elements")
    (is (= #{1 3 5} (matching-1-indexed-positions :style/border-width 2))
        ":nth-child(odd) matches exactly the 1st, 3rd, and 5th elements")
    (is (= #{1 3 5} (matching-1-indexed-positions :style/padding 3))
        ":nth-child(2n+1) matches the IDENTICAL subset :nth-child(odd) does")
    (is (= #{1 2} (matching-1-indexed-positions :style/margin 4))
        ":nth-child(-n+2) matches only the first two elements (n=1 -> 1,
         n=0 -> 2; n=2 would give position 0, which does not exist)")))

(deftest nth-of-type-and-first-last-of-type-count-only-same-tag-siblings-unlike-nth-child
  ;; Six siblings alternating <p>/<span>: overall document positions
  ;; 1..6 are p,span,p,span,p,span -- but each tag's OWN of-type position
  ;; resets independently: p1/s1/p2/s2/p3/s3 are of-type positions
  ;; 1,1,2,2,3,3 respectively.
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [p1 doc] (dom/create-element doc :p) doc (dom/append-child doc section p1)
        [s1 doc] (dom/create-element doc :span) doc (dom/append-child doc section s1)
        [p2 doc] (dom/create-element doc :p) doc (dom/append-child doc section p2)
        [s2 doc] (dom/create-element doc :span) doc (dom/append-child doc section s2)
        [p3 doc] (dom/create-element doc :p) doc (dom/append-child doc section p3)
        [s3 doc] (dom/create-element doc :span) doc (dom/append-child doc section s3)
        rules (css/parse-rules
               "p:first-of-type, span:first-of-type { color: red }
                p:last-of-type, span:last-of-type { color: blue }
                p:nth-of-type(2), span:nth-of-type(2) { border-width: 1px }
                p:nth-child(3), span:nth-child(3) { padding: 2px }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p1 :attrs :style/color])) "p1 is the first <p>")
    (is (= "red" (get-in doc [:nodes s1 :attrs :style/color]))
        "s1 is the first <span> -- of-type position resets per tag, so a
         DIFFERENT element also gets :first-of-type, unlike :first-child
         (which only ever matches ONE element, the true first child)")
    (is (nil? (get-in doc [:nodes p2 :attrs :style/color])) "p2 is the 2nd <p>, not first-of-type or last-of-type")
    (is (nil? (get-in doc [:nodes s2 :attrs :style/color])) "s2 is the 2nd <span>, not first-of-type or last-of-type")
    (is (= "blue" (get-in doc [:nodes p3 :attrs :style/color])) "p3 is the last <p>")
    (is (= "blue" (get-in doc [:nodes s3 :attrs :style/color])) "s3 is the last <span>")
    (is (= 1 (get-in doc [:nodes p2 :attrs :style/border-width]))
        "p2 is the 2nd <p> -- matched by p:nth-of-type(2)")
    (is (= 1 (get-in doc [:nodes s2 :attrs :style/border-width]))
        "s2 is the 2nd <span> -- matched by span:nth-of-type(2)")
    (is (nil? (get-in doc [:nodes p1 :attrs :style/border-width])))
    (is (nil? (get-in doc [:nodes s1 :attrs :style/border-width])))
    ;; :nth-child(3), by contrast, counts ALL element siblings regardless
    ;; of tag: overall document position 3 (p1=1, s1=2, p2=3, s2=4, p3=5,
    ;; s3=6) is p2 -- even though p2's OWN of-type position is only 2, NOT
    ;; 3. :nth-child never resets per-tag the way :nth-of-type does.
    (is (= 2 (get-in doc [:nodes p2 :attrs :style/padding]))
        "p2 is overall element-position 3, so p:nth-child(3) matches it
         despite p2's of-type position being only 2")
    (is (nil? (get-in doc [:nodes s2 :attrs :style/padding]))
        "s2 is overall element-position 4, not 3 -- span:nth-child(3) must
         not match it, even though s2's of-type position (2) happens to
         equal p2's")))

(deftest parses-nth-child-and-nth-of-type-arguments-into-selector-nth-args
  (let [nth-child-sel (css/parse-simple-selector "li:nth-child(2n+1)")
        nth-of-type-sel (css/parse-simple-selector "p:nth-of-type(even)")
        first-child-sel (css/parse-simple-selector "li:first-child")]
    (is (= [:nth-child] (:selector/pseudos nth-child-sel)))
    (is (= {:nth-child "2n+1"} (:selector/nth-args nth-child-sel))
        "the raw An+B argument text is captured separately from the bare
         pseudo-class name (which is captured the same way :hover/etc.
         already were), for matches-pseudo? to parse+evaluate later")
    (is (= {:nth-of-type "even"} (:selector/nth-args nth-of-type-sel)))
    (is (= [:first-child] (:selector/pseudos first-child-sel)))
    (is (empty? (:selector/nth-args first-child-sel))
        "an argument-less structural pseudo-class has no :selector/nth-args
         entry at all")))

;; ---- :nth-last-child()/:nth-last-of-type() -- from-the-end mirrors of
;;      :nth-child()/:nth-of-type() above ----
;;
;; The An+B micro-syntax and its arithmetic (parse-nth-expression/
;; nth-matches?) are entirely unchanged/reused -- these tests exist to
;; exercise the one genuinely new piece, the from-the-end index
;; (nth-pseudo-matches?'s `from-end?` reversal), through the same real
;; parse-rules -> apply-cascade pipeline the tests above use.

(deftest nth-last-child-1-matches-the-last-sibling-not-the-first
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        mk (fn [doc] (dom/create-element doc :li))
        [li1 doc] (mk doc) doc (dom/append-child doc ul li1)
        [li2 doc] (mk doc) doc (dom/append-child doc ul li2)
        [li3 doc] (mk doc) doc (dom/append-child doc ul li3)
        rules (css/parse-rules "li:nth-last-child(1) { color: purple }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes li1 :attrs :style/color]))
        "li1 is the FIRST child -- :nth-last-child(1) means the LAST
         child, not the first (unlike a naive same-number reading against
         :nth-child(1))")
    (is (nil? (get-in doc [:nodes li2 :attrs :style/color])))
    (is (= "purple" (get-in doc [:nodes li3 :attrs :style/color]))
        "li3 is the LAST child -- matched by :nth-last-child(1)")))

(deftest nth-last-child-2n-matches-every-other-sibling-counting-from-the-end
  ;; Six siblings (an EVEN count) so the from-the-end subset is visibly the
  ;; MIRROR IMAGE of :nth-child(2n)'s forward subset, not coincidentally
  ;; identical to it -- with an ODD sibling count (e.g. 5), :nth-child(2n)
  ;; and :nth-last-child(2n) happen to land on the EXACT SAME forward
  ;; positions (each position's distance from one end equals its distance
  ;; from the other, modulo the even/odd split), which would obscure which
  ;; direction is actually being tested.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        mk (fn [doc] (dom/create-element doc :li))
        [li1 doc] (mk doc) doc (dom/append-child doc ul li1)
        [li2 doc] (mk doc) doc (dom/append-child doc ul li2)
        [li3 doc] (mk doc) doc (dom/append-child doc ul li3)
        [li4 doc] (mk doc) doc (dom/append-child doc ul li4)
        [li5 doc] (mk doc) doc (dom/append-child doc ul li5)
        [li6 doc] (mk doc) doc (dom/append-child doc ul li6)
        lis [li1 li2 li3 li4 li5 li6]
        rules (css/parse-rules
               "li:nth-child(2n) { color: red }
                li:nth-last-child(2n) { border-width: 1px }")
        doc (css/apply-cascade doc rules)
        matching-1-indexed-positions
        (fn [prop expected-val]
          (set (keep-indexed (fn [idx id]
                                (when (= expected-val (get-in doc [:nodes id :attrs prop]))
                                  (inc idx)))
                              lis)))]
    (is (= #{2 4 6} (matching-1-indexed-positions :style/color "red"))
        ":nth-child(2n) matches the 2nd/4th/6th elements counting from the
         START, as before")
    (is (= #{1 3 5} (matching-1-indexed-positions :style/border-width 1))
        ":nth-last-child(2n) matches from-end positions 2/4/6 -- among 6
         siblings that's forward positions 5/3/1, the COMPLEMENT of
         :nth-child(2n)'s own {2 4 6}, proving this really counts from the
         last sibling backward and not just re-running the same forward
         arithmetic under a different name")))

(deftest nth-last-child-negative-n-plus-2-matches-the-last-two-siblings
  ;; :nth-last-child(-n+2) is a common real-world 'style the last two
  ;; items' idiom -- exercises a negative A coefficient against the
  ;; REVERSED index, not just a positive-A/simple-literal case.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        mk (fn [doc] (dom/create-element doc :li))
        [li1 doc] (mk doc) doc (dom/append-child doc ul li1)
        [li2 doc] (mk doc) doc (dom/append-child doc ul li2)
        [li3 doc] (mk doc) doc (dom/append-child doc ul li3)
        [li4 doc] (mk doc) doc (dom/append-child doc ul li4)
        [li5 doc] (mk doc) doc (dom/append-child doc ul li5)
        lis [li1 li2 li3 li4 li5]
        rules (css/parse-rules "li:nth-last-child(-n+2) { margin: 4px }")
        doc (css/apply-cascade doc rules)
        matching-1-indexed-positions
        (fn [prop expected-val]
          (set (keep-indexed (fn [idx id]
                                (when (= expected-val (get-in doc [:nodes id :attrs prop]))
                                  (inc idx)))
                              lis)))]
    (is (= #{4 5} (matching-1-indexed-positions :style/margin 4))
        ":nth-last-child(-n+2) matches from-end positions 1 and 2 (n=0 -> 2,
         n=1 -> 1; n=2 would need from-end position 0, which does not
         exist) -- the LAST two siblings, forward positions 5 and 4 among
         these five")))

(deftest nth-last-of-type-counts-only-same-tag-siblings-from-the-end-unlike-nth-last-child
  ;; Mirrors nth-of-type-and-first-last-of-type-count-only-same-tag-siblings-unlike-nth-child
  ;; above, but exercising the from-the-end :nth-last-of-type()/
  ;; :nth-last-child() pair instead of the from-the-start
  ;; :nth-of-type()/:nth-child() pair. Same six alternating <p>/<span>
  ;; siblings: overall forward document positions 1..6 are p,span,p,span,p,
  ;; span (p1/s1/p2/s2/p3/s3); of-type position resets per tag (p1/p2/p3 =
  ;; of-type 1/2/3, s1/s2/s3 = of-type 1/2/3), so of-type-FROM-THE-END, p3/
  ;; s3 are position 1 (the last of their own tag), p2/s2 are position 2,
  ;; p1/s1 are position 3.
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [p1 doc] (dom/create-element doc :p) doc (dom/append-child doc section p1)
        [s1 doc] (dom/create-element doc :span) doc (dom/append-child doc section s1)
        [p2 doc] (dom/create-element doc :p) doc (dom/append-child doc section p2)
        [s2 doc] (dom/create-element doc :span) doc (dom/append-child doc section s2)
        [p3 doc] (dom/create-element doc :p) doc (dom/append-child doc section p3)
        [s3 doc] (dom/create-element doc :span) doc (dom/append-child doc section s3)
        rules (css/parse-rules
               "p:nth-last-of-type(1), span:nth-last-of-type(1) { color: red }
                p:nth-last-of-type(2), span:nth-last-of-type(2) { border-width: 1px }
                p:nth-last-child(2) { padding: 2px }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p3 :attrs :style/color]))
        "p3 is the LAST <p> -- of-type-from-end position 1")
    (is (= "red" (get-in doc [:nodes s3 :attrs :style/color]))
        "s3 is the LAST <span> -- of-type-from-end position 1 too, a
         DIFFERENT element, since of-type-from-end position resets per tag
         just like plain of-type position already does")
    (is (nil? (get-in doc [:nodes p1 :attrs :style/color])))
    (is (nil? (get-in doc [:nodes s1 :attrs :style/color])))
    (is (nil? (get-in doc [:nodes p2 :attrs :style/color])))
    (is (nil? (get-in doc [:nodes s2 :attrs :style/color])))
    (is (= 1 (get-in doc [:nodes p2 :attrs :style/border-width]))
        "p2 is the 2nd-from-the-end <p> -- matched by
         p:nth-last-of-type(2)")
    (is (= 1 (get-in doc [:nodes s2 :attrs :style/border-width]))
        "s2 is the 2nd-from-the-end <span> -- matched by
         span:nth-last-of-type(2)")
    (is (nil? (get-in doc [:nodes p3 :attrs :style/border-width])))
    (is (nil? (get-in doc [:nodes s3 :attrs :style/border-width])))
    ;; :nth-last-child(2), by contrast, counts ALL element siblings
    ;; regardless of tag, from the end: overall from-end positions are
    ;; s3=1, p3=2, s2=3, p2=4, s1=5, p1=6 -- so overall position-from-end 2
    ;; is p3, NOT p2, even though p2's own of-type-from-end position (2)
    ;; happens to equal the argument too.
    (is (= 2 (get-in doc [:nodes p3 :attrs :style/padding]))
        "p3 is overall element-position-from-end 2, so
         p:nth-last-child(2) matches it despite p3's of-type-from-end
         position being only 1")
    (is (nil? (get-in doc [:nodes p2 :attrs :style/padding]))
        "p2 is overall element-position-from-end 4, not 2 --
         p:nth-last-child(2) must not match it, even though p2's
         of-type-from-end position (2) happens to equal the target
         argument")))

(deftest parses-nth-last-child-and-nth-last-of-type-arguments-into-selector-nth-args
  (let [nth-last-child-sel (css/parse-simple-selector "li:nth-last-child(2n+1)")
        nth-last-of-type-sel (css/parse-simple-selector "p:nth-last-of-type(even)")]
    (is (= [:nth-last-child] (:selector/pseudos nth-last-child-sel)))
    (is (= {:nth-last-child "2n+1"} (:selector/nth-args nth-last-child-sel))
        "captured exactly like :nth-child's own argument -- same regex
         alternation, same [A B] micro-syntax")
    (is (= {:nth-last-of-type "even"} (:selector/nth-args nth-last-of-type-sel)))))

;; ---- :root / :empty pseudo-classes ----
;;
;; Before this feature existed, both matched nothing at all, for any
;; element whatsoever -- their bare pseudo-class names were already
;; captured into :selector/pseudos just fine (pseudo-class-pattern doesn't
;; care what follows the name), but matches-pseudo? had no case for either
;; and fell through to its unrecognized-pseudo-class default of `false`.
;; These tests exercise the real fix through the full `parse-rules` ->
;; `apply-cascade` pipeline, the same discipline the rest of this file uses.

(deftest root-matches-only-the-documents-actual-root-element-and-empty-matches-a-genuinely-childless-element
  ;; The exact repro that exposed the gap in the first place: an <html>
  ;; root with two <div> children, one genuinely empty, one holding a real
  ;; text node. `padding` is used as a second, :root-only property (nothing
  ;; else in this rule set ever sets it) so ":root doesn't match the divs"
  ;; can be checked unambiguously, independent of div:empty's own (higher-
  ;; specificity) `color` declaration on empty-div. `margin` (a plain,
  ;; non-shorthand property, picked here purely as an arbitrary "any
  ;; recognized property" marker unrelated to its own real CSS meaning) is
  ;; used for the `*`-applies-everywhere sanity check -- this test
  ;; originally used `outline` for that same incidental purpose, but
  ;; `outline` is now itself a real, expanded shorthand (see cssom.core's
  ;; own `expand-outline-shorthand`), so `outline: 1px` no longer resolves
  ;; to a bare `:style/outline` key at all.
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        [empty-div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root empty-div)
        [full-div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root full-div)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc full-div t)
        rules (css/parse-rules
               "* { margin: 1px }
                :root { color: purple; padding: 9px }
                div:empty { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= 1 (get-in doc [:nodes root :attrs :style/margin]))
        "sanity check: `*` applies to the root too -- confirms the cascade
         wiring itself, independent of :root/:empty")
    (is (= 1 (get-in doc [:nodes empty-div :attrs :style/margin])))
    (is (= 1 (get-in doc [:nodes full-div :attrs :style/margin])))
    (is (= "purple" (get-in doc [:nodes root :attrs :style/color]))
        ":root matches the document's actual root element")
    (is (= 9 (get-in doc [:nodes root :attrs :style/padding]))
        ":root's OTHER declaration also lands on the root")
    (is (nil? (get-in doc [:nodes empty-div :attrs :style/padding]))
        ":root must NOT match this non-root element -- no other rule here
         ever sets `padding`, so a non-nil value could only mean :root
         wrongly matched it")
    (is (nil? (get-in doc [:nodes full-div :attrs :style/padding]))
        ":root must not match this non-root element either")
    (is (= "red" (get-in doc [:nodes empty-div :attrs :style/color]))
        "a genuinely childless <div></div> matches :empty")
    (is (nil? (get-in doc [:nodes full-div :attrs :style/color]))
        "a <div>hi</div> with a real text child does NOT match :empty")))

(deftest focus-within-matches-an-ancestor-of-the-focused-element
  ;; The confirmed repro: before this, `div:focus-within` never matched at
  ;; all, even with a real focused descendant, confirmed via direct REPL
  ;; reproduction before touching source (root-style stayed nil while the
  ;; focused child's own `:focus` still correctly matched blue).
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :input)
        doc (dom/append-child doc root child)
        rules (css/parse-rules "div:focus-within { color: red } input:focus { color: blue }")
        doc (assoc doc :focus child)
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes root :attrs :style/color]))
        ":focus-within matches an ancestor of the real focused element")
    (is (= "blue" (get-in doc [:nodes child :attrs :style/color]))
        ":focus still matches the focused element itself, unaffected")))

(deftest focus-within-matches-the-focused-element-itself-too
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :input)
        doc (dom/append-child doc root child)
        rules (css/parse-rules "input:focus-within { color: red }")
        doc (assoc doc :focus child)
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes child :attrs :style/color]))
        "real CSS :focus-within matches the focused element ITSELF too, not just its ancestors")))

(deftest focus-within-does-not-match-a-sibling-of-the-focused-element
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :input)
        doc (dom/append-child doc root child)
        [sibling doc] (dom/create-element doc :span)
        doc (dom/append-child doc root sibling)
        rules (css/parse-rules "span:focus-within { color: red }")
        doc (assoc doc :focus child)
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes sibling :attrs :style/color]))
        "a sibling of the focused element, not an ancestor, must not match :focus-within")))

(deftest focus-within-matches-nothing-when-nothing-is-focused
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :input)
        doc (dom/append-child doc root child)
        rules (css/parse-rules "*:focus-within { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes root :attrs :style/color])))
    (is (nil? (get-in doc [:nodes child :attrs :style/color])))))

(deftest root-pseudo-class-does-not-match-when-document-is-absent
  ;; The document-less 2-arity `matches?`/`matches-simple?` form never has
  ;; access to a `:root` key to compare against -- same documented
  ;; restriction `:focus` already has.
  (let [[div _doc] (dom/create-element dom/empty-document :div)
        selector (-> (css/parse-selector ":root") :selector/parts first)]
    (is (false? (css/matches? {:node/id div :node/type :element :tag :div} selector))
        ":root can never match via the document-less arity")))

(deftest empty-pseudo-class-does-not-match-a-whitespace-only-text-child
  ;; The one easy-to-get-wrong real-CSS detail: a text node made of nothing
  ;; but whitespace still has non-zero length, so it counts as content --
  ;; :empty does NOT match an element containing only whitespace text, even
  ;; though visually it looks the same as a genuinely empty element.
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        [ws doc] (dom/create-text-node doc "   ")
        doc (dom/append-child doc div ws)
        rules (css/parse-rules "div:empty { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes div :attrs :style/color]))
        "a whitespace-only text child disqualifies :empty, matching real
         CSS -- <div> </div> is NOT :empty")))

(deftest empty-pseudo-class-does-not-match-an-element-with-a-real-element-child
  ;; A nested element child disqualifies :empty on the OUTER element,
  ;; regardless of whether that inner child is itself empty.
  (let [[outer doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc outer)
        [inner doc] (dom/create-element doc :span)
        doc (dom/append-child doc outer inner)
        rules (css/parse-rules "div:empty { color: red } span:empty { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes outer :attrs :style/color]))
        "the outer div has a real element child -- not :empty")
    (is (= "blue" (get-in doc [:nodes inner :attrs :style/color]))
        "the inner span itself has no children at all -- IS :empty")))

(deftest empty-and-root-compose-correctly-with-other-simple-selector-parts
  ;; :empty/:root aren't only meaningful bare -- they must keep working
  ;; combined with a tag (the overwhelmingly common real-world usage,
  ;; `html:root { ... }` / `div:empty { ... }`) and must still respect the
  ;; OTHER part of the compound selector (a tag mismatch must still block
  ;; the match even when the pseudo-class alone would otherwise match).
  ;; `span:root` and `html:root` have IDENTICAL specificity and `span:root`
  ;; is declared LATER, so if `span:root` wrongly matched the (tag `html`)
  ;; root element too, its "hotpink" would win the tie over "purple" by
  ;; source order -- the root staying "purple" is proof the tag mismatch
  ;; correctly blocked it, not just that html:root happened to match.
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        [empty-p doc] (dom/create-element doc :p)
        doc (dom/append-child doc root empty-p)
        rules (css/parse-rules
               "html:root { color: purple }
                span:root { color: hotpink }
                p:empty { color: red }
                span:empty { color: green }")
        doc (css/apply-cascade doc rules)]
    (is (= "purple" (get-in doc [:nodes root :attrs :style/color]))
        "html:root matches, and the later same-specificity span:root does
         NOT wrongly also match the root element")
    (is (= "red" (get-in doc [:nodes empty-p :attrs :style/color]))
        "p:empty matches -- a genuinely childless <p>")
    (is (= [0 1 1] (css/specificity (-> rules first :rule/selectors first)))
        "html:root -- the tag `html` contributes 1 to the 3rd column, and
         :root contributes ordinary pseudo-class specificity (1 to the 2nd
         column), same as :hover/:disabled/:first-child/etc.")))

;; ---- :lang() pseudo-class ----
;;
;; Real CSS: `:lang(<tag>)` matches when the element's COMPUTED language --
;; its own `lang` HTML attribute if present and non-blank, else the nearest
;; ANCESTOR's -- is a whole-subtag, case-insensitive match for `<tag>` (a
;; comma-separated list of tags matches if ANY of them does). These tests
;; exercise the real parse-rules -> apply-cascade pipeline, the same
;; discipline the :root/:empty tests above use.

(deftest parses-lang-argument-into-selector-lang-args
  (let [lang-sel (css/parse-simple-selector "p:lang(en, fr)")
        no-arg-sel (css/parse-simple-selector "p:first-child")]
    (is (= [:lang] (:selector/pseudos lang-sel)))
    (is (= {:lang "en, fr"} (:selector/lang-args lang-sel))
        "the raw comma-separated argument text is captured separately from
         the bare pseudo-class name (which is captured the same way
         :hover/:nth-child/etc. already were), for matches-pseudo? to parse
         later")
    (is (empty? (:selector/lang-args no-arg-sel))
        "a compound selector with no :lang() at all has no
         :selector/lang-args entry")))

(deftest lang-matches-an-exact-tag-and-a-hyphenated-subtag-but-not-a-bare-string-prefix
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        [exact doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc exact :lang "en")
        doc (dom/append-child doc root exact)
        [subtag doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc subtag :lang "en-US")
        doc (dom/append-child doc root subtag)
        [not-a-subtag doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc not-a-subtag :lang "eng")
        doc (dom/append-child doc root not-a-subtag)
        rules (css/parse-rules "p:lang(en) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes exact :attrs :style/color]))
        "lang=\"en\" is an exact match for :lang(en)")
    (is (= "red" (get-in doc [:nodes subtag :attrs :style/color]))
        "lang=\"en-US\" -- \"en\" is a whole leading SUBTAG of it")
    (is (nil? (get-in doc [:nodes not-a-subtag :attrs :style/color]))
        "lang=\"eng\" must NOT match :lang(en) -- \"en\" is only a bare
         STRING prefix of \"eng\", not a whole subtag")))

(deftest lang-matches-case-insensitively
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        [p doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc p :lang "EN-us")
        doc (dom/append-child doc root p)
        rules (css/parse-rules "p:lang(en) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p :attrs :style/color]))
        "lang=\"EN-us\" matches :lang(en) case-insensitively on both
         sides")))

(deftest lang-is-inherited-from-the-nearest-ancestor-with-a-lang-attribute
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        doc (dom/set-attribute doc root :lang "fr")
        [section doc] (dom/create-element doc :section)
        doc (dom/append-child doc root section)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc section p)
        rules (css/parse-rules "p:lang(fr) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p :attrs :style/color]))
        "<p> itself has no lang attribute at all -- it inherits \"fr\" from
         its grandparent <html>, walking past its parent <section> (which
         also has no lang attribute of its own) to find it")))

(deftest lang-on-the-element-itself-overrides-an-ancestors-lang
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        doc (dom/set-attribute doc root :lang "fr")
        [p doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc p :lang "en")
        doc (dom/append-child doc root p)
        rules (css/parse-rules
               "p:lang(en) { color: red } p:lang(fr) { color: blue }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p :attrs :style/color]))
        "the element's OWN lang=\"en\" wins over its ancestor's lang=\"fr\"
         -- :lang(en) matches, :lang(fr) does not")))

(deftest lang-comma-separated-list-matches-any-one-of-its-tags
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        [fr-p doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc fr-p :lang "fr")
        doc (dom/append-child doc root fr-p)
        [de-p doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc de-p :lang "de")
        doc (dom/append-child doc root de-p)
        rules (css/parse-rules "p:lang(en, fr) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes fr-p :attrs :style/color]))
        "lang=\"fr\" matches the second tag in :lang(en, fr)")
    (is (nil? (get-in doc [:nodes de-p :attrs :style/color]))
        "lang=\"de\" matches neither tag in the list")))

(deftest lang-does-not-match-when-no-element-has-a-lang-attribute-at-all
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        [p doc] (dom/create-element doc :p)
        doc (dom/append-child doc root p)
        rules (css/parse-rules "p:lang(en) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes p :attrs :style/color]))
        "no element from <p> up to the root has a lang attribute -- no
         computed language at all, so :lang(en) never matches")))

(deftest lang-treats-a-blank-lang-attribute-as-no-computed-language-of-its-own
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        doc (dom/set-attribute doc root :lang "fr")
        [p doc] (dom/create-element doc :p)
        doc (dom/set-attribute doc p :lang "")
        doc (dom/append-child doc root p)
        rules (css/parse-rules "p:lang(fr) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes p :attrs :style/color]))
        "lang=\"\" (real HTML/CSS's own way of saying 'explicitly unknown
         language') does not count as the element's own computed language
         -- it still inherits its ancestor's \"fr\" rather than matching
         nothing at all")))

(deftest lang-pseudo-class-does-not-match-when-document-is-absent
  ;; Same documented restriction :root/the structural pseudo-classes
  ;; already have -- the document-less 2-arity form can't walk any
  ;; ancestor chain at all, so :lang() can never match there, even when
  ;; the node itself carries a matching `lang` attribute.
  (let [[p _doc] (dom/create-element dom/empty-document :p)
        selector (-> (css/parse-selector ":lang(en)") :selector/parts first)]
    (is (false? (css/matches? {:node/id p :node/type :element :tag :p
                                :attrs {:lang "en"}}
                               selector))
        ":lang() can never match via the document-less arity")))

;; ---- calc() -- constant, percentage-free arithmetic ----
;;
;; parse-style-value is private, so every case below goes through the real
;; parse-rules -> apply-cascade pipeline (same convention every other test
;; in this file already uses), reading the resulting cascade-resolved
;; :style/* attr straight off the real node.

(defn- calc-probe
  "Applies a single-property rule (`.box { <prop>: <value> }`) to a fresh
   real <div class=\"box\"> through the real parse-rules -> apply-cascade
   pipeline, returning that property's cascade-resolved :style/* value."
  [prop value]
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :class "box")
        rules (css/parse-rules (str ".box { " prop ": " value " }"))
        doc (css/apply-cascade doc rules)]
    (get-in doc [:nodes div :attrs (keyword "style" prop)])))

(deftest calc-constant-expression-resolves-to-a-plain-number-through-the-cascade
  (is (= 120 (calc-probe "width" "calc(100px + 20px)"))
      "two px lengths added together")
  (is (= 16 (calc-probe "padding" "calc(2 * 8px)"))
      "a plain number times a px length")
  (is (= 25 (calc-probe "gap" "calc(100px / 4)"))
      "a px length divided by a plain number")
  (is (= 120 (calc-probe "width" "calc( 100px + 20px )"))
      "whitespace immediately inside the parens is insignificant"))

(deftest calc-multiplication-and-division-bind-tighter-than-addition-and-subtraction
  (is (= 116 (calc-probe "width" "calc(100px + 2 * 8px)"))
      "* must bind before + -- 100px + (2 * 8px) = 116, not (100 + 2) * 8 = 816"))

(deftest calc-same-precedence-operators-associate-left-to-right
  (is (= 3 (calc-probe "margin" "calc(10px - 5px - 2px)"))
      "(10 - 5) - 2 = 3, NOT the right-associative 10 - (5 - 2) = 7"))

(deftest calc-nested-parens-override-default-precedence
  (is (= 32 (calc-probe "width" "calc((10px + 6px) * 2)"))))

(deftest calc-supports-negative-numbers
  (is (= 15 (calc-probe "width" "calc(-5px + 20px)"))
      "a leading unary minus on the first operand")
  (is (= 5 (calc-probe "padding" "calc(10px + -5px)"))
      "a unary minus on a later operand, right after a binary +"))

(deftest calc-keyword-is-case-insensitive-like-real-css
  (is (= 120 (calc-probe "width" "CALC(100px + 20px)"))))

(deftest calc-division-by-zero-does-not-resolve
  (is (= "calc(100px / 0)" (calc-probe "width" "calc(100px / 0)"))
      "real CSS: division by the number zero is invalid calc(), not
       Infinity/NaN -- falls through as the same raw unparsed string"))

(deftest calc-fractional-result-is-not-rounded-or-truncated
  (is (= (/ 100.0 3) (calc-probe "width" "calc(100px / 3)"))
      "a genuinely fractional result keeps its precision as a double
       rather than being silently rounded/floored to an integer"))

(deftest calc-with-a-percentage-stays-unresolved-same-as-before-calc-support-existed
  (is (= "calc(100% - 20px)" (calc-probe "width" "calc(100% - 20px)"))
      "a percentage inside calc() needs real layout against the
       container's own actual size -- out of this engine's bounded
       constant-calc() subset -- so the whole declaration falls through as
       the same raw, unparsed string calc() already fell through as before
       this subset was supported, never a guessed number"))

(deftest calc-arithmetic-type-violations-stay-unresolved
  (is (= "calc(100px * 20px)" (calc-probe "width" "calc(100px * 20px)"))
      "real CSS forbids multiplying two lengths together -- at least one
       side of * must be a plain unitless number")
  (is (= "calc(100px / 4px)" (calc-probe "height" "calc(100px / 4px)"))
      "real CSS requires a division's divisor to be a plain unitless
       number, never a length")
  (is (= "calc(100px + 5)" (calc-probe "margin" "calc(100px + 5)"))
      "+ requires both sides to be the SAME kind -- a length and a bare
       number don't add in real CSS either"))

(deftest malformed-calc-does-not-crash-and-stays-unresolved
  (is (= "calc(100px +)" (calc-probe "width" "calc(100px +)"))
      "a dangling operator with no right-hand operand")
  (is (= "calc(100px 20px)" (calc-probe "height" "calc(100px 20px)"))
      "two operands with no operator between them")
  (is (= "calc(100px))" (calc-probe "padding" "calc(100px))"))
      "an unbalanced extra closing paren")
  (is (= "calc()" (calc-probe "margin" "calc()"))
      "empty parens"))

;; ---- :has() relational pseudo-class ----
;;
;; Every OTHER pseudo-class this file supports (:not()/:is()/:where(), the
;; structural pseudo-classes, :root/:empty, :lang(), :nth-last-child()) tests
;; a candidate node against its ANCESTOR chain or its SIBLINGS -- walking UP
;; or SIDEWAYS via `document`. `:has()` needs the OPPOSITE direction: for a
;; candidate anchor node, walking DOWN into its own subtree and testing each
;; DESCENDANT against a selector -- e.g. `.card:has(.badge)` matches a `.card`
;; that CONTAINS a `.badge` somewhere inside it. These tests exercise the
;; real `parse-rules` -> `apply-cascade` pipeline, the same discipline the
;; rest of this file already uses, plus a couple of parser/specificity-level
;; tests mirroring the :not()/:is()/:where() precedent directly.

(deftest has-matches-a-card-containing-a-badge-nested-several-levels-deep-but-not-a-card-with-none
  ;; .badge is nested THREE levels deep inside card1 (card > wrapper > inner
  ;; > .badge), not just as a direct child -- proving this is a genuine
  ;; recursive walk of the WHOLE subtree, not merely an immediate-children
  ;; check.
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [card1 doc] (dom/create-element doc :div)
        doc (dom/set-attribute doc card1 :class "card")
        doc (dom/append-child doc section card1)
        [wrapper doc] (dom/create-element doc :div)
        doc (dom/append-child doc card1 wrapper)
        [inner doc] (dom/create-element doc :div)
        doc (dom/append-child doc wrapper inner)
        [badge doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc badge :class "badge")
        doc (dom/append-child doc inner badge)
        [card2 doc] (dom/create-element doc :div)
        doc (dom/set-attribute doc card2 :class "card")
        doc (dom/append-child doc section card2)
        rules (css/parse-rules ".card:has(.badge) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes card1 :attrs :style/color]))
        "card1 has a .badge three levels down its subtree")
    (is (nil? (get-in doc [:nodes card2 :attrs :style/color]))
        "card2 has no .badge anywhere inside it at all")))

(deftest has-with-a-leading-child-combinator-only-matches-a-direct-child-not-a-deeper-descendant
  ;; `:has(> img)` -- real CSS's direct-child form -- must match gallery1
  ;; (whose <img> is a direct child) but NOT gallery2 (whose <img> is nested
  ;; one extra wrapper div deeper), proving the `>` restriction is real and
  ;; not simply ignored/treated the same as the plain descendant case.
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [gallery1 doc] (dom/create-element doc :div)
        doc (dom/set-attribute doc gallery1 :class "gallery")
        doc (dom/append-child doc section gallery1)
        [img1 doc] (dom/create-element doc :img)
        doc (dom/append-child doc gallery1 img1)
        [gallery2 doc] (dom/create-element doc :div)
        doc (dom/set-attribute doc gallery2 :class "gallery")
        doc (dom/append-child doc section gallery2)
        [inner-wrapper doc] (dom/create-element doc :div)
        doc (dom/append-child doc gallery2 inner-wrapper)
        [img2 doc] (dom/create-element doc :img)
        doc (dom/append-child doc inner-wrapper img2)
        rules (css/parse-rules ".gallery:has(> img) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes gallery1 :attrs :style/color]))
        "gallery1's img is a DIRECT child")
    (is (nil? (get-in doc [:nodes gallery2 :attrs :style/color]))
        "gallery2's img is nested two levels deep -- :has(> img) must NOT
         match it")))

(deftest has-composes-with-a-nested-pseudo-class-inside-its-argument
  ;; `li:has(input:checked)` -- one of the most common real :has() patterns
  ;; -- needs the argument's own compound selector to be matched with the
  ;; FULL matches-simple? machinery (including its own :selector/pseudos
  ;; clause), not some cut-down tag/class-only comparison.
  (let [[ul doc] (dom/create-element dom/empty-document :ul)
        doc (dom/set-root doc ul)
        [li1 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li1)
        [input1 doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc input1 :type "checkbox")
        doc (dom/set-attribute doc input1 :checked "checked")
        doc (dom/append-child doc li1 input1)
        [li2 doc] (dom/create-element doc :li)
        doc (dom/append-child doc ul li2)
        [input2 doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc input2 :type "checkbox")
        doc (dom/append-child doc li2 input2)
        rules (css/parse-rules "li:has(input:checked) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes li1 :attrs :style/color]))
        "li1 contains a checked input")
    (is (nil? (get-in doc [:nodes li2 :attrs :style/color]))
        "li2's input exists but is not checked")))

(deftest has-comma-separated-argument-matches-if-either-alternative-is-present-as-a-descendant
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [d1 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d1)
        [a doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc a :class "a")
        doc (dom/append-child doc d1 a)
        [d2 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d2)
        [b doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc b :class "b")
        doc (dom/append-child doc d2 b)
        [d3 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d3)
        [c doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc c :class "c")
        doc (dom/append-child doc d3 c)
        rules (css/parse-rules "div:has(.a, .b) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes d1 :attrs :style/color])) "d1 has a .a descendant")
    (is (= "red" (get-in doc [:nodes d2 :attrs :style/color])) "d2 has a .b descendant")
    (is (nil? (get-in doc [:nodes d3 :attrs :style/color])) "d3 has neither .a nor .b, only .c")))

(deftest multiple-has-occurrences-are-and-combined-each-must-independently-hold
  ;; Mirrors :not(.a):not(.b)'s own AND-combined-groups semantics
  ;; (`not-pseudo-class-with-a-comma-separated-selector-list...` above) --
  ;; `:has(.a):has(.b)` requires BOTH occurrences to independently hold, not
  ;; just one of them.
  (let [[section doc] (dom/create-element dom/empty-document :section)
        doc (dom/set-root doc section)
        [d1 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d1)
        [a1 doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc a1 :class "a")
        doc (dom/append-child doc d1 a1)
        [d2 doc] (dom/create-element doc :div)
        doc (dom/append-child doc section d2)
        [a2 doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc a2 :class "a")
        doc (dom/append-child doc d2 a2)
        [b2 doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc b2 :class "b")
        doc (dom/append-child doc d2 b2)
        rules (css/parse-rules "div:has(.a):has(.b) { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes d1 :attrs :style/color]))
        "d1 has only a .a descendant -- :has(.b) fails, so the whole
         compound selector fails")
    (is (= "red" (get-in doc [:nodes d2 :attrs :style/color]))
        "d2 has both a .a and a .b descendant -- both :has() occurrences
         independently hold")))

;; The sibling-relative :has() forms. Every expectation below was measured
;; in Brave 151 on 2026-08-05, on the markup each test builds.

(defn- sibling-has-doc
  "A <div> whose element children are `tags` (a vector of [tag class] pairs,
   class may be nil), cascaded against `css`. Returns the children's
   resolved values for `prop`, in document order, nil where unset."
  [tags css prop]
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [ids doc] (reduce (fn [[ids d] [tag cls]]
                            (let [[id d] (dom/create-element d tag)
                                  d (cond-> d cls (dom/set-attribute id :class cls))
                                  d (dom/append-child d root id)]
                              [(conj ids id) d]))
                          [[] doc]
                          tags)
        doc (css/apply-cascade doc (css/parse-rules css))]
    (mapv #(get-in doc [:nodes % :attrs (keyword "style" (name prop))]) ids)))

(deftest has-with-a-following-sibling-combinator-is-forward-only
  ;; Brave, on <p>p0</p><h2>h-a</h2><span>s</span><p>after</p> with
  ;; `h2:has(~ p)`: only the <h2> is italic, and a LATER sibling counts
  ;; even with a <span> in between.
  (is (= [nil "italic" nil nil]
         (sibling-has-doc [[:p nil] [:h2 nil] [:span nil] [:p nil]]
                          "h2:has(~ p) { font-style: italic }"
                          :font-style)))
  (is (= [nil nil]
         (sibling-has-doc [[:p nil] [:h2 nil]]
                          "h2:has(~ p) { font-style: italic }"
                          :font-style))
      "an <h2> whose only <p> is BEFORE it must not match")
  ;; The forward-only half, measured directly: Brave gives
  ;; `p:has(~ .z) { font-weight: bold }` on <p>a<p>b<span class=z><p>c the
  ;; answer a=700 b=700 c=400 -- the <p> AFTER the `.z` is not selected.
  (is (= ["bold" "bold" nil nil]
         (sibling-has-doc [[:p nil] [:p nil] [:span "z"] [:p nil]]
                          "p:has(~ .z) { font-weight: bold }"
                          :font-weight))))

(deftest has-with-a-next-sibling-combinator-is-the-immediate-sibling-only
  ;; Brave: `h2:has(+ p)` italicises `h-d` (the <p> is next) and not `h-e`
  ;; (a <span> intervenes), where `h2:has(~ p)` would match both.
  (is (= ["italic" nil]
         (sibling-has-doc [[:h2 nil] [:p nil]]
                          "h2:has(+ p) { font-style: italic }" :font-style)))
  (is (= [nil nil nil]
         (sibling-has-doc [[:h2 nil] [:span nil] [:p nil]]
                          "h2:has(+ p) { font-style: italic }" :font-style))))

(deftest has-sibling-forms-compose-in-one-comma-separated-argument
  ;; Brave: `span:has(~ b, ~ i)` italicises both spans in the first row and
  ;; neither in the second -- the argument's comma list is an OR, exactly
  ;; as it already was for the descendant form. Only the SPANS are asserted:
  ;; the <i> and the <em> beside them are italic from the UA sheet, which
  ;; would make a "matched nothing" assertion pass for the wrong reason.
  (is (= ["italic" "italic"]
         (subvec (sibling-has-doc [[:span nil] [:span nil] [:i nil]]
                                  "span:has(~ b, ~ i) { font-style: italic }" :font-style)
                 0 2)))
  (is (= [nil]
         (subvec (sibling-has-doc [[:span nil] [:em nil]]
                                  "span:has(~ b, ~ i) { font-style: italic }" :font-style)
                 0 1))))

;; ---- :nth-child(An+B of <selector>) ----

(deftest nth-child-of-a-selector-counts-only-among-the-matching-siblings
  ;; Brave, on li.m/li/li.m/li/li.m/li.m with `li:nth-child(2n+1 of .m)`:
  ;; the 1st and 5th are bold -- the 1st and 3rd `.m`. Ignoring the clause
  ;; would bold the 1st, 3rd and 5th (odd children); treating the clause as
  ;; a plain compound would bold the 1st alone.
  (is (= ["bold" nil nil nil "bold" nil]
         (sibling-has-doc [[:li "m"] [:li nil] [:li "m"] [:li nil] [:li "m"] [:li "m"]]
                          "li:nth-child(2n+1 of .m) { font-weight: bold }"
                          :font-weight)))
  ;; `odd` and a bare integer take the clause too.
  (is (= [nil "bold" nil "bold"]
         (sibling-has-doc [[:li nil] [:li "m"] [:li "m"] [:li "m"]]
                          "li:nth-child(odd of .m) { font-weight: bold }"
                          :font-weight)))
  ;; and from the end
  (is (= [nil nil "bold" nil]
         (sibling-has-doc [[:li "m"] [:li nil] [:li "m"] [:li nil]]
                          "li:nth-last-child(1 of .m) { font-weight: bold }"
                          :font-weight))))

(deftest nth-child-of-a-selector-also-requires-the-element-itself-to-match
  ;; The half that is easy to leave out: an element that is at a matching
  ;; INDEX but does not itself match the clause selector is not selected.
  ;; Brave on li/li.m/li.m: `:nth-child(1 of .m)` selects the second <li>,
  ;; not the first.
  (is (= [nil "bold" nil]
         (sibling-has-doc [[:li nil] [:li "m"] [:li "m"]]
                          "li:nth-child(1 of .m) { font-weight: bold }"
                          :font-weight))))

(deftest nth-of-type-does-not-take-an-of-clause
  ;; `of` is valid on :nth-child/:nth-last-child only. `:nth-of-type(2 of
  ;; .m)` is not valid CSS, so it must select nothing rather than quietly
  ;; behaving like `:nth-child(2 of .m)`.
  (is (= [nil nil nil]
         (sibling-has-doc [[:li "m"] [:li "m"] [:li "m"]]
                          "li:nth-of-type(2 of .m) { font-weight: bold }"
                          :font-weight))))

(deftest has-pseudo-class-does-not-match-when-document-is-absent
  ;; Same documented restriction :root/:lang()/the structural pseudo-classes
  ;; already have -- the document-less 2-arity form has no `document` to
  ;; resolve any child/descendant node-id to a real node at all, so :has()
  ;; can never match there, even for a node that would otherwise match.
  (let [[div _doc] (dom/create-element dom/empty-document :div)
        selector (-> (css/parse-selector ":has(.badge)") :selector/parts first)]
    (is (false? (css/matches? {:node/id div :node/type :element :tag :div}
                               selector))
        ":has() can never match via the document-less arity")))

(deftest parses-has-into-per-occurrence-groups-with-an-optional-leading-child-combinator
  (let [has-sel (css/parse-simple-selector ".card:has(.badge, > img)")
        [group] (:selector/has has-sel)
        [badge-item img-item] group]
    (is (= ["card"] (:selector/classes has-sel))
        "the :has() argument's own class must NOT leak onto the outer
         compound selector -- same discipline :not()/:is()/:where() already
         established")
    (is (= ["badge"] (:selector/classes (:has/selector badge-item))))
    (is (false? (:has/direct-child? badge-item))
        "a plain comma-separated item with no leading combinator is the
         ANY-DESCENDANT case")
    (is (= :img (:selector/tag (:has/selector img-item))))
    (is (true? (:has/direct-child? img-item))
        "a leading `>` marks the DIRECT-CHILD-only case")))

(deftest has-contributes-the-specificity-of-its-most-specific-argument-unlike-where
  ;; Mirrors `not-and-is-contribute-the-specificity-of-their-most-specific-argument`
  ;; above exactly -- :has() is specificity-like :not()/:is(), never like
  ;; :where()'s always-zero treatment.
  (let [rules (css/parse-rules
               "div:has(.a) { color: red }
                div:has(#id, .a) { color: blue }")]
    (is (= [0 1 1] (css/specificity (-> rules first :rule/selectors first)))
        ":has(.a) adds .a's own specificity (0 class, contributing to the
         middle column) on top of the bare `div` tag selector's own (0 0 1)")
    (is (= [1 0 1] (css/specificity (-> rules second :rule/selectors first)))
        ":has(#id, .a) adds the MOST specific of its two arguments -- #id,
         not .a")))

;; ---- CSS-wide `inherit` keyword ----

(deftest inherit-keyword-resolves-to-the-parents-value-instead-of-storing-the-literal-string
  ;; Real bug this guards: `color: inherit` (an extremely common real-world
  ;; author idiom) previously stored the literal string "inherit" as the
  ;; winning declaration's value -- no downstream color parser recognizes
  ;; "inherit" as a color, so it silently rendered fully transparent,
  ;; invisible text. Confirmed via direct REPL reproduction before touching
  ;; source.
  ;;
  ;; The first fix REMOVED the property instead, leaving cssom.layout's own
  ;; `(or (:prop st) (:prop inherited))` fallback to do the inheriting.
  ;; That is right for an inherited property and WRONG for a non-inherited
  ;; one, which has no such fallback -- measured against Brave 151 on
  ;; 2026-08-05, `<div style="padding-left:40px"><p style="padding-left:
  ;; inherit">` reports 40px in the browser and reported 0 here. So
  ;; `inherit` now reads the parent's already-resolved value off its
  ;; :style/* attrs (`parent-computed-value`), for every property alike.
  ;; The value therefore APPEARS on the child, where it used to be absent;
  ;; what layout renders is unchanged, and `computed-style` is no longer
  ;; silent about it.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc root child)
        rules (css/parse-rules "div { color: red } span { color: inherit }")
        doc (css/apply-cascade doc rules)]
    (is (= "red" (get-in doc [:nodes root :attrs :style/color])))
    (is (= "red" (get-in doc [:nodes child :attrs :style/color]))
        "the winning `inherit` declaration must NOT leave the literal
         string \"inherit\" on the node -- it resolves to the parent's
         own value")))

(deftest inherit-keyword-drops-when-the-parent-resolved-nothing
  ;; The other half of `parent-computed-value`: a parent with no value of
  ;; its own leaves the property absent, which is both the initial value
  ;; for a non-inherited property and the way this engine spells "look
  ;; further up" for an inherited one.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc root child)
        rules (css/parse-rules "span { color: inherit }")
        doc (css/apply-cascade doc rules)]
    (is (nil? (get-in doc [:nodes child :attrs :style/color])))))

(deftest inherit-keyword-is-case-insensitive-and-tolerates-surrounding-whitespace
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc root child)
        rules (css/parse-rules "div { color: green } span { color:  InHeRiT  }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes child :attrs :style/color])))))

(deftest inherit-keyword-loses-to-a-later-more-specific-declaration-like-any-other-value
  ;; Sanity check that this fix doesn't special-case `inherit` OUTSIDE the
  ;; normal cascade -- a more specific declaration on the SAME element
  ;; still simply wins, exactly as if `inherit` had been any other value.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc child :class "override")
        doc (dom/append-child doc root child)
        rules (css/parse-rules "div { color: red } span { color: inherit } .override { color: purple }")
        doc (css/apply-cascade doc rules)]
    (is (= "purple" (get-in doc [:nodes child :attrs :style/color])))))

(deftest inherit-keyword-through-two-levels-with-no-intervening-rule-at-all
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [mid doc] (dom/create-element doc :section)
        doc (dom/append-child doc root mid)
        [leaf doc] (dom/create-element doc :span)
        doc (dom/append-child doc mid leaf)
        rules (css/parse-rules "div { color: blue } section { color: inherit } span { color: inherit }")
        doc (css/apply-cascade doc rules)]
    ;; The top-down walk means `section` is resolved before `span`, so the
    ;; blue `section` inherited from `div` is already on the node by the
    ;; time `span` reads it -- the chain carries a value now rather than an
    ;; absence (see inherit-keyword-resolves-to-the-parents-value... above).
    (is (= "blue" (get-in doc [:nodes leaf :attrs :style/color])))
    (is (= "blue" (get-in doc [:nodes mid :attrs :style/color])))))

(deftest inherit-keyword-resolves-through-the-real-layout-pipeline-to-the-parents-actual-color
  ;; End-to-end confirmation through the real cascade -> DOM -> layout
  ;; pipeline (cssom.layout/draw-ops), not just the raw :style/color attr
  ;; -- proving the fix genuinely reaches the visible text color, not just
  ;; the cascade's own intermediate representation.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [child doc] (dom/create-element doc :span)
        doc (dom/append-child doc root child)
        rules (css/parse-rules "div { color: red } span { color: inherit }")
        doc (css/apply-cascade doc rules)
        [txt doc] (dom/create-text-node doc "hello")
        doc (dom/append-child doc child txt)
        [_ doc] (dom/consume-ops doc)
        tree (dom/tree doc)
        ops (layout/draw-ops tree {:width 480})
        text-op (first (filter #(= :text (:draw/op %)) ops))]
    (is (= "red" (:color text-op))
        "the child's real, painted text color must be the parent's red,
         not the literal string \"inherit\" and not a default fallback")))


;; ---- CSS-wide `initial` / `unset` / `revert` ----
;;
;; Every expectation below was measured in Brave 151 on 2026-08-05, in the
;; conformance corpus's own 14px page -- see `css-wide-keywords` in
;; src/cssom/core.cljc for the table those measurements produced.

(defn- wide-keyword-doc
  "A <div> parent carrying `parent-decls` and a <p> child carrying
   `child-decls`, cascaded. Returns the child's resolved :style/* map. A
   `<p>` on purpose: it is the tag the user-agent sheet declares a margin
   and a display for, which is what separates `initial` from `unset` from
   `revert`."
  [parent-decls child-decls]
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        doc (dom/set-attribute doc root :class "parent")
        [child doc] (dom/create-element doc :p)
        doc (dom/append-child doc root child)
        rules (css/parse-rules (str ".parent { " parent-decls " } p { " child-decls " }"))
        doc (css/apply-cascade doc rules {:base-font-size 14})]
    (into {} (filter (fn [[k _]] (= "style" (namespace k)))) (get-in doc [:nodes child :attrs]))))

(deftest initial-keyword-writes-the-css-initial-value-and-beats-the-ua-sheet
  ;; Brave: `<p style="display: initial">` reports `inline`, NOT the UA
  ;; sheet's `block`, and `text-align: initial` under a `text-align:
  ;; center` parent reports `start`. Dropping the declaration -- what
  ;; `inherit` does -- would report `block` and `center` respectively.
  (let [st (wide-keyword-doc "text-align: center"
                             "display: initial; text-align: initial; margin-top: initial")]
    (is (= "inline" (:style/display st)))
    (is (= "start" (:style/text-align st)))
    (is (= 0 (:style/margin-top st)))))

(deftest unset-keyword-is-inherit-on-inherited-and-initial-on-everything-else
  ;; Brave, same page: `color: unset` under a green parent reports green;
  ;; `display: unset` reports `inline` (the initial value, not the UA
  ;; `block`); `padding-left: unset` under a 40px parent reports 0.
  (let [st (wide-keyword-doc "color: #008000; padding-left: 40px"
                             "color: unset; display: unset; padding-left: unset")]
    (is (= "#008000" (:style/color st)))
    (is (= "inline" (:style/display st)))
    (is (= 0 (:style/padding-left st)))))

(deftest revert-keyword-rolls-back-to-the-user-agent-origin
  ;; Brave: `p { margin: 0 }` plus `margin: revert` reports 14px top and
  ;; bottom (the UA `p { margin: 1em 0 }` at this page's 14px) and 0 left
  ;; and right (no UA declaration there, so the initial value). The author
  ;; rule is GONE, not outranked -- which is why the losing entries have to
  ;; survive as far as `resolve-css-wide-keyword`.
  (let [st (wide-keyword-doc "" "margin: 0; margin: revert")]
    (is (= 14 (:style/margin-top st)))
    (is (= 14 (:style/margin-bottom st)))
    (is (= 0 (:style/margin-left st)))
    (is (= 0 (:style/margin-right st))))
  ;; And a property the UA sheet says nothing about reverts to initial.
  (let [st (wide-keyword-doc "" "color: red; color: revert")]
    (is (nil? (:style/color st)))))

(deftest revert-keyword-keeps-the-ua-display-where-initial-would-not
  ;; The pair that makes `revert` a different keyword from `initial` on the
  ;; same declaration: Brave reports `block` for `display: revert` on a
  ;; `<p>` and `inline` for `display: initial`.
  (is (= "block" (:style/display (wide-keyword-doc "" "display: inline-block; display: revert"))))
  (is (= "inline" (:style/display (wide-keyword-doc "" "display: inline-block; display: initial")))))

(deftest css-wide-keyword-in-a-box-shorthand-expands-to-all-four-longhands
  ;; The shorthand has to expand or the longhands an author rule already
  ;; wrote survive underneath it -- which is exactly what left
  ;; `:cascade/revert-drops-to-the-user-agent-value` reporting 0.
  (let [st (wide-keyword-doc "" "margin: 8px; margin: initial")]
    (is (= [0 0 0 0] [(:style/margin-top st) (:style/margin-right st)
                      (:style/margin-bottom st) (:style/margin-left st)]))))

(deftest css-wide-keyword-is-only-a-keyword-as-the-whole-declaration
  ;; `margin: 1px revert` is not valid CSS, and admitting the keyword
  ;; per-token would make it look like one. Left unexpanded, as this
  ;; expander already leaves `margin: 1px solid 3px dashed` -- so the UA
  ;; sheet's own `p { margin-top: 1em }` (14 at this base size) survives
  ;; untouched, where an expansion would have written 1 or reverted.
  (let [st (wide-keyword-doc "" "margin: 1px revert")]
    (is (= 14 (:style/margin-top st)))))


;; ---- `em` / `rem` resolution and the computed font size ----
;;
;; Every number asserted below was measured in Brave 151 on 2026-08-05, on a
;; page whose container is `font-size: 14px` -- the shape the conformance
;; corpus uses -- unless the test says otherwise.

(defn- nest
  "A nest of <div class=\"l0\">/<div class=\"l1\">/... , outermost first, one
   per entry in `decls`, cascaded against a stylesheet built from those
   declarations. Returns each element's resolved `:style/*` map, outermost
   first. Declarations go through real rules rather than a `style=` attr
   because an inline style enters this namespace as `:style-inline`, which
   `kotoba-lang/htmldom` writes and this test namespace has no dependency
   on."
  ([decls] (nest decls {}))
  ([decls opts]
   (let [[ids doc] (reduce (fn [[ids doc] i]
                             (let [[id doc] (dom/create-element doc :div)
                                   doc (dom/set-attribute doc id :class (str "l" i))
                                   doc (if-let [parent (peek ids)]
                                         (dom/append-child doc parent id)
                                         (dom/set-root doc id))]
                               [(conj ids id) doc]))
                           [[] dom/empty-document]
                           (range (count decls)))
         sheet (->> decls
                    (map-indexed (fn [i d] (when d (str ".l" i " { " d " }"))))
                    (remove nil?)
                    (clojure.string/join "\n"))
         doc (css/apply-cascade doc (css/parse-rules sheet) opts)]
     (mapv (fn [id]
             (into {} (keep (fn [[k v]] (when (= "style" (namespace k)) [(keyword (name k)) v])))
                   (get-in doc [:nodes id :attrs])))
           ids))))

(deftest em-compounds-down-the-tree-rather-than-resolving-against-one-base
  ;; Brave: three nested `font-size: 1.5em` inside a 14px page report 21,
  ;; 31.5 and 47.25. This is the measurement that decides the whole design
  ;; -- a single `:base-font-size` that every `em` resolved against would
  ;; give 21 three times over.
  (let [[a b c d] (nest ["font-size: 14px" "font-size: 1.5em"
                         "font-size: 1.5em" "font-size: 1.5em"])]
    (is (= 14 (:font-size a)))
    ;; 21 rather than 21.0: an integral result is normalised back to a long
    ;; (see `as-length`), because this namespace's own `<n>px` coercion
    ;; always produced longs and everything downstream compares with `=`.
    (is (= 21 (:font-size b)))
    (is (= 31.5 (:font-size c)))
    (is (= 47.25 (:font-size d)))))

(deftest font-sizes-own-em-is-the-parents-size-every-other-em-is-its-own
  ;; The one fact a "resolve everything against one number" design gets
  ;; wrong. Brave on `<div style="font-size:2em; margin-top:1em;
  ;; padding-left:1em; width:10em">` inside 14px: font-size 28, margin-top
  ;; 28, padding-left 28, width 280 -- the font-size's own em is the
  ;; parent's 14, every other em on that element is this element's own 28.
  (let [[_ el] (nest ["font-size: 14px"
                      "font-size: 2em; margin-top: 1em; padding-left: 1em; width: 10em"])]
    (is (= 28 (:font-size el)))
    (is (= 28 (:margin-top el)))
    (is (= 28 (:padding-left el)))
    (is (= 280 (:width el)))))

(deftest an-em-length-resolves-against-an-inherited-font-size-not-a-declared-one
  ;; Brave: a div with no size of its own inside a 28px parent reports
  ;; margin-top 28px for `margin-top: 1em`. The layout table this replaced
  ;; could only see the element's OWN declared size and gave it the base --
  ;; the conformance harness charged that to the cascade as
  ;; `:cascade/inherited-font-size-chain`.
  (let [[_ _ el] (nest ["font-size: 14px" "font-size: 2em" "margin-top: 1em"])]
    (is (= 28 (:margin-top el)))))

(deftest rem-is-the-root-elements-size-not-the-parents
  ;; Brave, with `<html>` at its default 16: inside a 28px div,
  ;; `font-size: 1rem` reports 16px and `padding: 0.5rem` reports 8px. Here
  ;; the root element is the outermost div and declares 14, so rem is 14.
  (let [[_ _ el] (nest ["font-size: 14px" "font-size: 2em"
                        "font-size: 1rem; padding-top: 0.5rem"])]
    (is (= 14 (:font-size el)))
    (is (= 7 (:padding-top el)))))

(deftest a-percentage-font-size-is-the-parents-size-and-compounds
  ;; Brave: 150% of 14 is 21, then 50% of THAT is 10.5, and a `1em` margin
  ;; on the inner element is 10.5 -- a percentage participates in the chain
  ;; exactly like an em.
  (let [[_ a b] (nest ["font-size: 14px" "font-size: 150%"
                       "font-size: 50%; margin-top: 1em"])]
    (is (= 21 (:font-size a)))
    (is (= 10.5 (:font-size b)))
    (is (= 10.5 (:margin-top b)))))

(deftest a-percentage-on-any-other-property-is-left-completely-alone
  ;; `margin-top: 50%` measured 400px in an 800px box -- half the containing
  ;; BLOCK, not half a font size. Resolving it here would be a category
  ;; error, so it passes through untouched for layout to deal with.
  (let [[_ el] (nest ["font-size: 14px" "margin-top: 50%"])]
    (is (= "50%" (:margin-top el)))))

(deftest smaller-and-larger-are-the-parents-size-over-and-times-1-2-and-compound
  ;; Brave: `smaller` of 14 is 11.6667, a second `smaller` inside it is
  ;; 9.72222, and `larger` of 14 is 16.8.
  (let [[_ a b] (nest ["font-size: 14px" "font-size: smaller" "font-size: smaller"])
        [_ c] (nest ["font-size: 14px" "font-size: larger"])]
    (is (< (abs (- 11.6667 (:font-size a))) 0.0005))
    (is (< (abs (- 9.72222 (:font-size b))) 0.0005))
    (is (< (abs (- 16.8 (:font-size c))) 0.0005))))

(deftest an-absolute-font-size-keyword-is-left-unresolved-rather-than-guessed
  ;; Brave reports `font-size: medium` as 13px on a monospace page and 16px
  ;; on a proportional one: the keyword table is keyed on the default font
  ;; of the family in use, which this cascade cannot know. Left exactly as
  ;; the author wrote it, and NOT allowed to poison the chain -- the
  ;; descendant's `1em` resolves against the last size that was real.
  (let [[_ a b] (nest ["font-size: 14px" "font-size: medium" "margin-top: 1em"])]
    (is (= "medium" (:font-size a)))
    (is (= 14 (:margin-top b)))))

(defn- ua-doc
  "`tags` as children of a 14px root <div>, cascaded with no author CSS at
   all, so only the UA sheet speaks. Returns `[doc ids]`."
  [tags]
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-attribute doc root :class "root")
        doc (dom/set-root doc root)
        [ids doc] (reduce (fn [[ids doc] tag]
                            (let [[id doc] (dom/create-element doc tag)]
                              [(conj ids id) (dom/append-child doc root id)]))
                          [[] doc]
                          tags)]
    [(css/apply-cascade doc (css/parse-rules ".root { font-size: 14px }")) ids]))

(deftest the-ua-sheets-own-em-resolves-against-the-inherited-size-then-against-itself
  ;; Brave inside a 14px page: an `<h3>` reports font-size 16.38 (1.17em of
  ;; the inherited 14) and margin-block 16.38 (1em of its OWN 16.38, not of
  ;; the 14 it inherited). An `<h5>` reports 11.62 and 19.4054 (0.83em and
  ;; 1.67 of that), which is the pair that makes the two-reference-sizes
  ;; rule impossible to fake with one number.
  (let [[doc [h3 h5 p]] (ua-doc [:h3 :h5 :p])
        at (fn [id k] (get-in doc [:nodes id :attrs (keyword "style" (name k))]))]
    (is (< (abs (- 16.38 (at h3 :font-size))) 0.005))
    (is (< (abs (- 16.38 (at h3 :margin-top))) 0.005))
    (is (< (abs (- 11.62 (at h5 :font-size))) 0.005))
    (is (< (abs (- 19.4054 (at h5 :margin-top))) 0.005))
    (is (= 14 (at p :margin-top)) "a <p> is a plain 1em of the inherited 14")))

(deftest a-ua-em-follows-an-author-font-size-that-beats-the-ua-one
  ;; Brave: `<h3 style="font-size:10px">` reports margin-block 10px -- the
  ;; UA's `1em` resolves against the size that WON the cascade, not against
  ;; the UA's own 1.17em.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [h3 doc] (dom/create-element doc :h3)
        doc (dom/append-child doc root h3)
        doc (css/apply-cascade doc (css/parse-rules "h3 { font-size: 10px }"))]
    (is (= 10 (get-in doc [:nodes h3 :attrs :style/font-size])))
    (is (= 10 (get-in doc [:nodes h3 :attrs :style/margin-top])))))

(deftest a-nested-list-has-its-ua-margins-cancelled-and-a-top-level-one-does-not
  ;; Chrome's own `:is(ul,ol) ul { margin-block: 0 }`, spelled out as plain
  ;; descendant selectors. Measured on
  ;; `<ul><li>a<ul><li>b</li></ul></li></ul>`: the inner <ul> reports
  ;; margin-block 0px where the outer reports 14px, and the <li> below it
  ;; sits at y=20 rather than y=34.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-attribute doc root :class "root")
        doc (dom/set-root doc root)
        [outer doc] (dom/create-element doc :ul)
        doc (dom/append-child doc root outer)
        [li doc] (dom/create-element doc :li)
        doc (dom/append-child doc outer li)
        [inner doc] (dom/create-element doc :ul)
        doc (dom/append-child doc li inner)
        doc (css/apply-cascade doc (css/parse-rules ".root { font-size: 14px }"))]
    (is (= 14 (get-in doc [:nodes outer :attrs :style/margin-top])))
    (is (= 0 (get-in doc [:nodes inner :attrs :style/margin-top]))
        "the descendant rule wins on specificity, exactly as in a browser")))

(deftest an-author-margin-still-beats-the-nested-list-cancellation
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [outer doc] (dom/create-element doc :ul)
        doc (dom/append-child doc root outer)
        [inner doc] (dom/create-element doc :ul)
        doc (dom/append-child doc outer inner)
        doc (css/apply-cascade doc (css/parse-rules "ul ul { margin-top: 5px }"))]
    (is (= 5 (get-in doc [:nodes inner :attrs :style/margin-top])))))

(deftest a-control-gets-the-ua-control-font-size-whatever-it-inherits
  ;; Brave: an `<input>` inside a `font-size: 30px` div still reports
  ;; 13.3333px -- a control's UA font is absolute, not inherited.
  (let [[doc [input button]] (ua-doc [:input :button])]
    (is (< (abs (- 13.3333 (get-in doc [:nodes input :attrs :style/font-size]))) 0.0005))
    (is (< (abs (- 13.3333 (get-in doc [:nodes button :attrs :style/font-size]))) 0.0005))))

(deftest an-author-em-on-a-control-is-the-inherited-size-not-the-ua-control-size
  ;; Brave: `<input style="font-size:2em">` in a 14px page reports 28, not
  ;; 26.6666 -- a font-size's `em` is always the PARENT's computed size,
  ;; never the size this element would otherwise have had.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-attribute doc root :class "root")
        doc (dom/set-root doc root)
        [input doc] (dom/create-element doc :input)
        doc (dom/append-child doc root input)
        doc (css/apply-cascade doc (css/parse-rules ".root { font-size: 14px } input { font-size: 2em }"))]
    (is (= 28 (get-in doc [:nodes input :attrs :style/font-size])))))

(deftest a-radio-carries-its-own-ua-margins-not-a-checkboxs
  ;; Brave 151, 2026-08-05: a checkbox is `margin: 3px 3px 3px 4px` and a
  ;; radio is `3px 3px 0 5px`. This engine gave the radio the checkbox's
  ;; four numbers; once the UA sheet became a cascade origin the harness
  ;; charged those four values to the cascade, which is what this fixes.
  (let [[root doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc root)
        [cb doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc cb :type "checkbox")
        doc (dom/append-child doc root cb)
        [radio doc] (dom/create-element doc :input)
        doc (dom/set-attribute doc radio :type "radio")
        doc (dom/append-child doc root radio)
        doc (css/apply-cascade doc [])
        sides (fn [id] (mapv #(get-in doc [:nodes id :attrs (keyword "style" (name %))])
                             [:margin-top :margin-right :margin-bottom :margin-left]))]
    (is (= [3 3 3 4] (sides cb)))
    (is (= [3 3 0 5] (sides radio)))))

(deftest base-font-size-opt-starts-the-chain-and-is-replaced-by-the-first-declaration
  ;; What a caller who supplies nothing gets, stated as a test: the default
  ;; is this engine's own base size (see default-base-font-size for why it
  ;; is not CSS's 16), and the moment anything declares a size the opt stops
  ;; mattering at all.
  (let [margin (fn [decl opts]
                 (let [[root doc] (dom/create-element dom/empty-document :div)
                       doc (dom/set-attribute doc root :class "root")
                       doc (dom/set-root doc root)
                       [p doc] (dom/create-element doc :p)
                       doc (dom/append-child doc root p)
                       doc (css/apply-cascade doc (css/parse-rules (or decl "")) opts)]
                   (get-in doc [:nodes p :attrs :style/margin-top])))]
    (is (= css/default-base-font-size (margin nil {})))
    (is (= 32 (margin nil {:base-font-size 32})))
    (is (= 20 (margin ".root { font-size: 20px }" {:base-font-size 32}))
        "a declared size replaces the base immediately")))

(deftest an-em-line-height-computes-to-a-length-and-a-unitless-one-stays-a-factor
  ;; Brave: `line-height: 1.5em` on a 14px div computes to 21px and a CHILD
  ;; inherits that computed 21px; `line-height: 1.5` stays the factor 1.5,
  ;; which each element re-multiplies by its own size. Only the first is
  ;; this step's business -- a unitless line-height is not a length.
  (let [[_ a] (nest ["font-size: 14px" "line-height: 1.5em"])
        [_ b] (nest ["font-size: 14px" "line-height: 1.5"])]
    (is (= 21 (:line-height a)))
    (is (= "1.5" (:line-height b))
        "untouched, and untouched means the raw token -- this namespace
         has never numerically coerced a bare fractional value, and a
         unitless line-height is the one place that is exactly right")))

(deftest a-custom-propertys-own-em-is-left-to-whatever-substitutes-it
  ;; `--gap: 1em` is a raw token list, not a length on THIS element: real
  ;; CSS resolves it where the `var()` lands. Resolving it here would give
  ;; the wrong number whenever the two elements differ in size, so the
  ;; custom property passes through and the substituted value is what gets
  ;; resolved -- against the element that used it.
  (let [[_ _ el] (nest ["font-size: 14px" "--gap: 1em"
                        "font-size: 2em; margin-top: var(--gap)"])]
    (is (= 28 (:margin-top el))
        "1em of the USER's 28, not of the 14 where it was declared")))

;; ---- blockification (CSS Display 3 SS2.7) ----

(defn- blockify-case
  "Builds `<container>` with one `<span class=\"item\">` per entry in
   `items`, cascades `css` over it, and returns the resolved `display` of
   the container followed by each item's -- `nil` where the cascade wrote
   none, which is the CSS initial `inline` and is exactly the value
   blockification has to replace.

   Declarations come through real rules rather than a `style=` attribute
   for the same reason `nest` above does: an inline style enters this
   namespace as `:style-inline`, which `kotoba-lang/htmldom` writes and
   this test namespace has no dependency on. `items` is a seq of class
   names, one per span, so a rule can address each individually."
  [css items]
  (let [[container doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc container)
        doc (dom/set-attribute doc container :class "container")
        [ids doc] (reduce (fn [[ids doc] cls]
                            (let [[id doc] (dom/create-element doc :span)
                                  doc (dom/set-attribute doc id :class cls)
                                  doc (dom/append-child doc container id)
                                  [t doc] (dom/create-text-node doc "x")
                                  doc (dom/append-child doc id t)]
                              [(conj ids id) doc]))
                          [[] doc] items)
        doc (css/apply-cascade doc (css/parse-rules css))]
    (mapv #(get-in doc [:nodes % :attrs :style/display])
          (into [container] ids))))

(deftest a-flex-items-display-is-blockified-at-computed-value-time
  ;; Measured in Brave 151 on 2026-08-05 with getComputedStyle, on a span
  ;; carrying each value inside a `display: flex` parent. Before this the
  ;; cascade wrote the author's value through unchanged, which is 39 wrong
  ;; computed values on the conformance corpus -- and is also what let
  ;; cssom.layout mistake a flex item for an inline one.
  (let [[container i ib if- ig it b n c li]
        (blockify-case (str ".container { display: flex }"
                            ".ib { display: inline-block } .if { display: inline-flex }"
                            ".ig { display: inline-grid } .it { display: inline-table }"
                            ".b { display: block } .n { display: none }"
                            ".c { display: contents } .li { display: list-item }")
                       ["i" "ib" "if" "ig" "it" "b" "n" "c" "li"])]
    (is (= "flex" container) "the container itself is not an item and does not move")
    (is (= "block" i) "a bare span is inline by ABSENCE, and still has to come out block")
    (is (= "block" ib))
    (is (= "flex" if-) "inline-flex keeps its INNER display and loses only the outer half")
    (is (= "grid" ig))
    (is (= "table" it))
    (is (= "block" b) "already block-level, unchanged")
    (is (= "none" n) "display:none generates no box, so there is nothing to blockify")
    (is (= "contents" c) "measured: contents survives -- it is not a box either")
    (is (= "list-item" li) "measured: an <li> in a flex row still reports list-item")))

(deftest a-grid-item-a-float-and-an-out-of-flow-box-are-blockified-the-same-way
  ;; The three triggers produce the SAME table in Brave, which is what
  ;; makes this one rewrite rather than three.
  (let [item (fn [css] (second (blockify-case css ["x"])))]
    (is (= "block" (item ".container { display: grid }")))
    (is (= "block" (item ".container { display: inline-grid }"))
        "an inline-grid container's children are grid items too")
    (is (= "block" (item ".container { display: inline-flex }")))
    (is (= "block" (item ".x { float: left }")))
    (is (= "block" (item ".x { float: right }")))
    (is (= "block" (item ".x { position: absolute }")))
    (is (= "block" (item ".x { position: fixed }")))
    (is (= "flex" (item ".x { float: left; display: inline-flex }"))
        "a floated inline-flex box maps the same way a flex item's does")))

(deftest in-flow-positions-and-a-plain-grandchild-are-not-blockified
  ;; The negative half, and the reason `blockified?` names the two
  ;; out-of-flow positions instead of testing for `static`: measured in
  ;; Brave, a `position: sticky` span still reports `display: inline`.
  (let [item (fn [css] (second (blockify-case css ["x"])))]
    (is (nil? (item ".x { position: sticky }"))
        "sticky is in flow -- nothing is written, i.e. still the initial inline")
    (is (nil? (item ".x { position: relative }")))
    (is (nil? (item ".x { float: none }")) "`float: none` is not a float")
    (is (nil? (item ".container { display: block }"))
        "an ordinary block parent blockifies nothing"))
  (let [[container doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc container)
        doc (dom/set-attribute doc container :class "container")
        [item doc] (dom/create-element doc :div)
        doc (dom/append-child doc container item)
        [gc doc] (dom/create-element doc :span)
        doc (dom/append-child doc item gc)
        doc (css/apply-cascade doc (css/parse-rules ".container { display: flex }"))]
    (is (= "block" (get-in doc [:nodes item :attrs :style/display]))
        "the item itself is blockified")
    (is (nil? (get-in doc [:nodes gc :attrs :style/display]))
        "blockification reaches the flex container's OWN children and stops --
         a grandchild is laid out by its parent's block formatting context")))

(deftest a-display-contents-parent-does-not-hide-the-flex-container-from-its-items
  ;; `display: contents` generates no box, so the span below really is the
  ;; flex container's item and is blockified as one. Passing this
  ;; element's own `contents` down as the container display would have
  ;; stopped it -- see children-container-display.
  (let [[container doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc container)
        doc (dom/set-attribute doc container :class "container")
        [wrapper doc] (dom/create-element doc :span)
        doc (dom/set-attribute doc wrapper :class "wrapper")
        doc (dom/append-child doc container wrapper)
        [real doc] (dom/create-element doc :span)
        doc (dom/append-child doc wrapper real)
        doc (css/apply-cascade doc (css/parse-rules
                                    ".container { display: flex } .wrapper { display: contents }"))]
    (is (= "contents" (get-in doc [:nodes wrapper :attrs :style/display])))
    (is (= "block" (get-in doc [:nodes real :attrs :style/display])))))

;; ---- logical (flow-relative) box properties ----
;;
;; Every number below was measured in Brave 151 over CDP on 2026-08-06, on
;; the conformance corpus's own 14px monospace page at width 800, BEFORE
;; the code that produces it was written. `getComputedStyle` is quoted
;; because it is the direct evidence for WHERE the mapping belongs: the
;; browser reports the PHYSICAL longhand, so the rename is a
;; computed-value-time step in the cascade and not a layout-time one.

(defn- cascaded-style
  "The `:style/*` attrs `apply-cascade` writes for the element carrying
   `id`, through the real htmldom -> cssom.core pipeline."
  [css html id]
  (let [doc (-> (html/parse-into-document html)
                (css/apply-cascade (css/parse-rules (or css ""))))]
    (->> (:nodes doc)
         (some (fn [[_ n]]
                 (when (= id (get-in n [:attrs :id]))
                   (into {} (filter (fn [[k _]] (= "style" (namespace k))) (:attrs n)))))))))

(deftest logical-side-shorthands-expand-to-logical-longhands-not-physical-ones
  ;; Two values are `<start> <end>`, NOT the 1-to-4 clockwise rule the
  ;; physical shorthands use. They stay logical here because which physical
  ;; side each lands on is not known until the element's own direction is.
  (is (= {:margin-inline-start 20 :margin-inline-end 60}
         (:rule/declarations (first (css/parse-rules "#f { margin-inline: 20px 60px }")))))
  (is (= {:padding-block-start 12 :padding-block-end 24}
         (:rule/declarations (first (css/parse-rules "#f { padding-block: 12px 24px }")))))
  (is (= {:inset-inline-start 3 :inset-inline-end 9}
         (:rule/declarations (first (css/parse-rules "#f { inset-inline: 3px 9px }")))))
  ;; one value applies to both
  (is (= {:margin-inline-start 7 :margin-inline-end 7}
         (:rule/declarations (first (css/parse-rules "#f { margin-inline: 7px }")))))
  ;; three values is not a legal flow-relative shorthand: declined outright
  (is (contains? (:rule/declarations (first (css/parse-rules "#f { margin-inline: 1px 2px 3px }")))
                 :margin-inline)))

(deftest logical-border-shorthands-expand-per-logical-side
  (is (= {:border-inline-start-width 5 :border-inline-start-style "solid"
          :border-inline-start-color "#000"}
         (:rule/declarations (first (css/parse-rules "#f { border-inline-start: 5px solid #000 }")))))
  ;; `border-inline` sets BOTH sides -- measured, `border-inline: 3px solid
  ;; #000` on a 300px block reports border-left-width AND border-right-width
  ;; of 3px.
  ;; the omitted colour is reset to its initial `currentcolor`, like every
  ;; other border shorthand -- see `border-shorthand-initials`
  (is (= {:border-inline-start-width 3 :border-inline-start-style "solid"
          :border-inline-start-color "currentcolor"
          :border-inline-end-width 3 :border-inline-end-style "solid"
          :border-inline-end-color "currentcolor"}
         (:rule/declarations (first (css/parse-rules "#f { border-inline: 3px solid }"))))))

(deftest logical-properties-resolve-to-physical-ones-in-the-cascade
  ;; Brave: `margin-inline: 20px 60px` on a 300px ltr containing block puts
  ;; the box at x=20 w=220 and reports marginLeft 20px / marginRight 60px.
  (let [st (cascaded-style nil "<div style=\"width:300px\"><div id=\"a\" style=\"margin-inline: 20px 60px\">m</div></div>" "a")]
    (is (= 20 (:style/margin-left st)))
    (is (= 60 (:style/margin-right st)))
    (is (not (contains? st :style/margin-inline-start))))
  ;; ...and every other family, all measured the same way.
  (let [st (cascaded-style nil "<div id=\"a\" style=\"inline-size:120px; block-size:60px; max-inline-size:80px; min-block-size:5px\">i</div>" "a")]
    (is (= {:width 120 :height 60 :max-width 80 :min-height 5}
           (select-keys (into {} (map (fn [[k v]] [(keyword (name k)) v])) st)
                        [:width :height :max-width :min-height]))))
  (let [st (cascaded-style nil "<div id=\"a\" style=\"inset-inline-start: 30px; inset-block-start: 12px; position:absolute\">a</div>" "a")]
    (is (= 30 (:style/left st)))
    (is (= 12 (:style/top st)))))

(deftest a-logical-property-maps-by-the-elements-OWN-direction
  ;; The measurement that makes this a logical property rather than an
  ;; alias. Brave puts the box at x=60 for BOTH shapes -- `direction: rtl`
  ;; on the parent (inherited) and on the element itself -- so the rename
  ;; reads the element's own computed direction, and that is why the flow
  ;; is threaded down the cascade walk.
  (doseq [html ["<div style=\"width:300px; direction:rtl\"><div id=\"a\" style=\"margin-inline: 20px 60px\">m</div></div>"
                "<div style=\"width:300px\"><div id=\"a\" style=\"direction:rtl; margin-inline: 20px 60px\">m</div></div>"
                ;; and inherited through an intermediate element that
                ;; declares nothing
                "<div style=\"direction:rtl\"><div><div id=\"a\" style=\"margin-inline: 20px 60px\">m</div></div></div>"]]
    (let [st (cascaded-style nil html "a")]
      (is (= 60 (:style/margin-left st)) html)
      (is (= 20 (:style/margin-right st)) html)))
  ;; The BLOCK axis does not flip with `direction` -- only a writing mode
  ;; rotates it, and Brave reports marginTop 30px either way.
  (let [st (cascaded-style nil "<div style=\"direction:rtl\"><div id=\"a\" style=\"margin-block: 30px 0\">m</div></div>" "a")]
    (is (= 30 (:style/margin-top st)))))

(deftest a-logical-and-a-physical-declaration-compete-in-the-ordinary-cascade
  ;; All four measured in Brave, and they are the reason the rename happens
  ;; between the cascade's sort and its per-property winner selection
  ;; rather than on the resolved map.
  (is (= 40 (:style/margin-left
             (cascaded-style nil "<div id=\"a\" style=\"margin-left: 5px; margin-inline-start: 40px\">m</div>" "a"))))
  (is (= 5 (:style/margin-left
            (cascaded-style nil "<div id=\"a\" style=\"margin-inline-start: 40px; margin-left: 5px\">m</div>" "a"))))
  (is (= 40 (:style/margin-left
             (cascaded-style nil "<div id=\"a\" style=\"margin: 1px; margin-inline-start: 40px\">m</div>" "a"))))
  (is (= 1 (:style/margin-left
            (cascaded-style nil "<div id=\"a\" style=\"margin-inline-start: 40px; margin: 1px\">m</div>" "a"))))
  ;; SPECIFICITY still outranks source order: an `#id` physical declaration
  ;; beats a later `.class` logical one. Brave: x=5.
  (is (= 5 (:style/margin-left
            (cascaded-style "#a { margin-left: 5px } .a { margin-inline-start: 40px }"
                            "<div id=\"a\" class=\"a\">m</div>" "a"))))
  ;; ...and under `direction: rtl` the two no longer collide, so order
  ;; stops mattering: Brave reports marginLeft 5px AND marginRight 40px for
  ;; BOTH orders.
  (doseq [decl ["margin-left: 5px; margin-inline-start: 40px"
                "margin-inline-start: 40px; margin-left: 5px"]]
    (let [st (cascaded-style nil (str "<div style=\"direction:rtl\"><div id=\"a\" style=\"" decl "\">m</div></div>") "a")]
      (is (= 5 (:style/margin-left st)) decl)
      (is (= 40 (:style/margin-right st)) decl))))

(deftest a-logical-property-carrying-em-or-var-resolves-after-the-rename
  ;; The rename runs inside `resolve-style-and-flow`, i.e. BEFORE
  ;; `resolve-style-map`'s var() substitution and before
  ;; `resolve-relative-lengths` -- which is what lets both keep working
  ;; without a logical entry in `em-resolvable-properties`. Brave: x=40 and
  ;; x=25 respectively.
  (is (= 40 (:style/margin-left
             (cascaded-style nil "<div id=\"a\" style=\"font-size: 20px; margin-inline-start: 2em\">m</div>" "a"))))
  (is (= 25 (:style/margin-left
             (cascaded-style nil "<div style=\"--gap: 25px\"><div id=\"a\" style=\"margin-inline: var(--gap) 5px\">m</div></div>" "a")))))

(deftest a-vertical-writing-mode-rotates-the-logical-mapping
  ;; This test used to assert the OPPOSITE -- that a vertical writing mode
  ;; left every logical property unmapped -- and it was right to. The rename
  ;; was gated on `horizontal-tb` because the four rotated rows would have
  ;; made `getComputedStyle` correct while cssom.layout went on laying every
  ;; box out horizontally, and a mapping neither layout axis of the
  ;; conformance corpus can check is a mapping nothing keeps honest. The
  ;; gate was there so that could not happen quietly.
  ;;
  ;; What changed on 2026-08-06 is the thing the gate was waiting for:
  ;; cssom.layout lays a vertical writing mode out in a rotated basis (see
  ;; its `writing modes` section), so `inline-size: 70px` is now a 70px-tall
  ;; box on this side as well, and the geometry axis checks every row.
  ;;
  ;; Measured in Brave 151 over CDP, inside `writing-mode: vertical-rl` in a
  ;; 300x200 parent -- these four assertions are those four numbers.
  (let [st (cascaded-style nil "<div style=\"writing-mode: vertical-rl\"><div id=\"a\" style=\"margin-inline-start: 40px; inline-size: 70px; block-size: 20px; padding-block-start: 12px\">v</div></div>" "a")]
    (is (= 40 (:style/margin-top st)) "inline-start is the TOP under vertical-rl")
    (is (= 70 (:style/height st)) "inline-size is a HEIGHT under vertical-rl")
    (is (= 20 (:style/width st)) "block-size is a WIDTH under vertical-rl")
    (is (= 12 (:style/padding-right st)) "block-start is the RIGHT under vertical-rl")
    (is (nil? (:style/margin-left st)))))

(deftest direction-reverses-the-inline-axis-of-a-vertical-writing-mode
  ;; The control beside the test above, and the reason `logical-flow-sides`
  ;; is keyed by BOTH properties: `direction` swaps the inline pair and
  ;; never touches the block pair, in a vertical mode exactly as in a
  ;; horizontal one. Measured, same probe, `direction: rtl` added.
  (let [vrl (cascaded-style nil "<div style=\"writing-mode: vertical-rl; direction: rtl\"><div id=\"a\" style=\"margin-inline-start: 40px; margin-block-start: 13px\">v</div></div>" "a")
        htb (cascaded-style nil "<div style=\"direction: rtl\"><div id=\"a\" style=\"margin-inline-start: 40px; margin-block-start: 13px\">v</div></div>" "a")]
    (is (= 40 (:style/margin-bottom vrl)) "vertical-rl + rtl: inline-start is the BOTTOM")
    (is (= 13 (:style/margin-right vrl)) "...and block-start is still the RIGHT")
    (is (= 40 (:style/margin-right htb)) "horizontal-tb + rtl: inline-start is the RIGHT")
    (is (= 13 (:style/margin-top htb)) "...and block-start is still the TOP")))

(deftest sideways-lr-is-the-one-mode-whose-inline-axis-runs-the-other-way
  ;; `vertical-rl`, `vertical-lr` and `sideways-rl` share an inline
  ;; direction (top to bottom) and differ only in which physical side the
  ;; block axis starts on. `sideways-lr` differs in BOTH. Measured in Brave
  ;; with the same `margin-inline-start: 40px; margin-block-start: 13px`
  ;; probe; the control is `sideways-rl`, which must stay identical to
  ;; `vertical-rl`.
  (let [side (fn [mode k]
               (k (cascaded-style nil (str "<div style=\"writing-mode: " mode "\"><div id=\"a\" style=\"margin-inline-start: 40px; margin-block-start: 13px\">v</div></div>") "a")))]
    (is (= 40 (side "sideways-lr" :style/margin-bottom)))
    (is (= 13 (side "sideways-lr" :style/margin-left)))
    (is (= 40 (side "vertical-lr" :style/margin-top)))
    (is (= 13 (side "vertical-lr" :style/margin-left)))
    (is (= 40 (side "sideways-rl" :style/margin-top)))
    (is (= 13 (side "sideways-rl" :style/margin-right)))))

(deftest a-percentage-box-shorthand-expands-per-side-as-the-raw-value
  ;; Admitted since cssom.layout learned to resolve one. Brave: `padding:
  ;; 10% 20%` inside a 300x100 block is 30px top/bottom and 60px
  ;; left/right -- both axes of the WIDTH, which is why expanding per side
  ;; is as well defined for a percentage as for a px length.
  (is (= {:padding "10%" :padding-top "10%" :padding-right "20%"
          :padding-bottom "10%" :padding-left "20%"}
         (:rule/declarations (first (css/parse-rules "#f { padding: 10% 20% }")))))
  (is (= {:margin-inline-start "20%" :margin-inline-end "20%"}
         (:rule/declarations (first (css/parse-rules "#f { margin-inline: 20% }"))))))

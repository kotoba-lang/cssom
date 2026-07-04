(ns cssom.core-test
  (:require [cssom.core :as css]
            [clojure.test :refer [deftest is]]
            [kotoba.wasm.dom :as dom]))

(deftest parses-simple-selector-rules
  (let [rules (css/parse-rules "main.note, #hero { color: red; padding: 8px } .muted { color: gray }")]
    (is (= 2 (count rules)))
    (is (= {:color "red" :padding 8}
           (:rule/declarations (first rules))))
    (is (= :main (-> rules first :rule/selectors first :selector/parts first :selector/tag)))
    (is (= ["note"] (-> rules first :rule/selectors first :selector/parts first :selector/classes)))))

(deftest parses-attribute-selectors-and-important-declarations
  (let [rules (css/parse-rules "input[required], [data-mode=\"edit\"] { border-width: 2px !important; color: red }")]
    (is (= {:border-width 2 :color "red"}
           (:rule/declarations (first rules))))
    (is (= [{:attr/name :required :attr/operator nil :attr/value nil}]
           (-> rules first :rule/selectors first :selector/parts first :selector/attrs)))
    (is (= [{:attr/name :data-mode :attr/operator "=" :attr/value "edit"}]
           (-> rules first :rule/selectors second :selector/parts first :selector/attrs)))
    (is (= true (get-in (first rules) [:rule/declaration-meta :border-width :important?])))))

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

(deftest parses-form-state-pseudo-classes
  (let [rules (css/parse-rules "input:disabled, input:enabled, input:checked, input:required, input:optional, input:read-only, input:read-write, input:invalid, input:valid, input:focus { color: red }")
        pseudos (mapv #(-> % :selector/parts first :selector/pseudos first)
                      (-> rules first :rule/selectors))]
    (is (= [:disabled :enabled :checked :required :optional :read-only :read-write :invalid :valid :focus] pseudos))
    (is (= [0 1 1] (css/specificity (-> rules first :rule/selectors first))))))

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

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

(deftest unlayered-important-declaration-still-beats-every-layered-important-declaration
  (let [[div doc] (dom/create-element dom/empty-document :div)
        doc (dom/set-root doc div)
        doc (dom/set-attribute doc div :id "hero")
        rules (css/parse-rules
               "@layer a { #hero { color: red !important } }
                @layer b { #hero { color: blue !important } }
                div { color: green !important }")
        doc (css/apply-cascade doc rules)]
    (is (= "green" (get-in doc [:nodes div :attrs :style/color]))
        "even though !important reverses layer order among layered
         declarations, an unlayered !important declaration still wins over
         every layered !important declaration, no matter the layer or
         specificity of the layered rules -- unlayered-beats-layered holds
         for both importance groups")))

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

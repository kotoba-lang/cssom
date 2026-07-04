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

;; ---- structural pseudo-classes: :first-child/:last-child/:only-child/
;;      :nth-child(), and their same-tag :first-of-type/:last-of-type/
;;      :nth-of-type() counterparts ----
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
  ;; specificity) `color` declaration on empty-div.
  (let [[root doc] (dom/create-element dom/empty-document :html)
        doc (dom/set-root doc root)
        [empty-div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root empty-div)
        [full-div doc] (dom/create-element doc :div)
        doc (dom/append-child doc root full-div)
        [t doc] (dom/create-text-node doc "hi")
        doc (dom/append-child doc full-div t)
        rules (css/parse-rules
               "* { outline: 1px }
                :root { color: purple; padding: 9px }
                div:empty { color: red }")
        doc (css/apply-cascade doc rules)]
    (is (= 1 (get-in doc [:nodes root :attrs :style/outline]))
        "sanity check: `*` applies to the root too -- confirms the cascade
         wiring itself, independent of :root/:empty")
    (is (= 1 (get-in doc [:nodes empty-div :attrs :style/outline])))
    (is (= 1 (get-in doc [:nodes full-div :attrs :style/outline])))
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

(ns cssom.core
  "Small CSS selector/cascade subset for kotoba documents.

   Split out of kotoba-lang/browser (ADR-2607051140). Not to be confused with
   kotoba-lang/css, an unrelated EDN-as-CSS-data renderer (data -> CSS text);
   this namespace goes the other direction: parses CSS text and resolves the
   cascade against a kotoba.wasm.dom document.

   Beyond tag/id/class/attribute simple selectors and the child/descendant
   combinators, this namespace also supports:
   - Sibling combinators `+` (adjacent) and `~` (general/subsequent).
   - `::before` / `::after` pseudo-elements (also the legacy single-colon
     `:before`/`:after` spelling). kotoba.wasm.dom has no generated-content
     DOM node concept, so pseudo-element declarations are resolved into a
     synthetic `:pseudo/before` / `:pseudo/after` attribute on the *real*
     element (`apply-cascade`/`computed-style`), holding that
     pseudo-element's own cascade-resolved style map -- also directly
     queryable via `pseudo-element-style-for` without a full apply-cascade
     round trip. A `content` declaration that is a quoted string literal
     (`content: \"...\";` / `'...'`, including the empty string) is unquoted
     into a plain string under `:content`; unsupported forms (`attr(...)`,
     `counter(...)`, `url(...)`, `none`/`normal`, unquoted text) simply leave
     `:content` unset rather than storing something unusable. `cssom.layout`
     reads these attributes and paints generated content as real text
     immediately before/after the element's real children -- see its
     namespace docstring.
   - `@media (min-width: Npx)` / `(max-width: Npx)` conditional rule blocks
     (optionally combined with `and`), evaluated against a viewport width
     passed to `apply-cascade` via an options map (default
     `default-viewport-width`).
   - CSS custom properties (`--foo: value`) and `var(--foo[, fallback])`
     resolution, inherited top-down the same way `apply-cascade` walks the
     document from its root.
   - `@layer <name> { ... }` cascade layers, plus the bare
     `@layer name1, name2;` ordering statement. For normal (non-`!important`)
     declarations, a later-declared layer beats an earlier one; for
     `!important` declarations, real CSS *reverses* that order (an
     earlier-declared layer beats a later one), and this engine implements
     that reversal too. Either way, any unlayered declaration beats every
     layered one of the same importance regardless of specificity -- layer
     membership is checked before specificity, not instead of it. See
     `parse-rules` for exactly how layer name -> priority resolution works,
     and its docstring for the remaining known simplifications (anonymous
     `@layer { ... }` blocks treated as unlayered, `@media` nested inside
     `@layer` loses its layer tag); see `resolve-style-for` for the
     `!important` reversal mechanics and a known gap in inline-style
     `!important` support."
  (:require [clojure.string :as str]
            [kotoba.wasm.dom :as dom]))

(defn- parse-style-value
  [v]
  (let [v (str/trim (str v))]
    (cond
      (re-matches #"-?\d+" v) #?(:clj (Long/parseLong v) :cljs (js/parseInt v 10))
      (re-matches #"-?\d+px" v) #?(:clj (Long/parseLong (subs v 0 (- (count v) 2)))
                                   :cljs (js/parseInt v 10))
      :else v)))

(def ^:private content-literal-pattern
  "Matches a single quoted string literal, double- or single-quoted --
   `\"...\"` / `'...'`, including the empty string. See
   `parse-content-literal` for what this is used for and why it is
   deliberately this narrow."
  #"\"([^\"]*)\"|'([^']*)'")

(defn- parse-content-literal
  "Parses a CSS `content` declaration's raw value into the literal string it
   designates, for the narrow, common real-world case of a single quoted
   string (`content: \"some text\";` / `content: '...';`), including the
   empty string (`content: \"\";` -- a common icon-only generated-content
   idiom, still a real declared value, not the same as `content` being
   absent). Anything else this engine doesn't support (`attr(...)`,
   `counter(...)`, `url(...)`, `none`, `normal`, unquoted/unmatched text)
   returns nil rather than guessing -- callers treat nil exactly like
   `content` being absent: no generated-content box, no crash."
  [v]
  (when-let [[_ double-quoted single-quoted] (re-matches content-literal-pattern (str/trim (str v)))]
    (or double-quoted single-quoted "")))

(defn- parse-property-value
  "Parses a single declaration's raw value string for property `k` (still a
   raw string at this point, not yet keywordized). `content` gets its own
   quoted-string-literal parsing (see `parse-content-literal`); every other
   property keeps the existing numeric/px coercion (`parse-style-value`).
   May return nil (currently only possible for an unparseable `content`
   value) -- callers drop the declaration entirely in that case rather than
   storing an unusable value."
  [k v]
  (if (= "content" (str/lower-case k))
    (parse-content-literal v)
    (parse-style-value v)))

(defn parse-declarations
  [text]
  (->> (str/split (or text "") #";")
       (keep (fn [decl]
               (let [[k v] (map str/trim (str/split decl #":" 2))]
                 (when (and (seq k) (seq v))
                   (let [value (parse-property-value k (str/replace v #"(?i)\s*!important\s*$" ""))]
                     (when (some? value)
                       [(keyword k) value]))))))
       (into {})))

(defn- parse-declarations-with-importance
  [text]
  (->> (str/split (or text "") #";")
       (keep (fn [decl]
               (let [[k v] (map str/trim (str/split decl #":" 2))]
                 (when (and (seq k) (seq v))
                   (let [important? (boolean (re-find #"(?i)!important\s*$" v))
                         value (str/replace v #"(?i)\s*!important\s*$" "")
                         parsed (parse-property-value k value)]
                     (when (some? parsed)
                       [(keyword k) {:value parsed
                                     :important? important?}]))))))
       (into {})))

(defn- parse-attribute-selector
  [text]
  (when-let [[_ attr operator double-quoted single-quoted unquoted]
             (re-matches #"\[\s*([A-Za-z_][-A-Za-z0-9_]*)\s*(?:(~=|\|=|\^=|\$=|\*=|=)\s*(?:\"([^\"]*)\"|'([^']*)'|([^\]\s]+)))?\s*\]" text)]
    {:attr/name (keyword attr)
     :attr/operator operator
     :attr/value (or double-quoted single-quoted unquoted)}))

(def attribute-selector-pattern
  #"\[\s*[A-Za-z_][-A-Za-z0-9_]*\s*(?:(?:~=|\|=|\^=|\$=|\*=|=)\s*(?:\"[^\"]*\"|'[^']*'|[^\]\s]+))?\s*\]")

(def pseudo-class-pattern
  #":([A-Za-z_][-A-Za-z0-9_]*)")

(def pseudo-element-pattern
  "Matches `::before`/`::after` and the legacy single-colon `:before`/`:after`
   spelling. Deliberately narrower than a generic `::foo` pattern -- this
   subset only supports before/after generated content (see namespace doc)."
  #"(?i)::?(before|after)\b")

(defn- append-token
  [tokens token]
  (cond-> tokens
    (not (str/blank? token)) (conj token)))

(defn selector-tokens
  [selector]
  (let [s (str selector)
        n (count s)]
    (loop [idx 0
           start 0
           bracket-depth 0
           quote-char nil
           tokens []]
      (if (= idx n)
        (append-token tokens (str/trim (subs s start idx)))
        (let [ch (nth s idx)
              escaped? (and (pos? idx) (= \\ (nth s (dec idx))))]
          (cond
            (and quote-char (= ch quote-char) (not escaped?))
            (recur (inc idx) start bracket-depth nil tokens)

            quote-char
            (recur (inc idx) start bracket-depth quote-char tokens)

            (or (= ch \") (= ch \'))
            (recur (inc idx) start bracket-depth ch tokens)

            (= ch \[)
            (recur (inc idx) start (inc bracket-depth) quote-char tokens)

            (= ch \])
            (recur (inc idx) start (max 0 (dec bracket-depth)) quote-char tokens)

            (and (contains? #{\> \+ \~} ch) (zero? bracket-depth))
            (recur (inc idx)
                   (inc idx)
                   bracket-depth
                   quote-char
                   (-> tokens
                       (append-token (str/trim (subs s start idx)))
                       (conj (str ch))))

            (and (str/blank? (str ch)) (zero? bracket-depth))
            (recur (inc idx)
                   (inc idx)
                   bracket-depth
                   quote-char
                   (append-token tokens (str/trim (subs s start idx))))

            :else
            (recur (inc idx) start bracket-depth quote-char tokens)))))))

(defn parse-simple-selector
  [selector]
  (let [s (str/trim selector)
        selector-without-attrs (str/replace s attribute-selector-pattern "")
        pseudo-element (some-> (re-find pseudo-element-pattern selector-without-attrs)
                                second str/lower-case keyword)
        selector-sans-pseudo-element (str/replace selector-without-attrs pseudo-element-pattern "")
        selector-without-pseudos (str/replace selector-sans-pseudo-element pseudo-class-pattern "")
        tag (second (re-find #"^([A-Za-z][A-Za-z0-9_-]*)" selector-without-attrs))
        id (second (re-find #"#([A-Za-z_][-A-Za-z0-9_]*)" selector-without-pseudos))
        classes (mapv second (re-seq #"\.([A-Za-z_][-A-Za-z0-9_]*)" selector-without-pseudos))
        attrs (mapv parse-attribute-selector
                    (re-seq attribute-selector-pattern s))
        pseudos (mapv (comp keyword str/lower-case second)
                      (re-seq pseudo-class-pattern selector-sans-pseudo-element))]
    {:selector/raw s
     :selector/tag (when (seq tag) (keyword (str/lower-case tag)))
     :selector/id id
     :selector/classes classes
     :selector/attrs (filterv some? attrs)
     :selector/pseudos pseudos
     :selector/pseudo-element pseudo-element}))

(defn parse-selector
  [selector]
  (let [tokens (selector-tokens selector)
        [_ parts] (reduce (fn [[combinator parts] token]
                            (case token
                              ">" [:child parts]
                              "+" [:next-sibling parts]
                              "~" [:subsequent-sibling parts]
                              [nil (conj parts
                                         (assoc (parse-simple-selector token)
                                                :selector/combinator
                                                (if (seq parts)
                                                  (or combinator :descendant)
                                                  nil)))]))
                          [nil []]
                          (remove str/blank? tokens))]
    {:selector/raw (str/trim (or selector ""))
     :selector/parts parts}))

(defn split-selector-list
  [selector-list]
  (let [s (str selector-list)
        n (count s)]
    (loop [idx 0
           start 0
           bracket-depth 0
           quote-char nil
           selectors []]
      (if (= idx n)
        (->> (conj selectors (subs s start idx))
             (map str/trim)
             (remove str/blank?)
             vec)
        (let [ch (nth s idx)
              escaped? (and (pos? idx) (= \\ (nth s (dec idx))))]
          (cond
            (and quote-char (= ch quote-char) (not escaped?))
            (recur (inc idx) start bracket-depth nil selectors)

            quote-char
            (recur (inc idx) start bracket-depth quote-char selectors)

            (or (= ch \") (= ch \'))
            (recur (inc idx) start bracket-depth ch selectors)

            (= ch \[)
            (recur (inc idx) start (inc bracket-depth) quote-char selectors)

            (= ch \])
            (recur (inc idx) start (max 0 (dec bracket-depth)) quote-char selectors)

            (and (= ch \,) (zero? bracket-depth))
            (recur (inc idx) (inc idx) bracket-depth quote-char (conj selectors (subs s start idx)))

            :else
            (recur (inc idx) start bracket-depth quote-char selectors)))))))

(defn specificity
  [selector]
  (let [parts (or (:selector/parts selector) [selector])]
    [(reduce + (map #(if (:selector/id %) 1 0) parts))
     (reduce + (map #(+ (count (:selector/classes %))
                        (count (:selector/attrs %))
                        (count (:selector/pseudos %)))
                    parts))
     (reduce + (map #(+ (if (:selector/tag %) 1 0)
                        (if (:selector/pseudo-element %) 1 0))
                    parts))]))

(defn- find-matching-brace
  "Index of the `}` that closes the `{` at `open-idx`, honoring nested braces
   (so a `@media { selector { decls } }` block's outer `}` isn't mistaken for
   the inner rule's `}`)."
  [s open-idx]
  (let [n (count s)]
    (loop [idx (inc open-idx) depth 1]
      (when (< idx n)
        (let [ch (nth s idx)]
          (cond
            (= ch \{) (recur (inc idx) (inc depth))
            (= ch \}) (if (= depth 1) idx (recur (inc idx) (dec depth)))
            :else (recur (inc idx) depth)))))))

(defn- split-media-segments
  "Splits raw CSS text into an ordered sequence of
   {:segment/type :plain :segment/text \"...\"} and
   {:segment/type :media :segment/condition \"(min-width: 600px)\" :segment/text \"...\"}
   segments, preserving source order (so :rule/order stays stable across
   plain and @media-wrapped rules)."
  [css]
  (let [s (str (or css ""))
        n (count s)]
    (loop [idx 0 segments []]
      (let [at-idx (str/index-of s "@media" idx)]
        (if (nil? at-idx)
          (cond-> segments
            (< idx n) (conj {:segment/type :plain :segment/text (subs s idx)}))
          (let [brace-open (str/index-of s "{" at-idx)]
            (if (nil? brace-open)
              (conj segments {:segment/type :plain :segment/text (subs s idx)})
              (let [condition (str/trim (subs s (+ at-idx (count "@media")) brace-open))
                    brace-close (find-matching-brace s brace-open)]
                (if (nil? brace-close)
                  (conj segments {:segment/type :plain :segment/text (subs s idx)})
                  (recur (inc brace-close)
                         (cond-> segments
                           (> at-idx idx) (conj {:segment/type :plain
                                                 :segment/text (subs s idx at-idx)})
                           true (conj {:segment/type :media
                                       :segment/condition condition
                                       :segment/text (subs s (inc brace-open) brace-close)}))))))))))))

(defn- split-layer-segments
  "Splits raw CSS text into an ordered sequence of
   {:segment/type :plain :segment/text \"...\"} and
   {:segment/type :layer :segment/name \"foo\" :segment/text \"...\"}
   segments, preserving source order -- mirrors `split-media-segments`
   exactly, brace-depth-aware via the same `find-matching-brace`.

   Also recognizes the bare `@layer name1, name2;` ordering statement (no
   braces -- CSS's way of fixing layer priority up front, before any of
   those layers' rules are actually written). That statement carries no
   rules of its own, so it is simply dropped from the segment stream here;
   its declared order is picked up separately by `layer-declaration-order`,
   which scans the same text independently."
  [css]
  (let [s (str (or css ""))
        n (count s)]
    (loop [idx 0 segments []]
      (let [at-idx (str/index-of s "@layer" idx)]
        (if (nil? at-idx)
          (cond-> segments
            (< idx n) (conj {:segment/type :plain :segment/text (subs s idx)}))
          (let [brace-idx (str/index-of s "{" at-idx)
                semi-idx (str/index-of s ";" at-idx)
                bare-statement? (and semi-idx (or (nil? brace-idx) (< semi-idx brace-idx)))]
            (cond
              bare-statement?
              (recur (inc semi-idx)
                     (cond-> segments
                       (> at-idx idx) (conj {:segment/type :plain
                                             :segment/text (subs s idx at-idx)})))

              (nil? brace-idx)
              (conj segments {:segment/type :plain :segment/text (subs s idx)})

              :else
              (let [layer-name (str/trim (subs s (+ at-idx (count "@layer")) brace-idx))
                    brace-close (find-matching-brace s brace-idx)]
                (if (nil? brace-close)
                  (conj segments {:segment/type :plain :segment/text (subs s idx)})
                  (recur (inc brace-close)
                         (cond-> segments
                           (> at-idx idx) (conj {:segment/type :plain
                                                 :segment/text (subs s idx at-idx)})
                           true (conj {:segment/type :layer
                                       :segment/name layer-name
                                       :segment/text (subs s (inc brace-idx) brace-close)}))))))))))))

(defn- layer-declaration-order
  "Scans `css` left-to-right for every point a layer name is *first named* --
   either a bare `@layer a, b;` ordering statement's comma list, or a
   `@layer name { ... }` block's opening -- and returns those names in
   source order (not yet deduped; callers dedup keeping the first
   occurrence). Matches real CSS: a layer's priority is fixed at the point
   it is first named, whichever form that takes, so a bare ordering
   statement earlier in the source wins over a later block's position."
  [css]
  (let [s (str (or css ""))
        n (count s)]
    (loop [idx 0 names []]
      (let [at-idx (str/index-of s "@layer" idx)]
        (if (nil? at-idx)
          names
          (let [brace-idx (str/index-of s "{" at-idx)
                semi-idx (str/index-of s ";" at-idx)
                bare-statement? (and semi-idx (or (nil? brace-idx) (< semi-idx brace-idx)))]
            (cond
              bare-statement?
              (let [declared (->> (str/split (subs s (+ at-idx (count "@layer")) semi-idx) #",")
                                   (map str/trim)
                                   (remove str/blank?))]
                (recur (inc semi-idx) (into names declared)))

              (nil? brace-idx)
              names

              :else
              (let [layer-name (str/trim (subs s (+ at-idx (count "@layer")) brace-idx))
                    brace-close (find-matching-brace s brace-idx)]
                (if (nil? brace-close)
                  names
                  (recur (inc brace-close)
                         (cond-> names
                           (not (str/blank? layer-name)) (conj layer-name))))))))))))

(defn- layer-priority-order
  "Distinct layer names in declared/encountered priority order (see
   `layer-declaration-order`): index 0 is the lowest priority (loses ties),
   higher indices win -- a later-declared/encountered layer beats an
   earlier one, matching real CSS cascade-layer semantics."
  [css]
  (vec (distinct (layer-declaration-order css))))

(defn- parse-rules-raw
  "Parses `selector { decls }` pairs with no @media awareness. Returns rule
   maps without :rule/order or :rule/media (callers attach those)."
  [css]
  (->> (re-seq #"(?s)([^{}]+)\{([^{}]+)\}" (or css ""))
       (map (fn [[_ selector-text body]]
              {:rule/selectors (mapv parse-selector (split-selector-list selector-text))
               :rule/declarations (parse-declarations body)
               :rule/declaration-meta (parse-declarations-with-importance body)}))))

(defn parse-rules
  "Parses raw CSS text into rule maps. Rules nested inside an `@media (...)`
   block carry that condition (raw text, e.g. \"(min-width: 600px)\") under
   :rule/media; top-level rules have :rule/media nil (always applies).
   `apply-cascade` decides which :rule/media conditions currently hold.

   Rules nested inside an `@layer <name> { ... }` block carry that name
   under :rule/layer; top-level (non-`@layer`) rules have :rule/layer nil,
   meaning \"unlayered\". Each rule also carries :rule/layer-priority, an
   integer resolved from the whole stylesheet's declared/encountered layer
   order (see `layer-priority-order`): a bare `@layer a, b;` ordering
   statement fixes priority up front if present, otherwise a layer's
   priority is the order it is first named, by that statement or its first
   `@layer name { ... }` block, whichever comes first in the source.
   Unlayered rules always resolve to one past the highest named-layer index
   (real CSS: unlayered author styles beat every layered one). Downstream,
   `resolve-style-for` sorts on :rule/layer-priority, not the raw name.

   Known simplifications:
   - Anonymous `@layer { ... }` blocks (no name) are treated as unlayered
     rather than as their own distinct anonymous layer.
   - `@media` nested inside `@layer` loses the outer layer tag on that
     nested block's rules (they still get :rule/media correctly, just fall
     back to :rule/layer nil); `@layer` nested inside `@media` is fully
     supported."
  [css]
  (let [css (str (or css ""))
        layer-order (layer-priority-order css)
        layer-index (into {} (map-indexed (fn [idx name] [name idx]) layer-order))
        unlayered-priority (count layer-order)
        priority-for (fn [layer-name]
                       (if (nil? layer-name)
                         unlayered-priority
                         (get layer-index layer-name unlayered-priority)))]
    (->> (split-media-segments css)
         (mapcat (fn [{:segment/keys [type text condition]}]
                   (let [media (when (= type :media) condition)]
                     (->> (split-layer-segments text)
                          (mapcat (fn [layer-segment]
                                    (let [layer-name (when (= :layer (:segment/type layer-segment))
                                                        (:segment/name layer-segment))]
                                      (map #(assoc %
                                                   :rule/media media
                                                   :rule/layer layer-name
                                                   :rule/layer-priority (priority-for layer-name))
                                           (parse-rules-raw (:segment/text layer-segment))))))))))
         (map-indexed (fn [idx rule] (assoc rule :rule/order idx)))
         vec)))

(defn- parse-media-width
  [s]
  #?(:clj (Long/parseLong s)
     :cljs (js/parseInt s 10)))

(def ^:private media-feature-pattern
  #"(?i)\(\s*(min-width|max-width)\s*:\s*(\d+)(?:px)?\s*\)")

(defn media-condition-matches?
  "Evaluates a raw @media condition (as stored in :rule/media) against a
   viewport width in px. Supports `(min-width: Npx)` / `(max-width: Npx)`,
   combined with `and`; a bare `screen`/`all` media type always matches,
   `print` never does; anything else unrecognized is treated as matching
   (so unsupported media features don't silently hide rules)."
  [condition viewport-width]
  (let [condition (str/replace (str condition) #"(?i)^\s*@media\s*" "")
        parts (->> (str/split condition #"(?i)\s+and\s+")
                   (map str/trim)
                   (remove str/blank?))]
    (every? (fn [part]
              (let [lower (str/lower-case part)]
                (cond
                  (= lower "print") false
                  (contains? #{"screen" "all"} lower) true
                  :else
                  (if-let [[_ kind value] (re-matches media-feature-pattern part)]
                    (let [n (parse-media-width value)]
                      (case (str/lower-case kind)
                        "min-width" (>= viewport-width n)
                        "max-width" (<= viewport-width n)
                        true))
                    true))))
            parts)))

(def default-viewport-width
  "Viewport width (px) `apply-cascade` assumes for @media evaluation when the
   caller doesn't pass an explicit :viewport-width. Matches
   kotoba-lang/browser's own default viewport [800 600]."
  800)

(defn- rule-applies-to-viewport?
  [rule viewport-width]
  (let [media (:rule/media rule)]
    (or (nil? media) (media-condition-matches? media viewport-width))))

(defn- classes
  [node]
  (set (remove str/blank? (str/split (str (get-in node [:attrs :class] "")) #"\s+"))))

(defn- truthy-attr? [v]
  (or (= true v)
      (= "true" v)
      (= "" v)
      (and (string? v)
           (not (str/blank? v))
           (not= "false" (str/lower-case v)))))

(defn- parse-int
  [v]
  (when (some? v)
    (let [s (str v)]
      (when (re-matches #"-?\d+" s)
        #?(:clj (Long/parseLong s)
           :cljs (js/parseInt s 10))))))

(def form-control-tags #{:button :input :select :textarea})

(def disabled-capable-tags #{:button :fieldset :input :optgroup :option :select :textarea})

(def editable-form-control-tags #{:input :textarea})

(defn- input-type
  [node]
  (str/lower-case (str (or (get-in node [:attrs :type]) "text"))))

(defn- hidden-input-control?
  [node]
  (and (= :input (:tag node))
       (= "hidden" (input-type node))))

(defn- file-input-control?
  [node]
  (and (= :input (:tag node))
       (= "file" (input-type node))))

(defn- editable-form-control?
  [node]
  (and (contains? editable-form-control-tags (:tag node))
       (not (hidden-input-control? node))
       (not (file-input-control? node))))

(defn- form-control?
  [node]
  (contains? form-control-tags (:tag node)))

(defn- disabled-capable-control?
  [node]
  (contains? disabled-capable-tags (:tag node)))

(defn- descendant-node-ids
  ([document node-id]
   (descendant-node-ids document node-id #{}))
  ([document node-id visited]
   (when-not (contains? visited node-id)
     (let [visited (conj visited node-id)]
       (mapcat (fn [child-id]
                 (cons child-id (descendant-node-ids document child-id visited)))
               (get-in document [:nodes node-id :children]))))))

(defn- parent-node-id
  [document child-id]
  (some (fn [[node-id node]]
          (when (some #{child-id} (:children node))
            node-id))
        (:nodes document)))

(defn- descendant-or-self?
  [document ancestor-id node-id]
  (or (= ancestor-id node-id)
      (some (fn [child-id]
              (descendant-or-self? document child-id node-id))
            (get-in document [:nodes ancestor-id :children]))))

(defn- first-legend-child-id
  [document fieldset-id]
  (first (filter #(= :legend (get-in document [:nodes % :tag]))
                 (get-in document [:nodes fieldset-id :children]))))

(defn- disabled-by-fieldset?
  [document node]
  (loop [parent-id (parent-node-id document (:node/id node))]
    (when parent-id
      (let [parent (get-in document [:nodes parent-id])]
        (if (and (= :fieldset (:tag parent))
                 (truthy-attr? (get-in parent [:attrs :disabled]))
                 (not (when-let [legend-id (first-legend-child-id document parent-id)]
                        (descendant-or-self? document legend-id (:node/id node)))))
          true
          (recur (parent-node-id document parent-id)))))))

(defn- disabled-by-optgroup?
  [document node]
  (when (= :option (:tag node))
    (loop [parent-id (parent-node-id document (:node/id node))]
      (when parent-id
        (let [parent (get-in document [:nodes parent-id])]
          (if (= :optgroup (:tag parent))
            (truthy-attr? (get-in parent [:attrs :disabled]))
            (recur (parent-node-id document parent-id))))))))

(defn- disabled-control?
  [document node]
  (and (disabled-capable-control? node)
       (or (truthy-attr? (get-in node [:attrs :disabled]))
           (disabled-by-fieldset? document node)
           (disabled-by-optgroup? document node))))

(defn- text-content
  [document node-id]
  (let [node (get-in document [:nodes node-id])]
    (case (:node/type node)
      :text (:text node)
      :element (str/join "" (map #(text-content document %) (:children node)))
      "")))

(defn- option-value
  [document option-id]
  (let [attrs (get-in document [:nodes option-id :attrs])]
    (str (if (contains? attrs :value)
           (:value attrs)
           (text-content document option-id)))))

(defn- selected-option-id
  [document select-id]
  (let [options (->> (descendant-node-ids document select-id)
                     (filter #(= :option (get-in document [:nodes % :tag]))))
        selected-options (filter #(truthy-attr? (get-in document [:nodes % :attrs :selected]))
                                 options)
        enabled-option? #(not (disabled-control? document (get-in document [:nodes %])))
        multiple? (truthy-attr? (get-in document [:nodes select-id :attrs :multiple]))]
    (or (first (filter enabled-option? selected-options))
        (when (and (empty? selected-options) (not multiple?))
          (first (filter enabled-option? options))))))

(defn- select-value
  [document node]
  (when-let [option-id (and document (selected-option-id document (:node/id node)))]
    (option-value document option-id)))

(defn- radio-group-node-ids
  [document node]
  (let [name (get-in node [:attrs :name])
        form (get-in node [:attrs :form])
        root (:root document)]
    (->> (descendant-node-ids document root)
         (filter (fn [node-id]
                   (let [candidate (get-in document [:nodes node-id])]
                     (and (= :input (:tag candidate))
                          (= "radio" (str/lower-case (str (or (get-in candidate [:attrs :type]) "text"))))
                          (= (str name) (str (get-in candidate [:attrs :name])))
                          (= (str form) (str (get-in candidate [:attrs :form])))))))
         vec)))

(defn- radio-required-satisfied?
  [document node]
  (some #(truthy-attr? (get-in document [:nodes % :attrs :checked]))
        (radio-group-node-ids document node)))

(defn- control-value
  [document node]
  (let [type (str/lower-case (str (or (get-in node [:attrs :type]) "text")))]
    (str (or (get-in node [:attrs :text/value])
             (if (= :select (:tag node))
               (select-value document node)
               (get-in node [:attrs :value]))
             ""))))

(defn- validation-barred-control?
  [node]
  (or (hidden-input-control? node)
      (file-input-control? node)))

(defn- constraint-validation-barred-control?
  [node]
  (or (validation-barred-control? node)
      (and (editable-form-control? node)
           (truthy-attr? (get-in node [:attrs :readonly])))))

(defn- constraint-invalid?
  [document node]
  (let [attrs (:attrs node)
        type (str/lower-case (str (or (:type attrs) "text")))
        value (control-value document node)
        length (count value)
        minlength (parse-int (:minlength attrs))
        maxlength (parse-int (:maxlength attrs))]
    (and (not (constraint-validation-barred-control? node))
         (or (truthy-attr? (:invalid attrs))
             (and (truthy-attr? (:required attrs))
                  (cond
                    (and (= :input (:tag node)) (= "checkbox" type)) (not (truthy-attr? (:checked attrs)))
                    (and (= :input (:tag node)) (= "radio" type)) (not (radio-required-satisfied? document node))
                    :else (str/blank? value)))
             (and minlength
                  (pos? length)
                  (< length minlength))
             (and maxlength
                  (> length maxlength))))))

(defn- constraint-valid?
  [document node]
  (and (form-control? node)
       (not (disabled-control? document node))
       (not (constraint-validation-barred-control? node))
       (not (constraint-invalid? document node))))

(defn- matches-pseudo?
  [document node selector-pseudo]
  (case selector-pseudo
    :disabled (disabled-control? document node)
    :enabled (and (form-control? node)
                  (not (disabled-control? document node)))
    :checked (truthy-attr? (get-in node [:attrs :checked]))
    :required (and (form-control? node)
                   (not (disabled-control? document node))
                   (not (validation-barred-control? node))
                   (truthy-attr? (get-in node [:attrs :required])))
    :optional (and (form-control? node)
                   (not (disabled-control? document node))
                   (not (validation-barred-control? node))
                   (not (truthy-attr? (get-in node [:attrs :required]))))
    :read-only (and (not (validation-barred-control? node))
                    (or (not (editable-form-control? node))
                        (truthy-attr? (get-in node [:attrs :readonly]))))
    :read-write (and (editable-form-control? node)
                     (not (truthy-attr? (get-in node [:attrs :readonly])))
                     (not (disabled-control? document node)))
    :invalid (and (form-control? node)
                  (not (disabled-control? document node))
                  (not (constraint-validation-barred-control? node))
                  (constraint-invalid? document node))
    :valid (constraint-valid? document node)
    :focus (and document (= (:node/id node) (:focus document)))
    false))

(defn- matches-simple?
  ([node selector]
   (matches-simple? nil node selector))
  ([document node selector]
   (and (= :element (:node/type node))
        (or (nil? (:selector/tag selector))
            (= (:selector/tag selector) (:tag node)))
        (or (nil? (:selector/id selector))
            (= (:selector/id selector) (get-in node [:attrs :id])))
        (every? (classes node) (:selector/classes selector))
        (every? (fn [{:attr/keys [name operator value]}]
                  (let [actual (get-in node [:attrs name])]
                    (and (some? actual)
                         (case operator
                           nil true
                           "=" (= (str actual) value)
                           "~=" (contains? (set (remove str/blank?
                                                        (str/split (str actual) #"\s+")))
                                          value)
                           "^=" (str/starts-with? (str actual) value)
                           "$=" (str/ends-with? (str actual) value)
                           "*=" (str/includes? (str actual) value)
                           "|=" (or (= (str actual) value)
                                   (str/starts-with? (str actual) (str value "-")))
                           false))))
                (:selector/attrs selector))
        (every? #(matches-pseudo? document node %) (:selector/pseudos selector)))))

(defn- parent-index
  [document]
  (reduce-kv
   (fn [parents parent-id node]
     (reduce (fn [parents child-id]
               (assoc parents child-id parent-id))
             parents
             (:children node)))
   {}
   (:nodes document)))

(defn- sibling-index
  [children node-id]
  (first (keep-indexed (fn [idx id] (when (= id node-id) idx)) children)))

(defn- preceding-element-siblings
  "Element-type siblings before `node-id` under `parent-id`, in document
   order (nearest-last). Text nodes are ignored, matching CSS sibling
   combinator semantics."
  [document parent-id node-id]
  (let [children (vec (get-in document [:nodes parent-id :children] []))
        idx (sibling-index children node-id)]
    (if idx
      (->> (subvec children 0 idx)
           (filter #(= :element (get-in document [:nodes % :node/type]))))
      [])))

(defn- matches-parts?
  [document parents node-id parts]
  (let [parts (vec parts)]
    (letfn [(match-at [node-id idx]
              (let [simple (nth parts idx)
                    node (get-in document [:nodes node-id])]
                (and (matches-simple? document node simple)
                     (if (zero? idx)
                       true
                       (case (:selector/combinator simple)
                         :child
                         (when-let [parent-id (get parents node-id)]
                           (match-at parent-id (dec idx)))

                         :descendant
                         (loop [ancestor-id (get parents node-id)]
                           (when ancestor-id
                             (if (match-at ancestor-id (dec idx))
                               true
                               (recur (get parents ancestor-id)))))

                         :next-sibling
                         (when-let [parent-id (get parents node-id)]
                           (when-let [sibling-id (last (preceding-element-siblings
                                                         document parent-id node-id))]
                             (match-at sibling-id (dec idx))))

                         :subsequent-sibling
                         (when-let [parent-id (get parents node-id)]
                           (boolean
                            (some #(match-at % (dec idx))
                                  (preceding-element-siblings document parent-id node-id))))

                         false)))))]
      (if (seq parts)
        (match-at node-id (dec (count parts)))
        false))))

(defn matches?
  ([node selector]
   (matches-simple? node (if (:selector/parts selector)
                           (last (:selector/parts selector))
                           selector)))
  ([document node selector]
   (let [selector (if (:selector/parts selector)
                    selector
                    {:selector/parts [selector]})
         node-id (:node/id node)]
     (matches-parts? document (parent-index document) node-id (:selector/parts selector)))))

(defn- inline-style
  [node]
  (or (get-in node [:attrs :style-inline])
      {}))

(defn- clear-style-attrs
  [document node-id]
  (update-in document [:nodes node-id :attrs]
             (fn [attrs]
               (into {}
                     (remove (fn [[k _]]
                               (contains? #{"style" "pseudo"} (namespace k))))
                     attrs))))

(defn- pseudo-element-of
  [selector]
  (:selector/pseudo-element (last (:selector/parts selector))))

(defn- resolve-style-for
  "Cascade-resolves the declarations that target `pseudo-element` (nil for
   the real element itself, :before/:after for its generated content) on
   `node`. Mirrors the pre-pseudo-element `computed-style` algorithm exactly
   when `pseudo-element` is nil, so existing behavior is unchanged.

   Cascade layers: each rule-based entry carries its :rule/layer-priority
   (see `parse-rules`) as :layer, and the sort tuple below checks :unlayered?
   and :layer *before* :specificity -- so layer membership decides first,
   specificity only breaks ties within the same layer, exactly like real CSS
   cascade layers.

   `!important` layer-order reversal (CSS Cascading and Inheritance Level 5,
   the importance/cascade-origin step): for normal (non-`!important`)
   declarations, a later-declared layer beats an earlier one -- :layer sorts
   ascending and the last-sorted entry wins, so a higher :rule/layer-priority
   (later-declared layer) naturally wins. For `!important` declarations, real
   CSS *reverses* that: an earlier-declared layer beats a later one. Each
   entry's :layer value is negated when that entry's own :important? is true
   (see the `if important? (- raw-layer) raw-layer` below) so a lower
   :rule/layer-priority (earlier-declared layer) sorts *last* -- and wins --
   within the important group, while the non-important group is untouched.
   This negation is safe because: (1) it is keyed off each entry's own
   :important?, and :important? is compared *before* :layer in the tuple, so
   two entries only ever reach the :layer comparison once they've already
   tied on :important? -- meaning both sides use the same sign, never mixed;
   (2) negation is a strictly order-reversing map, so it flips relative order
   between different layers but leaves within-layer ties (same raw value,
   same sign) exactly as ties, falling through to :specificity/:order
   unaffected -- \"specificity/order tie-breaking within a layer\" is
   unchanged, as required.

   Unlayered-beats-layered is deliberately *not* encoded as a magic \"one
   past the highest layer index\" sentinel riding on :layer's numeric
   magnitude (as it effectively was before this negation existed) -- doing
   that would make the sentinel itself losable to negation (an unlayered
   entry's sentinel would need its own reversal-proofing to stay above every
   negated layer index, which the negation above does not attempt). Instead
   it's a dedicated :unlayered? boolean (true iff the originating rule's
   :rule/layer name is nil), compared *before* :layer: an unlayered
   declaration beats every layered one of the same importance regardless of
   either side's :layer value. Because :unlayered? sits above :layer in the
   tuple and is unaffected by the negation above, this holds in *both* the
   important and non-important groups -- matching real CSS, where an
   unlayered `!important` declaration still beats every layered `!important`
   declaration.

   Inline declarations have no layer concept in real CSS; per the Level 5
   spec they are treated like an unlayered declaration for cascade-layer
   purposes, and (like any unlayered declaration) always win over a layered
   rule-based declaration of the same importance. Both of those already fall
   out structurally here without needing any special-casing: :inline? is
   compared *before* :unlayered?/:layer in the tuple, so once :important?
   ties, a difference in :inline? decides the comparison and :unlayered?/
   :layer are never consulted -- the concrete :unlayered?/:layer values given
   to inline entries are inert for correctness (set to true / tied with the
   stylesheet's highest resolved layer priority, respectively, purely for
   documentation, matching the \"treated as unlayered\" framing). This
   reasoning is unaffected by the :important? negation above, since :inline?
   sits above :layer in the tuple regardless of :layer's sign.

   Known gap: inline declarations (`inline-style`) carry no real
   `!important` parsing of their own -- every inline entry here is hardcoded
   :important? false regardless of what the raw `style=\"...\"` attribute
   text said (real CSS/HTML allows `style=\"color: red !important\"`).
   `inline-style` reads an already-parsed `:style-inline` attrs map (property
   -> value only, no importance) that `kotoba-lang/htmldom`
   (`htmldom.core/parse-style`, called from `apply-attrs`) populates outside
   this namespace; that parser doesn't strip or record `!important` at all
   today. Wiring real inline `!important` through would mean changing that
   attribute's shape in `htmldom` (and re-checking every other consumer of
   `:style-inline`/`:style`), which is a separate, larger cross-repo change
   left out of scope here."
  [document rules node pseudo-element]
  (let [declarations (for [{:rule/keys [selectors declarations declaration-meta order layer-priority layer]} rules
                            selector selectors
                            :when (= pseudo-element (pseudo-element-of selector))
                            :when (if document
                                    (matches? document node selector)
                                    (matches? node selector))
                            [property value] declarations]
                        (let [{:keys [important?]} (get declaration-meta property)
                              important? (boolean important?)
                              raw-layer (or layer-priority 0)]
                          {:property property
                           :value value
                           :important? important?
                           :specificity (specificity selector)
                           :inline? false
                           :unlayered? (nil? layer)
                           :layer (if important? (- raw-layer) raw-layer)
                           :order order}))
        max-layer-priority (or (some->> rules (keep :rule/layer-priority) seq (apply max)) 0)
        inline-declarations (when (nil? pseudo-element)
                               (map-indexed (fn [idx [property value]]
                                              {:property property
                                               :value value
                                               :important? false
                                               :specificity [1 0 0]
                                               :inline? true
                                               :unlayered? true
                                               :layer max-layer-priority
                                               :order idx})
                                            (inline-style node)))]
    (reduce (fn [m {:keys [property value]}]
              (assoc m property value))
            {}
            (sort-by (juxt :important? :inline? :unlayered? :layer :specificity :order)
                     (concat declarations inline-declarations)))))

(defn computed-style
  "Cascade-resolved style map for `node`. Regular declarations are flat
   `{property value}` entries. If any rule targets this node's `::before`/
   `::after` pseudo-element, that pseudo-element's own resolved style map is
   attached under the extra key :pseudo/before / :pseudo/after (only when
   non-empty) -- see the namespace docstring for what does/doesn't consume
   those keys downstream."
  ([rules node]
   (computed-style nil rules node))
  ([document rules node]
   (let [base (resolve-style-for document rules node nil)
         before (resolve-style-for document rules node :before)
         after (resolve-style-for document rules node :after)]
     (cond-> base
       (seq before) (assoc :pseudo/before before)
       (seq after) (assoc :pseudo/after after)))))

(defn pseudo-element-style-for
  "Cascade-resolved style map for `node`'s `pseudo-element` (:before or
   :after) generated content only -- the subset of `rules` whose selector
   targets that pseudo-element (see the namespace docstring) and whose
   non-pseudo simple-selector part matches `node`. Same specificity/layer/
   cascade resolution as any other declaration; declarations that don't
   target `pseudo-element` never contribute. Returns {} when no rule
   targets this pseudo-element on this node -- callers (e.g. cssom.layout)
   treat that the same as the pseudo-element not existing at all.

   This is the same resolution `computed-style`/`apply-cascade` already
   perform internally (attaching the result to the real element's own
   computed style under :pseudo/before / :pseudo/after) -- exposed directly
   here so callers can resolve a node's pseudo-element style without a full
   apply-cascade + DOM-attrs round trip. `content` values are already
   unquoted plain strings when parseable (see `parse-content-literal`);
   `attr()`/`counter()`/`url()`/unsupported `content` forms simply have no
   :content key in the returned map, same as `content` being absent.

   Mirrors `computed-style`'s own two arities -- the document-less 3-arity
   form has the same pseudo-class-matching restriction `matches?`'s
   document-less arity does (document-dependent pseudo-classes like
   :focus/:disabled won't match)."
  ([rules node pseudo-element]
   (pseudo-element-style-for nil rules node pseudo-element))
  ([document rules node pseudo-element]
   (resolve-style-for document rules node pseudo-element)))

(defn- custom-property?
  [k]
  (str/starts-with? (name k) "--"))

(def ^:private var-ref-pattern
  #"var\(\s*(--[A-Za-z_][-A-Za-z0-9_]*)\s*(?:,\s*([^()]*))?\)")

(defn- var-lookup
  [env var-name fallback]
  (if (contains? env (keyword var-name))
    (get env (keyword var-name))
    (or (some-> fallback str/trim not-empty) "")))

(defn- resolve-value
  "Resolves `var(--name[, fallback])` references in `value` against the
   custom-property environment `env` (name -> already-resolved value,
   inherited top-down by apply-cascade). A value that is *exactly* one
   var() reference preserves the looked-up value's type (so `var(--gap)`
   resolving to the number 8 stays a number); a var() reference embedded in
   a larger string is substituted textually. Unresolvable references with no
   fallback resolve to \"\". Recursion is depth-capped to tolerate (but not
   usefully support) cyclic custom properties."
  ([env value] (resolve-value env value 0))
  ([env value depth]
   (cond
     (not (string? value)) value
     (> depth 8) value
     :else
     (let [trimmed (str/trim value)]
       (if-let [[_ var-name fallback] (re-matches var-ref-pattern trimmed)]
         (let [resolved (var-lookup env var-name fallback)]
           (if (string? resolved)
             (parse-style-value (resolve-value env resolved (inc depth)))
             resolved))
         (if (re-find #"var\(" value)
           (str/replace value var-ref-pattern
                        (fn [[_ var-name fallback]]
                          (str (var-lookup env var-name fallback))))
           value))))))

(defn- resolve-style-map
  [env m]
  (into {} (map (fn [[k v]] [k (resolve-value env v)])) m))

(defn- style-element
  "Resolves and writes computed style attrs for a single element, given the
   custom-property environment inherited from its ancestors. Returns
   [document node-env] where node-env is the environment children should
   inherit (inherited-env merged with this element's own resolved custom
   properties)."
  [document rules node-id inherited-env]
  (let [node (get-in document [:nodes node-id])
        style (computed-style document rules node)
        pseudo-keys #{:pseudo/before :pseudo/after}
        regular (into {} (remove (fn [[k _]] (contains? pseudo-keys k))) style)
        pseudo (select-keys style pseudo-keys)
        custom (into {} (filter (fn [[k _]] (custom-property? k))) regular)
        normal (into {} (remove (fn [[k _]] (custom-property? k))) regular)
        resolved-custom (into {} (map (fn [[k v]] [k (resolve-value inherited-env v)])) custom)
        node-env (merge inherited-env resolved-custom)
        resolved-normal (resolve-style-map node-env normal)
        resolved-pseudo (into {} (map (fn [[k v]] [k (resolve-style-map node-env v)])) pseudo)
        final-style (merge resolved-custom resolved-normal resolved-pseudo)
        document (reduce-kv
                  (fn [d k v]
                    (if (contains? pseudo-keys k)
                      (dom/set-attribute d node-id k v)
                      (dom/set-attribute d node-id (keyword "style" (name k)) v)))
                  (clear-style-attrs document node-id)
                  final-style)]
    [document node-env]))

(defn apply-cascade
  "Applies the cascade over `document`, writing each element's resolved
   style onto its :style/* attrs (and :pseudo/before / :pseudo/after where
   applicable -- see `computed-style`).

   Walks top-down from the document root so CSS custom properties inherit
   from ancestor to descendant. Any element unreachable from :root (e.g. a
   detached subtree) still gets styled, using an empty inherited environment,
   to preserve the previous flat-walk behavior for those nodes.

   `opts` (optional, 3-arity) may include :viewport-width (default
   `default-viewport-width`) used to decide whether `@media (min-width:...)`
   / `(max-width:...)` rule blocks apply."
  ([document rules]
   (apply-cascade document rules {}))
  ([document rules opts]
   (let [viewport-width (or (:viewport-width opts) default-viewport-width)
         rules (filterv #(rule-applies-to-viewport? % viewport-width) rules)]
     (letfn [(walk [document node-id inherited-env visited]
               (let [node (get-in document [:nodes node-id])
                     [document node-env] (if (= :element (:node/type node))
                                           (style-element document rules node-id inherited-env)
                                           [document inherited-env])
                     visited (conj visited node-id)]
                 (reduce (fn [[document visited] child-id]
                           (walk document child-id node-env visited))
                         [document visited]
                         (:children node))))]
       (let [[document visited] (if-let [root (:root document)]
                                   (walk document root {} #{})
                                   [document #{}])]
         (reduce-kv
          (fn [document node-id node]
            (if (and (= :element (:node/type node)) (not (contains? visited node-id)))
              (first (style-element document rules node-id {}))
              document))
          document
          (:nodes document)))))))

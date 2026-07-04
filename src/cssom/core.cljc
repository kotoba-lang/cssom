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
     into a plain string under `:content`; a bare `attr(name)` reference
     (`content: attr(data-foo);` -- e.g. the common `[title]::after {
     content: \" (\" attr(title) \")\"; }` idiom) resolves to the
     *originating* element's own real HTML attribute value at
     cascade-resolution time (a missing attribute resolves to `\"\"`,
     matching real CSS -- not the same as `content` being absent), also
     ending up as a plain string under `:content`; a bare `counter(name)`
     reference (`content: counter(item);`, the single most common
     generated-content idiom for automatic numbering -- e.g. a `<li>` with
     `counter-increment: item` and `::before { content: counter(item) \".
     \"; }`) resolves to that named counter's CURRENT value at this exact
     point in the document tree. Unlike `attr()`, a counter is NOT resolvable
     from one node in isolation -- its value is the cumulative effect of
     every `counter-reset`/`counter-increment` declaration on every element
     that precedes it in document tree order -- so it can only be resolved
     correctly by `apply-cascade`'s own top-down tree walk (see its
     docstring for how the running counters map is threaded alongside the
     existing custom-property environment); `computed-style`/
     `pseudo-element-style-for` called standalone, with no real tree walk
     backing them, honestly leave `:content` unset for a `counter()`
     reference rather than guessing a number (see their own docstrings). A
     mix of quoted-literal, `attr()`, and/or `counter()` terms in one
     declaration (`content: \"Price: \" attr(data-price);` /
     `content: counter(item) \". \";`) is supported too, concatenated in
     source order. Other unsupported forms (`counter()`'s two-argument
     `name, <list-style-type>` form, e.g. `counter(item, upper-roman)`,
     `url(...)`, `none`/`normal`, unquoted text, `attr()`'s extended `name
     type, fallback` syntax) simply leave `:content` unset rather than
     storing something unusable. `cssom.layout` reads these attributes and
     paints generated content as real text immediately before/after the
     element's real children -- see its namespace docstring.
   - `counter-reset`/`counter-increment` declarations (`counter-reset: item
     5;` / `counter-increment: item;`, one or more `<name> [<integer>]?`
     pairs each -- see `parse-counter-property`): parsed as ordinary
     properties (default reset value 0, default increment amount 1, matching
     real CSS), but their EFFECT (mutating a named-counter map as
     `apply-cascade` walks the document) only happens as part of that same
     tree walk described above -- a simplification this engine makes
     honestly explicit: counters live in one flat, per-document namespace
     (real CSS technically scopes them to element nesting in a more complex
     way this engine does not attempt).
   - `@media (min-width: Npx)` / `(max-width: Npx)` conditional rule blocks
     (optionally combined with `and`), evaluated against a viewport width
     passed to `apply-cascade` via an options map (default
     `default-viewport-width`).
   - `@container [<name>]? (min-width: Npx)` / `(max-width: Npx)` /
     `(width: Npx)` conditional rule blocks (optionally combined with `and`,
     optionally named -- `@container sidebar (min-width: 400px)` -- via the
     `container-name` property; `container-type: inline-size` / `size`
     marks an element as a query container in the first place -- see
     `parse-rules`/`split-container-segments` for the at-rule grammar and
     `container-condition-matches?`/`container-rule-matches?` for matching).
     Unlike `@media`'s single, globally-known viewport width, a container's
     size is normally only known once real layout has run -- this engine's
     cascade/layout pipeline is a strict one-directional pipe (`apply-cascade`
     finishes completely, writing every element's final `:style/*` attrs,
     and only then does `cssom.layout` consume them; there is no
     re-cascade-after-layout step or relayout loop). Rather than adding one,
     `apply-cascade` supports the one honestly-scoped case that does NOT
     need real layout at all: a container whose OWN `width` (and, if
     present, `min-width`/`max-width`) is a literal, already-cascade-resolved
     NUMBER -- i.e. the author wrote a plain `<n>`/`<n>px` value, not `auto`,
     a percentage, `fit-content`, `calc(...)`, or anything flex/grid/
     content-driven -- via one extra, bounded (never iterated/looped)
     cascade pass that resolves every container's own width BEFORE any
     `@container` rule is allowed to match anything (see apply-cascade's
     docstring for exactly how the two passes fit together, and
     `container-rule-matches?`'s docstring for why an unresolvable
     container width, or no matching container ancestor at all, makes an
     `@container` rule honestly NOT apply -- never a guess). Known,
     documented non-goals: a container whose own size depends on layout
     (auto/percentage/flex-basis/grid-driven widths), NESTED/chained
     container queries (a container whose own qualifying width is itself
     set by an ANCESTOR container's `@container` rule -- this engine only
     resolves one level, see apply-cascade), the block-size/height axis
     (`container-type: size` is accepted but this engine has no height
     query feature to offer regardless), the `container` shorthand property
     (only the `container-type`/`container-name` longhands are parsed), and
     `or`/range syntax/`style()`/`scroll-state()` container queries (same
     narrow feature/combinator subset `@media` already has).
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
  "Parses a single CSS `content` TERM into the literal string it designates,
   for the narrow, common real-world case of a single quoted string
   (`content: \"some text\";` / `content: '...';`), including the empty
   string (`content: \"\";` -- a common icon-only generated-content idiom,
   still a real declared value, not the same as `content` being absent).
   Returns nil for anything else -- including a bare `attr(...)` reference
   (see `parse-content-attr-ref`, its sibling parser for that case) and any
   other unsupported form -- rather than guessing. `parse-content-term`
   combines this with `parse-content-attr-ref` to parse either kind of term,
   and `parse-content-value` combines those over one or more terms."
  [v]
  (when-let [[_ double-quoted single-quoted] (re-matches content-literal-pattern (str/trim (str v)))]
    (or double-quoted single-quoted "")))

(def ^:private content-attr-pattern
  "Matches a single bare `attr(name)` reference -- an unquoted identifier
   inside `attr(...)`, no surrounding quotes, no fallback/type argument (CSS
   5's `attr(name type, fallback)` extended syntax is out of scope -- see
   `parse-content-attr-ref`)."
  #"attr\(\s*([A-Za-z_][-A-Za-z0-9_]*)\s*\)")

(defn- parse-content-attr-ref
  "Parses a single CSS `content` TERM into an *attr() reference* marker -- a
   map, `{:content/attr-name \"data-foo\"}` -- for the narrow, common
   real-world case of a single bare `attr(name)` call (`content:
   attr(data-foo);` / `content: attr(title);`): no quotes, no fallback, no
   type coercion. Unlike a quoted string literal (`parse-content-literal`),
   this term's actual text isn't known yet -- it depends on whatever
   specific element the declaration ends up cascade-winning on, not a fixed
   string the declaration itself carries -- so it stays an unresolved
   marker all the way through rule parsing and cascade priority resolution,
   and is only resolved to the real string at the very end of
   `resolve-style-for`, keyed off that call's own `node` (the *originating*
   element `attr()` always targets, never any hypothetical pseudo-element
   node -- see `resolve-content-value`). Returns nil for anything else, same
   contract as `parse-content-literal`."
  [v]
  (when-let [[_ attr-name] (re-matches content-attr-pattern (str/trim (str v)))]
    {:content/attr-name attr-name}))

(def ^:private content-counter-pattern
  "Matches a single bare `counter(name)` reference -- the default-decimal,
   no-list-style-type form only. CSS's two-argument
   `counter(name, <list-style-type>)` form (e.g. `counter(item,
   upper-roman)`, used to render as e.g. lowercase-alpha/upper-roman instead
   of plain decimal digits) is explicitly out of scope -- see
   `parse-content-counter-ref`."
  #"counter\(\s*([A-Za-z_][-A-Za-z0-9_]*)\s*\)")

(defn- parse-content-counter-ref
  "Parses a single CSS `content` TERM into a *counter() reference* marker --
   a map, `{:content/counter-name \"item\"}` -- for the narrow, common
   real-world case of a single bare `counter(name)` call: default decimal
   numbering, no list-style-type argument.

   Unlike `attr()` (purely local -- resolvable from one element's own attrs
   in isolation, see `parse-content-attr-ref`), a counter's value is
   fundamentally NOT local: it is the cumulative effect of every
   `counter-reset`/`counter-increment` declaration on every element that
   precedes THIS point in document tree order -- genuinely unknowable from
   `node` alone, no matter how much of its own state you inspect. So this
   marker stays unresolved through rule parsing and cascade priority
   resolution exactly like an attr() marker does, but its resolution point
   is different: `resolve-content-term`/`resolve-content-value` need an
   externally-supplied running `counters` map (name -> current value) to
   resolve it, which only `apply-cascade`'s own top-down tree walk can
   correctly build and thread node-by-node (see its docstring) --
   `resolve-style-for` honors a `counters` argument for exactly this reason.
   Returns nil for anything else (including the two-argument
   `name, <list-style-type>` form), same contract as
   `parse-content-literal`/`parse-content-attr-ref`."
  [v]
  (when-let [[_ counter-name] (re-matches content-counter-pattern (str/trim (str v)))]
    {:content/counter-name counter-name}))

(defn- parse-content-term
  "Parses a single content TERM -- no combination with any other term --
   into whichever of `parse-content-literal` (a quoted string literal),
   `parse-content-attr-ref` (a bare `attr(name)` call), or
   `parse-content-counter-ref` (a bare `counter(name)` call) matches, or nil
   if none does. Used both for the common single-term case and, by
   `parse-content-value`, for each term of a multi-term composed value."
  [v]
  (let [literal (parse-content-literal v)]
    (if (some? literal)
      literal
      (or (parse-content-attr-ref v) (parse-content-counter-ref v)))))

(defn- content-ws-char? [c] (boolean (re-matches #"\s" (str c))))

(defn- split-content-terms
  "Splits a `content` declaration's raw value into its top-level
   whitespace-separated terms, without breaking a quoted string literal
   apart on any whitespace *inside* it (e.g. `\"Price: \" attr(data-price)`
   splits into exactly two terms, not four) -- tracks a quote-char state
   while scanning, the same technique `selector-tokens`/
   `split-selector-list` already use elsewhere in this namespace for the
   same reason."
  [v]
  (let [s (str v)
        n (count s)]
    (loop [idx 0 start 0 quote-char nil terms []]
      (if (= idx n)
        (let [term (str/trim (subs s start idx))]
          (if (str/blank? term) terms (conj terms term)))
        (let [ch (nth s idx)]
          (cond
            (and quote-char (= ch quote-char))
            (recur (inc idx) start nil terms)

            quote-char
            (recur (inc idx) start quote-char terms)

            (or (= ch \") (= ch \'))
            (recur (inc idx) start ch terms)

            (content-ws-char? ch)
            (let [term (str/trim (subs s start idx))]
              (recur (inc idx) (inc idx) nil (if (str/blank? term) terms (conj terms term))))

            :else
            (recur (inc idx) start quote-char terms)))))))

(defn- parse-content-value
  "Parses a CSS `content` declaration's raw value. Supports:
   1. A single quoted string literal (`parse-content-literal`) -- returns a
      plain string, exactly as before `attr()`/`counter()` support existed.
   2. A single bare `attr(name)` call (`parse-content-attr-ref`) -- returns
      an attr() reference marker (resolved later, per-element -- see
      `resolve-content-value`), since its text isn't a fixed string the
      declaration itself carries.
   3. A single bare `counter(name)` call (`parse-content-counter-ref`) --
      returns a counter() reference marker (resolved later, against a
      running per-document counters map only `apply-cascade`'s own tree walk
      can build -- see `resolve-content-value`), since its value depends on
      every counter-reset/counter-increment declaration that precedes this
      point in document tree order, not anything local to one element.
   4. Two or more whitespace-separated terms, each itself a quoted literal,
      an `attr()` call, or a `counter()` call (e.g. `\"Price: \"
      attr(data-price)`, or the canonical numbering idiom `counter(item)
      \". \"`, both real, common compositions) -- returns a marker map
      holding the ordered parsed terms under :content/parts, concatenated
      later the same way (`resolve-content-value`). If ANY term in a
      multi-term value fails to parse (some other, unsupported form mixed
      in), the WHOLE declaration is dropped (nil) rather than silently
      rendering a partial string.

   Anything else this engine doesn't support (`counter()`'s two-argument
   `name, <list-style-type>` form, `url(...)`, `none`/`normal`,
   unquoted/unmatched text, `attr()`'s extended `name type, fallback`
   syntax) returns nil rather than guessing -- callers treat nil exactly
   like `content` being absent: no generated-content box, no crash."
  [v]
  (or (parse-content-term v)
      (let [terms (split-content-terms v)]
        (when (> (count terms) 1)
          (let [parsed (mapv parse-content-term terms)]
            (when (every? some? parsed)
              {:content/parts parsed}))))))

(defn- parse-counter-amount
  "Parses a bare integer token already validated by `counter-list-pattern`
   (mirrors `parse-style-value`'s own reader-conditional integer parsing --
   this exists as its own tiny helper, rather than reusing the general
   `parse-int` further down this file, purely to avoid a forward reference:
   `parse-int` is defined later in this namespace, after several of its own
   dependents)."
  [s]
  #?(:clj (Long/parseLong s) :cljs (js/parseInt s 10)))

(def ^:private counter-list-pattern
  "Validates that a `counter-reset`/`counter-increment` raw value is one or
   more whitespace-separated `<counter-name> [<integer>]?` pairs (real CSS
   allows more than one counter per declaration, e.g. `counter-reset:
   section subsection;`, each independently defaulting when no integer
   follows it) -- see `parse-counter-property`."
  #"[A-Za-z_][-A-Za-z0-9_]*(?:\s+-?\d+)?(?:\s+[A-Za-z_][-A-Za-z0-9_]*(?:\s+-?\d+)?)*")

(defn- parse-counter-property
  "Parses a `counter-reset`/`counter-increment` declaration's raw value into
   a vector of `[name amount]` pairs, e.g. `counter-reset: item 5;` ->
   `[[\"item\" 5]]`, `counter-increment: item;` -> `[[\"item\"
   default-amount]]`, `counter-reset: a 1 b 2;` -> `[[\"a\" 1] [\"b\" 2]]`.
   A counter name with no following integer gets `default-amount` (callers
   pass 0 for `counter-reset`, 1 for `counter-increment` -- real CSS's own
   defaults, see `parse-property-value`). This is pure parsing -- it has no
   idea what any of these counters' CURRENT values are; only
   `apply-cascade`'s own top-down tree walk actually mutates a running
   counters map by these `[name amount]` pairs, per node, in document
   order (see its docstring).

   Returns nil (declaration dropped, matching every other unparseable-value
   case in this namespace) for a blank value or anything that isn't this
   exact repeated name/integer shape (e.g. `none` -- CSS's own way to write
   'no counters' -- is intentionally out of scope, same treatment as any
   other unrecognized value)."
  [v default-amount]
  (let [s (str/trim (str v))]
    (when (and (seq s) (re-matches counter-list-pattern s))
      (let [tokens (str/split s #"\s+")]
        (loop [tokens tokens pairs []]
          (if (empty? tokens)
            pairs
            (let [name (first tokens)
                  next-tok (second tokens)]
              (if (and next-tok (re-matches #"-?\d+" next-tok))
                (recur (drop 2 tokens) (conj pairs [name (parse-counter-amount next-tok)]))
                (recur (rest tokens) (conj pairs [name default-amount]))))))))))

(defn- parse-property-value
  "Parses a single declaration's raw value string for property `k` (still a
   raw string at this point, not yet keywordized). `content` gets its own
   parsing (see `parse-content-value`: a quoted string literal, a bare
   `attr(name)`/`counter(name)` reference, or a mix of those terms);
   `counter-reset`/`counter-increment` also get their own parsing (see
   `parse-counter-property`, called with real CSS's own default amount for
   each: 0 for `counter-reset`, 1 for `counter-increment`); every other
   property keeps the existing numeric/px coercion (`parse-style-value`).
   May return nil (an unparseable `content`/`counter-reset`/
   `counter-increment` value) -- callers drop the declaration entirely in
   that case rather than storing an unusable value."
  [k v]
  (let [k-lower (str/lower-case k)]
    (cond
      (= "content" k-lower) (parse-content-value v)
      (= "counter-reset" k-lower) (parse-counter-property v 0)
      (= "counter-increment" k-lower) (parse-counter-property v 1)
      :else (parse-style-value v))))

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

(defn- split-container-segments
  "Splits raw CSS text into an ordered sequence of
   {:segment/type :plain :segment/text \"...\"} and
   {:segment/type :container :segment/name \"sidebar\" (or nil, unnamed)
   :segment/condition \"(min-width: 400px)\" :segment/text \"...\"}
   segments, preserving source order -- mirrors split-media-segments exactly
   (same brace-depth-aware find-matching-brace), extended to also pull an
   optional leading container-name token out of the at-rule's own header
   text (real CSS's `@container [<container-name>]? <container-condition>`
   grammar -- e.g. `@container sidebar (min-width: 400px)` vs the unnamed
   `@container (min-width: 400px)`): everything before the header's first
   top-level `(` is the name (blank collapses to nil, the unnamed-query
   case), everything from that `(` onward is the condition text (verbatim,
   handed to container-condition-matches? later, exactly like
   split-media-segments already hands :segment/condition to
   media-condition-matches? verbatim).

   A header with no `(` at all (a malformed/unsupported at-rule, e.g. a
   `style()`/`scroll-state()` container query this engine doesn't parse)
   still produces a :container segment (name nil, condition = the raw
   header text) rather than being silently dropped or misfiled as :plain --
   container-condition-matches? then honestly fails to recognize any
   feature in that raw text and returns false, the same conservative
   default this engine already uses everywhere else for an unrecognized
   form, rather than this segment's rules silently becoming unconditional."
  [css]
  (let [s (str (or css ""))
        n (count s)]
    (loop [idx 0 segments []]
      (let [at-idx (str/index-of s "@container" idx)]
        (if (nil? at-idx)
          (cond-> segments
            (< idx n) (conj {:segment/type :plain :segment/text (subs s idx)}))
          (let [brace-open (str/index-of s "{" at-idx)]
            (if (nil? brace-open)
              (conj segments {:segment/type :plain :segment/text (subs s idx)})
              (let [header (str/trim (subs s (+ at-idx (count "@container")) brace-open))
                    paren-idx (str/index-of header "(")
                    container-name (when (and paren-idx (pos? paren-idx))
                                      (not-empty (str/trim (subs header 0 paren-idx))))
                    condition (if paren-idx (subs header paren-idx) header)
                    brace-close (find-matching-brace s brace-open)]
                (if (nil? brace-close)
                  (conj segments {:segment/type :plain :segment/text (subs s idx)})
                  (recur (inc brace-close)
                         (cond-> segments
                           (> at-idx idx) (conj {:segment/type :plain
                                                 :segment/text (subs s idx at-idx)})
                           true (conj {:segment/type :container
                                       :segment/name container-name
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

   Rules nested inside an `@container [<name>]? (<condition>) { ... }` block
   (see `split-container-segments` for the at-rule grammar) carry that raw
   condition text under :rule/container and the optional name under
   :rule/container-name; top-level (non-`@container`) rules have both nil.
   `apply-cascade` decides which :rule/container conditions currently hold
   (see its own docstring and `container-rule-matches?` -- unlike
   :rule/media, this needs a container's own resolved size, not a single
   global number, so it is NOT decided up front here the way
   rule-applies-to-viewport? decides :rule/media).

   Segment nesting order is media -> container -> layer -> plain rules, so
   all three at-rules compose (`@media (...) { @container (...) { @layer x
   { ... } } }`), mirroring how media -> layer nesting already worked before
   `@container` support existed.

   Known simplifications:
   - Anonymous `@layer { ... }` blocks (no name) are treated as unlayered
     rather than as their own distinct anonymous layer.
   - `@media` nested inside `@layer` loses the outer layer tag on that
     nested block's rules (they still get :rule/media correctly, just fall
     back to :rule/layer nil); `@layer` nested inside `@media` is fully
     supported. The same applies to `@container` nested inside `@layer`
     (loses the layer tag; a `@layer` nested inside `@container` is fully
     supported, same media/layer precedent)."
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
                     (->> (split-container-segments text)
                          (mapcat (fn [container-segment]
                                    (let [container? (= :container (:segment/type container-segment))
                                          container (when container? (:segment/condition container-segment))
                                          container-name (when container? (:segment/name container-segment))]
                                      (->> (split-layer-segments (:segment/text container-segment))
                                           (mapcat (fn [layer-segment]
                                                     (let [layer-name (when (= :layer (:segment/type layer-segment))
                                                                         (:segment/name layer-segment))]
                                                       (map #(assoc %
                                                                    :rule/media media
                                                                    :rule/container container
                                                                    :rule/container-name container-name
                                                                    :rule/layer layer-name
                                                                    :rule/layer-priority (priority-for layer-name))
                                                            (parse-rules-raw (:segment/text layer-segment))))))))))))))
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

(def ^:private container-feature-pattern
  "Mirrors media-feature-pattern, plus a bare `width` equality feature (real
   CSS's @container also supports `(width: Npx)`, an exact-match query --
   less common than min-width/max-width but trivial to support once the
   parsing machinery for the other two exists, so it is included rather than
   arbitrarily left out)."
  #"(?i)\(\s*(min-width|max-width|width)\s*:\s*(\d+)(?:px)?\s*\)")

(defn container-condition-matches?
  "Evaluates a raw @container condition (as stored in :rule/container, see
   parse-rules/split-container-segments) against `known-width` -- the
   nearest matching container's own already-resolved width in px (see
   container-rule-matches?'s docstring for exactly how/when that is
   computed: a first, @container-rule-free cascade pass over every
   container-marked element's own explicit width -- see apply-cascade's
   docstring), or nil when it isn't honestly resolvable at all.

   Supports `(min-width: Npx)` / `(max-width: Npx)` / `(width: Npx)`,
   combined with `and` -- deliberately the exact same narrow feature/
   combinator subset media-condition-matches? already supports, for
   consistency (no `or`, no range syntax like `(400px <= width <= 800px)`,
   no style()/scroll-state() container queries).

   Unlike media-condition-matches?'s own 'an unrecognized feature still
   matches' fallback (safe there because @media's underlying queried value
   -- the viewport -- is always a known number; only the FEATURE keyword
   might be unrecognized), this function returns false for ANY unrecognized
   feature/part, and ALSO false outright when `known-width` itself is nil.
   That different default is deliberate, not an oversight: @container's
   problem is categorically different from an unrecognized @media
   feature -- the queried VALUE itself is structurally unknowable here
   (this engine does not run real layout to find it, see apply-cascade's
   docstring), so treating that the same as @media's 'probably fine, don't
   hide the rule' convention would be exactly the silently-wrong
   approximation this namespace's content()/counter() docstrings already
   refuse to make."
  [condition known-width]
  (boolean
   (when (some? known-width)
     (let [condition (str/replace (str condition) #"(?i)^\s*@container\s*" "")
           parts (->> (str/split condition #"(?i)\s+and\s+")
                      (map str/trim)
                      (remove str/blank?))]
       (and (seq parts)
            (every? (fn [part]
                      (when-let [[_ kind value] (re-matches container-feature-pattern part)]
                        (let [n (parse-media-width value)]
                          (case (str/lower-case kind)
                            "min-width" (>= known-width n)
                            "max-width" (<= known-width n)
                            "width" (= known-width n)
                            false))))
                    parts))))))

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

;; ---- @container containers / matching ----
;;
;; See the namespace docstring's `@container` paragraph and apply-cascade's
;; own docstring for the two-cascade-pass mechanism this composes into:
;; build-containers scans a document ALREADY styled by a first,
;; @container-rule-free cascade pass (so every container-marked element's
;; OWN width -- if it's a plain, literal number -- is already resolved on
;; its :style/* attrs, exactly as `cssom.layout` would also read it, without
;; needing any actual layout pass) into a lookup apply-cascade's real second
;; pass threads through resolve-style-for as part of `container-ctx`.

(defn- container-type-of
  [document node-id]
  (some-> (get-in document [:nodes node-id :attrs :style/container-type]) str str/lower-case))

(defn- container-names-of
  "An element's own `container-name` declaration, split on whitespace into a
   set (real CSS allows more than one name, e.g. `container-name: sidebar
   wide;`, matched by any `@container <name> (...)` referencing either one) --
   empty when the element has no such declaration. Only the `container-name`
   longhand is parsed; the `container` shorthand (`container: sidebar /
   inline-size`) is explicitly out of scope (see the namespace docstring)."
  [document node-id]
  (set (remove str/blank?
               (str/split (str (or (get-in document [:nodes node-id :attrs :style/container-name]) "")) #"\s+"))))

(defn- resolvable-container-width
  "The 'known container size' (px) a container-marked element's own,
   already-cascade-resolved style contributes for @container matching, or
   nil when it isn't honestly knowable without running real layout (see the
   namespace docstring's @container section). Mirrors the WIDTH half of
   cssom.layout/resolve-width's own base/min/max-clamp arithmetic -- but
   ONLY when every one of :width/:min-width/:max-width that IS present
   already resolved (via this namespace's own parse-style-value, during the
   first cascade pass) to a plain number, i.e. the CSS author wrote a
   literal `<n>`/`<n>px` value for it, not `auto`, a percentage,
   `fit-content`, `calc(...)`, or any other keyword/expression this
   engine's numeric coercion doesn't collapse to a number. A container with
   no numeric :width at all -- the common case, since most containers size
   from their own content/flex/grid context, which this engine cannot know
   without an actual layout pass it deliberately does not run for this
   feature (see apply-cascade's docstring) -- honestly returns nil here
   rather than guessing at whatever avail-width layout might eventually hand
   it. A present :min-width/:max-width that ISN'T a plain number is simply
   skipped (not applied as a clamp) rather than erroring, the same
   'degrade, don't crash' posture the rest of this namespace already takes
   for unparseable values."
  [document node-id]
  (let [attrs (get-in document [:nodes node-id :attrs])
        width (:style/width attrs)]
    (when (number? width)
      (let [min-w (:style/min-width attrs)
            max-w (:style/max-width attrs)
            width (if (number? min-w) (max width min-w) width)
            width (if (number? max-w) (min width max-w) width)]
        width))))

(defn- build-containers
  "Scans `document` (the result of apply-cascade's own first,
   @container-rule-free pass -- see its docstring) for every element whose
   own cascade-resolved `container-type` is `inline-size` or `size` (this
   engine's width-only subset never offers a block-size/height axis feature
   to query in the first place, so treating `size` -- which real CSS also
   uses to enable block-size querying -- identically to `inline-size` here
   has no observable difference; anything else, including `normal` -- real
   CSS's own default, 'not a query container' -- or no container-type
   declaration at all, is simply not a container), returning a `node-id ->
   {:names #{...} :known-width (a number or nil)}` map (see
   resolvable-container-width for exactly when :known-width is nil rather
   than a number). Threaded into apply-cascade's real second pass as part of
   its container-ctx (alongside parent-index) so container-rule-matches?
   can walk from any descendant up to its nearest matching container."
  [document]
  (into {}
        (keep (fn [[node-id node]]
                (when (and (= :element (:node/type node))
                           (contains? #{"inline-size" "size"} (container-type-of document node-id)))
                  [node-id {:names (container-names-of document node-id)
                            :known-width (resolvable-container-width document node-id)}])))
        (:nodes document)))

(defn- nearest-container
  "Walks up from `node-id`'s PARENT (never `node-id` itself -- real CSS
   never lets an element be its own query container, precisely to avoid the
   circularity of an element's style depending on a size that depends on
   that same element's own style; see apply-cascade's docstring) via
   `parent-index`, returning the first (nearest) `containers` entry -- see
   build-containers -- whose :names includes `container-name` (or the very
   first container entry found at all, when `container-name` is nil, i.e.
   an unnamed @container query just wants 'the nearest container, whatever
   it's called'). A container ancestor whose OWN name doesn't match is
   skipped over -- the walk continues further up looking for one that does
   -- but once a MATCHING container is found (by name, or the nearest one
   at all for an unnamed query), that is definitively the query container
   for this rule on this node, whether or not its :known-width turned out
   to be resolvable (container-rule-matches? is what turns an unresolvable
   :known-width into an honest non-match; this function's job is purely
   finding WHICH container, never approximating one)."
  [containers parent-index node-id container-name]
  (loop [pid (get parent-index node-id)]
    (when pid
      (if-let [container (get containers pid)]
        (if (or (nil? container-name) (contains? (:names container) container-name))
          container
          (recur (get parent-index pid)))
        (recur (get parent-index pid))))))

(defn- container-rule-matches?
  "Whether a rule carrying `:rule/container`/`:rule/container-name` (see
   parse-rules) applies to `node-id`, given `container-ctx` -- nil outside
   apply-cascade's real second pass (see resolve-style-for's own docstring
   for exactly which callers pass nil: computed-style/
   pseudo-element-style-for, called standalone with no real tree walk
   behind them, and apply-cascade's own FIRST pass, which must not let any
   @container rule contribute before container widths are even known). A
   rule with no :rule/container at all (an ordinary, non-@container rule)
   always passes here -- this predicate only ever filters the @container
   subset, exactly like rule-applies-to-viewport? only ever filters the
   @media subset.

   Honestly returns false (the rule does NOT apply), rather than guessing,
   in every case where the query genuinely cannot be answered: no
   container-ctx, no matching container ancestor at all (nearest-container
   returns nil), or a matching container whose own :known-width is nil (an
   explicit-width-only container -- see resolvable-container-width -- with
   no resolvable width, e.g. an auto-sized/percentage/flex-or-grid-computed
   container). See container-condition-matches?'s docstring for why this
   false-by-default posture is a deliberate divergence from
   media-condition-matches?'s own 'unrecognized feature still matches'
   convention, not an inconsistency."
  [rule node-id container-ctx]
  (let [condition (:rule/container rule)]
    (or (nil? condition)
        (and (some? container-ctx)
             (some? node-id)
             (let [{:keys [containers parent-index]} container-ctx
                   container (nearest-container containers parent-index node-id (:rule/container-name rule))]
               (and container
                    (some? (:known-width container))
                    (container-condition-matches? condition (:known-width container))))))))

(defn- resolve-content-term
  "Resolves a single already-parsed content TERM (see `parse-content-term`):
   an attr() reference marker (`{:content/attr-name \"data-foo\"}`) becomes
   `node`'s own real HTML attribute value, or `\"\"` if `node` doesn't carry
   it -- real CSS's attr() behavior for an absent attribute (an empty
   string, not \"no value\"), matching how `content: \"\"` already behaves.
   A counter() reference marker (`{:content/counter-name \"item\"}`) becomes
   that name's current value in `counters` (a running name -> value map, or
   0 if `counters` doesn't (yet) have an entry for it -- real CSS: a counter
   that was never `counter-reset`/`counter-increment`-ed reads as 0 the
   first time it's referenced), UNLESS `counters` itself is nil -- which
   means this call has no real document-tree-walk context to resolve a
   counter() reference against at all (see `resolve-style-for`'s own
   `counters` argument), in which case this honestly returns nil (an
   unresolvable term) rather than fabricating a number; `resolve-content-value`
   propagates that nil so the whole `content` value is dropped, same
   treatment as any other unsupported form. A plain string term passes
   through as itself."
  [node counters term]
  (cond
    (not (map? term)) (str term)

    (contains? term :content/attr-name)
    (str (or (get-in node [:attrs (keyword (:content/attr-name term))]) ""))

    (contains? term :content/counter-name)
    (when (some? counters)
      (str (get counters (:content/counter-name term) 0)))

    :else (str term)))

(defn- resolve-content-value
  "Resolves a cascade-winning `content` value (see `parse-content-value`)
   into the plain string `cssom.layout` expects under :content, given
   `counters` -- the running named-counter map as of this exact point in
   document tree order (nil when there is no real tree-walk context to draw
   one from -- see `resolve-style-for`): a :content/parts marker (a mix of
   literal/attr()/counter() terms) resolves each term (`resolve-content-term`)
   and concatenates them in source order, UNLESS any term resolves to nil
   (an unresolvable counter() reference with no `counters` context), in
   which case the WHOLE value resolves to nil rather than rendering a
   partial string; a lone attr() or counter() reference marker resolves the
   same way; anything else (already a plain string, from a quoted literal,
   or nil) passes through unchanged."
  [node counters value]
  (cond
    (and (map? value) (contains? value :content/parts))
    (let [resolved (mapv #(resolve-content-term node counters %) (:content/parts value))]
      (when (every? some? resolved)
        (str/join "" resolved)))

    (and (map? value) (or (contains? value :content/attr-name)
                           (contains? value :content/counter-name)))
    (resolve-content-term node counters value)

    :else value))

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
   left out of scope here.

   `content: attr(name)` resolution: a `content` declaration's raw value may
   parse (see `parse-content-value`) not to a plain string but to an attr()
   reference marker (or a marker holding a mix of string-literal and attr()
   terms) -- its actual text isn't known until the winning declaration for
   *this specific* `node` is resolved, since attr()'s value is that node's
   own real HTML attribute value, not anything the stylesheet text itself
   carries. `node` here is always the *originating* real element regardless
   of whether `pseudo-element` is nil or :before/:after (`computed-style`/
   `pseudo-element-style-for` always pass the real element in, never a
   synthetic pseudo-element node -- kotoba.wasm.dom has no such node concept,
   see the namespace docstring), which is exactly attr()'s real-CSS target:
   it always reads the attribute off the element the pseudo-element is
   generated FOR. So once the cascade above has picked this node's winning
   `content` declaration, `resolve-content-value` resolves any attr()
   reference(s) in it against `node`'s own :attrs immediately below, before
   returning -- a missing attribute resolves to `\"\"` (real CSS behavior:
   attr() on an absent attribute is an empty string, not \"no content\"),
   matching how `content: \"\"` already behaves. Every other property, and
   every already-a-plain-string `content` value, passes through this step
   unaffected.

   `content: counter(name)` resolution -- the 5-arity form's `counters`
   argument: unlike attr(), a counter() reference cannot be resolved from
   `node` alone (see the namespace docstring and `parse-content-counter-ref`
   for why: a counter's value is the cumulative effect of every
   counter-reset/counter-increment declaration on every element preceding
   this point in document tree order). So this function accepts an
   additional, OPTIONAL `counters` argument -- the running named-counter map
   as of this exact point in a real top-down tree walk, which only
   `apply-cascade` can honestly build and thread (see its docstring and
   `style-with-counters`). The 4-arity form (used by `computed-style` and
   `pseudo-element-style-for`, i.e. every standalone, non-apply-cascade
   caller) implicitly passes `counters` nil, meaning \"no real tree-walk
   context exists\" -- `resolve-content-value` then honestly leaves any
   counter() reference unresolved (dropping :content entirely, same as any
   other unsupported form) rather than fabricating a number. This is a real,
   documented limitation of calling `computed-style`/`pseudo-element-style-for`
   outside `apply-cascade`, not a bug.

   `@container` matching -- the 6-arity form's `container-ctx` argument:
   any rule carrying `:rule/container` (see `parse-rules`) additionally
   needs `container-rule-matches?` to pass, given `node`'s own
   :node/id and `container-ctx` (nil by default, same treatment as
   `counters` above and for the same reason -- see
   `container-rule-matches?`'s own docstring for exactly which callers ever
   have a real one: only `apply-cascade`'s own second pass, never
   `computed-style`/`pseudo-element-style-for`, and never apply-cascade's
   own FIRST pass either, which must not let any @container rule contribute
   before container widths are even known)."
  ([document rules node pseudo-element]
   (resolve-style-for document rules node pseudo-element nil nil))
  ([document rules node pseudo-element counters]
   (resolve-style-for document rules node pseudo-element counters nil))
  ([document rules node pseudo-element counters container-ctx]
   (let [declarations (for [rule rules
                             :let [{:rule/keys [selectors declarations declaration-meta order layer-priority layer]} rule]
                             selector selectors
                             :when (= pseudo-element (pseudo-element-of selector))
                             :when (if document
                                     (matches? document node selector)
                                     (matches? node selector))
                             :when (container-rule-matches? rule (:node/id node) container-ctx)
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
     (let [m (reduce (fn [m {:keys [property value]}]
                        (assoc m property value))
                      {}
                      (sort-by (juxt :important? :inline? :unlayered? :layer :specificity :order)
                               (concat declarations inline-declarations)))]
       (if (contains? m :content)
         (let [resolved (resolve-content-value node counters (:content m))]
           (if (nil? resolved)
             (dissoc m :content)
             (assoc m :content resolved)))
         m)))))

(defn computed-style
  "Cascade-resolved style map for `node`. Regular declarations are flat
   `{property value}` entries. If any rule targets this node's `::before`/
   `::after` pseudo-element, that pseudo-element's own resolved style map is
   attached under the extra key :pseudo/before / :pseudo/after (only when
   non-empty) -- see the namespace docstring for what does/doesn't consume
   those keys downstream.

   Called standalone here (not via `apply-cascade`'s tree walk), so any
   `content: counter(name)` reference has no running counters map to
   resolve against and is honestly left unresolved (no :content key) --
   see `resolve-style-for`'s `counters` argument for exactly why, and
   `apply-cascade`'s own docstring for the tree walk that CAN resolve it."
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
   apply-cascade + DOM-attrs round trip. `content` values are already plain
   strings by the time they land in the returned map -- a quoted literal
   (see `parse-content-literal`) already was one, and a `content: attr(name)`
   reference (see `parse-content-attr-ref`/`resolve-content-value`) has
   already been resolved against `node`'s own real HTML attribute (`\"\"` if
   absent) by `resolve-style-for` above; `url()`/other unsupported `content`
   forms simply have no :content key in the returned map, same as `content`
   being absent.

   `content: counter(name)` is a REAL, DOCUMENTED LIMITATION of this
   standalone entry point specifically: unlike attr(), a counter's value is
   the cumulative effect of every counter-reset/counter-increment
   declaration on every element preceding this point in document tree order
   (see the namespace docstring) -- genuinely not derivable from `node` in
   isolation, with no document tree walk backing this call. So a
   `counter()` reference here is honestly left unresolved (no :content key,
   same as `content` being absent) rather than guessing a number. Only
   `apply-cascade`'s own top-down tree walk (see its docstring) can resolve
   `counter()` correctly, node by node, in document order.

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

(defn- apply-counter-pairs
  "Applies `op` (:reset or :increment) over `pairs` (a `[[name amount] ...]`
   vector -- the shape `parse-counter-property` produces for a resolved
   `counter-reset`/`counter-increment` declaration) to `counters`, a running
   name -> current-value map: :reset sets/overwrites the named counter to
   `amount` outright (real CSS: `counter-reset` re-initializes, discarding
   any prior value); :increment adds `amount` to the counter's current value,
   defaulting a name with no prior entry to 0 first (real CSS: an
   un-reset counter starts from 0 the first time it's referenced or
   incremented -- see the namespace docstring's \"flat, per-document\"
   counters simplification). `pairs` nil/empty is a no-op (`reduce` over nil
   returns `counters` unchanged), matching a node with no
   counter-reset/counter-increment declaration at all."
  [counters op pairs]
  (reduce (fn [counters [name amount]]
            (case op
              :reset (assoc counters name amount)
              :increment (update counters name (fnil + 0) amount)))
          counters
          pairs))

(defn- style-with-counters
  "Like `computed-style`, but for `apply-cascade`'s own top-down tree walk:
   resolves `node`'s base/::before/::after style given `inherited-counters`
   (the running named-counter map accumulated from every node that precedes
   `node` in document order -- see `apply-cascade`'s docstring), and returns
   `[style node-counters]`.

   `node-counters` is `inherited-counters` updated by `node`'s OWN
   `counter-reset` declaration (applied first) then its OWN
   `counter-increment` declaration (applied second) -- exactly real CSS's
   order (`apply-counter-pairs`) -- and is what descendants AND subsequent
   siblings should keep accumulating from.

   `node`'s ::before/::after `content: counter(name)` reference(s) resolve
   against `node-counters`, i.e. AFTER `node`'s own reset/increment already
   applied: matching real CSS, a `<li>` with both `counter-increment: item`
   and a `::before { content: counter(item); }` sees the INCREMENTED value,
   never the pre-increment one. This is only possible because
   counter-reset/counter-increment are read off `node`'s own BASE style
   first (`resolve-style-for ... nil inherited-counters` below), before
   ::before/::after are resolved at all.

   Known, honest simplification: `node`'s own (non-pseudo-element) `content`
   declaration, if it has one (unusual and not meaningfully rendered by real
   CSS on a regular element in the first place -- content only applies to
   generated ::before/::after boxes), is resolved against
   `inherited-counters` (pre-this-node), not `node-counters` -- it has to
   be, structurally: `node`'s own counter-reset/counter-increment
   declarations are read FROM that very same base-style resolution call, so
   `node-counters` isn't known yet at the point the base style resolves.
   Since real CSS doesn't render a plain element's own `content` anyway,
   this ordering choice has no real-world-visible consequence.

   `container-ctx` (see `resolve-style-for`'s own docstring) is threaded
   through unchanged to all three `resolve-style-for` calls below -- nil
   during apply-cascade's first pass (and from any standalone caller), the
   real containers/parent-index map during its second pass."
  [document rules node inherited-counters container-ctx]
  (let [base (resolve-style-for document rules node nil inherited-counters container-ctx)
        node-counters (-> inherited-counters
                          (apply-counter-pairs :reset (:counter-reset base))
                          (apply-counter-pairs :increment (:counter-increment base)))
        before (resolve-style-for document rules node :before node-counters container-ctx)
        after (resolve-style-for document rules node :after node-counters container-ctx)
        style (cond-> base
                (seq before) (assoc :pseudo/before before)
                (seq after) (assoc :pseudo/after after))]
    [style node-counters]))

(defn- style-element
  "Resolves and writes computed style attrs for a single element, given the
   custom-property environment inherited from its ancestors and the running
   named-counter map inherited from every node preceding it in document
   order (see `style-with-counters`). Returns `[document node-env
   node-counters]`: `node-env` is the environment children should inherit
   (`inherited-env` merged with this element's own resolved custom
   properties, unchanged from before counters existed); `node-counters` is
   the counters map children AND subsequent siblings should continue
   accumulating from (`inherited-counters` updated by this element's own
   `counter-reset`/`counter-increment`, see `style-with-counters`).
   `container-ctx` is threaded straight through to `style-with-counters`
   (nil unless this is apply-cascade's real second pass -- see its
   docstring)."
  [document rules node-id inherited-env inherited-counters container-ctx]
  (let [node (get-in document [:nodes node-id])
        [style node-counters] (style-with-counters document rules node inherited-counters container-ctx)
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
    [document node-env node-counters]))

(defn- run-cascade-walk
  "The actual top-down tree walk apply-cascade performs (see its own
   docstring for the custom-property environment / running-counters
   threading this does) -- factored out so apply-cascade can run it TWICE
   when `rules` contains any `@container` rule (see apply-cascade's
   docstring for why two passes, never more, are enough): once with
   `container-ctx` nil (no @container rule ever contributes -- see
   `container-rule-matches?`) purely to discover every container-marked
   element's own width from its non-@container declarations, and once more
   with the real `container-ctx` built from that first pass's result, this
   time letting @container rules compete on equal footing with every other
   declaration. When `rules` has no @container rule at all, apply-cascade
   calls this exactly once with container-ctx nil -- byte-for-byte the same
   single walk this namespace always performed, so stylesheets that never
   use `@container` see no behavior or performance change."
  [document rules container-ctx]
  (letfn [(walk [document node-id inherited-env inherited-counters visited]
            (let [node (get-in document [:nodes node-id])
                  [document node-env node-counters]
                  (if (= :element (:node/type node))
                    (style-element document rules node-id inherited-env inherited-counters container-ctx)
                    [document inherited-env inherited-counters])
                  visited (conj visited node-id)]
              (reduce (fn [[document visited counters] child-id]
                        (walk document child-id node-env counters visited))
                      [document visited node-counters]
                      (:children node))))]
    (let [[document visited] (if-let [root (:root document)]
                                (walk document root {} {} #{})
                                [document #{}])]
      (reduce-kv
       (fn [document node-id node]
         (if (and (= :element (:node/type node)) (not (contains? visited node-id)))
           (first (style-element document rules node-id {} {} container-ctx))
           document))
       document
       (:nodes document)))))

(defn apply-cascade
  "Applies the cascade over `document`, writing each element's resolved
   style onto its :style/* attrs (and :pseudo/before / :pseudo/after where
   applicable -- see `computed-style`).

   Walks top-down from the document root so CSS custom properties inherit
   from ancestor to descendant. Any element unreachable from :root (e.g. a
   detached subtree) still gets styled, using an empty inherited environment,
   to preserve the previous flat-walk behavior for those nodes.

   This same top-down walk also threads a running named-counter map (see
   `style-with-counters`/`style-element`), alongside (but NOT the same as)
   the custom-property environment: `counter-reset`/`counter-increment`
   are NOT inherited properties (each element's own declaration affects only
   itself, exactly like every other non-inherited CSS property, and exactly
   like the custom-property environment already does NOT get threaded
   sideways between siblings) -- but a counter's VALUE is nonetheless a
   running total across the WHOLE preceding document (ancestors, preceding
   siblings, and everything nested inside them), because that's what
   `content: counter(name)` actually reads. So unlike the custom-property
   environment (recomputed fresh, unaffected by siblings, for every child
   from its parent's fixed `node-env`), the counters map threaded into the
   next sibling is the one that comes OUT of the previous sibling's ENTIRE
   subtree, not the one going INTO it -- i.e. it is threaded through the
   `reduce` over children the same way `document`/`visited` already are,
   not passed down as a fixed value the way `node-env` is. This is a real,
   deliberate, honest simplification of real CSS's actual (element-nesting
   scoped) counter scoping rules: this engine keeps counters in one flat,
   per-document namespace instead (see the namespace docstring).

   `opts` (optional, 3-arity) may include :viewport-width (default
   `default-viewport-width`) used to decide whether `@media (min-width:...)`
   / `(max-width:...)` rule blocks apply.

   `@container` support (see the namespace docstring's own `@container`
   paragraph for the feature's scope) is why this function may run
   `run-cascade-walk` TWICE rather than once -- and why it never needs a
   third time, or a loop, to do it:

   1. If `rules` contains no `@container` rule at all (`(some :rule/container
      rules)` is nil), this is exactly the single walk this function always
      performed -- no new code path, no behavior change, no extra cost.

   2. Otherwise, PASS 1 runs `run-cascade-walk` over only the non-@container
      rules (`container-ctx` nil, so even if it were somehow passed an
      @container rule, `container-rule-matches?` would drop it) -- this
      resolves every element's OWN `width`/`min-width`/`max-width` (among
      everything else) exactly as real, unconditional, non-@container CSS
      would set them. `build-containers` then scans that pass's resulting
      document for every `container-type: inline-size`/`size` element and
      records its resolved width (`resolvable-container-width`) -- a NUMBER
      only when the author wrote a literal `<n>`/`<n>px` value, honestly
      nil otherwise (see that function's docstring): this engine does not
      run real layout, so any width that depends on layout (auto/
      percentage/flex/grid-driven sizing) is, and stays, unknown here,
      never guessed at.

   3. PASS 2 runs `run-cascade-walk` again, this time over the FULL `rules`
      (including @container ones) and a real `container-ctx` (the
      containers map from step 2, plus `parent-index` so
      `container-rule-matches?` can walk from any node up to its nearest
      matching container) -- letting @container-conditioned declarations
      compete on specificity/layer/order exactly like any other declaration
      for every node, now that container widths are known. This pass's
      result is what `apply-cascade` returns.

   This is deliberately bounded -- one extra full pass, never an iterated
   fixpoint/relayout loop -- which is exactly why it is scoped the way it
   is: a container whose own width isn't resolvable from pass 1 (because it
   depends on layout, or on ANOTHER, ancestor container's own @container
   rule -- nested/chained container queries are explicitly unsupported, see
   the namespace docstring) simply makes @container rules inside it not
   apply, rather than this function looping passes until things stabilize."
  ([document rules]
   (apply-cascade document rules {}))
  ([document rules opts]
   (let [viewport-width (or (:viewport-width opts) default-viewport-width)
         rules (filterv #(rule-applies-to-viewport? % viewport-width) rules)]
     (if (some :rule/container rules)
       (let [pass1-rules (filterv #(nil? (:rule/container %)) rules)
             pass1-document (run-cascade-walk document pass1-rules nil)
             container-ctx {:containers (build-containers pass1-document)
                            :parent-index (parent-index pass1-document)}]
         (run-cascade-walk document rules container-ctx))
       (run-cascade-walk document rules nil)))))

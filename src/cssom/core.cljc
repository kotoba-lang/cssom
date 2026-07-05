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
     NUMBER -- i.e. the author wrote a plain `<n>`/`<n>px` value (or a
     CONSTANT `calc(...)` this namespace's own numeric coercion already
     collapses to one, e.g. `calc(400px - 100px)` -- see `parse-style-value`
     -- since by the time this pass runs, that's already indistinguishable
     from a literal number), not `auto`, a percentage, a non-constant
     `calc(...)` (one mixing in `%`/`em`/any other relative unit), `fit-
     content`, or anything flex/grid/content-driven -- via one extra,
     bounded (never iterated/looped)
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
   - `:not(<selector>)` / `:is(<selector-list>)` / `:where(<selector-list>)`
     selector-FUNCTION pseudo-classes -- not to be confused with a bare
     pseudo-CLASS like `:hover`/`:disabled` (see `pseudo-class-pattern` vs
     `functional-pseudo-class-pattern`). `:not(<sel>)` matches an element
     that does NOT match `<sel>`; `:is(<sel-list>)` matches an element that
     matches ANY selector in a comma-separated list -- reusing
     `split-selector-list`, the exact same comma-splitting logic top-level
     `sel1, sel2 { ... }` rules already use, so whitespace/commas inside the
     parens behave identically (`selector-tokens`/`split-selector-list` both
     track paren-depth for this, alongside their existing bracket-depth
     tracking for attribute selectors); `:where(<sel-list>)` matches
     IDENTICALLY to `:is(...)` but -- the one easy-to-get-wrong difference
     this feature hinges on -- ALWAYS contributes ZERO specificity, whereas
     `:not()`/`:is()` contribute the specificity of their own most specific
     argument, per occurrence (real CSS 4 behavior; see `specificity`/
     `simple-selector-specificity`). Scoped, documented limitation: the
     argument inside the parens supports simple/compound selectors only
     (tag/id/class/attribute/pseudo-class combinations) -- no descendant/
     child/sibling combinators and no NESTED functional pseudo-classes
     inside the parens (see `parse-simple-selector`'s own docstring for
     exactly what that does and doesn't cover). This scope covers the
     overwhelming majority of real-world usage: `:not(.hidden)`,
     `:is(h1, h2, h3)`, `:where(.card, .panel)` are all compound-selector-
     only in practice.
   - `:has(<relative-selector-list>)`, the CSS 'parent selector' --
     `.card:has(.badge)` matches a `.card` that CONTAINS a `.badge`
     somewhere inside it, `li:has(input:checked)` matches an `<li>`
     containing a checked input. Parses through the exact same
     `functional-pseudo-class-pattern` + `split-selector-list` path as
     `:not()`/`:is()`/`:where()` above (`has` is simply one more name in
     that pattern's alternation) into its own `:selector/has` key -- but
     unlike those three, `:has()` is architecturally NEW for this
     namespace, not just another selector-list consumer: every OTHER
     pseudo-class here (`:not()`/`:is()`/`:where()`, the structural
     pseudo-classes, `:root`/`:empty`, `:lang()`, `:nth-last-child()`)
     tests a candidate node against its ANCESTOR chain or its SIBLINGS --
     walking UP or SIDEWAYS via `document`. `:has()` needs the OPPOSITE
     direction: for a candidate anchor node, walking DOWN into its own
     subtree and testing each DESCENDANT against a selector. This
     namespace already has exactly the traversal primitive that needs,
     though, for an unrelated feature: `descendant-node-ids` (a
     document/node-id -> flat descendant-node-id walk, via each node's own
     `:children` vector) already backs `selected-option-id`/
     `radio-group-node-ids` elsewhere in this file -- `has-arg-descendant-match?`
     reuses it verbatim rather than inventing a second downward walker.
     Each candidate descendant is tested with `matches-simple?` (NOT
     `matches?` -- a `:has()` argument item is always a single bare
     compound selector, never a combinator chain, so no descendant/sibling
     combinator walking is needed on top of the subtree walk itself),
     short-circuiting (`some`) the instant one descendant matches (real
     `:has()` semantics: ANY match is enough).

     Scoped, documented limitation, mirroring the exact same cut
     `:not()`/`:is()`/`:where()` already made above, for the same reason
     (the overwhelming majority of real-world usage fits it): each
     comma-separated item of the argument is a single compound selector
     (tag/id/class/attr/pseudo -- including a NESTED bare pseudo-class like
     `:has(input:checked)`, one of the most common real patterns), never a
     combinator chain INSIDE the argument (`:has(div p)` is out of scope,
     same as `:is(.a .b)` above). On top of that compound-selector-only
     cut, `:has()` supports exactly one optional LEADING combinator per
     comma-separated item, `>` (`:has(> img)`, also very common -- 'has a
     DIRECT CHILD img', not just any img anywhere inside): `:selector/has`
     stores each item as `{:has/selector <compound> :has/direct-child?
     bool}` (see `parse-has-item`), and matching dispatches to
     `has-arg-child-match?` (node's immediate `:children` only) instead of
     `has-arg-descendant-match?` (the full subtree) for a `direct-child?`
     item. Deliberately OUT of scope, and unsupported (never crashes, just
     never matches that form specially): sibling-relative `:has()` forms
     (`:has(~ p)`/`:has(+ p)` -- real CSS also allows these, testing
     siblings instead of descendants, but they are rarer in practice than
     the descendant/child forms above), the `:scope` pseudo-class itself,
     and -- same as `:not()`/`:is()`/`:where()` -- any combinator chain or
     nested functional pseudo-class inside one compound-selector argument.
     Like `:root`/`:lang()`/the structural pseudo-classes, matching an
     element against its own subtree obviously needs `document` (`node`'s
     `:children` are only ids -- resolving them to real nodes needs
     `document`), so `:has()` never matches via the document-less 2-arity
     `matches?`/`matches-simple?` form (`has-group-matches?` honestly
     returns false rather than attempting a documentless walk). `:has()`
     contributes specificity exactly like `:not()`/`:is()` do (the
     specificity of its own most specific argument, per occurrence,
     combinators contributing nothing extra -- see
     `simple-selector-specificity`), never `:where()`'s always-zero
     treatment.
   - Structural pseudo-classes `:first-child`/`:last-child`/`:only-child`
     and `:nth-child(<An+B>)`, plus their same-tag-only counterparts
     `:first-of-type`/`:last-of-type`/`:nth-of-type(<An+B>)`. Unlike
     `:not()`/`:is()`/`:where()` (a NEW parsing category -- a
     selector-LIST argument needing `functional-pseudo-class-pattern` +
     `split-selector-list`), these fit the EXISTING plain-pseudo-class path
     (`pseudo-class-pattern` already captures the bare `:name`, parens and
     all, exactly as it always has -- see `parse-simple-selector`): only
     `:nth-child()`/`:nth-of-type()` carry an argument at all, and it is
     always a short An+B micro-syntax token (a bare integer, `even`/`odd`,
     or `<n-coefficient>n<+-offset>` like `2n+1`/`-n+3`/`n+2`) that never
     itself contains parens -- so a small dedicated regex
     (`nth-pseudo-class-pattern`) captures just that argument string
     alongside the existing pseudo-name capture, parsed separately by
     `parse-nth-expression` into an `[A B]` pair. Matching (`nth-matches?`,
     `matches-pseudo?`) reuses the same sibling-traversal building blocks
     the `+`/`~` combinators already established (`parent-node-id`,
     document-order children filtered to `:element`-type only -- text
     nodes never count toward sibling position, matching real CSS): a
     node's 1-indexed position among its parent's element children (ALL of
     them, for the `:nth-child`/`:first-child`/`:last-child`/`:only-child`
     family) or among only its SAME-TAG-NAME siblings (for the
     `:nth-of-type`/`:first-of-type`/`:last-of-type` family -- e.g. among
     alternating `<p>`/`<span>` siblings, `:nth-of-type` position resets
     per tag while `:nth-child` position does not). `p = A*n + B` for some
     integer `n >= 0` is the real CSS An+B matching rule (`nth-matches?`);
     an element with no parent at all (a detached/root node) never matches
     any of these, same as real CSS.

     `:nth-child(<An+B>)`/`:nth-of-type(<An+B>)` also have their own
     from-the-end mirrors, `:nth-last-child(<An+B>)`/
     `:nth-last-of-type(<An+B>)` -- real CSS's way of counting backward from
     the LAST matching sibling instead of forward from the first (e.g.
     `:nth-last-child(1)` means 'the last child', `:nth-last-child(-n+2)`
     means 'the last two children', both common real-world idioms). This
     needed no new micro-syntax or parser at all: the An+B argument grammar
     is byte-for-byte identical (`nth-pseudo-class-pattern`'s regex just
     grew two more name alternatives, `parse-nth-expression` is reused
     completely unchanged), and the only genuinely new piece of logic is
     which sibling INDEX gets tested against that same `[A B]` pair.
     `nth-pseudo-matches?` already computed `node`'s 1-indexed FORWARD
     position and the full relevant sibling set (`structural-siblings`/
     `sibling-position`) to answer `:nth-child`/`:nth-of-type` -- reversing
     that into a from-the-end index needs no second, backward sibling walk,
     just arithmetic on numbers already in hand:
     `total-siblings - forward-position + 1` (position `total` -- the last
     sibling -- reverses to 1; position 1 -- the first -- reverses to
     `total`). `nth-matches?`'s own An+B arithmetic then runs against that
     reversed index exactly as it does against a forward one, including
     correctly for a negative A coefficient like `:nth-last-child(-n+2)`
     (there is nothing special about negative A once the index itself is
     already the right one to test -- see `nth-pseudo-matches?`'s own
     docstring for the reversal formula and a worked example).
   - `:root` and `:empty`, two more argument-less pseudo-classes fitting the
     same plain-pseudo-class path the structural pseudo-classes above use
     (no parser changes needed at all -- `pseudo-class-pattern` already
     captures both bare names). `:root` matches ONLY the document's own
     root element -- `kotoba.wasm.dom`'s document map already names that
     element's node-id under a `:root` KEY (set by `dom/set-root`, read by
     `apply-cascade`'s own top-down walk and elsewhere in this namespace,
     e.g. `radio-group-node-ids`) -- not to be confused with the `:root`
     CSS pseudo-CLASS of the same name; matching is simply comparing
     `node`'s own `:node/id` against that key (`matches-pseudo?`), the same
     document-dependent restriction `:focus` already has (needs `document`;
     never matches via the document-less 2-arity `matches?`/
     `matches-simple?` form). `:empty` matches an element with NO children
     AT ALL, of any node type -- a stricter, and different, question than
     the structural pseudo-classes' element-only sibling counting above:
     real CSS's `:empty` counts a `:text` child too, UNLESS that text
     node's data is genuinely zero-length -- so a WHITESPACE-only text
     child (a single space/newline between tags) still counts as content
     (non-zero length), meaning `<div> </div>` does NOT match `:empty`,
     only a truly childless `<div></div>` does (verified against real
     browser behavior, not assumed -- see `child-counts-as-content?`).
   - `:lang(<tag>)`, a pseudo-class FUNCTION taking a comma-separated list of
     BCP-47-ish language tags/ranges (`:lang(en)`, `:lang(en, fr)`). Unlike
     `:not()`/`:is()`/`:where()` (a selector-list argument, needing
     `functional-pseudo-class-pattern` + `split-selector-list`) or
     `:nth-child()`/`:nth-of-type()` (an An+B micro-syntax argument), this
     argument is its own narrow micro-syntax -- a comma-separated list of
     bare identifiers and/or quoted strings (`lang-pseudo-class-pattern`
     captures the raw argument text exactly like `nth-pseudo-class-pattern`
     does, since a lang-range list never itself contains parens either;
     `parse-lang-ranges` then splits/unquotes it), each compared against the
     element's OWN computed language: the nearest `lang` HTML attribute
     found walking from the element ITSELF upward through its ancestor
     chain (`computed-lang`, reusing the exact same `parent-node-id`
     ancestor-chain walk `disabled-by-fieldset?`/`disabled-by-optgroup?`
     already established elsewhere in this namespace for a different
     purpose -- see the structural-pseudo-classes paragraph above for that
     precedent too) -- the element's own `lang` attribute wins if present
     and non-blank, otherwise the nearest ancestor's, matching real CSS
     language inheritance; an element with no non-blank `lang` attribute
     anywhere from itself up to the document root has no computed language
     at all and never matches any `:lang()` argument (a blank `lang=\"\"`
     -- real HTML/CSS's own way of saying 'explicitly unknown language' --
     is treated exactly like no `lang` attribute at all: `computed-lang`
     keeps walking up past it rather than treating \"\" as a real, if
     empty, tag).

     A single range matches case-insensitively when it is a WHOLE-SUBTAG
     prefix of the computed tag, `-`-separated (`lang-range-matches-tag?`)
     -- e.g. `:lang(en)` matches `lang=\"en\"`/`lang=\"en-US\"`/
     `lang=\"EN-us\"` but NOT `lang=\"eng\"` (real CSS's own
     subtag-boundary rule: a language range must match one or more WHOLE
     leading subtags, never just a bare string prefix -- `[\"en\"]` is a
     whole-subtag prefix of `[\"en\" \"us\"]` but is not, and can never be,
     a whole-subtag prefix of `[\"eng\"]`, even though \"en\" is a plain
     STRING prefix of \"eng\") -- and the
     comma-separated list matches if ANY range in it does (`:lang(en, fr)`
     matches either English or French content). Deliberately out of scope:
     RFC 4647 extended filtering's `*` wildcard subtag syntax
     (`:lang(*-fr)`/`:lang(de-*-DE)`) -- rare in practice; the overwhelming
     majority of real-world `:lang()` usage is a plain tag or comma list of
     plain tags, e.g. `:lang(en)`/`:lang(en, fr, de)`, exactly this
     engine's scope. Like `:root` and the structural pseudo-classes above,
     `:lang()` needs `document` to walk the ancestor chain at all, so it
     never matches via the document-less 2-arity `matches?`/
     `matches-simple?` form (the same restriction `:focus`/`:first-child`
     already have). This trusted-subset parser has no `xml:lang` concept at
     all -- only the plain HTML `lang` attribute exists here -- so
     `:lang()` checking only `lang` isn't a gap, it's the correct, complete
     behavior for this engine's scope (real CSS additionally falls back to
     `xml:lang` only for XML documents, which this engine doesn't model).
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
     `!important` reversal mechanics, including real inline-style
     `!important` support.
   - `calc(...)` arithmetic expressions -- but only the genuinely bounded,
     ALWAYS layout-independent subset: an expression whose ENTIRE contents
     are constant numeric literals (plain numbers and/or `px` lengths --
     NOT `%`/`em`/`vh`/`vw`/any other unit) combined with `+`/`-`/`*`/`/`
     and parens, e.g. `calc(100px + 20px)` -> `120`, `calc(2 * 8px)` ->
     `16`, `calc(100px / 4)` -> `25`. Real CSS `calc()` can mix absolute
     (`px`) with relative (`%`/`em`/viewport units/...) lengths that only
     resolve at LAYOUT time against a container's actual size -- the same
     'needs layout, which this cascade-then-layout pipeline doesn't have at
     cascade time' gap `@container` queries hit (see
     `resolvable-container-width`) -- so that general case is deliberately
     out of scope, not approximated. See `parse-calc`/`parse-calc-ast`/
     `eval-calc-node` for the tokenize -> parse (correct `*`/`/`-before-
     `+`/`-` precedence, left-to-right same-precedence associativity, so
     `calc(10px - 5px - 2px)` -> `3`, not `10 - (5 - 2)`) -> evaluate
     pipeline, and real CSS's own arithmetic-validity rules this honors:
     `+`/`-` require both sides to be the SAME kind (both plain numbers or
     both px lengths); `*` requires AT LEAST ONE side to be a plain
     unitless number (you can't multiply two lengths together); `/`'s
     divisor must be a plain unitless number (and non-zero). Wired into
     `parse-style-value` -- the general numeric/px coercion every
     declaration already goes through -- so every property gets it for
     free (`width`, `padding`, `margin`, `gap`, `top`/`left`, etc.); a
     `calc(...)` outside this subset (a percentage/other relative unit
     anywhere inside, an arithmetic-type violation, or a malformed
     expression) degrades to the SAME 'unusable, falls through as a raw
     unparsed string' treatment `calc()` already had before this subset was
     supported, never a guessed number. `cssom.layout` has its own SEPARATE,
     small mirror of this same constant-subset resolver for grid track
     lists (`grid-template-columns`/`grid-template-rows` are multi-token
     strings `parse-style-value` never touches at all -- see
     `parse-track-list`'s docstring), since that file already owns its own
     numeric coercion independent of this namespace (see its ns docstring).
   - Attribute selectors' optional trailing case-sensitivity FLAG --
     `[attr=val i]` / `[attr=val I]` (force case-INSENSITIVE matching,
     regardless of whether HTML happens to define that particular
     attribute as case-sensitive by default) and `[attr=val s]` /
     `[attr=val S]` (the explicit, no-op case-SENSITIVE default -- valid
     syntax that parses successfully and changes nothing) -- CSS Selectors
     Level 4's attribute-selector modifier, e.g. `[type=\"text\" i]`/
     `[lang=\"en\" i]`, a common real-world idiom for matching an HTML
     attribute value whose casing the author can't fully control.
     `attribute-selector-pattern`/`parse-attribute-selector` capture it
     into `:attr/case-insensitive?` (true only for `i`/`I`; `s`/`S` needs
     no special handling beyond parsing successfully), and
     `matches-simple?`'s attribute clause honors it by lower-casing BOTH
     `actual` and `value` before comparing -- the same `str/lower-case`
     convention `:lang()`'s subtag matching already established above --
     for EVERY operator this engine supports (`=`/`~=`/`^=`/`$=`/`*=`/
     `|=`, and the presence-only no-operator case, which the flag is
     simply irrelevant to), including `~=`'s own whitespace-split token
     set (each split token, not just the whole attribute value, needs the
     same lower-casing). Before this addition, `[attr=val i]` syntax
     didn't just fail to apply the flag: the flag token made the WHOLE
     bracket fail to match `attribute-selector-pattern` at all (no
     provision for anything besides whitespace between the value and the
     closing `]`), so the entire attribute constraint silently vanished
     from the compound selector -- an `[attr=val i]`-only compound
     selector matched EVERY element rather than the intended subset (a
     compound selector left with no tag/id/class/attr/pseudo constraints
     at all matches unconditionally), a worse failure mode than simply
     matching nothing. The flag must be separated from the value by REAL
     whitespace in this engine's regex (`\\s+`, not `\\s*`) -- not an
     arbitrary tightening: an unquoted attribute value tokenizes as a
     single maximal-munch identifier in real CSS, so `[data-x=abcs]` can
     never legitimately split into value `abc` + flag `s` (there is no
     whitespace token separating them, ever) -- and a `\\s*`-based
     (optional-whitespace) regex would, via ordinary greedy-then-backtrack
     matching, wrongly find exactly that split (backing the unquoted-value
     match off by one trailing character to let it double as the flag)
     the moment a mandatory-whitespace requirement isn't there to forbid
     it."
  (:require [clojure.string :as str]
            [kotoba.wasm.dom :as dom]))

;; ---- calc() -- constant, percentage-free arithmetic only ----
;;
;; See the namespace docstring's `calc(...)` paragraph for the scope this
;; honors and why (bounded to plain numbers/px lengths, real CSS's own
;; arithmetic-validity rules, wired into parse-style-value below). This is a
;; small, real tokenize -> parse (recursive descent, precedence climbing) ->
;; evaluate pipeline, not a regex hack -- calc() genuinely nests
;; (parentheses, `*`/`/` binding tighter than `+`/`-`), and getting
;; precedence/associativity/negative-number handling right needs real
;; parsing.

(def ^:private calc-pattern
  "Matches a whole-value `calc(...)` declaration -- the ENTIRE value is one
   calc() call, case-insensitively (real CSS's `calc` keyword is
   case-insensitive), with the parenthesized contents captured (group 1)
   for `parse-calc-ast`. A value with anything besides the call itself
   (leading/trailing text, `calc(1px) calc(2px)`, math mixed with a
   keyword) does not match -- multiple/composed calc() terms in one
   declaration are out of scope, matching parse-style-value's existing
   'ENTIRE-value' coercion approach (a bare number or px length also only
   ever coerces when it is the WHOLE value). The greedy `(.*)` capture
   between the outer `calc(` and the final `)` is safe for NESTED parens
   (`calc((10px + 6px) * 2)`) precisely because `re-matches` anchors both
   ends: greedy matching grabs everything up to the LAST `)` in the string,
   which for a well-formed whole-value calc() is exactly the call's own
   closing paren."
  #"(?is)calc\((.*)\)")

(defn- calc-number-at
  "Attempts to match a numeric literal -- optionally decimal, optionally
   with an immediately-following `px` unit (no space allowed between the
   number and its unit, matching real CSS) -- starting at index `idx` of
   calc() tokenizer input `s`. Returns `[token next-idx]`, or nil if `idx`
   isn't the start of one -- signalling to `tokenize-calc-expr` that
   whatever is at `idx` isn't part of this engine's constant-calc()
   subset at all (a `%`/`em`/any other unit, or stray text), the same
   'stop, don't guess' contract every other token-matching helper in this
   namespace already uses (e.g. `content-attr-pattern`)."
  [s idx]
  (when-let [num-str (re-find #"^\d+(?:\.\d+)?" (subs s idx))]
    (let [after (+ idx (count num-str))
          px? (and (<= (+ after 2) (count s)) (= "px" (subs s after (+ after 2))))
          end (if px? (+ after 2) after)
          value #?(:clj (Double/parseDouble num-str) :cljs (js/parseFloat num-str))]
      [{:calc/type :operand :calc/unit (if px? :px :number) :calc/value value} end])))

(defn- tokenize-calc-expr
  "Tokenizes the inside of a `calc(...)` call (see calc-pattern) into a flat
   token vector -- bare operator/paren tokens (`:calc/type` one of `:plus`/
   `:minus`/`:star`/`:slash`/`:lparen`/`:rparen`) plus number-or-px-length
   operand tokens (`calc-number-at`) -- for `parse-calc-level`, skipping
   whitespace. Returns nil if any character isn't part of one of those
   recognized tokens (e.g. a `%`/`em`/other unit anywhere in the
   expression, or any other unrecognized character) -- signalling 'not this
   engine's constant-calc() subset' all the way up to `parse-calc`, which
   then degrades the whole calc() exactly like any other unparseable value
   in this namespace degrades (see parse-style-value)."
  [s]
  (let [n (count s)]
    (loop [idx 0 tokens []]
      (cond
        (= idx n) tokens
        (re-matches #"\s" (str (nth s idx))) (recur (inc idx) tokens)
        :else
        (case (nth s idx)
          \+ (recur (inc idx) (conj tokens {:calc/type :plus}))
          \- (recur (inc idx) (conj tokens {:calc/type :minus}))
          \* (recur (inc idx) (conj tokens {:calc/type :star}))
          \/ (recur (inc idx) (conj tokens {:calc/type :slash}))
          \( (recur (inc idx) (conj tokens {:calc/type :lparen}))
          \) (recur (inc idx) (conj tokens {:calc/type :rparen}))
          (if-let [[operand next-idx] (calc-number-at s idx)]
            (recur next-idx (conj tokens operand))
            nil))))))

(defn- parse-calc-level
  "Parses a calc() token vector (see tokenize-calc-expr) into an AST node
   (`:calc/op` one of `:num`/`:neg`/`:add`/`:sub`/`:mul`/`:div`, see
   eval-calc-node) via PRECEDENCE CLIMBING, `level` 0 = lowest precedence
   (`+`/`-`), 1 = `*`/`/`, 2 = unary `+`/`-` and a primary (a number/px
   operand, or a parenthesized sub-expression which always restarts at
   level 0). Returns `[node remaining-tokens]`, or nil on any parse failure
   (an operator with no right-hand operand, an unclosed paren, an empty
   expression) -- `parse-calc-ast` additionally requires `remaining-tokens`
   to be exhausted, catching a malformed trailing fragment
   (`calc(1px 2px)`, `calc(1px))`) that this function alone would otherwise
   silently ignore.

   Written as ONE self-recursive function (recursing into itself with a
   different `level`, including for a parenthesized sub-expression
   restarting at level 0) rather than the classic four mutually-recursive
   `expr`/`term`/`factor`/`primary` grammar functions: this namespace
   deliberately avoids declare-based forward references (see
   `parse-counter-amount`'s docstring for that precedent), and parens in a
   real grammar want exactly that kind of forward-referencing mutual
   recursion (a primary needs to call back into the top-level expression
   parser) -- folding the whole grammar into one precedence-parameterized
   function sidesteps the need for `declare` entirely while still encoding
   correct `*`/`/`-before-`+`/`-` binding and left-to-right same-level
   associativity (each level's own `loop` folds left, so
   `calc(10px - 5px - 2px)` parses as `(10px - 5px) - 2px`, not
   `10px - (5px - 2px)`)."
  [tokens level]
  (if (= level 2)
    (when (seq tokens)
      (let [t (first tokens)]
        (case (:calc/type t)
          :minus (when-let [[node toks] (parse-calc-level (rest tokens) 2)]
                   [{:calc/op :neg :calc/arg node} toks])
          :plus (parse-calc-level (rest tokens) 2)
          :operand [{:calc/op :num :calc/unit (:calc/unit t) :calc/value (:calc/value t)}
                    (rest tokens)]
          :lparen (when-let [[node toks] (parse-calc-level (rest tokens) 0)]
                    (when (and (seq toks) (= :rparen (:calc/type (first toks))))
                      [node (rest toks)]))
          nil)))
    (let [ops (if (= level 0) #{:plus :minus} #{:star :slash})
          op->ast (fn [op] (case op :plus :add :minus :sub :star :mul :slash :div))]
      (when-let [[left toks] (parse-calc-level tokens (inc level))]
        (loop [left left toks toks]
          (if (and (seq toks) (contains? ops (:calc/type (first toks))))
            (let [op (:calc/type (first toks))]
              (if-let [[right toks2] (parse-calc-level (rest toks) (inc level))]
                (recur {:calc/op (op->ast op) :calc/left left :calc/right right} toks2)
                nil))
            [left toks]))))))

(defn- parse-calc-ast
  "Tokenizes and parses a `calc(...)` call's inner text (see
   tokenize-calc-expr/parse-calc-level) into a full AST, or nil if it
   isn't a well-formed expression in this engine's constant-calc() subset
   (tokenization failed, parsing failed, or leftover unconsumed tokens
   remained -- a malformed trailing fragment)."
  [expr-text]
  (when-let [tokens (tokenize-calc-expr expr-text)]
    (when-let [[node toks] (parse-calc-level tokens 0)]
      (when (empty? toks) node))))

(defn- eval-calc-node
  "Evaluates a parsed calc() AST node (see parse-calc-ast) into a
   `[value unit]` pair, `unit` one of `:number` (a plain, dimensionless
   number) or `:px` (a resolved pixel length) -- or nil if this node (or
   any descendant) violates real CSS calc()'s own arithmetic-validity
   rules for this engine's px-or-plain-number subset:
     - `:add`/`:sub` (`+`/`-`) require BOTH operands to be the SAME unit
       (both `:number` or both `:px`) -- real CSS's own same-type addition
       rule -- and the result keeps that unit.
     - `:mul` (`*`) requires AT LEAST ONE operand to be a plain `:number`
       (real CSS: 'at most one operand of a product can carry a unit',
       i.e. you can't multiply two lengths together) -- the result's unit
       is whichever operand ISN'T `:number` (or `:number` if both are).
     - `:div` (`/`) requires the RIGHT operand (the divisor) to be a plain
       `:number`, and non-zero -- the result's unit is the left operand's
       (dividend's) own unit. Division by the number zero is rejected
       (nil) rather than producing Infinity/NaN, matching real CSS
       (division by zero is invalid calc())."
  [node]
  (case (:calc/op node)
    :num [(:calc/value node) (:calc/unit node)]

    :neg (when-let [[v u] (eval-calc-node (:calc/arg node))]
           [(- v) u])

    :add (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (when (= lu ru) [(+ lv rv) lu])))

    :sub (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (when (= lu ru) [(- lv rv) lu])))

    :mul (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (cond
               (= lu :number) [(* lv rv) ru]
               (= ru :number) [(* lv rv) lu]
               :else nil)))

    :div (when-let [[lv lu] (eval-calc-node (:calc/left node))]
           (when-let [[rv ru] (eval-calc-node (:calc/right node))]
             (when (and (= ru :number) (not (zero? rv)))
               [(/ lv rv) lu])))

    nil))

(defn- calc-result->number
  "Normalizes an evaluated calc() result (see eval-calc-node) to a plain
   number in this namespace's own convention: an exact integer (matching
   parse-style-value's existing Long/parseLong coercion for a literal
   `<n>`/`<n>px` value) when the arithmetic came out whole -- e.g.
   `calc(100px / 4)` -> `25`, not `25.0` -- and a double (not rounded, so no
   precision is thrown away) for a genuinely fractional result, e.g.
   `calc(100px / 3)`."
  [value]
  (let [truncated (long value)]
    (if (== value truncated) truncated value)))

(defn- parse-calc
  "Parses a whole-value CSS `calc(...)` expression's already-extracted
   inner text (see calc-pattern) into the single plain number it resolves
   to, for the bounded, ALWAYS layout-independent subset this engine
   supports -- see the namespace docstring's `calc(...)` paragraph for the
   full scope and rationale. Returns nil for anything outside that subset
   (a percentage/other non-px unit anywhere inside, an arithmetic-type
   violation -- e.g. `calc(1px * 1px)`, `calc(1px / 1px)`, `calc(1px + 1)`
   -- or a malformed expression), so callers (`parse-style-value`) treat it
   exactly like every other unparseable value in this namespace: the
   declaration falls through/degrades rather than guessing a number."
  [expr-text]
  (when-let [node (parse-calc-ast expr-text)]
    (when-let [[value _unit] (eval-calc-node node)]
      (calc-result->number value))))

(defn- parse-style-value
  "Parses a single declaration's raw value string into a number when it is
   ENTIRELY a bare integer (`-?\\d+`) or a single `<n>px` length (`-?\\d+px`)
   -- both coerced via a plain Long/parseInt, this namespace's simple
   numeric-literal subset used everywhere a CSS property's value gets
   generic numeric coercion (`parse-property-value`) -- or when it is
   ENTIRELY a whole-value `calc(...)` call (see calc-pattern) whose inner
   arithmetic is this engine's bounded constant-calc() subset (plain
   numbers/px lengths, `+`/`-`/`*`/`/`/parens, no percentage or other
   relative unit -- see the namespace docstring's `calc(...)` paragraph and
   `parse-calc`), in which case it resolves to that single plain number
   too (e.g. `calc(100px + 20px)` -> `120`). Anything else -- `auto`, a
   percentage, `fit-content`, a `calc(...)` outside the constant subset
   (mixing in `%`/`em`/other relative units, or otherwise malformed/
   arithmetically invalid), any other keyword/expression -- is returned
   completely unchanged as a trimmed raw string rather than guessing,
   exactly this namespace's existing 'degrade, don't crash' posture for
   every other unparseable value."
  [v]
  (let [v (str/trim (str v))]
    (cond
      (re-matches #"-?\d+" v) #?(:clj (Long/parseLong v) :cljs (js/parseInt v 10))
      (re-matches #"-?\d+px" v) #?(:clj (Long/parseLong (subs v 0 (- (count v) 2)))
                                   :cljs (js/parseInt v 10))
      :else
      (if-let [[_ inner] (re-matches calc-pattern v)]
        (or (parse-calc inner) v)
        v))))

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

(defn parse-declarations-with-importance
  "Parses a raw `property: value; ...` declaration-block string (e.g. a
   `<style>` rule body, or a JS-mutated `element.style.cssText`) into a
   `{property {:value v :important? bool}}` map -- for callers that need
   real per-property `!important` tracking, not just a corruption-free
   value (see `parse-declarations` for the simpler bare-value form).
   `important?` is true iff that declaration's raw value ended in a
   trailing `!important` (case-insensitive), which is stripped from
   `:value` either way."
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

(defn parse-declarations
  "Like `parse-declarations-with-importance`, but each entry's value is
   the bare parsed value, discarding importance -- for callers (e.g.
   `:rule/declarations`, or `kotoba-lang/browser`'s `dom_bridge.cljc`
   before this fix) that don't need per-property `!important` tracking."
  [text]
  (into {} (map (fn [[k v]] [k (:value v)])) (parse-declarations-with-importance text)))

(defn- parse-attribute-selector
  "Parses a single `[...]` attribute selector (see `attribute-selector-pattern`)
   into `{:attr/name :attr/operator :attr/value :attr/case-insensitive?}`.
   The trailing CSS Selectors Level 4 case-sensitivity flag (`i`/`I`/`s`/`S`,
   see the namespace docstring's own paragraph on it) is optional and only
   ever meaningful when a value is present -- captured here as group 6,
   REQUIRED to be separated from the value by at least one real whitespace
   character (`\\s+`, not `\\s*`) so an unquoted value's own trailing
   `i`/`s` character (`[data-x=abcs]`) can never be misread as a
   whitespace-less flag (see the namespace docstring for why `\\s*` would
   be a genuine bug here, not just stylistic). `:attr/case-insensitive?` is
   true only for `i`/`I` -- `s`/`S` is the explicit, already-default
   case-SENSITIVE behavior and needs no special handling beyond parsing
   successfully."
  [text]
  (when-let [[_ attr operator double-quoted single-quoted unquoted flag]
             (re-matches #"\[\s*([A-Za-z_][-A-Za-z0-9_]*)\s*(?:(~=|\|=|\^=|\$=|\*=|=)\s*(?:\"([^\"]*)\"|'([^']*)'|([^\]\s]+))(?:\s+([iIsS]))?)?\s*\]" text)]
    {:attr/name (keyword attr)
     :attr/operator operator
     :attr/value (or double-quoted single-quoted unquoted)
     :attr/case-insensitive? (boolean (#{"i" "I"} flag))}))

(def attribute-selector-pattern
  "Matches a single `[...]` attribute selector, including its optional
   trailing CSS Selectors Level 4 case-sensitivity flag (`i`/`I`/`s`/`S`,
   see the namespace docstring and `parse-attribute-selector`) -- the flag
   is only recognized as part of the SAME optional group as the
   operator+value (so a bare `[attr]` presence-only selector, with no
   value at all, correctly has no flag position to match either), and
   must be separated from the value by at least one real whitespace
   character, never zero, for the same unquoted-value-boundary reason
   `parse-attribute-selector` documents."
  #"\[\s*[A-Za-z_][-A-Za-z0-9_]*\s*(?:(?:~=|\|=|\^=|\$=|\*=|=)\s*(?:\"[^\"]*\"|'[^']*'|[^\]\s]+)(?:\s+[iIsS])?)?\s*\]")

(def pseudo-class-pattern
  #":([A-Za-z_][-A-Za-z0-9_]*)")

(def pseudo-element-pattern
  "Matches `::before`/`::after` and the legacy single-colon `:before`/`:after`
   spelling. Deliberately narrower than a generic `::foo` pattern -- this
   subset only supports before/after generated content (see namespace doc)."
  #"(?i)::?(before|after)\b")

(def functional-pseudo-class-pattern
  "Matches a single `:not(...)` / `:is(...)` / `:where(...)` / `:has(...)`
   occurrence -- the selector-FUNCTION forms of these pseudo-classes, each
   capturing its own name (group 1) and its raw parenthesized argument text
   (group 2). `:has(...)` (see the namespace docstring's own `:has()`
   paragraph for how its matching semantics differ from the other three)
   shares this exact pattern/parsing path since its argument is, syntactically,
   the same shape: a comma-separated list captured as raw text, parsed
   downstream by `split-selector-list` -- only what happens to each parsed
   item afterward differs (`parse-has-item` additionally
   detects a leading `>` combinator, see `parse-simple-selector`).
   Deliberately distinct from `pseudo-class-pattern` (which matches the bare
   `:name` shape of an ordinary pseudo-class like `:hover`/`:disabled`): the
   trailing `\\(` this pattern requires right after the name is what keeps
   the two patterns from double-matching the same `:not`/`:is`/`:where`/
   `:has` text (`pseudo-class-pattern` alone would otherwise match just the
   name and leave the parenthesized argument as unconsumed, confusing,
   leftover text -- see `parse-simple-selector`'s docstring for the concrete
   bug this caused before this pattern existed).

   The argument is captured as `[^()]*` -- deliberately unable to contain
   another `(` or `)` -- which scopes this pattern (and everything that
   consumes it) to NON-NESTED occurrences only: nested functional
   pseudo-classes inside the argument (`:is(:not(.a))`, `:has(:is(.a))`) are
   out of scope (see `parse-simple-selector`'s docstring for what happens
   instead of crashing), and an attribute selector's own `[...]` inside the
   argument is fine (`:not([hidden])`, `:has([hidden])`) since square
   brackets never conflict with this pattern's parens."
  #"(?i):(not|is|where|has)\(([^()]*)\)")

(def nth-pseudo-class-pattern
  "Matches a single `:nth-child(...)` / `:nth-of-type(...)` /
   `:nth-last-child(...)` / `:nth-last-of-type(...)` occurrence, capturing
   its name (group 1) and its raw parenthesized An+B argument text (group
   2, e.g. `\"2n+1\"`, `\"even\"`, `\"-n+3\"` -- see `parse-nth-expression`
   for the micro-syntax this argument is parsed with -- IDENTICAL for all
   four names, see `nth-pseudo-matches?`). Unlike
   `functional-pseudo-class-pattern` (:not()/:is()/:where()'s own argument,
   a full comma-separated SELECTOR LIST that needs `split-selector-list`'s
   paren/bracket-depth-aware splitting), this argument is always a short
   keyword/integer/An+B token that never itself contains parens, so a plain
   non-nested `[^()]*` capture is enough -- no companion
   selector-list-splitting machinery needed.

   `nth-last-child`/`nth-last-of-type` don't collide with the plain
   `nth-child`/`nth-of-type` alternatives despite sharing the `nth-` prefix:
   the literal text right after it diverges immediately (`-child`/`-of-type`
   vs `-last-child`/`-last-of-type`), so this alternation is unambiguous
   regardless of the order the four names are listed in.

   The bare pseudo-class NAME (`:nth-child`, alongside the argument-less
   `:first-child`/`:last-child`/`:only-child`/`:first-of-type`/
   `:last-of-type` structural pseudo-classes) is already picked up by the
   existing `pseudo-class-pattern` exactly as it always has been -- that
   regex's own `:name` match doesn't care what follows, parens included, so
   `:selector/pseudos` already contained `:nth-child`/`:nth-of-type`/
   `:nth-last-child`/`:nth-last-of-type` before this pattern existed (the
   actual pre-existing gap was that nothing ever looked at those keywords in
   `matches-pseudo?`, and the argument text was simply discarded). This
   pattern exists ONLY to additionally capture that argument text, which
   `pseudo-class-pattern` alone cannot (its match stops at the pseudo name,
   never consuming a trailing `(...)`) -- see `parse-simple-selector`'s
   `:selector/nth-args`."
  #"(?i):(nth-last-child|nth-last-of-type|nth-child|nth-of-type)\(([^()]*)\)")

(def lang-pseudo-class-pattern
  "Matches a single `:lang(...)` occurrence, capturing its raw parenthesized
   argument text (group 1 -- e.g. `\"en\"`, `\"en, fr\"`, `\"'en-US'\"` --
   see `parse-lang-ranges` for the comma-separated tag/quoted-string-list
   micro-syntax this argument is parsed with). Mirrors
   `nth-pseudo-class-pattern` exactly (see its own docstring for why a
   plain non-nested `[^()]*` capture is enough here too -- a lang-range
   list never itself contains parens): the bare `:lang` pseudo-class NAME
   is already picked up by the existing `pseudo-class-pattern` regex (that
   match stops at the name, parens and all, exactly like every other
   pseudo-class); this pattern exists ONLY to additionally capture the
   argument text, into `:selector/lang-args`."
  #"(?i):lang\(([^()]*)\)")

(defn- append-token
  [tokens token]
  (cond-> tokens
    (not (str/blank? token)) (conj token)))

(defn selector-tokens
  "Splits `selector` into its top-level compound-selector/combinator tokens
   (see `parse-selector`), tracking BOTH `[...]` bracket-depth (attribute
   selectors) and `(...)` paren-depth (a `:not(...)`/`:is(...)`/
   `:where(...)` functional pseudo-class's argument, see
   `functional-pseudo-class-pattern`) so that a combinator character or
   whitespace INSIDE either kind of nesting never splits a token -- e.g.
   `.card:is(.a, .b) > p` must tokenize as `[\".card:is(.a, .b)\" \">\" \"p\"]`,
   not split again at the space after the comma inside the parens."
  [selector]
  (let [s (str selector)
        n (count s)]
    (loop [idx 0
           start 0
           bracket-depth 0
           paren-depth 0
           quote-char nil
           tokens []]
      (if (= idx n)
        (append-token tokens (str/trim (subs s start idx)))
        (let [ch (nth s idx)
              escaped? (and (pos? idx) (= \\ (nth s (dec idx))))
              nesting-depth (+ bracket-depth paren-depth)]
          (cond
            (and quote-char (= ch quote-char) (not escaped?))
            (recur (inc idx) start bracket-depth paren-depth nil tokens)

            quote-char
            (recur (inc idx) start bracket-depth paren-depth quote-char tokens)

            (or (= ch \") (= ch \'))
            (recur (inc idx) start bracket-depth paren-depth ch tokens)

            (= ch \[)
            (recur (inc idx) start (inc bracket-depth) paren-depth quote-char tokens)

            (= ch \])
            (recur (inc idx) start (max 0 (dec bracket-depth)) paren-depth quote-char tokens)

            (= ch \()
            (recur (inc idx) start bracket-depth (inc paren-depth) quote-char tokens)

            (= ch \))
            (recur (inc idx) start bracket-depth (max 0 (dec paren-depth)) quote-char tokens)

            (and (contains? #{\> \+ \~} ch) (zero? nesting-depth))
            (recur (inc idx)
                   (inc idx)
                   bracket-depth
                   paren-depth
                   quote-char
                   (-> tokens
                       (append-token (str/trim (subs s start idx)))
                       (conj (str ch))))

            (and (str/blank? (str ch)) (zero? nesting-depth))
            (recur (inc idx)
                   (inc idx)
                   bracket-depth
                   paren-depth
                   quote-char
                   (append-token tokens (str/trim (subs s start idx))))

            :else
            (recur (inc idx) start bracket-depth paren-depth quote-char tokens)))))))

(defn split-selector-list
  "Splits a comma-separated selector list (`\"sel1, sel2\"`, as in a
   top-level `sel1, sel2 { ... }` rule, or a `:not(...)`/`:is(...)`/
   `:where(...)` functional pseudo-class's own argument -- see
   `parse-simple-selector`, which reuses this exact function for that
   argument rather than reinventing comma-splitting) into its trimmed,
   non-blank parts. Tracks BOTH `[...]` bracket-depth (so a comma inside an
   attribute selector's value, e.g. `[data-label=\"a,b\"]`, doesn't split)
   and `(...)` paren-depth (so a comma inside a functional pseudo-class's
   own argument, e.g. `:not(.a, .b)` appearing inside a larger top-level
   selector list, doesn't split there either -- mirrors `selector-tokens`'s
   own paren-depth tracking, for the same reason)."
  [selector-list]
  (let [s (str selector-list)
        n (count s)]
    (loop [idx 0
           start 0
           bracket-depth 0
           paren-depth 0
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
            (recur (inc idx) start bracket-depth paren-depth nil selectors)

            quote-char
            (recur (inc idx) start bracket-depth paren-depth quote-char selectors)

            (or (= ch \") (= ch \'))
            (recur (inc idx) start bracket-depth paren-depth ch selectors)

            (= ch \[)
            (recur (inc idx) start (inc bracket-depth) paren-depth quote-char selectors)

            (= ch \])
            (recur (inc idx) start (max 0 (dec bracket-depth)) paren-depth quote-char selectors)

            (= ch \()
            (recur (inc idx) start bracket-depth (inc paren-depth) quote-char selectors)

            (= ch \))
            (recur (inc idx) start bracket-depth (max 0 (dec paren-depth)) quote-char selectors)

            (and (= ch \,) (zero? bracket-depth) (zero? paren-depth))
            (recur (inc idx) (inc idx) bracket-depth paren-depth quote-char (conj selectors (subs s start idx)))

            :else
            (recur (inc idx) start bracket-depth paren-depth quote-char selectors)))))))

(defn parse-simple-selector
  "Parses one compound-selector token (see `selector-tokens`) into its
   tag/id/classes/attrs/pseudos/pseudo-element parts, plus the
   selector-FUNCTION pseudo-classes `:not(...)`/`:is(...)`/`:where(...)`/
   `:has(...)` (see `functional-pseudo-class-pattern` -- not to be confused
   with a bare pseudo-CLASS like `:hover`/`:disabled`, matched by
   `pseudo-class-pattern` instead).

   Every `:not(...)`/`:is(...)`/`:where(...)`/`:has(...)` occurrence is
   extracted FIRST (`functional-matches`) and stripped out of the working
   text (`s`, as opposed to `raw`, the untouched original) BEFORE any
   tag/id/class/attr/pseudo extraction runs on what's left. This ordering
   is essential, not cosmetic: without it, an argument like `.special`
   inside `:not(.special)` would otherwise be picked up by the plain class
   regex as though `.special` were a class on the OUTER compound selector
   itself -- this was a real bug: `:not(.special)`/`:is(.special)` never
   matched anything at all, because they were silently misparsed into \"has
   class special AND has an unrecognized :not/:is pseudo-class\"
   (unrecognized pseudo-classes never match, see `matches-pseudo?`'s
   default `false`), which could never be true for any element.

   Each `:not()`/`:is()`/`:where()` occurrence's parenthesized argument is
   parsed as a comma-separated SELECTOR LIST via `split-selector-list` --
   the exact same comma-splitting logic top-level `sel1, sel2 { ... }` rules
   already use, so whitespace/commas inside the parens behave identically
   (`selector-tokens`/`split-selector-list` both track paren-depth for
   this) -- into a vector of parsed compound selectors, `parse-simple-selector`
   itself called recursively on each comma-separated item (`parse-group`
   below). One such vector is stored as a GROUP under :selector/not /
   :selector/is / :selector/where per occurrence (almost always zero or one
   group each, but e.g. `:not(.a):not(.b)` correctly records two groups,
   both of which must hold -- see `matches-simple?` for exactly how groups
   combine, and `simple-selector-specificity` for how they contribute to
   specificity -- :where()'s groups are matched identically to :is()'s but
   deliberately NEVER consulted for specificity, always contributing zero).

   `:has()` (:selector/has) reuses this exact same
   `functional-pseudo-class-pattern` + `split-selector-list` parsing path
   (`has-groups` below mirrors `parse-group` almost verbatim) -- its
   argument is syntactically the same comma-separated selector-list shape
   -- but each parsed item is a `{:has/selector <compound>
   :has/direct-child? bool}` map instead of a bare compound-selector map:
   `parse-has-item` first checks for an optional LEADING `>` combinator
   (`:has(> img)`, real CSS's direct-child form) and strips it before
   parsing the rest as an ordinary compound selector, recording whether it
   was present as :has/direct-child? (false, the far more common case, for
   a plain `:has(.badge)`-style item with no leading combinator at all --
   'has this ANYWHERE in the subtree'). See the namespace docstring's own
   `:has()` paragraph for why this pseudo-class needs a DOWNWARD tree walk
   -- architecturally new for this file -- and `matches-simple?`/
   `has-group-matches?` for how :selector/has is actually matched (never
   via `matches-pseudo?`, same as :selector/not/:selector/is/:selector/where
   above).

   SCOPED LIMITATION (deliberate, documented -- not a bug), shared by
   `:not()`/`:is()`/`:where()`/`:has()` alike: the argument inside the
   parens supports simple/compound selectors only (tag/id/class/
   attribute/pseudo-class combinations) -- no descendant/child/sibling
   combinators inside the parens (`:is(.a .b)` is misparsed as a single
   compound requiring both classes on the SAME element, not a descendant
   relationship; `:has()`'s own leading `>` is the one deliberate, narrow
   exception -- a single leading combinator, never a chain -- see above),
   and no NESTED functional pseudo-classes inside the argument
   (`:is(:not(.a))`, `:has(:is(.a))` -- `functional-pseudo-class-pattern`'s
   argument capture deliberately cannot contain another `(`/`)`, so a
   nested occurrence like this is never correctly parsed, though it also
   never crashes). This covers the overwhelming majority of real-world
   usage: `:not(.hidden)`, `:is(h1, h2, h3)`, `:where(.card, .panel)`,
   `:has(.badge)`, `:has(> img)` are all compound-selector-only (plus, for
   `:has()`, at most one leading `>`) in practice.

   Structural pseudo-classes (`:first-child`/`:last-child`/`:only-child`/
   `:nth-child()` and their `:first-of-type`/`:last-of-type`/
   `:nth-of-type()` same-tag counterparts, plus `:nth-child()`'s and
   `:nth-of-type()`'s own from-the-end mirrors `:nth-last-child()`/
   `:nth-last-of-type()`) need none of the above selector-list machinery --
   their bare names are already captured into `:selector/pseudos` by the
   ordinary `pseudo-class-pattern` regex just like `:hover`/`:disabled`
   always were (parens or not, that regex's match stops at the name either
   way). Only `:nth-child()`/`:nth-of-type()`/`:nth-last-child()`/
   `:nth-last-of-type()` carry an argument, and it is always a short An+B
   micro-syntax token, never a selector -- `nth-pseudo-class-pattern`
   captures that argument text alongside the pseudo name into
   `:selector/nth-args` (a `{pseudo-keyword raw-arg-string}` map), left for
   `matches-pseudo?` to parse (`parse-nth-expression`) and evaluate against
   the element's actual sibling position (forward for `:nth-child()`/
   `:nth-of-type()`, from-the-end for `:nth-last-child()`/
   `:nth-last-of-type()`) at match time (see the namespace docstring's
   structural-pseudo-classes paragraph).

   `:lang(...)` (see `lang-pseudo-class-pattern`) works exactly like
   `:nth-child()`/`:nth-of-type()` above -- its bare name is already
   captured into `:selector/pseudos`, and its raw comma-separated
   tag-list argument is captured separately into `:selector/lang-args`
   (same `{pseudo-keyword raw-arg-string}` shape as `:selector/nth-args`,
   just its own map rather than merged into that one -- mirroring how
   `:selector/not`/`:selector/is`/`:selector/where` each get their own key
   despite similar shapes), left for `matches-pseudo?` to parse
   (`parse-lang-ranges`) and evaluate against the element's computed
   language at match time (see the namespace docstring's `:lang()`
   paragraph)."
  [selector]
  (let [raw (str/trim selector)
        functional-matches (re-seq functional-pseudo-class-pattern raw)
        parse-group (fn [kind]
                      (->> functional-matches
                           (filter (fn [[_ fn-name _]] (= kind (str/lower-case fn-name))))
                           (mapv (fn [[_ _ arg]] (mapv parse-simple-selector (split-selector-list arg))))))
        parse-has-item (fn [item]
                         (let [trimmed (str/trim item)]
                           (if-let [[_ rest] (re-matches #">\s*(.*)" trimmed)]
                             {:has/selector (parse-simple-selector rest) :has/direct-child? true}
                             {:has/selector (parse-simple-selector trimmed) :has/direct-child? false})))
        has-groups (->> functional-matches
                        (filter (fn [[_ fn-name _]] (= "has" (str/lower-case fn-name))))
                        (mapv (fn [[_ _ arg]] (mapv parse-has-item (split-selector-list arg)))))
        nth-args (into {}
                       (map (fn [[_ pseudo-name arg]]
                              [(keyword (str/lower-case pseudo-name)) (str/trim arg)]))
                       (re-seq nth-pseudo-class-pattern raw))
        lang-args (into {}
                        (map (fn [[_ arg]] [:lang (str/trim arg)]))
                        (re-seq lang-pseudo-class-pattern raw))
        s (str/replace raw functional-pseudo-class-pattern "")
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
    {:selector/raw raw
     :selector/tag (when (seq tag) (keyword (str/lower-case tag)))
     :selector/id id
     :selector/classes classes
     :selector/attrs (filterv some? attrs)
     :selector/pseudos pseudos
     :selector/pseudo-element pseudo-element
     :selector/not (parse-group "not")
     :selector/is (parse-group "is")
     :selector/where (parse-group "where")
     :selector/has has-groups
     :selector/nth-args nth-args
     :selector/lang-args lang-args}))

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

(defn- simple-selector-specificity
  "Specificity contribution -- a `[id-count class/attr/pseudo-count
   tag/pseudo-element-count]` 3-vector, see `specificity` below -- of a
   single already-parsed compound/simple selector map. Factored out from
   the public `specificity` (which just sums this across a full selector's
   :selector/parts) so this exact same per-compound computation can ALSO be
   applied, recursively, to a :not()/:is() argument (itself always a bare
   compound-selector map, never a full multi-part selector -- see
   `parse-simple-selector`'s compound-only scope for these functions'
   arguments) -- without a forward reference to `specificity` itself (this
   namespace deliberately avoids `declare`-based forward references, see
   `parse-counter-amount`'s docstring for precedent; `specificity` is
   defined further down this file than `parse-simple-selector`'s callers
   need, so this self-contained helper is defined first instead).

   `:not()`/`:is()` (:selector/not / :selector/is) each contribute the
   specificity of their OWN most specific argument, PER OCCURRENCE
   (`most-specific-in-group`), summed across however many occurrences this
   compound has (almost always zero or one each, but e.g.
   `:not(.a):not(.b)` correctly contributes both) -- real CSS 4 behavior.

   `:where()` (:selector/where) is DELIBERATELY never consulted here: it
   always contributes ZERO specificity regardless of its own argument's
   specificity -- the one easy-to-get-wrong divergence from `:is()` this
   whole feature hinges on getting right, and why `:where()` still needs
   its own :selector/where key (matched identically to :is() by
   `matches-simple?`) rather than reusing :selector/is verbatim.

   `:has()` (:selector/has) contributes EXACTLY like `:not()`/`:is()` above
   -- the specificity of its own most specific argument, per occurrence,
   never `:where()`'s always-zero treatment (real CSS: `:has()` is not
   special-cased away from specificity the way `:where()` is). Each
   :selector/has group holds `{:has/selector <compound> :has/direct-child?
   bool}` maps rather than bare compound-selector maps (see
   `parse-simple-selector`), so `has-groups` first unwraps each item to its
   own :has/selector before reusing `groups-specificity` unchanged -- the
   `>` combinator recorded alongside it is irrelevant here, matching real
   CSS's general rule that combinators themselves never contribute
   specificity."
  [simple]
  (let [most-specific-in-group
        (fn [group]
          (reduce (fn [best arg]
                    (let [candidate (simple-selector-specificity arg)]
                      (if (pos? (compare candidate best)) candidate best)))
                  [0 0 0]
                  group))
        groups-specificity
        (fn [groups]
          (reduce (fn [[a b c] group]
                    (let [[ga gb gc] (most-specific-in-group group)]
                      [(+ a ga) (+ b gb) (+ c gc)]))
                  [0 0 0]
                  groups))
        has-groups (mapv (fn [group] (mapv :has/selector group)) (:selector/has simple))
        [na nb nc] (groups-specificity (:selector/not simple))
        [ia ib ic] (groups-specificity (:selector/is simple))
        [ha hb hc] (groups-specificity has-groups)]
    [(+ (if (:selector/id simple) 1 0) na ia ha)
     (+ (count (:selector/classes simple))
        (count (:selector/attrs simple))
        (count (:selector/pseudos simple))
        nb ib hb)
     (+ (if (:selector/tag simple) 1 0)
        (if (:selector/pseudo-element simple) 1 0)
        nc ic hc)]))

(defn specificity
  [selector]
  (let [parts (or (:selector/parts selector) [selector])]
    (reduce (fn [[a b c] part]
              (let [[pa pb pc] (simple-selector-specificity part)]
                [(+ a pa) (+ b pb) (+ c pc)]))
            [0 0 0]
            parts)))

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

(def ^:private color-scheme-feature-pattern
  "`(prefers-color-scheme: light|dark)` -- the one non-width media feature
   this engine recognizes, since it's ubiquitous in real-world CSS (a page's
   light/dark variants are almost always both written as ordinary `@media
   (prefers-color-scheme: dark) { ... }` blocks, ordinary declarations
   competing on specificity/order like anything else) and, unlike most other
   Level 4 media features, has a genuine, simple binary value a host can
   reasonably inject (see media-condition-matches?'s `color-scheme` arg and
   apply-cascade's `:color-scheme` opt) rather than needing real hardware/
   OS sensing this engine has no way to do (`hover`/`pointer`/`prefers-
   reduced-motion`/etc. remain unrecognized and fall through to the
   documented always-matching default below)."
  #"(?i)\(\s*prefers-color-scheme\s*:\s*(light|dark)\s*\)")

(def default-viewport-width
  "Viewport width (px) `apply-cascade` assumes for @media evaluation when the
   caller doesn't pass an explicit :viewport-width. Matches
   kotoba-lang/browser's own default viewport [800 600]."
  800)

(def default-color-scheme
  "Color scheme (\"light\"/\"dark\") `apply-cascade` assumes for
   `prefers-color-scheme` @media evaluation when the caller doesn't pass an
   explicit :color-scheme. \"light\" matches both most real OSes' own
   factory-default color scheme and (per the CSS Color Adjustment spec)
   what `prefers-color-scheme` itself resolves to when a user agent can't
   determine an actual preference at all."
  "light")

(defn media-condition-matches?
  "Evaluates a raw @media condition (as stored in :rule/media) against a
   viewport width in px and a `color-scheme` (\"light\"/\"dark\", see
   default-color-scheme). Supports `(min-width: Npx)` / `(max-width: Npx)`,
   `(prefers-color-scheme: light|dark)`, combined with `and`; a bare
   `screen`/`all` media type always matches, `print` never does; anything
   else unrecognized (`hover`, `pointer`, `prefers-reduced-motion`, `not`/
   `only` qualifiers, ...) is treated as matching (so unsupported media
   features don't silently hide rules).

   2-arity overload (`color-scheme` omitted) defaults to
   default-color-scheme, preserving the exact behavior every caller had
   before `prefers-color-scheme` support existed."
  ([condition viewport-width]
   (media-condition-matches? condition viewport-width default-color-scheme))
  ([condition viewport-width color-scheme]
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
                     (if-let [[_ scheme] (re-matches color-scheme-feature-pattern part)]
                       (= (str/lower-case scheme) (str/lower-case (str color-scheme)))
                       true)))))
             parts))))

(defn- rule-applies-to-viewport?
  [rule viewport-width color-scheme]
  (let [media (:rule/media rule)]
    (or (nil? media) (media-condition-matches? media viewport-width color-scheme))))

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

(defn- parse-number
  "A `min`/`max`/`value` attribute as a real number for range-validation
   purposes (see `constraint-invalid?`) -- unlike `parse-int` above, this
   also accepts a decimal fraction (`\"3.5\"`), since real HTML5
   `<input type=\"number\">`/`<input type=\"range\">` values/`min`/`max`
   are ordinary floating-point numbers, not just integers. Scientific
   notation (`\"1e10\"`) is a real but rare real-world form for these
   attributes, deliberately NOT supported here, matching this codebase's
   existing 'most common forms, not full spec coverage' convention (see
   `hsl()`'s hue-unit scoping in the sibling dom-gpu repo for the same
   kind of documented, honest cut)."
  [v]
  (when (some? v)
    (let [s (str/trim (str v))]
      (when (re-matches #"-?\d+(\.\d+)?" s)
        #?(:clj (Double/parseDouble s)
           :cljs (js/parseFloat s))))))

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

(defn- range-invalid?
  "Real HTML5 range-overflow/range-underflow: `type=\"number\"`/`\"range\"`
   with a non-blank, numerically-parseable `value` outside its own `min`/
   `max` attributes (either bound optional; a `min`/`max` that isn't
   itself a valid number, per `parse-number`, is simply not enforced --
   matching this file's existing degrade-don't-guess convention for
   malformed constraint attributes elsewhere, e.g. a non-numeric
   `minlength` below). Before this, NEITHER `constraint-invalid?` (here)
   NOR the separate, real form-submission-blocking `validation-reason` in
   kotoba-lang/browser's own `document_input.cljc` checked `min`/`max` at
   all -- a real, common pattern like `<input type=\"number\" min=\"1\"
   max=\"10\" value=\"15\">` was silently treated as VALID by both,
   confirmed via direct REPL reproduction through the real cascade (the
   real out-of-range value resolved `:invalid`/`:valid` CSS to the same
   `green`/valid color as an in-range one)."
  [type value attrs]
  (and (contains? #{"number" "range"} type)
       (not (str/blank? value))
       (when-let [n (parse-number value)]
         (let [min (parse-number (:min attrs))
               max (parse-number (:max attrs))]
           (or (and min (< n min))
               (and max (> n max)))))))

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
                  (> length maxlength))
             (and (= :input (:tag node))
                  (range-invalid? type value attrs))))))

(defn- constraint-valid?
  [document node]
  (and (form-control? node)
       (not (disabled-control? document node))
       (not (constraint-validation-barred-control? node))
       (not (constraint-invalid? document node))))

;; ---- structural pseudo-classes (:first-child/:last-child/:only-child/
;;      :nth-child() and their :first-of-type/:last-of-type/:nth-of-type()
;;      same-tag counterparts, plus :nth-child()'s/:nth-of-type()'s own
;;      from-the-end mirrors :nth-last-child()/:nth-last-of-type()) ----

(defn- element-children
  "All `:element`-type children of `parent-id`, in document order -- text
   nodes are ignored, matching real CSS sibling-position semantics (the
   same filtering `preceding-element-siblings`, further down this file,
   already applies to just the PRECEDING subset for the `+`/`~` sibling
   combinators -- the structural pseudo-classes below reuse that same
   traversal approach for the FULL sibling list rather than reinventing
   it)."
  [document parent-id]
  (->> (get-in document [:nodes parent-id :children] [])
       (filter #(= :element (get-in document [:nodes % :node/type])))))

(defn- structural-siblings
  "Element-type siblings of `node`, INCLUDING `node` itself, in document
   order -- `parent-node-id` finds `node`'s parent (the same document-map
   walk `disabled-by-fieldset?`/`disabled-by-optgroup?` above already use
   to find an ancestor), then `element-children` lists that parent's
   element children. When `same-tag?` is true, narrows to only siblings
   sharing `node`'s own tag name -- real CSS's `:nth-of-type`/
   `:first-of-type`/`:last-of-type` family counts only same-tag-name
   siblings, while `:nth-child`/`:first-child`/`:last-child`/`:only-child`
   count every element sibling regardless of tag (`same-tag?` false).
   Returns nil when `node` has no parent in `document` at all (a detached
   or root node) -- real CSS never matches any structural pseudo-class on
   an element with no parent."
  [document node same-tag?]
  (when-let [parent-id (parent-node-id document (:node/id node))]
    (let [siblings (element-children document parent-id)]
      (if same-tag?
        (filter #(= (:tag node) (get-in document [:nodes % :tag])) siblings)
        siblings))))

(defn- sibling-position
  "1-indexed position of `node-id` within `siblings` (a seq of node-ids in
   document order, see `structural-siblings`), or nil if not present."
  [siblings node-id]
  (some (fn [[idx id]] (when (= id node-id) (inc idx)))
        (map-indexed vector siblings)))

(def ^:private nth-an-b-pattern
  "Matches the general `An+B` form of real CSS's nth-child micro-syntax: an
   optional signed integer coefficient on `n` (`2n`, `-2n`, a bare `n`
   implicitly meaning coefficient 1, `-n` implicitly meaning coefficient
   -1, `+n`), optionally followed by a signed integer offset (`+3`, `-1` --
   real CSS also tolerates whitespace around that sign, e.g. `2n + 1`).
   `parse-nth-expression` separately handles the `even`/`odd` keywords and
   a bare signed integer (`3`, `-2`, meaning A=0) -- neither of which
   contains a literal `n`, so neither matches this pattern."
  #"(?i)([+-]?)(\d*)n(?:\s*([+-])\s*(\d+))?")

(defn- parse-nth-int
  "Parses a `[+-]?\\d+` integer token from An+B micro-syntax parsing (see
   `parse-nth-expression`) via the existing `parse-int` -- which only
   accepts a leading `-`, not `+` (real CSS's An+B syntax allows a leading
   `+` on either A or B, e.g. `+2n-1`) -- by stripping a leading `+` first
   rather than reinventing integer parsing from scratch."
  [s]
  (parse-int (str/replace s #"^\+" "")))

(defn- parse-nth-expression
  "Parses a `:nth-child()`/`:nth-of-type()` raw argument (see
   `nth-pseudo-class-pattern`) into an `[A B]` pair per real CSS's An+B
   micro-syntax (see `nth-matches?` for how A/B are then tested against a
   1-indexed sibling position):
   - `even` -> `[2 0]`, `odd` -> `[2 1]` (real CSS keywords).
   - A bare signed integer with no `n` at all (`3`, `-2`, `0`) -> `[0 B]`
     (a constant position, no periodic term).
   - The general `An+B` form (`nth-an-b-pattern`) -- an optionally signed
     coefficient on `n` (a bare `n`/`-n` implicitly means 1/-1) optionally
     followed by a signed integer offset.
   Returns nil for anything unparseable -- `nth-pseudo-matches?` treats
   that the same as 'never matches', the same conservative default this
   namespace uses everywhere else for an unparseable value, rather than
   guessing."
  [s]
  (let [s (str/trim (str s))
        lower (str/lower-case s)]
    (cond
      (= lower "even") [2 0]
      (= lower "odd") [2 1]

      (re-matches #"[+-]?\d+" s)
      [0 (parse-nth-int s)]

      :else
      (when-let [[_ a-sign a-digits b-sign b-digits] (re-matches nth-an-b-pattern s)]
        (let [a (cond
                  (seq a-digits) (parse-nth-int (str a-sign a-digits))
                  (= a-sign "-") -1
                  :else 1)
              b (if b-digits (parse-nth-int (str b-sign b-digits)) 0)]
          [a b])))))

(defn- nth-matches?
  "Whether 1-indexed position `p` satisfies the An+B pattern `[a b]` -- real
   CSS semantics: there must exist an integer n >= 0 such that
   `p = A*n + B`. When A is zero this simplifies to 'p equals B exactly'
   (a plain `:nth-child(3)`-style constant); when A is nonzero, solving for
   n gives `n = (p - B) / A`, which matches iff that division is EXACT
   (`mod`/`quot`, not float/ratio division, so this holds identically for
   negative A) AND its quotient is >= 0 -- a negative n, or an inexact
   quotient, both mean no natural number n produces this exact p."
  [p [a b]]
  (and (pos? p)
       (if (zero? a)
         (= p b)
         (let [diff (- p b)]
           (and (zero? (mod diff a))
                (>= (quot diff a) 0))))))

(defn- nth-pseudo-matches?
  "Whether `node` matches `:nth-child(arg)` (`same-tag?` false, `from-end?`
   false), `:nth-of-type(arg)` (`same-tag?` true, `from-end?` false),
   `:nth-last-child(arg)` (`same-tag?` false, `from-end?` true), or
   `:nth-last-of-type(arg)` (`same-tag?` true, `from-end?` true) -- resolves
   `node`'s 1-indexed position among the relevant sibling set
   (`structural-siblings`/`sibling-position`) and tests it against `arg`'s
   parsed An+B pattern (`parse-nth-expression`/`nth-matches?`, IDENTICAL
   micro-syntax and arithmetic for all four pseudo-classes -- only which
   index gets tested differs).

   `from-end?` reverses which END of the sibling set position 1 counts
   from: real CSS's `:nth-last-child`/`:nth-last-of-type` count backward
   from the LAST matching sibling instead of forward from the first. Since
   `structural-siblings` already returns the full relevant sibling set (all
   element siblings, or just same-tag ones) in document order, and
   `sibling-position` already gives `node`'s 1-indexed FORWARD position
   within it, the reverse (\"from-the-end\") position is just
   `total-siblings - forward-position + 1` -- simple arithmetic on numbers
   already in hand, needing no second, backward walk of the sibling list at
   all. E.g. among 5 siblings, forward position 5 (the last one) reverses to
   `5 - 5 + 1 = 1` (`:nth-last-child(1)` means 'the last child', matching
   real CSS), and forward position 1 (the first) reverses to
   `5 - 1 + 1 = 5`. `nth-matches?`'s own An+B arithmetic is then run against
   this reversed index exactly as it would against a forward one -- it has
   no idea, and doesn't need to know, which direction the index it was
   handed came from.

   False for an unparseable `arg` or a `node` with no parent, the same
   conservative defaults their own docstrings describe."
  [document node same-tag? from-end? arg]
  (boolean
   (when-let [an-b (parse-nth-expression arg)]
     (let [siblings (structural-siblings document node same-tag?)
           position (sibling-position siblings (:node/id node))]
       (and position
            (nth-matches? (if from-end?
                            (- (+ (count siblings) 1) position)
                            position)
                          an-b))))))

;; ---- :root / :empty pseudo-classes ----

(defn- child-counts-as-content?
  "Whether `document`'s node `child-id` counts as REAL CONTENT that
   disqualifies its parent from matching `:empty` (see
   `empty-pseudo-matches?`). An `:element` child ALWAYS counts (any element
   child at all -- however deeply empty THAT child may itself be --
   disqualifies its parent, since the parent isn't childless); a `:text`
   child counts only when its own data is non-empty (real CSS tests a text
   node's LENGTH, not whether it's meaningful: a WHITESPACE-ONLY text node,
   e.g. a single space or newline sitting between tags in the source, still
   has non-zero length and so DOES count as content -- `<div> </div>` does
   NOT match `:empty`, only a genuinely childless `<div></div>` does; only
   a truly zero-length text node -- about the only way one would ever exist
   is an explicit `content: \"\"` producing an empty string -- doesn't
   count. Verified against real browser `:empty` behavior, not assumed).
   Any other/unknown node type -- including when `document` is nil, e.g.
   `:empty` checked via the document-less 2-arity `matches-simple?` form --
   conservatively counts as content too, the same 'don't guess, degrade
   safely' default this namespace uses everywhere else for an unrecognized
   case."
  [document child-id]
  (let [child (get-in document [:nodes child-id])]
    (case (:node/type child)
      :element true
      :text (pos? (count (str (:text child))))
      true)))

(defn- empty-pseudo-matches?
  "Whether `node` matches `:empty` -- real CSS: an element with NO children
   AT ALL, of ANY node type. Deliberately different from the structural
   pseudo-classes above (`element-children`/`structural-siblings`), which
   ignore text nodes entirely for SIBLING-POSITION purposes -- `:empty` is
   a stricter question about the element's OWN children, where a text node
   very much counts unless it is genuinely zero-length (see
   `child-counts-as-content?`). `node`'s own `:children` (a vector of
   child node-ids populated by `kotoba.wasm.dom/append-child`) is checked
   directly -- no parent/sibling traversal needed, unlike the structural
   pseudo-classes."
  [document node]
  (not-any? #(child-counts-as-content? document %) (:children node)))

;; ---- :lang() pseudo-class ----

(defn- own-lang-attr
  "`node`'s own `lang` HTML attribute value, or nil when absent OR blank --
   an empty/whitespace-only `lang=\"\"` (real HTML/CSS's own way of saying
   'explicitly unknown language') is deliberately treated the same as no
   `lang` attribute at all, so `computed-lang` keeps walking up past it
   rather than treating `\"\"` as this element's own real (if empty)
   computed language."
  [node]
  (let [lang (get-in node [:attrs :lang])]
    (when (and (string? lang) (not (str/blank? lang)))
      lang)))

(defn- computed-lang
  "The BCP-47-ish language tag governing `node` for `:lang()` matching --
   `node`'s own `lang` HTML attribute if present and non-blank
   (`own-lang-attr`), else the NEAREST ancestor's `lang` attribute, walking
   up node-by-node via `parent-node-id` (the exact same ancestor-chain walk
   `disabled-by-fieldset?`/`disabled-by-optgroup?` already use elsewhere in
   this namespace, for a different purpose -- see the namespace docstring's
   `:lang()` paragraph). Matches real CSS language inheritance: an element
   without its own `lang` inherits its nearest ancestor's.

   Returns nil when no element from `node` up to the document root carries
   a non-blank `lang` attribute at all (an unset/unknown computed language
   never matches any `:lang()` argument, see `lang-pseudo-matches?`), and
   also unconditionally when `document` is nil -- the document-less 2-arity
   `matches-simple?` form can't walk any ancestor chain at all, the same
   restriction `:first-child` and friends already have."
  [document node]
  (when document
    (loop [current node]
      (when current
        (or (own-lang-attr current)
            (recur (get-in document [:nodes (parent-node-id document (:node/id current))])))))))

(defn- lang-tag-subtags
  [tag]
  (str/split (str/lower-case (str tag)) #"-"))

(defn- lang-range-matches-tag?
  "Whether a single already-unquoted `:lang()` comma-list item `range` (see
   `parse-lang-ranges`) matches computed language `tag` (`computed-lang`) --
   real CSS's own subtag-boundary rule: `range` matches when it is a
   case-insensitive prefix of `tag`'s `-`-separated SUBTAGS, one or more
   WHOLE subtags, never a bare string prefix -- e.g. range `\"en\"` matches
   tag `\"en\"`/`\"en-US\"` (subtags `[\"en\"]` is a whole-subtag prefix of
   `[\"en\"]`/`[\"en\" \"us\"]`) but NOT tag `\"eng\"` (`[\"en\"]` is a
   STRING prefix of `[\"eng\"]` but is not, and can never be, equal to that
   tag's one and only whole subtag, so this correctly rejects it)."
  [range tag]
  (let [range-subtags (lang-tag-subtags range)
        tag-subtags (lang-tag-subtags tag)]
    (and (<= (count range-subtags) (count tag-subtags))
         (= range-subtags (take (count range-subtags) tag-subtags)))))

(defn- unquote-lang-range
  "Strips one optional matching pair of surrounding quotes (double or
   single) off a trimmed `:lang()` comma-list item -- real CSS accepts
   either a bare identifier (`:lang(en)`) or a quoted string
   (`:lang(\"en\")`) per item. Leaves the text as-is when it isn't a
   matching quoted pair (the common bare-identifier case)."
  [s]
  (let [s (str/trim s)]
    (if (and (>= (count s) 2)
             (or (and (str/starts-with? s "\"") (str/ends-with? s "\""))
                 (and (str/starts-with? s "'") (str/ends-with? s "'"))))
      (subs s 1 (dec (count s)))
      s)))

(defn- parse-lang-ranges
  "Parses a `:lang(...)` raw argument (see `lang-pseudo-class-pattern`) into
   its comma-separated list of language-range items -- real CSS allows
   either a bare identifier or a quoted string per item, and more than one
   comma-separated item (`:lang(en, fr)`, matching EITHER -- see
   `lang-pseudo-matches?`). A blank item (a stray trailing comma, or a
   wholly-blank argument) is dropped rather than becoming a range that
   could never legitimately match anything anyway."
  [arg]
  (->> (str/split (str arg) #",")
       (map unquote-lang-range)
       (remove str/blank?)))

(defn- lang-pseudo-matches?
  "Whether `node` matches `:lang(arg)` -- real CSS: `node`'s computed
   language (`computed-lang`) matches AT LEAST ONE comma-separated range in
   `arg` (`parse-lang-ranges`/`lang-range-matches-tag?`). False when `node`
   has no computed language at all (no `lang` attribute anywhere from
   itself up to the document root, or no `document` to walk at all), or
   when `arg` parses to no usable ranges."
  [document node arg]
  (boolean
   (when-let [tag (computed-lang document node)]
     (let [ranges (parse-lang-ranges arg)]
       (some #(lang-range-matches-tag? % tag) ranges)))))

(defn- matches-pseudo?
  "Whether `node` matches bare pseudo-class `selector-pseudo`, given its raw
   argument text `arg` (nil for every pseudo-class except `:nth-child`/
   `:nth-of-type`/`:nth-last-child`/`:nth-last-of-type`/`:lang`, see
   `parse-simple-selector`'s `:selector/nth-args` / `:selector/lang-args`).

   `:first-child`/`:last-child`/`:only-child` and `:first-of-type`/
   `:last-of-type` need no argument -- they test `node`'s position
   (`structural-siblings`/`sibling-position`) against a fixed constant
   (1st, last, or 'only one at all'); `:nth-child`/`:nth-of-type`/
   `:nth-last-child`/`:nth-last-of-type` all delegate to
   `nth-pseudo-matches?`, which additionally parses+evaluates `arg`'s An+B
   micro-syntax -- the `:nth-last-*` pair simply passes `from-end?` true,
   testing the same An+B pattern against `node`'s position counted from the
   END of the relevant sibling set instead of the start (see
   `nth-pseudo-matches?`'s own docstring for exactly how that reversed index
   is computed). `:root` compares `node`'s own `:node/id` against
   `document`'s own `:root` key (the document's root element, set by
   `kotoba.wasm.dom/set-root` -- NOT the same thing as this `:root` CSS
   pseudo-class, see the namespace docstring), the same document-dependent
   restriction `:focus` already has. `:empty` delegates to
   `empty-pseudo-matches?` (real CSS's stricter 'no children of ANY node
   type' rule, not just no element children). `:lang` delegates to
   `lang-pseudo-matches?`, which parses+evaluates `arg`'s comma-separated
   language-range list against `node`'s computed language (`computed-lang`,
   walking `node`'s own ancestor chain via `document` -- the same
   document-dependent restriction `:root`/structural pseudo-classes already
   have, see the namespace docstring's `:lang()` paragraph)."
  [document node selector-pseudo arg]
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
    :first-child (= 1 (sibling-position (structural-siblings document node false) (:node/id node)))
    :last-child (let [siblings (structural-siblings document node false)]
                  (and (seq siblings)
                       (= (count siblings) (sibling-position siblings (:node/id node)))))
    :only-child (= 1 (count (structural-siblings document node false)))
    :first-of-type (= 1 (sibling-position (structural-siblings document node true) (:node/id node)))
    :last-of-type (let [siblings (structural-siblings document node true)]
                    (and (seq siblings)
                         (= (count siblings) (sibling-position siblings (:node/id node)))))
    :nth-child (nth-pseudo-matches? document node false false arg)
    :nth-of-type (nth-pseudo-matches? document node true false arg)
    :nth-last-child (nth-pseudo-matches? document node false true arg)
    :nth-last-of-type (nth-pseudo-matches? document node true true arg)
    :root (and document (= (:node/id node) (:root document)))
    :empty (empty-pseudo-matches? document node)
    :lang (lang-pseudo-matches? document node arg)
    false))

;; ---- :has() relational pseudo-class ----
;;
;; See the namespace docstring's own `:has()` paragraph for why this needs a
;; DOWNWARD tree walk -- a genuinely new traversal direction for this file,
;; where every other pseudo-class above only ever looks up an ancestor chain
;; or sideways at siblings. `descendant-node-ids` (defined much earlier in
;; this file, already backing `selected-option-id`/`radio-group-node-ids`)
;; already provides exactly that downward walk, so nothing new needed to be
;; invented there.
;;
;; `has-arg-descendant-match?`/`has-arg-child-match?`/`has-group-matches?`
;; below each take an explicit `match-fn` parameter -- always `matches-simple?`
;; in every real call site -- rather than calling `matches-simple?` by name
;; directly: `matches-simple?` itself needs to call `has-group-matches?` (see
;; its own :selector/has clause below), and this namespace deliberately never
;; uses `declare` to paper over a forward reference between two top-level
;; functions that need each other (see `parse-counter-amount`'s docstring for
;; this precedent, and `parse-calc-level`'s for another shape of the same
;; principle) -- passing the matcher in as an ordinary higher-order-function
;; argument sidesteps the forward reference entirely without inlining this
;; logic into `matches-simple?`'s own body.

(defn- has-arg-descendant-match?
  "Whether ANY of `node`'s DESCENDANTS ANYWHERE in its subtree (never `node`
   itself) matches compound selector `compound`, per `match-fn` (always
   `matches-simple?` -- see the note above this function for why it is
   passed explicitly rather than called by name) -- the plain,
   no-leading-combinator `:has(<compound-selector>)` case, e.g.
   `.card:has(.badge)`: 'has a `.badge` ANYWHERE inside it, however deeply
   nested'. Walks `node`'s full subtree via `descendant-node-ids` (the SAME
   downward node-id walk `selected-option-id`/`radio-group-node-ids`
   elsewhere in this namespace already use, for a different purpose --
   reused verbatim here rather than inventing a second one), testing each
   candidate descendant with `match-fn` and SHORT-CIRCUITING (`some`) the
   instant one matches -- real `:has()` semantics only ever need ONE
   matching descendant, never proof that every descendant was checked."
  [document node compound match-fn]
  (boolean
   (some (fn [descendant-id]
           (match-fn document (get-in document [:nodes descendant-id]) compound))
         (descendant-node-ids document (:node/id node)))))

(defn- has-arg-child-match?
  "Whether ANY of `node`'s DIRECT CHILDREN ONLY (one level, never a deeper
   descendant) matches compound selector `compound`, per `match-fn` (always
   `matches-simple?`) -- the `:has(> <compound-selector>)` case, e.g.
   `.gallery:has(> img)`: 'has an `<img>` as a DIRECT child', deliberately
   NOT matching an `<img>` nested two levels deep the way
   `has-arg-descendant-match?` above would. `node`'s own `:children` vector
   (populated by `kotoba.wasm.dom/append-child`) already gives exactly the
   immediate-child node-ids, of every node type -- `matches-simple?` itself
   filters out anything that isn't `:element` (see its own `(= :element
   (:node/type node))` first clause), so a text-node child here is simply
   never going to match any compound selector, no separate filtering needed
   in this function. Short-circuits (`some`) the same way as
   `has-arg-descendant-match?`."
  [document node compound match-fn]
  (boolean
   (some (fn [child-id]
           (match-fn document (get-in document [:nodes child-id]) compound))
         (:children node))))

(defn- has-group-matches?
  "Whether `node` matches one :has() GROUP -- one occurrence's
   comma-separated relative-selector list, each item a `{:has/selector
   <compound> :has/direct-child? bool}` map (see `parse-simple-selector`).
   Real CSS: matches if AT LEAST ONE listed relative selector matches
   (`some`) -- :has()'s own comma list is an OR, mirroring :is()/:where()'s
   identical per-group `some` semantics (see `matches-simple?`) -- dispatching
   each item to `has-arg-child-match?` (when :has/direct-child? is true, the
   `>` leading-combinator case) or `has-arg-descendant-match?` (otherwise,
   the far more common plain case).

   :has() needs `document` to walk `node`'s subtree/children at all --
   `node`'s own `:children` are only ids, resolving them to real nodes needs
   `document` -- the same document-dependent restriction :root/:lang()/the
   structural pseudo-classes already have (see the namespace docstring), so
   this unconditionally returns false when `document` is nil rather than
   attempting a documentless walk (the documentless 2-arity `matches-simple?`
   form below passes `document` nil, so :has() never matches there either)."
  [document node group match-fn]
  (boolean
   (when document
     (some (fn [{:has/keys [selector direct-child?]}]
             (if direct-child?
               (has-arg-child-match? document node selector match-fn)
               (has-arg-descendant-match? document node selector match-fn)))
           group))))

(defn- matches-simple?
  "Whether `node` matches one compound/simple selector map (see
   `parse-simple-selector`) -- tag/id/classes/attrs/pseudos as before, plus
   `:not()`/`:is()`/`:where()` (:selector/not / :selector/is /
   :selector/where, each a vector of GROUPS -- one per occurrence of that
   function on this compound, see `parse-simple-selector`'s docstring):

   - :selector/not: `node` must satisfy EVERY group (`every?`) by matching
     NONE of that group's comma-separated selectors (`not-any?`) -- e.g.
     `:not(.a, .b)` requires neither `.a` nor `.b` to match; two occurrences
     `:not(.a):not(.b)` requires both groups to independently hold, which is
     the same as requiring neither `.a` nor `.b` to match either way.
   - :selector/is / :selector/where: `node` must satisfy EVERY group by
     matching AT LEAST ONE of that group's selectors (`some`) -- :is() and
     :where() are matching-behavior IDENTICAL; they differ only in
     specificity (see `simple-selector-specificity`), never in whether they
     match.
   - :selector/has: `node` must satisfy EVERY group (`every?`, same as
     :selector/not/:selector/is above -- e.g. `:has(.a):has(.b)` requires
     BOTH occurrences to independently hold) via `has-group-matches?`, which
     itself requires at least one of that group's comma-separated relative
     selectors to match a DESCENDANT (or, for a `>`-prefixed item, a DIRECT
     CHILD) of `node` -- see the `:has()` matching section above this
     function and the namespace docstring's own `:has()` paragraph for why
     this is a fundamentally different traversal DIRECTION (downward into
     `node`'s own subtree) than every other clause in this function (upward/
     sideways via `document`).

   Each :selector/not/:selector/is/:selector/where group's selectors are
   matched via `matches-simple?` itself, recursively -- ordinary
   self-recursion (`document`/node stay the same, only the selector being
   tested changes to a simpler argument selector), not a forward reference
   to some other function defined later. :selector/has's groups are matched
   the same way, just one level removed: `has-group-matches?`/
   `has-arg-descendant-match?`/`has-arg-child-match?` take `matches-simple?`
   itself as an explicit `match-fn` argument (see the comment above those
   functions for why -- they are defined BEFORE `matches-simple?` in this
   file, so they cannot reference it by name directly without either a
   `declare` this namespace deliberately avoids, or inlining the logic here;
   passing it in as a value sidesteps both).

   Every :selector/pseudos entry is checked via `matches-pseudo?`, given
   its matching raw argument text from :selector/nth-args or
   :selector/lang-args when present (nil for every pseudo-class except
   `:nth-child`/`:nth-of-type`/`:nth-last-child`/`:nth-last-of-type`/
   `:lang` -- the two maps' keys never overlap, so `or`-ing their lookups
   together is unambiguous -- see `parse-simple-selector`). Structural
   pseudo-classes (`:first-child` and
   friends) and `:lang` alike need `document` to look up `node`'s
   parent/siblings/ancestor chain, exactly like `:focus`/`:disabled`
   already need it for other reasons -- the document-less 2-arity form
   below passes `document` nil, so those never match there either, the
   same documented restriction `matches?`'s own document-less arity
   already has. :selector/has has that identical document-less restriction
   too, for the same underlying reason (see `has-group-matches?`)."
  ([node selector]
   (matches-simple? nil node selector))
  ([document node selector]
   (and (= :element (:node/type node))
        (or (nil? (:selector/tag selector))
            (= (:selector/tag selector) (:tag node)))
        (or (nil? (:selector/id selector))
            (= (:selector/id selector) (get-in node [:attrs :id])))
        (every? (classes node) (:selector/classes selector))
        (every? (fn [{:attr/keys [name operator value case-insensitive?]}]
                  (let [actual (get-in node [:attrs name])]
                    (and (some? actual)
                         (let [actual-str (cond-> (str actual) case-insensitive? str/lower-case)
                               value (cond-> value case-insensitive? str/lower-case)]
                           (case operator
                             nil true
                             "=" (= actual-str value)
                             "~=" (contains? (set (remove str/blank?
                                                          (str/split actual-str #"\s+")))
                                            value)
                             "^=" (str/starts-with? actual-str value)
                             "$=" (str/ends-with? actual-str value)
                             "*=" (str/includes? actual-str value)
                             "|=" (or (= actual-str value)
                                     (str/starts-with? actual-str (str value "-")))
                             false)))))
                (:selector/attrs selector))
        (every? (fn [pseudo]
                  (matches-pseudo? document node pseudo
                                   (or (get (:selector/nth-args selector) pseudo)
                                       (get (:selector/lang-args selector) pseudo))))
                (:selector/pseudos selector))
        (every? (fn [group] (not-any? #(matches-simple? document node %) group))
                (:selector/not selector))
        (every? (fn [group] (some #(matches-simple? document node %) group))
                (:selector/is selector))
        (every? (fn [group] (some #(matches-simple? document node %) group))
                (:selector/where selector))
        (every? (fn [group] (has-group-matches? document node group matches-simple?))
                (:selector/has selector)))))

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

(defn- inline-style-importance
  "The set of `inline-style` property keywords the ORIGINAL raw inline
   style text marked `!important` -- see `resolve-style-for`'s own
   docstring, \"Known gap\" paragraph (now fixed) for why this has to be a
   separate attr/accessor rather than folded into `inline-style`'s own
   `{property value}` shape."
  [node]
  (or (get-in node [:attrs :style-inline-important])
      #{}))

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
   literal `<n>`/`<n>px` value for it -- or a CONSTANT `calc(...)`
   `parse-style-value` already collapsed to one, e.g. `width: calc(400px -
   100px)` (see its own docstring) -- not `auto`, a percentage, a
   non-constant `calc(...)` (mixing in `%`/`em`/any other relative unit),
   `fit-content`, or any other keyword/expression this
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

   Inline `!important` (real, common CSS -- e.g. `style=\"color: red
   !important\"`, routinely used to override a stubborn rule-based style):
   :important? above is real per-property importance, from
   `inline-style-importance` (a SEPARATE accessor reading a SEPARATE
   `:style-inline-important` attr -- a set of property keywords -- rather
   than changing `inline-style`/`:style-inline`'s own `{property value}`
   shape, since that shape has real consumers elsewhere, e.g.
   kotoba-lang/browser's `dom_bridge.cljc`, that must not be disturbed).
   `kotoba-lang/htmldom` (`htmldom.core/parse-style` + the new
   `htmldom.core/style-importance`, both called from `apply-attrs`)
   populates both attrs outside this namespace. Before this, `!important`
   wasn't just unranked here -- `htmldom.core/parse-style` didn't even
   STRIP the literal `!important` suffix from the value, so an
   `!important`-marked inline declaration's value was genuinely corrupted
   (e.g. `:color \"red !important\"`, which no downstream color parser
   recognizes as `\"red\"`) and silently fell back to that property's own
   unstyled/transparent default -- a real, visible rendering bug, not
   merely a cascade-ordering nicety.

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
         node-inline-importance (inline-style-importance node)
         inline-declarations (when (nil? pseudo-element)
                                (map-indexed (fn [idx [property value]]
                                               {:property property
                                                :value value
                                                :important? (contains? node-inline-importance property)
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
   / `(max-width:...)` rule blocks apply, and :color-scheme (default
   `default-color-scheme`, \"light\"/\"dark\") used to decide whether
   `@media (prefers-color-scheme: ...)` rule blocks apply.

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
         color-scheme (or (:color-scheme opts) default-color-scheme)
         rules (filterv #(rule-applies-to-viewport? % viewport-width color-scheme) rules)]
     (if (some :rule/container rules)
       (let [pass1-rules (filterv #(nil? (:rule/container %)) rules)
             pass1-document (run-cascade-walk document pass1-rules nil)
             container-ctx {:containers (build-containers pass1-document)
                            :parent-index (parent-index pass1-document)}]
         (run-cascade-walk document rules container-ctx))
       (run-cascade-walk document rules nil)))))

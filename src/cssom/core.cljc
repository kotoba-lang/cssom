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
     comma-separated item -- `>` (`:has(> img)`: 'has a DIRECT CHILD img',
     not just any img anywhere inside), `~` (`:has(~ p)`: has a LATER
     SIBLING p) and `+` (`:has(+ p)`: the immediately next element sibling
     is a p). `:selector/has` stores each item as `{:has/selector
     <compound> :has/combinator <kw>}` (see `parse-has-item`), and matching
     dispatches to `has-arg-child-match?` (node's immediate `:children`
     only), `has-arg-sibling-match?` (the parent's later element children)
     or `has-arg-descendant-match?` (the full subtree). The two sibling
     forms are forward-only, which is what real CSS's `~`/`+` mean.
     Deliberately OUT of scope, and unsupported (never crashes, just
     never matches that form specially): the `:scope` pseudo-class itself,
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
     `min()` / `max()` / `clamp()` are evaluated over the SAME constant
     subset and by the same parser -- a math function is a primary in the
     expression grammar, so they nest in both directions
     (`calc(min(100px, 50px) + 10px)` -> `60`,
     `min(calc(10px + 5px), 20px)` -> `15`) and `clamp(lo, v, hi)` is
     literally `max(lo, min(v, hi))` (so `clamp(90px, 5px, 300px)` -> `90`,
     not `5`). Every argument of a comparison function must carry the same
     unit, real CSS's own rule; a percentage or any other relative unit
     inside one puts the whole value outside the subset exactly as it does
     inside `calc()`, so `min(50%, 300px)` degrades to a raw string rather
     than being guessed at against a containing block this pass does not
     have. See `math-function-names` for the authority on which functions
     are in, and why `round()`/`mod()`/the trigonometric family are not.
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

(def ^:private math-function-names
  "The CSS math functions this namespace evaluates, over exactly the
   constant px-or-number subset `calc()` is bounded to (see the namespace
   docstring). `calc` is one of them rather than a separate case, because
   `min(calc(10px + 5px), 20px)` and `calc(min(100px, 50px) + 10px)` are
   both ordinary nesting once the parser has a function primary at all.

   THE authority for the set: `calc-pattern` is built from it and
   `calc-function-at` tests membership against it, so adding a function
   here is the only edit adding a function needs (`eval-calc-node` then
   decides what it MEANS).

   Not here, and out of scope for the same reason a percentage inside
   `calc()` is: `round()`/`mod()`/`rem()`/the trigonometric and
   exponential functions are either not layout-independent in the way
   this pipeline needs or rare enough that guessing would cost more than
   declining. A name outside this set is an unrecognized token, and the
   whole value degrades to a raw string unchanged."
  #{"calc" "min" "max" "clamp"})

(def ^:private calc-pattern
  "Matches a whole-value MATH FUNCTION declaration -- the ENTIRE value is
   one `math-function-names` call, case-insensitively (real CSS's function
   names are case-insensitive), with the function NAME captured (group 1)
   and its parenthesized contents captured (group 2) for `parse-calc-ast`.
   A value with anything besides the call itself (leading/trailing text,
   `calc(1px) calc(2px)`, math mixed with a keyword) does not match --
   multiple/composed math terms in one declaration are out of scope,
   matching parse-style-value's existing 'ENTIRE-value' coercion approach
   (a bare number or px length also only ever coerces when it is the WHOLE
   value). The greedy `(.*)` capture between the opening paren and the
   final `)` is safe for NESTED parens (`calc((10px + 6px) * 2)`,
   `calc(min(100px, 50px) + 10px)`) precisely because `re-matches` anchors
   both ends: greedy matching grabs everything up to the LAST `)` in the
   string, which for a well-formed whole-value call is exactly that call's
   own closing paren."
  (re-pattern (str "(?is)(" (str/join "|" (sort math-function-names)) ")\\((.*)\\)")))

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

(defn- calc-matching-paren
  "Index of the `)` closing the `(` at `open` in `s`, or nil if unbalanced.
   Used to lift a nested math function's whole argument list out as one
   token (see `calc-function-at`) rather than trying to model commas in
   the flat operator/operand token stream."
  [s open]
  (loop [i (inc open) depth 1]
    (cond
      (>= i (count s)) nil
      (= \( (nth s i)) (recur (inc i) (inc depth))
      (= \) (nth s i)) (if (= 1 depth) i (recur (inc i) (dec depth)))
      :else (recur (inc i) depth))))

(defn- calc-split-arguments
  "Splits a math function's argument text at TOP-LEVEL commas -- commas
   inside a nested call (`min(10px, max(2px, 3px))`) belong to that call,
   not to this one. Returns a vector of trimmed argument strings; a
   trailing or doubled comma yields a blank argument, which the caller
   rejects when it fails to parse."
  [s]
  (let [n (count s)]
    (loop [i 0 depth 0 start 0 out []]
      (cond
        (= i n) (conj out (str/trim (subs s start)))
        (= \( (nth s i)) (recur (inc i) (inc depth) start out)
        (= \) (nth s i)) (recur (inc i) (dec depth) start out)
        (and (= \, (nth s i)) (zero? depth))
        (recur (inc i) depth (inc i) (conj out (str/trim (subs s start i))))
        :else (recur (inc i) depth start out)))))

(defn- calc-function-at
  "Attempts to match a nested math function CALL -- one of
   `math-function-names` immediately followed by a parenthesized
   argument list -- starting at index `idx` of tokenizer input `s`.
   Returns `[token next-idx]` carrying the function name and the RAW
   argument text, or nil if `idx` isn't the start of one.

   The argument text is carried raw rather than tokenized here because a
   comma is not an operator in the expression grammar: it separates whole
   sub-expressions, each of which is parsed on its own (see
   `parse-calc-level`'s `:fncall` branch). Keeping the flat token stream
   comma-free is what lets `min()`/`max()`/`clamp()` be added without
   touching the precedence-climbing parser that handles `+`/`-`/`*`/`/`."
  [s idx]
  (when-let [name (re-find #"^[A-Za-z-]+" (subs s idx))]
    (when (contains? math-function-names (str/lower-case name))
      (let [after (+ idx (count name))]
        (when (and (< after (count s)) (= \( (nth s after)))
          (when-let [close (calc-matching-paren s after)]
            [{:calc/type :fncall
              :calc/name (str/lower-case name)
              :calc/text (subs s (inc after) close)}
             (inc close)]))))))

(defn- tokenize-calc-expr
  "Tokenizes the inside of a math function call (see calc-pattern) into a
   flat token vector -- bare operator/paren tokens (`:calc/type` one of
   `:plus`/`:minus`/`:star`/`:slash`/`:lparen`/`:rparen`), number-or-px
   operand tokens (`calc-number-at`) and nested function-call tokens
   (`calc-function-at`) -- for `parse-calc-level`, skipping whitespace.
   Returns nil if any character isn't part of one of those recognized
   tokens (e.g. a `%`/`em`/other unit anywhere in the expression, an
   unsupported function name, or any other unrecognized character) --
   signalling 'not this engine's constant subset' all the way up to
   `parse-calc`, which then degrades the whole value exactly like any
   other unparseable value in this namespace degrades (see
   parse-style-value).

   A function call is tried BEFORE a number, which matters for no input
   this engine accepts today but keeps the two matchers unambiguous: a
   function name never starts with a digit and a number never starts with
   a letter, so the order is documentation rather than a tie-break."
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
          (if-let [[call next-idx] (calc-function-at s idx)]
            (recur next-idx (conj tokens call))
            (if-let [[operand next-idx] (calc-number-at s idx)]
              (recur next-idx (conj tokens operand))
              nil)))))))

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
          ;; A nested `calc()`/`min()`/`max()`/`clamp()`: each top-level
          ;; comma-separated argument is a WHOLE expression of its own, so
          ;; each is tokenized and parsed at level 0 from scratch. nil from
          ;; any argument (an unsupported unit, a malformed sub-expression,
          ;; a blank argument from a doubled comma) makes the whole call
          ;; nil, which is this pipeline's one and only 'not our subset'
          ;; signal.
          :fncall (let [args (mapv (fn [arg]
                                     (when-let [toks (tokenize-calc-expr arg)]
                                       (when-let [[node rest-toks] (parse-calc-level toks 0)]
                                         (when (empty? rest-toks) node))))
                                   (calc-split-arguments (:calc/text t)))]
                    (when (and (seq args) (every? some? args))
                      [{:calc/op :fn :calc/name (:calc/name t) :calc/args args}
                       (rest tokens)]))
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
       (division by zero is invalid calc()).
     - `:fn` (a `calc()`/`min()`/`max()`/`clamp()` call) requires every
       argument to evaluate AND to carry the SAME unit -- real CSS's own
       rule for the comparison functions, and the same same-type
       requirement `+`/`-` already enforce (`min(10px, 2)` is invalid, not
       2). `calc()` takes exactly one argument and passes it through;
       `clamp(min, val, max)` takes exactly three and is `max(min,
       min(val, max))`, which is the spec's own definition and is what
       makes `clamp(90px, 5px, 300px)` come out 90 rather than 5.
       A wrong argument count is rejected rather than tolerated."
  [node]
  (case (:calc/op node)
    :num [(:calc/value node) (:calc/unit node)]

    :fn (let [vals (mapv eval-calc-node (:calc/args node))]
          (when (and (seq vals) (every? some? vals)
                     (apply = (map second vals)))
            (let [unit (second (first vals))
                  ns' (mapv first vals)]
              (case (:calc/name node)
                "calc" (when (= 1 (count ns')) [(first ns') unit])
                "min" [(reduce min ns') unit]
                "max" [(reduce max ns') unit]
                "clamp" (when (= 3 (count ns'))
                          (let [[lo v hi] ns']
                            [(max lo (min v hi)) unit]))
                nil))))

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
      (if-let [[_ fname inner] (re-matches calc-pattern v)]
        (or (parse-calc (if (= "calc" (str/lower-case fname))
                          inner
                          ;; A whole-value `min(...)`/`max(...)`/`clamp(...)`
                          ;; is re-wrapped as the argument of an outer
                          ;; expression so ONE parser handles both the
                          ;; top-level call and a nested one, rather than a
                          ;; second entry point that would drift from it.
                          (str fname "(" inner ")")))
            v)
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

(defn- parse-content-none-ref
  "A `content` term that is `none` or `normal` -- the two keywords meaning
   'generate no box' -- as a `{:content/none true}` marker.

   It has to be a MARKER rather than nil, and that only started mattering
   when the user-agent sheet grew a `::before` rule of its own. Returning
   nil drops the declaration at PARSE time, so it never enters the cascade
   and never beats anything; an author writing `q::before { content: none }`
   got the UA sheet's quotation marks anyway. Measured in Brave 151 on
   2026-08-05: that rule makes `<q>hello</q>` 35px wide instead of 63.
   `resolve-content-value` turns the marker back into nil, so every reader
   downstream sees exactly what it saw before -- an absent `:content`."
  [v]
  (when (contains? #{"none" "normal"} (str/lower-case (str/trim (str v))))
    {:content/none true}))

(def ^:private content-quote-keywords
  "CSS Generated Content's quote keywords, as `:content/quote` markers. The
   character each one stands for is not knowable here: it depends on the
   element's QUOTE DEPTH, which is a property of the tree and not of the
   declaration -- see `quote-marks` and `resolve-quote-content`, which is
   where the marker is finally turned into text.

   `no-open-quote`/`no-close-quote` produce no text and still move the
   depth, which is the entire reason they exist in CSS."
  {"open-quote" :open "close-quote" :close
   "no-open-quote" :no-open "no-close-quote" :no-close})

(defn- parse-content-quote-ref
  "A `content` term that is one of the four quote keywords, as a
   `{:content/quote <kw>}` marker, or nil."
  [v]
  (when-let [kw (get content-quote-keywords (str/lower-case (str/trim (str v))))]
    {:content/quote kw}))

(defn- parse-content-term
  "Parses a single content TERM -- no combination with any other term --
   into whichever of `parse-content-literal` (a quoted string literal),
   `parse-content-attr-ref` (a bare `attr(name)` call),
   `parse-content-counter-ref` (a bare `counter(name)` call) or
   `parse-content-quote-ref` (one of the four quote keywords) matches, or
   nil if none does. Used both for the common single-term case and, by
   `parse-content-value`, for each term of a multi-term composed value."
  [v]
  (let [literal (parse-content-literal v)]
    (if (some? literal)
      literal
      (or (parse-content-attr-ref v)
          (parse-content-counter-ref v)
          (parse-content-quote-ref v)
          (parse-content-none-ref v)))))

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

   5. One of the four quote keywords (`parse-content-quote-ref`) -- returns
      a `{:content/quote <kw>}` marker, because the character it stands for
      depends on the element's quote DEPTH and not on the declaration (see
      `resolve-quote-content`).
   6. `none`/`normal` (`parse-content-none-ref`) -- returns a
      `{:content/none true}` marker that `resolve-content-value` turns back
      into nil. It is a marker rather than a straight nil so that it can
      WIN the cascade over the user-agent sheet's own `content`; see that
      function for the measurement.

   Anything else this engine doesn't support (`counter()`'s two-argument
   `name, <list-style-type>` form, `url(...)`, unquoted/unmatched text,
   `attr()`'s extended `name type, fallback` syntax) returns nil rather
   than guessing -- callers treat nil exactly like `content` being absent:
   no generated-content box, no crash."
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

      ;; `line-height` is the one common property where a UNITLESS number
      ;; is not a length at all but a ratio of the element's own font-size
      ;; (`line-height: 2` = twice the font size), and it is by far the
      ;; most common way real CSS writes it. parse-style-value coerces any
      ;; bare integer to a number, which erases the very distinction
      ;; `line-height` depends on -- `2` and `2px` both arrived at
      ;; cssom.layout's resolve-line-height as the number 2, and it can
      ;; only read a number as absolute pixels. The result, confirmed by
      ;; differential testing against a real browser: `line-height: 2`
      ;; rendered as a TWO-PIXEL line height, stacking every wrapped line
      ;; almost exactly on top of the previous one, while the decimal form
      ;; `line-height: 1.5` (which survives as a string, since it is not an
      ;; integer) worked correctly all along. Keeping a unitless integer as
      ;; a STRING here routes it down resolve-line-height's existing
      ;; multiplier branch; `2px` still coerces to the number 2 and stays
      ;; absolute, which is the distinction real CSS makes.
      (and (= "line-height" k-lower) (re-matches #"\s*-?\d+\s*" (str v)))
      (str/trim (str v))

      ;; `columns` is the multi-column shorthand for `column-width ||
      ;; column-count`, and it has line-height's problem in its sharpest
      ;; form: which HALF a single value sets is decided by its unit and
      ;; nothing else (`columns: 3` is three columns, `columns: 3px` is a
      ;; 3px column width), so the coercion above -- which turns both into
      ;; the number 3 -- destroys the only thing that tells them apart. Kept
      ;; whole and raw, exactly as the two-value form (`columns: 2 100px`)
      ;; already survives, and split by cssom.layout's columns-shorthand.
      (= "columns" k-lower) (str/trim (str v))

      :else (parse-style-value v))))

(def ^:private var-ref-pattern
  ;; Defined HERE, above the shorthand expanders, rather than down in the
  ;; custom-property section with its only other user (`resolve-value`),
  ;; because `expand-box-side-shorthand` below has to recognize a whole-token
  ;; `var(...)` reference too -- see its own docstring. One pattern, two
  ;; readers: a second, separately-written copy of this regex next to the
  ;; expander is exactly the kind of drift this cycle is removing.
  ;;
  ;; The fallback capture allows the fallback text to contain ONE level of
  ;; balanced, paren-free-inside parens (`(?:[^()]|\([^()]*\))*`) -- not
  ;; just `[^()]*` -- so a fallback with a nested function call
  ;; (`rgba(...)`, `hsl(...)`, `calc(...)`, or another `var(--y, plain)`)
  ;; still matches, instead of the WHOLE `var(...)` reference failing to
  ;; match at all. Previously `var(--x, rgba(0,0,0,0.5))` -- an ordinary,
  ;; common custom-property idiom, not a contrived case -- left the
  ;; literal, unresolved text in the computed value: the character class
  ;; excluded any paren whatsoever from the fallback, so as soon as the
  ;; fallback contained ITS OWN parens, this pattern's own trailing `\)`
  ;; had nothing left to close against and the match failed outright.
  ;; Bounded, honest scope cut (consistent with this file's calc()/hsl()
  ;; cuts elsewhere): a fallback nested TWO levels deep (e.g. a `calc()`
  ;; inside an `rgba()` inside the fallback) still doesn't match -- real
  ;; recursive-descent parsing would be needed for arbitrary nesting, and
  ;; one level already covers the overwhelmingly common real-world cases.
  #"var\(\s*(--[A-Za-z_][-A-Za-z0-9_]*)\s*(?:,\s*((?:[^()]|\([^()]*\))*))?\)")

(def ^:private percentage-pattern
  "A whole value that is a single signed number plus `%`.

   MOVED UP HERE on 2026-08-06, from beside `absolute-length-pattern` in
   the relative-length section that was its only reader, for exactly the
   reason `var-ref-pattern` above gives for living here: the box-side
   shorthand expanders below now have to recognize a percentage token too,
   and a second, separately-written copy of this regex next to them is the
   drift this file keeps removing. A percentage is a value this namespace
   deliberately does NOT resolve -- it has no containing block -- but does
   carry per side, exactly as it carries `auto`."
  #"^([+-]?(?:\d+\.?\d*|\.\d+))%$")

(def ^:private border-style-keywords
  #{"none" "hidden" "dotted" "dashed" "solid" "double" "groove" "ridge" "inset" "outset"})

(def ^:private line-width-keywords
  "CSS's three named `<line-width>` values. Kept as the KEYWORD here, not
   as the pixel number, because this namespace holds SPECIFIED values and
   cssom.layout resolves them (`border-px`) -- the same split every other
   non-length value in this file already takes.

   Measured in Brave 151 on 2026-08-06:
   `border-style: solid; border-width: thin medium thick 0` reports
   `1px 3px 5px 0px`, and `border: medium solid` reports 3px on all four
   sides. `medium` is also the INITIAL value of every `border-*-width`
   longhand, which is why `border-top: solid` -- a shorthand with no width
   token at all -- is 3px rather than 0 (measured: a 300px block with
   `border-top: solid` is 19.797 tall against a 16.797 bare one)."
  #{"thin" "medium" "thick"})

(defn- border-shorthand-width-token?
  [tok]
  (boolean (or (re-matches #"-?\d+" tok)
               (re-matches #"-?\d+px" tok)
               (contains? line-width-keywords (str/lower-case tok)))))

(defn- expand-border-shorthand
  "Parses a `border` shorthand value (real CSS's own order-independent
   grammar, `<line-width> || <line-style> || <color>`) into a map of
   whichever of `:border-width`/`:border-style`/`:border-color` it
   actually specifies -- a real, legal shorthand may omit any of the
   three (e.g. `border: solid red` has no width at all). Before this,
   `border` wasn't expanded at all -- it was stored verbatim as a single
   `:border` key (e.g. `\"2px solid #00ff00\"`), which `border-ops`'s own
   `:border-width`/`:border-color` lookups never recognize, so a real,
   extremely common author pattern like `border: 2px solid red` silently
   painted NO border at all even after last cycle's fix taught this
   engine to paint borders in the first place -- confirmed via direct
   REPL reproduction (an ordinary `<div>` with the shorthand and one
   with the equivalent three longhands resolved to visibly different
   `:style/*` shapes, only the longhand form actually painting).

   Deliberately scoped to the single most common real-world token forms:
   width as a bare integer/`px` length (not the `thin`/`medium`/`thick`
   keyword forms), and color as a SINGLE whitespace-delimited token
   (a hex value or named keyword -- NOT a functional-notation color
   with internal spaces, e.g. `rgb(0 0 0 / 0.5)`'s newer space syntax,
   which this shorthand's naive whitespace tokenizing would incorrectly
   split apart; the far more common comma syntax, `rgb(0, 0, 0)`, has no
   internal spaces and tokenizes correctly as-is)."
  [v]
  (let [tokens (->> (str/split (str/trim (str v)) #"\s+") (remove str/blank?))]
    (reduce (fn [result tok]
              (let [lower (str/lower-case tok)]
                (cond
                  (and (not (contains? result :border-width))
                       (border-shorthand-width-token? tok))
                  (assoc result :border-width (parse-style-value tok))

                  (and (not (contains? result :border-style))
                       (contains? border-style-keywords lower))
                  (assoc result :border-style tok)

                  (not (contains? result :border-color))
                  (assoc result :border-color tok)

                  :else result)))
            {}
            tokens)))

(defn- box-shorthand-tokens
  "Splits a `margin`/`padding` shorthand value into its top-level values,
   paren-aware: `padding: calc(2 * 8px)` is ONE value whose own internal
   spaces must not be mistaken for side separators, and neither is
   `padding: var(--a, 10px) var(--b)` two-and-a-half. Shared by
   `expand-box-side-shorthand` (before custom-property substitution) and
   `resolve-style-map`'s own post-substitution re-slice, so the two can
   never disagree about where one side's value ends."
  [v]
  (loop [chars (seq (str/trim (str v))) depth 0 cur "" out []]
    (if-let [c (first chars)]
      (cond
        (= c \() (recur (rest chars) (inc depth) (str cur c) out)
        (= c \)) (recur (rest chars) (dec depth) (str cur c) out)
        (and (zero? depth) (re-matches #"\s" (str c)))
        (recur (rest chars) depth "" (if (str/blank? cur) out (conj out cur)))
        :else (recur (rest chars) depth (str cur c) out))
      (if (str/blank? cur) out (conj out cur)))))

(def ^:private box-side-picks
  "Real CSS's own 1-to-4 value rule as a token index per side, indexed by
   the number of values written: one value applies to all four sides, two
   are vertical/horizontal, three are top/horizontal/bottom, four are
   top/right/bottom/left clockwise. Sides are in that same clockwise order
   (top, right, bottom, left)."
  {1 [0 0 0 0]
   2 [0 1 0 1]
   3 [0 1 2 1]
   4 [0 1 2 3]})

;; ---- the CSS-wide keywords ----

(def ^:private css-wide-keywords
  "The four CSS-wide keywords (CSS Cascading and Inheritance Level 4 SS7),
   which every property accepts and which mean the same thing on all of
   them. Only `inherit` used to be handled here -- the other three were
   stored as the literal string, which no downstream reader recognizes, so
   `text-align: initial` reached `cssom.layout` as the word \"initial\" and
   `margin: revert` left the author's own `margin: 0` standing.

   Measured in Brave 151 on 2026-08-05, in the corpus's own 14px monospace
   page, on a `<p>` inside a `<div>` that declared color/font-size/
   font-weight/font-style/text-align, with and without an author rule on
   the `<p>` itself:

   | keyword   | inherited property (`color`) | non-inherited (`display`) | UA-declared (`p`'s `margin`) |
   |-----------|------------------------------|---------------------------|------------------------------|
   | `initial` | black -- NOT the parent's    | `inline` -- NOT the UA `block` | 0 -- NOT the UA 1em |
   | `unset`   | the parent's green           | `inline` (= initial)      | 0 (= initial)                |
   | `revert`  | the parent's green           | `block` (the UA value)    | 14px (the UA 1em)            |
   | `inherit` | the parent's green           | the parent's own value    | the parent's own value       |

   The `initial` row is why this cannot be done by simply forgetting the
   declaration: a `<p style=\"display: initial\">` must report `inline`,
   and dropping the declaration would leave the UA sheet's `block`
   standing. `initial` has to WRITE a value (see `initial-values`), and it
   is the only one of the four that has to."
  {"inherit" :inherit "initial" :initial "unset" :unset "revert" :revert})

(defn- css-wide-keyword
  "The CSS-wide keyword `value` names (`:inherit`/`:initial`/`:unset`/
   `:revert`), or nil. Case-insensitive and whitespace-trimmed, matching
   how every other keyword value in this file is compared."
  [value]
  (when (string? value)
    (get css-wide-keywords (str/lower-case (str/trim value)))))

(defn- expand-box-side-shorthand
  "Expands a `margin`/`padding` shorthand into its four per-side longhands
   using real CSS's own 1-to-4 value rule: one value applies to all four
   sides, two are vertical/horizontal, three are top/horizontal/bottom, and
   four are top/right/bottom/left clockwise.

   This is what makes the user-agent stylesheet expressible at all. Real UA
   rules are overwhelmingly one-axis -- `p { margin: 1em 0 }` is vertical
   only, `ul { padding-left: 40px }` is horizontal only -- and this engine's
   box model had a single UNIFORM margin/padding, so applying either would
   have moved the box in the wrong axis too. The conformance harness's
   geometry axis reported that ceiling precisely: `p` 40/54 and `li` 0/8
   failing for one shared, structural reason.

   The uniform `:margin`/`:padding` key is still emitted alongside the
   longhands (set to the first value) so every existing reader that has
   only ever known the uniform form keeps working unchanged.

   A token that is entirely a `var(--name[, fallback])` reference counts as
   expandable even though it is not a length YET. Custom properties are
   substituted much later (`style-element`, once the element's inherited
   environment is known), and NOTHING re-expanded a shorthand after that --
   so `padding: var(--pad)` with `--pad: 20px` resolved to a lone
   `:padding 20` with no per-side longhands at all, and this engine's box
   model reads only the longhands. Measured against a real browser by the
   conformance harness: `:cascade/custom-property` was one of the cases
   reporting a cascade-attributed `padding-left 0 -> 20px` mismatch.
   Expanding here, BEFORE substitution, is also what keeps the cascade
   honest: expansion has to happen at declaration time so that
   `padding: 12px; padding-left: 0` and `padding-left: 0; padding: 12px`
   still resolve differently (they do -- confirmed through the real
   pipeline). Re-expanding after the cascade had already merged would
   clobber a later, more specific longhand with the earlier shorthand.

   Each side therefore carries the var() reference verbatim and substitutes
   independently. When a custom property's own value is ITSELF a multi-value
   box shorthand (`--pad: 4px 8px`, then `padding: var(--pad)`), the four
   sides would each end up holding the whole substituted string -- so
   `resolve-style-map` re-slices exactly those keys once substitution has
   actually happened. That post-substitution step is the ONLY place a box
   shorthand is re-read after the cascade, and it is safe there precisely
   because it rewrites a value the substitution itself produced."
  [prop v]
  (let [tokens (box-shorthand-tokens v)
        n (count tokens)
        [t r b l] (map #(get tokens % (last tokens))
                       (get box-side-picks n [0 0 0 0]))]
    (when (and (pos? n) (<= n 4)
               ;; Only expand when EVERY token is a length this engine can
               ;; actually resolve, or -- for `margin` alone -- the keyword
               ;; `auto`. Outright nonsense (a var() regression guard in the
               ;; test suite passes `margin: 1px solid 3px dashed`) is left
               ;; completely untouched for the generic path to store raw,
               ;; this namespace's standing degrade-don't-guess posture.
               ;; Silently keeping the first token of an unparseable
               ;; shorthand would be a guess dressed as a value.
               ;;
               ;; A PERCENTAGE is admitted (it was not, until
               ;; cssom.layout learned to resolve one -- see its own
               ;; `percentage-box-basis`). It is not a length here and never
               ;; becomes one in this namespace: it rides through as the raw
               ;; `"10%"` string exactly as `auto` does, and cssom.layout
               ;; resolves it against the containing block's inline size at
               ;; layout time, which is the only place that size exists.
               ;; Measured in Brave 151 on 2026-08-06:
               ;; `<div style="width:300px;height:100px"><div
               ;; style="padding:10% 20%">` reports padding 30px top/bottom
               ;; and 60px left/right -- BOTH axes of the 300px width -- so
               ;; expanding a percentage shorthand per side is exactly as
               ;; well defined as expanding a px one.
               ;;
               ;; A whole-token var() reference is admitted too: it is not a
               ;; length yet, but it is a value this engine WILL resolve
               ;; (see the docstring). `margin: 1px solid 3px dashed` -- the
               ;; regression guard in the test suite -- still fails this
               ;; check on `solid`/`dashed` and stays unexpanded.
               ;;
               ;; `auto` is admitted for `margin` ONLY, and it is admitted
               ;; even though it will never be a length: it is a real,
               ;; extremely common margin value with real layout meaning
               ;; (`margin: 0 auto` centres a block), and declining to
               ;; expand it left `margin-left` reading 0 where the browser
               ;; reports 150px -- 2 of the 11 cascade-attributed values the
               ;; conformance harness's computed-style axis still charged to
               ;; this namespace, on `box/margin-auto-centers-a-block`. It
               ;; stays a raw string through the cascade, exactly as a
               ;; directly-declared `margin-left: auto` already did; layout
               ;; reads it through cssom.layout's own auto-margin?.
               ;; `padding: auto` is not valid CSS and is not admitted --
               ;; the property is checked, not just the token.
               (every? #(or (re-matches #"-?\d+(px)?" %)
                            (re-matches percentage-pattern %)
                            (re-matches calc-pattern %)
                            (re-matches var-ref-pattern %)
                            (and (= "margin" prop)
                                 (= "auto" (str/lower-case %)))
                            ;; A CSS-wide keyword is admitted as the SOLE
                            ;; token, which is the only place real CSS
                            ;; allows one: `margin: 1px revert` is invalid,
                            ;; `margin: revert` resets all four longhands.
                            ;; Expanding matters because the longhands are
                            ;; what the cascade compares -- measured, an
                            ;; unexpanded `style="margin: revert"` left the
                            ;; author's own `p.rv { margin: 0 }` longhands
                            ;; standing and reported 0 where Brave reports
                            ;; the UA's 14px (`:cascade/revert-drops-to-
                            ;; the-user-agent-value`). The keyword rides
                            ;; through to `resolve-style-for`, which is
                            ;; where all four are resolved, independently,
                            ;; against whatever each side reverts TO.
                            (and (= 1 n) (css-wide-keyword %)))
                       tokens))
      {(keyword prop) (parse-style-value (tokens 0))
       (keyword (str prop "-top")) (parse-style-value t)
       (keyword (str prop "-right")) (parse-style-value r)
       (keyword (str prop "-bottom")) (parse-style-value b)
       (keyword (str prop "-left")) (parse-style-value l)})))

(defn- expand-inset-shorthand
  "Expands the `inset` shorthand into `top`/`right`/`bottom`/`left` by the
   same 1-to-4 rule `expand-box-side-shorthand` applies to
   `margin`/`padding`.

   It is a separate function only because its longhands are BARE side
   names rather than `<prop>-<side>` ones -- `inset: 10px 20px` is
   `top: 10px; right: 20px; bottom: 10px; left: 20px`, not `inset-top`.
   Those four are the exact keys `layout-absolute-children` already reads
   (`node-style`'s `:top`/`:left`, and the `:right`/`:bottom` beside
   them), so nothing downstream needed a new concept.

   Measured before it was written, in Brave 151 over CDP:
   `<div style=\"width:300px;height:60px;position:relative\">
   <div style=\"position:absolute;inset:10px 20px\">a</div></div>`
   puts the inner box at x=20 y=10 w=260 h=40. Without this the shorthand
   was stored raw under an `:inset` key nothing reads, so the box fell
   back to its static position and shrink-to-fit width: 0,0,7x20.

   `auto` is admitted for the same reason it is admitted for `margin`: it
   is `inset`'s own INITIAL value and a real, common authored one
   (`inset: 0 auto`), and it travels as a raw string exactly as a
   directly-declared `top: auto` already does. So are PERCENTAGES, and for
   the same reason: `layout-absolute-children` already resolves a
   percentage `top`/`left` against the containing block (measured, and
   passing: `:position/absolute-percentage-offsets` and
   `:position/relative-percentage-offset`), so the four longhands this
   produces can read one. Note the basis is NOT the same as a percentage
   margin's: measured in Brave 151 on 2026-08-06, `left: 50%` of a 200x60
   containing block is 100 and `top: 50%` is 30 -- each axis against its
   OWN dimension -- while a percentage margin or padding resolves against
   the INLINE size on all four sides. Two different rules that both spell
   `50%`; see `cssom.layout/percentage-box-basis`."
  [v]
  (let [tokens (box-shorthand-tokens v)
        n (count tokens)
        [t r b l] (map #(get tokens % (last tokens))
                       (get box-side-picks n [0 0 0 0]))]
    (when (and (pos? n) (<= n 4)
               (every? #(or (re-matches #"-?\d+(px)?" %)
                            (re-matches percentage-pattern %)
                            (re-matches calc-pattern %)
                            (re-matches var-ref-pattern %)
                            (= "auto" (str/lower-case %)))
                       tokens))
      {:top (parse-style-value t)
       :right (parse-style-value r)
       :bottom (parse-style-value b)
       :left (parse-style-value l)})))

;; ---- the flow-relative (logical) box properties ----
;;
;; CSS Logical Properties and Values Level 1. `margin-inline-start` is not
;; an alias for `margin-left`: which physical side it lands on depends on
;; the element's own resolved `direction` (and `writing-mode`), so the two
;; halves live in two different places in this file. The SHORTHANDS expand
;; here, at declaration-parse time, into logical LONGHANDS -- exactly what
;; `expand-box-side-shorthand` does for the physical ones, and for exactly
;; the same reason (the cascade compares longhands). The logical-to-
;; physical rename happens later, in `resolve-style-for`, which is the
;; first point at which the element's own direction is known.
;;
;; Measured in Brave 151 over CDP on 2026-08-06, on the corpus's own 14px
;; monospace page at width 800, which is where every number below and in
;; `logical->physical-by-flow` comes from.

(def ^:private logical-side-shorthand-longhands
  "The two-value flow-relative box shorthands, and the pair of logical
   longhands each expands to.

   Note the value rule is NOT `expand-box-side-shorthand`'s 1-to-4 clockwise
   one: these take one or two values only, and two values are
   `<start> <end>` rather than `<vertical> <horizontal>`. Measured:
   `margin-inline: 20px 60px` on a 300px-wide ltr containing block puts the
   box at x=20 with w=220, i.e. 20 on the left (start) and 60 on the right
   (end); the same declaration under `direction: rtl` puts it at x=60."
  {"margin-inline" [:margin-inline-start :margin-inline-end]
   "margin-block" [:margin-block-start :margin-block-end]
   "padding-inline" [:padding-inline-start :padding-inline-end]
   "padding-block" [:padding-block-start :padding-block-end]
   "inset-inline" [:inset-inline-start :inset-inline-end]
   "inset-block" [:inset-block-start :inset-block-end]})

(defn- expand-logical-side-shorthand
  "Expands one of `logical-side-shorthand-longhands`' six shorthands into
   its `<start>`/`<end>` logical longhands, or nil when the value is
   outside the token subset this namespace resolves.

   The admitted tokens are exactly `expand-box-side-shorthand`'s -- a
   px/bare length, a percentage, a constant `calc()`, a whole-token
   `var()` reference, and `auto` for the two families where `auto` is
   legal (`margin-*` and `inset-*`, never `padding-*`; the property is
   checked, not just the token, the same way it is there). Nothing else is
   guessed at: an unexpandable shorthand is left for the generic path to
   store raw under a key nothing reads, which is this namespace's standing
   degrade-don't-guess posture and is precisely what these six did before
   this function existed."
  [prop v]
  (when-let [[start end] (get logical-side-shorthand-longhands prop)]
    (let [tokens (box-shorthand-tokens v)
          n (count tokens)
          auto-ok? (or (str/starts-with? prop "margin") (str/starts-with? prop "inset"))]
      (when (and (pos? n) (<= n 2)
                 (every? #(or (re-matches #"-?\d+(px)?" %)
                              (re-matches percentage-pattern %)
                              (re-matches calc-pattern %)
                              (re-matches var-ref-pattern %)
                              (and auto-ok? (= "auto" (str/lower-case %)))
                              (and (= 1 n) (css-wide-keyword %)))
                         tokens))
        {start (parse-style-value (tokens 0))
         end (parse-style-value (get tokens 1 (tokens 0)))}))))

(def ^:private logical-border-shorthand-sides
  "The flow-relative `border-*` shorthands, and the logical SIDES each one
   sets. `border-inline`/`border-block` set both of their axis's sides from
   one `<line-width> || <line-style> || <color>` value; the four
   single-side shorthands set one.

   Measured: `border-inline: 3px solid #000` on a 300px block reports
   `border-left-width: 3px` AND `border-right-width: 3px`, and
   `border-inline-start-width: 8px` + `border-inline-start-style: solid`
   (the sub-longhands, with no shorthand anywhere) reports
   `border-left-width: 8px` -- so the sub-longhands map too, and are in
   `logical->physical-by-flow` for that reason."
  {"border-inline-start" [:inline-start]
   "border-inline-end" [:inline-end]
   "border-block-start" [:block-start]
   "border-block-end" [:block-end]
   "border-inline" [:inline-start :inline-end]
   "border-block" [:block-start :block-end]})

(def ^:private border-sides
  "The four physical sides, in CSS's own clockwise order -- which is also
   the order `expand-box-side-shorthand`'s 1-to-4 rule fills."
  [:top :right :bottom :left])

(def ^:private border-shorthand-initials
  "What a `border`/`border-<side>` SHORTHAND writes for a component its
   value omits. A shorthand always sets all of its longhands: the omitted
   ones are reset to their INITIAL values, not left standing.

   That reset is the whole reason this map exists rather than the omitted
   components simply being dropped, and it is measured, not assumed. In
   Brave 151 on 2026-08-06:

     border-top-width: 9px; border-top: 2px solid   ->  2px   (not 9px)
     border: 5px solid;     border-top: none        ->  0px   (style none)
     border-top: solid                              ->  3px   (medium)
     border-top: 10px                               ->  0px   (style none)

   -- the third and fourth are the pair that pins it down: an omitted
   width is `medium` (3px, visible), and an omitted STYLE is `none`, which
   zeroes the used width however wide it was declared."
  {:border-width "medium" :border-style "none" :border-color "currentcolor"})

(defn- border-side-longhand-values
  "`parts` (whatever of `:border-width`/`:border-style`/`:border-color` a
   shorthand actually named) completed with `border-shorthand-initials`,
   so the caller can write all three longhands of a side."
  [parts]
  (merge border-shorthand-initials parts))

(defn- expand-logical-border-shorthand
  "Expands one of `logical-border-shorthand-sides`' six shorthands into
   per-logical-side `-width`/`-style`/`-color` longhands, reusing
   `expand-border-shorthand`'s own order-independent parse (and inheriting
   its documented token-form scope cut) rather than writing a second copy
   of it.

   The scope note this carried until 2026-08-06 -- that cssom.layout had
   no per-side border at all, so the `:border-left-width` these become was
   correct in `getComputedStyle` and invisible in layout -- is gone.
   `node-style` resolves four used widths and `border-side` reads them, so
   `border-inline-start: 5px solid #000` on a 300px block now makes it 305
   wide with its `<p>` at x=5, which is what Brave 151 renders."
  [prop v]
  (when-let [sides (get logical-border-shorthand-sides prop)]
    (let [parts (expand-border-shorthand v)]
      (when (seq parts)
        (into {}
              (for [side sides
                    [k v] (border-side-longhand-values parts)
                    :let [sub (subs (name k) (count "border-"))]]
                [(keyword (str "border-" (name side) "-" sub)) v]))))))

(defn- expand-border-side-shorthand
  "Expands one of the four per-side `border-<side>` shorthands into that
   side's three longhands, reusing `expand-border-shorthand`'s own
   order-independent parse.

   This is the declaration cssom.layout could not see at all before
   2026-08-06. Measured in Brave 151: a 300px-wide block with
   `border-top: 10px solid` is 26.797 tall with its `<p>` at y=10 and the
   full 300 wide -- a top border costs height and nothing else -- against
   16.797/y=0 here, because `border-top` fell through to the generic path
   and was stored as the raw string `\"10px solid\"`, which nothing reads.
   Not \"ten pixels on four sides\": zero on all of them.

   All three longhands are written even when the value names one of them,
   because that is what a shorthand DOES -- see
   `border-shorthand-initials` for the four browser readings that pin the
   reset down. Writing only the named components would leave an earlier
   `border-top-width: 9px` standing under a later `border-top: 2px solid`,
   which Brave resolves to 2px."
  [prop v]
  (when-let [side (get {"border-top" :top "border-right" :right
                        "border-bottom" :bottom "border-left" :left}
                       prop)]
    (when-not (css-wide-keyword v)
      (let [parts (expand-border-shorthand v)]
        (when (seq parts)
          (into {}
                (for [[k value] (border-side-longhand-values parts)
                      :let [sub (subs (name k) (count "border-"))]]
                  [(keyword (str "border-" (name side) "-" sub)) value])))))))

(defn- expand-border-box-shorthand
  "Expands `border-width`/`border-style`/`border-color` -- each of which is
   a 1-to-4 shorthand over the four sides, exactly like `margin`/`padding`
   -- into its four per-side longhands, KEEPING the uniform key beside them
   the way `expand-box-side-shorthand` does (every existing reader of the
   uniform `:border-width` goes on working unchanged).

   Measured in Brave 151 on 2026-08-06:
   `border-width: 10px 5px; border-style: solid` gives 10/5/10/5 and a
   310x36.797 box; `border-width: 10px; border-style: solid none` gives
   10/0/10/0, i.e. the STYLE shorthand carries per side too and zeroes the
   width on the sides it says `none` on.

   Unlike the length-only `expand-box-side-shorthand`, the admitted tokens
   differ per property, so each one checks its own: a width is a length or
   one of `thin`/`medium`/`thick`, a style is one of the ten
   `border-style-keywords`, and a colour is any single whitespace-free
   token (the same scope cut `expand-border-shorthand` already documents).
   A value with a token this cannot classify is left completely untouched
   for the generic path to store raw -- the same degrade-don't-guess
   posture as every other expander here."
  [prop v]
  (when-let [token-ok?
             (get {"border-width" border-shorthand-width-token?
                   "border-style" #(contains? border-style-keywords (str/lower-case %))
                   "border-color" #(not (str/blank? %))}
                  prop)]
    (let [tokens (box-shorthand-tokens v)
          n (count tokens)]
      (when (and (pos? n) (<= n 4) (every? token-ok? tokens))
        (let [[t r b l] (map #(get tokens % (last tokens))
                             (get box-side-picks n [0 0 0 0]))]
          (into {(keyword prop) (parse-style-value (tokens 0))}
                (map (fn [[side value]]
                       [(keyword (str "border-" (name side) "-"
                                      (subs prop (count "border-"))))
                        (parse-style-value value)]))
                (map vector border-sides [t r b l])))))))

(defn- expand-border-shorthand-with-sides
  "The `border` shorthand: the three uniform keys `expand-border-shorthand`
   already produced, PLUS all twelve per-side longhands.

   The twelve are not decoration. Real CSS's `border` sets every one of
   them, and the cascade is where declaration ORDER is resolved, so a
   `border` that did not write them could not overwrite an earlier
   `border-top: 10px solid`. Measured in Brave 151 on 2026-08-06:
   `border-top: 10px solid red; border: 2px solid blue` is 2px on all four
   sides (304x20.797), and the same pair in the other order is
   10/2/2/2 (304x28.797)."
  [v]
  (let [parts (expand-border-shorthand v)]
    (when (seq parts)
      (into parts
            (for [side border-sides
                  [k value] (border-side-longhand-values parts)
                  :let [sub (subs (name k) (count "border-"))]]
              [(keyword (str "border-" (name side) "-" sub)) value])))))

(defn- expand-text-shadow-shorthand
  "Parses a `text-shadow` shorthand value (real CSS's own grammar,
   `<offset-x> <offset-y> <blur-radius>? <color>?` -- offsets are
   REQUIRED, blur-radius and color are each optional, and the color MAY
   also appear before the offsets, e.g. `red 2px 2px`) into a map of
   whichever of `:text-shadow-x`/`:text-shadow-y`/`:text-shadow-blur`/
   `:text-shadow-color` it actually specifies. Deliberately scoped to the
   same common token forms `expand-border-shorthand` already commits to
   (a bare integer/`px` length per offset/blur, color as a single
   whitespace-delimited token) -- multiple comma-separated shadows (real
   CSS's own `text-shadow` list syntax) are NOT supported, a single
   shadow only, an honest scope-cut consistent with this engine's
   existing 'reasonable baseline, not full spec coverage' posture
   elsewhere (e.g. `::selection`'s fixed UA-default highlight color).

   `none` (real CSS's own explicit 'no shadow' keyword) resolves to
   `{:text-shadow-color \"none\"}` -- a real, PRESENT sentinel value
   rather than an empty map, so it can correctly WIN over an inherited
   ancestor's own real shadow the same way any other explicit override
   does (text-shadow is a genuinely inherited real CSS property, unlike
   box-shadow) -- an empty map here would instead be indistinguishable
   from `text-shadow` never having been declared on this element at all,
   silently leaving an ancestor's shadow showing through when the intent
   was to cancel it."
  [v]
  (let [v (str/trim (str v))]
    (if (or (str/blank? v) (= "none" (str/lower-case v)))
      {:text-shadow-color "none"}
      (let [tokens (->> (str/split v #"\s+") (remove str/blank?))]
        (reduce (fn [result tok]
                  (cond
                    (and (not (contains? result :text-shadow-x))
                         (border-shorthand-width-token? tok))
                    (assoc result :text-shadow-x (parse-style-value tok))

                    (and (contains? result :text-shadow-x)
                         (not (contains? result :text-shadow-y))
                         (border-shorthand-width-token? tok))
                    (assoc result :text-shadow-y (parse-style-value tok))

                    (and (contains? result :text-shadow-y)
                         (not (contains? result :text-shadow-blur))
                         (border-shorthand-width-token? tok))
                    (assoc result :text-shadow-blur (parse-style-value tok))

                    (not (contains? result :text-shadow-color))
                    (assoc result :text-shadow-color tok)

                    :else result))
                {}
                tokens)))))

(defn- expand-box-shadow-shorthand
  "Parses a `box-shadow` shorthand value (real CSS's own grammar,
   `<offset-x> <offset-y> <blur-radius>? <spread-radius>? <color>?` --
   offsets are REQUIRED, blur-radius/spread-radius/color are each
   optional, color may also appear before the offsets) into a map of
   whichever of `:box-shadow-x`/`:box-shadow-y`/`:box-shadow-blur`/
   `:box-shadow-spread`/`:box-shadow-color` it actually specifies --
   previously read NOWHERE at all, `box-shadow` stored verbatim as a
   single unrecognized `:box-shadow` key that `layout.cljc` never read,
   so a real, common author declaration like `box-shadow: 4px 4px 8px
   #000000` silently painted nothing at all, confirmed via direct REPL
   reproduction. Deliberately scoped to the same token forms
   `expand-border-shorthand`/`expand-text-shadow-shorthand` already
   commit to (a bare integer/`px` length per offset/blur/spread, color
   as a single whitespace-delimited token) -- multiple comma-separated
   shadows and the `inset` keyword are NOT supported, a single non-inset
   drop shadow only, the same 'reasonable baseline, not full spec
   coverage' posture as `text-shadow` above.

   Spread-radius (the 4th length component) WAS a real, severe bug here,
   not merely an absent feature: before this fix, a 4th length-shaped
   token (e.g. the `0` in the extremely common real-world
   `box-shadow: 0 1px 2px 0 rgba(0,0,0,0.1)` shape -- Tailwind's/
   Material's/Bootstrap's own default shadows all use exactly this
   5-token form) fell through into the `:box-shadow-color` branch,
   confirmed via direct REPL reproduction, and the REAL trailing color
   token was then silently DROPPED entirely (the `:else` branch, since
   `:box-shadow-color` was already \"taken\" by the spread token) --
   worse than a scope-cut, an author's real shadow color was corrupted
   and lost. Fixed by recognizing a 4th length-shaped token as
   `:box-shadow-spread` before falling through to color.

   Unlike `text-shadow`, `box-shadow` is NOT a real inherited CSS
   property, so `none`/blank simply resolves to an EMPTY map (no
   ancestor value to cancel -- each element's own box-shadow, or lack
   thereof, is entirely independent of its parent's), unlike text-
   shadow's own real, PRESENT `{:text-shadow-color \"none\"}` sentinel."
  [v]
  (let [v (str/trim (str v))]
    (if (or (str/blank? v) (= "none" (str/lower-case v)))
      {}
      (let [tokens (->> (str/split v #"\s+") (remove str/blank?))]
        (reduce (fn [result tok]
                  (cond
                    (and (not (contains? result :box-shadow-x))
                         (border-shorthand-width-token? tok))
                    (assoc result :box-shadow-x (parse-style-value tok))

                    (and (contains? result :box-shadow-x)
                         (not (contains? result :box-shadow-y))
                         (border-shorthand-width-token? tok))
                    (assoc result :box-shadow-y (parse-style-value tok))

                    (and (contains? result :box-shadow-y)
                         (not (contains? result :box-shadow-blur))
                         (border-shorthand-width-token? tok))
                    (assoc result :box-shadow-blur (parse-style-value tok))

                    (and (contains? result :box-shadow-blur)
                         (not (contains? result :box-shadow-spread))
                         (border-shorthand-width-token? tok))
                    (assoc result :box-shadow-spread (parse-style-value tok))

                    (not (contains? result :box-shadow-color))
                    (assoc result :box-shadow-color tok)

                    :else result))
                {}
                tokens)))))

(def ^:private outline-style-keywords
  ;; Real CSS outline-style's own keyword set -- close to but not
  ;; identical to border-style-keywords above (outline has no "hidden"
  ;; value; it uniquely allows "auto", the UA-native focus-ring style,
  ;; which this engine does not attempt to render any differently, the
  ;; same "parsed but not visually distinguished" scope-cut border-style
  ;; already has).
  #{"none" "auto" "dotted" "dashed" "solid" "double" "groove" "ridge" "inset" "outset"})

(defn- expand-outline-shorthand
  "Parses an `outline` shorthand value (real CSS's own order-independent
   grammar, `<line-width> || <line-style> || <color>`, identical shape to
   `border`) into a map of whichever of `:outline-width`/`:outline-
   style`/`:outline-color` it actually specifies -- previously read
   NOWHERE at all (a repo-wide grep for `outline` in this file's own
   source returned nothing but a handful of unrelated comments), so a
   real, common author declaration like `outline: 2px solid #ff0000`
   silently painted no outline at all, confirmed via direct REPL
   reproduction before touching source. Deliberately scoped to the exact
   same token forms `expand-border-shorthand` already commits to (a bare
   integer/`px` length for width, color as a single whitespace-delimited
   token). `outline-offset` is a real, SEPARATE (non-shorthand) CSS
   property, not part of this grammar at all -- handled generically by
   this file's own plain `parse-style-value` coercion, no special-casing
   needed here."
  [v]
  (let [tokens (->> (str/split (str/trim (str v)) #"\s+") (remove str/blank?))]
    (reduce (fn [result tok]
              (let [lower (str/lower-case tok)]
                (cond
                  (and (not (contains? result :outline-width))
                       (border-shorthand-width-token? tok))
                  (assoc result :outline-width (parse-style-value tok))

                  (and (not (contains? result :outline-style))
                       (contains? outline-style-keywords lower))
                  (assoc result :outline-style tok)

                  (not (contains? result :outline-color))
                  (assoc result :outline-color tok)

                  :else result)))
            {}
            tokens)))

(defn- flex-shorthand-number-token?
  "A `<number>` in the `flex` shorthand's grow/shrink slots. Deliberately
   NOT a length: `flex: 1` is a grow factor, `flex: 10px` is a basis, and
   the difference is exactly the presence of a unit."
  [tok]
  (boolean (re-matches #"\d+(\.\d+)?" (str tok))))

(defn- expand-flex-shorthand
  "Expands the `flex` shorthand into `:flex-grow`/`:flex-shrink`/
   `:flex-basis`, per CSS Flexible Box Layout §7.1
   (`none | [ <'flex-grow'> <'flex-shrink'>? || <'flex-basis'> ]`).

   This is the whole of `flex: 1`, which is the single most-used flex
   declaration on the real web -- and it is NOT `flex-grow: 1`. The
   one-number form resets the BASIS to zero, so the items split the
   container evenly regardless of their content; `flex-grow: 1` alone
   leaves the basis at `auto` and only distributes the leftover. Nothing
   here expanded it at all before, so `flex: 1` reached
   `cssom.layout/node-style` as an unread `:flex` key and every item kept
   its content width: measured against a real browser, two `flex: 1`
   items in a 300px row came out 7px and 70px where the browser gives
   150 and 150.

   Zero is emitted for the `0%` basis the spec names, rather than the
   percentage string: this engine resolves a flex basis as a length, and
   `0%` of any containing block is 0 either way. The distinction real CSS
   keeps (`0%` behaves as `content` when the container's main size is
   indefinite) is not modelled -- see `cssom.layout/flex-item-base-size`
   for the corresponding scope cut on the reading side.

   Anything outside this grammar is left completely unexpanded for the
   generic path to store raw, the same degrade-don't-guess posture
   `expand-box-side-shorthand` takes: a value this cannot parse must not
   become a guessed grow factor."
  [v]
  (let [tokens (->> (str/split (str/trim (str v)) #"\s+") (remove str/blank?))
        n (count tokens)
        numbers (filterv flex-shorthand-number-token? tokens)
        bases (filterv (complement flex-shorthand-number-token?) tokens)
        ;; `initial`/`auto`/`none` are the three named forms, and each is
        ;; defined by the spec as an exact grow/shrink/basis triple.
        named (when (= 1 n) (get {"none" [0 0 "auto"] "auto" [1 1 "auto"]
                                  "initial" [0 1 "auto"]}
                                 (str/lower-case (first tokens))))]
    (cond
      named {:flex-grow (named 0) :flex-shrink (named 1) :flex-basis (named 2)}

      ;; At most two numbers (grow, then shrink) and at most one basis, and
      ;; nothing left over -- `flex: 1 solid` parses as neither.
      (and (pos? n) (<= n 3) (<= (count numbers) 2) (<= (count bases) 1)
           (= n (+ (count numbers) (count bases)))
           ;; A lone token that is neither a number nor a resolvable length
           ;; (`flex: содержимое`) is not a basis this engine can use.
           (every? #(or (re-matches #"-?\d+(\.\d+)?(px)?" %)
                        (= "auto" (str/lower-case %))
                        (re-matches calc-pattern %)
                        (str/ends-with? % "%"))
                   bases))
      {:flex-grow (if (seq numbers) (parse-style-value (numbers 0)) 1)
       :flex-shrink (if (> (count numbers) 1) (parse-style-value (numbers 1)) 1)
       :flex-basis (if (seq bases)
                     (parse-style-value (bases 0))
                     ;; the `<number>`-only form's own `0%`
                     0)})))

(def ^:private font-shorthand-style-keywords
  #{"italic" "oblique"})

(def ^:private font-shorthand-weight-keywords
  #{"bold" "bolder" "lighter" "100" "200" "300" "400" "500" "600" "700" "800" "900"})

(def ^:private font-shorthand-skip-keywords
  ;; `normal` (real CSS's own reset value, legal in the style/variant/
  ;; weight/stretch slot -- deliberately a no-op here, since simply not
  ;; assigning that longhand already produces the same "unspecified,
  ;; inherit/default" outcome) plus font-variant/font-stretch keywords
  ;; this engine has no longhand support for at all (no `font-variant`/
  ;; `font-stretch` property is read anywhere in layout.cljc) -- consumed
  ;; and dropped here rather than mis-parsed as the start of font-family.
  #{"normal" "small-caps" "condensed" "expanded" "semi-condensed" "semi-expanded"
    "extra-condensed" "extra-expanded" "ultra-condensed" "ultra-expanded"})

(defn- expand-font-shorthand
  "Parses a `font` shorthand value (real CSS's own grammar, an OPTIONAL
   leading run of `<font-style>||<font-variant>||<font-weight>||
   <font-stretch>` tokens in any order, then a REQUIRED `<font-size>
   [/<line-height>]?`, then a REQUIRED `<font-family>` list taking up the
   REST of the value) into a map of whichever of `:font-style`/`:font-
   weight`/`:font-size`/`:line-height`/`:font-family` it actually
   specifies -- previously read NOWHERE at all, `font` stored verbatim as
   a single unrecognized `:font` key (confirmed via direct REPL
   reproduction: `font: italic bold 14px/1.5 sans-serif` resolved to
   `{:font \"italic bold 14px/1.5 sans-serif\"}`, none of the 5 real
   longhands this shorthand expands to -- all already fully wired in
   `cssom.layout` -- ever actually set).

   Deliberately scoped to the single most common real-world form: the
   leading run only recognizes `italic`/`oblique` (style) and `bold`/
   `bolder`/`lighter`/a bare `100`-`900` multiple-of-100 (weight) --
   `font-variant`/`font-stretch` keywords and the reset value `normal`
   are recognized just enough to be consumed WITHOUT being mis-parsed as
   the start of `font-family`, but set no longhand of their own (neither
   property exists anywhere in this engine). The 6 real CSS system-font
   keywords (`caption`/`icon`/`menu`/`message-box`/`small-caption`/
   `status-bar`) are NOT supported, the same 'reasonable baseline, not
   full spec coverage' posture as the other 4 shorthand expanders in this
   file. Once the leading run ends, the VERY NEXT token is treated
   unconditionally as `<font-size>[/<line-height>]?` (matching real CSS's
   own strict positional grammar -- no further keyword-vs-size
   disambiguation needed), and everything after THAT token is rejoined
   with a single space into `:font-family` verbatim (a multi-word quoted
   family name like `'Times New Roman'` or a comma-separated fallback
   list like `Arial, sans-serif` both survive this whitespace-normalizing
   rejoin losslessly, unlike `expand-border-shorthand`'s own color-token
   limitation, since font-family is never classified token-by-token
   here).

   Both `<font-size>` and `<line-height>` (when present) are run through
   `parse-style-value`, exactly like a plain, non-shorthand `font-size`/
   `line-height` declaration would be -- so e.g. a bare `1.5` line-height
   survives as the same raw multiplier STRING `cssom.layout/resolve-line-
   height` already knows how to interpret, not a special shorthand-only
   representation. `font-style`/`font-weight` are also run through
   `parse-style-value` for the identical reason (a numeric weight like
   `700` needs to become the same coerced integer a plain `font-weight:
   700` declaration already produces elsewhere).

   Real CSS treats a `font` shorthand missing its mandatory `<font-size>`
   or `<font-family>` as an ENTIRELY invalid declaration (dropped
   wholesale, not partially applied) -- mirrored here by returning an
   empty map rather than guessing, this file's own established degrade-
   don't-guess convention for every other malformed/incomplete value."
  [v]
  (let [tokens (->> (str/split (str/trim (str v)) #"\s+") (remove str/blank?))
        [leading remaining] (split-with (fn [tok]
                                           (let [lower (str/lower-case tok)]
                                             (or (contains? font-shorthand-style-keywords lower)
                                                 (contains? font-shorthand-weight-keywords lower)
                                                 (contains? font-shorthand-skip-keywords lower))))
                                         tokens)]
    (if (or (empty? remaining) (empty? (rest remaining)))
      {}
      (let [size-token (first remaining)
            family (str/join " " (rest remaining))
            style-tok (some #(when (contains? font-shorthand-style-keywords (str/lower-case %)) %) leading)
            weight-tok (some #(when (contains? font-shorthand-weight-keywords (str/lower-case %)) %) leading)
            [size-part lh-part] (str/split size-token #"/" 2)]
        (cond-> {:font-size (parse-style-value size-part)
                 :font-family family}
          style-tok (assoc :font-style (parse-style-value style-tok))
          weight-tok (assoc :font-weight (parse-style-value weight-tok))
          lh-part (assoc :line-height (parse-style-value lh-part)))))))

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
       (mapcat (fn [decl]
                 (let [[k v] (map str/trim (str/split decl #":" 2))]
                   (if (and (seq k) (seq v))
                     (let [important? (boolean (re-find #"(?i)!important\s*$" v))
                           value (str/replace v #"(?i)\s*!important\s*$" "")]
                       (cond
                         (and (contains? #{"margin" "padding"} (str/lower-case k))
                              (some? (expand-box-side-shorthand (str/lower-case k) value)))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-box-side-shorthand (str/lower-case k) value))

                         (and (= "inset" (str/lower-case k))
                              (some? (expand-inset-shorthand value)))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-inset-shorthand value))

                         ;; The six flow-relative box shorthands. Expanded
                         ;; to LOGICAL longhands here (not physical ones):
                         ;; which physical side each lands on is not known
                         ;; until the element's own `direction` is
                         ;; resolved, which happens in `resolve-style-for`.
                         (some? (expand-logical-side-shorthand (str/lower-case k) value))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-logical-side-shorthand (str/lower-case k) value))

                         (some? (expand-logical-border-shorthand (str/lower-case k) value))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-logical-border-shorthand (str/lower-case k) value))

                         (some? (expand-border-side-shorthand (str/lower-case k) value))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-border-side-shorthand (str/lower-case k) value))

                         (some? (expand-border-box-shorthand (str/lower-case k) value))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-border-box-shorthand (str/lower-case k) value))

                         (and (= "border" (str/lower-case k))
                              (some? (expand-border-shorthand-with-sides value)))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-border-shorthand-with-sides value))

                         (= "text-shadow" (str/lower-case k))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-text-shadow-shorthand value))

                         (= "box-shadow" (str/lower-case k))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-box-shadow-shorthand value))

                         (= "outline" (str/lower-case k))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-outline-shorthand value))

                         (= "font" (str/lower-case k))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-font-shorthand value))

                         (and (= "flex" (str/lower-case k))
                              (some? (expand-flex-shorthand value)))
                         (map (fn [[longhand longhand-value]]
                                [longhand {:value longhand-value :important? important?}])
                              (expand-flex-shorthand value))

                         :else
                         (let [parsed (parse-property-value k value)]
                           (if (some? parsed)
                             [[(keyword k) {:value parsed :important? important?}]]
                             []))))
                     []))))
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

   The argument is captured tolerating ONE level of nested parens
   (`(?:[^()]|\\([^()]*\\))*`, the same bounded-nesting convention this
   namespace's own `calc()`/`var()` fallback parsing already uses) --
   previously captured as a strict `[^()]*` (deliberately unable to
   contain ANY `(`/`)` at all), which silently broke every occurrence
   whose argument contained a parenthesized pseudo-class like
   `:nth-child()`/`:nth-of-type()`/`:lang()` -- a real, severe, and
   completely silent bug, not merely a documented scope-cut: confirmed
   via direct REPL reproduction before this fix that `li:not(:nth-child(1))`
   matched ZERO elements at all (not just the wrong ones), for every real
   list item that should have matched. Root cause: the old strict pattern
   simply failed to match the WHOLE `:not(...)` occurrence whenever its
   argument had its own parens, so `functional-matches` never captured it,
   the un-stripped literal `:not`/`:is`/`:where`/`:has` text then got
   mis-picked-up by the plain, argument-less `pseudo-class-pattern`
   instead (the exact same 'unrecognized bare pseudo-class' bug class this
   docstring's own paragraph above already documents for the
   `.special`-inside-`:not(.special)` case), and `matches-pseudo?`'s
   default `false` for an unrecognized name meant the ENTIRE compound
   selector could never match anything, silently, for one of real CSS's
   most common idioms (`:not(:nth-child(1))`, `:is(:nth-child(odd), .x)`).
   One level of nesting also incidentally starts correctly handling the
   previously-documented `:is(:not(.a))` shape too, confirmed via direct
   REPL check with no regression on any already-working case (`:not(.a,
   .b)`, `:is(h1, h2, h3)`, `:has(.badge)`, `:has(> img)` all still behave
   identically). Genuinely deeper nesting (two levels, e.g.
   `:is(:not(:nth-child(1)))`) remains out of scope -- the same bounded-
   nesting cut this file's own `calc()`/`var()` fallback already commits
   to, not a new or different limitation -- and an attribute selector's
   own `[...]` inside the argument is unaffected either way
   (`:not([hidden])`, `:has([hidden])`) since square brackets never
   conflict with this pattern's parens."
  #"(?i):(not|is|where|has)\(((?:[^()]|\([^()]*\))*)\)")

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
   :has/combinator <kw>}` map instead of a bare compound-selector map:
   `parse-has-item` first checks for an optional LEADING `>`/`~`/`+`
   combinator (`:has(> img)`, `:has(~ p)`, `:has(+ p)`) and strips it
   before parsing the rest as an ordinary compound selector, recording
   which one it was as :has/combinator (`:descendant`, the far more common
   case, for a plain `:has(.badge)`-style item with no leading combinator
   at all -- 'has this ANYWHERE in the subtree'). :has/direct-child? is
   still emitted alongside it for readers that only knew the `>` form.
   See the namespace docstring's own
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
   exception -- a single leading combinator, never a chain -- see above).
   A single level of nesting inside the argument IS supported (a
   parenthesized pseudo-class like `:not(:nth-child(1))`/
   `:is(:nth-child(odd), .x)`, or a once-nested functional pseudo-class
   like `:is(:not(.a))` -- see `functional-pseudo-class-pattern`'s own
   docstring for the real, severe bug this fixed: previously EVERY
   occurrence with any parens in its argument silently matched nothing at
   all); genuinely DEEPER nesting (two levels, e.g.
   `:is(:not(:nth-child(1)))`) remains out of scope, the same bounded-
   nesting cut this file's own `calc()`/`var()` fallback parsing already
   commits to elsewhere. This covers the overwhelming majority of real-
   world usage: `:not(.hidden)`, `:is(h1, h2, h3)`, `:where(.card,
   .panel)`, `:has(.badge)`, `:has(> img)`, and now also
   `:not(:nth-child(2n+1))`-shaped forms, are all compound-selector-only
   (plus, for `:has()`, at most one leading `>`) in practice.

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
                         (let [trimmed (str/trim item)
                               [_ combinator rest] (re-matches #"([>+~])\s*(.*)" trimmed)]
                           {:has/selector (parse-simple-selector (or rest trimmed))
                            :has/combinator (case combinator
                                              ">" :child
                                              "+" :next-sibling
                                              "~" :following-sibling
                                              nil :descendant)
                            ;; kept for readers that only ever knew the
                            ;; child form; :has/combinator is the value
                            ;; `has-group-matches?` actually dispatches on.
                            :has/direct-child? (= ">" combinator)}))
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

(defn- node-by-dom-id
  [document dom-id]
  (some (fn [[node-id candidate]]
          (when (= dom-id (get-in candidate [:attrs :id]))
            node-id))
        (:nodes document)))

(defn- ancestor-form-id
  "The owning <form>'s node-id for node-id: an explicit `form=\"...\"`
   attribute pointing at a real <form> by id, else the nearest ancestor
   <form>. Mirrors browser.document-input's own already-correct
   ancestor-form-id (https://html.spec.whatwg.org/multipage/forms.html#form-owner)."
  [document node-id]
  (or (when-let [form-dom-id (get-in document [:nodes node-id :attrs :form])]
        (let [form-id (node-by-dom-id document form-dom-id)]
          (when (= :form (get-in document [:nodes form-id :tag]))
            form-id)))
      (loop [parent-id (parent-node-id document node-id)]
        (when parent-id
          (if (= :form (get-in document [:nodes parent-id :tag]))
            parent-id
            (recur (parent-node-id document parent-id)))))))

(defn- radio-group-node-ids
  "The HTML radio button group containing node: same (non-empty) `name`
   *and* same owner form (https://html.spec.whatwg.org/multipage/input.html#radio-button-group).
   Previously compared only the literal :form ATTRIBUTE string (both
   sides defaulting to \"\" when absent) instead of real form ownership
   -- so two same-named radios in two DIFFERENT <form> elements, neither
   carrying an explicit form= attribute (the overwhelmingly common
   authoring shape: relying on the ancestor <form>, not the form=
   attribute), were incorrectly treated as ONE shared group, corrupting
   :required/:invalid constraint validation across unrelated forms.
   browser.document-input's own radio-group-node-ids already gets this
   right via ancestor-form-id; this mirrors that fix."
  [document node]
  (let [node-id (:node/id node)
        group-name (get-in node [:attrs :name])
        named? (not (str/blank? (str group-name)))
        group-form-id (ancestor-form-id document node-id)]
    (->> (:nodes document)
         (keep (fn [[id candidate]]
                 (when (and (= :input (:tag candidate))
                            (= "radio" (str/lower-case (str (or (get-in candidate [:attrs :type]) "text"))))
                            (if named?
                              (and (= group-name (get-in candidate [:attrs :name]))
                                   (= group-form-id (ancestor-form-id document id)))
                              (= id node-id)))
                   id)))
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

(def ^:private step-mismatch-tolerance
  "Real HTML5's own step-validity algorithm uses precise decimal
   arithmetic to avoid floating-point rounding artifacts (e.g. so
   `step=\"0.1\"` cleanly accepts `0.1`/`0.2`/`0.3`, which naive `double`
   division does not: `0.3 / 0.1` is `2.9999999999999996`, not `3.0`).
   This file's own `parse-number` already only accepts plain decimal
   strings (no scientific notation, an existing honest scope-cut), so a
   small, fixed epsilon tolerance -- rather than a full precise-decimal
   reimplementation -- is a pragmatic, documented simplification
   consistent with this file's other 'reasonable baseline, not full spec
   coverage' checks."
  1e-9)

(defn- step-invalid?
  "Real HTML5 step-mismatch: `type=\"number\"`/`\"range\"` (the same two
   types `range-invalid?` above already scopes to) with a non-blank,
   numerically-parseable `value` that isn't `step-base + n*step` for some
   integer `n` -- previously read NOWHERE at all (an honest, documented
   scope-cut in kotoba-lang/browser's own JS-facing
   `__kotobaValidityState`: `stepMismatch: false` hardcoded).

   Real HTML5's own default `step` for both types is `1` (NOT 'no
   constraint' -- a genuinely common surprise: `<input type=\"number\"
   value=\"3.5\">` with no `step` attribute at all is real HTML5
   INVALID), and a real, legal `step=\"any\"` disables the check
   entirely. A `step` attribute present but not itself a valid positive
   number (per `parse-number`) falls back to that same default of `1`
   -- deliberately DIFFERENT from `min`/`max`'s own degrade-don't-guess
   convention (an unparseable bound there has nothing sensible to fall
   back to and so is simply dropped), because real HTML5 step genuinely
   HAS a defined default value to fall back to, unlike min/max which
   don't. `step-base` is `min` when present and parseable, else `0`,
   matching real HTML5's own step-base algorithm for these two types
   (neither has a `list`/default-option step base concept to consider)."
  [type value attrs]
  (and (contains? #{"number" "range"} type)
       (not (str/blank? value))
       (when-let [n (parse-number value)]
         (let [raw-step (:step attrs)]
           (when-not (and raw-step (= "any" (str/lower-case (str raw-step))))
             (let [step (let [parsed (parse-number raw-step)]
                          (if (and parsed (pos? parsed)) parsed 1.0))
                   base (or (parse-number (:min attrs)) 0.0)
                   steps (/ (- n base) step)
                   ;; portable across CLJ/CLJS (no Math/round -- `mod`'s
                   ;; own floored-division semantics already put the
                   ;; fractional remainder in [0, 1) regardless of sign,
                   ;; so "close to 0 OR close to 1" means "close to some
                   ;; integer" either way).
                   frac (mod steps 1)]
               (and (> frac step-mismatch-tolerance)
                    (< frac (- 1 step-mismatch-tolerance)))))))))

(defn- pattern-invalid?
  "Real HTML5 pattern-mismatch: a non-blank value on a text/search/url/
   tel/email/password `<input>` (real HTML5's own restriction on which
   control types `pattern` even applies to -- the caller's own
   `(= :input (:tag node))` guard, the same shape `range-invalid?` above
   already relies on, excludes `<textarea>` even though an untyped
   `<textarea>` also resolves this file's own `type` default to
   `\"text\"`) that doesn't fully match its own `pattern` attribute,
   implicitly anchored `^(?:...)$` the same way `re-matches` already
   anchors any pattern here. A malformed `pattern` (not a legal regex) is
   simply NOT enforced -- matching this file's existing degrade-don't-
   guess convention for malformed constraint attributes elsewhere (mirrors
   kotoba-lang/browser's own identically-scoped compile-pattern/pattern-
   mismatch check in document_input.cljc, fixed together for the same
   reason `range-invalid?` above was)."
  [type value attrs]
  (and (contains? #{"text" "search" "url" "tel" "email" "password"} type)
       (not (str/blank? value))
       (when-let [pattern (:pattern attrs)]
         (when-let [re #?(:clj (try (re-pattern pattern) (catch Exception _ nil))
                          :cljs (try (re-pattern pattern) (catch :default _ nil)))]
           (not (re-matches re value))))))

(def ^:private email-format-pattern
  "The real WHATWG HTML5 email-format regex (verbatim -- the same one
   real browsers use for `type=\"email\"` `typeMismatch` checking), not a
   hand-simplified approximation. Deliberately still an honest scope-cut
   in one specific way: the `multiple` attribute (a comma-separated list
   of addresses, each individually checked) is NOT supported -- a single
   address only, matching this file's own established 'single X only'
   posture elsewhere (e.g. `text-shadow`'s own single-shadow scope-cut)."
  #"[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*")

(def ^:private url-format-pattern
  "A deliberately simplified `type=\"url\"` format check -- real HTML5
   requires successfully parsing via the full WHATWG URL parser (which
   accepts almost anything with a scheme); this engine has no such parser,
   so this instead requires the single most common practical shape, an
   absolute URL with a real scheme (`scheme://...`) -- an honest,
   documented scope-cut consistent with this file's other 'reasonable
   baseline, not full spec coverage' checks."
  #"[a-zA-Z][a-zA-Z0-9+.-]*://\S+")

(defn- type-mismatch?
  "Real HTML5 `typeMismatch`: a non-blank value on `type=\"email\"`/
   `\"url\"` that doesn't match that type's own format -- previously read
   NOWHERE at all. Mirrors `range-invalid?`/`pattern-invalid?`'s own
   shape -- deliberately NOT enforced for a blank value (that's
   `required`'s concern, not `typeMismatch`'s). Fixed together with the
   identical gap in kotoba-lang/browser's own document_input.cljc
   `type-mismatch?` and quickjs_wasm.cljc's JS-facing
   `__kotobaValidationReason`."
  [type value]
  (and (not (str/blank? value))
       (case type
         "email" (not (re-matches email-format-pattern value))
         "url" (not (re-matches url-format-pattern value))
         false)))

(defn- length-constrained-control?
  "Real HTML5's own restriction: minlength/maxlength apply ONLY to
   text/search/url/tel/email/password <input>s and to <textarea>
   (unlike `pattern-invalid?` above, which excludes <textarea>) -- NOT
   to number/range/color/date/datetime-local/month/week/time, and NOT
   to <select>/checkbox/radio either. Previously `constraint-invalid?`
   had no type guard on minlength/maxlength at all, so a real, common
   shape like <input type=\"number\" value=\"12345\" maxlength=\"3\">
   spuriously matched :invalid instead of :valid, confirmed via direct
   REPL reproduction through the real cascade. Fixed together with the
   identical gap in kotoba-lang/browser's own document_input.cljc
   validation-reason and quickjs_wasm.cljc's JS-facing
   __kotobaValidationReason."
  [node type]
  (or (= :textarea (:tag node))
      (and (= :input (:tag node))
           (contains? #{"text" "search" "url" "tel" "email" "password"} type))))

(defn- constraint-invalid?
  [document node]
  (let [attrs (:attrs node)
        type (str/lower-case (str (or (:type attrs) "text")))
        value (control-value document node)
        length (count value)
        minlength (parse-int (:minlength attrs))
        maxlength (parse-int (:maxlength attrs))
        length-constrained? (length-constrained-control? node type)]
    (and (not (constraint-validation-barred-control? node))
         (or (truthy-attr? (:invalid attrs))
             (and (truthy-attr? (:required attrs))
                  (cond
                    (and (= :input (:tag node)) (= "checkbox" type)) (not (truthy-attr? (:checked attrs)))
                    (and (= :input (:tag node)) (= "radio" type)) (not (radio-required-satisfied? document node))
                    :else (str/blank? value)))
             (and minlength
                  length-constrained?
                  (pos? length)
                  (< length minlength))
             (and maxlength
                  length-constrained?
                  (> length maxlength))
             (and (= :input (:tag node))
                  (range-invalid? type value attrs))
             (and (= :input (:tag node))
                  (pattern-invalid? type value attrs))
             (and (= :input (:tag node))
                  (type-mismatch? type value))
             (and (= :input (:tag node))
                  (step-invalid? type value attrs))))))

(defn- constraint-valid?
  [document node]
  (and (form-control? node)
       (not (disabled-control? document node))
       (not (constraint-validation-barred-control? node))
       (not (constraint-invalid? document node))))

(defn- range-limited-control?
  "Real HTML5 \"has range limitations\": for the number/range input types
   `range-invalid?` already scopes this engine's range validation to, true
   whenever a valid (per `parse-number`, matching `range-invalid?`'s own
   degrade-don't-guess convention for malformed bounds) `min` or `max`
   attribute is present. `:in-range`/`:out-of-range` only ever apply to a
   control that has range limitations at all -- a plain, unbounded
   `type=\"number\"` with neither `min` nor `max` matches NEITHER
   pseudo-class, not `:in-range` by default."
  [type attrs]
  (and (contains? #{"number" "range"} type)
       (boolean (or (parse-number (:min attrs)) (parse-number (:max attrs))))))

(defn- in-range?
  [document node]
  (let [attrs (:attrs node)
        type (str/lower-case (str (or (:type attrs) "text")))
        value (control-value document node)]
    (and (= :input (:tag node))
         (form-control? node)
         (not (disabled-control? document node))
         (not (constraint-validation-barred-control? node))
         (range-limited-control? type attrs)
         (not (range-invalid? type value attrs)))))

(defn- out-of-range?
  [document node]
  (let [attrs (:attrs node)
        type (str/lower-case (str (or (:type attrs) "text")))
        value (control-value document node)]
    (and (= :input (:tag node))
         (form-control? node)
         (not (disabled-control? document node))
         (not (constraint-validation-barred-control? node))
         (range-limited-control? type attrs)
         (range-invalid? type value attrs))))

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

(def ^:private nth-of-pattern
  "Splits an `:nth-child()` argument at its `of` keyword: group 1 is the
   An+B text before it, group 2 the selector list after. `of` is matched as
   a whole word so an An+B expression can never contain it -- that
   micro-syntax is digits, signs and the letter `n`, and `even`/`odd` are
   whole tokens that `of` cannot be a suffix of."
  #"(?i)^(.*?)\s+of\s+(.+)$")

(defn- nth-of-clause
  "`[an-b-text of-selector-text]` for an `:nth-child()` argument, with
   `of-selector-text` nil when there is no `of` clause."
  [arg]
  (if-let [[_ an-b of-text] (re-matches nth-of-pattern (str/trim (str arg)))]
    [an-b of-text]
    [arg nil]))

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

   The `of <selector-list>` clause (`nth-of-clause`, CSS Selectors 4's
   `:nth-child(2n+1 of .m)`) narrows the sibling set to the siblings that
   match that list, and additionally requires `node` itself to match it --
   both, which is what makes it different from simply writing
   `.m:nth-child(2n+1)`. Measured in Brave 151 on 2026-08-05, on
   `<p class=m>1</p><p>2</p><p class=m>3</p><p>4</p>` with
   `p:nth-child(2n+1 of .m)`: only the FIRST `.m` is selected. It is the
   first `.m` among `.m`s (index 1) and would be the third among all
   children, so an engine that ignores the clause bolds both `.m`s and one
   that treats it as a plain compound bolds neither.

   `of` is valid on `:nth-child`/`:nth-last-child` only, so it is read only
   when `same-tag?` is false -- `:nth-of-type(2n of .m)` is not valid CSS
   and stays unparseable, i.e. matches nothing.

   `match-fn` is always `matches-simple?`, passed in rather than called by
   name for the same forward-reference reason `has-group-matches?` states
   for itself.

   SCOPE, stated because it is measurable: the clause's own selector goes
   through `parse-simple-selector`, so it is compound-only -- the same cut
   `:not()`/`:is()`/`:has()` already commit to -- and a selector containing
   parens (`of :not(.x)`) is not even captured, because
   `nth-pseudo-class-pattern`'s argument is a non-nested `[^()]*`. And the
   clause does NOT contribute to specificity: real CSS adds the most
   specific selector in the list, so `p:nth-child(2n+1 of .m)` should
   score (0,2,1) and scores (0,1,1) here. That only shows against a
   competing rule of exactly the intervening specificity.

   False for an unparseable `arg` or a `node` with no parent, the same
   conservative defaults their own docstrings describe."
  [document node same-tag? from-end? arg match-fn]
  (boolean
   (let [[an-b-text of-text] (if same-tag? [arg nil] (nth-of-clause arg))
         of-selectors (when of-text (mapv parse-simple-selector (split-selector-list of-text)))]
     (when-let [an-b (parse-nth-expression an-b-text)]
       (let [matches-of? (fn [n] (some #(match-fn document n %) of-selectors))
             siblings (cond->> (structural-siblings document node same-tag?)
                        of-selectors
                        (filter #(matches-of? (get-in document [:nodes %]))))
             position (sibling-position siblings (:node/id node))]
         (and position
              (or (nil? of-selectors) (matches-of? node))
              (nth-matches? (if from-end?
                              (- (+ (count siblings) 1) position)
                              position)
                            an-b)))))))

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
   have, see the namespace docstring's `:lang()` paragraph). `:focus-within`
   matches `node` itself OR any descendant currently holding `document`'s
   `:focus`, reusing `descendant-or-self?` (already established by `:has()`
   below for its own downward tree walk) rather than an upward ancestor
   walk -- the one case in this function that asks 'is X somewhere in MY
   subtree' instead of 'is X an ancestor of me'. `:in-range`/`:out-of-range`
   delegate to `in-range?`/`out-of-range?`, which reuse `range-invalid?`
   (already computing exactly real HTML5 range-overflow/underflow for
   `constraint-invalid?`) -- a control with no `min`/`max` at all has no
   'range limitations' per spec and matches NEITHER pseudo-class.

   `match-fn` is always `matches-simple?`, threaded through purely so
   `nth-pseudo-matches?` can evaluate an `:nth-child(... of <selector>)`
   clause -- the same explicit higher-order-function argument the `:has()`
   family below uses, and for the same reason: `matches-simple?` calls this
   function, so this function cannot name it."
  [document node selector-pseudo arg match-fn]
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
    :in-range (in-range? document node)
    :out-of-range (out-of-range? document node)
    :focus (and document (= (:node/id node) (:focus document)))
    :focus-within (and document (:focus document)
                        (descendant-or-self? document (:node/id node) (:focus document)))
    :first-child (= 1 (sibling-position (structural-siblings document node false) (:node/id node)))
    :last-child (let [siblings (structural-siblings document node false)]
                  (and (seq siblings)
                       (= (count siblings) (sibling-position siblings (:node/id node)))))
    :only-child (= 1 (count (structural-siblings document node false)))
    :first-of-type (= 1 (sibling-position (structural-siblings document node true) (:node/id node)))
    :last-of-type (let [siblings (structural-siblings document node true)]
                    (and (seq siblings)
                         (= (count siblings) (sibling-position siblings (:node/id node)))))
    :nth-child (nth-pseudo-matches? document node false false arg match-fn)
    :nth-of-type (nth-pseudo-matches? document node true false arg match-fn)
    :nth-last-child (nth-pseudo-matches? document node false true arg match-fn)
    :nth-last-of-type (nth-pseudo-matches? document node true true arg match-fn)
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

(defn- has-arg-sibling-match?
  "Whether any of `node`'s FOLLOWING siblings matches compound selector
   `compound`, per `match-fn` -- `:has(~ p)` (`adjacent-only?` false, real
   CSS's general-sibling form: any later sibling) and `:has(+ p)`
   (`adjacent-only?` true: the IMMEDIATELY next element sibling only).

   The one relative form :has() has that does not look downward at all.
   `has-arg-descendant-match?`/`has-arg-child-match?` walk `node`'s own
   subtree; this walks sideways, which needs the PARENT's element children
   -- `element-children`/`sibling-position`, the same pair the structural
   pseudo-classes already use, rather than a third traversal.

   Only FOLLOWING siblings, never preceding ones: real CSS's `~` and `+`
   are both forward-only, so `h2:has(~ p)` matches an `<h2>` with a later
   `<p>` and never a `<p>` with an earlier `<h2>` -- that second element is
   what `h2 ~ p` selects, and it is a different subject. Measured in Brave
   151 on 2026-08-05: `<div><h2>head</h2><p>after</p></div>` with
   `h2:has(~ p) { font-style: italic }` italicises the `<h2>` alone.

   False when `node` has no parent, matching every other sibling-relative
   answer in this namespace."
  [document node compound adjacent-only? match-fn]
  (boolean
   (when-let [siblings (structural-siblings document node false)]
     (let [siblings (vec siblings)
           position (sibling-position siblings (:node/id node))
           following (when position (subvec siblings position))
           candidates (if adjacent-only? (take 1 following) following)]
       (some (fn [sibling-id]
               (match-fn document (get-in document [:nodes sibling-id]) compound))
             candidates)))))

(defn- has-group-matches?
  "Whether `node` matches one :has() GROUP -- one occurrence's
   comma-separated relative-selector list, each item a `{:has/selector
   <compound> :has/combinator <kw>}` map (see `parse-simple-selector`).
   Real CSS: matches if AT LEAST ONE listed relative selector matches
   (`some`) -- :has()'s own comma list is an OR, mirroring :is()/:where()'s
   identical per-group `some` semantics (see `matches-simple?`) --
   dispatching each item on its leading combinator: `:child` (`>`) to
   `has-arg-child-match?`, `:next-sibling` (`+`) and `:following-sibling`
   (`~`) to `has-arg-sibling-match?`, and `:descendant` (no leading
   combinator, the far more common plain case) to
   `has-arg-descendant-match?`.

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
     (some (fn [{:has/keys [selector combinator direct-child?]}]
             (case (or combinator (if direct-child? :child :descendant))
               :child (has-arg-child-match? document node selector match-fn)
               :next-sibling (has-arg-sibling-match? document node selector true match-fn)
               :following-sibling (has-arg-sibling-match? document node selector false match-fn)
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
                                       (get (:selector/lang-args selector) pseudo))
                                   matches-simple?))
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

    ;; `none`/`normal`: a real declaration that generates no box. It
    ;; travelled this far as a marker only so it could WIN the cascade over
    ;; the user-agent sheet's own `content` -- see `parse-content-none-ref`
    ;; -- and becomes the absent `:content` every reader already knows.
    (and (map? value) (contains? value :content/none))
    nil

    :else value))

;; ---- generated quotes ----

(def ^:private quote-marks
  "The characters `open-quote`/`close-quote` produce, one pair per QUOTE
   DEPTH -- CSS's `quotes` property, at the `auto` value every element in
   this engine has, resolved for this oracle's locale.

   Measured in Brave 151 on 2026-08-05, and measured rather than looked up
   because `quotes: auto` is locale-dependent and nothing in the CSS text
   names a character: the same markup was rendered twice, once with
   `quotes: auto` and once with `quotes: \"\\201C\" \"\\201D\" \"\\2018\"
   \"\\2019\"`, and every `<q>` box came out BYTE-IDENTICAL in both -- 63px
   for `<q>hello</q>`, 91 and 35 for a nested pair. The characters are
   therefore U+201C/U+201D at depth 1 and U+2018/U+2019 at depth 2.

   Two more numbers from the same page, because they are what makes the
   depth observable at all: each of those four characters advances 14px in
   this page's monospace 14px face (a plain ASCII `\"` advances 7 -- they
   fall back to a proportional face), and a THREE-deep nest measures 147 /
   91 / 35, i.e. depth 3 reuses depth 2's pair, which is what CSS says
   happens once the list runs out."
  [["\u201C" "\u201D"] ["\u2018" "\u2019"]])

(defn- quote-mark
  "The `open`/`close` character for `depth` (0-indexed), reusing the last
   pair once the list runs out -- real CSS's own rule for a depth deeper
   than the `quotes` list."
  [depth open?]
  (let [pair (nth quote-marks (min depth (dec (count quote-marks))))]
    (if open? (first pair) (second pair))))

(defn- quote-marker
  "The `:content/quote` keyword a resolved pseudo-element style's `content`
   holds, or nil -- see `parse-content-quote-ref`."
  [pseudo-style]
  (let [v (:content pseudo-style)]
    (when (map? v) (:content/quote v))))

(defn- resolve-quote-content
  "Turns the `:content/quote` markers on `style`'s `:pseudo/before` and
   `:pseudo/after` into real text, and answers what quote depth this
   element's CHILDREN are at. Returns `[style child-depth]`.

   Depth is a property of the tree, which is why this runs in
   `style-element` (where the walk has it) rather than in
   `resolve-style-for` (where the declaration is): an element's own two
   pseudo-elements both sit at `depth` -- a `::after`'s `close-quote`
   closes the quote its own `::before` opened, not a deeper one -- and only
   its DESCENDANTS are one deeper. Measured in Brave, `x <q>a <q>b</q> c
   </q> y`: the outer `<q>` is 91px wide with U+201C/U+201D and the inner
   35 with U+2018/U+2019, and the outer's closing mark is the wide one.

   `no-open-quote`/`no-close-quote` move the depth and generate nothing,
   which this expresses by dropping the `:content` key entirely -- the same
   thing every other unresolvable `content` value does, so no reader needs
   to learn a new shape.

   SCOPE: the depth is carried down the tree only, so a `close-quote` with
   no matching `open-quote` above it reads as depth 0 rather than being an
   error, and a sibling's quote does not affect the next sibling's depth.
   Real CSS keeps ONE running counter in document order, which differs from
   this only for markup where the two do not nest -- and `quotes` itself is
   not modelled at all, so an author cannot change the characters."
  [style depth]
  (let [resolve-one
        (fn [m open?]
          (if-let [kw (quote-marker m)]
            (case kw
              :open (assoc m :content (quote-mark depth true))
              :close (assoc m :content (quote-mark depth false))
              (dissoc m :content))
            m))
        before (:pseudo/before style)
        after (:pseudo/after style)
        opens? (contains? #{:open :no-open} (quote-marker before))
        style (cond-> style
                before (assoc :pseudo/before (resolve-one before true))
                after (assoc :pseudo/after (resolve-one after false)))]
    [style (if opens? (inc depth) depth)]))

;; ---- resolving the CSS-wide keywords ----
;;
;; The keywords themselves are recognized much earlier in this file
;; (`css-wide-keywords`/`css-wide-keyword`), because
;; `expand-box-side-shorthand` has to admit one as a whole-declaration
;; token. What they RESOLVE to needs `parent-node-id`, so it lives here.

(def ^:private inherited-properties
  "The properties that INHERIT, of those this engine models -- which is the
   whole of what `unset` needs to know (`unset` is `inherit` on an
   inherited property and `initial` on every other one).

   Measured rather than recalled, in Brave 151 on 2026-08-05: each property
   was set to a non-default value on a `<div>` and to `unset` on a `<span>`
   inside it, and the span was read back. Everything listed here kept the
   div's value; `vertical-align` -- the one that looks like it belongs and
   does not -- came back `baseline`, and is therefore absent.

   A property NOT listed here is treated as non-inherited, which is the
   conservative direction: `unset` then resolves to the property's initial
   value, which is what an unlisted property's initial value already was
   before this table existed."
  #{:color :font :font-family :font-size :font-style :font-weight :font-variant
    :font-stretch :line-height :letter-spacing :word-spacing :text-align
    :text-indent :text-transform :text-shadow :white-space :word-break
    :overflow-wrap :word-wrap :tab-size :hyphens :direction :visibility
    :cursor :quotes :list-style :list-style-type :list-style-position
    :list-style-image :border-collapse :border-spacing :caption-side
    :empty-cells :orphans :widows :writing-mode :text-orientation})

(def ^:private initial-values
  "CSS's own initial value for the properties whose initial value is not
   the same as ABSENCE in this engine's representation -- i.e. every
   property the user-agent stylesheet declares (where dropping the
   declaration would leave the UA value standing, and `initial` must beat
   it), plus the inherited properties (where dropping would inherit).

   Every entry measured in Brave 151 on 2026-08-05 by setting the property
   to `initial` on an element inside an ancestor that declared a different
   value, and reading `getComputedStyle` back -- see `css-wide-keywords`
   for the table and the page.

   NOT here, and each for a reason that is not 'not got to yet':

   - `font-size`. Its initial value is the keyword `medium`, whose pixel
     value is keyed on the DEFAULT font of the family in use: measured
     13px on the corpus's monospace page and 16px on the same page with
     `font-family: Arial`. That is the same family-keyed table that keeps
     the absolute font-size keywords out of `resolve-font-size` (see its
     own docstring), and this engine has no font-family model to key it
     on. `font-size: initial` therefore drops, and inherits -- wrong, and
     wrong in a way that is one measurement away from being right if a
     family model ever arrives.
   - `font-family`. Its initial value is the browser's own default family,
     which is a user preference and not a CSS value at all: measured
     `\"Hiragino Kaku Gothic ProN\"` on this machine. This sheet has no
     font-family rule to beat, so `font-family: initial` drops and
     inherits -- the same cut as `font-size`, for the same missing model.
   - The uniform `:margin`/`:padding` keys this engine emits alongside the
     four longhands (see `expand-box-side-shorthand`). They are not CSS
     properties, and absence is exactly what they should say when no
     uniform value survives -- their readers already fall back.
   - Anything else. A property with no entry drops, which is CSS's initial
     value for every non-inherited property that the UA sheet does not
     declare. Adding an entry is only ever needed to beat the UA sheet or
     to stop an inherited property inheriting."
  {:color "#000000"
   :font-weight "normal"
   :font-style "normal"
   :display "inline"
   :text-align "start"
   :vertical-align "baseline"
   :white-space "normal"
   :text-transform "none"
   :text-indent 0
   :letter-spacing "normal"
   :word-spacing 0
   :line-height "normal"
   :visibility "visible"
   :list-style-type "disc"
   :direction "ltr"
   :border-collapse "separate"
   :border-spacing 0
   :caption-side "top"
   :empty-cells "show"
   :word-break "normal"
   :overflow-wrap "normal"
   :tab-size 8
   :cursor "auto"
   :text-shadow "none"
   :margin-top 0 :margin-right 0 :margin-bottom 0 :margin-left 0
   :padding-top 0 :padding-right 0 :padding-bottom 0 :padding-left 0})

(def ^:private drop-declaration
  "The sentinel `resolve-css-wide-keyword` returns for 'this property has
   no value here' -- distinct from nil, which is a value a declaration can
   legitimately resolve to."
  ::drop)

(defn- parent-computed-value
  "The value the PARENT element resolved for `property`, read off the
   `:style/*` attrs `style-element` has already written -- which is what
   makes `inherit` answerable at all. `apply-cascade` walks top-down, so
   by the time any element resolves, its parent's attrs are final.

   Returns `drop-declaration` when there is no document, no parent, or the
   parent resolved nothing for this property. All three are the same
   honest answer for a different reason: a parent that declared nothing
   for a NON-inherited property computed that property's initial value,
   which is exactly what dropping the declaration yields; and a parent
   that declared nothing for an INHERITED one is itself inheriting, which
   dropping also reproduces, because absence is how this engine spells
   'look further up' (see `resolve-style-for`'s docstring). The standalone
   `computed-style` path has no styled ancestors at all and therefore gets
   initial values throughout -- stated here rather than discovered."
  [document node property]
  (or (when document
        (when-let [parent-id (parent-node-id document (:node/id node))]
          (get-in document [:nodes parent-id :attrs (keyword "style" (name property))])))
      drop-declaration))

(defn- resolve-css-wide-keyword
  "Resolves one CSS-wide keyword to the value it stands for, or
   `drop-declaration`.

   `lower-entries` is the cascade entries for this same property from
   origins BELOW the one the keyword was declared in, already sorted --
   which is all `revert` needs: real CSS's `revert` rolls the cascade back
   to the previous ORIGIN, and with two origins (see `ua-origin` /
   `author-origin`) an author's `revert` is 'resolve this property using
   the user-agent declarations alone'. Measured in Brave 151, 2026-08-05:
   `p.rv { margin: 0 }` with `style=\"margin: revert\"` reports 14px top
   and bottom (the UA `p { margin: 1em 0 }`) and 0 left and right (no UA
   declaration, so the initial value) -- the author rule is gone, not
   merely outranked.

   A `revert` with nothing below it degrades to `unset`, per the spec's
   own definition, and so does a `revert` inside the lowest origin -- this
   sheet has no CSS-wide keyword in it, so that branch is reachable only
   from a host that supplies its own UA rules."
  [document node property keyword-kind lower-entries]
  (case keyword-kind
    :inherit (parent-computed-value document node property)
    :initial (get initial-values property drop-declaration)
    :unset (resolve-css-wide-keyword document node property
                                     (if (contains? inherited-properties property)
                                       :inherit
                                       :initial)
                                     nil)
    :revert (if-let [{:keys [value]} (last lower-entries)]
              (if-let [nested (css-wide-keyword value)]
                (resolve-css-wide-keyword document node property
                                          (if (= :revert nested) :unset nested)
                                          nil)
                value)
              (resolve-css-wide-keyword document node property :unset nil))))

;; ---- the user-agent stylesheet ----
;;
;; A browser's cascade has THREE origins -- user-agent, author, inline --
;; and until now this one had only the last two. The UA origin's knowledge
;; lived in `cssom.layout`, as a column of `(or (style node :x) <ua
;; default>)` fallbacks and half a dozen tag->value tables beside them, so
;; the cascade never wrote those values and `computed-style` reported
;; `display: inline` for a `<div>`, weight 400 for a `<b>` and black for a
;; link. Layout was right; everyone who ASKED was told something false --
;; devtools, an accessibility projection, a `getComputedStyle`-compatible
;; API, a script that branches on style. Measured against a real browser by
;; the conformance harness on 2026-08-05: 2,260 of 2,315 computed-style
;; mismatches were that one architectural fact repeated, and the genuinely
;; cascade-attributed residual behind them was NINE values. See
;; ADR-2800003100.
;;
;; It is CSS text rather than a tag->value map on purpose. Real UA rules
;; are not keyed on the tag alone and pretending they are is the trap this
;; measurement walked into first: an `<a>` is blue only when it HAS an
;; `href` (Chrome's own rule is `a:-webkit-any-link`, and a bare `<a>`
;; measures ZERO non-initial properties), an `<input>`'s box depends
;; entirely on its `type`, and `[hidden]` is an attribute rule that applies
;; to every element there is. Written as CSS, `parse-rules` and `matches?`
;; -- the same selector engine an author's stylesheet goes through --
;; express all three directly, and specificity orders `input[type=...]`
;; over `input` for free.

(def ^:private ua-stylesheet-text
  "The user-agent stylesheet, as CSS.

   SCOPE, stated exactly, because what is NOT here matters as much as what
   is. The sheet landed in two halves. The first carried every UA
   declaration whose value is an ABSOLUTE length or a keyword. The second
   -- `p { margin: 1em 0 }`, `h1 { font-size: 2em }`, a `<fieldset>`'s
   `padding: 0.35em 0.75em 0.625em`, `small { font-size: smaller }` -- had
   to wait for the cascade to be able to resolve a length at all, because
   `em` resolves against the element's OWN computed font size and this
   cascade used to store whatever the author declared without ever
   computing one. `resolve-relative-lengths` is that step, and those rules
   are now here with the rest; `cssom.layout`'s `ua-margin-scale` /
   `ua-font-scale` / `ua-em-box` are gone.

   Deliberately absent, each for a reason that is not 'not got to yet':

   - `display: none` for `<head>`/`<script>`/`<style>`/... . `cssom.layout`
     suppresses those by tag (`non-rendered-tags`) BEFORE it looks at
     `display` at all, and the set of tags a real UA hides is wider than
     the set this engine renders -- writing the rule would change what
     `<datalist>`/`<source>`/`<track>` lay out, which is a rendering change
     wearing a cascade change's clothes. `[hidden]` IS here: that one is
     already spelled in `node-style`, as an attribute rule, and moving it
     is a move rather than a new rule.
   - the ABSOLUTE font-size keywords. Chrome's real sheet says
     `pre { font-family: monospace; font-size: -webkit-xxx-large }`-style
     things whose value comes out of a table keyed on the default font of
     the family in use (measured: `font-size: medium` is 13px on a
     monospace page and 16 on a proportional one). `smaller`/`larger` ARE
     here, on `<small>`/`<sub>`/`<sup>`, because they are a plain ratio
     off the parent (see `font-size-scale-step`) and need no such table.
   - `<select>`'s block padding. `cssom.layout`'s `ua-control-box` gives it
     1px top and bottom, and a browser reports `padding: 0px` -- the 1px is
     this engine's expression of Chrome's own internal button padding,
     measured as a constant +4px of height at every font size (see that
     table's docstring). It is a box constant with no `getComputedStyle`
     counterpart, so writing it into the cascade would make the reported
     value WRONG to make a box right.
   - `text-align: center` on `<th>`/`<button>` and `-webkit-center` on
     `<caption>`. Real, and this engine has neither -- so they are new UA
     knowledge, not a move. Landed separately, measured against geometry.

   Values are the ones `cssom.layout` already used, which are themselves
   readings off `getComputedStyle` in Brave 151 (see each table's docstring
   there for the measurement). Where the browser and this engine differed,
   the ENGINE's value was written when the sheet's first half landed: that
   change moved where a value comes from, not what it is. The one place
   that left a knowingly-wrong number -- `<input type=radio>`, given a
   CHECKBOX's `3px 3px 3px 4px` where a browser measures `3px 3px 0 5px`
   -- is corrected here, re-measured in Brave 151 on 2026-08-05, because
   the second half moves boxes anyway and a 4-value residual charged to
   the cascade is not worth carrying to look tidy.

   The `margin`/`padding` SHORTHAND is used only where the uniform value it
   also emits (see expand-box-side-shorthand) is the one this engine's
   uniform `:padding`/`:margin` reader should see. `input`/`button` state
   their four sides as longhands for exactly that reason: their shorthand's
   first token is the 1px BLOCK padding, and letting it land on the uniform
   key would narrow every text field by the difference.

   The measurements that travelled here with the rules, from the tables in
   `cssom.layout` these replace -- each one is why a rule is here rather
   than a plausible-looking guess:

   - `td, th { padding: 1px }`: without it every table cell was 2px short
     in each axis, which the conformance harness's geometry axis reported
     as `td` 6/29.
   - `figure { margin: 1em 40px }`: only the 1em half of it used to exist
     (the vertical margin was in `ua-margin-scale` from the start while the
     40px indent was nowhere), so a `<figure>` sat flush against its
     article's content edge and was 80px too wide. Measured in Brave 151,
     2026-08-05, on `:page/article-with-figure`'s own 300px article: the
     browser puts the figure at x=40 with w=220 and this engine had x=0,
     w=300, which the figure's `<img>` and `<figcaption>` then inherited
     box for box -- six numbers from one missing declaration.
   - `menu`/`dir` carry `<ul>`'s 40px indent and `display: block` because
     they are the legacy list containers and are in Chrome's own rule.
     Measured 2026-08-05 on a bare `<menu><li>a</li></menu>`:
     `margin-block: 16px`, `padding-left: 40px`.
   - `b, strong, th, h1..h6 { font-weight: bold }`: authors do not write
     `b { font-weight: bold }`, the UA does, and without it `<b>`,
     `<strong>`, `<th>` and every heading rendered in NORMAL weight.
   - `sub { vertical-align: sub }` / `sup { vertical-align: super }`: an
     author writes `<sub>`, never the declaration, so without them a
     subscript and a superscript sat on the same baseline as the text
     around them -- the entire visual point of both tags.
   - `table { border-spacing: 2px }` is keyed on the TAG, not on being a
     table: real CSS's initial value is 0, and measured in Brave a
     `<div style=\"display:table\">` reports `border-spacing: 0px` where
     `<table>` reports 2px. Defaulting every table-displayed box to 2 put
     phantom spacing into every CSS-declared table.
   - The `:disabled` colours, measured in Brave 151 on 2026-08-05 by
     putting every control in the page twice, once bare and once
     `disabled`, and reading `color` back. There are THREE of them, not
     one, and which one applies is keyed on the control's `type`:

       input, textarea                                rgb(84, 84, 84)
       button, input type=button/submit/reset/color   rgba(16, 16, 16, .3)
       select                                         rgb(128, 128, 128)

     `:disabled` and not `[disabled]`: an `<input>` inside a
     `<fieldset disabled>` reports the same grey with no attribute of its
     own, which is exactly what `disabled-control?` already computes.
     `readonly` is NOT this -- a readonly input reports plain black -- and
     neither is `disabled` on a non-control (a `<p disabled>` is black).
     `input[type=\"range\"]:disabled` measures rgb(197, 197, 197) and is
     deliberately absent: the range control has no text to colour here and
     this engine draws no track, so the value would be unobservable.

     What is deliberately NOT here, though it was measured at the same
     time: the ENABLED control colours. An `<input>` inside a
     `color: #ff0000` div reports BLACK in a browser -- a control does not
     inherit the page's colour -- and this engine inherits it. Writing
     `input, textarea, select { color: #000000 }` would fix that and would
     also hard-code black into every host theme that renders through this
     cascade, including the dark one `cssom.layout`'s own theme draws (the
     conformance corpus paints its text `#e6ebf5`). The real UA value is
     the system colour `fieldtext`, and this sheet has no system-colour
     model; landing it needs one, not a hex constant.

   - `[hidden] { display: none }` is attribute PRESENCE and does not look
     at the value. Measured in Brave 151, 2026-08-05: `hidden=\"false\"`,
     `hidden=\"\"` and `hidden=\"hidden\"` all report `display: none` and
     `offsetHeight: 0`, against a bare `<div>`'s `block`/24.

   And the `em`-relative half, every number of it re-measured in Brave 151
   on 2026-08-05 inside a 14px page (which is what makes the ratios
   readable: an `<h5>` reports font-size 11.62 and margin 19.4054, i.e.
   0.83 and 1.67 exactly, and 1.67 of its OWN 11.62 rather than of the
   page's 14):

   - `h1..h6`: font-size 2 / 1.5 / 1.17 / 1 / 0.83 / 0.67 em, margin-block
     0.67 / 0.83 / 1 / 1.33 / 1.67 / 2.33 em. `h4`'s `font-size: 1em` is
     not written: it is the inherited size by definition, and writing it
     would turn an inherited value into a declared one for no effect.
   - `p, blockquote, dl, pre, figure, ul, ol, menu, dir { margin-block:
     1em }` and `hr { margin-block: 0.5em }`.
   - `:is(ul, ol, menu, dir) :is(ul, ol, menu, dir) { margin-block: 0 }`,
     spelled out as sixteen descendant selectors because this sheet's
     matching stays inside the plain-selector subset. Chrome's own rule is
     the `:is()` form. This is the ONE rule here with a combinator, and it
     is why `ua-style-of` has a no-document branch -- see there.
   - `small, sub, sup { font-size: smaller }`. This engine used to say
     `0.83em`, close enough to survive the harness's tolerance (11.62
     against the browser's 11.6667) but not the same rule; `smaller` is
     what a UA sheet actually says and it is exactly 1/1.2.
   - `fieldset { padding: 0.35em 0.75em 0.625em }`, as longhands for the
     reason stated above -- measured 4.9 / 10.5 / 8.75 at 14px and
     7 / 15 / 12.5 at 20px, which is what separates the em terms from the
     px ones.
   - `button, input, select, textarea { font-size: 13.3333px }`. Not `em`
     at all, and the reason it is in this half rather than the first is
     that it needed the same machinery: a control's UA font is an absolute
     13.3333px in every browser (an `<input>` inside a `font-size: 30px`
     div still measures 13.3333), and until `resolve-relative-lengths`
     normalised a fractional length to a number, writing it here would
     have handed `cssom.layout` the string \"13.3333px\" where it wants a
     number. The FAMILY that goes with it (Arial, monospace for
     `<textarea>`) stays in `cssom.layout`'s `ua-control-font` -- this
     sheet has no font-family rule at all and adding one is a separate
     measurement.
   - `input[type=\"radio\"]`'s own margins, `3px 3px 0 5px`, split out of
     the checkbox rule they were wrongly sharing.
   - `progress { width: 10em; height: 1em }` and
     `meter { width: 5em; height: 1em }`, plus
     `progress, meter { display: inline-block; box-sizing: border-box;
     vertical-align: -0.2em }`. Both tags used to be in `cssom.layout`'s
     `inline-level-tags` -- text-like inlines with no box of their own --
     and laid out as 400x0 blocks. The `inline-block` half was written
     down here as deliberately ABSENT until 2026-08-06, on the grounds
     that acting on it \"would silently rewrite how they flow, which is a
     layout decision needing its own measurement\". This is that
     measurement, in Brave 151, and every number of it is `em`:

       font-size    progress      meter
       8px           80x8         40x8
       14px         140x14        70x14
       28px         280x28       140x28

     i.e. `10em x 1em` and `5em x 1em` exactly, which is why these are
     stylesheet rules and not the platform constants a 140 and a 70 would
     otherwise look like. `box-sizing: border-box` and `display:
     inline-block` are read straight off `getComputedStyle`; the
     `-0.2em` is the 2.797px both sit BELOW the baseline at 14px (5.594 at
     28px, and 0 change under `line-height: 10px`/`40px`, which is what
     rules out a leading-derived explanation). Their flow is measured too:
     `x <progress></progress> y` is ONE 20px line in Brave with the bar at
     x=14, where the block treatment gave three rows.
   - `input[type=\"range\"|\"color\"|\"file\"]`. All three used to come out
     of the plain `input` rule at a text field's 153x21 with its 1px/2px
     padding and 2px border, and none of them is a text field. Measured in
     Brave 151 on 2026-08-06, at `font-size` 8, 14, 28 and 40 -- none of
     these numbers moves with the font, which is what says they are
     platform WIDGETS and not boxes derived from a face:

       range   129x16, `margin: 2px`, no padding, no border, content-box
       color   50x27, `border: 1px`, the text field's 1px/2px padding,
               border-box
       file    ...x27, no padding, no border, content-box

     The `...` is not an omission, it is the scope cut, and it is here
     rather than in a comment because the number is the interesting part:
     Brave says **253**, and 253 is not a platform constant. It is the
     width of a shadow-DOM `<button>` holding the browser's own localized
     \"Choose File\" string plus a reserved filename column. Measured on
     the same page: that button is 87.141 wide and \"No file chosen\" in
     the UA control face is 84.484, which sum to 171.625 -- 81 short of
     253 -- so the control also reserves a fixed filename column this
     engine has no way to derive, and both halves are en-US strings that a
     browser in another locale renders at a different width. A 253 written
     down here would be a measurement of this machine's UI language. The
     HEIGHT is a rule and is written: 27 is the engine's own 21px
     `<button>` with 3px above and below it, which is also where the
     control's baseline comes from (see `ua-control-baseline` in
     `cssom.layout`).
   - `iframe { border: 2px inset }`, which is the entire difference
     between an `<iframe>` and a `<video>`: measured in Brave 151 on
     2026-08-06 both reserve a 300x150 CONTENT box, and the iframe reports
     304x154 because of this one declaration -- `border: 0` on the same
     iframe gives 300x150 exactly. The `inset` STYLE is not modelled (this
     engine draws one solid border, the same reading `<hr>` already
     carries in `cssom.layout`'s `ua-tag-box`), so the style is written
     for what it is and the paint is a solid hairline.
   - `dialog`'s `padding: 1em` and `border: solid`, measured in Brave 151
     on 2026-08-05: `padding` 14px on all four sides at this page's 14px,
     `border-top-width` 3px (`solid`'s `medium`), `border-top-style`
     solid. Written as four padding longhands, like `fieldset`, because
     `padding: 1em` is not a length at declaration time and
     `expand-box-side-shorthand` correctly declines to expand it -- which
     leaves ONLY the uniform key, and everything that reads a per-side
     padding (including `getComputedStyle`) then sees nothing. Together
     these make the box exact in one dimension: Brave reports
     `<dialog open>Hi</dialog>` as 48x54 and this engine drew 300x20,
     which is now 300x54.

     And the REST of `dialog`'s UA rule, written on 2026-08-06 once
     `cssom.layout` could resolve `fit-content`: `position: absolute;
     left: 0; right: 0; width: fit-content; height: fit-content;
     margin: auto`. It was measured a round earlier and deliberately left
     out then, because adding it would have moved the numbers without
     converging on them -- and that was measured too: with the
     declarations added and `fit-content` still behaving as `auto`, NOT
     ONE BOX MOVED. The box stayed 300 wide, so the leftover space the
     auto margins are supposed to split was zero.

     Re-measured in Brave 151 over CDP on 2026-08-06, `<dialog open>Hi
     </dialog>` inside a 300px `position: relative` parent: the box is
     48x54 at x=126, `getComputedStyle` reports `width: 14px` (the
     fit-content size of `Hi`), `height: 20px`, `margin: 0px 126px` --
     i.e. `(300 - 48) / 2` on each inline side and ZERO on the block
     sides -- `left: 0px`, `right: 0px`, and `top` resolved to the box's
     own STATIC position, which is what says `top` is `auto` in the UA
     sheet and only the inline insets are written. Confirmed by moving
     the same dialog into a case with no positioned ancestor: it centres
     in the 756px viewport (x=354) and its `top` reads back as that
     case's own offset down the page (124px), which no declared `top`
     could produce.

     Written as four `margin-*` longhands for the same reason the padding
     is: `auto` is not a length, so expand-box-side-shorthand correctly
     declines to expand `margin: auto`, and everything that reads a
     per-side margin -- `auto-margin?` in `cssom.layout`, and
     `getComputedStyle` -- would then see nothing.

     Measured but NOT written, because neither changes a box or any of
     the fourteen properties the computed-style axis compares:
     `background-color: rgb(255,255,255)` and `color: rgb(0,0,0)`. This
     engine's default page is dark, so writing them would repaint every
     dialog white on a surface nothing else in the corpus can judge."
  "
  html, body, address, article, aside, blockquote, center, dd, details,
  dialog, dir, div, dl, dt, fieldset, figcaption, figure, footer, form,
  h1, h2, h3, h4, h5, h6, header, hgroup, hr, legend, main, menu, nav,
  ol, optgroup, option, p, pre, search, section, ul { display: block }
  li, summary { display: list-item }
  table { display: table }
  tr { display: table-row }
  td, th { display: table-cell }
  thead { display: table-header-group }
  tbody { display: table-row-group }
  tfoot { display: table-footer-group }
  caption { display: table-caption }
  col { display: table-column }
  colgroup { display: table-column-group }
  button, input, select, textarea { display: inline-block }
  progress, meter { display: inline-block; box-sizing: border-box;
                    vertical-align: -0.2em }
  [hidden] { display: none }
  template { display: none }
  dialog:not([open]) { display: none }

  th, button { text-align: center }
  caption { text-align: -webkit-center }
  option { padding: 0 2px 1px }

  b, strong, th, h1, h2, h3, h4, h5, h6 { font-weight: bold }
  address, cite, dfn, em, i, var { font-style: italic }
  sub { vertical-align: sub }
  sup { vertical-align: super }
  pre { white-space: pre }
  a[href] { color: #0000EE }
  hr { color: #808080 }
  q::before { content: open-quote }
  q::after { content: close-quote }
  input:disabled, textarea:disabled { color: #545454 }
  select:disabled { color: #808080 }
  button:disabled, input[type=\"button\"]:disabled,
  input[type=\"submit\"]:disabled, input[type=\"reset\"]:disabled,
  input[type=\"color\"]:disabled { color: rgba(16, 16, 16, 0.3) }

  table { border-spacing: 2px }
  td, th { padding: 1px }
  ul, ol, menu, dir { padding-left: 40px }
  blockquote, figure { margin-left: 40px; margin-right: 40px }
  dd { margin-left: 40px }
  fieldset { margin-left: 2px; margin-right: 2px }
  legend { padding-left: 2px; padding-right: 2px }
  textarea { padding-top: 2px; padding-right: 2px;
             padding-bottom: 2px; padding-left: 2px }
  input { padding-top: 1px; padding-bottom: 1px;
          padding-left: 2px; padding-right: 2px }
  button { padding-top: 1px; padding-bottom: 1px;
           padding-left: 6px; padding-right: 6px }
  input[type=\"checkbox\"], input[type=\"radio\"] {
    padding-top: 0; padding-right: 0; padding-bottom: 0; padding-left: 0;
    margin-top: 3px; margin-right: 3px }
  input[type=\"checkbox\"] { margin-bottom: 3px; margin-left: 4px }
  input[type=\"radio\"] { margin-bottom: 0; margin-left: 5px }
  input[type=\"range\"] { width: 129px; height: 16px;
                          padding: 0; margin: 2px; border-width: 0 }
  input[type=\"color\"] { width: 50px; height: 27px;
                          border-width: 1px; box-sizing: border-box }
  input[type=\"file\"] { height: 27px; padding: 0; border-width: 0 }

  h1 { font-size: 2em }
  h2 { font-size: 1.5em }
  h3 { font-size: 1.17em }
  h5 { font-size: 0.83em }
  h6 { font-size: 0.67em }
  small, sub, sup { font-size: smaller }
  button, input, select, textarea { font-size: 13.3333px }

  h1 { margin-top: 0.67em; margin-bottom: 0.67em }
  h2 { margin-top: 0.83em; margin-bottom: 0.83em }
  h3 { margin-top: 1em; margin-bottom: 1em }
  h4 { margin-top: 1.33em; margin-bottom: 1.33em }
  h5 { margin-top: 1.67em; margin-bottom: 1.67em }
  h6 { margin-top: 2.33em; margin-bottom: 2.33em }
  p, blockquote, dl, pre, figure, ul, ol, menu, dir {
    margin-top: 1em; margin-bottom: 1em }
  hr { margin-top: 0.5em; margin-bottom: 0.5em }
  progress { width: 10em; height: 1em }
  meter { width: 5em; height: 1em }
  ul ul, ul ol, ul menu, ul dir, ol ul, ol ol, ol menu, ol dir,
  menu ul, menu ol, menu menu, menu dir, dir ul, dir ol, dir menu, dir dir {
    margin-top: 0; margin-bottom: 0 }
  fieldset { padding-top: 0.35em; padding-right: 0.75em;
             padding-bottom: 0.625em; padding-left: 0.75em }
  dialog { padding-top: 1em; padding-right: 1em;
           padding-bottom: 1em; padding-left: 1em;
           border-width: 3px; border-style: solid;
           position: absolute; left: 0; right: 0;
           width: fit-content; height: fit-content;
           margin-top: auto; margin-right: auto;
           margin-bottom: auto; margin-left: auto }
  iframe { border-width: 2px; border-style: inset }
  ")

(def ua-rules
  "`ua-stylesheet-text`, parsed once. Public so `cssom.layout` can read the
   SAME rules when it is handed a document that was never cascaded -- see
   `ua-style-for`."
  (parse-rules ua-stylesheet-text))

(def ^:private ua-rules-by-tag
  "`ua-rules` indexed by the tag its selector's SUBJECT names, so matching
   costs one map lookup plus the two or three rules that can possibly apply
   instead of a scan of the whole sheet on every element of every document.

   `nil` is the bucket for a selector with no tag in its subject compound
   (`[hidden]`), which has to be tried against everything."
  (reduce (fn [idx rule]
            (reduce (fn [idx selector]
                      (let [subject (if (:selector/parts selector)
                                      (last (:selector/parts selector))
                                      selector)
                            tag (:selector/tag subject)]
                        (update idx tag (fnil conj [])
                                (assoc rule :rule/selectors [selector]))))
                    idx
                    (:rule/selectors rule)))
          {}
          ua-rules))

(defn- ua-rules-for
  "The UA rules that can possibly apply to `node` -- its tag's bucket plus
   the tagless one."
  [node]
  (concat (get ua-rules-by-tag (:tag node)) (get ua-rules-by-tag nil)))

(def ^:private ua-origin
  "The cascade ORIGIN of a user-agent declaration. Sorted before
   `author-origin`, so the last-sorted (winning) entry for a property is an
   author's whenever an author declared one at all -- real CSS's
   origin step, and the reason this is a separate tuple element rather
   than a very low :specificity (a UA `input[type=\"checkbox\"]` must still
   lose to an author's bare `input`, which specificity alone would get
   backwards)."
  0)

(def ^:private author-origin 1)

(def ^:private ua-conditional-attrs
  "Every HTML attribute any selector in `ua-stylesheet-text` tests -- read
   off the parsed rules rather than restated, so adding a rule to the sheet
   cannot silently invalidate the fast path in `ua-style-for` below.

   Today: `#{:hidden :href :type}`."
  (set (for [rule ua-rules
             selector (:rule/selectors rule)
             part (or (:selector/parts selector) [selector])
             a (:selector/attrs part)]
         (:attr/name a))))

(def ^:private ua-sheet-is-tag-and-attr-only?
  "Whether every COMPOUND in every selector in the sheet is a bare tag, or
   a tag plus an attribute-PRESENCE-implying condition -- no class, id,
   pseudo-class, `:not()`/`:is()`/`:where()`/`:has()`.

   `ua-style-for`'s fast path rests on exactly this: if it holds, an
   element carrying none of `ua-conditional-attrs` cannot match anything
   beyond its own tag's rules, so its UA style is a precomputed map and
   costs one lookup. A `:not([hidden])` or a `.foo` in the sheet would
   break that reasoning, so it is CHECKED at load rather than remembered --
   fail the check and every element takes the general path, which is
   slower and still correct.

   A COMBINATOR does not break it, and used not to be allowed here at all.
   The sheet now has one rule with combinators (the nested-list margin
   cancellation), and what makes the precompute still sound is that a
   combinator selector is skipped outright when there is no document to
   walk (see `ua-style-of`), so the precomputed answer and the answer this
   path would compute are the same answer: the one for an element with no
   ancestors. That is exactly what the fast path's single caller wants --
   see `ua-style-for`."
  (every? (fn [rule]
            (every? (fn [selector]
                      (every? (fn [p]
                                (and (nil? (:selector/id p))
                                     (empty? (:selector/classes p))
                                     (empty? (:selector/pseudos p))
                                     (nil? (:selector/pseudo-element p))
                                     (empty? (:selector/not p))
                                     (empty? (:selector/is p))
                                     (empty? (:selector/where p))
                                     (empty? (:selector/has p))))
                              (or (:selector/parts selector) [selector])))
                    (:rule/selectors rule)))
          ua-rules))

(defn- ua-style-of
  "The general path: match every candidate rule and merge the winners in
   specificity then source order.

   With no `document`, a selector that has a COMBINATOR is skipped rather
   than matched on its subject compound alone. `matches?`'s own 1-arity
   does the latter -- it is the right answer for an author's stylesheet
   being probed against a detached node -- and here it would be actively
   wrong: `ul ul { margin-block: 0 }` would then zero the margins of every
   list on the page, nested or not. Skipping means a caller with no
   document gets the no-ancestors answer, which is the honest one.

   A selector with a PSEUDO-ELEMENT is skipped outright. This answers what
   the UA sheet says about the ELEMENT, and `q::before { content:
   open-quote }` says nothing about a `<q>` -- merging it in would put a
   `content` on the element itself, which real CSS does not render and
   this engine's only caller (`cssom.layout`, on a document that was never
   cascaded) has no generated box to hang it on."
  [document node]
  (->> (for [rule (ua-rules-for node)
             selector (:rule/selectors rule)
             :when (nil? (pseudo-element-of selector))
             :when (if document
                     (matches? document node selector)
                     (and (<= (count (:selector/parts selector [selector])) 1)
                          (matches? node selector)))]
         {:declarations (:rule/declarations rule)
          :sort-key [(specificity selector) (:rule/order rule)]})
       (sort-by :sort-key)
       (reduce (fn [m entry] (merge m (:declarations entry))) {})))

(def ^:private ua-style-by-tag
  "The UA style of an element that carries none of `ua-conditional-attrs`
   and has no ancestors, precomputed per tag. `cssom.layout` asks for this
   once per `node-style` call and `node-style` runs many times per element
   over a layout pass (measure, intrinsic width, then the real one), so the
   difference between a lookup and a match-and-sort is the difference
   between a layout pass and a noticeably slower one -- measured on the
   357-case conformance corpus, the general path alone cost +80% end to
   end."
  (when ua-sheet-is-tag-and-attr-only?
    (into {} (map (fn [tag] [tag (ua-style-of nil {:node/type :element :tag tag :attrs {}})]))
          (remove nil? (keys ua-rules-by-tag)))))

(defn ua-style-for
  "The user-agent declarations that apply to `node`, resolved among
   themselves by specificity, as a plain `{property value}` map.

   The cascade (`apply-cascade`/`computed-style`) does NOT go through this
   -- it folds the same rules in as a real origin, so an author declaration
   can beat them. This exists for the one caller that has no cascade to
   read: `cssom.layout`, handed a tree whose `:style/*` attrs were written
   by something other than `apply-cascade` (a host that renders a page with
   no stylesheet at all takes exactly that path -- see
   `browser.core/render-document`, where `apply-cascade` runs only
   `(seq css-rules)`). Layout consults this ONE table rather than carrying
   its own copy, which is the point: the drift this whole change exists to
   remove is the same knowledge written down twice.

   Values may be RELATIVE (`1em`, `smaller`): this returns the declarations,
   not computed values, and only the caller knows the font size to resolve
   them against. `cssom.layout` runs them through
   `resolve-relative-lengths` with its theme's base size, the same call the
   cascade makes with the real inherited size.

   The precomputed fast path is taken only when there is no `document`,
   because that is the only case where an ancestor-dependent rule provably
   does not apply (see `ua-style-of`). Handed a document, this takes the
   general path and answers correctly for the nested-list rule too."
  ([node] (ua-style-for nil node))
  ([document node]
   (if (and (nil? document)
            ua-style-by-tag
            (not-any? #(contains? (:attrs node) %) ua-conditional-attrs))
     (get ua-style-by-tag (:tag node) {})
     (ua-style-of document node))))

;; ---- relative lengths: `em`, `rem`, and the computed font size ----
;;
;; The second half of the UA stylesheet is `em`-relative -- `p { margin: 1em
;; 0 }`, `h1 { font-size: 2em }`, a `<fieldset>`'s `padding: 0.35em 0.75em
;; 0.625em` -- and none of it could be written down until this cascade could
;; resolve a length. Measured in Brave 151 on 2026-08-05, the model is
;; exactly real CSS's and nothing less will do:
;;
;;   - `em` compounds. Three nested `font-size: 1.5em` divs inside a 14px
;;     page report 21, 31.5 and 47.25px. A single "base size" option
;;     cannot express that; only a top-down walk carrying the PARENT's
;;     computed size can.
;;   - the two `em`s on ONE element resolve against DIFFERENT sizes.
;;     `<div style="font-size:2em; margin-top:1em">` inside 14px reports
;;     font-size 28px and margin-top 28px: the font-size's own `em` is the
;;     parent's 14, every other `em` on that element is its own 28. This is
;;     why the UA sheet's `h3 { font-size: 1.17em; margin-block: 1em }`
;;     comes out 16.38/16.38 rather than 16.38/14, and it is the single
;;     fact a "resolve everything against one number" design gets wrong.
;;   - `rem` is the ROOT element's computed size, not the parent's: inside
;;     a 28px div, `font-size: 1rem` reports 16px on a page whose <html> is
;;     the browser default.
;;   - a percentage font-size is the parent's size (`150%` of 14 -> 21,
;;     then `50%` of that -> 10.5). A percentage on any OTHER property is
;;     the containing BLOCK's, not a font size at all (`margin-top: 50%`
;;     in an 800px box measured 400px) -- so percentages are resolved here
;;     for `font-size` and left completely alone everywhere else.
;;   - `smaller`/`larger` are the parent's size over/times 1.2, and they
;;     compound (14 -> 11.6667 -> 9.72222; 14 -> 16.8).

(def default-base-font-size
  "The font size the ROOT element's own relative units resolve against, and
   the value of `rem` in a document whose root declares no font-size, when
   `apply-cascade` is not told otherwise (its `:base-font-size` opt).

   14, which is NOT CSS's own initial `font-size: medium` (16 in every
   browser) and is chosen against it deliberately. `1em` has exactly one
   honest meaning -- the size of the text this engine actually DRAWS -- and
   that size is `cssom.layout/default-theme`'s, which is 14. Defaulting to
   16 here was tried first and is what a spec reading argues for; it
   produced a document whose bare `<p>` was given 16px margins around 14px
   text, and 42 of this repo's own layout tests said so immediately. A
   number that disagrees with the renderer beside it is not more correct
   for matching a spec that assumed a different renderer.

   So the number is defined HERE and `cssom.layout/default-theme` reads it,
   rather than the two agreeing by coincidence -- the same rule that put
   the UA stylesheet in one place (ADR-2800003100). A host that draws at
   another size must pass BOTH a `:theme` with its `:font-size` and this
   opt with the same number; passing one without the other is precisely the
   drift this arrangement exists to make visible.

   What it costs a caller who supplies nothing: a document that declares no
   font-size anywhere reports `margin-top: 14px` on a bare `<p>` where a
   browser reports 16. The moment ANY ancestor declares a size -- which
   every real page and every case in the conformance corpus does -- this
   number is replaced by that one and never appears again."
  14)

(def ^:private font-size-scale-step
  "The ratio `font-size: smaller`/`larger` divides/multiplies the parent's
   computed size by. Measured in Brave 151, 2026-08-05, at a size outside
   the absolute-keyword table: `smaller` of 14px is 11.6667 and `larger` of
   14px is 16.8, i.e. exactly 1.2 either way, and a second `smaller` inside
   the first gives 9.72222 -- it compounds off the already-scaled parent."
  1.2)

(def ^:private absolute-length-pattern
  "A whole value that is a single signed number plus `px`, `em` or `rem`.
   Deliberately does NOT admit `ex`/`ch`/`vw`/`vh`/`%` or a `calc()`:
   `ex`/`ch` need font METRICS this namespace has never had, the viewport
   units need a viewport height nobody passes, and a percentage means
   something different on every property (see the block comment above)."
  #"(?i)^([+-]?(?:\d+\.?\d*|\.\d+))(px|em|rem)$")

;; `percentage-pattern` -- the other half of this pair -- is defined up
;; beside `var-ref-pattern`, where the shorthand expanders that also read
;; it can see it.

(defn- parse-number
  [s]
  #?(:clj (try (Double/parseDouble s) (catch Exception _ nil))
     :cljs (let [n (js/parseFloat s)] (when-not (js/isNaN n) n))))

(defn- as-length
  "`n` as a long when it is integral, unchanged otherwise.

   Purely a numeric SHAPE normalisation, and it earns its place: `2em` of
   14 is 28, and `(= 28 28.0)` is FALSE on the JVM. This namespace's own
   `<n>px` coercion has always produced longs (see `parse-style-value`),
   and every reader, test and stored golden downstream compares resolved
   lengths with `=`. A genuinely fractional result -- an `<h3>`'s 16.38, a
   control's 13.3333 -- is left exactly as it is; the whole point of this
   step is that those numbers survive."
  [n]
  (if (and (number? n) (== n (long n))) (long n) n))

(defn- resolve-font-size
  "`v` -- a `font-size` declaration's already-var()-substituted value -- as
   an absolute number of px, or nil when this namespace cannot resolve it.

   `parent-px` is the parent element's computed font size (real CSS's
   reference for `em`, `%`, `smaller` and `larger` ON font-size itself);
   `root-px` is the root element's, which is what `rem` means.

   nil for the ABSOLUTE keywords (`medium`, `small`, `x-large`, ...) on
   purpose rather than for want of a table: the keyword table is keyed on
   the DEFAULT font size of the family in use, which this cascade has no
   way to know. Guessing 16 would be wrong by 3px on every monospace page.
   An unresolved value is left exactly as the author wrote it, which is
   this namespace's posture everywhere else.

   The whole table, measured in Brave 151 on 2026-08-05 so a future fix
   does not have to go and get it -- the SAME page twice, once in its
   `font-family: monospace` (whose default size is 13) and once with
   `font-family: Arial` (16), with every keyword on a `<p>`:

     keyword      monospace page   Arial page (= serif page)
     xx-small           10*             10*
     x-small            10              10
     small              12              13
     medium             13              16
     large              16              18
     x-large            20              24
     xx-large           26              32
     xxx-large          39              48

   Three things read off it. It is not a ratio -- 16/13 is not 18/16, so
   the two columns are two ROWS of a table and not one row scaled. It is
   keyed on the family's default SIZE and not on the family: `Arial` and
   `serif` produce byte-identical columns, and both differ from
   `monospace` only because Chrome's default fixed size is 13 where its
   default proportional size is 16. And the starred entries are the one
   place the reported value and the value `em` resolves against DISAGREE:
   `xx-small` reports 10px in every family while the same element's
   `margin: 1em` measures 9, so the row's own value is 9 and something
   clamps only the reported one. Anything built on this table should
   assert against the margin, not against the reported size.

   What a fix needs beyond the table is a font-family model: the row is
   chosen by the family's default size, so `font-family` has to reach the
   cascade first. This engine's sheet has no font-family rule at all (see
   `ua-stylesheet-text`'s note on the control font, which is the same
   gap). Until then the corpus's own `:text/font-size-absolute-keyword`
   stays divergent -- and its cost is not the font size, which the
   harness excludes as non-absolute, but the UA `p { margin: 1em 0 }`
   underneath it, which then resolves against 14 instead of 16."
  [v parent-px root-px]
  (cond
    (number? v) v
    (not (string? v)) nil
    :else
    (let [s (str/trim v)]
      (some->
       (or (when-let [[_ n unit] (re-matches absolute-length-pattern s)]
             (when-let [n (parse-number n)]
               (case (str/lower-case unit)
                 "px" n
                 "em" (* n parent-px)
                 "rem" (* n root-px))))
           (when-let [[_ n] (re-matches percentage-pattern s)]
             (when-let [n (parse-number n)]
               (* (/ n 100.0) parent-px)))
           (case (str/lower-case s)
             "smaller" (/ parent-px font-size-scale-step)
             "larger" (* parent-px font-size-scale-step)
             nil))
       as-length))))

(defn- resolve-em-length
  "`v` as an absolute number of px when it is a single `<n>em`/`<n>rem`
   length, nil otherwise -- including for a plain `<n>px`, which is left
   alone so this step only ever touches values it actually changes."
  [v own-px root-px]
  (when (string? v)
    (when-let [[_ n unit] (re-matches absolute-length-pattern (str/trim v))]
      (let [unit (str/lower-case unit)]
        (when (not= "px" unit)
          (when-let [n (parse-number n)]
            (as-length (if (= "em" unit) (* n own-px) (* n root-px)))))))))

(def ^:private em-resolvable-properties
  "The properties whose value is a LENGTH, and so whose `em`/`rem` this
   step resolves. Enumerated rather than inferred from the value's shape
   because the shape is ambiguous: a `content: \"2em\"` is a string that
   must survive untouched, and a custom property (`--gap: 1em`) is a raw
   token list whose `em` belongs to whatever substitutes it, not to the
   element that declares it.

   `font-size` is here for completeness but is handled separately by
   `resolve-relative-lengths` -- its own `em` resolves against the PARENT,
   every other one against this element's own computed size."
  #{:font-size :line-height
    :width :height :min-width :max-width :min-height :max-height
    :top :right :bottom :left
    :margin :margin-top :margin-right :margin-bottom :margin-left
    :padding :padding-top :padding-right :padding-bottom :padding-left
    :border-width :border-top-width :border-right-width
    :border-bottom-width :border-left-width :border-radius
    :border-spacing :outline-width :outline-offset
    :box-shadow-x :box-shadow-y :box-shadow-blur :box-shadow-spread
    :text-shadow-x :text-shadow-y :text-shadow-blur
    :gap :row-gap :column-gap :flex-basis :text-indent
    :letter-spacing :word-spacing
    ;; `vertical-align` is the one property here whose value is USUALLY a
    ;; keyword (`sub`/`super`/`middle`/`top`/`bottom`) and only sometimes a
    ;; length. That costs nothing: `resolve-em-length` returns nil for
    ;; anything that is not `<n>em`/`<n>rem`, and this step writes a value
    ;; back only when it resolved, so every keyword survives untouched.
    ;; It is here for `progress`/`meter { vertical-align: -0.2em }`, which
    ;; is the UA declaration behind the 2.797px those two sit BELOW the
    ;; baseline at 14px -- see that rule in `ua-stylesheet-text`.
    :vertical-align})

(defn resolve-relative-lengths
  "Returns `[style computed-font-size]`: `style` with every `em`/`rem`
   length resolved to an absolute number of px, and the element's own
   computed font size (which its children inherit, and which every `em` on
   this element other than `font-size` itself resolved against).

   `parent-font-size` is the parent element's computed size; `root-font-size`
   is the root element's, or nil on the root element itself (where `rem`
   falls back to the element's own size, real CSS's rule for the root).

   `font-size` is written back as a NUMBER whenever it resolved -- a
   computed font size in real CSS is always an absolute length, and every
   downstream reader here already coerces. Every other property is written
   back only when it actually carried an `em`/`rem`, so a plain `10.5px`
   stays the string it has always been and this step has no blast radius
   beyond the values it exists to fix.

   Public because `cssom.layout` needs the same resolution on the ONE path
   that has no cascade behind it (`ua-style-for`, a document nobody
   cascaded) -- the same reason `ua-style-for` itself is public, and the
   same rule against writing this knowledge down twice."
  [style parent-font-size root-font-size]
  (let [resolved-own (resolve-font-size (:font-size style) parent-font-size
                                        (or root-font-size parent-font-size))
        own (or resolved-own parent-font-size)
        root (or root-font-size own)
        style (cond-> style resolved-own (assoc :font-size own))]
    [(reduce-kv (fn [m k v]
                  (if (and (not= :font-size k)
                           (contains? em-resolvable-properties k))
                    (if-let [px (resolve-em-length v own root)]
                      (assoc m k px)
                      m)
                    m))
                style
                style)
     own]))

;; ---- logical -> physical, at computed-value time ----
;;
;; WHERE THIS LIVES AND WHY. `margin-inline-start` becomes `margin-left` in
;; the CASCADE, not in cssom.layout, because that is demonstrably where a
;; browser does it. Measured in Brave 151 over CDP on 2026-08-06, on the
;; corpus's own page:
;;
;;   <div style="max-inline-size: 80px">      getComputedStyle -> maxWidth: 80px
;;   <div style="margin-inline: 20px 60px">   -> marginLeft 20px, marginRight 60px
;;   ...the same, inside direction:rtl        -> marginLeft 60px, marginRight 20px
;;
;; i.e. the PHYSICAL longhand genuinely holds the value by the time
;; `getComputedStyle` can be asked, which is the definition of
;; computed-value time. A layout-time mapping would leave the cascade --
;; and therefore this engine's own `computed-style`, a devtools panel, and
;; a live page's `getComputedStyle` -- reporting nothing at all, exactly
;; the architectural mistake ADR-2800003100 corrected for the UA sheet.
;;
;; WHICH DIRECTION IT READS. The element's OWN computed direction, not its
;; containing block's. Measured: `<div style="width:300px"><div
;; style="direction:rtl; margin-inline:20px 60px">` puts the inner box at
;; x=60 -- the same answer as declaring `direction: rtl` on the PARENT and
;; inheriting it. Both were measured, side by side, because they are
;; indistinguishable in every case where only the parent declares it.
;;
;; HOW IT COMPETES WITH THE PHYSICAL PROPERTY. A logical and a physical
;; longhand that land on the same side are two declarations for ONE slot,
;; resolved by the ordinary cascade -- so within one declaration block,
;; SOURCE ORDER decides. Measured, all four combinations:
;;
;;   margin-left: 5px; margin-inline-start: 40px   -> marginLeft 40px
;;   margin-inline-start: 40px; margin-left: 5px   -> marginLeft  5px
;;   margin: 1px; margin-inline-start: 40px        -> marginLeft 40px (rest 1px)
;;   margin-inline-start: 40px; margin: 1px        -> marginLeft  1px
;;
;; and, decisively, the same first pair under `direction: rtl` gives
;; marginLeft 5px / marginRight 40px in BOTH orders -- because there the
;; two declarations no longer collide. The collision is therefore decided
;; AFTER the rename, not before, which is exactly what `resolve-style-for`
;; does: it renames each declaration in the already-sorted cascade list and
;; lets its existing "last entry of the group wins" step pick between them.
;; Nothing about ordering had to be added.

(def ^:private logical-flow-sides
  "Which physical side each flow-relative side maps to, per writing
   mode + direction. Keyed by `[writing-mode direction]`.

   Every row measured in Brave 151 over CDP on 2026-08-06 with one probe --
   `margin-inline-start: 40px; margin-block-start: 13px` on a box inside a
   300x200 parent, read back through `getComputedStyle`:

   | mode          | dir | 40px lands on | 13px lands on |
   |---------------|-----|---------------|---------------|
   | horizontal-tb | ltr | margin-left   | margin-top    |
   | horizontal-tb | rtl | margin-right  | margin-top    |
   | vertical-rl   | ltr | margin-top    | margin-right  |
   | vertical-rl   | rtl | margin-bottom | margin-right  |
   | vertical-lr   | ltr | margin-top    | margin-left   |
   | sideways-rl   | ltr | margin-top    | margin-right  |
   | sideways-lr   | ltr | margin-bottom | margin-left   |
   | sideways-lr   | rtl | margin-top    | margin-left   |

   -- i.e. `direction` swaps the INLINE pair and never touches the block
   pair, and `sideways-lr` is the one mode whose inline axis runs the other
   way (measured independently: `inline-size: 70px` puts its single word at
   y=63 in a 70-tall box where the other three put it at y=0). The two
   unmeasured rows (`vertical-lr`/`sideways-rl` under rtl) follow that same
   inline swap; nothing else in the table varies.

   THIS TABLE WAS GATED ON `horizontal-tb` UNTIL 2026-08-06, and the reason
   it no longer is, is that the gate's own justification expired. It said
   the rotated rows `would make getComputedStyle right while every box
   stayed laid out horizontally -- a mapping that cannot be checked by
   either layout axis of the conformance corpus`. That was true and was the
   right call at the time. cssom.layout now lays a vertical writing mode out
   in a rotated basis (see its `writing modes` section), so all four rows
   are checked by the geometry axis: `inline-size: 70px; block-size: 20px`
   is a 20x70 box on both sides, and `padding-block-start: 12px` is 12px of
   padding on the box's right edge on both sides."
  {["horizontal-tb" "ltr"] {"inline-start" "left" "inline-end" "right"
                            "block-start" "top" "block-end" "bottom"}
   ["horizontal-tb" "rtl"] {"inline-start" "right" "inline-end" "left"
                            "block-start" "top" "block-end" "bottom"}
   ["vertical-rl" "ltr"]   {"inline-start" "top" "inline-end" "bottom"
                            "block-start" "right" "block-end" "left"}
   ["vertical-rl" "rtl"]   {"inline-start" "bottom" "inline-end" "top"
                            "block-start" "right" "block-end" "left"}
   ["sideways-rl" "ltr"]   {"inline-start" "top" "inline-end" "bottom"
                            "block-start" "right" "block-end" "left"}
   ["sideways-rl" "rtl"]   {"inline-start" "bottom" "inline-end" "top"
                            "block-start" "right" "block-end" "left"}
   ["vertical-lr" "ltr"]   {"inline-start" "top" "inline-end" "bottom"
                            "block-start" "left" "block-end" "right"}
   ["vertical-lr" "rtl"]   {"inline-start" "bottom" "inline-end" "top"
                            "block-start" "left" "block-end" "right"}
   ["sideways-lr" "ltr"]   {"inline-start" "bottom" "inline-end" "top"
                            "block-start" "left" "block-end" "right"}
   ["sideways-lr" "rtl"]   {"inline-start" "top" "inline-end" "bottom"
                            "block-start" "left" "block-end" "right"}})

(def ^:private logical-flow-sizes
  "The six sizing longhands, per writing mode. The inline axis is the
   horizontal one only in `horizontal-tb`; in all four vertical modes it is
   the page's vertical axis, so `inline-size` is a HEIGHT there.

   Measured, inside a 300x200 parent: `inline-size: 70px; block-size: 20px`
   is a box 20 wide and 70 tall under `vertical-rl`, `vertical-lr` and
   `sideways-lr` alike, and 70 wide by 20 tall under `horizontal-tb`.

   `direction` is not a parameter: it reverses an axis, it does not swap
   the two."
  (into {}
        (map (fn [mode]
               (let [vertical? (not= "horizontal-tb" mode)
                     inline (if vertical? "height" "width")
                     block (if vertical? "width" "height")]
                 [mode {:inline-size (keyword inline)
                        :block-size (keyword block)
                        :min-inline-size (keyword (str "min-" inline))
                        :max-inline-size (keyword (str "max-" inline))
                        :min-block-size (keyword (str "min-" block))
                        :max-block-size (keyword (str "max-" block))}])))
        ["horizontal-tb" "vertical-rl" "vertical-lr" "sideways-rl" "sideways-lr"]))

(def ^:private logical->physical-by-flow
  "`{[writing-mode direction] {logical-property physical-property}}` over
   every flow-relative longhand this engine carries: the four sides of
   `margin`/`padding`, the four `inset-*`, the twelve
   `border-<side>-{width,style,color}`, and the six sizing properties.

   Built from `logical-flow-sides` rather than written out, because 34
   hand-written entries per flow would be 34 chances to transpose one --
   and a transposed `inline-end` is a bug no test in this file would
   catch (both sides are real properties, both take the same values)."
  (into {}
        (map (fn [[[mode _ :as flow] sides]]
               [flow
                (into (logical-flow-sizes mode)
                      cat
                      [(for [box ["margin" "padding"]
                             [logical physical] sides]
                         [(keyword (str box "-" logical)) (keyword (str box "-" physical))])
                       (for [[logical physical] sides]
                         [(keyword (str "inset-" logical)) (keyword physical)])
                       (for [[logical physical] sides
                             sub ["width" "style" "color"]]
                         [(keyword (str "border-" logical "-" sub))
                          (keyword (str "border-" physical "-" sub))])])]))
        logical-flow-sides))

(def ^:private initial-flow
  "The flow every element starts in: CSS's own initial `writing-mode` and
   `direction`. Also what a detached subtree, and any caller with no tree
   walk behind it, is styled against."
  {:writing-mode "horizontal-tb" :direction "ltr"})

(defn- flow-keyword
  "Normalises a cascaded `direction`/`writing-mode` value for
   `logical-flow-sides`' key, resolving the CSS-wide keywords against
   `inherited` the way `resolve-css-wide-keyword` would.

   Both properties are INHERITED (see `inherited-properties`), so `inherit`
   and `unset` are the same answer here, and `initial`/`revert` fall to
   `initial-flow` -- the UA stylesheet declares neither, so there is
   nothing for `revert` to roll back TO. `nil` (no declaration on this
   element) inherits, which is the whole reason this value is threaded down
   the walk at all."
  [declared inherited fallback]
  (let [kw (css-wide-keyword declared)]
    (cond
      (nil? declared) (or inherited fallback)
      (contains? #{:inherit :unset} kw) (or inherited fallback)
      (some? kw) fallback
      :else (let [s (str/lower-case (str/trim (str declared)))]
              (if (seq s) s fallback)))))

(defn- resolve-style-and-flow
  "Cascade-resolves the declarations that target `pseudo-element` (nil for
   the real element itself, :before/:after for its generated content) on
   `node`. Mirrors the pre-pseudo-element `computed-style` algorithm exactly
   when `pseudo-element` is nil, so existing behavior is unchanged.

   Cascade layers: each rule-based entry carries its :rule/layer-priority
   (see `parse-rules`) as :layer, and the sort tuple below checks :layer
   *before* :specificity -- so layer membership decides first, specificity
   only breaks ties within the same layer, exactly like real CSS cascade
   layers.

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

   Unlayered-beats-layered is encoded as a \"one past the highest layer
   index\" sentinel riding on :layer's own numeric magnitude -- unlayered
   entries get `(inc max-layer-priority)` as their raw layer value instead
   of a real `:rule/layer-priority`, guaranteeing it sorts after every real
   layer for NORMAL declarations (a plain `(sort-by :layer ...)` already
   picks the highest number). Crucially, this sentinel is subject to the
   *exact same* `(if important? (- raw-layer) raw-layer)` negation every
   real layer value already gets -- there is no separate :unlayered?
   dimension to keep in sync. Negation preserves relative order across the
   WHOLE numeric range uniformly, sentinel included: `-(max+1)` is smaller
   than `-real-layer` for every real layer `<= max`, so for `!important`
   declarations the sentinel sorts *first* (lowest priority) among the
   important group -- meaning an unlayered `!important` declaration
   correctly LOSES to every layered `!important` declaration, exactly
   matching real CSS's own well-documented (if unintuitive) importance-
   reversal behavior: `!important` doesn't just reverse layer order among
   layers, it also flips unlayered from \"wins over everything\" to \"loses
   to everything layered.\" An earlier, since-corrected version of this
   function used a separate :unlayered? boolean compared *before* :layer
   specifically to avoid this reversal (reasoning that the sentinel would
   need its own \"reversal-proofing\") -- that reasoning was itself the bug:
   a plain arithmetic sentinel *already* reverses correctly under the same
   negation every other layer value uses, and the separate-boolean
   approach instead pinned unlayered `!important` as an unconditional
   winner, which both an author's real Chrome/Firefox and the CSS
   Cascading and Inheritance Level 5 spec disagree with. Confirmed via
   direct REPL reproduction before touching source: `@layer a { #hero {
   color: red !important } } @layer b { #hero { color: blue !important }
   } div#hero { color: green !important }` resolved to `green` (unlayered)
   instead of the spec-correct `red` (layer `a`, the earliest-declared
   layer, since importance reverses layer order too, and both layered
   rules beat the unlayered one).

   Inline declarations have no layer concept in real CSS; per the Level 5
   spec they are treated like an unlayered declaration for cascade-layer
   purposes, and (like any unlayered declaration) always win over a layered
   rule-based declaration of the same importance -- but see the paragraph
   above: for `!important` specifically, inline is EXEMPT from the
   unlayered-loses-to-layered reversal precisely because :inline? sits
   *above* :layer in the sort tuple, so once :important? ties, a difference
   in :inline? decides the comparison and :layer (sentinel value and its
   negation both) is never consulted at all -- inline's own :layer value is
   inert for correctness (set to the stylesheet's highest resolved layer
   priority, purely for documentation). This matches real CSS: an inline
   `style=\"... !important\"` declaration beats even a layered `!important`
   rule, unlike a plain unlayered `!important` rule-based declaration.

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
   before container widths are even known).

   The CSS-wide keywords -- `inherit`, `initial`, `unset`, `revert` (see
   `css-wide-keywords` for the browser measurements that separate them,
   and `resolve-css-wide-keyword` for the resolution): a winning
   declaration whose value is one of the four never reaches the resulting
   map as the literal string. Storing it verbatim was a real bug -- an
   extremely common author idiom, `color: inherit`, silently rendered
   fully transparent/invisible text downstream, since no color parser
   recognizes the word \"inherit\" as a color -- and the same was true of
   the other three until 2026-08-05.

   `inherit` reads the PARENT's resolved value straight off its
   `:style/*` attrs (`parent-computed-value`), which works for a
   non-inherited property (`padding-left: inherit` under a
   `padding-left: 40px` parent now reports 40, where dropping it reported
   0 -- measured against Brave, `:cascade/inherit-on-a-non-inherited-
   property`) as well as for an inherited one. When the parent resolved
   nothing, the property is dropped instead, which lets the SAME
   already-existing `(or (:prop st) (:prop inherited))` fallback
   `cssom.layout` applies for genuinely-inherited properties do the real
   inheriting at layout time -- and is also, for a non-inherited property,
   exactly the initial value the parent itself computed.

   `initial` WRITES the property's initial value (`initial-values`) rather
   than dropping it, because dropping would leave the user-agent
   stylesheet's value standing: a `<p style=\"display: initial\">` reports
   `inline` in a browser, not the UA's `block`. `unset` is `inherit` on an
   inherited property (`inherited-properties`) and `initial` on every
   other. `revert` rolls the cascade back to the previous ORIGIN, which is
   why the entries below the winner are kept rather than discarded.

   `inherited-flow` -- the 7-arity form's last argument, and the reason the
   logical-to-physical rename can happen here at all: `{:writing-mode ...
   :direction ...}` as the PARENT resolved them, threaded down
   `run-cascade-walk` exactly like `parent-font-size` and `parent-display`
   already are, because both properties inherit and this element may not
   declare either. nil means \"no tree walk behind this call\" (every
   standalone `computed-style`/`pseudo-element-style-for` caller), which
   resolves to `initial-flow` -- the same honest simplification the
   `parent-display` argument already makes for blockification, and with the
   same consequence: a standalone `computed-style` on an element inside a
   `direction: rtl` ancestor maps its logical properties as if it were ltr.

   The rename itself is one `map` over `sorted`, BELOW the sort and ABOVE
   the group-by, and that position is the whole design -- see the
   `logical -> physical, at computed-value time` block above for the four
   declaration-order measurements it reproduces without any code of its
   own.

   Returns `[style flow]` -- the resolved map, and the element's own
   resolved `{:writing-mode :direction}`, which its children inherit.
   `resolve-style-for` is the same function without the second value, and
   is what every caller that does not walk a tree uses."
  ([document rules node pseudo-element counters container-ctx inherited-flow]
   (let [max-layer-priority (or (some->> rules (keep :rule/layer-priority) seq (apply max)) 0)
         declarations (for [rule rules
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
                              raw-layer (if (nil? layer) (inc max-layer-priority) (or layer-priority 0))]
                          {:property property
                           :value value
                           :important? important?
                           :origin author-origin
                           :specificity (specificity selector)
                           :inline? false
                           :layer (if important? (- raw-layer) raw-layer)
                           :order order}))
         ;; The USER-AGENT origin, at the bottom of the cascade: every
         ;; author declaration of the same importance beats it, which is
         ;; the whole of what "UA stylesheet" means and is why :origin sits
         ;; between :important? and :inline? in the sort tuple below.
         ;;
         ;; Matched per PSEUDO-ELEMENT, exactly like the author rules
         ;; above: the sheet's `q::before { content: open-quote }` must
         ;; reach a ::before resolution and must NOT reach the element's
         ;; own. It used to be skipped for pseudo-elements outright, which
         ;; was correct only while the sheet had no ::before/::after rule
         ;; in it. `li::marker` -- a real UA sheet's other one -- is still
         ;; not here, because this engine has no marker box to style.
         ua-declarations (for [rule (ua-rules-for node)
                                 selector (:rule/selectors rule)
                                 :when (= pseudo-element (pseudo-element-of selector))
                                 ;; Same no-document rule as `ua-style-of`,
                                 ;; and for the same reason: `matches?`'s
                                 ;; 1-arity tests only the SUBJECT compound,
                                 ;; which would let `ul ul` zero the margins
                                 ;; of a top-level list. apply-cascade always
                                 ;; has a document; `computed-style`'s
                                 ;; 2-arity does not, and honestly answers
                                 ;; without the ancestor-dependent rules.
                                 :when (if document
                                         (matches? document node selector)
                                         (and (<= (count (:selector/parts selector [selector])) 1)
                                              (matches? node selector)))
                                 [property value] (:rule/declarations rule)]
                             {:property property
                              :value value
                              ;; No UA declaration here is `!important`, so
                              ;; real CSS's importance REVERSAL (a UA
                              ;; `!important` outranks an author one) never
                              ;; arises and is deliberately not modelled:
                              ;; :important? sorts before :origin, so an
                              ;; author `!important` wins here, which is
                              ;; correct for every rule this sheet has.
                              :important? false
                              :origin ua-origin
                              :specificity (specificity selector)
                              :inline? false
                              :layer 0
                              :order (:rule/order rule)})
         node-inline-importance (inline-style-importance node)
         inline-declarations (when (nil? pseudo-element)
                                (map-indexed (fn [idx [property value]]
                                               {:property property
                                                :value value
                                                :important? (contains? node-inline-importance property)
                                                :origin author-origin
                                                :specificity [1 0 0]
                                                :inline? true
                                                :layer max-layer-priority
                                                :order idx})
                                             (inline-style node)))
         sorted (sort-by (juxt :important? :origin :inline? :layer :specificity :order)
                         (concat ua-declarations declarations inline-declarations))
         ;; ---- logical -> physical ----
         ;; This element's OWN flow, resolved from this same cascade: the
         ;; last-sorted `writing-mode`/`direction` declaration is that
         ;; property's winner by exactly the rule the group-by below uses,
         ;; and neither property has a logical form, so neither can depend
         ;; on the rename it decides.
         flow (let [win (fn [prop]
                          (some->> sorted (filter #(= prop (:property %))) last :value))]
                {:writing-mode (flow-keyword (win :writing-mode)
                                             (:writing-mode inherited-flow)
                                             (:writing-mode initial-flow))
                 :direction (flow-keyword (win :direction)
                                          (:direction inherited-flow)
                                          (:direction initial-flow))})
         ;; nil only for a flow this table has no row for, which after
         ;; 2026-08-06 means an unrecognised `writing-mode` keyword. A
         ;; logical declaration then stays under its logical key, which
         ;; nothing reads -- the same safe non-answer the vertical modes
         ;; got while `logical-flow-sides` was gated on `horizontal-tb`.
         rename (get logical->physical-by-flow
                     [(:writing-mode flow) (:direction flow)])
         sorted (if rename
                  (map #(if-let [physical (get rename (:property %))]
                          (assoc % :property physical)
                          %)
                       sorted)
                  sorted)
         ;; Grouped per property rather than reduced straight into a map,
         ;; because `revert` needs the LOSING entries too -- it rolls the
         ;; cascade back to the previous origin rather than to nothing.
         ;; `sort-by` is stable and `group-by` preserves input order within
         ;; each group, so the last entry of each group is the same winner
         ;; the old straight reduce ended on.
         m (reduce-kv
            (fn [m property entries]
              (let [{:keys [value] :as winner} (peek entries)]
                (if-let [kind (css-wide-keyword value)]
                  (let [resolved (resolve-css-wide-keyword
                                  document node property kind
                                  (when (= :revert kind)
                                    (filterv #(< (:origin %) (:origin winner)) entries)))]
                    (if (= drop-declaration resolved)
                      m
                      (assoc m property resolved)))
                  (assoc m property value))))
            {}
            (group-by :property sorted))]
     [(if (contains? m :content)
        (let [resolved (resolve-content-value node counters (:content m))]
          (if (nil? resolved)
            (dissoc m :content)
            (assoc m :content resolved)))
        m)
      flow])))

(defn- resolve-style-for
  "`resolve-style-and-flow`'s resolved style map alone, for every caller
   that has no tree walk to hand the element's own flow back to."
  ([document rules node pseudo-element]
   (resolve-style-for document rules node pseudo-element nil nil))
  ([document rules node pseudo-element counters]
   (resolve-style-for document rules node pseudo-element counters nil))
  ([document rules node pseudo-element counters container-ctx]
   (resolve-style-for document rules node pseudo-element counters container-ctx nil))
  ([document rules node pseudo-element counters container-ctx inherited-flow]
   (first (resolve-style-and-flow document rules node pseudo-element counters
                                  container-ctx inherited-flow))))

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

;; `var-ref-pattern` (the `var(--name[, fallback])` regex the three
;; functions below read) is defined much higher up, next to the shorthand
;; expanders -- `expand-box-side-shorthand` needs the same pattern to
;; recognize a `padding: var(--pad)` shorthand as expandable, and one
;; shared def is the point.

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

(def ^:private box-side-key-pattern
  #"(?:margin|padding)(?:-(top|right|bottom|left))?")

(def ^:private box-side-order
  "Side name -> its index in `box-side-picks`' clockwise order. The uniform
   `margin`/`padding` key itself has no side and takes index 0, i.e. the
   first written value -- the same value `expand-box-side-shorthand` puts
   there."
  {"top" 0 "right" 1 "bottom" 2 "left" 3})

(defn- reslice-substituted-box-shorthands
  "Re-slices a `margin`/`padding` key (uniform or per-side) whose value is
   STILL a multi-value box shorthand after custom-property substitution.

   `expand-box-side-shorthand` runs at declaration-parse time, which is what
   keeps the cascade honest (a later `padding-left` must beat an earlier
   `padding`, and vice versa) -- but it therefore hands each side the var()
   reference verbatim, not the substituted text. That is exact whenever the
   custom property holds ONE value (`--pad: 20px`, the overwhelmingly common
   form). When it holds a whole box shorthand (`--pad: 4px 8px`), every side
   would instead be left holding the entire string `4px 8px`, so the 1-to-4
   rule is applied here, to the text substitution actually produced.

   Deliberately narrow, so this cannot become a second, order-blind
   expansion path: it only rewrites keys that are already margin/padding
   (uniform or one of the four sides), only when the value is still a
   STRING (a declaration that expanded normally is already a number and is
   never revisited), and only when every top-level token is a length this
   engine can resolve. `margin: 0 auto` and any other multi-token value with
   a non-length in it is left exactly as it was."
  [m]
  (reduce-kv
   (fn [acc k v]
     (if-let [[_ side] (and (string? v) (re-matches box-side-key-pattern (name k)))]
       (let [tokens (box-shorthand-tokens v)
             n (count tokens)]
         (if (and (> n 1) (<= n 4)
                  (every? #(or (re-matches #"-?\d+(px)?" %)
                               (re-matches calc-pattern %))
                          tokens))
           (let [idx (get box-side-order side 0)]
             (assoc acc k (parse-style-value (tokens (nth (get box-side-picks n) idx)))))
           acc))
       acc))
   m
   m))

(defn- resolve-style-map
  [env m]
  (reslice-substituted-box-shorthands
   (into {} (map (fn [[k v]] [k (resolve-value env v)])) m)))

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
   real containers/parent-index map during its second pass.

   `inherited-flow` is threaded the same way, and reaches the ::before/
   ::after resolutions too: a generated box sits in its originating
   element's flow, so `content` beside a `margin-inline-start` on the same
   ::before maps against the same direction the element itself resolved."
  ([document rules node inherited-counters container-ctx]
   (style-with-counters document rules node inherited-counters container-ctx nil))
  ([document rules node inherited-counters container-ctx inherited-flow]
   (let [[base node-flow] (resolve-style-and-flow document rules node nil inherited-counters
                                                  container-ctx inherited-flow)
        node-counters (-> inherited-counters
                          (apply-counter-pairs :reset (:counter-reset base))
                          (apply-counter-pairs :increment (:counter-increment base)))
        ;; The generated boxes sit in THIS element's flow, not its
        ;; parent's -- a `::before` of a `direction: rtl` element has its
        ;; own `margin-inline-start` on the right even though the element
        ;; declared the direction itself.
        before (resolve-style-for document rules node :before node-counters container-ctx node-flow)
        after (resolve-style-for document rules node :after node-counters container-ctx node-flow)
        style (cond-> base
                (seq before) (assoc :pseudo/before before)
                (seq after) (assoc :pseudo/after after))]
    [style node-counters node-flow])))

(def ^:private current-color-keys
  "The color-valued properties (other than `color` itself) this namespace
   threads onto `:style/*` attrs that real CSS lets take the `currentColor`
   keyword."
  ;; The four per-side border colours are here because a per-side
  ;; SHORTHAND writes one whether or not the author named a colour:
  ;; `border-top: 10px solid` resets `border-top-color` to its initial
  ;; `currentcolor` (see `border-shorthand-initials`), so leaving them out
  ;; would hand cssom.layout the literal keyword to paint with.
  #{:border-color :border-top-color :border-right-color
    :border-bottom-color :border-left-color
    :box-shadow-color :outline-color :text-shadow-color})

(defn- resolve-current-color
  "Real CSS: the `currentColor` keyword, used in any color-valued property
   other than `color` itself, resolves to that same element's own computed
   `color` value. Resolved here (not in cssom.layout) because this is the
   single place that writes the canonical `:style/<prop>` attrs both
   cssom.layout's rendering AND a live page's `getComputedStyle()` read --
   fixing it here fixes both, whereas fixing it only in cssom.layout would
   leave getComputedStyle() seeing the literal, unresolved string.

   Only resolves against a `color` this same element explicitly declares
   in `final-style` -- `color` resolving its OWN currentColor against an
   ancestor's INHERITED color is a rarer, more complex circular-inheritance
   case this namespace has no inheritance machinery for at all (ordinary
   property inheritance only happens later, in cssom.layout's rendering
   pass -- see apply-cascade's docstring). An honest scope-cut: if this
   element has no own `:color`, the keyword is left as-is rather than
   silently resolved to nil."
  [final-style]
  (let [color (:color final-style)]
    (if-not color
      final-style
      (reduce (fn [m k]
                (if (and (contains? m k) (= "currentcolor" (str/lower-case (str (get m k)))))
                  (assoc m k color)
                  m))
              final-style
              current-color-keys))))

(def ^:private initial-display
  "CSS's own initial value for `display`, which every element with no
   declaration of its own (author or UA -- see `ua-stylesheet-text`, which
   names the block-level, list-item, table and form-control tags and stops
   there) computes to. `<span>`, `<a>`, `<label>`, `<strong>`, `<img>` and
   the rest of the inline tags reach it by never being mentioned."
  "inline")

(def ^:private blockified-displays
  "CSS Display 3 SS2.7 `blockify`: the computed-value-time rewrite of a box's
   OUTER display type, for a box that must be block-level in its parent's
   formatting context. Anything absent is already block-level (`block`,
   `flex`, `grid`, `table`, `flow-root`) or is not a box at all (`none`,
   `contents`), and keeps the value it has.

   Measured in Brave 151 on 2026-08-05 by reading `getComputedStyle` on a
   span carrying each value in three positions -- inside a
   `<div style=\"display:flex\">`, `float: left`, and `position: absolute`
   -- all three of which produced the SAME table, which is what makes it
   one rewrite rather than three:

     inline -> block          inline-flex  -> flex
     inline-block -> block    inline-grid  -> grid
     table-cell -> block      inline-table -> table
     table-row -> block

   And what deliberately does NOT move, measured the same way: `list-item`
   stays `list-item` (an `<li>` in a flex row still reports it), `contents`
   stays `contents`, `none` stays `none`, `flow-root` stays `flow-root`.

   `ruby` is left alone rather than mapped: Brave reports `block ruby`, a
   two-keyword display whose outer half is blockified and whose inner half
   is not, and this engine has no ruby formatting context to spend the
   distinction on."
  {"inline" "block"
   "inline-block" "block"
   "inline-flex" "flex"
   "inline-grid" "grid"
   "inline-table" "table"
   "table-cell" "block"
   "table-row" "block"})

(def ^:private flex-or-grid-container-displays
  "The `display` values whose IN-FLOW children are flex or grid items, and
   are therefore blockified. Both spellings of each, because an
   `inline-flex` container that is ITSELF blockified reads as `flex` while
   one in a line box still reads as `inline-flex`, and its children are
   items either way."
  #{"flex" "inline-flex" "grid" "inline-grid"})

(defn- display-token
  "A `display`/`float`/`position` value normalised for comparison: the
   cascade stores what the author wrote, so `Inline-Block` and
   ` absolute ` have to answer the same as their lower-case selves."
  [v]
  (when (some? v)
    (let [s (str/lower-case (str/trim (str v)))]
      (when (seq s) s))))

(defn- blockified?
  "Whether this box is one of the three CSS Display 3 SS2.7 calls for a
   blockified `display`: a float, an absolutely positioned box, or a flex/
   grid item.

   `position: sticky` and `position: relative` are NOT among them --
   measured, a `position: sticky` span still reports `display: inline` --
   which is why this tests the two out-of-flow positions by name rather
   than testing for `static`."
  [style parent-display]
  (let [pos (display-token (:position style))
        flt (display-token (:float style))]
    (boolean
     (or (contains? #{"absolute" "fixed"} pos)
         (and flt (not= "none" flt))
         (contains? flex-or-grid-container-displays parent-display)))))

(defn- blockify-display
  "`style` with its `display` rewritten when this box is blockified, and
   unchanged otherwise.

   Writes the value even when the element declared no `display` at all --
   a bare `<span>` in a flex row has to come out `block`, and the only
   thing that says `inline` today is the absence of a declaration. That
   absence is exactly what `initial-display` supplies."
  [style parent-display]
  (let [d (or (display-token (:display style)) initial-display)]
    (if-let [b (and (blockified? style parent-display)
                    (get blockified-displays d))]
      (assoc style :display b)
      style)))

(defn- children-container-display
  "The `display` this element's children should be blockified against --
   its OWN computed display, except that a `display: contents` box
   generates no box at all, so its children are laid out by whatever
   formatting context this element itself sits in. Passing this element's
   `contents` down instead would make a
   `<div style=\"display:flex\"><span style=\"display:contents\">` hide the
   flex container from the spans that really are its items."
  [style parent-display]
  (let [d (or (display-token (:display style)) initial-display)]
    (if (= "contents" d) parent-display d)))

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
   docstring).

   `parent-font-size`/`root-font-size` are the third thing this walk
   inherits, alongside the custom-property environment and the running
   counters: `em` resolves against the element's OWN computed font size,
   which is its declared/UA one resolved against the PARENT's, so the
   chain has to come down the tree the same way (see
   `resolve-relative-lengths`). The extra return value is this element's
   own computed size, which its children inherit. Resolution runs AFTER
   `resolve-style-map` because a `font-size: var(--x)` is not a length
   until the substitution has happened, and it covers the pseudo-element
   maps too, against this element's size -- a `::before`'s `em` is its
   own font size, which it inherits from the element it hangs off.

   `parent-display` is the fourth thing this walk inherits, and it is
   inherited for exactly one reason: blockification (CSS Display 3 SS2.7,
   see `blockify-display`) rewrites a box's own `display` when its PARENT
   establishes a flex or grid formatting context, so the answer for this
   element is not derivable from this element. The extra return value is
   what this element's own children should be blockified against, which is
   its own computed display except for `display: contents` -- see
   `children-container-display`.

   `parent-flow` is the fifth, and it is inherited because BOTH of its
   halves are inherited CSS properties: an element with no `direction` of
   its own is in its parent's, and that is what decides which physical side
   its `margin-inline-start` lands on (see `logical->physical-by-flow`).
   The extra return value is this element's own resolved flow, which its
   children inherit -- computed in `resolve-style-for`, where the cascade
   that decides it already ran, rather than re-derived here from the
   written-back `:style/direction` (which would be wrong for an element
   that declares none, since this map holds no inherited values)."
  [document rules node-id inherited-env inherited-counters container-ctx
   parent-font-size root-font-size parent-display parent-quote-depth parent-flow]
  (let [node (get-in document [:nodes node-id])
        [style node-counters node-flow]
        (style-with-counters document rules node inherited-counters container-ctx parent-flow)
        pseudo-keys #{:pseudo/before :pseudo/after}
        regular (into {} (remove (fn [[k _]] (contains? pseudo-keys k))) style)
        pseudo (select-keys style pseudo-keys)
        custom (into {} (filter (fn [[k _]] (custom-property? k))) regular)
        normal (into {} (remove (fn [[k _]] (custom-property? k))) regular)
        resolved-custom (into {} (map (fn [[k v]] [k (resolve-value inherited-env v)])) custom)
        node-env (merge inherited-env resolved-custom)
        [resolved-normal node-font-size]
        (resolve-relative-lengths (resolve-style-map node-env normal)
                                  parent-font-size root-font-size)
        resolved-pseudo (into {}
                              (map (fn [[k v]]
                                     [k (first (resolve-relative-lengths
                                                (resolve-style-map node-env v)
                                                node-font-size
                                                (or root-font-size node-font-size)))]))
                              pseudo)
        [final-style child-quote-depth]
        (-> (merge resolved-custom resolved-normal resolved-pseudo)
            resolve-current-color
            (blockify-display parent-display)
            (resolve-quote-content (or parent-quote-depth 0)))
        document (reduce-kv
                  (fn [d k v]
                    (if (contains? pseudo-keys k)
                      (dom/set-attribute d node-id k v)
                      (dom/set-attribute d node-id (keyword "style" (name k)) v)))
                  (clear-style-attrs document node-id)
                  final-style)]
    [document node-env node-counters node-font-size
     (children-container-display final-style parent-display)
     child-quote-depth node-flow]))

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
   use `@container` see no behavior or performance change.

   `base-font-size` is what the ROOT element's own relative units resolve
   against (see `apply-cascade`'s `:base-font-size`). `root-font-size`
   starts nil and is fixed by the FIRST element styled -- the root element,
   in document order -- to that element's own computed size, which is
   exactly what `rem` means for everything below it.

   A subtree unreachable from `:root` is styled with `base-font-size` as
   both its parent's and the root's size, the same honest simplification
   the empty inherited environment above it already makes: a detached node
   has no ancestor chain to read either number off."
  [document rules container-ctx base-font-size]
  (letfn [(walk [document node-id inherited-env inherited-counters visited
                 parent-font-size root-font-size parent-display quote-depth parent-flow]
            (let [node (get-in document [:nodes node-id])
                  element? (= :element (:node/type node))
                  [document node-env node-counters node-font-size node-display
                   node-quote-depth node-flow]
                  (if element?
                    (style-element document rules node-id inherited-env inherited-counters
                                   container-ctx parent-font-size root-font-size
                                   parent-display quote-depth parent-flow)
                    ;; a text node establishes no formatting context of its
                    ;; own, so its (impossible) children would still be
                    ;; blockified against this node's parent -- and it
                    ;; generates no quote, so the depth passes straight
                    ;; through it -- and it is in its parent's flow, since
                    ;; both halves of a flow are inherited properties
                    [document inherited-env inherited-counters parent-font-size parent-display
                     quote-depth parent-flow])
                  root-font-size (if element? (or root-font-size node-font-size) root-font-size)
                  visited (conj visited node-id)]
              (reduce (fn [[document visited counters] child-id]
                        (walk document child-id node-env counters visited
                              node-font-size root-font-size node-display node-quote-depth
                              node-flow))
                      [document visited node-counters]
                      (:children node))))]
    (let [[document visited] (if-let [root (:root document)]
                                (walk document root {} {} #{} base-font-size nil nil 0 initial-flow)
                                [document #{}])]
      (reduce-kv
       (fn [document node-id node]
         (if (and (= :element (:node/type node)) (not (contains? visited node-id)))
           ;; a detached subtree has no ancestor chain to read a container
           ;; display off either -- the same honest simplification the empty
           ;; inherited environment above it already makes
           ;; a detached subtree has no ancestor chain to read a quote
           ;; depth off either -- it starts at 0, the same honest
           ;; simplification as the two above
           ;; a detached subtree has no ancestor chain to read a FLOW off
           ;; either -- it starts in `initial-flow`, same simplification
           (first (style-element document rules node-id {} {} container-ctx
                                 base-font-size base-font-size nil 0 initial-flow))
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
   / `(max-width:...)` rule blocks apply, :color-scheme (default
   `default-color-scheme`, \"light\"/\"dark\") used to decide whether
   `@media (prefers-color-scheme: ...)` rule blocks apply, and
   :base-font-size (default `default-base-font-size`, 16).

   :base-font-size is what the ROOT element's own `em`/`%`/`smaller` resolve
   against, and what `rem` means in a document whose root declares no
   font-size. It is one number and it is only ever the START of the chain:
   the moment any element declares a font-size, its own subtree resolves
   against THAT (see `resolve-relative-lengths`), which is why this is an
   opt rather than the design -- a single base size cannot express `em`
   compounding, and compounding is what a browser does. A caller that
   supplies nothing gets `default-base-font-size`, the size this engine's
   own theme draws at; see that def for what it costs and why it is not
   CSS's 16.

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
         base-font-size (or (:base-font-size opts) default-base-font-size)
         rules (filterv #(rule-applies-to-viewport? % viewport-width color-scheme) rules)]
     (if (some :rule/container rules)
       (let [pass1-rules (filterv #(nil? (:rule/container %)) rules)
             pass1-document (run-cascade-walk document pass1-rules nil base-font-size)
             container-ctx {:containers (build-containers pass1-document)
                            :parent-index (parent-index pass1-document)}]
         (run-cascade-walk document rules container-ctx base-font-size))
       (run-cascade-walk document rules nil base-font-size)))))

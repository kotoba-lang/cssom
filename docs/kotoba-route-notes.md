# Kotoba route conversion notes (cssom.core)

Status: the typed (:kotoba) route of `src/cssom/core.cljk` is converted
through the scanners, shorthand expanders, attribute/var references and
the selector cluster. The style pipeline is gated behind explicit GAPs.

## Pipeline GAPs (removal conditions)

| GAP | stub | removal condition |
|---|---|---|
| scan-conditional-at-rules | empty seq | the string scan lands |
| parse-rules | nil | the scanner-driven parser lands |
| resolve-style-and-flow | document | parse-rules + delay-free scope walk |
| style-element | [document nil nil] | resolve-style-and-flow answers |
| run-cascade-walk | document | style-element answers |
| apply-cascade (2 arities) | document | parse-rules + run-cascade-walk |
| calc() cluster | raw string | the typed calc evaluator |

## Closed-profile rules measured here (amu `--jvm-free`)

- `[]` literal = `[:vector :i64]`; build typed lists with
  `(typed-list-new [:list T] item ...)`, append with
  `(typed-list-conj [:list T] xs x)` (NOT `typed-list-append`).
- `#{...}` literal is ALWAYS `[:set :keyword]`; other item types need
  `(typed-set-new [:set T] ...)` (~110 string-set sites remain).
- `count` on strings is banned (ADR 0353): `string-byte-length` /
  `string-code-unit-count`; on typed containers use `vector-count`.
- `subs`/`nth` on strings/pairs return options: use `string-substring`
  and `hetero-vector-at` / `typed-list-nth`.
- loop bindings are strictly i64/homo: bool flags become 1/0;
  quote-style state is an empty-string sentinel; never bind bare `nil`
  and never pass an `if` expression directly as a `recur` argument
  (hoist to a `let`).
- An untyped helper called inside a `recur` argument poisons the loop
  binding inference (reported as "binding is i64"); annotate helpers.
- `[]`-literal tuples desugar by context (`desugar-list`): a list
  context yields `typed-list-new`, otherwise `hetero-vector-new`.
- fn parameters splice as `[#?@(:kotoba [...] :default [...])]`; the
  return annotation is a standalone `#?@(:kotoba [T])`.
- `[:vector-i64]` is outside the profile: `[:vector [:i64 :i64 :i64]]`.
- Global regex sweeps MUST be scope-checked: a `(nth s N)` sweep matched
  the variable name `s` in 30 sites where `s` was a string (restored) or
  a specificity triple (restored) -- the two accessor sweeps then fought
  each other. Audit each sweep's variable bindings after running it.
- Diagnostic spans can be misattributed (a rename test proved the
  reported fn was not the failing one); verify by experiment.

## Stub-elimination log (loop-exit-type)

Ruled out by whole-fn stubbing (message persisted): css-chunks,
find-matching-brace, selector-function-spans, selector-attribute-spans,
selector-pseudo-element-spans, selector-remove-spans,
selector-outer-spans, selector-simple-parts, selector-parts;
plus earlier: balanced-paren-group, parse-simple-selector(:kotoba),
attr-reference-at, env-reference-at, split-first-on,
split-css-call-arguments, split-content-terms, next-conditional-at-rule,
layer-name-path. All three libs (bounded_regex, text, string.join) check
green standalone. The `[]`-init/conj loop class is now fully typed on
the :kotoba route (media-range-tokens, split-top-level(-word),
parse-counter-property, amp-positions); remaining `[]`/`conj` sites are
:default-only (reader-stripped on --jvm-free).

## Tuple scan (full-file, 2+ element literals)

943 tuples inventoried: all return-position tuples are either
:default-only (calc cluster is dual-stubbed), docstrings, let bindings,
or typed/annotated (calc-name-parts [:string :string], nth-of-clause
[:string :option :string], env/attr-reference [i64 name fallback]-ish).
The [:i64 :option-i64] pair remains unlocated -- the leading theory is
a desugar-emitted loop (mapv/keep/into over a tuple-producing fn) whose
printed forms are truncated in the diagnostic.

## :fn (callable) parameter recipe -- probed 2026-10-20

- `[:fn [[params] result] ...]` clauses exist; an optional `{:effects #{...}}`
  row trails the clauses.
- A parameter type is admitted exactly when it is admitted as a closure
  RESULT type (closure-flat-result-types plus records/options/results).
- Measured: `:i64`, `:document` (builtin flat) and inline qualified
  records with FEW fields pass; a TOP-LEVEL `[:ref ...]` parameter is
  refused (`callable-parameter-type`); an inline record with many fields
  and nested refs (the 14-field :cssom/compound) exceeds the VALUE-TYPE
  NODE LIMIT. So a :fn param can name a small record but not the big
  compound -- the fn-value pattern (match-fn plumbing) cannot be fully
  typed this way and needs either a compact shadow record or direct
  calls instead of fn values.

## Keyword-set contains? sites (4 remaining)

`(contains? #{:a :b} x)` sites at 8148/9180/9334/11137 need the same
typed-map conversion as the string sets -- an automated regex attempt
broke the reader (unmatched paren), so convert these FOUR by hand:
`(contains? #?(:kotoba (typed-map-new [:map :keyword :bool] :a true :b true) :default #{:a :b}) x)`.

## map-presence :i64 receiver -- identified 2026-10-20

The failing contains? receiver is the variable `result` (sema message
patched with recv=). Source: the SHARED (non-split) shorthand expander
reducers -- expand-text-shadow-shorthand (2598), the box-shadow twin
(2665) and the outline twin (2721) use `(reduce (fn [result tok] ...))`
where the fn-literal parameter types poison to :i64. Fix: route-split
each into a typed loop fold (the established pattern); note their
result maps mix :string and RVS values ({:x-color "none"} vs
(parse-style-value ...)) -- the map value type needs the union carrier
or a string-normalized representation.

## RVS resolves the shadow-shorthand value union

The text/box-shadow and outline expanders' result maps mix `{:x-color
"none"}` strings with parse-style-value RVS values. The fix is to store
"none" as `(result-err-of [:result :f64 :string] "none")` -- the RVS
model's err-case IS a raw string, so the map type becomes uniformly
[:map :keyword [:result :f64 :string]] with no union. The typed loop
fold then replaces the shared (reduce (fn [result tok] ...)) reducers.

## Known remaining wall

`:kotoba.error/loop-exit-type` (no span): one loop exits with a
`[:vector [:i64 :option-i64]]` tuple literal and a `[:list :string]`
whose first item is a module call. Ruled out by elimination:
balanced-paren-group, parse-simple-selector(:kotoba), split-first-on,
split-css-call-arguments, split-content-terms, selector span scanners,
attr-reference-at, next-conditional-at-rule, layer-name-path; the
bounded-regex / text libs check green standalone. This wall gates both
core and layout (layout requires core).

## parse-rules rewrite sketch (next implementation step)

Scanner-driven, no regex literals:
1. `css-chunks` segmentation (already scanner-based) -> segment list.
2. Per rule segment: `selector-tokens` -> `parse-selector` ->
   record `:cssom/compound` (schemas in the ns form).
3. Declaration block: `split-value-components` on `;` at depth 0 ->
   `parse-property` + `parse-property-value` (the RVS model:
   `[:result :f64 :string]`, ok = coerced number, err = raw text).
4. Emit `:cssom/rule` records (extend the minimal schema as fields are
   needed) into a typed list; then the cascade resolver un-GAPs.

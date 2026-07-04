# kotoba-lang/cssom

CSS Object Model for kotoba documents: selector parsing, cascade resolution,
and the reference box-model/layout projection to renderer draw ops.

Split out of `kotoba-lang/browser` (ADR-2607041700), where it lived as
`browser.css` (cascade) and, via `kotoba-lang/wasm-ui`'s
`kotoba.wasm.layout`, the layout projection. Both now live together here as
`cssom.core` and `cssom.layout`, since cascade and layout are one cohesive
"CSS engine" concern.

**Not to be confused with `kotoba-lang/css`**, an unrelated EDN-as-CSS-data
renderer (`{:rules [...]}` -> CSS text, Hiccup-style). This repo goes the
other direction: it parses CSS text and HTML-derived documents, not the
reverse.

- `cssom.core`: selector tokenizing/parsing (tag/id/class/attribute
  operators/pseudo-classes), specificity, cascade resolution
  (`apply-cascade`) against a `kotoba.wasm.dom` document.
- `cssom.layout`: reference projection from a `kotoba.wasm.dom` tree to
  renderer draw ops (rects/text/semantic node ops). Documented in its own
  docstring as a *reference* implementation — vertical block-stacking with
  padding/gap only. It does not implement `display: flex`, `position:
  absolute/relative`, min/max-width, border-box sizing, or border draw-ops;
  real hosts (or a future revision of this repo) can replace it with a full
  box-model/flexbox engine while keeping the same `draw-ops` data boundary.

## Maturity

| | |
|---|---|
| Role | ui-substrate |
| Tests | `clojure -M:test` |
| Flexbox / position / box-model layout | not implemented (see `cssom.layout` docstring) |

## Test

```bash
clojure -M:test
```

# Rust Language Binding — Implementation Summary

Added Rust as a fifth language binding, following the same `language.yaml`-driven
generic engine used by C++/Java/Python/C#.

## New: `templates/languages/rust/`

- `language.yaml`, `macros.vm`, and templates for struct/enum/interface/union/const/template_inst
- A static `face_error.rs` (FACE `BAD_PARAM` / `DATA_CONVERSION` as a `Result` error type)
- A `mod.rs.vm` post-file template for wiring up the crate's module tree

Full design rationale is in [`docs/rust-binding-guide.md`](docs/rust-binding-guide.md).

## Design highlights

- **Structs** are plain value types (`Debug, Clone, PartialEq`) — no C#-style
  copy-constructor footgun; ownership and the borrow checker rule out aliasing bugs.
- **Unions** map to real Rust tagged enums — more idiomatic here than in any other
  supported language (C# explicitly can't map them at all).
- **Interfaces**: single-op → boxed `FnMut` closure, multi-op → `pub trait`;
  every operation returns `Result<T, FaceError>` since Rust has no exceptions.
- **Cross-type references** resolve to fully self-contained paths
  (`crate::...` or `super::...`) with no `use` needed anywhere.
- **Const** maps directly to a module-scope `pub const` (no wrapping class needed,
  unlike C#).

## Generic engine extensions (not Rust-specific)

Three additions to the shared `GenericLanguageMapper` engine, all data-driven
and language-agnostic even though Rust is (so far) the only consumer of each:

- **`per_directory_tree` post-file trigger** (`GenericLanguageMapper` /
  `LanguageDescriptor`) — lets a language wire up `mod.rs` / `lib.rs` at
  *every* directory level, not just leaves that directly received a
  construct. Something Python (implicit namespace packages) and C#
  (namespace declared per-file) never needed, but Rust's module system
  requires.
- **`$templateInstAliases` registry** (commit `572e621`, "debug fixes") — a
  `Set<String>` of every `TemplateInstNode` alias actually rendered in the
  current pass, exposed to every construct template. Added to fix a real
  bug (see below): a scoped reference into *another* template
  instantiation's own generated file looks, as a string, identical to an
  ordinary reference to an ordinary same-named construct — the registry is
  what lets Rust's `rustScopedType` macro tell the two apart.
- **`$multiOpInterfaceNames` registry** (commit `1622637`, "Fix Rust codegen
  for multi-op interfaces used as value types") — the bare names of every
  multi-operation interface, collected from the **full merged spec**
  (deliberately not units-restricted like `enumNames`/`templateInstAliases`,
  since the interface declaration this exists for lives in static framework
  IDL only ever reached through instantiation) and recursing into
  `TemplateModuleNode` bodies. Lets a language's macros special-case a
  reference to a multi-op interface differently from a single-op one.

## Bugs found and fixed via actual `cargo build` validation of generated output

- `IdlType`'s `Sequence` / `Array` / `Scoped` / `Str` / `WideStr` inner classes
  were missing JavaBean getter aliases, so Velocity's `$t.elementType`-style
  property access silently resolved to `null` and fell through to the
  `"/* unknown */"` fallback. This was a **latent bug already affecting the C#
  `copyExpr` macro** too (confirmed in generated `ValidationResult.cs`) — fixed
  for every language, not just Rust.
- Rust can't `#[derive(Default)]` a data-carrying enum (a mapped union), which
  transitively broke `Default` derivation on any struct embedding one —
  resolved by not deriving `Default` on structs at all (Rust struct-literal
  construction covers the same need).

The above were both found against the small `examples/SampleModel.yaml`
fixture. Actually running the tool end-to-end against a real, template-heavy
model (`examples/GROCERY_with_IM.face`) and `cargo build`-ing the result
surfaced two more, both Rust-specific and both fixed:

- **Path doubling into another instantiation's file** (commit `572e621`).
  FACE's Injectable-wraps-a-TypedTS dependency-injection pattern references a
  type through *another* template instantiation's own alias, e.g.
  `::FACE::TSS::InventoryGateway_Templates::NewStockAgent_Response::TypedTS`
  (`NewStockAgent_Response` is itself a separately-generated file; `TypedTS`
  is a member declared inside it). The Rust macro's "double the trailing
  segment" convention — correct for an ordinary construct, where the file
  and the type inside it share one name — misfired here and produced a
  bogus `...::TypedTS::TypedTS` path that doesn't exist. Fixed by exposing
  the `$templateInstAliases` registry (above) so the macro can tell "a
  member of another instantiation's file" apart from "an ordinary
  same-named module+type."
- **Multi-op interface used as a value type** (commit `1622637`,
  `error[E0782]: expected a type, found a trait`). A multi-operation
  interface renders as `pub trait X` in Rust; once the path-doubling bug
  above was fixed, a reference to one used as a plain parameter/field type
  (the same Injectable pattern's `inout T interface_reference`) emitted the
  bare trait name, which isn't `Sized` and can't be used by value.
  `rustc`'s own "add `dyn`" suggestion was a red herring — an unwrapped
  `dyn Trait` is still unsized. Fixed by exposing the
  `$multiOpInterfaceNames` registry (above) and wrapping a matching
  reference as `Box<dyn Trait + Send>`, matching the convention
  single-operation interfaces already used.

Verified against the grocery model's full DM+TSS tree: the 50 `E0782`
errors this second fix addressed are gone; only the already-documented
framework-type gap below remains. C++ output confirmed byte-for-byte
unchanged across all 200 generated files before/after — these fixes only
touch Rust's own macros plus generic, additive context keys.

## Wiring

- `--rust` flag added to `face-idl-gen` (`generate-tss-idl`, `generate`,
  `parse-tss-idl`) and `face-idl-binder bind`.
- Included in `--all-languages`; excluded from `--all-face` (same treatment as `--csharp`).
- `FaceToolUtils.buildMappers(...)` extended with a `genRust` parameter.

## Test coverage

- Extended `LanguageBindingIntegrationTest` with Rust assertions (file existence,
  struct/enum content, `mod.rs`/`lib.rs` presence, framework-type exclusion),
  mirroring the existing C++/Python checks.
- `mvn clean test` — all test classes pass, including the new Rust assertions.
- Independently verified: generated output compiles cleanly with `cargo build`
  (both the plain-struct/enum/union/const path and the template-instantiation
  path, including cross-module references).

## Known limitation (shared across all five languages, not new)

`FACE::`-namespace framework types referenced but not defined in your own IDL
(e.g. `FACE::RETURN_CODE_TYPE`, a real `enum` in the FACE framework's
`Common.idl`) are referenced by their resolved path but never (re)generated
by this tool for *any* language — the test suite explicitly asserts framework
types are absent from generated output. A C++ project satisfies such a
reference against the platform's real FACE SDK headers; a Rust project must
supply the equivalent hand-written module itself (mirroring `face_error.rs`,
which this generator *does* hand-author because `FaceError` has no FACE SDK
counterpart to reference instead). See `docs/rust-binding-guide.md` §13.

## Files changed

Reflects the initial commit only; the two follow-up commits above additionally
touch `GenericLanguageMapper.java` (+103 lines total) and
`templates/languages/rust/macros.vm` (+71 lines total).

```
 README.md                                                     |  12 +-
 docs/README.md                                                |   6 +
 docs/design-guide.md                                          |   2 +-
 docs/user-guide.md                                            |   4 +-
 docs/rust-binding-guide.md                                    | new
 templates/languages/rust/                                     | new
 src/main/java/.../FaceIdlBinder.java                          |   7 +-
 src/main/java/.../FaceIdlGen.java                             |  25 +-
 src/main/java/.../FaceToolUtils.java                          |   9 +-
 src/main/java/.../ast/IdlType.java                            |  20 +
 src/main/java/.../binding/generic/GenericLanguageMapper.java  |  59 +
 src/main/java/.../binding/generic/LanguageDescriptor.java     |  27 +-
 src/test/java/.../LanguageBindingIntegrationTest.java         |  64 +
```

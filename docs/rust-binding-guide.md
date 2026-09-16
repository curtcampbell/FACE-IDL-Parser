# Rust Language Binding Guide
## Entity Reactor IDL Generator — Non-FACE Pragmatic Mapping

**Audience:** Developers implementing FACE UoPs or TSS components who want to
work in Rust. You are expected to understand what a UoP is, what a Transport
Service is, and how IDL is used in a FACE system. You do not need to know the
FACE Java or C++ mapping rules.

---

## 1. Purpose and Scope

FACE Technical Standard 3.2 does not define a Rust language binding. This
mapping is a pragmatic adaptation that lets Rust developers write UoP
implementations against generated Rust types without writing any interop code
themselves — following the same non-normative approach the [C# binding
guide](csharp-binding-guide.md) already takes for .NET.

The generated types define the **contract** for a UoP implementation. There is
no FFI, no `unsafe`, and no reference to unmanaged memory. The interop layer
between the FACE platform runtime and your Rust UoP is handled by a separate
component; your job as a UoP author is to implement the generated traits and
work with the generated data types.

---

## 2. Activating Rust Generation

Rust output is produced by the **language binding generator**
(`face-idl-binder`, or `face-idl-gen generate-tss-idl` / `generate` /
`parse-tss-idl` with a language flag), configured by
`templates/languages/rust/language.yaml` and driven by the `per_construct`
iteration strategy.

```sh
face-idl-binder bind --idl-dir ./IDL --rust --output-dir ./out
# or, alongside other languages:
face-idl-gen generate-tss-idl --rust --cpp -o out examples/GROCERY_with_IM.face
```

`--rust` is additive with every other language flag (`--cpp`, `--java`,
`--python`, `--csharp`), and is included in `--all-languages`. It is excluded
from `--all-face` (the FACE-standard C++/Java pair), same as `--csharp`.

Output is written under `<output-dir>/rust/`.

---

## 3. Output Structure and Integrating It Into a Crate

```
out/rust/
├── lib.rs                       <- crate root: `pub mod FACE;`
├── face_error.rs                <- FaceError (see §9)
└── FACE/
    ├── mod.rs                   <- `pub mod DM;`
    └── DM/
        ├── mod.rs                <- `pub mod SampleModel;`
        └── SampleModel/
            ├── mod.rs             <- `pub mod GeoPosition;` etc.
            ├── GeoPosition.rs     <- IDL struct -> Rust struct
            ├── ThreatLevel.rs     <- IDL enum   -> Rust enum
            └── IThreatEntity.rs   <- IDL interface -> Rust trait
```

**Unlike the other four mappings, Rust requires this tree to be mounted as a
crate's own module root.** C# embeds its namespace in each file
(`namespace FACE.DM.SampleModel { ... }`) so file location is irrelevant to
the compiler; Python relies on PEP 420 implicit namespace packages. Rust has
neither — `mod` declarations are mandatory at every level, which is exactly
what `lib.rs` / `mod.rs` provide here (see §4). Consuming this output means
one of:

- Point a dedicated crate's `[lib] path` in `Cargo.toml` at
  `<output-dir>/rust/lib.rs`, or
- Copy/symlink `<output-dir>/rust/` in as that crate's `src/` directory.

Struct field types and interface signatures use `crate::`-absolute paths
(e.g. `crate::FACE::DM::SampleModel::GeoPosition::GeoPosition`), so the
generated tree must be the crate root — mounting it as a *submodule* of an
existing crate (e.g. via `#[path] mod generated;`) will not resolve.

---

## 4. Why Every Directory Has a `mod.rs`

Rust requires every module to be declared with `mod`/`pub mod` at its parent —
there is no directory-scan equivalent. The generator emits a `mod.rs` (or,
at the tree root, `lib.rs`) in every directory that received at least one
generated file or subdirectory, declaring `pub mod <child>;` for each one.
This file is regenerated on every run — never hand-edit it.

---

## 5. Namespace Mapping

IDL modules map to nested Rust modules via the directory tree, **preserving
their original casing** (same as C#'s namespace mapping, and unlike Java's
lowercased packages).

| IDL | Rust path |
|-----|-----------|
| `module FACE { module DM { module SampleModel { ... } } }` | `crate::FACE::DM::SampleModel::...` |

Module identifiers mirror IDL names verbatim, so they are not
`snake_case` — every generated file therefore carries
`#![allow(non_snake_case, non_camel_case_types)]`. This is a deliberate,
visible choice (not a suppressed real problem): renaming modules to Rust
convention would break the direct, traceable correspondence to the IDL text.

---

## 6. Primitive Type Mapping

| IDL type | Rust type | Notes |
|---|---|---|
| `boolean` | `bool` | |
| `char` | `u8` | FACE/IDL `char` is an 8-bit narrow character, not a 4-byte Unicode scalar — `u8` is the byte-accurate mapping, unlike Java/C#'s `char` |
| `wchar` | `u16` | FACE wide char is 16-bit |
| `octet` | `u8` | |
| `short` / `int16` | `i16` | |
| `unsigned short` / `uint16` | `u16` | |
| `long` / `int32` | `i32` | |
| `unsigned long` / `uint32` | `u32` | |
| `long long` / `int64` | `i64` | |
| `unsigned long long` / `uint64` | `u64` | |
| `float` | `f32` | |
| `double` | `f64` | |
| `long double` | `f64` | Closest available Rust type (no native extended-precision float in `std`) — same rationale as C#'s `decimal` choice |
| `string` | `String` | |
| `wstring` | `String` | Rust `String` is UTF-8 either way |
| `sequence<T>` / `T[N]` | `Vec<T>` | The sequence bound / array dimension is not preserved — same limitation as Python's `List[T]` and C#'s `T[]` |

FACE scoped types (`FACE::Long`, `FACE::GUID_TYPE`, ...) resolve to their
underlying Rust primitive via `scoped_overrides` in `language.yaml`.

---

## 7. Struct → Rust Struct (Value Type, Not a Reference Type)

An IDL struct maps to a plain Rust `struct` with `pub` fields, deriving
`Debug, Clone, PartialEq`.

```idl
// IDL
struct GeoPosition {
    double latitude;
    double longitude;
    float  altitude_m;
};
```

```rust
// Generated Rust
#[derive(Debug, Clone, PartialEq)]
pub struct GeoPosition {
    pub latitude: f64,
    pub longitude: f64,
    pub altitude_m: f32,
}
```

### 7.1 Copy Semantics — This Is Where Rust Wins

The [C# guide's §6.2](csharp-binding-guide.md#62-copy-semantics--important)
spends a full section warning that `sealed class` is a reference type, and
that forgetting to call the copy constructor at a UoP boundary silently
aliases state between sender and receiver. **That entire class of bug does
not exist here.** A Rust struct is a value; passing one by value moves or
(because `Clone` is derived) `.clone()`s it, and the borrow checker rejects
any attempt to alias mutable state across an API boundary at compile time,
not at review time.

### 7.2 Field Naming — No Case Conversion

FACE IDL identifiers are `snake_case`, which is already idiomatic Rust — so,
unlike the Java (`camelCase` accessors) and C# (`PascalCase` properties)
mappings, field names are emitted **verbatim**. `GUID_TYPE`, `heading_deg`,
etc. need no conversion.

### 7.3 No Default Derive

Structs do **not** derive `Default`. This is a direct consequence of §8.2:
Rust's `#[default]` attribute is only legal on a *unit* enum variant, and a
generated union (§8) is a data-carrying enum whose variants can never be
unit variants — so a struct with a union-typed field could never derive
`Default` anyway. Rather than derive it only on the structs that happen not
to embed a union (and have that break the moment a schema gains one), it is
left off uniformly. Construct instances with struct-literal syntax:
`GeoPosition { latitude: 0.0, longitude: 0.0, altitude_m: 0.0 }`.

---

## 8. Union → Rust Enum (a Direct, Idiomatic Mapping)

The [C# guide states outright](csharp-binding-guide.md#14-what-is-not-mapped)
that unions are skipped: "discriminated unions have no direct idiomatic C#
equivalent." **Rust's `enum` *is* a tagged union** — this is the one IDL
construct where the Rust mapping is more direct than every other language
this generator supports, C++ included.

```idl
union EntityPayload switch (EntityTypeEnum) {
    case ENTITY_TYPE_TRACK:    TrackEntity   track;
    case ENTITY_TYPE_THREAT:   ThreatEntity  threat;
    case ENTITY_TYPE_WAYPOINT: WaypointEntity waypoint;
};
```

```rust
#[derive(Debug, Clone, PartialEq)]
pub enum EntityPayload {
    Track(crate::FACE::DM::SampleModel::TrackEntity::TrackEntity),
    Threat(crate::FACE::DM::SampleModel::ThreatEntity::ThreatEntity),
    Waypoint(crate::FACE::DM::SampleModel::WaypointEntity::WaypointEntity),
}
```

### 8.1 What Is Not Preserved

The IDL discriminant *value* itself is not carried as data — Rust manages
the enum's own tag internally. `match payload { EntityPayload::Track(t) => ... }`
replaces comparing against the original switch value.

### 8.2 No Default Derive

See §7.3 — a data-carrying variant can never be marked `#[default]`, so
`Default` is never derived here.

---

## 9. Interface → Boxed Closure or Trait

### 9.1 Single-Operation Interface → Boxed `FnMut`

The [C# guide maps a single-operation interface to a
`delegate`](csharp-binding-guide.md#10-single-operation-interface--delegate):
you receive exactly one FACE-defined callback interface with one operation at
a dependency-injection port, and can't substitute an arbitrary user interface
there anyway — so there's no benefit to a full trait for one method. Rust's
own idiom for "one callback, no interface" is a boxed closure, so that's what
this mapping uses directly, rather than following C#'s delegate detour:

```idl
interface Read_Callback {
    void send_message(in TrackData data, in long transaction_id);
};
```

```rust
pub type Read_Callback =
    Box<dyn FnMut(TrackData, i32) -> Result<(), crate::face_error::FaceError> + Send>;
```

Your UoP registers a closure matching this signature at its FACE DI port; the
TSS calls it when data arrives.

### 9.2 Multi-Operation Interface → `pub trait`

```idl
interface ThreatProcessor {
    void update_position(in GeoPosition pos, out ThreatLevel level);
    long get_track_id();
};
```

```rust
pub trait ThreatProcessor {
    fn update_position(&mut self, pos: GeoPosition)
        -> Result<ThreatLevel, crate::face_error::FaceError>;
    fn get_track_id(&mut self)
        -> Result<i32, crate::face_error::FaceError>;
}
```

Base interfaces become Rust supertraits directly: `interface Foo : Base` ->
`pub trait Foo: Base`.

### 9.3 Parameter Direction Rules

Identical folding rules to the [C# mapping's
§9](csharp-binding-guide.md#9-method-signature-transformation):

| IDL direction | Rust treatment |
|---|---|
| `in T` | Regular input parameter |
| `out T` | Folded into the `Ok(..)` return value (never an `&mut` out-param) |
| `inout T` | Input parameter **and** folded into the return value |

| Outputs | `Ok(..)` payload |
|---|---|
| None (all `in`) | `()` |
| One `out`/`inout` | `T` directly |
| Multiple outputs | Anonymous tuple `(T1, T2, ...)` — stable Rust has no named tuple fields, so parameter names are documented on the trait method instead (see the generated doc comment) |

### 9.4 `&mut self`

Every trait method takes `&mut self`, since a UoP implementation is free to
carry internal state; an implementation that needs no mutation can simply
ignore it.

---

## 10. `Result<T, FaceError>`, Not Exceptions

FACE TS 3.2 §K.4.2 (`BAD_PARAM`) and §K.4.3 (`DATA_CONVERSION`) are exception
types in the Java mapping (checked `throws`) and documented-but-unchecked
exceptions in the [C# mapping](csharp-binding-guide.md#12-face-framework-exception-types).
**Rust has no exceptions at all** — a panic is for unrecoverable programmer
error, not an expected fallible outcome — so every generated interface
operation returns `Result<T, FaceError>` instead:

```rust
// face_error.rs — emitted once per output tree
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum FaceError {
    BadParam(String),
    DataConversion(String),
}
```

`FaceError` implements `std::fmt::Display` and `std::error::Error`, and is
always referenced by its fully-qualified path
(`crate::face_error::FaceError`) so no `use` is ever required at a call site.

---

## 11. Template Module Instantiations

Same substitution mechanism as every other mapping (FACE §4.8.4.1's
`Typed<D, R>` template modules). Each instantiation produces one Rust file
(named after the instantiation alias) containing every substituted
definition as a top-level item — analogous to the [C# mapping's
approach](csharp-binding-guide.md#11-template-module-instantiations) of
emitting substituted types directly into the enclosing namespace, rather
than the Java mapping's nested static outer class.

```idl
template module Typed_TS<typename ENTITY_TYPE, typename MESSAGE_TYPE> {
    interface Read_Callback {
        void send_message(in MESSAGE_TYPE msg, in FACE::Long xact_id);
    };
    struct SingleMessage {
        MESSAGE_TYPE data;
    };
};

typedef Typed_TS<TrackEntity, TrackData> TrackTS;
```

```rust
// TrackTS.rs
pub type Read_Callback =
    Box<dyn FnMut(TrackData, i32) -> Result<(), crate::face_error::FaceError> + Send>;

#[derive(Debug, Clone, PartialEq)]
pub struct SingleMessage {
    pub data: TrackData,
}
```

A reference from one substituted definition to another declared in the
*same* instantiation (e.g. a `TypedTS` operation parameter typed as the
sibling `Read_Callback`) resolves as a bare in-file reference, not a
`super::`-qualified one — those two items live in the same file/module, not
separate ones.

---

## 12. Cross-Type References — How `crate::`/`super::` Paths Are Built

Every generated field/parameter/return type is a **fully self-contained
Rust path** — no `use` import is ever required at the call site. The
resolution rule (implemented in `templates/languages/rust/macros.vm`):

- A **fully-qualified** IDL reference (`::FACE::DM::SampleModel::GeoPosition`)
  becomes the absolute path `crate::FACE::DM::SampleModel::GeoPosition::GeoPosition`
  — the doubled final segment is because every generated type lives in its
  own same-named module file (§4); `FACE::DM::SampleModel` is the *module*
  path, and the trailing `::GeoPosition` reaches the type declared inside it.
- A **bare** (unqualified) IDL reference is a sibling type declared in the
  same IDL module — therefore the same output directory / same parent Rust
  module: `super::GeoPosition::GeoPosition`.
- A FACE namespace alias resolved via `scoped_overrides` or a typedef chain
  to a plain Rust type (`i32`, `String`, ...) is returned unchanged.

---

## 13. What Is Not Mapped

Consistent with the other pragmatic (non-FACE-standard) mappings:

- **Typedef** — resolved transparently during type emission (structural
  expansion, or the FACE-namespace-alias table). No separate Rust type alias
  is generated for a plain IDL `typedef`.
- **Const** — mapped, uniquely among the five languages: `pub const NAME: T
  = value;` at module scope. No wrapping class/struct is needed (contrast
  with C#'s `public static class NAME { public const T Value = ...; }`).
- **Template module declarations** — only instantiations produce output.
- **`FACE::`-namespace framework types referenced but not defined in your own
  IDL** (e.g. `FACE::RETURN_CODE_TYPE`, a real `enum` declared in the FACE
  framework's own `Common.idl`) are referenced by their resolved path but
  never (re)generated by this tool for *any* of the five languages — the
  test suite explicitly asserts framework types are absent from generated
  output. A C++ project satisfies such a reference against the platform's
  real FACE SDK headers; a Rust project must supply the equivalent hand-
  written module itself (mirroring `face_error.rs`, which this generator
  *does* hand-author precisely because `FaceError` has no FACE SDK
  counterpart to reference instead).

---

## 14. Quick Reference Card

| IDL construct | Rust output |
|---|---|
| `module A::B` | `crate::A::B` (case-preserved directory tree + `mod.rs`) |
| `struct Foo` | `pub struct Foo` — value type, `Debug + Clone + PartialEq` |
| `enum Bar` | `pub enum Bar` — unit variants, `Debug + Clone + Copy + PartialEq + Eq + Default` |
| `union U switch(D)` | `pub enum U` — data-carrying variants (a real Rust tagged union) |
| Single-op interface | `pub type Name = Box<dyn FnMut(..) -> Result<T, FaceError> + Send>` |
| Multi-op interface | `pub trait Name { fn op(&mut self, ..) -> Result<T, FaceError>; }` |
| `in T param` | `param: T` (input) |
| `out T param` | Folded into the `Ok(..)` return value (or tuple element) |
| `inout T param` | Input parameter + `Ok(..)` return element |
| `const NAME` | `pub const NAME: T = value;` |
| `BAD_PARAM` | `crate::face_error::FaceError::BadParam(String)` |
| `DATA_CONVERSION` | `crate::face_error::FaceError::DataConversion(String)` |

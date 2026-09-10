# IDL Input Guide — face-codegen

This guide describes the IDL input format that `face-codegen generate` parses,
how include resolution works, the IDL subset the parser handles, and how
`Typed<D,R>` template module instantiations map to `TEMPLATE_INST` scope
entries in the manifest.

---

## Overview

`face-codegen` ingests a directory of IDL files (via `--idl-dir`), resolves all
`#include` directives, concatenates the resulting translation units in
dependency order, and feeds them to an ANTLR4 parser. The output is a single
merged `IdlSpecification` — the root of the IDL AST — that all Velocity
templates operate on.

```
--idl-dir  (project IDL root)
       │
       ▼ IdlDirectoryParser
           1. Walk tree — collect all .idl files (lexicographic order)
           2. For each file — IdlIncludeResolver resolves #include directives
              (search: file's own dir → --face-idl-dir → -I paths in order)
           3. Deduplicate across all root files (each file appears at most once)
           4. Concatenate source in dependency order (leaves first)
           5. Feed to ANTLR4 grammar → IdlAstBuilder → IdlSpecification
```

The merged spec is then passed to `ContextAssembler`, which exposes it as
`$spec` in every template context.

---

## include Resolution

### Search path

When the parser encounters `#include "file"` or `#include <file>` it searches:

1. The directory containing the including file.
2. The FACE framework IDL root (`--face-idl-dir`).
3. Any additional directories supplied via `-I` / `--include-path`, in order.

Missing includes are **silently skipped**. This handles FACE platform headers
(e.g. `<FACE/Common.idl>`) that are installed separately from this tool. If a
skipped header defines types actually referenced in IDL structs, the ANTLR4
parse will fail with an undefined-type error.

### Deduplication

The resolver tracks canonical paths. A file that is transitively included by
multiple root files is concatenated exactly once, in its earliest dependency
position.

### Example: SampleModel IDL tree

```
--idl-dir Code/SampleModel/reactor/gen/idl/IDL
-I        Code/Core/IDL
```

The parser walks `IDL/` and finds three layers:

```
IDL/
├── Entities/
│   ├── GeoPosition.idl
│   ├── ThreatEntity.idl     (includes GeoPosition.idl)
│   ├── TrackEntity.idl      (includes GeoPosition.idl)
│   └── WaypointEntity.idl   (includes GeoPosition.idl)
├── EntityReactor/
│   ├── EntityPayload.idl
│   ├── EntityReactor_Types.idl
│   ├── EntityCrudRequest.idl
│   ├── EntityCrudResponse.idl
│   ├── EntityEvent.idl
│   ├── SubscriptionRequest.idl
│   └── SubscriptionResponse.idl
└── TypedTS/
    ├── EntityCrudRequest_EntityCrudResponse.idl
    ├── EntityEvent.idl
    └── SubscriptionRequest_SubscriptionResponse.idl
```

The FACE framework IDL (`Code/Core/IDL/`) is searched when includes like
`<FACE/Common.idl>` are encountered. The `Typed<D>` / `Typed<D,R>` template
module definitions in `FACE/TSS/TypedTS.idl` are loaded from this path —
they are required for `TEMPLATE_INST` scope resolution.

---

## IDL Subset Handled by the Parser

The parser covers all IDL constructs used in FACE 3.2 entity data models.

**Supported:**

| Construct | Example |
|---|---|
| Modules (regular) | `module FACE { module DM { ... }; };` |
| Template modules | `module Typed<typename D, typename R> { ... };` |
| Template module instantiations | `module ::FACE::TSS::Typed<Req_t, Resp_t> MyAlias;` |
| Structs | `struct TrackEntity { FACE::GUID_TYPE track_id; };` |
| Enums | `enum EntityTypeEnum { ENTITY_TYPE_TRACK, ... };` |
| Unions | `union EntityPayload switch (EntityTypeEnum) { ... };` |
| Interfaces | `interface TypedReadCallback<...> { ... };` |
| Operations | `Read(in DATA_TYPE message, ...);` |
| Consts | `const long MAX_ENTITIES = 64;` |
| Typedefs | `typedef long GUID_TYPE;` |
| All IDL type expressions | `sequence<double, 8>`, `string<255>`, `double[3]`, `::FACE::GUID_TYPE` |

**Silently skipped (not used in FACE entity data models):**

- Exception declarations
- Bitset and bitmask types
- Native declarations
- Forward declarations

**Not implemented:**

- `#pragma once` and preprocessor macros are lexed out; only `#include` is acted upon.
- Annotation (`@`) syntax is not yet parsed.

---

## IDL Type Scoping

The parser preserves fully-qualified type names as written in the IDL. Scoped
names that start with `::` (e.g. `::FACE::GUID_TYPE`) are stored with the
leading `::`. Templates should not add a second `::` prefix when referencing
resolved type names — see `docs/velocity-template-gotchas.md` rule 4.

---

## Typed<D,R> Template Module Instantiations and TEMPLATE_INST Scope

### What a template instantiation looks like in IDL

```idl
// TypedTS/EntityCrudRequest_EntityCrudResponse.idl
module ::FACE::TSS::Typed<
    ::FACE::DM::SampleModel::EntityCrudRequest_t,
    ::FACE::DM::SampleModel::EntityCrudResponse_t>
    EntityCrudRequest_EntityCrudResponse;
```

The parser creates a `TemplateInstNode` with:

| Field | Value |
|---|---|
| `alias` | `EntityCrudRequest_EntityCrudResponse` |
| `templateName` | `::FACE::TSS::Typed` |
| `actualParameters` | `["::FACE::DM::SampleModel::EntityCrudRequest_t", "::FACE::DM::SampleModel::EntityCrudResponse_t"]` |

### Manifest entry for TEMPLATE_INST scope

```yaml
- template:     entity_crud_ts.hpp.vm
  for_each:     TEMPLATE_INST
  name_pattern: "EntityCrudRequest_EntityCrudResponse"
  output:       "{project_namespace}/{inst.alias}TS.hpp"
```

The pipeline calls `TemplateInstantiator.instantiate(inst)`, which:

1. Looks up the template module (`::FACE::TSS::Typed`) in a registry built from
   the merged spec (requires `FACE/TSS/TypedTS.idl` to be on the include path).
2. Matches the arity (number of actual parameters) to the right overload.
3. Resolves each actual parameter through the typedef chain.
   `EntityCrudRequest_t` → `::FACE::DM::SampleModel::EntityCrudRequest`.
4. Deep-copies the template body, substituting formal parameter names with
   resolved actual types.

The result is an `InstantiationResult` placed in `$resolved` in the template
context:

| `$resolved` field | Description |
|---|---|
| `$resolved.definitions` | Substituted interface and struct definitions from the template body |
| `$resolved.localInterfaceNames` | Interface names local to the template body |
| `$resolved.resolvedActuals` | Concrete actual type nodes in formal-parameter order |

### When TEMPLATE_INST resolution fails

If the template module definition is not in the merged spec (because
`--face-idl-dir` was not set or the IDL was not on the include path), the
`TemplateInstantiator` returns an empty `Optional` and the pipeline logs a
`WARNING` and skips that entry. All other entries continue to render normally.

---

## Error Reporting

Because all sources are concatenated before parsing, ANTLR4 reports line
numbers within the combined source. The `IdlDirectoryParser` maps combined-source
line numbers back to the originating file and computes the local line, then
re-throws an enriched exception with:

- The originating file name
- The local line:column
- The offending source line
- A caret (`^`) pointing to the error column

In practice this mapping is accurate unless the combined source is very large.

---

## FAQ

**Q: The IDL files for my project are generated by another tool (e.g. `face-idl-gen`). Do I need to do anything special?**

No. Point `--idl-dir` at the root directory of generated IDL files and
`--face-idl-dir` at the FACE framework IDL root. The parser does not
distinguish generated IDL from hand-authored IDL.

**Q: Do I need to list IDL files explicitly?**

No. `IdlDirectoryParser` walks `--idl-dir` recursively and picks up all `.idl`
files in lexicographic order. Include resolution determines the parse order from
there.

**Q: Can I have IDL files from multiple directories?**

Yes — use `-I` for additional search directories. The parser roots at
`--idl-dir` and searches for includes in `--face-idl-dir` and `-I` paths. If
you have IDL source files in multiple top-level directories, you can either
restructure or supply a root that covers all of them.

**Q: My template has `$inst.resolvedActuals` but it's rendering as the literal string. Why?**

`$inst.resolvedActuals` is not a field on `TemplateInstNode` — it is on
`$resolved` (`InstantiationResult`). Use `$resolved.resolvedActuals` instead.
See `docs/velocity-template-gotchas.md` rule 3 for the Velocity getter rule.

**Q: Can I pass a `.face` XMI file as the IDL input?**

No. `face-codegen` reads IDL, not `.face` XMI. The `.face` XMI file is
optional input (via `--face-file`) for UoP model data that populates `$model`
and `$uops`. A separate tool (`face-idl-gen`) reads `.face` XMI and produces
IDL files.

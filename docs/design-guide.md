# face-codegen Design Guide

Team-facing implementation reference. This document is for engineers who need
to add features, write new templates, extend the EntityReactor model, or
understand why the pipeline does what it does.

---

## Contents

1. [Overall Pipeline](#1-overall-pipeline)
2. [IDL AST Node Hierarchy](#2-idl-ast-node-hierarchy)
3. [`##!` Directive Scanning](#3--directive-scanning)
4. [`IdlFileUnit` and FILE Scope](#4-idlfileunit-and-file-scope)
5. [`IdlDerivedVariables`](#5-idlderivedvariables)
6. [TemplateInstantiator — Resolving Typed<D,R>](#6-templateinstantiator----resolving-typeddr)
7. [ContextAssembler Scope Model](#7-contextassembler-scope-model)
8. [EntityReactorHelper Field Classification](#8-entityreactorhelper-field-classification)
9. [How to Add a New Entity Type to SampleModel](#9-how-to-add-a-new-entity-type-to-samplemodel)
10. [How to Write a New Template](#10-how-to-write-a-new-template)
11. [Template Sets — Type Distinctions](#11-template-sets----type-distinctions)
12. [EntityReactor C++ Design](#12-entityreactor-c-design)

---

## 1. Overall Pipeline

```
--idl-dir (project IDL)
--face-idl-dir / -I (include paths)
        │
        ▼  IdlDirectoryParser
           1. Walk --idl-dir recursively for .idl files (lexicographic order)
           2. For each root file — IdlIncludeResolver resolves #include directives
              (search order: file's own dir → --face-idl-dir → -I paths)
           3. Deduplicate by canonical path (each file concatenated once)
           4. Build IdlFileUnit list (one per root .idl file — used for FILE scope)
           5. tagSourceFile() pass: annotate per-file AST nodes with their source path
           6. Concatenate source in dependency order (leaves first)
           7. Feed combined text to ANTLR4 grammar
        │
        ▼  IdlAstBuilder (ANTLR4 visitor)
           Walks the parse tree; produces IdlSpecification (merged spec)
        │
        ▼  IdlParseResult
           .mergedSpec()    → IdlSpecification
           .fileUnits()     → List<IdlFileUnit>  (one per root .idl)
        │
--face-file (optional)
        ├──▶  FaceTssReader → UoPModelData
        │
--template-dir
        ├──▶  DirectiveScanner: scan ##! headers from all .vm files
        │         → List<DirectiveEntry> (template + for_each + filter + output + driver)
        ├──▶  codegen-defaults.yaml (optional) → CodeGenDefaults
        │
--manifest (codegen.yaml, optional)
        ├──▶  CodeGenManifestLoader → CodeGenManifest
        │         (supplements or overrides DirectiveScanner results)
        │
        ▼  IdlDerivedVariables.derive(mergedSpec)
           → 5 auto-derived variables (model_namespace, project_namespace, etc.)
        │
ContextAssembler.build(parseResult, model, languageDir)
        ├── new TemplateInstantiator(spec)
        │       builds templateRegistry (name → List<TemplateModuleNode>)
        │       builds typedefMap (alias → IdlType)
        └── optional TypeResolver if language_dir set
        │
CodeGenPipeline(manifest, directiveEntries, templateDir, outputDir, assembler).run()
        ├── VelocityEngine configured (FileResourceLoader → templateDir)
        ├── base context built: $spec, $instantiator, $model, $uops, $types
        ├── variables injected (priority: --var > manifest > codegen-defaults > IDL-derived)
        ├── helpers constructed and injected into base context
        └── for each GenerationEntry (from directives + manifest.generations):
               scope dispatch → per-element iteration
               ↓ filter / name_pattern filter
               ↓ assembler.withXxx(base, ...) → child VelocityContext
               ↓ OutputPathResolver.forXxx(entry.output, ...) → relativePath
               ↓ resolveVars(relativePath) → substitute all variables
               ↓ [driver: false] velocity.getTemplate(entry.template).merge(ctx, writer)
                                 write to outputDir/relativePath
               ↓ [driver: true ] DriverOutputHandler intercepts #parse when $outFile set
                                 renders leaf template to resolved $outFile path
```

All Java code lives under
`code_generator/src/main/java/com/warhex/er/generator/`.

> **Note:** `--manifest` (i.e. `codegen.yaml`) is now optional. The pipeline
> falls back to `codegen-defaults.yaml` + `##!` directive scanning when no
> manifest is supplied.

Key packages:

| Package | Contents |
|---|---|
| `(root)` | `FaceCodeGen` (entry point), `FaceToolUtils` |
| `parser` | `IdlDirectoryParser`, `IdlIncludeResolver`, `IdlParser`, `IdlAstBuilder` |
| `ast` | IDL AST node types (see §2) |
| `binding` | `TemplateInstantiator`, `TemplateInstantiator.InstantiationResult` |
| `codegen.context` | `ContextAssembler` |
| `codegen.manifest` | `CodeGenManifest`, `GenerationEntry`, `ForEachScope`, `CodeGenManifestLoader` |
| `codegen.pipeline` | `CodeGenPipeline`, `OutputPathResolver` |
| `codegen.helpers` | `EntityReactorHelper`, `HelperRegistry` |
| `codegen.directives` | `DirectiveScanner`, `DirectiveEntry` |
| `codegen.derived` | `IdlDerivedVariables` |
| `reader.dto` | `UoPModelData`, `UoPData`, `ConnectionData` |
| `reader.face` | `FaceTssReader`, `FaceXmiDocument`, `TssToEntityModelAdapter`, `FaceTemplateEntityReader` |

---

## 2. IDL AST Node Hierarchy

All nodes implement `IdlNode`. `IdlDefinition` is the common base for
everything that can appear at module scope.

```
IdlNode  (marker interface)
└── IdlDefinition  (has name; has nullable sourceFile Path)
    ├── ModuleNode         module FACE { ... }
    ├── TemplateModuleNode module Typed<typename D> { ... }
    ├── TemplateInstNode   module ::FACE::TSS::Typed<Req_t> MyAlias
    ├── StructNode         struct TrackEntity { ... }
    ├── EnumNode           enum EntityTypeEnum { ... }
    ├── UnionNode          union EntityPayload switch(...) { ... }
    ├── InterfaceNode      interface TypedReadCallback { ... }
    ├── TypedefNode        typedef long GUID_TYPE
    └── ConstNode          const long MAX = 64
```

**`sourceFile` on `IdlDefinition`:**

`IdlDefinition` has a nullable `Path sourceFile()` field. It is set by the
`tagSourceFile()` pass during `IdlDirectoryParser` processing, but only on
nodes produced by the per-file unit parse (used for FILE scope iteration). On
merged-spec nodes from the combined parse it is `null`. Templates iterating via
`FILE` scope can use `$def.sourceFile` to know which `.idl` file a definition
came from.

Additional non-definition nodes (children of the above):

```
FieldNode       — struct member: name + IdlType
OperationNode   — interface operation: name + return type + params
ParameterNode   — operation parameter: direction + type + name
EnumValueNode   — enum enumerator: name
FormalParameter — template module formal: kind + name (typename/sequence/interface)
UnionCaseNode   — union branch
```

`IdlType` is a sealed hierarchy with no external subclasses:

```
IdlType
├── Primitive    — boolean, short, long, long long, float, double, ...
│                  (carries PrimitiveKind enum)
├── Scoped       — ::FACE::GUID_TYPE, GeoPosition, EntityTypeEnum
│                  (carries String qualifiedName)
├── Sequence     — sequence<T> or sequence<T, N>
├── Str          — string or string<N>
├── WideStr      — wstring or wstring<N>
├── Array        — T[N] (one or more dimensions)
└── Void         — void (operation return type only)
```

### Key fields

**`ModuleNode`**

| Field | Type | Description |
|---|---|---|
| `name()` | String | Module simple name (e.g. `"FACE"`) |
| `definitions()` | `List<IdlDefinition>` | Direct children |

**`StructNode`**

| Field | Type | Description |
|---|---|---|
| `name()` | String | Struct simple name (e.g. `"TrackEntity"`) |
| `members()` | `List<FieldNode>` | Declared fields in order |
| `baseType()` | `Optional<String>` | Inheritance base name (rare in FACE) |

**`TemplateInstNode`**

| Field | Type | Description |
|---|---|---|
| `alias()` | String | The short name after the closing `>` |
| `templateName()` | String | Fully-qualified template module name |
| `actualParameters()` | `List<String>` | Actual parameter names as written in IDL |

**`InterfaceNode`**

| Field | Type | Description |
|---|---|---|
| `name()` | String | Interface simple name |
| `bases()` | `List<String>` | Base interface names |
| `operations()` | `List<OperationNode>` | Declared operations |

**`IdlSpecification`**

| Field | Type | Description |
|---|---|---|
| `definitions()` | `List<IdlDefinition>` | Top-level definitions in the merged spec |

All nodes expose both Java record-style accessors (e.g. `name()`) **and**
JavaBean getters (e.g. `getName()`). Always use the JavaBean getters in
Velocity templates (see `docs/velocity-template-gotchas.md` rule 3).

---

## 3. `##!` Directive Scanning

`DirectiveScanner` reads `##!` headers from every `.vm` file in `--template-dir`
before any template is rendered. This is a plain text scan — Velocity is not
invoked at this stage.

### Scan algorithm

For each `.vm` file in `--template-dir` (recursive):

1. Read lines until the first non-comment, non-blank line.
2. For each line starting with `##!`, parse the key and value (colon-separated).
3. Collect `for_each`, `output`, `filter`, and `driver` values into a
   `DirectiveEntry`.
4. If `for_each` is absent, skip this file (it has no directive header).

A `#set`, blank line, or any non-comment output before the first `##!` line
causes the file's directives to be missed silently — this is by design to allow
non-scanned template fragments (leaf templates called via `#parse`).

### `DirectiveEntry` fields

| Field | Source | Description |
|---|---|---|
| `template` | File path | Template file path relative to `--template-dir` |
| `forEachScope` | `##! for_each:` | `ForEachScope` enum value |
| `filter` | `##! filter:` | Regex string; null if absent |
| `outputPattern` | `##! output:` | Output path pattern; null for driver templates |
| `driver` | `##! driver:` | `true` when `"true"` (case-insensitive); otherwise `false` |

### Integration with the manifest

`DirectiveScanner` results and manifest `generations:` entries are merged into
a unified `List<GenerationEntry>` before the pipeline runs. Manifest entries
take precedence when both declare a generation for the same template. This allows
a project `codegen.yaml` to override a template's own `##!` directives.

---

## 4. `IdlFileUnit` and FILE Scope

`IdlFileUnit` represents a single root `.idl` file from `--idl-dir`. It is
produced by `buildFileUnits()` in `IdlDirectoryParser`.

### `buildFileUnits()`

For each `.idl` file found recursively under `--idl-dir`:

1. Resolve includes (`IdlIncludeResolver`).
2. Parse only that file's own content (not transitive includes) using the ANTLR4
   grammar.
3. Build an `IdlFileUnit` carrying the file's metadata and its top-level
   `IdlDefinition` list.

The file units are constructed independently of the merged spec parse. The merged
spec concatenates all files for a single combined parse; the file units parse
each file individually to produce per-file definition lists for FILE scope
iteration.

### `tagSourceFile()` pass

After `buildFileUnits()`, `tagSourceFile()` walks each `IdlFileUnit`'s
definitions and calls `definition.setSourceFile(path)` on each node. This
annotates per-file nodes so templates can use `$def.sourceFile` to identify
which `.idl` file a definition came from.

### `IdlFileUnit` fields

| Field | Getter | Description |
|---|---|---|
| `stemName` | `getStemName()` | Filename without extension (e.g. `"EntityEvent"`) |
| `relativePath` | `getRelativePath()` | Path relative to `--idl-dir`, forward-slash-normalized (e.g. `"TypedTS/EntityEvent.idl"`) |
| `relativeOutputPath` | `getRelativeOutputPath()` | Same as `relativePath` but with `.hpp` extension |
| `absolutePath` | `getAbsolutePath()` | Full OS path to the `.idl` file |
| `definitions` | `getDefinitions()` | `List<IdlDefinition>` — top-level definitions in this file only (not transitive includes) |

### FILE scope filter

The `##! filter:` regex for FILE scope is matched against the forward-slash-normalized
`relativePath` (e.g. `TypedTS/EntityEvent.idl`). Pattern `TypedTS/.*` matches
all files under `TypedTS/`. The match is a full-string `String.matches()` call.

---

## 5. `IdlDerivedVariables`

`IdlDerivedVariables` derives 5 project-level variables from the parsed IDL AST.
These are computed once per pipeline run and injected into the base Velocity
context at the lowest priority — they can be overridden by `codegen-defaults.yaml`,
`codegen.yaml`, or `--var`.

### Derivation logic

| Variable | Derivation |
|---|---|
| `model_namespace` | IDL module path (joined with `::`) of the module containing entity structs — identified as the deepest module that directly contains `StructNode` definitions whose names match the `.*Entity$` heuristic. |
| `project_namespace` | Last component of `model_namespace` (e.g. `SampleModel` from `FACE::DM::SampleModel`). |
| `face_tss_namespace` | TSS module path from `Typed<>` instantiation nodes — the module path enclosing `TemplateInstNode` entries (e.g. `FACE::TSS::SampleModel`). |
| `entity_payload_idl` | Constructed path `{module_path_as_dir}/EntityPayload.hpp` from the model namespace. |
| `entity_payload_idl_enum` | Fixed as `"EntityTypeEnum"` unless overridden. |

### Priority

```
IDL-derived (lowest)
    → codegen-defaults.yaml
        → codegen.yaml variables:
            → --var CLI flags (highest)
```

Any of these variables can be overridden at a higher priority level. For
projects where the IDL structure does not match the heuristic, supply the
correct values in `codegen.yaml` or via `--var`.

---

## 6. TemplateInstantiator — Resolving Typed<D,R>

### Construction

`TemplateInstantiator(IdlSpecification spec)` walks the spec on construction
and builds two indices:

1. **`templateRegistry`** — maps fully-qualified template name to a list of
   `TemplateModuleNode` entries (one per arity). `::FACE::TSS::Typed` has two
   entries: one for `Typed<D>` and one for `Typed<D,R>`.

2. **`typedefMap`** — maps typedef alias names (with and without leading `::`)
   to their underlying `IdlType`. Used to resolve actual parameters like
   `EntityCrudRequest_t` → `::FACE::DM::SampleModel::EntityCrudRequest`.

### `instantiate(TemplateInstNode inst)`

Returns `Optional<InstantiationResult>`. Returns empty if the template is not
in the registry.

Steps:

1. Look up the template module by `inst.templateName()` and match by arity
   (number of actual parameters).
2. For each actual parameter name, follow the typedef chain until a non-typedef
   type is reached. Collect the resolved `IdlType` list as `resolvedActuals`.
3. Build a `formal → actual` substitution map by pairing each
   `FormalParameter.name()` with the corresponding resolved type.
4. Deep-copy every `IdlDefinition` in the template body, replacing every
   `IdlType.Scoped` whose `qualifiedName` matches a formal parameter name
   with its resolved actual type.
5. Return `InstantiationResult` carrying the substituted definitions,
   `localInterfaceNames`, `interfaceKindActuals`, and `resolvedActuals`.

### InstantiationResult fields

| Field | Type | Velocity name | Use |
|---|---|---|---|
| `definitions` | `List<IdlDefinition>` | `$resolved.definitions` | Substituted interface/struct bodies |
| `localInterfaceNames` | `Set<String>` | `$resolved.localInterfaceNames` | Interface names from template body |
| `interfaceKindActuals` | `Set<String>` | (internal) | Determines `T*&` vs `T**` for `inout` params |
| `resolvedActuals` | `List<IdlType>` | `$resolved.resolvedActuals` | Concrete actual types in formal order |

---

## 7. ContextAssembler Scope Model

`ContextAssembler` assembles `VelocityContext` objects for the pipeline. Each
`withXxx()` method returns a new child context that chains the base context as
its parent — base variables remain accessible without copying.

### Base context (`baseContext()`)

Called once per pipeline run. Populated with:

| Variable | Type | Present when |
|---|---|---|
| `$spec` | `IdlSpecification` | Always |
| `$instantiator` | `TemplateInstantiator` | Always |
| `$model` | `UoPModelData` | `--face-file` supplied; otherwise `null` |
| `$uops` | `List<UoPData>` | Always (empty list when no model) |
| `$types` | `TypeResolver` | `language_dir` set in manifest; otherwise absent |

After `baseContext()` is built, `CodeGenPipeline` also injects:

- **manifest variables** (`injectVariables`) — each `variables` key→value from `codegen.yaml`
- **helpers** (`injectHelpers`) — each helper from `helpers[]`, constructed via `HelperRegistry`

### Per-scope child contexts

| Scope | Method | Variables added to child |
|---|---|---|
| `SPEC` | (uses base directly) | — |
| `MODULE` | `withModule(base, module, namespaces)` | `$module`, `$namespaces` |
| `STRUCT` | `withStruct(base, struct, module, namespaces)` | `$struct`, `$module`, `$namespaces` |
| `INTERFACE` | `withInterface(base, iface, module, namespaces)` | `$iface`, `$module`, `$namespaces` |
| `TEMPLATE_INST` | `withTemplateInst(base, inst, module, namespaces)` | `$inst`, `$resolved`, `$module`, `$namespaces` |
| `UOP` | `withUop(base, uop)` | `$uop` |
| `CONNECTION` | `withConnection(base, uop, conn)` | `$uop`, `$conn` |

`$namespaces` is a `List<String>` of the module name path from the IDL root to
the enclosing module, e.g. `["FACE", "DM", "SampleModel"]` for a struct nested
inside `module FACE { module DM { module SampleModel { ... } } }`.

### Spec walkers

`ContextAssembler` provides three walkers that the pipeline uses for iteration:

- `allStructs()` — returns `List<ScopedElement<StructNode>>`
- `allInterfaces()` — returns `List<ScopedElement<InterfaceNode>>`
- `allTemplateInsts()` — returns `List<ScopedElement<TemplateInstNode>>`
- `topLevelModules()` — returns `List<ModuleNode>` (direct children of spec only)

`ScopedElement<T>` is a record carrying `node`, `enclosingModule`, and
`namespaces`.

---

## 8. EntityReactorHelper Field Classification

`EntityReactorHelper` (`$er`) maps IDL struct members to EntityReactor C++
concepts. It is constructed once per pipeline run and shared across all renders.

### Construction

`EntityReactorHelper(IdlSpecification spec, TemplateInstantiator instantiator)`

On construction it walks the spec and builds two indices:
- `structsByName` — maps both simple name and fully-qualified name to `StructNode`
- `enumsByName` — maps both simple name and fully-qualified name to `List<EnumValueNode>`

### IDL type → EntityValueKind and AttributeValueType

| IDL type | EntityValueKind | AttributeValueType |
|---|---|---|
| `boolean` | `EV_BOOL` | `AV_BOOLEAN` |
| `short`, `long`, `long long`, `char`, `octet`, int variants | `EV_INT64` | `AV_INT64` |
| `unsigned short`, `unsigned long`, `unsigned long long`, uint variants | `EV_UINT64` | `AV_UINT64` |
| `float` | `EV_FLOAT` | `AV_FLOAT` |
| `double`, `long double` | `EV_DOUBLE` | `AV_DOUBLE` |
| `string` / `STRING_TYPE` | `EV_STRING` | `AV_STRING` |
| `GUID_TYPE` | `EV_GUID` | `AV_GUID` |
| `SYSTEM_TIME_TYPE` | `EV_SYSTEM_TIME` | `AV_SYSTEM_TIME` |
| Sequence / array | `EV_BYTES` | `null` |
| IDL enum (in enumsByName) | `EV_INT64` | `AV_INT64` |
| IDL struct (in structsByName) | `EV_COMPOSITE` | `null` |

The composite detection rule: a `Scoped` type is composite if its name (simple
or FQ) is found in `structsByName` **and** is not one of the well-known FACE
types (`GUID_TYPE`, `SYSTEM_TIME_TYPE`, `STRING_TYPE`).

### topFields / subFields decomposition

**`$er.topLevelFields(struct)`** returns one `TopLevelField` per IDL struct
member:

| Field property | Getter | Description |
|---|---|---|
| `name` | `getName()` | Member name |
| `ordinal` | `getOrdinal()` | 0-based index within the parent struct |
| `composite` | `isComposite()` | `true` when the type resolves to a nested struct |
| `idlTypeName` | `getIdlTypeName()` | `IdlType.toString()` |
| `attributeValueType` | `getAttributeValueType()` | `null` for composites |
| `entityValueKind` | `getEntityValueKind()` | e.g. `"EV_DOUBLE"` |
| `subFields` | `getSubFields()` | Non-empty list of `SubField` when composite; empty otherwise |

**`SubField`** carries:
- `parentFieldName`, `name`, `compositeIndex` (0-based within the composite)
- `attributeValueType`, `entityValueKind`
- `qualifiedName` ("`parentName.subName`")

**`$er.flatFields(struct)`** produces the flat `FieldDescriptor` list used in
the C++ registration call. Each `FlatField` row represents one field in the
`EntityTypeDescriptor::fields` vector:

| Field property | Description |
|---|---|
| `qualifiedName` | `"fieldName"` or `"parentName.subName"` |
| `name` | Top-level field name |
| `subFieldName` | Sub-field name; `null` for simple fields |
| `ordinal` | Top-level ordinal (flat position into `EntityPayload`) |
| `positionIndex` | Index within immediate containing struct |
| `compositeIndex` | Index within `EntityValue.composite_val`; `-1` for simple fields |
| `attributeValueType` | `AttributeValueType` constant name |
| `entityValueKind` | `EntityValueKind` constant name |
| `simple` | `true` for non-composite fields |
| `compositeEntry` | `true` for composite sub-fields |

---

## 9. How to Add a New Entity Type to SampleModel

This is the most common extension task.

### Step 1 — Edit `SampleModel.yaml`

Add a new entry under `entities:` in
`Code/SampleModel/face_model/SampleModel.yaml`.

```yaml
entities:
  # ... existing entities ...

  - simple_name: MyEntity
    description:  "A new platform entity."
    includes:
      - GeoPosition.idl        # include any referenced supporting structs
    fields:
      - name:                  entity_id
        idl_type:              "::FACE::GUID_TYPE"
        attribute_value_type:  AV_GUID
        comment:               "Unique identifier"

      - name:                  position
        idl_type:              GeoPosition
        nested:                true
        comment:               "WGS-84 geographic position"

      - name:                  label
        idl_type:              "::FACE::STRING_TYPE"
        attribute_value_type:  AV_STRING
        comment:               "Human-readable label"
```

SampleModel uses `struct_name_pattern: "{name}Entity"`, so `simple_name` is the
base name without the `Entity` suffix:

```yaml
- simple_name: Widget   # → struct name: WidgetEntity
```

Do **not** write `simple_name: WidgetEntity` — that would produce
`WidgetEntityEntity`.

### Step 2 — Rebuild IDL

Run `face-idl-gen` (or trigger the CMake target `SampleModel_IdlGen`) to
regenerate IDL. The new entity struct appears in
`gen/idl/IDL/Entities/WidgetEntity.idl` and `ENTITY_TYPE_WIDGET` is added to
`EntityTypeEnum` in its alphabetically-sorted position.

### Step 3 — Run face-codegen

Run `face-codegen generate` (or trigger the CMake target
`SampleModel_ReactorCodegen`). The `name_pattern: ".*Entity$"` filter in the
manifest picks up `WidgetEntity` automatically.

New files generated:
- `WidgetEntityRegistrar.hpp` (from `entity_registrar.hpp.vm`, STRUCT scope)
- `EntityModelConversionTable.hpp` regenerated (from `entity_model_conversion_table.hpp.vm`, SPEC scope)

No manifest changes are needed for entity structs — the `.*Entity$` pattern
catches any struct whose name ends in `Entity`.

### Step 4 — Update C++ consumers

The generated `WidgetEntityRegistrar.hpp` must be included in the registrar
call site (wherever `Register_All_Entity_Types` is implemented). The
`EntityModelConversionTable.hpp` is regenerated automatically and includes the
new entity.

---

## 10. How to Write a New Template

### Overview

A Velocity 2.3 template (`.vm` file) in the template directory is a plain-text
file with `#directive` and `${variable}` expressions. The pipeline renders it
once per scope element (or once for `SPEC` scope).

### Starting from a context variable reference

Check what is in context for your chosen scope (see §4). For a `STRUCT` scope
template:

```velocity
## my_template.hpp.vm
## Context: $struct (StructNode), $module (ModuleNode), $namespaces (List<String>)
## Plus base context: $spec, $instantiator, $model, $uops
## Plus manifest variables: $model_namespace, $project_namespace, etc.
## Plus helpers: $er (EntityReactorHelper)

#pragma once

namespace ${project_namespace} {

// ${struct.name} has ${struct.members.size()} members.
struct ${struct.name}Helper {
#foreach ($m in $struct.members)
    // field: ${m.name} : ${m.type}
#end
};

} // namespace ${project_namespace}
```

Note that `$struct.members` uses the JavaBean getter `getMembers()` under the
hood. If `$struct.members` renders as the literal string `${struct.members}`,
the getter is
### Add `##!` directives to the template

For a template that should run once per matching struct, add at the top of the
`.vm` file (before any non-comment Velocity content):

```
##! for_each: STRUCT
##! filter:   .*Entity$
##! output:   {struct.name}Helper.hpp
```

The pipeline picks this up automatically — no manifest entry is needed. If you
also need a manifest entry (e.g. to override `filter` for a specific project),
add a `generations:` entry in `codegen.yaml`.

### The 9 Velocity rules to follow

All of these are hard-won lessons documented in full in
`docs/velocity-template-gotchas.md`:

1. **Use `$foreach.hasNext`, not `$velocityHasNext`.**
   In Velocity 2.x, `$velocityHasNext` is unreliable inside nested loops.

2. **Only escape real VTL directives.**
   `\#include` emits `#include`. `#pragma once` needs no escaping — `#pragma`
   is not a VTL directive.

3. **Expose JavaBean getters.**
   Velocity 2.x accesses object properties via `getXxx()`. Public fields
   alone are not reliably accessible. Every Java object put into context must
   have getters for every property used in templates.

4. **IDL type names already carry `::`.**
   `IdlType.Scoped.toString()` returns `"::FACE::DM::SampleModel::TrackEntity"`.
   Do not prepend `::` in the template.

5. **No arithmetic inside `${...}`.**
   Use `#set ($n = $idx + 1)` before referencing `$n`.

6. **ASCII only in `.vm` files.**
   Non-ASCII characters (arrows, em-dashes) can cause Velocity tokenizer
   failures. Replace with ASCII equivalents.

7. **Cast enum-to-integer assignments.**
   When writing to a field declared as an IDL enum in a FACE struct, use
   `static_cast<decltype(dst.field)>(...)` to avoid C++ compiler errors.

8. **Match `EntityValue` / `AttributeValue` C++ API exactly.**
   Use `$er.evFromMethod(kind)` and `$er.evScalarMember(kind)` to get the
   correct method/member names. Do not hard-code them.

9. **Verify file endings after programmatic creation.**
   Velocity stops at EOF and produces no error for truncated templates. After
   generating or editing a `.vm` file programmatically, run `tail -5` to
   confirm the file ends at the expected closing brace.

---

## 11. Template Sets — Type Distinctions

Three categories of template sets ship in `code_generator/templates/`. Each
is driven by a different pipeline and must not be confused.

| Template location | Pipeline | File ext | Directive style |
|---|---|---|---|
| `templates/languages/*/` | Language binding generator | `.vm` | `language.yaml` routing |
| `templates/entity-reactor-idl/` | `face-idl-gen generate-entity-idl` | `.vtl` | `## @foreach entity` |
| `templates/data-model-idl/` | `face-idl-gen` | `.vtl` | `## @foreach ...` |
| `Code/EntityReactor/codegen/` | `face-codegen generate` | `.vm` | `##!` directives |

### `##!`-directive templates (`face-codegen generate`)

The templates in `Code/EntityReactor/codegen/` (and any project-specific
template directory) use the `##!` directive system described in §3. These are
plain `.vm` Velocity templates driven by the `CodeGenPipeline`.

### `language.yaml`-driven templates (language binding generator)

Templates in `templates/languages/{cpp,csharp,java,python}/` are driven by
`language.yaml` routing configuration. Key fields in `language.yaml`:

| Field | Description |
|---|---|
| `iteration.strategy` | `per_idl_file` (one output per `.idl`) or `per_construct` (one output per IDL construct) |
| `templates.struct` | Template to use for struct constructs |
| `templates.enum` | Template to use for enum constructs |
| `templates.interface` | Template to use for interface constructs |
| `templates.template_inst` | Template to use for template instantiations |
| `primitive_types` | IDL primitive → target language type map |
| `parameterized_types` | Sequence/array/string → target language type map |

Language templates do **not** use `##!` directives.

### `.vtl` IDL generation templates (`face-idl-gen`)

Templates in `templates/entity-reactor-idl/` and `templates/data-model-idl/`
are Apache Velocity `.vtl` files (note the extension) driven by `face-idl-gen`'s
own pipeline. They use `## @foreach entity` style comment headers and a
different context (`$model`, `$entity`, `$structName`, etc.). Do not add
`##!` directives to `.vtl` files — the directive scanner ignores `.vtl`.

#### Entity source for `generate-entity-idl` (Workflow A vs. Workflow B)

`face-idl-gen generate-entity-idl` supports two entity-source paths that feed
the same `IdlGeneratorPipeline`:

**Workflow A — YAML/JSON model (default).**  The model file is read by
`ModelReaderFactory` → `EntityModelMapper` → `EntityModel`.  Struct names use
the identity pattern (`{name}`).

**Workflow B — `.face` template elements.**  Activated by `--entities` or
`--entity-source GROUP`.  `FaceTemplateEntityReader` (in `reader.face`) reads
the named `um:UoPModel` group from the `.face` XMI file using a two-pass
algorithm:

- **Pass 1 — registration:** walks direct children of the group; registers each
  `uop:Template` and `uop:CompositeTemplate` as a `TssTypeData` keyed by UUID.
- **Pass 2 — field resolution:** for `uop:Template` elements, parses the
  `boundQuery` attribute (SQL-like `SELECT … FROM …` spec) and resolves each
  selected field against `platform:Entity` elements in the full document; for
  `uop:CompositeTemplate` elements, resolves `TemplateComposition` children
  to their component types.

`FaceTemplateEntityReader.read()` delegates to `TssToEntityModelAdapter.adaptType()`
(package-private) to convert each `TssTypeData` to an `EntityData`, then
returns an `IdlModelData` with `structNamePattern = "{name}Entity"`.  This
causes the pipeline to name output files `<Name>Entity.idl` and use
`<Name>Entity` as the IDL struct name.

The reader throws `IllegalArgumentException` if the named group is not found
in the document; it returns an empty entities list (no exception) if the group
exists but contains no template children.

---

## 12. EntityReactor C++ Design

The EntityReactor Base Layer lives in `Code/EntityReactor/`. The key headers
are in `include/WARHEX/EntityReactor/`. This is a summary of the intended
design; some parts are not yet fully implemented.

### Core types

**`EntityPayload`** (`EntityPayload.hpp`)

Generic key-value field store for one entity instance. Fields are accessed by
`FieldOrdinal` (a `uint32_t` zero-based index into the field vector) for O(1)
access. Holds a `const EntityTypeDescriptor*` for bounds checking and
introspection. The field vector is pre-allocated to `descriptor.fields.size()`
slots on construction.

**`EntityValue`** (`EntityPayload.hpp`)

Hand-rolled C++14 discriminated value type. Does not use `std::variant` to
preserve C++14 portability for avionics toolchains. Stores scalar values in a
C union (`bool_val`, `int_val`, `uint_val`, `float_val`, `double_val`,
`guid_val`, `time_val`). Non-trivial types (`string_val`, `bytes_val`,
`composite_val`) are separate members. The active member is indicated by
`EntityValueKind kind`.

`EV_COMPOSITE` stores `vector<EntityValue>` for nested structs, arrays, and
union branches. The self-referential `vector<EntityValue>` is valid in C++14
because `vector`'s size is fixed regardless of `T`.

Factory functions (`from_bool`, `from_int`, `from_uint`, etc.) are the
preferred construction path — they avoid implicit-conversion ambiguity between
numeric types.

**`AttributeValue`** (`Types.hpp`)

Tagged union for subscription filter conditions and field accessor return
values. Uses `AttributeValueData` (a C union) for storage. Only scalar types
— no composite support (subscription conditions are scalar only). Factory
functions follow the same naming convention as `EntityValue`.

**`EntityTypeDescriptor`** (`Types.hpp`)

Schema record for one registered entity type. Contains:

| Field | Type | Description |
|---|---|---|
| `entity_type` | `EntityTypeEnum` value | Must not be 0 |
| `type_name` | `string` | FACE dot-notation name, e.g. `"FACE.DM.SampleModel.Track"` |
| `fields` | `vector<FieldDescriptor>` | One per leaf field (flat, not nested) |
| `comparator` | `Comparator` functor | Assigned by `Register_Entity_Type` |

**`FieldDescriptor`** (`Types.hpp`)

Describes one field in the registration call. The `entity_registrar.hpp.vm`
template generates these. Key fields:

| Field | Description |
|---|---|
| `qualified_name` | `"fieldName"` or `"parentName.subName"` |
| `position_index` | Index within the immediate containing struct |
| `field_type` | `AttributeValueType` for this field |
| `payload_ordinal` | Index into `EntityPayload.m_fields` for direct access |
| `composite_index` | Index within `EntityValue.composite_val`; `COMPOSITE_INDEX_NONE` for scalars |
| `accessor` | `std::function<AttributeValue(const EntityPayload&)>` — extracts one value |

**`Registry`** (`Registry.hpp`)

Pure-virtual interface with two operations:

- `Register_Entity_Type(descriptor, return_code)` — accepts and stores one
  `EntityTypeDescriptor`. Must be called before `Finalize_Registration`.
- `Finalize_Registration(return_code)` — closes the registration window. After
  this call, `Create_Connection` is permitted.

**`Reactor`** (`Reactor.hpp`)

Pure-virtual connection-facing interface. Derives from
`FACE::Configuration_Injectable::Injectable`. Operations include `Initialize`,
`Create_Connection`, `Destroy_Connection`, `ProcessCrudRequest`, and
`Validate_Subscription`.

**`ReactorImpl`** — the concrete class that implements both `Registry` and
`Reactor`. Not yet fully implemented.

### Initialization sequence

```
1. Set_Reference          (inject FACE::Configuration, if required)
2. Initialize
3. Register_Entity_Type   (via Registry; call Register_All_Entity_Types)
4. Finalize_Registration  (via Registry)
5. Create_Connection      (now permitted for TSS clients)
```

### What is and is not yet implemented

| Component | Status |
|---|---|
| `EntityPayload` / `EntityValue` / `EntityValueKind` | Implemented |
| `AttributeValue` / `AttributeValueType` | Implemented |
| `EntityTypeDescriptor` / `FieldDescriptor` | Implemented |
| `Registry` interface | Implemented |
| `Reactor` interface | Implemented |
| `ReactorImpl` concrete class | **Partial — in active development** |
| Subscription query compiler / filter evaluation | **Not yet implemented** |
| Event dispatch (EntityEvent) | **Not yet implemented** |
| Generated registrar headers (from face-codegen) | Implemented and compile |
| Generated TypedTS transport service skeletons | Implemented and compile |

The generated registrar headers (`{Entity}Registrar.hpp`) are ready to be used
once `ReactorImpl` provides a concrete `Registry&` to call
`Register_Entity_Type` on.

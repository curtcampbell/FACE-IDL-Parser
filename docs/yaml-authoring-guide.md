# Template Authoring Guide — `face-codegen`

A reference for writing templates and manifests consumed by `face-codegen generate`.

> **Primary mechanism:** As of the FACE TS 3.2 refactor, templates are
> self-describing via `##!` comment directives. A `codegen.yaml` manifest is
> **optional** and used only for per-project variable overrides. The `generations:`
> list in `codegen.yaml` is a legacy/override mechanism; for most template sets
> the `##!` directives replace it entirely.

---

## Contents

1. [How Code Generation Is Controlled](#1-how-code-generation-is-controlled)
2. [`##!` Directives — The Primary Mechanism](#2--directives----the-primary-mechanism)
   - 2.1 [Directive reference](#21-directive-reference)
   - 2.2 [for_each scope values](#22-for_each-scope-values)
   - 2.3 [filter — name filtering](#23-filter----name-filtering)
   - 2.4 [output path tokens](#24-output-path-tokens)
3. [`codegen-defaults.yaml` — Template-Set Defaults](#3-codegen-defaultsyaml----template-set-defaults)
4. [Auto-Derived Variables](#4-auto-derived-variables)
5. [`codegen.yaml` — Per-Project Overrides (Legacy / Optional)](#5-codegenyaml----per-project-overrides-legacy--optional)
   - 5.1 [Top-level structure](#51-top-level-structure)
   - 5.2 [helpers — registering built-in helpers](#52-helpers----registering-built-in-helpers)
   - 5.3 [variables — project-level values](#53-variables----project-level-values)
   - 5.4 [generations — override-style generation entries](#54-generations----override-style-generation-entries)
   - 5.5 [Annotated full example (legacy/override style)](#55-annotated-full-example-legacyoverride-style)
6. [Velocity Context Reference](#6-velocity-context-reference)
7. [Manifest Placement and the `--manifest` Flag](#7-manifest-placement-and-the---manifest-flag)
8. [Directive vs. Manifest Approach — Side-by-Side](#8-directive-vs-manifest-approach----side-by-side)
9. [Common Mistakes](#9-common-mistakes)

---

## 1. How Code Generation Is Controlled

The pipeline assembles a generation plan from three sources, in priority order
(highest to lowest):

| Source | Priority | Purpose |
|---|---|---|
| `--var KEY=VALUE` CLI flags | Highest | One-off variable overrides for a single run |
| `codegen.yaml` (`--manifest`) | High | Per-project variables and optional override `generations:` list |
| `codegen-defaults.yaml` in `--template-dir` | Medium | Template-set-wide defaults (variables, helpers) |
| IDL-derived variables | Low | Automatically derived from the parsed IDL AST |
| `##!` directives in `.vm` files | (generation plan) | Declares which templates run, what they iterate, where they write |

The `##!` directives define *what* to generate. The variable sources define
*what values* are available to templates and output paths.

---

## 2. `##!` Directives — The Primary Mechanism

Each `.vm` file in a template set declares its own iteration behavior using
`##!` comment directives at the top of the file.

### 2.1 Directive reference

```
##! for_each: STRUCT
##! output:   {struct.name}Registrar.hpp
##! filter:   .*Entity$
##! driver:   true
```

| Directive | Required | Description |
|---|---|---|
| `##! for_each:` | Yes | Iteration scope (see §2.2). Case-insensitive. |
| `##! output:` | Yes (non-driver) | Output file path pattern. Tokens in `{braces}` are substituted per element (see §2.4). Omit for driver templates. |
| `##! filter:` | No | Java regex matched against the element name (or forward-slash-normalized relative path for `FILE` scope). Absent or blank matches all elements. |
| `##! driver:` | No | `true` marks the template as a driver that internally dispatches to leaf templates via `#set($outFile = ...)` / `#parse(...)`. |

**Critical:** directives must appear before any non-comment Velocity content.
A `#set`, blank line, or non-comment output before a `##!` line will cause
the directive to be missed silently.

### 2.2 `for_each` scope values

| Scope | Iterates over | Velocity context variable |
|---|---|---|
| `SPEC` | The entire merged spec — renders **once** | (uses base context) |
| `MODULE` | Each top-level `ModuleNode` | `$module`, `$namespaces` |
| `STRUCT` | Every `StructNode` at any nesting depth | `$struct`, `$module`, `$namespaces` |
| `INTERFACE` | Every `InterfaceNode` at any nesting depth | `$iface`, `$module`, `$namespaces` |
| `TEMPLATE_INST` | Every `Typed<D,R>` instantiation | `$inst`, `$resolved`, `$module`, `$namespaces` |
| `FILE` | Each `.idl` source file under `--idl-dir` | `$file` (`IdlFileUnit`) |
| `ENUM` | Every `EnumNode` at any nesting depth | `$enum`, `$module`, `$namespaces` |
| `TYPEDEF` | Every `TypedefNode` at any nesting depth | `$typedef`, `$module`, `$namespaces` |
| `UNION` | Every `UnionNode` at any nesting depth | `$union`, `$module`, `$namespaces` |
| `CONST` | Every `ConstNode` at any nesting depth | `$const`, `$module`, `$namespaces` |
| `UOP` | Each `UoPData` in the loaded `.face` model | `$uop` |
| `CONNECTION` | Each `ConnectionData` across all UoPs | `$conn`, `$uop` |
| `GLOBAL` | Once per run (like `SPEC`) | (uses base context) |

`$namespaces` is a `List<String>` of the module name path from the IDL root to
the enclosing module, e.g. `["FACE", "DM", "SampleModel"]`.

**`FILE` scope — `$file` properties:**

| Property | Getter | Example |
|---|---|---|
| `$file.stemName` | `getStemName()` | `"EntityEvent"` |
| `$file.relativePath` | `getRelativePath()` | `"TypedTS/EntityEvent.idl"` |
| `$file.relativeOutputPath` | `getRelativeOutputPath()` | `"TypedTS/EntityEvent.hpp"` |
| `$file.absolutePath` | `getAbsolutePath()` | Full OS path |
| `$file.definitions` | `getDefinitions()` | Top-level IDL definitions in this file only |

Note: `$file.definitions` contains only definitions from that specific file,
not transitively included types. `sourceFile` on `IdlDefinition` is set for
nodes produced by FILE scope iteration and null on merged-spec nodes.

### 2.3 `filter` — name filtering

`filter` is a **full-string Java regex** applied via `String.matches()`. The
pattern must match the *entire* name string — not just a substring.

| Pattern | Behaviour |
|---|---|
| `.*Entity$` | Matches any name ending in `Entity` |
| `^EntityEvent$` | Matches exactly `EntityEvent` (anchors optional; `String.matches()` is full-string) |
| `TypedTS/.*` | For FILE scope: matches files under `TypedTS/` directory |
| Absent / blank | Matches every element in the scope |

**Name tested by scope:**

| Scope | Name tested |
|---|---|
| `STRUCT` | `StructNode.name()` |
| `INTERFACE` | `InterfaceNode.name()` |
| `TEMPLATE_INST` | `TemplateInstNode.alias()` |
| `MODULE` | `ModuleNode.name()` |
| `ENUM` | `EnumNode.name()` |
| `TYPEDEF` | `TypedefNode.name()` |
| `UNION` | `UnionNode.name()` |
| `CONST` | `ConstNode.name()` |
| `UOP` | `UoPData.getName()` |
| `CONNECTION` | `ConnectionData.getName()` |
| `FILE` | Forward-slash-normalized relative path (e.g. `TypedTS/EntityEvent.idl`) |
| `SPEC` / `GLOBAL` | (filter ignored) |

### 2.4 output path tokens

Tokens in `{braces}` in `##! output:` are substituted per element. Variable
values from all sources (auto-derived, `codegen-defaults.yaml`, `codegen.yaml`,
`--var`) are also available as tokens.

**Tokens by scope:**

| Scope | Available tokens |
|---|---|
| `SPEC` / `GLOBAL` | None (output is a literal path) |
| `MODULE` | `{module.name}` |
| `STRUCT` | `{struct.name}`, `{module.name}` |
| `INTERFACE` | `{iface.name}`, `{module.name}` |
| `TEMPLATE_INST` | `{inst.alias}`, `{inst.templateName}`, `{module.name}` |
| `ENUM` | `{enum.name}`, `{module.name}` |
| `TYPEDEF` | `{typedef.name}`, `{module.name}` |
| `UNION` | `{union.name}`, `{module.name}` |
| `CONST` | `{const.name}`, `{module.name}` |
| `FILE` | `{file.stem}`, `{file.name}`, `{file.relativePath}`, `{file.relativeOutputPath}` |
| `UOP` | `{uop.name}` |
| `CONNECTION` | `{conn.name}`, `{uop.name}` |

Plus all variable keys (e.g. `{model_namespace}`, `{project_namespace}`).

**Examples:**

```
##! output: {struct.name}Registrar.hpp
##! output: {project_namespace}/{inst.alias}TS.hpp
##! output: EntityModelConversionTable.hpp
##! output: {file.relativeOutputPath}
```

---

## 3. `codegen-defaults.yaml` — Template-Set Defaults

`codegen-defaults.yaml` lives at the root of a template set and declares
constants and helpers that apply to every project using that template set.

```yaml
# Example: Code/EntityReactor/codegen/codegen-defaults.yaml
variables:
  reactor_namespace: "WARHEX::EntityReactor"
  reactor_include:   "WARHEX/EntityReactor"
helpers:
  - id: entity_reactor
    as: er
```

Values in `codegen-defaults.yaml` are the lowest-priority variable source
(other than IDL-derived variables). A project's `codegen.yaml` or `--var`
flags can override any value.

---

## 4. Auto-Derived Variables

The pipeline derives these 5 variables automatically from the IDL AST. They
are available in templates and as output path tokens without any manifest
declaration.

| Variable | Derived from |
|---|---|
| `$model_namespace` | IDL module path of entity structs (e.g. `FACE::DM::SampleModel`) |
| `$project_namespace` | Last component of `model_namespace` (e.g. `SampleModel`) |
| `$face_tss_namespace` | TSS module path from `Typed<>` instantiations |
| `$entity_payload_idl` | Path to the generated EntityPayload header |
| `$entity_payload_idl_enum` | Enum type name in the payload IDL |

Override these in `codegen.yaml` or via `--var` if the auto-derived values are
incorrect for a project.

---

## 5. `codegen.yaml` — Per-Project Overrides (Legacy / Optional)

`codegen.yaml` is optional. Use it only when you need to override defaults or
provide variables that cannot be auto-derived. Do **not** delete your existing
`codegen.yaml` — the `generations:` list is still honored when present and is
useful for projects that need to override what `##!` directives would otherwise
produce.

### 5.1 Top-level structure

```yaml
# Optional: append this subdirectory to --output-dir before writing all files
output_subdirectory: cpp

# Optional: path to a language.yaml descriptor (enables $types in templates)
language_dir: path/to/languages/cpp

# Optional: built-in helpers to activate (usually declared in codegen-defaults.yaml)
helpers:
  - id: entity_reactor
    as: er

# Optional: project-specific variables (override auto-derived or defaults)
variables:
  model_namespace: "FACE::DM::MyModel"
  project_namespace: "MyModel"

# Optional: generation entries (override-style; supplements or replaces ##! directives)
generations:
  - template:     my_template.hpp.vm
    for_each:     STRUCT
    name_pattern: ".*Entity$"
    output:       "{struct.name}.hpp"
```

| Field | Type | Required | Description |
|---|---|---|---|
| `output_subdirectory` | string | No | Appended to `--output-dir` before writing any files. |
| `language_dir` | string | No | Path to a `language.yaml` descriptor directory. Enables `$types` in templates. |
| `helpers` | list | No | Built-in helpers to activate. Usually declared in `codegen-defaults.yaml` instead. |
| `variables` | map | No | Project-specific key→value pairs. Override auto-derived or `codegen-defaults.yaml` values. |
| `generations` | list | No | Override-style generation entries (see §5.4). Not required when `##!` directives are present. |

### 5.2 helpers — registering built-in helpers

```yaml
helpers:
  - id: entity_reactor
    as: er          # available as $er in all templates
```

| Field | Required | Description |
|---|---|---|
| `id` | Yes | Helper identifier registered in `HelperRegistry`. Currently: `"entity_reactor"`. |
| `as` | No | Velocity context variable name. Defaults to the helper's built-in default (`"er"` for `entity_reactor`). |

Key methods on the `entity_reactor` helper (`$er`):

| Method | Returns | Description |
|---|---|---|
| `$er.topLevelFields($struct)` | `List<TopLevelField>` | One entry per IDL struct member. Composite members carry sub-fields. |
| `$er.flatFields($struct)` | `List<FlatField>` | Flattened rows for `FieldDescriptor` registration (one per leaf field). |
| `$er.entityValueKind($type)` | `String` | `EntityValueKind` constant, e.g. `"EV_DOUBLE"`. |
| `$er.attributeValueType($type)` | `String` | `AttributeValueType` constant, e.g. `"AV_DOUBLE"`. `null` for composites. |
| `$er.isComposite($type)` | `boolean` | `true` when the type resolves to a nested struct. |
| `$er.enumValues("Name")` | `List<EnumValueNode>` | All values of a named IDL enum. |
| `$er.stripEntitySuffix("TrackEntity")` | `String` | `"Track"` |
| `$er.toMemberName("TrackEntity")` | `String` | `"track"` |
| `$er.toEntityTypeEnum("TrackEntity")` | `String` | `"ENTITY_TYPE_TRACK"` |
| `$er.toSnakeCase("GeoPosition")` | `String` | `"geo_position"` |
| `$er.evFromMethod("EV_GUID")` | `String` | `"from_guid"` |
| `$er.evScalarMember("EV_INT64")` | `String` | `"int_val"` |
| `$er.avFromMethod("AV_SYSTEM_TIME")` | `String` | `"from_time"` |

See `docs/design-guide.md` for the complete `EntityReactorHelper` API reference.

### 5.3 variables — project-level values

```yaml
variables:
  model_namespace:    "FACE::DM::SampleModel"
  project_namespace:  "SampleModel"
```

Every key in `variables` is placed into the Velocity context under its exact name
(`$model_namespace`, `$project_namespace`, etc.) and as an output path token
(`{model_namespace}`, `{project_namespace}`, etc.).

**Rules:**

- Keys must be valid Velocity identifiers (no spaces; start with a letter or `_`).
- Values must be strings. Numeric values are accepted but stored as strings.
- Keys that conflict with scope variables (`$struct`, `$inst`, etc.) are shadowed
  by the scope variables in child contexts — avoid reusing those names.
- Auto-derived variable values are overridden by any explicit `variables:` entry
  with the same key.

### 5.4 generations — override-style generation entries

When a `generations:` list is present in `codegen.yaml`, the pipeline uses those
entries **in addition to** (or as an override of) the `##!` directive scan. Use
this when you need project-specific generation logic that cannot be expressed in
shared `##!` directives.

```yaml
generations:
  - template:     entity_registrar.hpp.vm
    for_each:     STRUCT
    name_pattern: ".*Entity$"       # same semantics as ##! filter:
    output:       "{struct.name}Registrar.hpp"
```

| Field | Required | Description |
|---|---|---|
| `template` | Yes | Template file path relative to `--template-dir`. Must end in `.vm`. |
| `for_each` | Yes | Iteration scope. Case-insensitive. Same values as `##! for_each:`. |
| `name_pattern` | No | Java regex matched against the primary element name. Same semantics as `##! filter:`. |
| `output` | Yes | Output file path pattern. Same token syntax as `##! output:`. |

### 5.5 Annotated full example (legacy/override style)

The example below shows the **old-style** manifest where `generations:` drove all
code generation. This style is now superseded by `##!` directives for template
sets that ship with `codegen-defaults.yaml`. It remains valid as an override
mechanism or for projects that have not yet migrated.

```yaml
# SampleModel EntityReactor — legacy/override-style codegen.yaml
# Compare with the directive-based approach in §8.

# ── Helpers ──────────────────────────────────────────────────────────────────
# In the directive-based approach these live in codegen-defaults.yaml.
helpers:
  - id: entity_reactor
    as: er

# ── Variables ─────────────────────────────────────────────────────────────────
# Only list variables that differ from codegen-defaults.yaml or auto-derived.
# In the directive-based approach, model_namespace and project_namespace are
# auto-derived; reactor_namespace and reactor_include come from codegen-defaults.yaml.
variables:
  model_namespace:         "FACE::DM::SampleModel"
  project_namespace:       "SampleModel"
  reactor_namespace:       "WARHEX::EntityReactor"
  reactor_include:         "WARHEX/EntityReactor"
  face_tss_namespace:      "FACE::TSS::SampleModel"
  entity_payload_idl:      "FACE/DM/SampleModel/EntityPayload.hpp"
  entity_payload_idl_enum: "EntityTypeEnum"

# ── Generations ───────────────────────────────────────────────────────────────
# In the directive-based approach these entries are replaced by ##! directives
# in each .vm file. The entries below are equivalent to what ##! directives
# in entity_registrar.hpp.vm and entity_model_conversion_table.hpp.vm declare.
generations:

  - template:     entity_registrar.hpp.vm
    for_each:     STRUCT
    name_pattern: ".*Entity$"
    output:       "{struct.name}Registrar.hpp"

  - template:     entity_model_conversion_table.hpp.vm
    for_each:     SPEC
    output:       "EntityModelConversionTable.hpp"

  - template:     entity_crud_ts.hpp.vm
    for_each:     TEMPLATE_INST
    name_pattern: "EntityCrudRequest_EntityCrudResponse"
    output:       "{project_namespace}/{inst.alias}TS.hpp"

  - template:     entity_crud_ts.cpp.vm
    for_each:     TEMPLATE_INST
    name_pattern: "EntityCrudRequest_EntityCrudResponse"
    output:       "{inst.alias}TS.cpp"

  - template:     subscription_ts.hpp.vm
    for_each:     TEMPLATE_INST
    name_pattern: "SubscriptionRequest_SubscriptionResponse"
    output:       "{project_namespace}/{inst.alias}TS.hpp"

  - template:     subscription_ts.cpp.vm
    for_each:     TEMPLATE_INST
    name_pattern: "SubscriptionRequest_SubscriptionResponse"
    output:       "{inst.alias}TS.cpp"

  - template:     entity_event_ts.hpp.vm
    for_each:     TEMPLATE_INST
    name_pattern: "^EntityEvent$"
    output:       "{project_namespace}/{inst.alias}TS.hpp"

  - template:     entity_event_ts.cpp.vm
    for_each:     TEMPLATE_INST
    name_pattern: "^EntityEvent$"
    output:       "{inst.alias}TS.cpp"

  - template:     sample_model_policy.hpp.vm
    for_each:     SPEC
    output:       "{project_namespace}/{project_namespace}Policy.hpp"

  - template:     sample_model_policy.cpp.vm
    for_each:     SPEC
    output:       "{project_namespace}Policy.cpp"
```

**Equivalent directive-based approach** (what `entity_registrar.hpp.vm` now
contains at the top instead of the manifest entry):

```
##! for_each: STRUCT
##! filter:   .*Entity$
##! output:   {struct.name}Registrar.hpp
```

No manifest changes are needed when a new entity struct is added — the
`.*Entity$` filter picks it up automatically.

---

## 6. Velocity Context Reference

The pipeline assembles the Velocity context in layers. Inner layers shadow
outer ones for the same key name.

**Always present (base context):**

| Variable | Type | Description |
|---|---|---|
| `$spec` | `IdlSpecification` | The merged, fully-parsed IDL AST |
| `$instantiator` | `TemplateInstantiator` | Resolves `Typed<D,R>` instantiations |
| `$model` | `UoPModelData` or `null` | UoP model from `--face-file`; null when not supplied |
| `$uops` | `List<UoPData>` | All UoPs; empty when `--face-file` not supplied |
| `$types` | `TypeResolver` or absent | Type resolution helper; present only when `language_dir` is set |

**From `helpers`:** each helper under its `as` name (e.g. `$er` for `entity_reactor`).

**From variables (all sources):** each key→value pair injected directly, e.g.
`$model_namespace`, `$reactor_namespace`.

**Added per scope:**

| Scope | Variables added |
|---|---|
| `MODULE` | `$module` (`ModuleNode`), `$namespaces` (`List<String>`) |
| `STRUCT` | `$struct` (`StructNode`), `$module`, `$namespaces` |
| `INTERFACE` | `$iface` (`InterfaceNode`), `$module`, `$namespaces` |
| `TEMPLATE_INST` | `$inst` (`TemplateInstNode`), `$resolved` (`InstantiationResult`), `$module`, `$namespaces` |
| `FILE` | `$file` (`IdlFileUnit`) |
| `ENUM` | `$enum` (`EnumNode`), `$module`, `$namespaces` |
| `TYPEDEF` | `$typedef` (`TypedefNode`), `$module`, `$namespaces` |
| `UNION` | `$union` (`UnionNode`), `$module`, `$namespaces` |
| `CONST` | `$const` (`ConstNode`), `$module`, `$namespaces` |
| `UOP` | `$uop` (`UoPData`) |
| `CONNECTION` | `$conn` (`ConnectionData`), `$uop` |

The `$resolved` object for `TEMPLATE_INST` scope:

| Property | Type | Description |
|---|---|---|
| `$resolved.definitions` | `List<IdlDefinition>` | Substituted interfaces/structs from the template body |
| `$resolved.localInterfaceNames` | `Set<String>` | Interface names declared inside the template body |
| `$resolved.resolvedActuals` | `List<IdlType>` | Concrete actual parameter types in formal order |

---

## 7. Manifest Placement and the `--manifest` Flag

`face-codegen generate` no longer requires `--manifest`. When omitted:

1. The pipeline probes `--template-dir` for `codegen-defaults.yaml` and loads it
   as the source of template-set-level variables and helpers.
2. The pipeline scans `##!` directives from all `.vm` files in `--template-dir`
   to build the generation plan.
3. An empty manifest is used as the fallback if neither file is present.

Pass `--manifest` explicitly when a project has variables or `generations:`
entries that override or extend the template set defaults:

```bat
face-codegen generate ^
  --template-dir Code\EntityReactor\codegen ^
  --manifest     Code\SampleModel\face_model\codegen.yaml ^
  --output-dir   out\reactor
```

The `--template-dir` still controls where `.vm` files are looked up; `--manifest`
only overrides the manifest file path.

---

## 8. Directive vs. Manifest Approach — Side-by-Side

The table below compares the same generation task expressed with `##!` directives
(current approach) vs. a `codegen.yaml` `generations:` entry (legacy/override).

| Aspect | `##!` directives | `generations:` entry |
|---|---|---|
| Location | Top of the `.vm` file | `codegen.yaml` |
| Scope | Per-template | Per-entry in manifest |
| Project sharing | Automatic — same template set, same plan | Requires each project to duplicate |
| Override | Supplement with `generations:` in `codegen.yaml` | Already in manifest |
| `filter` / `name_pattern` | `##! filter:` directive | `name_pattern:` field |
| Output path | `##! output:` directive | `output:` field |
| Driver support | `##! driver: true` | Not available |

**When do you still need `codegen.yaml`?**

- Your project uses different variable values than `codegen-defaults.yaml` sets
  (e.g. a different `model_namespace`).
- You need auto-derived variables overridden (e.g. `entity_payload_idl`).
- You need project-specific generation entries that the shared template set
  does not cover (use `generations:` in addition to `##!` directives).

---

## 9. Common Mistakes

### Wrong: `##!` directive not at the top of the file

```velocity
## WRONG — #set before ##! causes the directive to be missed
#set($someVar = "value")
##! for_each: STRUCT
##! output:   {struct.name}.hpp

## CORRECT — ##! directives before any Velocity content
##! for_each: STRUCT
##! output:   {struct.name}.hpp
#set($someVar = "value")
```

### Wrong: `filter` not anchored when an exact match is needed

```yaml
## ❌ "EntityEvent" with String.matches() matches only the whole string anyway,
##    but explicit anchors improve readability for edge cases with wildcards.
##! filter:   EntityEvent

## ✅ Explicit exact match
##! filter:   ^EntityEvent$
```

### Wrong: referencing a token from the wrong scope

```
## ❌ {inst.alias} is only valid when for_each: TEMPLATE_INST
##! for_each: STRUCT
##! output:   {inst.alias}Registrar.hpp
```

Output paths with unresolved tokens cause the file to be skipped with a warning.
Match tokens to their scope (see §2.4).

### Wrong: variables key contains spaces or special characters

```yaml
# ❌
variables:
  model namespace: "FACE::DM::MyModel"

# ✅
variables:
  model_namespace: "FACE::DM::MyModel"
```

### Wrong: `language_dir` used without a `language.yaml` in that directory

If `language.yaml` is absent, `face-codegen` throws an `IOException` at startup.

### Wrong: `generations` list is empty (when used)

```yaml
generations: []   # not valid when present — omit the key entirely instead
```

The manifest validator throws an `IllegalStateException` on startup when
`generations:` is present but empty.

### Wrong: template path is absolute or escapes template-dir

```yaml
# ❌
template: /abs/path/to/template.vm
template: ../other/template.vm

# ✅
template: entity_registrar.hpp.vm
template: cpp/entity_registrar.hpp.vm
```

### Wrong: `$file.definitions` used to get transitively included types

`$file.definitions` contains only the top-level definitions declared in that
specific `.idl` file. Transitively included types (from `#include` directives)
are not in this list — they appear in the merged `$spec.definitions` instead.

# face-codegen — User Guide

> **Tool:** `face-codegen`  
> **Version:** 0.1.0-SNAPSHOT  
> **Standard:** FACE Technical Standard 3.2  
> **Status:** Early feedback release — not yet feature-complete. Feedback welcome.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Early-Stage Notice](#2-early-stage-notice)
3. [Prerequisites](#3-prerequisites)
4. [Building from Source](#4-building-from-source)
   - 4.1 [Maven JAR build](#41-maven-jar-build)
   - 4.2 [CMake configure and build](#42-cmake-configure-and-build)
   - 4.3 [CLion workflow](#43-clion-workflow)
5. [Quick Start](#5-quick-start)
6. [CLI Reference — `generate`](#6-cli-reference----generate)
   - 6.1 [Global flags](#61-global-flags)
   - 6.2 [generate flags](#62-generate-flags)
7. [Template System — `##!` Directives](#7-template-system----directives)
   - 7.1 [Directive syntax](#71-directive-syntax)
   - 7.2 [for_each scope values](#72-for_each-scope-values)
   - 7.3 [filter — name filtering](#73-filter----name-filtering)
   - 7.4 [output path tokens](#74-output-path-tokens)
8. [codegen-defaults.yaml](#8-codegen-defaultsyaml)
9. [Auto-Derived Variables](#9-auto-derived-variables)
10. [FILE Scope and the `$file` Context](#10-file-scope-and-the-file-context)
11. [Driver Templates](#11-driver-templates)
12. [The `codegen.yaml` Manifest (Optional / Override)](#12-the-codegenyaml-manifest-optional--override)
13. [Wrapper Scripts](#13-wrapper-scripts)
14. [Template Sets — Which Pipeline Uses What](#14-template-sets----which-pipeline-uses-what)
15. [What Gets Generated and Where](#15-what-gets-generated-and-where)
16. [Known Limitations](#16-known-limitations)
17. [Troubleshooting](#17-troubleshooting)
18. [CLI Reference — `face-idl-gen generate-tss-idl`](#18-cli-reference----face-idl-gen-generate-tss-idl)
    - 18.1 [Purpose](#181-purpose)
    - 18.2 [Output directory layout](#182-output-directory-layout)
    - 18.3 [Flags](#183-flags)
    - 18.4 [Two-phase operation](#184-two-phase-operation)
    - 18.5 [Per-UoP TypedTS language binding](#185-per-uop-typedts-language-binding)
    - 18.6 [Examples](#186-examples)
    - 18.7 [Relationship to other subcommands](#187-relationship-to-other-subcommands)
19. [CLI Reference — `face-idl-gen parse-tss-idl`](#19-cli-reference----face-idl-gen-parse-tss-idl)
    - 19.1 [Purpose](#191-purpose)
    - 19.2 [Output directory layout](#192-output-directory-layout)
    - 19.3 [Flags](#193-flags)
    - 19.4 [Examples](#194-examples)
    - 19.5 [Relationship to other subcommands](#195-relationship-to-other-subcommands)

---

## 1. Overview

`face-codegen` reads FACE 3.2 IDL files and drives Apache Velocity 2.3 templates
to generate C++ (or other) source code. Templates declare their own iteration
behavior via `##!` comment directives; a `codegen.yaml` manifest is optional and
used only for per-project overrides.

The primary use case in this repository is generating EntityReactor boilerplate
for a FACE data model: per-entity registrar headers, type-conversion tables, and
`TypedTS` transport-service implementation skeletons.

```
--idl-dir  ──► IdlDirectoryParser ──► merged IdlSpecification
                                                │
--face-file ──► FaceTssReader ──► UoPModelData (optional)
                                                │
template-dir ──► ##! directive scan ──► generation plan
             ──► codegen-defaults.yaml ──► template-set defaults
             ──► codegen.yaml (optional) ──► per-project overrides
             └──► *.vm templates
                                                │
                                 IdlDerivedVariables (auto-derived)
                                                │
                                  CodeGenPipeline (Velocity 2.3)
                                                │
                                    generated files on disk
```

The tool is invoked through a single subcommand:

```
face-codegen generate  [flags]  →  generated source files
```

Prefer the wrapper scripts in `code_generator/bin/` over calling `java -jar`
directly — they configure JUL logging automatically:

```
code_generator\bin\face-codegen.bat  generate  [flags]
```

For the template authoring reference see `docs/yaml-authoring-guide.md`. For
the IDL parsing reference see `docs/idl-input-guide.md`.

---

## 2. Early-Stage Notice

This tool and the accompanying EntityReactor C++ library are in early
development. Interfaces and file layouts may change between builds. Feedback
on the design, API ergonomics, and generated output is welcome.

What is functional:

- IDL include resolution, concatenation, and ANTLR4 parsing
- All `for_each` scopes: `SPEC`, `MODULE`, `STRUCT`, `INTERFACE`, `TEMPLATE_INST`,
  `FILE`, `ENUM`, `TYPEDEF`, `UNION`, `CONST`, `UOP`, `CONNECTION`, `GLOBAL`
- `##!` directive scanning — templates are self-describing
- `codegen-defaults.yaml` — template-set-level defaults
- Auto-derived variables from the IDL AST
- FILE scope iteration (`for_each: FILE`) with `$file` context
- Driver template pattern (`##! driver: true`)
- Velocity context assembly with manifest variables and registered helpers
- EntityReactor helper (`$er`) for IDL→C++ field mapping
- SampleModel code generation end-to-end

What is incomplete or not yet implemented:

- `UOP` and `CONNECTION` scopes require a `.face` XMI model that may not be
  fully populated
- The EntityReactor C++ library (`Code/EntityReactor/`) is in active development;
  generated registrar code compiles but the full `Registry` / `Reactor` runtime
  is not complete
- Error messages for malformed templates can reference combined-source line numbers
  rather than individual file line numbers

---

## 3. Prerequisites

| Requirement | Minimum version | Notes |
|---|---|---|
| Java | 11 | JDK required for Maven build; JRE sufficient to run the JAR |
| Apache Maven | 3.8 | On `PATH` as `mvn`, or set `$M2_HOME` / `$MAVEN_HOME` |
| CMake | 3.16 | Required for C++ build only |
| C++ compiler | C++14 | GCC, Clang, or MSVC; avionics-toolchain-compatible |
| Python | — | **Not required** |

---

## 4. Building from Source

### 4.1 Maven JAR build

The JAR is the only artifact needed to run `face-codegen`.

```bash
cd code_generator
mvn package -DskipTests
```

Produces:

```
code_generator/target/face-codegen-0.1.0-SNAPSHOT.jar
```

After building, use the wrapper script rather than invoking `java -jar` directly:

```
code_generator\bin\face-codegen.bat --help
code_generator\bin\face-codegen.bat generate --help
```

### 4.2 CMake configure and build

The root `CMakeLists.txt` builds the Java toolchain via `ExternalProject_Add`
and then the C++ libraries via `add_subdirectory(Code)`. Run from the repo root:

```bash
cmake -S FACEWin -B FACEWin/build
cmake --build FACEWin/build
```

Maven runs automatically during the CMake build step. The JAR path is cached as:

```
FACE_CODEGEN_JAR = <repo>/code_generator/target/face-codegen-0.1.0-SNAPSHOT.jar
```

You can override it on the command line if you have a pre-built JAR elsewhere:

```bash
cmake -S FACEWin -B FACEWin/build \
  -DFACE_CODEGEN_JAR=/path/to/face-codegen.jar
```

### 4.3 CLion workflow

Open the `FACEWin/` directory as a CMake project. CMake will detect Maven and
configure the build. Use the **Build** action in CLion to trigger both the Maven
package step and the C++ compile. The ANTLR4-generated parser sources are
produced during `mvn generate-sources` (part of `mvn package`).

---

## 5. Quick Start

The following uses the SampleModel EntityReactor template set included in the
repository. No `--manifest` is required — the template set is self-describing
via `##!` directives and `codegen-defaults.yaml`.

```bat
REM 1. Build the JAR
cd code_generator
mvn package -DskipTests
cd ..

REM 2. Run code generation for SampleModel
code_generator\bin\face-codegen.bat generate ^
    --idl-dir  <build>\gen\idl\IDL ^
    -I         Code\Core\IDL ^
    --template-dir Code\EntityReactor\codegen ^
    --output-dir   out\reactor
```

Replace `<build>` with the actual build output directory (e.g. `FACEWin\build`).

**With a per-project override manifest** (e.g. SampleModel custom variables):

```bat
code_generator\bin\face-codegen.bat generate ^
    --idl-dir  <build>\gen\idl\IDL ^
    -I         Code\Core\IDL ^
    --template-dir Code\EntityReactor\codegen ^
    --manifest     Code\SampleModel\face_model\codegen.yaml ^
    --output-dir   out\reactor
```

Expected output under `out\reactor\`:

```
out\reactor\
├── ThreatEntityRegistrar.hpp
├── TrackEntityRegistrar.hpp
├── WaypointEntityRegistrar.hpp
├── EntityModelConversionTable.hpp
├── SampleModel\
│   ├── EntityCrudRequest_EntityCrudResponseTS.hpp
│   ├── EntityEventTS.hpp
│   └── SubscriptionRequest_SubscriptionResponseTS.hpp
├── EntityCrudRequest_EntityCrudResponseTS.cpp
├── EntityEventTS.cpp
└── SubscriptionRequest_SubscriptionResponseTS.cpp
```

---

## 6. CLI Reference — `generate`

### 6.1 Global flags

These flags precede the subcommand name.

| Flag | Description |
|---|---|
| `-v` / `--verbose` | Print full Java stack traces on errors |
| `--help` | Show top-level help and exit |
| `--version` | Show version string (`0.1.0-SNAPSHOT`) and exit |

### 6.2 `generate` flags

```
face-codegen generate
    --idl-dir   DIR          (required)
    --template-dir DIR       (required)
    --output-dir   DIR       (required)
   [--face-file   FILE]
   [--face-idl-dir DIR]
   [-I DIR ...]
   [--manifest    FILE]
   [--var KEY=VALUE ...]
```

| Flag | Short | Required | Description |
|---|---|---|---|
| `--idl-dir` | `-i` | Yes | Directory containing the project IDL files to parse. All `.idl` files found recursively are parsed into a single merged `IdlSpecification`. |
| `--template-dir` | `-t` | Yes | Directory containing `.vm` template files. The pipeline scans `##!` directives from templates here and optionally reads `codegen-defaults.yaml` and `codegen.yaml`. |
| `--output-dir` | `-o` | Yes | Root directory for generated output. Created if absent. |
| `--face-file` | `-f` | No | Optional `.face` XMI model file. Enables `$model` and `$uops` in templates. Required for `for_each: UOP` and `for_each: CONNECTION`. |
| `--face-idl-dir` | | No | FACE framework IDL root (`Code/Core/IDL/` in this repo). Defaults to an install-relative path. Set explicitly when running outside CMake. |
| `--include-path` | `-I` | No | Additional IDL include search directory. Repeatable. |
| `--manifest` | `-m` | No | Path to a `codegen.yaml` override manifest. When omitted, the pipeline uses `codegen-defaults.yaml` in `--template-dir` (if present) and falls back to an empty manifest. |
| `--var` | | No | Override or inject a single variable: `--var KEY=VALUE`. Repeatable. Highest-priority variable source. |

**Examples:**

```bat
REM Minimal — no manifest, templates are self-describing via ##! directives
face-codegen generate ^
  --idl-dir    gen\idl\IDL ^
  --template-dir Code\EntityReactor\codegen ^
  --output-dir   out\reactor

REM With extra include path
face-codegen generate ^
  --idl-dir    gen\idl\IDL ^
  -I           Code\Core\IDL ^
  --template-dir Code\EntityReactor\codegen ^
  --output-dir   out\reactor

REM With per-project override manifest
face-codegen generate ^
  --idl-dir    gen\idl\IDL ^
  -I           Code\Core\IDL ^
  --template-dir Code\EntityReactor\codegen ^
  --manifest   Code\SampleModel\face_model\codegen.yaml ^
  --output-dir out\reactor

REM With .face model (enables UOP/CONNECTION scopes)
face-codegen generate ^
  --idl-dir    gen\idl\IDL ^
  --face-file  SampleModel.face ^
  --template-dir Code\EntityReactor\codegen ^
  --output-dir out\reactor

REM Override a single variable on the command line
face-codegen generate ^
  --idl-dir    gen\idl\IDL ^
  --template-dir Code\EntityReactor\codegen ^
  --output-dir   out\reactor ^
  --var project_namespace=MyProject

REM Verbose error output
face-codegen -v generate --idl-dir ... --template-dir ... --output-dir ...
```

---

## 7. Template System — `##!` Directives

Templates in a `face-codegen` template set declare their own iteration behavior
using `##!` comment directives at the top of each `.vm` file. This is the
primary mechanism for controlling code generation as of FACE TS 3.2 — it
replaces the `generations:` list that was previously required in `codegen.yaml`.

### 7.1 Directive syntax

Directives must appear **before any non-comment Velocity content** in the `.vm`
file. The pipeline scans them as plain text before rendering; a `#set` or
whitespace line before a `##!` directive will cause it to be missed.

```
##! for_each: STRUCT
##! output:   {struct.name}Registrar.hpp
##! filter:   .*Entity$
##! driver:   true
```

| Directive | Required | Description |
|---|---|---|
| `##! for_each:` | Yes | Iteration scope (see §7.2). Case-insensitive. |
| `##! output:` | Yes (non-driver) | Output file path pattern relative to `--output-dir`. Tokens in `{braces}` are substituted per element (see §7.4). Driver templates omit this. |
| `##! filter:` | No | Java regex matched against the element name (or forward-slash-normalized relative path for `FILE` scope). Null or absent matches all elements. |
| `##! driver:` | No | `true` marks the template as a driver (see §11). |

**Example — entity registrar:**

```
##! for_each: STRUCT
##! filter:   .*Entity$
##! output:   {struct.name}Registrar.hpp
```

This template renders once per `STRUCT` whose name ends in `Entity`, writing
`TrackEntityRegistrar.hpp`, `ThreatEntityRegistrar.hpp`, etc.

### 7.2 `for_each` scope values

| Scope | Iterates over | Notes |
|---|---|---|
| `SPEC` | The entire merged spec — renders **once** | |
| `MODULE` | Each top-level `ModuleNode` | |
| `STRUCT` | Every `StructNode` at any nesting depth | |
| `INTERFACE` | Every `InterfaceNode` at any nesting depth | |
| `TEMPLATE_INST` | Every `Typed<D,R>` instantiation | Template module must be in merged spec |
| `FILE` | Each `.idl` source file under `--idl-dir` | See §10 |
| `ENUM` | Every `EnumNode` at any nesting depth | |
| `TYPEDEF` | Every `TypedefNode` at any nesting depth | |
| `UNION` | Every `UnionNode` at any nesting depth | |
| `CONST` | Every `ConstNode` at any nesting depth | |
| `UOP` | Each `UoPData` in the loaded `.face` model | Requires `--face-file` |
| `CONNECTION` | Each `ConnectionData` across all UoPs | Requires `--face-file` |
| `GLOBAL` | Once per run (like SPEC, but for cross-cutting output) | |

### 7.3 `filter` — name filtering

`filter` is a **full-string Java regex** (`String.matches()`). It must match the
*entire* name, not just a substring.

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
| `SPEC` / `GLOBAL` | (filter ignored — always renders once) |

Common patterns:

| Pattern | Matches |
|---|---|
| `.*Entity$` | Any name ending in `Entity` |
| `^EntityEvent$` | Exactly `EntityEvent` |
| `TypedTS/.*` | All files under `TypedTS/` (FILE scope) |
| Absent | Every element in the scope |

### 7.4 output path tokens

Tokens in `{braces}` in the `##! output:` value are substituted per element.
Manifest variables (from `codegen-defaults.yaml`, `codegen.yaml`, or `--var`)
are also available as tokens.

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

Plus all variable keys from `codegen-defaults.yaml`, `codegen.yaml`, and
`--var` flags (e.g. `{project_namespace}`, `{model_namespace}`).

---

## 8. `codegen-defaults.yaml`

A file named `codegen-defaults.yaml` at the root of a template set declares
constants and helpers that apply to every project using that template set. This
lets the template set author encode its own requirements without requiring each
project to duplicate them in its `codegen.yaml`.

```yaml
# Code/EntityReactor/codegen/codegen-defaults.yaml
variables:
  reactor_namespace: "WARHEX::EntityReactor"
  reactor_include:   "WARHEX/EntityReactor"
helpers:
  - id: entity_reactor
    as: er
```

When `face-codegen generate` is run without `--manifest`, the pipeline:

1. Reads `codegen-defaults.yaml` from `--template-dir` (if present).
2. Scans `##!` directives from all `.vm` files in `--template-dir` to build
   the generation plan.
3. Derives the 5 auto-derived variables from the IDL AST (see §9).

**Context priority (lowest → highest):**

```
IDL-derived variables
    → codegen-defaults.yaml variables
        → codegen.yaml (--manifest) variables
            → --var CLI flags
```

---

## 9. Auto-Derived Variables

The pipeline automatically derives 5 variables from the IDL AST. These are
available in all template contexts and as output path tokens without being
declared in any manifest.

| Variable | Derived from |
|---|---|
| `$model_namespace` | IDL module path of the entity structs (e.g. `FACE::DM::SampleModel`) |
| `$project_namespace` | Last component of `model_namespace` (e.g. `SampleModel`) |
| `$face_tss_namespace` | TSS module path from `Typed<>` instantiations (e.g. `FACE::TSS::SampleModel`) |
| `$entity_payload_idl` | Path to the generated EntityPayload header |
| `$entity_payload_idl_enum` | Enum type name in the payload IDL |

These variables used to be required entries in the manifest `variables:` block.
They are now derived automatically and only need to be overridden in
`codegen.yaml` if the auto-derived value is incorrect for a project.

---

## 10. FILE Scope and the `$file` Context

`for_each: FILE` iterates once per `.idl` source file found under `--idl-dir`.
Each iteration provides a `$file` variable (`IdlFileUnit`) in the Velocity
context.

**`$file` properties:**

| Property | Getter | Example value |
|---|---|---|
| `$file.stemName` | `getStemName()` | `"EntityEvent"` |
| `$file.relativePath` | `getRelativePath()` | `"TypedTS/EntityEvent.idl"` |
| `$file.relativeOutputPath` | `getRelativeOutputPath()` | `"TypedTS/EntityEvent.hpp"` |
| `$file.absolutePath` | `getAbsolutePath()` | Full OS path to the `.idl` file |
| `$file.definitions` | `getDefinitions()` | Top-level IDL definitions in this file only |

**Notes:**
- `$file.definitions` contains only the top-level definitions from that specific
  file, not transitively included types.
- The `filter` for FILE scope is matched against the forward-slash-normalized
  relative path (e.g. `TypedTS/.*` matches all files under `TypedTS/`).

**Example directive:**

```
##! for_each: FILE
##! filter:   TypedTS/.*
##! output:   {file.relativeOutputPath}
```

**`sourceFile` on `IdlDefinition`:**

`IdlDefinition` has a nullable `Path sourceFile()` field. It is set on nodes
produced by FILE scope iteration, and null on merged-spec nodes from the
combined parse. Templates iterating via FILE scope can use `$def.sourceFile`
to know which `.idl` file a definition came from.

---

## 11. Driver Templates

A `.vm` file with `##! driver: true` is a driver template. Instead of writing a
single output file, a driver template contains one or more `#set($outFile = "...")`
/ `#parse("leaf.vm")` pairs. The pipeline:

1. Text-scans the driver template for `#set($outFile = ...)` / `#parse(...)` pairs.
2. Evaluates each `$outFile` Velocity expression.
3. Renders the named leaf template to the resolved output path.

Driver templates are used when one scope element (e.g. one `TEMPLATE_INST`
iteration) must produce several differently-named output files.

**Example (`entity_crud_ts_driver.vm`):**

```
##! for_each: TEMPLATE_INST
##! filter:   EntityCrudRequest_EntityCrudResponse
##! driver:   true

#set($outFile = "${project_namespace}/${inst.alias}TS.hpp")
#parse("entity_crud_ts.hpp.vm")
#set($outFile = "${inst.alias}TS.cpp")
#parse("entity_crud_ts.cpp.vm")
```

This produces both the `.hpp` and `.cpp` for each matching `TEMPLATE_INST`
in a single iteration pass.

**Important:** `$outFile` must be assigned by a `#set` on its own line. The
pipeline's text scan is line-oriented and will miss `$outFile` assignments that
are not on a dedicated `#set` line.

---

## 12. The `codegen.yaml` Manifest (Optional / Override)

The `codegen.yaml` manifest is **no longer required**. When omitted, the pipeline
uses `codegen-defaults.yaml` from the template directory and the `##!` directive
system to determine what to generate.

A project-level `codegen.yaml` is useful when you need to:

- Override template-set defaults from `codegen-defaults.yaml` (e.g. change a
  namespace for a specific project).
- Inject project-specific variables not derivable from the IDL AST.
- Override auto-derived variables when the derivation is incorrect.

**Minimal override manifest:**

```yaml
# Only variables that differ from codegen-defaults.yaml or auto-derived values
variables:
  model_namespace:   "FACE::DM::MyProject"
  project_namespace: "MyProject"
```

Pass it with `--manifest`:

```bat
face-codegen generate ^
  --template-dir Code\EntityReactor\codegen ^
  --manifest     Code\MyProject\face_model\codegen.yaml ^
  --output-dir   out\reactor
```

For the full manifest reference (including the `generations:` list for
legacy/override-style generation) see `docs/yaml-authoring-guide.md`.

---

## 13. Wrapper Scripts

Two `.bat` launcher scripts are included in `code_generator/bin/`. They invoke
the respective JARs with JUL logging configured via `logging.properties`.

```
code_generator/
├── bin/
│   ├── face-codegen.bat      — launches face-codegen-0.1.0-SNAPSHOT.jar
│   └── face-idl-gen.bat      — launches face-idl-gen-0.1.0-SNAPSHOT.jar
└── logging.properties        — compact "LEVEL: message" JUL format
```

**Recommended usage (`face-codegen`):**

```bat
code_generator\bin\face-codegen.bat generate ^
    --idl-dir  <build>\gen\idl\IDL ^
    -I         Code\Core\IDL ^
    --template-dir Code\EntityReactor\codegen ^
    --output-dir   <build>\gen\reactor
```

**Recommended usage (`face-idl-gen`):**

`face-idl-gen generate-entity-idl` supports three entity-source modes:

*Workflow A — YAML/JSON model (original behavior):*
```bat
code_generator\bin\face-idl-gen.bat generate-entity-idl ^
    --model    Code\SampleModel\face_model\SampleModel.yaml ^
    --output-dir <build>\gen\idl
```

*Workflow B — template elements from a `.face` file (default group):*
```bat
code_generator\bin\face-idl-gen.bat generate-entity-idl ^
    --model    Code\SampleModel\face_model\SampleModel.face ^
    --entities ^
    --output-dir <build>\gen\idl
```
Reads `uop:Template` and `uop:CompositeTemplate` elements from the
`EntityReactorTemplates` group in the `.face` file. Struct names are suffixed
with `Entity` (e.g. `T1` → `T1Entity.idl`).

*Workflow B — template elements from a named group:*
```bat
code_generator\bin\face-idl-gen.bat generate-entity-idl ^
    --model    Code\SampleModel\face_model\SampleModel.face ^
    --entity-source Model_Templates ^
    --output-dir <build>\gen\idl
```
Same as `--entities` but reads from the explicitly named `um:UoPModel` group
instead of the default `EntityReactorTemplates` group. `--entity-source` implies
`--entities`; both flags require a `.face` model file.

The scripts use `%~dp0` to locate the JAR relative to the script's own
directory, so they work correctly regardless of the current working directory.

---

## 14. Template Sets — Which Pipeline Uses What

`code_generator/templates/` ships three categories of template sets. Understanding
which pipeline drives each is important for template authoring.

### Language binding templates — `templates/languages/{cpp,csharp,java,python}/`

These templates are for the **language binding code generator**, not for
`face-codegen generate`. Each language directory contains:

- `language.yaml` — declares the language name, iteration strategy
  (`per_idl_file` or `per_construct`), template routing by construct type
  (`struct:`, `enum:`, `interface:`, etc.), type maps, reserved words, and
  optional static files.
- `macros.vm` — shared Velocity macro library included by other templates.
- One `.vm` template per construct type (`class.cs.vm`, `enum.cs.vm`, etc.).

**Language templates do not use `##!` directives.** They are driven by
`language.yaml` routing configuration, not by `##!` headers.

| Strategy | Description |
|---|---|
| `per_idl_file` | One output file per `.idl` source file; output path derived from module namespace. C++ uses this. |
| `per_construct` | One output file per IDL construct (struct, enum, interface, etc.). C#, Java, Python use this. |

### Entity Reactor IDL generation templates — `templates/entity-reactor-idl/`

Used by **`face-idl-gen generate-entity-idl`**, not `face-codegen`. These are
Apache Velocity `.vtl` files (note: `.vtl`, not `.vm`) driven by the IDL
generator's own pipeline. Template authors for this set use `## @foreach entity`
style comments and a different context (`$model`, `$entity`, `$structName`, etc.).

### Data model IDL generation templates — `templates/data-model-idl/`

Also used by **`face-idl-gen`**. Same `.vtl` style as `entity-reactor-idl`.

**Summary:**

| Template location | Pipeline | File extension | Directive style |
|---|---|---|---|
| `templates/languages/*/` | Language binding generator | `.vm` | `language.yaml` routing |
| `templates/entity-reactor-idl/` | `face-idl-gen` | `.vtl` | `## @foreach entity` |
| `templates/data-model-idl/` | `face-idl-gen` | `.vtl` | `## @foreach ...` |
| `Code/EntityReactor/codegen/` | `face-codegen generate` | `.vm` | `##!` directives |

---

## 15. What Gets Generated and Where

For each template with `##!` directives the pipeline:

1. Iterates over the elements selected by `for_each`.
2. Applies `filter` (a Java regex) to skip non-matching elements.
3. Evaluates the `output` path pattern, substituting `{tokens}` for the
   current element and all available variables.
4. Renders the Velocity template into the resolved path under `--output-dir`.

For driver templates (`##! driver: true`), step 3–4 are performed once per
`#set($outFile = ...)` / `#parse(...)` pair inside the driver.

Manifest `variables` values (from `codegen-defaults.yaml`, `codegen.yaml`, or
`--var`) are available both in templates (as `$key`) and in output path patterns
(as `{key}`).

The actual SampleModel output is shown in the [Quick Start](#5-quick-start)
section above.

---

## 16. Known Limitations

- **Combined-source line numbers in parse errors.** The IDL parser concatenates
  all source files before parsing. Error messages report line numbers within the
  combined source. The parser attempts to map these back to original filenames;
  the mapping may occasionally be off by a few lines when files are large.

- **`TEMPLATE_INST` resolution failures are warnings, not errors.** If a template
  module referenced in an IDL instantiation is not found in the merged spec, the
  entry is skipped with a `WARNING` log and code generation continues. Check the
  `--face-idl-dir` and `-I` paths if expected files are missing.

- **`UOP` / `CONNECTION` scopes require `--face-file`.** If a template uses
  `for_each: UOP` or `for_each: CONNECTION` without `--face-file` being supplied,
  the tool aborts with an error at runtime.

- **EntityReactor C++ library is incomplete.** The generated registrar and
  transport-service files compile against the current headers, but the `Registry`
  and `Reactor` runtime classes are not yet fully implemented.

- **No distributed ZIP release.** The tool is run directly from the Maven
  `target/` JAR via the `bin/` wrapper scripts. There is no installer package.

---

## 17. Troubleshooting

### `ERROR: --idl-dir does not exist or is not a directory`

The path passed to `--idl-dir` must exist and contain `.idl` files. In the CMake
build the IDL files are produced by a preceding code-generation step; ensure
that step has completed before running `face-codegen`.

### `WARNING: codegen.yaml not found` (no longer fatal)

If `--manifest` is omitted and no `codegen.yaml` is in `--template-dir`, the
pipeline continues normally using `codegen-defaults.yaml` and the `##!` directive
system. A missing manifest is valid as of the Phase 1–8 refactor. If you see
unexpected output (no files generated), check that your `.vm` files have valid
`##! for_each:` and `##! output:` directives.

### No files are generated

If the pipeline runs without error but produces no output files:

- Verify that your `.vm` files have `##! for_each:` directives at the top,
  before any non-comment Velocity content.
- Check that `##! filter:` is not accidentally excluding all elements.
- Run with `-v` to see detailed pipeline logging.

### IDL parse error at `line N:M`

The ANTLR4 parser encountered a syntax error. Common causes:

- A referenced IDL type is defined in a file that is not on the include path.
  Add `-I <dir>` for every directory the parser needs to search.
- `--face-idl-dir` is not set and the FACE framework IDL files are not found.
  Point it at `Code/Core/IDL/`.
- A hand-edited IDL file contains a syntax error. The reported line number is
  within the concatenated source; look for the error around that location
  across your IDL files.

### IDL file contains null bytes / unexpected parse failure

Some IDL generation tools can emit null bytes in otherwise valid files. A file
with a null byte will cause the ANTLR4 lexer to fail at that position with a
confusing error. Check suspect files with:

```bat
findstr /P /M "" *.idl
```

Any file reported by `findstr` contains a null byte and must be regenerated or
fixed before parsing.

### Template variable renders as literal `${varName}`

Velocity (in non-strict mode) renders undefined variables as literal text.
Causes:

- The variable was not placed in the context. Check that `codegen-defaults.yaml`
  declares the expected `helpers` and `variables`, or that `--manifest` / `--var`
  provides them.
- The Java object accessed in the template has no JavaBean getter for the property.
  See `docs/velocity-template-gotchas.md` rule 3.

### `WARNING: Skipping unresolvable template inst`

A `TEMPLATE_INST` entry refers to a `Typed<D,R>` instantiation whose template
module definition could not be found. Ensure `--face-idl-dir` points to the FACE
framework IDL root that contains `FACE/TSS/TypedTS.idl`.

### Verbose output for debugging

```bat
code_generator\bin\face-codegen.bat -v generate ^
    --idl-dir ... --template-dir ... --output-dir ...
```

Full Java stack traces are printed to `stderr` for every error.

---


---

## 18. CLI Reference — `face-idl-gen generate-tss-idl`

### 18.1 Purpose

`generate-tss-idl` reads a FACE 3.2 `.face` XMI model and produces two
categories of IDL output in a single pass:

- **FACE data-model IDL** — one `.idl` struct file per `Template` /
  `CompositeTemplate` element, placed under `<output-dir>/idl/data-model/`.
- **FACE TypedTS IDL** — one TypedTS instantiation `.idl` per UoP Connection
  (Standard or Extended variant), placed under
  `<output-dir>/idl/uop-tss/<UoPName>/`.

Optionally, after IDL generation the tool runs the language binding pipeline to
produce C++, Java, Python, and/or C# source files from the generated IDL.

```
face-idl-gen generate-tss-idl  <FACE_FILE>  --output-dir <DIR>  [flags]
```

---

### 18.2 Output directory layout

```
<output-dir>/
├── idl/
│   ├── data-model/               ← FACE DM IDL — one .idl per struct type
│   │   ├── GeoPosition.idl
│   │   ├── TrackEntity.idl
│   │   └── ...
│   └── uop-tss/                  ← TypedTS IDL — one subdirectory per UoP
│       └── <UoPName>/
│           ├── <MsgType>_ts.idl                    ← Standard (QUEUING / SINGLE_INSTANCE)
│           └── <ReqType>_<RespType>_ts.idl         ← Extended (CLIENT_SERVER)
│
├── cpp/                          ← C++ language binding (when --cpp or --all-face)
│   ├── data-model/               ← C++ data-model types — builds as its own library
│   │   └── FACE/DM/<namespace>/...
│   └── <UoPName>/                ← C++ TypedTS binding for this UoP — standalone library
│       └── FACE/TSS/<namespace>/...
│
├── java/                         ← Java language binding (when --java or --all-face)
│   ├── data-model/
│   │   └── face/dm/<namespace>/...
│   └── <UoPName>/
│
├── python/                       ← Python binding (when --python or --all-languages)
│   ├── data-model/
│   └── <UoPName>/
│
└── csharp/                       ← C# binding (when --csharp or --all-languages)
    ├── data-model/
    └── <UoPName>/
```

Rules:

- `--output-dir` points to the output root. The `idl/` directory and the
  per-language directories are created automatically if absent.
- `--idl-only` stops after generating the IDL tree; no language binding is run.
- Language bindings are only generated when at least one language flag is given.
  `--all-face` (C++ + Java) is the default set when no language flag is
  specified.

---

### 18.3 Flags

```
face-idl-gen generate-tss-idl
    <FACE_FILE>                 (required, positional)
    --output-dir DIR            (required)
   [--templates-dir DIR]
   [--face-idl-dir DIR]
   [-I DIR ...]
   [--idl-only]
   [--all-languages | --all-face | --cpp | --java | --python | --csharp]
```

| Flag | Short | Required | Description |
|---|---|---|---|
| `FACE_FILE` | | Yes | Path to the `.face` XMI model file. |
| `--output-dir` | `-o` | Yes | Root output directory. Created if absent. Receives the `idl/`, `cpp/`, `java/`, etc. subdirectory tree. |
| `--templates-dir` | `-t` | No | TSS IDL template root. Defaults to `<install>/templates/data-model-idl/`. |
| `--face-idl-dir` | | No | FACE framework IDL root (the `face-idl/` install directory). Defaults to an install-relative path. Set explicitly when running outside the CMake build. |
| `--include-path` | `-I` | No | Additional IDL include search directory. Repeatable. These directories are searched in order when the IDL parser resolves `#include` directives. |
| `--idl-only` | | No | Generate IDL only; skip all language binding steps. |
| `--all-languages` | | No | Generate C++, Java, Python, and C# bindings. |
| `--all-face` | | No | Generate C++ and Java bindings (FACE-standard languages). This is the default when no language flag is specified. |
| `--cpp` | | No | Generate C++ bindings only. |
| `--java` | | No | Generate Java bindings only. |
| `--python` | | No | Generate Python bindings only. |
| `--csharp` | | No | Generate C# bindings only. Note: C# is not a FACE-standard language and is excluded from `--all-face`. |

Language flags are additive: `--cpp --python` generates C++ and Python without
Java or C#.

---

### 18.4 Two-phase operation

When language binding flags are present (and `--idl-only` is absent), the tool
runs two phases in sequence:

**Phase 1 — IDL generation**

Reads the `.face` XMI model and generates:

1. One `.idl` struct file per `uop:Template` / `uop:CompositeTemplate` element
   → `<output-dir>/idl/data-model/<TypeName>.idl`
2. One TypedTS instantiation `.idl` per UoP Connection, grouped by UoP
   → `<output-dir>/idl/uop-tss/<UoPName>/<ConnName>_ts.idl`

**Phase 2 — Language binding**

Parses the data-model IDL produced in Phase 1 and runs the active language
mappers to produce struct/class definitions:

```
<output-dir>/idl/data-model/  →  parser  →  <output-dir>/cpp/  (data-model types)
                                         →  <output-dir>/java/
                                         →  ...
```

Then, for each UoP subdirectory discovered under `<output-dir>/idl/uop-tss/`,
the tool runs a second binding pass that writes TypedTS language bindings into a
per-UoP subdirectory under each language root (see §18.5).

---

### 18.5 Per-UoP TypedTS language binding

After the data-model binding, the tool iterates every subdirectory of
`<output-dir>/idl/uop-tss/`. For each UoP it:

1. Parses that UoP's TypedTS IDL, with `<output-dir>/idl/data-model/` on the
   include search path so that `#include "TrackEntity.idl"` resolves correctly.
2. Runs the active language mappers, writing output to
   `<output-dir>/<lang>/<UoPName>/`.

This produces isolated per-UoP output trees:

```
cpp/
├── data-model/             ← data-model C++ types (builds as its own library)
│   └── FACE/DM/...
└── NavSystem/              ← TypedTS C++ binding for the NavSystem UoP
    └── FACE/TSS/NavSystem/Position/TypedTS/...
java/
├── data-model/
│   └── face/dm/...
└── NavSystem/
    └── face/tss/NavSystem/...
```

The C++ namespace inside the generated files follows the FACE TS 3.2 convention:
`FACE::TSS::<UoPName>::<MessageType>::TypedTS`. This namespace is determined by
the TypedTS IDL templates, not by the output directory structure.

**Design rationale:** Isolating each UoP's TypedTS output into its own
subdirectory lets each UoP be compiled into a standalone library containing only
its TypedTS definitions, preventing accidental type coupling between UoPs.

---

### 18.6 Examples

**IDL only (no language binding):**

```bat
code_generator\bin\face-idl-gen.bat generate-tss-idl ^
    Code\SampleModel\face_model\SampleModel.face ^
    --output-dir out\tss ^
    --idl-only
```

Output:

```
out\tss\
├── idl\
│   ├── data-model\
│   │   ├── GeoPosition.idl
│   │   ├── TrackEntity.idl
│   │   ├── ThreatEntity.idl
│   │   └── WaypointEntity.idl
│   └── uop-tss\
│       └── NavSystem\
│           ├── TrackEntity_ts.idl
│           └── ThreatEntity_ts.idl
```

**IDL + C++ and Java binding (default language set):**

```bat
code_generator\bin\face-idl-gen.bat generate-tss-idl ^
    Code\SampleModel\face_model\SampleModel.face ^
    --output-dir out\tss ^
    --all-face ^
    --face-idl-dir Code\Core\IDL ^
    -I Code\Core\IDL
```

**IDL + C++ only:**

```bat
code_generator\bin\face-idl-gen.bat generate-tss-idl ^
    Code\SampleModel\face_model\SampleModel.face ^
    --output-dir out\tss ^
    --cpp ^
    --face-idl-dir Code\Core\IDL
```

**IDL + all languages including Python and C#:**

```bat
code_generator\bin\face-idl-gen.bat generate-tss-idl ^
    Code\SampleModel\face_model\SampleModel.face ^
    --output-dir out\tss ^
    --all-languages ^
    --face-idl-dir Code\Core\IDL
```

**Verbose output for diagnosing binding failures:**

```bat
code_generator\bin\face-idl-gen.bat -v generate-tss-idl ^
    Code\SampleModel\face_model\SampleModel.face ^
    --output-dir out\tss ^
    --cpp
```

---

### 18.7 Relationship to other subcommands

| Goal | Command |
|---|---|
| Generate entity-reactor IDL from a YAML or `.face` model | `face-idl-gen generate-entity-idl` |
| Generate FACE TSS data-model IDL and UoP TypedTS IDL from a `.face` model | `face-idl-gen generate-tss-idl` |
| Run entity-reactor IDL generation + language binding end-to-end | `face-idl-gen generate MODEL` |
| Run language binding against an existing IDL directory | `face-codegen generate --idl-dir ...` |

`generate-tss-idl` and `face-codegen generate` cover different IDL. The TSS
subcommand generates data-model and TypedTS IDL from a `.face` XMI model; it
does not use the EntityReactor Velocity templates. `face-codegen generate` reads
arbitrary IDL and drives project-level Velocity templates (EntityReactor
boilerplate, registrars, etc.); it does not read `.face` XMI files directly
(instead consuming the IDL files those models produce).

A typical full workflow for a FACE UoP is:

```
1. face-idl-gen generate-entity-idl  →  entity-reactor IDL
2. face-codegen generate              →  EntityReactor C++ boilerplate
3. face-idl-gen generate-tss-idl     →  DM IDL + TypedTS IDL + language bindings
```


---

## 19. CLI Reference — `face-idl-gen parse-tss-idl`

### 19.1 Purpose

`parse-tss-idl` accepts a directory of existing IDL files and a set of
language-binding flags, parses all `.idl` files in that directory
(recursively), and runs the built-in `LanguageBindingPipeline` to produce
language-specific binding output — **without** requiring a `.face` XMI model
file and **without** requiring a `--template-dir`.

Use this subcommand when you already have IDL on disk (generated by an
earlier `generate-tss-idl` or `generate` run, or hand-authored) and want to
(re-)produce language bindings from it directly. It fills the gap where both
`generate-tss-idl` and `face-codegen generate` require additional inputs
(`--face-file` or `--template-dir`) that may not be available or needed in a
downstream build step.

Unlike `generate-tss-idl`, `parse-tss-idl` does not perform IDL generation —
it is a binding-only step. Output goes directly under `<output-dir>/<lang>/`
via each mapper's `outputSubdirectory()`.

---

### 19.2 Output directory layout

```
<output-dir>/
├── cpp/
│   └── FACE/DM/<Namespace>/
│       └── <Type>.hpp
├── java/
│   └── face/dm/<namespace>/
│       └── <Type>.java
├── python/
│   └── FACE/DM/<Namespace>/
│       └── <Type>.py
└── csharp/
    └── FACE/DM/<Namespace>/
        └── <Type>.cs
```

The exact tree beneath each language directory mirrors the IDL module
hierarchy and is determined by the language descriptor for each binding.

---

### 19.3 Flags

| Flag | Short | Required | Description |
|---|---|---|---|
| `--idl-dir` | `-i` | **Yes** | Directory of IDL files to parse (recursive). Replaces the role of the `.face` model file. |
| `--output-dir` | `-o` | **Yes** | Root output directory. Created if absent. Language output lands under `<output-dir>/<lang>/`. |
| `--face-idl-dir` | — | No | FACE framework IDL root (`Code/Core/IDL/` in this repo). Defaults to `<install>/face-idl/`. Same resolution order as `generate-tss-idl`. |
| `--include-path` | `-I` | No | Additional IDL include search directory. Repeatable. Same semantics as `generate-tss-idl`. |
| `--all-face` | — | No | Generate C++ and Java bindings (default when no language flag is given). |
| `--all-languages` | — | No | Generate C++, Java, Python, and C# bindings. |
| `--cpp` | — | No | Generate C++ bindings only (additive with other flags). |
| `--java` | — | No | Generate Java bindings only (additive with other flags). |
| `--python` | — | No | Generate Python bindings only (additive with other flags). |
| `--csharp` | — | No | Generate C# bindings (non-FACE; excluded from `--all-face`). Additive with other flags. |
| `--help` | `-h` | No | Print help and exit. |

When no language flag is supplied, `--all-face` (C++ + Java) is the default,
matching the behaviour of `generate-tss-idl`.

---

### 19.4 Examples

**IDL-only input, default (C++ + Java) output:**

```bat
code_generator\bin\face-idl-gen.bat parse-tss-idl ^
    --idl-dir out\tss\idl\data-model ^
    --output-dir out\tss\bindings ^
    --face-idl-dir Code\Core\IDL
```

**C++ binding only — useful for a C++-only downstream build:**

```bat
code_generator\bin\face-idl-gen.bat parse-tss-idl ^
    --idl-dir out\tss\idl\data-model ^
    --output-dir out\tss\bindings ^
    --cpp ^
    --face-idl-dir Code\Core\IDL
```

**All FACE-standard languages (explicit `--all-face`):**

```bat
code_generator\bin\face-idl-gen.bat parse-tss-idl ^
    --idl-dir Code\SampleModel\idl\data-model ^
    --output-dir build\bindings ^
    --all-face ^
    --face-idl-dir Code\Core\IDL ^
    -I Code\SampleModel\idl
```

**All languages including Python and C#:**

```bat
code_generator\bin\face-idl-gen.bat parse-tss-idl ^
    --idl-dir out\tss\idl\data-model ^
    --output-dir out\tss\bindings ^
    --all-languages ^
    --face-idl-dir Code\Core\IDL
```

**Verbose output for diagnosing binding failures:**

```bat
code_generator\bin\face-idl-gen.bat -v parse-tss-idl ^
    --idl-dir out\tss\idl\data-model ^
    --output-dir out\tss\bindings ^
    --cpp ^
    --face-idl-dir Code\Core\IDL
```

---

### 19.5 Relationship to other subcommands

| Goal | Command | Requires `.face` file? | Requires template dir? |
|---|---|---|---|
| Generate TSS data-model IDL **and** language bindings from a `.face` model | `face-idl-gen generate-tss-idl` | **Yes** | No (built-in templates) |
| Run entity-reactor IDL generation + language binding end-to-end | `face-idl-gen generate MODEL` | Optional | **Yes** |
| Run language binding against IDL using project-level Velocity templates | `face-codegen generate --idl-dir ...` | No | **Yes** |
| Run language binding against existing IDL on disk, no model or template dir needed | **`face-idl-gen parse-tss-idl`** | **No** | **No** |

`parse-tss-idl` is the right choice when you already have IDL on disk and
want to regenerate bindings in isolation — for example in a downstream CMake
step that should not re-run the full TSS pipeline. It uses exactly the same
`LanguageBindingPipeline` as `generate-tss-idl`; the only difference is the
input source (an IDL directory rather than a `.face` model file) and the
absence of the IDL-generation step.

---

*See also:*

- `docs/yaml-authoring-guide.md` — `codegen.yaml` manifest reference and `##!` directive authoring
- `docs/idl-input-guide.md` — IDL input format and include resolution
- `docs/design-guide.md` — implementation internals for contributors
- `docs/velocity-template-gotchas.md` — Velocity 2.3 lessons learned

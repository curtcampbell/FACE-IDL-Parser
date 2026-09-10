# Design Specification: Template-Based Entity Source for Entity Reactor IDL Generation

## Purpose

This document specifies a new feature for the `face-idl-gen` tool that allows
`uop:Template` and `uop:CompositeTemplate` elements from a `.face` XMI file to
serve as the entity source for entity-reactor IDL generation. The feature
coexists with the existing `platform:Entity`-based path and is selected via
command-line flags.

This document is intended as the authoritative implementation brief. A new
session should read it in full before writing any code.

---

## Repository Context

Project root: `code_generator/`

```
code_generator/
├── src/main/java/com/warhex/er/generator/
│   ├── FaceIdlGen.java                        ← CLI entry point (subcommands)
│   ├── FaceToolUtils.java                     ← shared CLI helpers
│   ├── reader/
│   │   ├── ModelReader.java                   ← interface
│   │   ├── ModelReaderFactory.java            ← routes file extension → reader
│   │   ├── DefaultModelLoader.java            ← loads + maps model
│   │   ├── EntityModelMapper.java             ← IdlModelData → EntityModel
│   │   ├── dto/                               ← shared DTOs
│   │   │   ├── IdlModelData.java
│   │   │   ├── EntityData.java
│   │   │   ├── TssTypeData.java
│   │   │   ├── UoPModelData.java
│   │   │   └── ...
│   │   ├── face/
│   │   │   ├── FaceXmiDocument.java           ← shared DOM parse + UUID map
│   │   │   ├── FaceXmiModelReader.java        ← platform:Entity → IdlModelData
│   │   │   ├── FaceTssReader.java             ← uop:Template → UoPModelData
│   │   │   └── TssToEntityModelAdapter.java   ← UoPModelData → IdlModelData
│   │   └── yaml/
│   │       └── YamlModelReader.java
│   └── idl/
│       └── IdlGeneratorPipeline.java
├── templates/
│   ├── entity-reactor-idl/                    ← entity reactor Velocity templates
│   └── data-model-idl/                        ← TSS data-model Velocity templates
└── examples/
    └── model.face                             ← reference .face XMI file
```

---

## Current Architecture

### Entity Reactor IDL path (`generate-entity-idl`)

```
model.face
  └─► ModelReaderFactory.forFile()
        └─► FaceXmiModelReader.read()
              walks <dm> subtree for platform:Entity elements
              resolves platform:Composition fields (struct, enum, primitive)
              structNamePattern = "{name}Entity"
              idlModule = "FACE.DM.<ModelName>"
              └─► IdlModelData
                    └─► EntityModelMapper.map() → EntityModel
                          └─► IdlGeneratorPipeline.generate() → entity-reactor IDL
```

### TSS IDL path (`generate-tss-idl`)

```
model.face
  └─► FaceTssReader.read()
        Pass 1: register all uop:Template / uop:CompositeTemplate (entire <um> subtree)
        Pass 2: resolve fields via boundQuery / TemplateComposition children
        └─► UoPModelData
              └─► TssToEntityModelAdapter.adapt()
                    structNamePattern = "{name}"    ← no Entity suffix
                    └─► IdlModelData
                          └─► EntityModelMapper.map() → EntityModel
                                └─► IdlGeneratorPipeline.generate() → TSS data-model IDL
```

### Combined path (`generate`)

The `generate` command runs the entity-reactor pipeline (Step 1 + 2), then
optionally runs the TSS pipeline when `--face-file` is also supplied.

---

## New Feature: Template-Based Entity Source

### Overview

Add support for using `uop:Template` and `uop:CompositeTemplate` elements —
scoped to a named `um:UoPModel` group — as the entity source for entity-reactor
IDL generation, in place of `platform:Entity` elements.

A modeler signals intent by placing the relevant templates inside a `um:UoPModel`
group with an agreed name (default: `"EntityReactorTemplates"`). The parser
locates that group by name and reads only its direct `element` children whose
`xmi:type` is `uop:Template` or `uop:CompositeTemplate`.

### `.face` File Convention

Templates intended as entity sources are placed inside a `um:UoPModel` group
whose `name` attribute equals the agreed group name. No other change to the
`.face` file is needed.

```xml
<!-- Example: inside the <um> subtree -->
<um xmi:type="uop:UoPModel"
    xmi:id="..."
    name="EntityReactorTemplates">

  <element xmi:type="uop:Template"
           xmi:id="_abc"
           name="TrackEntity"
           boundQuery="_xyz"/>

  <element xmi:type="uop:CompositeTemplate"
           xmi:id="_def"
           name="ThreatEntity">
    <composition xmi:type="uop:TemplateComposition"
                 xmi:id="_ghi"
                 rolename="position"
                 type="_abc"/>
  </element>

</um>
```

Templates outside this group are ignored by the entity source reader. They
remain available to the TSS pipeline unchanged.

---

## Implementation Plan

### 1. New class: `FaceTemplateEntityReader`

**Package:** `com.warhex.er.generator.reader.face`

**Implements:** `ModelReader`

**Responsibility:** Read `uop:Template` and `uop:CompositeTemplate` elements
from a named `um:UoPModel` group in a `.face` XMI file and produce an
`IdlModelData` for the entity-reactor IDL pipeline.

**Constructor:**
```java
public FaceTemplateEntityReader(String groupName)
```
`groupName` is the `name` attribute of the `um:UoPModel` group to search.
Defaults to `"EntityReactorTemplates"` when constructed via the no-arg factory
path.

**Algorithm:**

1. Parse the `.face` file via `FaceXmiDocument` (shared DOM + UUID map).
2. Walk the DOM to find the `um:UoPModel` element whose `name` attribute
   equals `groupName`. If not found, throw `IllegalArgumentException` with a
   clear message naming the missing group.
3. **Pass 1:** Within that group element only, register every `element` child
   whose `xmi:type` is `uop:Template` or `uop:CompositeTemplate` into a
   `Map<String, TssTypeData> typeById`. Use the same registration logic as
   `FaceTssReader.registerTemplates()` but scoped to direct children of the
   group element.
4. **Pass 2:** Resolve fields for each registered type using the same logic as
   `FaceTssReader.resolveAllTemplateFields()` — Templates via `boundQuery`
   spec parsing, CompositeTemplates via `uop:TemplateComposition` children.
   Note: `boundQuery` resolution walks the full UUID map (built from the entire
   document), so cross-group references resolve correctly.
5. Build `IdlModelData`:
   - `modelName`         = root element `name` attribute
   - `idlModule`         = `"FACE.DM." + modelName`
   - `tssIdlModule`      = `"FACE.TSS." + modelName`
   - `cppNamespace`      = `"FACE::DM::" + modelName`
   - `structNamePattern` = `"{name}Entity"`   ← matches current entity reactor path
   - `entities`          = one `EntityData` per registered `TssTypeData`
     (via `TssToEntityModelAdapter.adaptType()` — see §3)
   - `supportingStructs` = empty list (nested deduplication handled downstream)

**Key difference from `FaceTssReader`:**

| Concern | `FaceTssReader` | `FaceTemplateEntityReader` |
|---|---|---|
| Scope | All templates in entire `<um>` subtree | Only templates in named group |
| UoP collection | Yes (PortableComponent, etc.) | No |
| Output type | `UoPModelData` | `IdlModelData` directly |
| Struct name pattern | `{name}` (set by adapter) | `{name}Entity` |
| Purpose | TSS pipeline | Entity reactor pipeline |

**No modification to `FaceTssReader`.** It continues to serve the TSS pipeline
unchanged.

---

### 2. Changes to `TssToEntityModelAdapter`

Add a package-private (or public) `adaptType(TssTypeData)` method that is
callable from `FaceTemplateEntityReader` to convert a single `TssTypeData` to
an `EntityData`. This method already exists as a private method in
`TssToEntityModelAdapter`; it should be promoted to package-private or extracted
as a static helper so `FaceTemplateEntityReader` can reuse it without
duplication.

Alternatively, `FaceTemplateEntityReader` can inline the equivalent logic
directly (it is three lines: set `simpleName`, set `isUnion`, set `fields`).
Either approach is acceptable; the inline approach avoids coupling.

**No change to the struct name pattern logic in `TssToEntityModelAdapter`.**
That class continues to use `"{name}"` for the TSS path. The entity reactor
path sets `"{name}Entity"` directly in `FaceTemplateEntityReader`.

---

### 3. Changes to `ModelReaderFactory`

No change required. `FaceTemplateEntityReader` is not routed through
`ModelReaderFactory`. It is instantiated directly in the command code when the
`--entities` / `--entity-source` flags are present (see §4).

---

### 4. Changes to `FaceIdlGen`

#### New options — added to both `GenerateIdlCommand` and `GenerateCommand`

```java
@Option(names = {"--entities"},
        description = "Use templates as entity source. Reads from the '"
                    + FaceTemplateEntityReader.DEFAULT_GROUP_NAME
                    + "' group in the .face model. "
                    + "Only valid when MODEL is a .face file.")
private boolean useTemplates;

@Option(names = {"--entity-source"},
        paramLabel = "GROUP",
        description = "Named um:UoPModel group to use as template entity source. "
                    + "Implies --entities. "
                    + "Only valid when MODEL is a .face file.")
private String entitySourceGroup;
```

#### Logic change in `GenerateIdlCommand.call()` and `GenerateCommand.call()`

Replace the `FaceToolUtils.loadModel(modelFile)` call with a helper that
respects the new flags:

```java
private EntityModel resolveEntityModel() throws Exception {
    boolean templateMode = useTemplates || entitySourceGroup != null;
    if (templateMode) {
        String modelExt = modelFile.getFileName().toString().toLowerCase();
        if (!modelExt.endsWith(".face")) {
            throw new IllegalArgumentException(
                "--entities / --entity-source require a .face model file. "
                + "Got: " + modelFile.getFileName());
        }
        String group = entitySourceGroup != null
                ? entitySourceGroup
                : FaceTemplateEntityReader.DEFAULT_GROUP_NAME;
        IdlModelData idlData = new FaceTemplateEntityReader(group).read(modelFile);
        return new EntityModelMapper().map(idlData);
    }
    return FaceToolUtils.loadModel(modelFile);
}
```

This helper can be placed in `FaceToolUtils` or inlined in each command's
`call()` method; either is acceptable.

#### `GenerateCommand` additional note

The TSS pipeline branch (triggered by `--face-file`) is independent of the new
flags and requires no change. When `--face-file` is supplied alongside
`--entities`, both pipelines run: entity-reactor IDL comes from the named
template group, TSS IDL comes from the full `<um>` subtree via `FaceTssReader`.

---

## Constant: default group name

Define in `FaceTemplateEntityReader`:

```java
public static final String DEFAULT_GROUP_NAME = "EntityReactorTemplates";
```

---

## Validation Rules

| Condition | Error / Behaviour |
|---|---|
| `--entities` or `--entity-source` present, MODEL is not `.face` | `IllegalArgumentException` with clear message; exit code 1 |
| `--entity-source=GroupName` without `--entities` | Valid; `--entity-source` implies template mode |
| Named group not found in `.face` file | `IllegalArgumentException` naming the missing group |
| Named group exists but contains no templates | `IdlModelData` with empty `entities` list; pipeline runs and produces empty IDL; log a warning |
| `--entities` and `--entity-source=X` both present | `entitySourceGroup` takes precedence; `--entities` is redundant but not an error |

---

## Three Supported Workflows

### Workflow A — Entity reactor IDL from `platform:Entity` (unchanged)

```
face-idl-gen generate-entity-idl model.face --output-dir out/
```

### Workflow B — Entity reactor IDL from templates

```
face-idl-gen generate-entity-idl model.face --entities --output-dir out/
face-idl-gen generate-entity-idl model.face --entity-source=EntityReactorTemplates --output-dir out/
face-idl-gen generate-entity-idl model.face --entity-source=MyCustomGroup --output-dir out/
```

### Workflow C — TSS data-model IDL + UoP TypedTS IDL (unchanged)

```
face-idl-gen generate-tss-idl model.face --output-dir out/
```

### Workflow D — Both entity reactor IDL and TSS IDL (combined)

```
# Entity reactor from platform:Entity + TSS:
face-idl-gen generate model.face --face-file model.face --output-dir out/

# Entity reactor from templates + TSS:
face-idl-gen generate model.face --face-file model.face --entities --output-dir out/

# Entity reactor from named template group + TSS:
face-idl-gen generate model.face --face-file model.face --entity-source=MyGroup --output-dir out/
```

---

## Test Coverage Expectations

### Unit tests — `FaceTemplateEntityReader`

File: `src/test/java/com/warhex/er/generator/reader/face/FaceTemplateEntityReaderTest.java`

Using `examples/model.face`. The sample model has a `um:UoPModel` group named
`"Model_Templates"` containing T1, T2, and PV1. For initial tests, use
`"Model_Templates"` as the group name (no `.face` file change needed).

Required test cases:

1. **Group found, types registered** — reader with `groupName = "Model_Templates"`
   produces `IdlModelData` with 3 entities (T1, T2, PV1).
2. **Entity names** — entity simple names are `"T1"`, `"T2"`, `"PV1"`.
3. **Struct name pattern** — `idlData.getStructNamePattern()` equals `"{name}Entity"`.
4. **IDL module** — `idlData.getIdlModule()` equals `"FACE.DM.SampleUSM"`.
5. **T1 fields** — T1 has the expected fields resolved from its `boundQuery`.
6. **PV1 is composite** — PV1 entity has `isUnion == false` and fields
   `mT1` (type `"T1"`) and `mT2` (type `"T2"`).
7. **Group not found** — `FaceTemplateEntityReader("NonExistentGroup").read(...)`
   throws `IllegalArgumentException`.
8. **Empty group** — group found but contains no template children → empty
   entities list, no exception.

### Integration test addition — `TssIdlGeneratorIntegrationTest` or new test class

Add a test case that runs `GenerateIdlCommand` end-to-end with `--entities`
(or `--entity-source=Model_Templates`) against `examples/model.face` and
asserts that the output IDL directory contains entity files named
`T1Entity.idl`, `T2Entity.idl`, `PV1Entity.idl` (or equivalent based on how
the entity template renders names).

### Existing tests — no regression

Run `FaceTssReaderTest` and `TssToEntityModelAdapterTest` to confirm the TSS
pipeline is unaffected.

---

## Files to Create or Modify

| Action | File |
|---|---|
| **Create** | `src/main/java/com/warhex/er/generator/reader/face/FaceTemplateEntityReader.java` |
| **Modify** | `src/main/java/com/warhex/er/generator/FaceIdlGen.java` — add options to `GenerateIdlCommand` and `GenerateCommand`; update `call()` logic |
| **Modify** | `src/main/java/com/warhex/er/generator/FaceToolUtils.java` — optionally house the `resolveEntityModel()` helper |
| **Modify** (minor) | `src/main/java/com/warhex/er/generator/reader/face/TssToEntityModelAdapter.java` — promote `adaptType()` to package-private if reuse is chosen over inline |
| **Create** | `src/test/java/com/warhex/er/generator/reader/face/FaceTemplateEntityReaderTest.java` |
| **Modify** | Existing integration test or new test class for end-to-end validation |
| **No change** | `FaceTssReader.java`, `FaceXmiModelReader.java`, `ModelReaderFactory.java`, `FaceXmiDocument.java`, all Velocity templates |

---

## Summary of Design Decisions

- `FaceTssReader` is **not modified**. It continues to serve the TSS pipeline
  reading the full `<um>` subtree.
- `FaceXmiModelReader` is **not modified**. It continues as the default entity
  source for `.face` files.
- The signal convention is **purely structural**: templates belong to a named
  `um:UoPModel` group. No `description` markers, no external manifests.
- Struct name pattern for template-sourced entities is **`{name}Entity`**,
  matching the existing entity reactor path.
- The default group name is **`"EntityReactorTemplates"`**, overridable via
  `--entity-source=<name>`.
- `--entities` and `--entity-source=<name>` are both valid ways to activate the
  new path; `--entity-source` implies `--entities` and takes precedence on the
  group name.
- The new flags apply to both `generate-entity-idl` and `generate` subcommands.
- The TSS pipeline (`generate-tss-idl`, and the `--face-file` branch of
  `generate`) is **completely independent** of these flags.

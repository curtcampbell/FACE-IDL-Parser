# Seed Prompt — FACE Code Generator Design

## What This Project Is

We are building a FACE TS 3.2 toolchain in Java (Maven, Java 11+). The toolchain currently
has two CLI tools living in one Maven project (`face-idl-tools`):

1. **`face-idl-gen`** — reads a `.face` XMI file (or YAML model) and emits IDL artifacts.
2. **`face-idl-binder`** — reads IDL and emits FACE language binding code (C++, Java, Python,
   C# abstract interface classes) using Apache Velocity 2.3 templates.

We are now designing a **third tool** — a code generator — whose job is:

> **IDL + `.face` model → concrete UoP implementation skeletons**
> (classes that implement the abstract interfaces produced by `face-idl-binder`)

This tool has not been named yet. We need to design it from scratch.

---

## What Has Already Been Built (Do Not Redesign)

### Tool 1 — `face-idl-gen`

Pipeline: `.face` XMI → `FaceTssReader` → `UoPModelData` → `TssToEntityModelAdapter`
→ `IdlModelData` → `EntityModelMapper` → `EntityModel` → `IdlGeneratorPipeline`
→ IDL files on disk.

Produces two output trees:
- `data-model/` — one `.idl` struct per FACE Template / CompositeTemplate
- `uop-tss/` — per-UoP TypedTS instantiation files (`{msg}_ts.idl`,
  `{req}_{resp}_ts.idl`)

Also supports entity-reactor IDL from YAML via a separate template set
(`entity-reactor-idl/`).

### Tool 2 — `face-idl-binder`

Pipeline: IDL directory → `IdlDirectoryParser` → `IdlParseResult` (AST)
→ `LanguageBindingPipeline` → per-language abstract interface files.

The AST is built by an ANTLR4 grammar (`FACE_IDL.g4`) and includes:
- `IdlSpecification`, `ModuleNode`, `InterfaceNode`, `TemplateInstNode`,
  `OperationNode`, `ParameterNode`, `TypedefNode`, `StructNode`, `EnumNode`
- Template module instantiation via `TemplateInstantiator` (resolves type parameters)

Language mappers use Velocity templates in `templates/languages/<lang>/` and a
`language.yaml` descriptor. C++ is hand-coded (`CppLanguageMapper`); Java, Python,
C# use the generic descriptor-driven engine (`LanguageDescriptorLoader`).

Produces: abstract interface classes per language (e.g., `TypedTS`, `Read_Callback`
stubs with `abstractmethod` / pure virtual methods).

### Key DTO Types

```
UoPModelData
  modelName, idlModule
  platformTypes: List<TssTypeData>   (Templates + CompositeTemplates)
  uoPs:          List<UoPData>

UoPData
  name, uuid
  connections: List<ConnectionData>

ConnectionData
  name, kind (QUEUING | SINGLE_INSTANCE | CLIENT_SERVER)
  role (CONSUMER | PRODUCER | REQUESTER | RESPONDER)
  typedTsVariant (STANDARD | EXTENDED)
  messageType: TssTypeData
  responseMessageType: TssTypeData   (CLIENT_SERVER only)

TssTypeData
  name, uuid, isUnion, fields: List<FieldData>

FieldData
  name, idlType, nested, nestedFields
```

### Project Layout

```
code_generator/
  src/main/java/com/warhex/er/generator/
    FaceIdlGen.java          (face-idl-gen entry point; subcommands: generate-entity-idl,
                              generate-tss-idl, generate)
    FaceIdlBinder.java       (face-idl-binder entry point; subcommand: bind)
    FaceToolUtils.java       (shared static helpers)
    idl/                     (IdlGeneratorPipeline, TemplateMetadata)
    model/                   (EntityModel, EntityDescriptor, FieldDescriptor, …)
    reader/                  (EntityModelMapper, ModelToIdlAdapter, DTOs, …)
    reader/face/             (FaceTssReader, TssToEntityModelAdapter, FaceXmiDocument)
    binding/                 (LanguageBindingPipeline, LanguageMapper, TemplateInstantiator, …)
    ast/                     (IdlSpecification, ModuleNode, InterfaceNode, …)
    parser/                  (IdlParser, IdlAstBuilder, IdlDirectoryParser)
  templates/
    entity-reactor-idl/      (VTL templates for entity-reactor IDL)
    data-model-idl/          (VTL templates for TSS data-model IDL)
    languages/               (VTL templates for language bindings: cpp/, java/, python/, csharp/)
  examples/
    model.face               (sample .face XMI — SampleUSM model)
    SampleModel.yaml         (sample YAML model)
  pom.xml                    (artifactId: face-idl-tools; two shade executions)
```

---

## What We Need to Design — The Code Generator

### User's Vision (verbatim)

> "If we go this route, the next tool will be the code generator.
>  1. idl + model → implementation using interface code in tool number 2."

In concrete terms: given the abstract interfaces that `face-idl-binder` produces,
generate the **concrete implementation skeletons** that a UoP developer would fill in —
classes that `extend` / `implements` / inherit from those interfaces and provide
stub method bodies.

### Open Questions to Resolve in the New Chat

1. **Inputs** — What exactly does the code generator consume?
   - The raw IDL files? The parsed AST? The `UoPModelData` DTO? All three?
   - Does it need the `.face` file at runtime, or is the IDL sufficient?

2. **Output shape** — What does one generated implementation skeleton look like per language?
   - One class per UoP? One per connection? One per TypedTS interface?
   - What goes in the method bodies (empty stub, `throw NotImplemented`, logging)?
   - What file/package layout mirrors the abstract interfaces?

3. **Connection between binder and generator** — Does the code generator:
   - Call `face-idl-binder` internally as a library step, or
   - Consume the binder's *output files* from disk (treating them as a black box)?

4. **Tool name** — We haven't named this tool yet. Candidates to consider:
   - `face-idl-impl` (implementation generator)
   - `face-uop-gen` (UoP-focused)
   - `face-codegen` (generic)
   - Other?

5. **Template strategy** — Does the generator:
   - Reuse the same Velocity + `language.yaml` descriptor pattern from `face-idl-binder`?
   - Need a new template strategy because it generates implementation (not interfaces)?

6. **Maven shape** — Does the code generator become:
   - A third shade execution in the existing `face-idl-tools` project, or
   - A new Maven module / separate project?

7. **CLI shape** — What subcommands make sense?
   - Single `generate` command (from `.face` → everything)?
   - Separate `generate-skeletons`, `generate-ts-impl`, etc.?

8. **FACE TS role granularity** — should the tool generate:
   - Just the TypedTS Read/Write subscriber/publisher stub?
   - The full UoP component class wiring connections together?
   - Both?

### Architectural Context to Keep In Mind

- LCM ports are **intentionally not parsed** and must not be added without explicit
  instruction. Do not design around them.
- The `UoPModelData` DTO already knows each connection's `ConnectionRole`
  (CONSUMER / PRODUCER / REQUESTER / RESPONDER) and `TypedTsVariant`
  (STANDARD / EXTENDED). These should drive what skeleton is generated per connection.
- The existing `TemplateInstantiator` already resolves `Typed<T>` template bodies
  into concrete operation lists — this is the correct hook for generating method stubs
  without re-parsing IDL.
- All template rendering uses Apache Velocity 2.3 (`VelocityContext`, `VelocityEngine`).
  The new tool should follow the same pattern.

---

## Suggested Starting Point for the New Chat

Begin by proposing:
1. A name for the tool
2. Its inputs, outputs, and CLI shape
3. The pipeline stages (analogous to how `face-idl-gen` and `face-idl-binder` are layered)
4. Which existing classes it reuses vs. what new classes are needed
5. The template strategy for at least one target language (C++ is the primary one)

Defer implementation until the design is agreed upon.

# Multi-Transport-Service ConnectionTable Design

## Purpose

This document is the authoritative design specification for evolving the `face-codegen`
Velocity template system to generate a separate `ConnectionTable` per *(UoP, transport
service)* pair rather than one monolithic table per UoP type. It is intended to seed
implementation work and can be handed directly to a new chat session for code-generation
tasks.

---

## Background

### What exists today

The code generator produces three files per UoP via `for_each: UOP` in `codegen.yaml`:

| File | Role |
|------|------|
| `{Name}ConnectionTable.h` | Pure-virtual interface — one `send___()` per PRODUCER/REQUESTER, one `register___Handler()` per CONSUMER/RESPONDER |
| `{Name}ConnectionTableImpl.h` | Concrete class; privately owns one `EventDispatcher<…>` per CONSUMER/RESPONDER |
| `{Name}ConnectionTableImpl.cpp` | Stub implementation; CONSUMER/RESPONDER delegate to their dispatcher; PRODUCER/REQUESTER carry TSS TODO stubs |

All three files aggregate **every connection on the UoP regardless of which transport
service carries it.** When a UoP participates in more than one transport service, this
collapses a boundary the FACE integration model draws explicitly.

> **Observed evidence:** The existing IDL output for UoP1 already contains separate
> per-transport-service files (`PV1_T2_ts.idl`, `PV1_ts.idl`, `T1_ts.idl`), confirming
> that the Java reader already groups by transport service at the IDL level. The
> ConnectionTable layer has not yet received the same treatment.

### Velocity template context (`for_each: UOP`)

| Variable | Type | Description |
|----------|------|-------------|
| `$uop` | `UoPData` | `$uop.name`, `$uop.connections` |
| `$conn` | `ConnectionData` | `name`, `role` (PRODUCER/CONSUMER/REQUESTER/RESPONDER), `kind` (QUEUING/SINGLE_INSTANCE/CLIENT_SERVER), `messageType.name`, `messageType.uuid`, `responseMessageType` (null unless CLIENT_SERVER) |
| `$model_namespace` | String | Always available from `IdlDerivedVariables` (e.g. `FACE::DM::SampleUSM`) |

---

## Design Question 1 — FACE Integration Model Constructs

### The `IntegrationContext` is the transport-service boundary

Source authority: **c232 Appendix J.2.3** (`face.integration` metamodel, Figures 33–34)
and **G240 Vol. 3 §3.4** (Table 6, Figures 103–110).

> *"A collection of all TSNodeConnections, and TransportNodes that are part of a
> transport. It must have at least one ViewTransporter specifying that data is
> transported across a TS."*
> — G240 Vol. 3 §3.4, Table 6

Each `IntegrationContext` corresponds to exactly one transport service instance. Multiple
`IntegrationContext`s can appear inside a single `IntegrationModel`, each backed by a
different `TransportChannel`.

### Traceability chain — connection to transport service

```
UoPInstance
  └─ .output / .input (0..*) ──► UoPOutputEndPoint / UoPInputEndPoint
        └─ .connection (1) ──────► face.uop.Connection          ← the named UoP connection
              │
              └─ appears as source or destination of
                    TSNodeConnection
                      └─ owned by IntegrationContext (via .connection 0..*)
                            └─ .node (0..*) ──► ViewTransporter
                                  └─ .channel (1) ──► TransportChannel  ← TS name
```

**Grouping rule:** A `face.uop.Connection C` of `UoPInstance U` belongs to
`IntegrationContext IC` if and only if there exists a `TSNodeConnection` owned by `IC`
whose source or destination port traces to the `UoPEndPoint` of `U` with `connection = C`.

### Key metamodel facts (c232 J.2.3)

| Meta-class | Key relationship | Multiplicity | Design significance |
|------------|-----------------|:---:|---------------------|
| `IntegrationContext` | `node` → TransportNode | 0..* | Owns all TransportNodes for this TS |
| `IntegrationContext` | `connection` → TSNodeConnection | 0..* | Owns all wiring links for this TS |
| `UoPInstance` | `realizes` → UnitOfPortability | 1 | Links integration instance to UoP type |
| `UoPEndPoint` | `connection` → face.uop.Connection | 1 | Binds endpoint to named UoP connection |
| `TSNodePort` | `view` → face.uop.MessageType | 1 | Types each port |
| `ViewTransporter` | `channel` → TransportChannel | 1 | Names the concrete TS |
| `TransportChannel` | inherits from `Element` | — | `name` attribute becomes the TSS wiring key |

### Client/Server connections

A `ClientServerConnection` (c232 J.2.2) carries both `messageType` (request) and
`responseMessageType` (response). In the integration model, a CLIENT_SERVER pair generates
two `TSNodeConnection`s (one for each direction) both owned by the same `IntegrationContext`,
so REQUESTER/RESPONDER pairs always land in the same per-IC table.

---

## Design Question 2 — face-codegen Data Model

### Current DTO landscape

| DTO / Class | What it holds | TS-aware? |
|-------------|--------------|:---------:|
| `UoPData` | `name`, flat `List<ConnectionData> connections` | No |
| `ConnectionData` | `name`, `role`, `kind`, `messageType`, `responseMessageType`, `uuid` | No |
| `TssTypeData` | TSS-level type groupings for IDL | Yes |
| `TypedTsVariant` | Standard vs. extended TS variant | Partial |
| `UoPModelData` | Collection of `UoPData` | No |

### Required extension — new `IntegrationContextData` DTO

```java
// Pseudocode — shape of the new DTO bound to $integrationContext in templates
public class IntegrationContextData {
    String name;                       // IntegrationContext.name from XMI
    String transportChannelName;       // ViewTransporter.channel.name in this IC
    String uopName;                    // UoPInstance.realizes.name
    List<ConnectionData> connections;  // scoped to this IC only; preserves UoPData order
}
```

`UoPData.connections` and `ConnectionData` are **unchanged** so existing `UOP`-scoped
templates continue working without modification.

### Reader extension

`FaceXmiModelReader` / `FaceTssReader` must traverse:

```
IntegrationContext.connection
  → TSNodeConnection.source / .destination
    → UoPEndPoint.connection
      → look up in existing ConnectionData list for the owning UoP
```

This graph is already traversed for IDL generation. The extension is exposing the
grouping result as `IntegrationContextData` objects, not re-parsing the XMI.

---

## Design Question 3 — Template Scope

### Decision: new `UOP_INTEGRATION_CONTEXT` scope ✓ RECOMMENDED

Add a new enum value to `ForEachScope` and a corresponding iteration path in
`ContextAssembler`. The codegen iterates once per *(UoP type, IntegrationContext)* pair.

**Why not a nested loop inside `UOP` scope:** face-codegen maps one template execution to
one output file. Generating multiple files per template execution would require a new
multi-output mechanism — significantly more invasive than adding a new scope constant.

### Updated `codegen.yaml`

```yaml
generations:
  # Existing UOP-scoped entries — unchanged:
  - template: UoPTypeTraits.h.vm
    for_each: UOP
    output:   "{uop.name}/{uop.name}TypeTraits.h"
  - template: UoPTypeTraits.cpp.vm
    for_each: UOP
    output:   "{uop.name}/{uop.name}TypeTraits.cpp"
  - template: UoPBase.h.vm
    for_each: UOP
    output:   "{uop.name}/{uop.name}Base.h"

  # New per-(UoP, IntegrationContext) entries:
  - template: ConnectionTable.h.vm
    for_each: UOP_INTEGRATION_CONTEXT
    output:   "{uop.name}/{integrationContext.name}/{uop.name}{integrationContext.name}ConnectionTable.h"
  - template: ConnectionTableImpl.h.vm
    for_each: UOP_INTEGRATION_CONTEXT
    output:   "{uop.name}/{integrationContext.name}/{uop.name}{integrationContext.name}ConnectionTableImpl.h"
  - template: ConnectionTableImpl.cpp.vm
    for_each: UOP_INTEGRATION_CONTEXT
    output:   "{uop.name}/{integrationContext.name}/{uop.name}{integrationContext.name}ConnectionTableImpl.cpp"
```

### New Velocity context variables

| Variable | Type | Description |
|----------|------|-------------|
| `$uop` | `UoPData` | The realized UoP type (unchanged object; `$uop.name` provides class name prefix) |
| `$integrationContext` | `IntegrationContextData` | IC name, transport channel name, scoped connections |
| `$model_namespace` | String | Injected by `IdlDerivedVariables` as before |

---

## Design Question 4 — Naming Convention

**Pattern:** `{UoPName}{IntegrationContextName}ConnectionTable`

Both components come directly from named elements in the FACE model.

| Component | Source in model | Example |
|-----------|----------------|---------|
| `{UoPName}` | `UoPInstance.realizes.name` | `AOIPublisher` |
| `{IntegrationContextName}` | `IntegrationContext.name` | `NavDataIC` |
| Interface class | — | `AOIPublisherNavDataICConnectionTable` |
| Impl class | — | `AOIPublisherNavDataICConnectionTableImpl` |
| Namespace | UoP type name | `namespace AOIPublisher { … }` |
| Include guard | Upper-cased concatenation | `AOIPUBLISHER_NAVDATAIC_CONNECTION_TABLE_H` |
| Output directory | Nested IC subdirectory | `AOIPublisher/NavDataIC/` |

### Velocity fragment

```velocity
#set ($className   = "${uop.name}${integrationContext.name}ConnectionTable")
#set ($guardSymbol = "${uop.name.toUpperCase()}_${integrationContext.name.toUpperCase()}_CONNECTION_TABLE_H")
```

---

## Design Question 5 — Template Changes

The three template files change in two ways:
1. **Iteration source:** `$uop.connections` → `$integrationContext.connections` everywhere
2. **Class/guard symbols:** gain the IC name (see DQ4 above)
3. **TSS stubs:** name the `TransportChannel`

### `ConnectionTable.h.vm`

```velocity
## Iteration source change:
## Before: #foreach ($conn in $uop.connections)
## After:  #foreach ($conn in $integrationContext.connections)

## Include guard and class name:
#set ($guardSymbol = "${uop.name.toUpperCase()}_${integrationContext.name.toUpperCase()}_CONNECTION_TABLE_H")
#ifndef ${guardSymbol}
\#define ${guardSymbol}

## IResponseSender deduplication scopes to $integrationContext.connections — no structural change needed.

class ${uop.name}${integrationContext.name}ConnectionTable {
```

### `ConnectionTableImpl.h.vm`

```velocity
## Private members — after:
private:
    // TransportChannel: ${integrationContext.transportChannelName}
#foreach ($conn in $integrationContext.connections)
#if ($conn.role.toString() == "CONSUMER")
    EventDispatcher<const ::${model_namespace}::${conn.messageType.name}&> m_${conn.name}Dispatcher;
#elseif ($conn.role.toString() == "RESPONDER")
    EventDispatcher<const ::${model_namespace}::${conn.messageType.name}&,
                   I${conn.responseMessageType.name}ResponseSender&> m_${conn.name}Dispatcher;
#end
#end
```

### `ConnectionTableImpl.cpp.vm`

```velocity
## PRODUCER stub — after:
// TODO: publish via TransportChannel '${integrationContext.transportChannelName}'
//       for connection ${conn.name} (UoP ${uop.name})
//       Message type: ::${model_namespace}::${conn.messageType.name}
(void)msg;

## REQUESTER stub — after:
// TODO: send request via TransportChannel '${integrationContext.transportChannelName}'
//       for connection ${conn.name} (UoP ${uop.name})
//       Request:  ::${model_namespace}::${conn.messageType.name}
//       Response: ::${model_namespace}::${conn.responseMessageType.name}
//       Invoke responseCallback when the reply arrives.
(void)request;
(void)responseCallback;

## CONSUMER / RESPONDER register methods — unchanged in structure; iteration source changes only.
```

### Per-file change summary

| File | Iteration source | Content change |
|------|-----------------|----------------|
| `ConnectionTable.h.vm` | `$integrationContext.connections` | Guard + class name include IC name; IResponseSender dedup scoped to IC |
| `ConnectionTableImpl.h.vm` | `$integrationContext.connections` | Class name includes IC name; comment names TransportChannel; dispatcher members scoped to IC |
| `ConnectionTableImpl.cpp.vm` | `$integrationContext.connections` | TODO stubs name the TransportChannel; CONSUMER/RESPONDER bodies unchanged |

---

## Java Implementation — `ContextAssembler` and `ForEachScope`

```java
// ForEachScope.java — add new enum value
public enum ForEachScope {
    UOP,
    UOP_INTEGRATION_CONTEXT   // one context per (UoP type, IntegrationContext) pair
}

// ContextAssembler.java — new iteration branch (pseudocode)
case UOP_INTEGRATION_CONTEXT:
    for (IntegrationContextData ic : model.getIntegrationContexts()) {
        VelocityContext ctx = buildBaseContext();
        ctx.put("uop",                model.getUopByName(ic.getUopName()));
        ctx.put("integrationContext", ic);
        IdlDerivedVariables.inject(ctx, ic.getUopName());
        contexts.add(ctx);
    }
    break;
```

---

## Migration Plan

Perform changes in this order to keep the build green at every step:

1. **Extend the Java reader** — build `IntegrationContextData` objects and verify population
   against the existing FACE model. No template or manifest changes yet.

2. **Add `UOP_INTEGRATION_CONTEXT` to `ForEachScope`** and the `ContextAssembler` branch.
   Verify the new scope produces context objects with the correct scoped connection lists.

3. **Update `codegen.yaml`** with the new `for_each: UOP_INTEGRATION_CONTEXT` entries.
   Templates still use `$uop.connections` at this point — they compile but may produce
   incorrect output. Use to validate file naming and directory structure.

4. **Update the three ConnectionTable templates** — replace `$uop.connections` with
   `$integrationContext.connections`; add IC name to class/guard symbols; update TSS
   TODO stubs. Regenerate and diff against prior output.

5. **Update consuming build systems** — `CMakeLists.txt` or equivalent that globs
   the ConnectionTable output paths must be updated for the new
   `{UoPName}/{IntegrationContextName}/` directory structure.

### Compatibility notes

- All existing `UOP`-scoped templates (`UoPTypeTraits`, `UoPBase`) are unaffected.
- A UoP with exactly one IntegrationContext produces one ConnectionTable, functionally
  equivalent to the prior output but with the IC name in the class name.
- A UoP with no `UoPInstance` in the integration model produces no ConnectionTable files
  from the new scope. `UOP`-scoped TypeTraits and Base files are unaffected.

---

## Source References

| Reference | Relevant sections |
|-----------|------------------|
| FACE Technical Standard, Edition 3.2 (c232) | Appendix J.2.3 — `face.integration` metamodel (UoPInstance, IntegrationContext, UoPEndPoint, TSNodeConnection, ViewTransporter, TransportChannel) |
| RIG G240 Vol. 3, §3.4 | Integration Model chapter — Table 6 (element descriptions), Figures 102–110 (examples including pub/sub, client/server, ViewFilter, ViewTransformation, ViewAggregation) |
| RIG G240 Vol. 1 | General FACE architecture guidance |
| Existing templates | `Code/UopStub/codegen/cpp/ConnectionTable.h.vm`, `ConnectionTableImpl.h.vm`, `ConnectionTableImpl.cpp.vm` |
| Manifest | `Code/UopStub/codegen/cpp/codegen.yaml` |

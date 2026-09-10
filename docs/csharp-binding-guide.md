# C# Language Binding Guide
## Entity Reactor IDL Generator — Non-FACE Pragmatic Mapping

**Audience:** Developers implementing FACE UoPs or TSS components who want to
work in C#.  You are expected to understand what a UoP is, what a Transport
Service, and how IDL is used in a FACE system.  You do not need to know the
FACE Java or C++ mapping rules.

---

## 1. Purpose and Scope

FACE Technical Standard 3.2 does not define a C# language binding.  This
mapping is a pragmatic adaptation that lets .NET developers write UoP
implementations against generated C# types without writing any interop code
themselves.

The generated types define the **contract** for a UoP implementation.  They
are not a COM wrapper, a P/Invoke shim, or a native interop layer.  There is
no `unsafe` code, no `DllImport`, and no reference to unmanaged memory.

The interop layer between the FACE platform runtime and your .NET UoP is
handled by a separate component.  Your job as a UoP author is to implement
the generated interfaces and work with the generated data types.

---

## 2. Activating C# Generation

C# output is produced by the **language binding generator** (`face-idl-binder`
or the equivalent CMake target), not by `face-codegen generate`. The generator
is configured via `templates/languages/csharp/language.yaml` and driven by
the `per_construct` iteration strategy.

```
face-idl-binder generate \
  --idl-dir    ./IDL \
  --lang-dir   code_generator/templates/languages/csharp \
  --output-dir ./out
```

> **Note:** The exact CLI for the language binding generator may differ from the
> above depending on the build integration. Refer to the CMake target for
> `CSharpBindings` in your project's `CMakeLists.txt` for the authoritative
> invocation. The `language.yaml`-driven pipeline does **not** use `--manifest`
> or `##!` directives — it is a separate pipeline from `face-codegen generate`.

Output is written under `<output-dir>/csharp/`.

---

## 3. Output Structure

```
out/csharp/
├── FACE/
│   ├── BadParamException.cs
│   └── DataConversionException.cs
└── FACE/
    └── DM/
        └── SampleModel/           ← namespace FACE.DM.SampleModel
            ├── GeoPosition.cs     ← IDL struct → sealed class
            ├── ThreatLevel.cs     ← IDL enum  → C# enum
            └── IThreatEntity.cs   ← IDL interface → C# interface
```

---

## 4. Namespace Mapping

IDL modules map directly to C# namespaces, **preserving their original
casing**.  A nested module stack becomes a dotted namespace.

| IDL | C# namespace |
|-----|-------------|
| `module FACE { module DM { module SampleModel { ... } } }` | `FACE.DM.SampleModel` |

This differs from the Java mapping, which lowercases every segment
(e.g. `face.dm.samplemodel`).

---

## 5. Primitive Type Mapping

| IDL type | C# type | Notes |
|---|---|---|
| `boolean` | `bool` | |
| `char` | `char` | |
| `octet` | `byte` | |
| `short` | `short` | |
| `unsigned short` | `ushort` | |
| `long` | `int` | |
| `unsigned long` | `uint` | |
| `long long` | `long` | |
| `unsigned long long` | `ulong` | |
| `float` | `float` | |
| `double` | `double` | |
| `long double` | `decimal` | Closest available .NET type |
| `string` | `string` | |
| `wstring` | `string` | |

FACE scoped types (e.g. `FACE::Long`, `FACE::Float`) resolve to their
underlying C# primitive.

---

## 6. Struct → Sealed Class

An IDL struct maps to a C# `sealed class`.  The class exposes one
auto-property per IDL member, using **PascalCase** names.  It also provides
a default (zero-argument) constructor and a deep copy constructor.

```idl
// IDL
struct GeoPosition {
    double latitude;
    double longitude;
    float  altitude_m;
};
```

```csharp
// Generated C#
namespace FACE.DM.SampleModel
{
    /// <remarks>
    /// Copy semantics: reference type — use new GeoPosition(source) at every
    /// UoP exchange boundary.
    /// </remarks>
    public sealed class GeoPosition
    {
        public double  Latitude  { get; set; }
        public double  Longitude { get; set; }
        public float   AltitudeM { get; set; }

        public GeoPosition() { }

        public GeoPosition(GeoPosition source)
        {
            if (source is null) throw new ArgumentNullException(nameof(source));
            Latitude  = source.Latitude;
            Longitude = source.Longitude;
            AltitudeM = source.AltitudeM;
        }
    }
}
```

### 6.1 Name Conversion

IDL uses `snake_case`; C# properties use **PascalCase**.  The conversion
drops underscores and capitalises the following letter.  `altitude_m` becomes
`AltitudeM`.  The original IDL name is preserved in XML doc comments.

### 6.2 Copy Semantics — Important

`sealed class` is a reference type.  If you pass an instance to another method
without copying it, both sides hold a reference to the same object.  A later
mutation on either side silently affects the other.

**The TSS implementation performs a deep copy at every send/receive boundary.**
Your UoP implementation is responsible for copying before you store or forward
any instance beyond the immediate call scope.

```csharp
// BAD — both variables refer to the same object
var pos  = new GeoPosition { Latitude = 1.0 };
var pos2 = pos;
pos2.Latitude = 2.0;
// pos.Latitude is now 2.0 — probably not what you intended

// GOOD — pos2 is an independent copy
var pos2 = new GeoPosition(pos);
```

For value-type members (primitives, `decimal`, enums) the copy constructor
assigns directly.  For reference-type members (nested classes, strings) it
calls the nested type's copy constructor.  For sequences (`List<T>`) it does
an element-wise copy.  For arrays it calls `Clone()`.

---

## 7. Enum → C# Enum

IDL enums map directly to C# `enum`.  Value names are preserved exactly.

```idl
enum ThreatLevel { LOW, MEDIUM, HIGH, CRITICAL };
```

```csharp
namespace FACE.DM.SampleModel
{
    public enum ThreatLevel
    {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }
}
```

---

## 8. Interface → C# Interface

IDL interfaces with **more than one operation** map to a C# `interface`
with an `I` prefix.

```idl
interface ThreatProcessor {
    void update_position(in GeoPosition pos, out ThreatLevel level);
    long get_track_id();
};
```

```csharp
public interface IThreatProcessor
{
    ThreatLevel UpdatePosition(GeoPosition pos);
    int GetTrackId();
}
```

There are no `out` or `ref` keywords, no `Holder<T>`.  See Section 9 for how
parameter directions are transformed.

---

## 9. Method Signature Transformation

This is the most significant difference from the Java mapping.

### 9.1 Direction Rules

| IDL direction | C# treatment |
|---|---|
| `in T` | Regular C# input parameter |
| `out T` | Moved to the return value (never a C# `out` param) |
| `inout T` | Input parameter **and** contributes to the return value |

### 9.2 Return Value Construction

| Outputs | C# return type |
|---|---|
| None (all `in`) | `void` |
| One `out` or `inout` | `T` directly |
| Multiple outputs | Named tuple `(T1 Name1, T2 Name2, ...)` |

```idl
// One out param → direct return
void get_position(out GeoPosition pos);
```
```csharp
GeoPosition GetPosition();
```

```idl
// Multiple out params → named tuple
void compute(in float input, out double result, out long status);
```
```csharp
(double Result, long Status) Compute(float input);
```

```idl
// inout → appears as both input param and output element
void transform(inout GeoPosition pos, out ThreatLevel level);
```
```csharp
(GeoPosition Pos, ThreatLevel Level) Transform(GeoPosition pos);
```

### 9.3 C# Keyword Conflicts

If an IDL identifier is a C# keyword (e.g. `event`, `object`, `params`), the
generator prefixes it with `FACE_`.  This is consistent with the Java mapping's
`safeName()` treatment.

---

## 10. Single-Operation Interface → Delegate

A FACE dependency injection port is typed: you receive exactly one FACE-defined
callback interface with one operation.  Because you cannot substitute an
arbitrary user-defined interface at a FACE DI port, there is no benefit to
generating a full C# interface.  Instead, a **single-operation** IDL interface
maps to a C# `delegate`.

```idl
interface Read_Callback {
    void send_message(in TrackData data, in long transaction_id);
};
```

```csharp
// Generated — no I-prefix; delegate name matches the IDL interface name
public delegate void Read_Callback(TrackData data, int transactionId);
```

Your UoP registers a method group or lambda matching this signature at its
FACE DI port.  The TSS calls the delegate when data arrives.

---

## 11. Template Module Instantiations

FACE defines typed transport services via IDL template modules.  Each
instantiation produces concrete types inside the enclosing namespace.

Unlike the Java mapping (which wraps types in a static outer class), the C#
generator emits each substituted type directly into the namespace.  This
follows standard .NET conventions.

```idl
// In the FACE framework IDL
template module Typed_TS<typename ENTITY_TYPE, typename MESSAGE_TYPE> {
    interface Read_Callback {
        void send_message(in MESSAGE_TYPE msg, in FACE::Long xact_id);
    };
    struct SingleMessage {
        MESSAGE_TYPE data;
    };
};

// In your DM IDL
typedef Typed_TS<TrackEntity, TrackData> TrackTS;
```

Generated files for `TrackTS`:

```csharp
// TrackTS.cs
namespace FACE.TSS.TrackDomain
{
    // Substituted callback interface → delegate (single operation)
    public delegate void Read_Callback(TrackData msg, long xactId);

    // Substituted struct → sealed class
    public sealed class SingleMessage
    {
        public TrackData Data { get; set; }

        public SingleMessage() { }
        public SingleMessage(SingleMessage source)
        {
            if (source is null) throw new ArgumentNullException(nameof(source));
            Data = new TrackData(source.Data);
        }
    }
}
```

---

## 12. FACE Framework Exception Types

Two exception classes are generated into the `FACE` namespace:

```csharp
namespace FACE
{
    public class BadParamException    : System.Exception { ... }
    public class DataConversionException : System.Exception { ... }
}
```

These are C# adaptations of `BAD_PARAM` (§K.4.2) and `DATA_CONVERSION`
(§K.4.3).  Unlike the Java mapping (which requires them to be declared in
`throws` clauses), C# exceptions are unchecked — the compiler will not enforce
them.  Generated interface methods document them in XML doc comments.  You
should handle them at UoP boundaries.

---

## 13. Implementing a UoP — Worked Example

Suppose your data model defines:

```idl
module FACE { module DM { module TrackDomain {
    struct TrackData {
        long long track_id;
        double    lat;
        double    lon;
    };
    interface IProcessor {
        void process(in TrackData data, out long result_code);
    };
}}}
```

Generated C#:

```csharp
// TrackData.cs
namespace FACE.DM.TrackDomain
{
    public sealed class TrackData
    {
        public long   TrackId { get; set; }
        public double Lat     { get; set; }
        public double Lon     { get; set; }

        public TrackData() { }
        public TrackData(TrackData source)
        {
            TrackId = source.TrackId;
            Lat     = source.Lat;
            Lon     = source.Lon;
        }
    }
}

// IProcessor.cs
namespace FACE.DM.TrackDomain
{
    public interface IIProcessor
    {
        long Process(TrackData data);
    }
}
```

Your UoP implementation:

```csharp
using FACE.DM.TrackDomain;

public class MyTrackProcessor : IIProcessor
{
    public long Process(TrackData data)
    {
        // data is a copy made by the TSS — safe to read without further copying.
        // If you store it, make your own copy:
        var stored = new TrackData(data);
        _store.Add(stored);

        return 0;  // result_code
    }

    private readonly List<TrackData> _store = new();
}
```

---

## 14. What Is Not Mapped

The following IDL constructs are skipped with a log warning:

- **Union** — discriminated unions have no direct idiomatic C# equivalent.
  Model your discriminated type as an abstract base class with derived sealed
  classes, or as a `OneOf`-style pattern, and introduce it outside the generator.
- **Typedef** — resolved transparently during type emission.  No separate C#
  type alias is generated.
- **Const** — not mapped.  Define constants in your own assembly if needed.
- **Template module declarations** — only instantiations produce output.

---

## 15. Quick Reference Card

| IDL construct | C# output |
|---|---|
| `module A::B` | `namespace A.B` (case-preserved) |
| `struct Foo` | `sealed class Foo` + copy ctor |
| `enum Bar` | `enum Bar` |
| Single-op interface | `delegate` |
| Multi-op interface | `interface IFoo` |
| `in T param` | `T param` (input) |
| `out T param` | Becomes return value (or tuple element) |
| `inout T param` | Input param + return value element |
| `BAD_PARAM` | `FACE.BadParamException` |
| `DATA_CONVERSION` | `FACE.DataConversionException` |

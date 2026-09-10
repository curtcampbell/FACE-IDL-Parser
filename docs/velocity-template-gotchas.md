# Velocity Template Developer's Guide — Gotchas & Lessons Learned

These are hard-won lessons from developing Velocity 2.3 templates for the
FACE TS 3.2 EntityReactor code generator.  Each section describes the
symptom, the root cause, and the correct pattern to use.

---

## 1. `$velocityHasNext` is unreliable in Velocity 2.x — use `$foreach.hasNext`

**Symptom:** Commas missing between all array/initializer-list elements, not
just the last one.

**Root cause:** `$velocityHasNext` is a legacy Velocity 1.x alias.  In
Velocity 2.x it is deprecated and its value is unreliable, especially inside
nested `#foreach` loops where the outer loop's value can shadow the inner
one.

**Rule:** Always use `$foreach.hasNext` (and `$foreach.count`, `$foreach.index`).
Never use `$velocityHasNext` or `$velocityCount`.

```velocity
## WRONG
#foreach($item in $items)
    { ... }#if ($velocityHasNext),#end
#end

## CORRECT
#foreach($item in $items)
    { ... }#if ($foreach.hasNext),#end
#end
```

---

## 2. `\#include` vs `\#pragma` — only escape real VTL directives

**Symptom:** Generated files contain a literal backslash: `\#pragma once`.

**Root cause:** The backslash escape in Velocity (`\#foo`) is only consumed
when `#foo` is a recognized VTL directive.  `#include` is a built-in VTL
directive (it reads and inserts a file from the template path), so `\#include`
correctly emits `#include`.  `#pragma` is not a VTL directive, so `\#pragma`
emits `\#pragma` — the backslash is kept.

**Rule:** Escape only the directives that Velocity actually recognizes:

| C++ line | Template |
|---|---|
| `#include "foo.h"` | `\#include "foo.h"` |
| `#pragma once` | `#pragma once` (no escape needed) |
| `#ifndef GUARD_H` | `#ifndef GUARD_H` (no escape needed) |
| `#define GUARD_H` | `#define GUARD_H` (no escape needed) |

Built-in VTL directives that need escaping if used literally in output:
`#include`, `#parse`, `#set`, `#if`, `#else`, `#elseif`, `#end`,
`#foreach`, `#macro`, `#stop`, `#break`, `#define`, `#evaluate`.

---

## 3. Velocity `#set` silently discards null — unresolved variables render as literal text

**Symptom:** Template variable references like `${reqType}` appear verbatim
in generated output instead of being substituted.

**Root cause:** Velocity's `#set` has a special rule: if the right-hand side
evaluates to `null`, the assignment is a **no-op** — the variable is left
undefined.  When an undefined variable is referenced, Velocity (in
non-strict mode) renders it as the literal reference string `${reqType}`.

Common triggers:
- The Java object being accessed has no JavaBean getter for the property
  (Velocity 2.x uses `getXxx()` methods; public fields alone are not
  guaranteed to be accessible).
- A method call throws an exception (e.g. `list.get(0)` on an empty list);
  Velocity swallows it and the result is null.

**Rule:** Ensure every Java object exposed to Velocity templates has
JavaBean-style getters (`getXxx()`) for every property accessed in templates.
Public fields alone are not sufficient.

```java
// WRONG — Velocity may not see this
public final List<IdlType> resolvedActuals;

// CORRECT — add explicit getters
public final List<IdlType> resolvedActuals;
public List<IdlType> getResolvedActuals() { return resolvedActuals; }
```

---

## 4. Resolved IDL type names already carry the `::` prefix — don't double it

**Symptom:** Generated C++ has `::::FACE::DM::SampleModel::EntityEvent` (four
colons).

**Root cause:** `IdlType.Scoped.toString()` returns the fully-qualified name
as stored, e.g. `"::FACE::DM::SampleModel::EntityEvent"`.  If the template
also prepends `::`, the result is `::::`.

**Rule:** Do not add a `::` prefix before a Velocity variable that holds a
resolved IDL type.  The resolved type already includes it.

```velocity
## WRONG
const ::${reqType}&   message,   ## → const ::::FACE::DM::...::EntityCrudRequest&

## CORRECT
const ${reqType}&     message,   ## → const ::FACE::DM::...::EntityCrudRequest&
```

---

## 5. Velocity arithmetic inside `${...}` is invalid — use `#set`

**Symptom:** Parse error or literal `${idx + 1}` in output.

**Root cause:** VTL does not support arithmetic expressions inside `${...}`
property references.  Only simple variable names and property chains are
allowed there.

**Rule:** Use `#set` to compute values before using them in output.

```velocity
## WRONG
//   [${idx}] (= ${idx + 1})

## CORRECT
#set ($ordinal = $idx + 1)
//   [${idx}] (= ${ordinal})
```

---

## 6. Non-ASCII characters in templates can cause Velocity tokenizer failures

**Symptom:** Velocity parse or tokenizer error at a specific line/column
that contains a non-ASCII character (e.g. `→`, em-dashes).

**Root cause:** Velocity's default lexer may mishandle non-ASCII code points,
producing confusing parse failures well after the actual offending character.

**Rule:** Use only ASCII in `.vm` template files.  Replace Unicode arrows
and punctuation with ASCII equivalents:

| Unicode | ASCII |
|---|---|
| `→` | `->` |
| `←` | `<-` |
| `—` (em dash) | `--` |
| `…` | `...` |

---

## 7. Enum-to-integer assignment requires an explicit cast

**Symptom:** Compiler error: `invalid conversion from 'int64_t' to 'SomeEnum'
[-fpermissive]` in generated registrar code.

**Root cause:** The EntityReactor stores all integer-like values (including
enums) as `int64_t` in `EntityValue::scalar.int_val`.  When copying back
to a FACE struct whose field is an IDL enum type, the implicit conversion
from `int64_t` to the enum is not permitted in C++.

**Rule:** Always cast scalar integer reads to the destination type using
`static_cast<decltype(dst.field)>(...)`.  This is a zero-overhead no-op for
plain integer fields and performs the correct enum conversion otherwise.

```velocity
## WRONG
dst.${tf.name} = entity_payload.get(${tf.ordinal}u).scalar.${er.evScalarMember($tf.entityValueKind)};

## CORRECT
dst.${tf.name} = static_cast<decltype(dst.${tf.name})>(
    entity_payload.get(${tf.ordinal}u).scalar.${er.evScalarMember($tf.entityValueKind)});
```

---

## 8. Match `EntityValue` / `AttributeValue` C++ API exactly

The Java helper methods `evFromMethod()`, `evScalarMember()`, and
`avFromMethod()` must match the actual C++ method and member names in
`EntityPayload.hpp` and `Types.hpp`.  Mismatches are silent at codegen time
and produce linker or type errors at compile time.

Verified mapping (as of EntityReactor current version):

| Kind | `from_xxx` factory | `.scalar.xxx` member |
|---|---|---|
| `EV_GUID` / `AV_GUID` | `from_guid` | `guid_val` |
| `EV_INT64` / `AV_INT64` | `from_int` | `int_val` |
| `EV_UINT64` / `AV_UINT64` | `from_uint` | `uint_val` |
| `EV_FLOAT` / `AV_FLOAT` | `from_float` | `float_val` |
| `EV_DOUBLE` / `AV_DOUBLE` | `from_double` | `double_val` |
| `EV_BOOL` / `AV_BOOLEAN` | `from_bool` | `bool_val` |
| `EV_SYSTEM_TIME` | `from_system_time` | `time_val` |
| `AV_SYSTEM_TIME` | `from_time` | `time_val` |
| `EV_STRING` / `AV_STRING` | `from_string` | *(use `.string_val` directly)* |
| `EV_BYTES` | `from_bytes` | *(use `.bytes_val` directly)* |
| `EV_COMPOSITE` | `from_composite` | *(use `.composite_val` directly)* |

**Note:** The names are `from_int` / `from_uint` — NOT `from_int64` /
`from_uint64`.  The scalar members are `int_val` / `uint_val` — NOT
`int64_val` / `uint64_val`.

---

## 9. Truncated template files produce silently truncated output

**Symptom:** Generated file is cut off mid-line or mid-function with no
error from Velocity or the build system.

**Root cause:** Velocity renders exactly what the template contains and stops
at EOF.  If a `.vm` file was truncated during editing (e.g. by a tool that
streams or chunks output), Velocity has no way to detect that the file is
incomplete — it simply stops generating at the end of the file.

**Rule:** After creating or editing a template programmatically, always verify
that the file ends at the expected closing line (usually a closing `}`
and/or `} // namespace ...`).  Check with:

```sh
tail -5 path/to/template.vm
```

If the last line is mid-expression or mid-statement, the file is truncated
and must be restored.

---

## 10. `##!` directives must appear before any non-comment Velocity content

**Symptom:** Template is not included in the generation plan; no output files
are produced for that template even though the directive is present.

**Root cause:** `DirectiveScanner` reads `##!` headers by scanning lines
from the top of the file until the first non-comment, non-blank line. A
`#set`, blank template output, or any non-comment directive before the `##!`
lines causes the scanner to stop, and all `##!` lines after that point are
missed.

**Rule:** Place all `##!` directives at the very top of the `.vm` file,
before any Velocity content including `#set` statements. Regular `## comments`
are fine.

```velocity
## WRONG — #set before ##! causes directives to be missed
#set($someVar = "value")
##! for_each: STRUCT
##! output:   {struct.name}.hpp

## CORRECT — ##! directives before any Velocity content
##! for_each: STRUCT
##! output:   {struct.name}.hpp
#set($someVar = "value")
```

---

## 11. Driver templates: `$outFile` must be assigned on its own `#set` line

**Symptom:** Driver template runs but produces zero output files; or only some
output files are produced.

**Root cause:** The driver dispatch in `DriverOutputHandler` is a plain text
scan for `#set($outFile = ...)` / `#parse(...)` pairs. The scan is line-oriented.
A `$outFile` assignment embedded in a compound expression, on the same line as
other content, or inside a `#if` block that the scanner cannot evaluate will
not be recognized.

**Rule:** Every `$outFile` assignment must be a standalone `#set` on its own
line, followed immediately (possibly after whitespace) by the corresponding
`#parse`.

```velocity
## WRONG — $outFile on same line as other content
#set($outFile = "${project_namespace}/${inst.alias}TS.hpp") ## inline comment

## CORRECT — dedicated line for each #set / #parse pair
#set($outFile = "${project_namespace}/${inst.alias}TS.hpp")
#parse("entity_crud_ts.hpp.vm")
#set($outFile = "${inst.alias}TS.cpp")
#parse("entity_crud_ts.cpp.vm")
```

---

## 12. FILE scope: `$file.definitions` contains only top-level definitions of that file

**Symptom:** Template using `$file.definitions` iterates fewer items than
expected; transitively included types are missing.

**Root cause:** `IdlFileUnit.definitions` is built from a per-file parse of
only that file's own content. Types included via `#include` directives in the
`.idl` file appear in the merged `$spec.definitions` but not in
`$file.definitions`.

**Rule:** Use `$file.definitions` only when you want definitions from a
single file. Use `$spec.definitions` (or the walker methods) when you need
all types from the merged specification.

```velocity
## WRONG — expects included types to appear in $file.definitions
#foreach($def in $file.definitions)
    ## may miss types from #included files
#end

## CORRECT — use $spec for merged definitions, $file for single-file definitions
#foreach($def in $spec.definitions)
    ## all types from all files
#end
```

---

## 13. `sourceFile` is null on merged-spec nodes — only set on FILE scope nodes

**Symptom:** `$def.sourceFile` renders as the literal `${def.sourceFile}` or
`null` when iterating over `$spec.definitions`.

**Root cause:** `IdlDefinition.sourceFile()` is set only on nodes produced by
the per-file unit parse used in FILE scope iteration. Nodes in the merged
`IdlSpecification` (produced by the combined ANTLR4 parse) have `sourceFile`
as `null`.

**Rule:** Only use `$def.sourceFile` inside a `for_each: FILE` template where
`$file.definitions` is the iteration source. Never use it on nodes from
`$spec.definitions` in other scope templates.

```velocity
## CORRECT — sourceFile is set on nodes from $file.definitions
##! for_each: FILE
##! output:   {file.relativeOutputPath}
#foreach($def in $file.definitions)
    ## $def.sourceFile is set and non-null here
    // From file: ${def.sourceFile}
#end
```

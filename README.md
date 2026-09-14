# FACE IDL Tools

A toolchain for the [FACE Technical Standard](https://www.opengroup.org/face) (3.2):
parses entity models and FACE IDL, and generates IDL and language bindings from them.
Install it once per development environment, the same way you would install `cmake`
or a compiler — projects that consume FACE IDL point their build at the install
rather than vendoring the tool.

## What's in it

Three command-line tools, built as a single Maven project:

| Command           | Reads                                             | Produces                                                     |
|-------------------|----------------------------------------------------|----------------------------------------------------------------|
| `face-idl-gen`    | Entity models (YAML/JSON) or `.face` XMI files    | FACE IDL (data-model IDL, TSS/TypedTS IDL) — and, when given a language flag (`--cpp`, `--java`, `--python`, `--csharp`), bindings for that IDL in the same run |
| `face-idl-binder` | FACE IDL you already have (hand-written, or from a previous run) | Language bindings (C++, Java, Python, C#), without regenerating the IDL |
| `face-codegen`    | FACE IDL + optional `.face` model + your Velocity templates | Application code (transport services, UoP skeletons, etc.) |

`face-idl-gen` is the primary entry point: it generates FACE-standard IDL
and, in the same step, bindings for it — see [Quick usage](#quick-usage)
below. `face-idl-binder` exists separately for the narrower case of binding
IDL you already have without asking `face-idl-gen` to regenerate it.
`face-codegen` is a different kind of tool entirely: it generates *your*
implementation code (not FACE-standard bindings) against templates you
supply. See `docs/user-guide.md` for the full pipeline and
template-authoring reference.

## Requirements

- JDK 17+
- Maven 3.6+

## Build

```sh
git clone git@github.com:curtcampbell/FACE-IDL-Parser.git
cd FACE-IDL-Parser
mvn clean package
```

This produces `target/face-idl-tools-<version>-dist.zip` — the installable
distribution — along with the three standalone jars and the raw test/compile
output. The same command works identically on Linux, WSL, and Windows.

## Install

The distribution zip is self-contained; unzip it anywhere and point your `PATH`
at its `bin/` directory. There is no installer script and no environment
variable to set for the tools themselves.

### Linux / WSL

```sh
unzip target/face-idl-tools-<version>-dist.zip -d /opt
export PATH="/opt/face-idl-tools-<version>/bin:$PATH"

face-idl-gen --version
face-idl-binder --version
face-codegen --version
```

Add the `export PATH=...` line to your shell profile to make it permanent, or
symlink the three launchers onto an existing `PATH` directory (e.g.
`/usr/local/bin`) instead — they resolve their own install root even when
followed through a symlink.

### Windows

```powershell
Expand-Archive target\face-idl-tools-<version>-dist.zip -DestinationPath C:\Tools
$env:Path += ";C:\Tools\face-idl-tools-<version>\bin"

face-idl-gen --version
face-idl-binder --version
face-codegen --version
```

Add the install's `bin` directory to your permanent `PATH` via System
Properties → Environment Variables to make it available in new shells.

### Layout

```
face-idl-tools-<version>/
├── VERSION                  # plain-text version, matches --version output
├── bin/                     # face-idl-gen, face-idl-binder, face-codegen (+ .bat)
├── lib/                     # shaded jars for each tool
├── conf/logging.properties  # default java.util.logging config
├── face-idl/                # FACE framework IDL (§J.8 core types)
└── templates/               # built-in IDL and language-binding templates
```

`JAVA_HOME` is honored if set; otherwise the launchers fall back to `java` on
`PATH`. Override the logging config per-invocation with `FACE_IDL_TOOLS_LOGGING`.

## Quick usage

### Generate IDL and bindings from a `.face` model

The most common path — TSS data-model and TypedTS IDL from a `.face` model,
plus C++ bindings for it — is a **single command**, and works with nothing
beyond the install, since the FACE data-model-IDL and C++ binding templates
ship with the tool:

```sh
face-idl-gen generate-tss-idl --cpp -o out examples/GROCERY_with_IM.face
```

This writes both the IDL (`out/idl/data-model/FACE/...`) and ready-to-compile
C++ headers (`out/cpp/face-model/include/FACE/DM/...`, `FACE/TSS/...`) —
`generate-tss-idl` binds the IDL it just generated in the same run unless you
pass `--idl-only`.

`--cpp` is one of several language flags `generate-tss-idl` (and every other
IDL-consuming command below) accepts: `--java`, `--python`, and `--csharp`
also generate that language's bindings, `--all-face` generates C++ and Java
together (the FACE-standard pair, and the default when no flag is given), and
`--all-languages` generates all four. Most current consumers (e.g.
[BLUSH](https://github.com/curtcampbell/BLUSH), a C++17 FACE library built on
this tool) only exercise `--cpp` today — that's a reflection of what's been
built on top of the output so far, not a limit of the tool, and is likely to
change as other language bindings see real use.

### Bind IDL you already have, without regenerating it

If you're starting from IDL you didn't just generate — hand-written, or kept
from an earlier run — `face-idl-binder` binds it directly:

```sh
face-idl-binder bind --cpp -i out/idl/data-model -o out/bindings
```

### Generate your own code from that IDL

`face-idl-gen`/`face-idl-binder` only ever produce FACE-standard bindings.
For application-specific code on top of the same IDL — a transport service
implementation, UoP skeleton classes, whatever your project needs —
`face-codegen` renders your own Velocity (`.vm`) templates against it:

```sh
face-codegen generate -f examples/GROCERY_with_IM.face \
    -i out/idl -t path/to/your/templates -o out/codegen
```

`-t`/`--template-dir` is required and always points at templates you supply
yourself; unlike the FACE-standard IDL/binding templates, project-specific
templates are intentionally not part of this distribution.
[BLUSH](https://github.com/curtcampbell/BLUSH)'s `data-model/templates/` and
`uop-generator/templates/` are a real, working set if you want to see the
shape of one — including a `codegen.yaml` manifest and per-file `##!`
directives, both described in `docs/user-guide.md`.

`face-idl-gen generate` / `generate-entity-idl` (entity-reactor IDL from a
YAML/JSON or `.face` model, rather than the TSS pipeline above) similarly
need a `--templates-dir` you supply.

Run any command with `--help`, or `<command> help <subcommand>`, for full
option reference.

## Documentation

See `docs/` for template authoring, the `##!` directive system, YAML model
authoring, and per-language binding guides — start with `docs/user-guide.md`.

## Development

`scripts/dev/` has lightweight launchers that run the jars straight out of
`target/` after a plain `mvn package` — no need to unzip a distribution while
iterating locally.

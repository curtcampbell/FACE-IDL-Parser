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
| `face-idl-gen`    | Entity models (YAML/JSON) or `.face` XMI files    | FACE IDL (data-model IDL, TSS/TypedTS IDL)                    |
| `face-idl-binder` | FACE IDL                                          | Language bindings (C++, Java, Python, C#)                     |
| `face-codegen`    | FACE IDL + optional `.face` model + your Velocity templates | Application code (transport services, UoP skeletons, etc.) |

`face-idl-gen` and `face-idl-binder` generate FACE-standard IDL and bindings;
`face-codegen` is for generating your own implementation code against
user-supplied templates. See `docs/user-guide.md` for the full pipeline and
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

Generate TSS data-model and TypedTS IDL from a `.face` model, then C++
bindings from that IDL — this works with nothing beyond the install, since
the FACE data-model-IDL and language-binding templates ship with the tool:

```sh
face-idl-gen generate-tss-idl --cpp -o out examples/GROCERY_with_IM.face
face-idl-binder bind --cpp -i out/idl/data-model -o out/bindings
```

`face-idl-gen generate` / `generate-entity-idl` (entity-reactor IDL from a
YAML/JSON or `.face` model) and `face-codegen generate` (implementation code
from IDL) both need a `--templates-dir`/`--template-dir` you supply yourself —
those templates are project-specific and intentionally not part of this
distribution; only the FACE-standard IDL and language-binding templates ship
with it.

Run any command with `--help`, or `<command> help <subcommand>`, for full
option reference.

## Documentation

See `docs/` for template authoring, the `##!` directive system, YAML model
authoring, and per-language binding guides — start with `docs/user-guide.md`.

## Development

`scripts/dev/` has lightweight launchers that run the jars straight out of
`target/` after a plain `mvn package` — no need to unzip a distribution while
iterating locally.

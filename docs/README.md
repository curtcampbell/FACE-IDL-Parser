# code_generator/docs — Document Summary

All documents in this directory were updated to reflect the current `face-codegen`
tool (v0.1.0-SNAPSHOT, FACE TS 3.2) including the Phase 1–8 codegen refactor.

The refactor introduced three key changes that affect all documents:

- **`##!` directive system** — templates declare their own iteration behavior;
  `codegen.yaml` is no longer required.
- **`codegen-defaults.yaml`** — template-set-level defaults (variables, helpers)
  that apply to every project using the template set.
- **Wrapper scripts** — `code_generator/bin/face-codegen.bat` and
  `code_generator/bin/face-idl-gen.bat` launch the respective JARs with logging
  configured via `logging.properties`.

---

**`user-guide.md`** — User-facing reference for `face-codegen generate`. Covers:
the `##!` directive system; `codegen-defaults.yaml`; auto-derived variables;
`FILE` scope and `$file` context; driver templates; wrapper scripts; template set
overview (distinguishing `face-idl-gen` `.vtl` sets from `face-codegen` `.vm`
sets); updated CLI reference (noting `--manifest` is optional); and updated
troubleshooting. Section 13 includes `face-idl-gen generate-entity-idl` usage
examples for all three entity-source modes: YAML/JSON model, `--entities`
(template elements from the default group), and `--entity-source GROUP` (named
`um:UoPModel` group). Intended as a developer quick-reference while writing
templates or running commands.

**`user-guide.docx`** — Word version of the user guide. Professional user manual
with a cover page, auto-generated Table of Contents, Getting Started walkthrough,
callout boxes for warnings and tips, a conceptual pipeline diagram, and a CLI
reference appendix. Less exhaustive than the Markdown; focused on common workflows.

**`yaml-authoring-guide.md`** — Template and manifest authoring reference.
Reorganized to lead with `##!` directives as the primary mechanism, with
`codegen.yaml` documented as optional/override. Covers all `for_each` scope
values (including `FILE`, `ENUM`, `TYPEDEF`, `UNION`, `CONST`, `GLOBAL`);
`FILE` scope output tokens; auto-derived variables; `codegen-defaults.yaml`;
and the directive vs. manifest comparison table. The annotated `codegen.yaml`
example is retained and annotated as the legacy/override style.

**`yaml-authoring-guide.docx`** — Word version of the template authoring guide.
Reorganized to lead with `##!` directives; `codegen.yaml` moved to a
"Customization and Overrides" section. Includes a two-column comparison table
(directive-based vs. manifest-based approaches) and a sidebar on when
`codegen.yaml` is still needed.

**`design-guide.md`** — Engineer-facing implementation reference. Updated
pipeline diagram (manifest now optional; `##!` directive scanning and
`IdlDerivedVariables` added as stages). New sections: `##!` Directive Scanning,
`IdlFileUnit` and FILE Scope, and `IdlDerivedVariables`. `sourceFile` on
`IdlDefinition` documented. Template set type distinctions added (§11).
`FaceTemplateEntityReader` and the template entity source path (Workflow B)
added to the `reader.face` package entry and §11. Sections renumbered to 1–12.

**`idl-input-guide.md`** — IDL input format, include resolution algorithm, and
`TEMPLATE_INST` scope reference. No changes required — the IDL parsing layer
was not affected by the refactor.

**`velocity-template-gotchas.md`** — Velocity 2.3 lessons learned. Extended
with four new gotchas specific to the refactored features: `##!` directives
placement (§10), driver template `$outFile` line requirements (§11), FILE scope
`$file.definitions` scope (§12), and `sourceFile` null on merged-spec nodes (§13).

**`language-mapping-guide.docx`** — Developer reference for the language binding
code generator (`language.yaml`-driven pipeline). Verified for accuracy against
`templates/languages/csharp/language.yaml`. Minor updates to clarify the
distinction between the language binding pipeline and `face-codegen generate`.

**`csharp-binding-guide.md`** — C# language binding guide for UoP developers.
Section 2 (Activating C# Generation) updated to remove stale `er-generator`
commands. All other sections verified accurate.

**`template-entity-source-design.md`** — Design specification for the template
entity source feature (`FaceTemplateEntityReader`). Documents the two-pass
registration/field-resolution algorithm, the `--entities` and `--entity-source`
CLI flags added to `face-idl-gen generate-entity-idl` and `generate`, the
`structNamePattern` convention (`{name}Entity`), and the test coverage
requirements. This is the authoritative spec for Workflow B.

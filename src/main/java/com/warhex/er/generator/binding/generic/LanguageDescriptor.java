package com.warhex.er.generator.binding.generic;

import java.util.List;
import java.util.Map;

/**
 * POJO representation of a {@code language.yaml} descriptor file.
 *
 * <p>Deserialised by SnakeYAML 2.2 via {@code Yaml.loadAs(input, LanguageDescriptor.class)}.
 * All fields are public to satisfy SnakeYAML's default bean-property mapping without
 * requiring a custom constructor.
 *
 * <p>The {@link #templates} field is typed {@code Map<String,Object>} because each value
 * may be either a single template entry ({@code Map<String,Object>}) or a list of entries
 * ({@code List<Map<String,Object>>}).  Use {@link #getTemplateEntries(String)} to normalise.
 */
public class LanguageDescriptor {

    // ── Identity ─────────────────────────────────────────────────────────────

    /** Display name, e.g. {@code "Java"}, {@code "C++"}. */
    public String name;

    /** Output subdirectory under the root output dir, e.g. {@code "java"}, {@code "cpp"}. */
    public String output_subdirectory;

    /**
     * Filename of the macro library loaded before any template in this language,
     * e.g. {@code "macros.vm"}.  May be {@code null} if the language has no macros.
     */
    public String macro_library;

    /**
     * Fully-qualified class name of a legacy per-language type helper to instantiate
     * (no-arg constructor required) and expose in the Velocity context.
     * Used during migration Phases 2–5 to keep existing templates working without change.
     * Example: {@code "com.warhex.er.generator.binding.java.JavaTypeHelper"}.
     * {@code null} means no legacy helper.
     */
    public String legacy_helper_class;

    /**
     * Velocity context key under which the legacy helper is exposed.
     * Example: {@code "java"} → {@code $java} in templates.
     * Ignored when {@link #legacy_helper_class} is {@code null}.
     */
    public String legacy_helper_key;

    // ── Package / namespace name computation ──────────────────────────────────

    /**
     * Separator character used to join module-stack segments into a package/
     * namespace name (e.g. {@code "."} for Java and C#, {@code "::"} for C++).
     * When non-null, the engine computes {@code $packageName} (and
     * {@code $namespaceName} as an alias) from the module stack using this
     * separator and {@link #package_case}.
     */
    public String package_separator;

    /**
     * Casing applied to each module-stack segment before joining.
     * {@code "lower"} → lowercase; {@code "preserve"} → as-is in IDL.
     * Defaults to {@code "preserve"} when null.
     */
    public String package_case;

    // ── Iteration strategy ────────────────────────────────────────────────────

    /** Iteration and output-path configuration. */
    public IterationConfig iteration;

    // ── Template routing ──────────────────────────────────────────────────────

    /**
     * Maps IDL construct kind (e.g. {@code "struct"}, {@code "enum"}, {@code "file"})
     * to either a single {@code TemplateEntry} map or a list of them.
     * Normalise with {@link #getTemplateEntries(String)}.
     */
    public Map<String, Object> templates;

    // ── Type mapping ──────────────────────────────────────────────────────────

    /**
     * Tier 1: IDL {@link com.warhex.er.generator.ast.PrimitiveKind} name → language type.
     * Keys are enum constant names (e.g. {@code "SHORT"}, {@code "LONG_LONG"}).
     */
    public Map<String, String> primitive_types;

    /**
     * Tier 2: Parameterised type patterns.
     * Keys: {@code "string"}, {@code "wstring"}, {@code "sequence"}, {@code "array"}.
     * Values support tokens: {@code {element}}, {@code {element:boxed}}, {@code {bound}}.
     */
    public Map<String, String> parameterized_types;

    /**
     * Tier 3: Scoped-name overrides.
     * Maps FACE qualified names (with and without leading {@code ::}) → language type.
     * Checked before typedef-chain resolution.
     */
    public Map<String, String> scoped_overrides;

    // ── Naming helpers ────────────────────────────────────────────────────────

    /**
     * Language type strings that are immutable (need Holder wrapping for Java
     * out/inout, etc.).  Used by {@link TypeResolver#isImmutable(com.warhex.er.generator.ast.IdlType)}.
     */
    public List<String> immutable_types;

    /**
     * Maps primitive language type names → wrapper/boxed class names.
     * Used by {@link TypeResolver#boxedType(com.warhex.er.generator.ast.IdlType)}.
     */
    public Map<String, String> boxed_types;

    /** Prefix prepended to identifiers that clash with reserved words, e.g. {@code "FACE_"}. */
    public String reserved_prefix;

    /** List of reserved words / keywords for this language. */
    public List<String> reserved_words;

    // ── Include computation ───────────────────────────────────────────────────

    /** Rules for computing {@code #include} or {@code import} lists (C++ specific). */
    public IncludeComputation include_computation;

    // ── Static files ──────────────────────────────────────────────────────────

    /** Files emitted once before the AST walk (not tied to any IDL construct). */
    public List<StaticFileEntry> static_files;

    // ── Post-processing files ─────────────────────────────────────────────────

    /** Files emitted once after the AST walk (e.g. Python {@code __init__.py}). */
    public List<PostFileEntry> post_files;

    // =========================================================================
    // Helper: normalise the templates map entry for a given construct kind
    // =========================================================================

    /**
     * Returns the list of {@link TemplateEntry} objects for the given IDL construct
     * kind.  Handles both the single-entry shorthand and the multi-entry list form.
     *
     * @param kind e.g. {@code "struct"}, {@code "enum"}, {@code "file"}
     * @return possibly-empty list of template entries (never {@code null})
     */
    @SuppressWarnings("unchecked")
    public List<TemplateEntry> getTemplateEntries(String kind) {
        if (templates == null) return List.of();
        Object raw = templates.get(kind);
        if (raw == null) return List.of();
        if (raw instanceof Map) {
            return List.of(TemplateEntry.of((Map<String, Object>) raw));
        }
        if (raw instanceof List) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) raw;
            return list.stream().map(TemplateEntry::of).toList();
        }
        return List.of();
    }

    // =========================================================================
    // Inner classes
    // =========================================================================

    /** Iteration strategy and output-path configuration. */
    public static class IterationConfig {

        /**
         * One of {@code "per_construct"}, {@code "per_idl_file"},
         * or {@code "per_construct_multi"}.
         */
        public String strategy;

        /**
         * Output path pattern for {@code per_construct} / {@code per_construct_multi}.
         * Tokens: {@code {modules}}, {@code {modules:lower}}, {@code {name}}, {@code {name:lower}}.
         */
        public String path;

        /**
         * Extension substitution map for {@code per_idl_file} strategy.
         * Example: {@code { ".idl": ".hpp" }}.
         */
        public Map<String, String> extension_replace;

        /**
         * When {@code true}, compute a {@code $guardBase} string from the output path
         * (strip extension, replace {@code /} and {@code -} with {@code _}, uppercase).
         */
        public boolean compute_guard;

        /**
         * When {@code true} and strategy is {@code per_idl_file} but
         * {@code result.fileUnits()} is empty, fall back to {@code per_construct}.
         */
        public boolean fallback_to_per_construct;

        /**
         * When {@code true} and strategy is {@code per_idl_file}, derive the
         * output path from the IDL file's module chain rather than its directory
         * path.  Requires {@link #path} to be set (e.g. {@code "{modules}/{name}.hpp"}).
         * Used by C++ to mirror the FACE namespace hierarchy instead of the
         * IDL source-tree structure.
         */
        public boolean path_from_namespace;
    }

    /** One template-to-output mapping entry. */
    public static class TemplateEntry {

        /** Template filename, relative to the language's template directory. */
        public final String template;

        /**
         * Output path pattern override.  When {@code null}, falls back to
         * {@link IterationConfig#path}.
         */
        public final String path;

        public TemplateEntry(String template, String path) {
            this.template = template;
            this.path     = path;
        }

        @SuppressWarnings("unchecked")
        static TemplateEntry of(Map<String, Object> m) {
            return new TemplateEntry(
                    (String) m.get("template"),
                    (String) m.get("path"));
        }
    }

    /** Configuration for computing {@code #include} / import lists. */
    public static class IncludeComputation {

        /** Headers always emitted in every file, regardless of content. */
        public List<String> always_include;

        /** Header added when any field/param type is a {@code sequence<T>}. */
        public String sequence_include;

        /** Additional headers added for every {@code template_inst} construct. */
        public List<String> template_inst_always_include;

        /**
         * Pattern for computing an include path from a qualified IDL name.
         * Example: {@code "{qualifiedName:strip-leading-colons:replace(::,/)}.hpp"}.
         */
        public String include_path_pattern;

        /**
         * Qualified-name prefixes whose scoped types are excluded from include
         * computation for struct field types (e.g. {@code "::FACE::"}).
         */
        public List<String> skip_prefixes;

        /**
         * Qualified-name prefixes excluded when computing includes for
         * template-instantiation resolved actuals.  When absent, falls back to
         * {@link #skip_prefixes}.  C++ uses a narrower skip set here than for
         * struct fields (only skip {@code FACE::TSS} / {@code FACE::Common},
         * not all {@code FACE::} types).
         */
        public List<String> template_inst_skip_prefixes;
    }

    /** A static file emitted once before the AST walk. */
    public static class StaticFileEntry {

        /** Template filename for this file. */
        public String template;

        /** Output path relative to the language output subdirectory. */
        public String path;

        /** When {@code true}, skip emission if the output file already exists. */
        public boolean skip_if_exists;
    }

    /** A post-processing file emitted after the AST walk. */
    public static class PostFileEntry {

        /**
         * Emission trigger.  Currently supported: {@code "per_output_directory"}
         * (rendered once per directory that received at least one construct).
         */
        public String trigger;

        /** Template filename. */
        public String template;

        /** Output filename, relative to the triggered directory. */
        public String path;

        /**
         * Context key under which the accumulated data is exposed to the template
         * (e.g. {@code "classNames"} → {@code $classNames} in the template).
         */
        public String context_key;
    }
}

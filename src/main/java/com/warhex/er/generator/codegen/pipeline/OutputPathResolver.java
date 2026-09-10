package com.warhex.er.generator.codegen.pipeline;

import com.warhex.er.generator.ast.ConstNode;
import com.warhex.er.generator.ast.EnumNode;
import com.warhex.er.generator.ast.InterfaceNode;
import com.warhex.er.generator.ast.ModuleNode;
import com.warhex.er.generator.ast.StructNode;
import com.warhex.er.generator.ast.TemplateInstNode;
import com.warhex.er.generator.ast.TypedefNode;
import com.warhex.er.generator.ast.UnionNode;
import com.warhex.er.generator.parser.IdlFileUnit;
import com.warhex.er.generator.reader.dto.ConnectionData;
import com.warhex.er.generator.reader.dto.IntegrationContextData;
import com.warhex.er.generator.reader.dto.UoPData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves output-path patterns into concrete relative file paths.
 *
 * <h2>Pattern syntax</h2>
 * A pattern is a string containing zero or more {@code {token}} placeholders.
 * Each token is replaced with a value derived from the current scope element.
 * Tokens that are not populated for the active scope are left unreplaced,
 * which will cause the pipeline to log a warning.
 *
 * <h2>Available tokens by scope</h2>
 * <pre>
 *   SPEC           — (no tokens; pattern used verbatim)
 *
 *   MODULE         — {module.name}
 *
 *   STRUCT         — {struct.name}
 *                    {module.name}
 *                    {namespaces}     (namespace segments joined by '/')
 *
 *   INTERFACE      — {iface.name}
 *                    {module.name}
 *                    {namespaces}
 *
 *   TEMPLATE_INST  — {inst.alias}
 *                    {inst.templateName}
 *                    {module.name}
 *                    {namespaces}
 *
 *   UOP            — {uop.name}
 *
 *   CONNECTION     — {conn.name}
 *                    {uop.name}
 *
 *   FILE           — {file.stem}
 *                    {file.name}
 *                    {file.relativePath}
 *                    {file.relativeOutputPath}
 * </pre>
 *
 * <h2>Example patterns</h2>
 * <pre>
 *   "{inst.alias}Impl.hpp"                 → "EntityEvent_tsImpl.hpp"
 *   "{uop.name}/{uop.name}Impl.hpp"        → "NavigationUoP/NavigationUoPImpl.hpp"
 *   "{conn.name}Impl.cpp"                  → "Conn_PV1_out_Impl.cpp"
 *   "{namespaces}/{struct.name}.hpp"       → "FACE/DM/SampleModel/GeoPosition.hpp"
 * </pre>
 */
public final class OutputPathResolver {

    private static final Pattern TOKEN = Pattern.compile("\\{([^}]+)}");

    private OutputPathResolver() {}

    // -----------------------------------------------------------------------
    // Scope-specific resolve methods
    // -----------------------------------------------------------------------

    /** Resolves a pattern for the {@code SPEC} scope (no substitutions). */
    public static String forSpec(String pattern) {
        return pattern;
    }

    /** Resolves a pattern for the {@code MODULE} scope. */
    public static String forModule(String pattern, ModuleNode module) {
        return apply(pattern, Map.of(
                "module.name", module.name()
        ));
    }

    /** Resolves a pattern for the {@code STRUCT} scope. */
    public static String forStruct(String pattern,
                                    StructNode struct,
                                    ModuleNode module,
                                    List<String> namespaces) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("struct.name",  struct.name());
        tokens.put("module.name",  module != null ? module.name() : "");
        tokens.put("namespaces",   String.join("/", namespaces));
        return apply(pattern, tokens);
    }

    /** Resolves a pattern for the {@code INTERFACE} scope. */
    public static String forInterface(String pattern,
                                       InterfaceNode iface,
                                       ModuleNode module,
                                       List<String> namespaces) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("iface.name",  iface.name());
        tokens.put("module.name", module != null ? module.name() : "");
        tokens.put("namespaces",  String.join("/", namespaces));
        return apply(pattern, tokens);
    }

    /** Resolves a pattern for the {@code TEMPLATE_INST} scope. */
    public static String forTemplateInst(String pattern,
                                          TemplateInstNode inst,
                                          ModuleNode module,
                                          List<String> namespaces) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("inst.alias",        inst.alias());
        tokens.put("inst.templateName", inst.templateName());
        tokens.put("module.name",       module != null ? module.name() : "");
        tokens.put("namespaces",        String.join("/", namespaces));
        return apply(pattern, tokens);
    }

    /** Resolves a pattern for the {@code UOP} scope. */
    public static String forUop(String pattern, UoPData uop) {
        return apply(pattern, Map.of(
                "uop.name", uop.getName()
        ));
    }

    /** Resolves a pattern for the {@code CONNECTION} scope. */
    public static String forConnection(String pattern,
                                        UoPData uop,
                                        ConnectionData conn) {
        return apply(pattern, Map.of(
                "uop.name",  uop.getName(),
                "conn.name", conn.getName()
        ));
    }


    /** Resolves a pattern for the {@code UOP_INTEGRATION_CONTEXT} scope.
     *
     * <h2>Available tokens</h2>
     * <pre>
     *   {uop.name}                  — UoP type name (from the realized UoPData)
     *   {integrationContext.name}   — IntegrationContext name
     * </pre>
     *
     * <p>Example pattern:
     * {@code "{uop.name}/{integrationContext.name}/{uop.name}{integrationContext.name}ConnectionTable.h"}
     * → {@code "AOIPublisher/NavDataIC/AOIPublisherNavDataICConnectionTable.h"}
     */
    public static String forUopIntegrationContext(String pattern,
                                                   UoPData uop,
                                                   IntegrationContextData integrationContext) {
        return apply(pattern, Map.of(
                "uop.name",               uop.getName(),
                "integrationContext.name", integrationContext.getName()
        ));
    }

    // -----------------------------------------------------------------------
    // Phase 4 scope resolvers
    // -----------------------------------------------------------------------

    /** Resolves a pattern for the {@code ENUM} scope (Phase 4). */
    public static String forEnum(String pattern,
                                  EnumNode enumNode,
                                  ModuleNode module,
                                  List<String> namespaces) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("enum.name",   enumNode.name());
        tokens.put("module.name", module != null ? module.name() : "");
        tokens.put("namespaces",  String.join("/", namespaces));
        return apply(pattern, tokens);
    }

    /** Resolves a pattern for the {@code TYPEDEF} scope (Phase 4). */
    public static String forTypedef(String pattern,
                                     TypedefNode typedef,
                                     ModuleNode module,
                                     List<String> namespaces) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("typedef.name", typedef.name());
        tokens.put("module.name",  module != null ? module.name() : "");
        tokens.put("namespaces",   String.join("/", namespaces));
        return apply(pattern, tokens);
    }

    /** Resolves a pattern for the {@code UNION} scope (Phase 4). */
    public static String forUnion(String pattern,
                                   UnionNode union,
                                   ModuleNode module,
                                   List<String> namespaces) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("union.name",  union.name());
        tokens.put("module.name", module != null ? module.name() : "");
        tokens.put("namespaces",  String.join("/", namespaces));
        return apply(pattern, tokens);
    }

    /**
     * Resolves a pattern for the {@code FILE} scope (Phase 8).
     *
     * <h2>Available tokens</h2>
     * <pre>
     *   {file.stem}               — filename without extension, e.g. "EntityEvent"
     *   {file.name}               — filename with extension,    e.g. "EntityEvent.idl"
     *   {file.relativePath}       — path from IDL root,         e.g. "TypedTS/EntityEvent.idl"
     *   {file.relativeOutputPath} — .hpp variant of above,      e.g. "TypedTS/EntityEvent.hpp"
     * </pre>
     */
    public static String forFile(String pattern, IdlFileUnit file) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("file.stem",               file.stemName());
        tokens.put("file.name",               file.absolutePath().getFileName().toString());
        tokens.put("file.relativePath",
                   file.relativePath().toString().replace('\\', '/'));
        tokens.put("file.relativeOutputPath",
                   file.relativeOutputPath().toString().replace('\\', '/'));
        return apply(pattern, tokens);
    }

    /**
     * Resolves a pattern for the {@code MODEL_NAMESPACE} scope.
     *
     * <h2>Available tokens</h2>
     * <pre>
     *   {model.namespace}  — simple namespace token, e.g. "CheckoutGateway_Templates"
     * </pre>
     */
    public static String forModelNamespace(String pattern, String modelNamespace) {
        return apply(pattern, Map.of(
                "model.namespace", modelNamespace
        ));
    }

    /** Resolves a pattern for the {@code CONST} scope (Phase 4). */
    public static String forConst(String pattern,
                                   ConstNode constNode,
                                   ModuleNode module,
                                   List<String> namespaces) {
        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("const.name",  constNode.name());
        tokens.put("module.name", module != null ? module.name() : "");
        tokens.put("namespaces",  String.join("/", namespaces));
        return apply(pattern, tokens);
    }

    // -----------------------------------------------------------------------
    // Core substitution
    // -----------------------------------------------------------------------

    /**
     * Replaces all {@code {token}} occurrences in {@code pattern} using
     * {@code tokens}.  Any token not present in the map is left as-is in
     * the output (the surrounding braces are preserved), making it easy to
     * detect unsupported tokens.
     *
     * @param pattern the raw pattern string
     * @param tokens  map of token name → replacement value
     * @return resolved path string
     */
    static String apply(String pattern, Map<String, String> tokens) {
        Matcher m = TOKEN.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String key         = m.group(1);
            String replacement = tokens.get(key);
            if (replacement != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            } else {
                // Leave the token in place — pipeline will warn
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Returns {@code true} if any {@code {token}} placeholders remain in
     * {@code resolved} (indicating the pattern had tokens that were not
     * satisfied for the active scope).
     *
     * @param resolved output of one of the {@code forXxx()} methods
     * @return {@code true} when unresolved tokens remain
     */
    public static boolean hasUnresolvedTokens(String resolved) {
        return TOKEN.matcher(resolved).find();
    }
}

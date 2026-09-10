package com.warhex.er.generator.codegen.manifest;

/**
 * Controls which model elements a {@link GenerationEntry} iterates over.
 *
 * <p>Each scope determines:
 * <ol>
 *   <li>How many times the template is rendered (one per element in the scope).</li>
 *   <li>Which variables are added to the per-render Velocity context.</li>
 *   <li>Which {@link OutputPathResolver} tokens are available.</li>
 * </ol>
 *
 * <h2>Context variables by scope</h2>
 * <pre>
 *   SPEC            — (nothing extra; full base context only)
 *   MODULE          — $module (ModuleNode), $namespaces (List&lt;String&gt;)
 *   STRUCT          — $struct (StructNode), $module, $namespaces
 *   INTERFACE       — $iface (InterfaceNode), $module, $namespaces
 *   TEMPLATE_INST   — $inst (TemplateInstNode), $resolved (InstantiationResult)
 *                     $module, $namespaces
 *   UOP             — $uop (UoPData)          [requires --face-file]
 *   CONNECTION      — $conn (ConnectionData), $uop  [requires --face-file]
 * </pre>
 *
 * <h2>Output path tokens by scope</h2>
 * <pre>
 *   SPEC            — (no tokens)
 *   MODULE          — {module.name}
 *   STRUCT          — {struct.name}, {module.name}, {namespaces}
 *   INTERFACE       — {iface.name},  {module.name}, {namespaces}
 *   TEMPLATE_INST   — {inst.alias},  {inst.templateName}, {module.name}
 *   UOP             — {uop.name}
 *   CONNECTION      — {conn.name},   {uop.name}
 * </pre>
 */
public enum ForEachScope {

    /**
     * Render the template exactly once with the full base context.
     * Useful for top-level files (e.g. a project CMakeLists.txt or package
     * index that lists all types).
     */
    SPEC,

    /**
     * Render once per top-level {@link com.warhex.er.generator.ast.ModuleNode}
     * in the merged spec.
     * Context adds: {@code $module}, {@code $namespaces}.
     */
    MODULE,

    /**
     * Render once per {@link com.warhex.er.generator.ast.StructNode} found
     * anywhere in the merged spec (walks all module nesting).
     * Context adds: {@code $struct}, {@code $module}, {@code $namespaces}.
     */
    STRUCT,

    /**
     * Render once per {@link com.warhex.er.generator.ast.InterfaceNode} found
     * anywhere in the merged spec.
     * Context adds: {@code $iface}, {@code $module}, {@code $namespaces}.
     */
    INTERFACE,

    /**
     * Render once per {@link com.warhex.er.generator.ast.TemplateInstNode}
     * found anywhere in the merged spec, resolved through
     * {@link com.warhex.er.generator.binding.TemplateInstantiator}.
     * Context adds: {@code $inst}, {@code $resolved}
     * ({@link com.warhex.er.generator.binding.TemplateInstantiator.InstantiationResult}),
     * {@code $module}, {@code $namespaces}.
     *
     * <p>If an instantiation cannot be resolved (template not in registry),
     * the entry is skipped with a warning rather than failing the run.
     */
    TEMPLATE_INST,

    /**
     * Render once per {@link com.warhex.er.generator.reader.dto.UoPData}
     * in the loaded {@code .face} model.
     * Requires {@code --face-file}; the pipeline aborts if the model is absent.
     * Context adds: {@code $uop}.
     */
    UOP,

    /**
     * Render once per {@link com.warhex.er.generator.reader.dto.ConnectionData}
     * across all UoPs in the loaded {@code .face} model.
     * Requires {@code --face-file}.
     * Context adds: {@code $conn}, {@code $uop}.
     */
    CONNECTION,

    /**
     * Render once per {@code (UoP type, IntegrationContext)} pair found in the
     * integration model ({@code <im>} subtree) of the loaded {@code .face} file.
     *
     * <p>Requires {@code --face-file} with an integration model section; produces
     * no output (not an error) when the model has no integration contexts.
     *
     * <h2>Context variables</h2>
     * <pre>
     *   $uop                 — {@link com.warhex.er.generator.reader.dto.UoPData}
     *                          The UoP type realized by the IntegrationContext's
     *                          UoPInstance.  Unchanged object; {@code $uop.name}
     *                          gives the class-name prefix.
     *   $integrationContext  — {@link com.warhex.er.generator.reader.dto.IntegrationContextData}
     *                          IC name, transport channel name, and the scoped
     *                          connection list for this (UoP, IC) pair.
     *   $model_namespace     — injected by {@link com.warhex.er.generator.codegen.context.IdlDerivedVariables}
     *                          as before.
     * </pre>
     *
     * <h2>Output path tokens</h2>
     * <pre>
     *   {uop.name}                  — UoP type name
     *   {integrationContext.name}   — IntegrationContext name
     * </pre>
     */
    UOP_INTEGRATION_CONTEXT,


    // -----------------------------------------------------------------------
    // Scopes added by Phase 4 (directive system)
    // -----------------------------------------------------------------------

    /**
     * Render exactly once with the full base context.
     * Equivalent to {@link #SPEC} but used when the template has no
     * {@code ##! for_each} directive at all, making the intent explicit.
     * Output path pattern is used verbatim (no token substitution).
     */
    GLOBAL,

    /**
     * Render once per {@link com.warhex.er.generator.ast.EnumNode} found
     * anywhere in the merged spec.
     * Context adds: {@code $enum}, {@code $module}, {@code $namespaces}.
     * Output path token: {@code {enum.name}}.
     */
    ENUM,

    /**
     * Render once per {@link com.warhex.er.generator.ast.TypedefNode} found
     * anywhere in the merged spec.
     * Context adds: {@code $typedef}, {@code $module}, {@code $namespaces}.
     * Output path token: {@code {typedef.name}}.
     */
    TYPEDEF,

    /**
     * Render once per {@link com.warhex.er.generator.ast.UnionNode} found
     * anywhere in the merged spec.
     * Context adds: {@code $union}, {@code $module}, {@code $namespaces}.
     * Output path token: {@code {union.name}}.
     */
    UNION,

    /**
     * Render once per {@link com.warhex.er.generator.ast.ConstNode} found
     * anywhere in the merged spec.
     * Context adds: {@code $const}, {@code $module}, {@code $namespaces}.
     * Output path token: {@code {const.name}}.
     */
    CONST,

    /**
     * Render once per IDL source file found under {@code --idl-dir}.
     *
     * <p>Context adds: {@code $file}
     * ({@link com.warhex.er.generator.parser.IdlFileUnit}), which exposes:
     * <ul>
     *   <li>{@code $file.stemName}             — e.g. {@code "EntityEvent"}</li>
     *   <li>{@code $file.relativePath}         — e.g. {@code TypedTS/EntityEvent.idl}</li>
     *   <li>{@code $file.relativeOutputPath}   — e.g. {@code TypedTS/EntityEvent.hpp}</li>
     *   <li>{@code $file.absolutePath}         — full OS path</li>
     *   <li>{@code $file.definitions}          — top-level IDL definitions in this file only
     *                                            (excluding transitively included framework headers)</li>
     * </ul>
     *
     * <p>Output path tokens: {@code {file.stem}}, {@code {file.name}},
     * {@code {file.relativePath}}, {@code {file.relativeOutputPath}}.
     *
     * <p>Filter is matched against the forward-slash-normalised relative path
     * (e.g. {@code "TypedTS/EntityEvent.idl"}), so a filter of
     * {@code "TypedTS/.*"} matches all TypedTS files.
     *
     * <p>Each definition in {@code $file.definitions} has its
     * {@link com.warhex.er.generator.ast.IdlDefinition#sourceFile()} set to
     * the file's absolute path.
     */
    FILE,

    /**
     * Render once per unique model namespace found in
     * {@link com.warhex.er.generator.reader.dto.UoPModelData#getPlatformTypes()}.
     *
     * <p>Requires {@code --face-file}; the pipeline aborts if the model is absent.
     * The namespace is the last dot-separated segment of
     * {@link com.warhex.er.generator.reader.dto.TssTypeData#getIdlModule()},
     * e.g. {@code "CheckoutGateway_Templates"} from
     * {@code "FACE.DM.CheckoutGateway_Templates"}.
     *
     * <h2>Context variables</h2>
     * <pre>
     *   $modelNamespace  — String, the simple namespace token
     *   $namespaceTypes  — List&lt;TssTypeData&gt; types in this namespace
     * </pre>
     *
     * <h2>Output path tokens</h2>
     * <pre>
     *   {model.namespace}  — same as $modelNamespace
     * </pre>
     */
    MODEL_NAMESPACE
}

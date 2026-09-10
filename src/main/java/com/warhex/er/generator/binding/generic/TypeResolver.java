package com.warhex.er.generator.binding.generic;

import com.warhex.er.generator.ast.*;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Data-driven type resolver placed in the Velocity context as {@code $types}.
 *
 * <p>Implements three-tier resolution driven entirely by the {@link LanguageDescriptor}:
 * <ol>
 *   <li>{@link IdlType.Primitive} → look up {@link LanguageDescriptor#primitive_types}.</li>
 *   <li>{@link IdlType.Sequence}, {@link IdlType.Array}, {@link IdlType.Str},
 *       {@link IdlType.WideStr} → apply {@link LanguageDescriptor#parameterized_types} pattern.</li>
 *   <li>{@link IdlType.Scoped} → check {@link LanguageDescriptor#scoped_overrides};
 *       if absent, follow the typedef chain (see §2.2.1); if still unresolved, return
 *       the last {@code ::} segment as the simple type name.</li>
 * </ol>
 *
 * <p>No language-specific logic lives in this class.  All behaviour is data-driven
 * from the descriptor.
 *
 * <h2>Typedef chain resolution (§2.2.1)</h2>
 * When resolving a scoped type not in {@code scoped_overrides}, the resolver walks
 * the typedef map supplied at construction time.  This map is extracted from
 * {@link com.warhex.er.generator.binding.TemplateInstantiator#typedefMap()} which
 * already performs the walk over the merged spec.  Walking to a non-Scoped underlying
 * type terminates the chain and delegates back to {@link #type(IdlType)}.
 */
public class TypeResolver {

    private final LanguageDescriptor descriptor;
    private final Map<String, IdlType> typedefMap;
    private final Set<String> reservedWordSet;

    /**
     * @param descriptor  the language descriptor (never {@code null})
     * @param typedefMap  typedef alias map from {@link
     *                    com.warhex.er.generator.binding.TemplateInstantiator#typedefMap()};
     *                    may be {@code null} (treated as empty)
     */
    public TypeResolver(LanguageDescriptor descriptor, Map<String, IdlType> typedefMap) {
        this.descriptor    = descriptor;
        this.typedefMap    = typedefMap != null ? typedefMap : Map.of();
        this.reservedWordSet = descriptor.reserved_words != null
                ? new HashSet<>(descriptor.reserved_words)
                : Set.of();
    }

    // =========================================================================
    // Primary type resolution
    // =========================================================================

    /**
     * Returns the language type string for the given IDL type.
     *
     * @param t IDL type (never {@code null})
     * @return language type string (never {@code null})
     */
    public String type(IdlType t) {
        if (t instanceof IdlType.Primitive p) {
            return resolvePrimitive(p.kind());
        }
        if (t instanceof IdlType.Void) {
            // Void may appear in primitive_types under the VOID key
            String v = descriptor.primitive_types != null
                    ? descriptor.primitive_types.get("VOID") : null;
            return v != null ? v : "void";
        }
        if (t instanceof IdlType.Str) {
            return resolveParameterized("string", t);
        }
        if (t instanceof IdlType.WideStr) {
            return resolveParameterized("wstring", t);
        }
        if (t instanceof IdlType.Sequence seq) {
            return resolveParameterized("sequence", seq);
        }
        if (t instanceof IdlType.Array arr) {
            return resolveParameterized("array", arr);
        }
        if (t instanceof IdlType.Scoped s) {
            return resolveScoped(s.qualifiedName());
        }
        return "/* unknown */";
    }

    // =========================================================================
    // Tier 1 — primitives
    // =========================================================================

    private String resolvePrimitive(PrimitiveKind kind) {
        if (descriptor.primitive_types == null) return "/* unknown */";
        String result = descriptor.primitive_types.get(kind.name());
        return result != null ? result : "/* unknown */";
    }

    // =========================================================================
    // Tier 2 — parameterised types
    // =========================================================================

    private String resolveParameterized(String typeName, IdlType t) {
        if (descriptor.parameterized_types == null) return typeName;
        String pattern = descriptor.parameterized_types.get(typeName);
        if (pattern == null) return typeName;

        if (t instanceof IdlType.Sequence seq) {
            String elem      = type(seq.elementType());
            String elemBoxed = boxedType(seq.elementType());
            pattern = pattern
                    .replace("{element:boxed}", elemBoxed)
                    .replace("{element}", elem);
            if (seq.bound().isPresent()) {
                pattern = pattern.replace("{bound}", String.valueOf(seq.bound().getAsInt()));
            }
        } else if (t instanceof IdlType.Array arr) {
            String elem      = type(arr.elementType());
            String elemBoxed = boxedType(arr.elementType());
            pattern = pattern
                    .replace("{element:boxed}", elemBoxed)
                    .replace("{element}", elem);
        }
        // string / wstring have no tokens
        return pattern;
    }

    // =========================================================================
    // Tier 3 — scoped names (overrides → typedef chain → lastSegment)
    // =========================================================================

    private String resolveScoped(String qualifiedName) {
        // Step 1: direct scoped_overrides check
        if (descriptor.scoped_overrides != null) {
            String mapped = descriptor.scoped_overrides.get(qualifiedName);
            if (mapped != null) return mapped;
        }

        // Step 2: walk typedef chain
        Set<String> visited = new HashSet<>();
        String current = qualifiedName;
        while (!visited.contains(current)) {
            visited.add(current);
            IdlType underlying = typedefMap.get(current);
            if (underlying == null) break;

            if (underlying instanceof IdlType.Scoped s) {
                // Check scoped_overrides on the resolved scoped name
                if (descriptor.scoped_overrides != null) {
                    String mapped = descriptor.scoped_overrides.get(s.qualifiedName());
                    if (mapped != null) return mapped;
                }
                current = s.qualifiedName();
            } else {
                // Non-scoped underlying type — resolve recursively
                return type(underlying);
            }
        }

        // Step 3: last-segment fallback
        return lastSegment(current);
    }

    // =========================================================================
    // Additional helper methods (callable from Velocity templates / macros)
    // =========================================================================

    /**
     * Returns the boxed/wrapper type for {@code t}.
     * For a primitive language type that appears in {@link LanguageDescriptor#boxed_types},
     * returns the boxed name; otherwise returns the same as {@link #type(IdlType)}.
     *
     * @param t IDL type
     * @return boxed type string
     */
    public String boxedType(IdlType t) {
        String raw = type(t);
        if (descriptor.boxed_types != null) {
            String boxed = descriptor.boxed_types.get(raw);
            if (boxed != null) return boxed;
        }
        return raw;
    }

    /**
     * Returns {@code true} when the language type for {@code t} appears in
     * {@link LanguageDescriptor#immutable_types}.
     *
     * @param t IDL type
     * @return {@code true} when Holder wrapping (or equivalent) is required
     */
    public boolean isImmutable(IdlType t) {
        if (descriptor.immutable_types == null) return false;
        return descriptor.immutable_types.contains(type(t));
    }

    /**
     * Returns {@code true} when {@code t} is an IDL primitive type.
     *
     * @param t IDL type
     * @return {@code true} for {@link IdlType.Primitive}
     */
    public boolean isPrimitive(IdlType t) {
        return t instanceof IdlType.Primitive;
    }

    /**
     * Returns {@code true} when {@code t} is an IDL {@code sequence<T>}.
     *
     * @param t IDL type
     * @return {@code true} for {@link IdlType.Sequence}
     */
    public boolean isSequence(IdlType t) {
        return t instanceof IdlType.Sequence;
    }

    /**
     * Returns the IDL identifier prefixed with {@link LanguageDescriptor#reserved_prefix}
     * when it conflicts with a reserved word; otherwise returns {@code name} unchanged.
     *
     * @param name IDL identifier
     * @return safe language identifier
     */
    public String safeName(String name) {
        if (reservedWordSet.contains(name)) {
            String prefix = descriptor.reserved_prefix != null ? descriptor.reserved_prefix : "_";
            return prefix + name;
        }
        return name;
    }

    /**
     * Converts an IDL {@code snake_case} (or already mixed-case) identifier to
     * {@code PascalCase} by splitting on {@code '_'} and capitalising the first
     * character of each segment.
     *
     * <p>Examples: {@code "threat_id"} → {@code "ThreatId"},
     * {@code "Receive"} → {@code "Receive"}.
     *
     * @param name IDL identifier
     * @return PascalCase name
     */
    public String toPascalCase(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        boolean cap = true;
        for (char c : name.toCharArray()) {
            if (c == '_') { cap = true; }
            else          { sb.append(cap ? Character.toUpperCase(c) : c); cap = false; }
        }
        return sb.toString();
    }

    /**
     * Converts an IDL {@code snake_case} identifier to {@code camelCase}.
     * Delegates to {@link #toPascalCase(String)} then lowercases the first character.
     *
     * <p>Examples: {@code "threat_id"} → {@code "threatId"},
     * {@code "timeout"} → {@code "timeout"}.
     *
     * @param name IDL identifier
     * @return camelCase name
     */
    public String toCamelCase(String name) {
        String pascal = toPascalCase(name);
        if (pascal == null || pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    // =========================================================================
    // C++ specific helpers (used by C++ templates; harmless to other languages)
    // =========================================================================

    /**
     * Builds a C++ header-guard token from namespace segments and type name.
     *
     * <p>Example: {@code guard(["FACE","DM","SampleModel"], "TrackEntity")}
     * → {@code "FACE_DM_SAMPLEMODEL_TRACKENTITY"}.
     * Templates append {@code "_HPP"} to produce the full guard token.
     *
     * @param namespaces module path segments (may be empty)
     * @param typeName   simple construct name
     * @return guard prefix token (all uppercase, segments joined by {@code _})
     */
    public String guard(List<String> namespaces, String typeName) {
        String prefix = namespaces.stream()
                .map(String::toUpperCase)
                .collect(Collectors.joining("_"));
        return prefix.isEmpty()
                ? typeName.toUpperCase()
                : prefix + "_" + typeName.toUpperCase();
    }

    /**
     * Returns a complete C++ member declaration.
     *
     * <p>Handles arrays specially: {@code "FACE::Long data[8][4]"}; all other
     * types produce {@code "T name"}.
     *
     * @param t    IDL type
     * @param name member identifier
     * @return C++ member declaration string
     */
    public String typeDecl(IdlType t, String name) {
        if (t instanceof IdlType.Array arr) {
            StringBuilder sb = new StringBuilder(type(arr.elementType()))
                    .append(' ')
                    .append(name);
            for (int dim : arr.dimensions()) {
                sb.append('[').append(dim).append(']');
            }
            return sb.toString();
        }
        return type(t) + " " + name;
    }

    /**
     * Returns the C++ default initialiser expression for {@code t}.
     *
     * <p>Follows FACE TS 3.2 §4.14.8.7.1:
     * {@code float} → {@code "0.0f"},
     * {@code double} → {@code "0.0"},
     * {@code long double} → {@code "0.0L"},
     * {@code bool} → {@code "false"},
     * {@code char/wchar_t} → {@code "'\\0'"},
     * all other primitives → {@code "0"},
     * structs/sequences/strings/arrays → {@code "{}"}.
     *
     * @param t IDL type
     * @return default initialiser string
     */
    public String defaultInit(IdlType t) {
        if (t instanceof IdlType.Primitive p) {
            return switch (p.kind()) {
                case FLOAT       -> "0.0f";
                case DOUBLE      -> "0.0";
                case LONG_DOUBLE -> "0.0L";
                case BOOLEAN     -> "false";
                case CHAR, WIDE_CHAR -> "'\\0'";
                default          -> "0";
            };
        }
        if (t instanceof IdlType.Void) {
            return "";
        }
        return "{}";
    }

    /**
     * Returns a C++ parameter declaration applying FACE TS 3.2 §4.14.8.10.2 rules.
     *
     * <ul>
     *   <li>{@code in} + primitive/void → by value: {@code "T name"}</li>
     *   <li>{@code in} + other          → const ref: {@code "const T& name"}</li>
     *   <li>{@code out} / {@code inout} → mutable ref: {@code "T& name"}</li>
     * </ul>
     *
     * @param param parameter node
     * @return C++ parameter declaration string
     */
    public String paramDecl(ParameterNode param) {
        IdlType t    = param.type();
        String  name = param.name();
        return switch (param.direction()) {
            case IN -> {
                if (t instanceof IdlType.Primitive || t instanceof IdlType.Void) {
                    yield type(t) + " " + name;
                }
                yield "const " + type(t) + "& " + name;
            }
            case OUT, INOUT -> type(t) + "& " + name;
        };
    }

    /**
     * Full parameter declaration for template-instantiated bodies.
     *
     * <p>Applies interface-type pointer semantics when the parameter type is a
     * scoped name that refers to an interface:
     * <ul>
     *   <li>{@code in  local_iface}   → {@code "const T* name"}</li>
     *   <li>{@code out/inout local_iface} → {@code "T** name"}</li>
     *   <li>{@code out/inout iface_actual} → {@code "T*& name"}</li>
     * </ul>
     * All other parameters fall through to {@link #paramDecl(ParameterNode)}.
     *
     * @param param                parameter node (type already substituted)
     * @param localInterfaces      simple names of interfaces in the template body
     * @param interfaceKindActuals qualified names bound from {@code interface}-kind formals
     * @return C++ parameter declaration string
     */
    public String paramDeclFull(ParameterNode param,
                                Set<String>   localInterfaces,
                                Set<String>   interfaceKindActuals) {
        if (param.type() instanceof IdlType.Scoped s) {
            String qn   = s.qualifiedName();
            String bare = qn.startsWith("::") ? qn.substring(2) : qn;
            String simple = lastSegment(qn);
            boolean isIfaceType = localInterfaces.contains(simple)
                    || interfaceKindActuals.contains(qn)
                    || interfaceKindActuals.contains(bare);
            if (isIfaceType) {
                boolean isLocal = localInterfaces.contains(simple);
                return switch (param.direction()) {
                    case IN         -> "const " + type(param.type()) + "* " + param.name();
                    case OUT, INOUT -> isLocal
                            ? type(param.type()) + "** " + param.name()
                            : type(param.type()) + "*& "  + param.name();
                };
            }
        }
        return paramDecl(param);
    }

    /**
     * Converts a fully-qualified IDL name to a relative include path using the
     * {@link LanguageDescriptor.IncludeComputation#include_path_pattern}.
     *
     * <p>Example (C++): {@code "::FACE::DM::SampleModel::Foo"} →
     * {@code "FACE/DM/SampleModel/Foo.hpp"}.
     *
     * @param qualifiedName fully-qualified IDL name (with or without leading {@code ::})
     * @return include path string
     */
    public String includePathFor(String qualifiedName) {
        if (descriptor.include_computation != null
                && descriptor.include_computation.include_path_pattern != null) {
            return applyIncludePattern(
                    descriptor.include_computation.include_path_pattern, qualifiedName);
        }
        // Default fallback (mirrors CppTypeHelper behaviour)
        String stripped = qualifiedName.startsWith("::") ? qualifiedName.substring(2) : qualifiedName;
        return stripped.replace("::", "/") + ".hpp";
    }

    /**
     * Returns the last {@code ::}-separated segment of a qualified IDL name.
     * Strips the leading {@code ::} before splitting.
     *
     * <p>Example: {@code "::FACE::DM::GeoPosition"} → {@code "GeoPosition"}.
     *
     * @param qualifiedName qualified IDL name
     * @return simple (unqualified) name
     */
    public String lastSegment(String qualifiedName) {
        String n = qualifiedName.startsWith("::") ? qualifiedName.substring(2) : qualifiedName;
        int lastColon = n.lastIndexOf(':');
        return lastColon >= 0 ? n.substring(lastColon + 1) : n;
    }

    // =========================================================================
    // Pattern application helpers
    // =========================================================================

    /**
     * Applies an include-path pattern to a qualified name.
     *
     * <p>Pattern tokens take the form {@code {variable:transform1:transform2…}}.
     * Currently supported variable: {@code qualifiedName}.
     * Currently supported transforms:
     * <ul>
     *   <li>{@code strip-leading-colons} — removes a leading {@code ::}</li>
     *   <li>{@code replace(from,to)} — replaces all occurrences of {@code from} with {@code to}</li>
     *   <li>{@code lower} — lowercases the value</li>
     * </ul>
     */
    private String applyIncludePattern(String pattern, String qualifiedName) {
        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < pattern.length()) {
            if (pattern.charAt(i) == '{') {
                int end = pattern.indexOf('}', i);
                if (end < 0) { result.append(pattern.charAt(i++)); continue; }
                String spec = pattern.substring(i + 1, end);
                String[] parts = spec.split(":");
                String value = "qualifiedName".equals(parts[0]) ? qualifiedName : "";
                for (int j = 1; j < parts.length; j++) {
                    value = applyTransform(value, parts[j]);
                }
                result.append(value);
                i = end + 1;
            } else {
                result.append(pattern.charAt(i++));
            }
        }
        return result.toString();
    }

    private String applyTransform(String value, String transform) {
        if ("strip-leading-colons".equals(transform)) {
            return value.startsWith("::") ? value.substring(2) : value;
        }
        if (transform.startsWith("replace(") && transform.endsWith(")")) {
            String args  = transform.substring("replace(".length(), transform.length() - 1);
            int    comma = args.indexOf(',');
            if (comma >= 0) {
                return value.replace(args.substring(0, comma), args.substring(comma + 1));
            }
        }
        if ("lower".equals(transform)) {
            return value.toLowerCase();
        }
        return value;
    }

    /**
     * Expands an iteration-path pattern for a per-construct output path.
     *
     * <p>Supported tokens:
     * <ul>
     *   <li>{@code {modules}}       → module stack joined by {@code /}</li>
     *   <li>{@code {modules:lower}} → each segment lowercased, joined by {@code /}</li>
     *   <li>{@code {name}}          → construct simple name (as-is)</li>
     *   <li>{@code {name:lower}}    → construct name lowercased</li>
     * </ul>
     *
     * @param pattern      path pattern from the descriptor
     * @param moduleStack  enclosing module segments
     * @param constructName simple construct name
     * @return resolved relative output path
     */
    public static String expandPathPattern(String pattern,
                                           java.util.List<String> moduleStack,
                                           String constructName) {
        String modules      = String.join("/", moduleStack);
        String modulesLower = String.join("/",
                moduleStack.stream().map(String::toLowerCase).toList());

        // When the module stack is empty, the literal pattern "{modules}/{name}"
        // would expand to "/{name}", producing an absolute path that resolves to
        // the filesystem root on Windows (AccessDeniedException) or / on Unix.
        // Collapse "{modules}/" and "{modules:lower}/" to "" when the stack is
        // empty so the result is just "{name}" (relative to the output root).
        String result = pattern;
        if (modules.isEmpty()) {
            result = result.replace("{modules:lower}/", "")
                           .replace("{modules}/", "");
        }
        return result
                .replace("{modules:lower}", modulesLower)
                .replace("{modules}",       modules)
                .replace("{name:lower}",    constructName.toLowerCase())
                .replace("{name}",          constructName);
    }
}

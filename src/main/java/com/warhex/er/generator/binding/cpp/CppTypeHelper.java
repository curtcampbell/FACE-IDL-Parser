package com.warhex.er.generator.binding.cpp;

import com.warhex.er.generator.ast.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Type-conversion helper for the C++ language mapper.
 *
 * <p>An instance of this class is placed in the Velocity context as {@code $cpp}
 * so templates can call methods like $cpp.type(), $cpp.defaultInit(), and
 * $cpp.paramDecl().
 *
 * <h2>Type mapping source</h2>
 * Primitive types follow FACE TS 3.2 section 4.14.8.7.1 Table 14.
 * Parameter direction rules follow section 4.14.8.10.2.
 */
public class CppTypeHelper {

    // -----------------------------------------------------------------------
    // Table 14 - IDL primitive to FACE C++ typedef
    // -----------------------------------------------------------------------

    private static final Map<PrimitiveKind, String> TYPE_TABLE =
            new EnumMap<>(PrimitiveKind.class);

    static {
        TYPE_TABLE.put(PrimitiveKind.SHORT,              "FACE::Short");
        TYPE_TABLE.put(PrimitiveKind.LONG,               "FACE::Long");
        TYPE_TABLE.put(PrimitiveKind.LONG_LONG,          "FACE::LongLong");
        TYPE_TABLE.put(PrimitiveKind.UNSIGNED_SHORT,     "FACE::UnsignedShort");
        TYPE_TABLE.put(PrimitiveKind.UNSIGNED_LONG,      "FACE::UnsignedLong");
        TYPE_TABLE.put(PrimitiveKind.UNSIGNED_LONG_LONG, "FACE::UnsignedLongLong");
        TYPE_TABLE.put(PrimitiveKind.FLOAT,              "FACE::Float");
        TYPE_TABLE.put(PrimitiveKind.DOUBLE,             "FACE::Double");
        TYPE_TABLE.put(PrimitiveKind.LONG_DOUBLE,        "FACE::LongDouble");
        TYPE_TABLE.put(PrimitiveKind.BOOLEAN,            "FACE::Boolean");
        TYPE_TABLE.put(PrimitiveKind.CHAR,               "FACE::Char");
        TYPE_TABLE.put(PrimitiveKind.WIDE_CHAR,          "FACE::WChar");
        TYPE_TABLE.put(PrimitiveKind.OCTET,              "FACE::Octet");
        TYPE_TABLE.put(PrimitiveKind.ANY,                "FACE::Any");
        TYPE_TABLE.put(PrimitiveKind.VOID,               "void");
        TYPE_TABLE.put(PrimitiveKind.INT8,               "int8_t");
        TYPE_TABLE.put(PrimitiveKind.UINT8,              "uint8_t");
        TYPE_TABLE.put(PrimitiveKind.INT16,              "FACE::Short");
        TYPE_TABLE.put(PrimitiveKind.UINT16,             "FACE::UnsignedShort");
        TYPE_TABLE.put(PrimitiveKind.INT32,              "FACE::Long");
        TYPE_TABLE.put(PrimitiveKind.UINT32,             "FACE::UnsignedLong");
        TYPE_TABLE.put(PrimitiveKind.INT64,              "FACE::LongLong");
        TYPE_TABLE.put(PrimitiveKind.UINT64,             "FACE::UnsignedLongLong");
    }

    // -----------------------------------------------------------------------
    // Public API (called from Velocity templates)
    // -----------------------------------------------------------------------

    /** Returns the C++ type string for t. */
    public String type(IdlType t) {
        if (t instanceof IdlType.Primitive p) {
            return TYPE_TABLE.getOrDefault(p.kind(), "/* unknown */");
        }
        if (t instanceof IdlType.Void) {
            return "void";
        }
        if (t instanceof IdlType.Scoped s) {
            return stripLeadingColons(s.qualifiedName());
        }
        if (t instanceof IdlType.Sequence seq) {
            return "std::vector<" + type(seq.elementType()) + ">";
        }
        if (t instanceof IdlType.Str) {
            return "FACE::STRING_TYPE";
        }
        if (t instanceof IdlType.WideStr) {
            return "FACE::WSTRING_TYPE";
        }
        if (t instanceof IdlType.Array arr) {
            return type(arr.elementType());
        }
        return "/* unknown */";
    }

    /** Returns a complete member declaration: "FACE::Float speed" or "FACE::Long data[8][4]". */
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

    /** Returns the default initializer for t (e.g. "0", "0.0f", "{}"). */
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

    /** Returns the C++ return type string for an operation. */
    public String retType(IdlType t) {
        return type(t);
    }

    /**
     * Returns a C++ parameter declaration applying FACE TS section 4.14.8.10.2 rules:
     * in + primitive -> by value; in + other -> const T&; out/inout -> T&.
     */
    public String paramDecl(ParameterNode param) {
        IdlType t = param.type();
        String name = param.name();
        return switch (param.direction()) {
            case IN -> {
                if (t instanceof IdlType.Primitive || t instanceof IdlType.Void) {
                    yield type(t) + " " + name;
                }
                yield "const " + type(t) + "& " + name;
            }
            case OUT   -> type(t) + "& " + name;
            case INOUT -> type(t) + "& " + name;
        };
    }

    /**
     * Full parameter declaration for template-instantiated bodies.
     *
     * <p>Applies correct C++ mappings for interface-typed parameters per
     * OMG IDL-to-C++ Language Mapping §5.16.3.3 / FACE TS 3.2 §4.14.8.10.2:
     * <ul>
     *   <li>{@code in interface_type}    → {@code const T* name}</li>
     *   <li>{@code out interface_type}   → {@code T*& name}</li>
     *   <li>{@code inout interface_type} → {@code T*& name}</li>
     * </ul>
     *
     * An interface-typed parameter is detected when the (post-substitution) type
     * is a scoped name whose simple identifier appears in {@code localInterfaces}
     * (interfaces defined inside the template body, e.g. {@code Read_Callback})
     * or whose qualified name appears in {@code interfaceKindActuals} (types that
     * were bound from {@code interface}-kind formal parameters).
     *
     * <p>All other parameters fall through to {@link #paramDecl(ParameterNode)}.
     *
     * @param param                parameter node (type already substituted)
     * @param localInterfaces      simple names of interfaces defined in the
     *                             template body
     * @param interfaceKindActuals qualified names of types bound from
     *                             {@code interface}-kind formal parameters
     */
    public String paramDeclFull(ParameterNode param,
                                Set<String> localInterfaces,
                                Set<String> interfaceKindActuals) {
        if (param.type() instanceof IdlType.Scoped s) {
            String simple = lastSegment(s.qualifiedName());
            String qn     = s.qualifiedName();
            String bare   = qn.startsWith("::") ? qn.substring(2) : qn;

            boolean isIfaceType = localInterfaces.contains(simple)
                    || interfaceKindActuals.contains(qn)
                    || interfaceKindActuals.contains(bare);

            if (isIfaceType) {
                // Local-interface inout/out → double-pointer (I**) per TemplateInstantiator doc.
                // Interface-kind-actual inout/out → pointer-reference (T*&, Injectable idiom).
                boolean isLocal = localInterfaces.contains(simple);
                return switch (param.direction()) {
                    case IN    -> "const " + type(param.type()) + "* " + param.name();
                    case OUT, INOUT -> isLocal
                            ? type(param.type()) + "** "  + param.name()
                            : type(param.type()) + "*& " + param.name();
                };
            }
        }
        return paramDecl(param);
    }

    /**
     * Converts a fully-qualified IDL name to a relative include path.
     * Example: "::FACE::DM::SampleModel::Foo" -> "FACE/DM/SampleModel/Foo.hpp"
     */
    public String includePathFor(String qualifiedName) {
        String stripped = qualifiedName.startsWith("::") ? qualifiedName.substring(2) : qualifiedName;
        return stripped.replace("::", "/") + ".hpp";
    }

    /**
     * Builds a C++ header-guard token from namespace segments and type name.
     * Example: guard(["FACE","DM","SampleModel"], "TrackEntity") -> "FACE_DM_SAMPLEMODEL_TRACKENTITY"
     * Templates append "_HPP" to produce the full guard token.
     */
    public String guard(List<String> namespaces, String typeName) {
        String prefix = namespaces.stream()
                .map(String::toUpperCase)
                .collect(Collectors.joining("_"));
        return prefix.isEmpty()
                ? typeName.toUpperCase()
                : prefix + "_" + typeName.toUpperCase();
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Returns the last "::" segment of a qualified name. */
    private String lastSegment(String qualifiedName) {
        String n = qualifiedName.startsWith("::") ? qualifiedName.substring(2) : qualifiedName;
        int lastColon = n.lastIndexOf(':');
        return lastColon >= 0 ? n.substring(lastColon + 1) : n;
    }

    /** Strips leading "::" so "::FACE::GUID_TYPE" becomes "FACE::GUID_TYPE". */
    private String stripLeadingColons(String name) {
        return name.startsWith("::") ? name.substring(2) : name;
    }
}

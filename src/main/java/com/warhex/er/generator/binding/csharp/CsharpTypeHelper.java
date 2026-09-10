package com.warhex.er.generator.binding.csharp;

import com.warhex.er.generator.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Type-conversion and naming helper for the C# language mapper.
 *
 * <p>An instance is placed in the Velocity context as {@code $cs} so templates
 * can call methods like {@code $cs.type($member.type)},
 * {@code $cs.toPascalCase($member.name)}, {@code $cs.csharpReturnType($op)}, etc.
 *
 * <h2>Type mapping conventions</h2>
 * <ul>
 *   <li>IDL unsigned integer types map to C# unsigned types ({@code ushort}, {@code uint},
 *       {@code ulong}) — a clean advantage over the Java mapping.</li>
 *   <li>IDL {@code long double} → {@code decimal}.</li>
 *   <li>IDL {@code string} / {@code wstring} → {@code string}.</li>
 *   <li>IDL {@code sequence<T>} → {@code IList<T>}.</li>
 *   <li>IDL {@code array T[N]} → {@code T[]}.</li>
 * </ul>
 *
 * <h2>Method signature transformation</h2>
 * <p>IDL parameter directions are transformed to idiomatic C# signatures:
 * <ul>
 *   <li>{@code in T} → regular C# parameter {@code T camelName}</li>
 *   <li>{@code inout T} → appears as both a regular input parameter <em>and</em>
 *       a named element in the return tuple</li>
 *   <li>{@code out T} → removed from the parameter list; included in the return</li>
 *   <li>Single output → the type is returned directly (no tuple wrapper)</li>
 *   <li>Multiple outputs → named C# tuple {@code (T1 Name1, T2 Name2, ...)}</li>
 * </ul>
 *
 * <h2>Naming conventions</h2>
 * <ul>
 *   <li>IDL {@code snake_case} names → {@code PascalCase} for properties, types,
 *       tuple element names, and method names</li>
 *   <li>IDL {@code snake_case} names → {@code camelCase} for method parameters</li>
 *   <li>C# interfaces are prefixed with {@code I} (e.g., {@code IGeoPosition})</li>
 * </ul>
 */
public class CsharpTypeHelper {

    // -----------------------------------------------------------------------
    // Known FACE namespace scoped types → C# type string
    // -----------------------------------------------------------------------

    /** FACE scoped types that map to C# {@code ulong}. */
    private static final Set<String> FACE_ULONG_TYPES = Set.of(
            "::FACE::GUID_TYPE",            "FACE::GUID_TYPE",
            "::FACE::MESSAGE_INSTANCE_GUID_TYPE", "FACE::MESSAGE_INSTANCE_GUID_TYPE",
            "::FACE::UnsignedLongLong",     "FACE::UnsignedLongLong"
    );

    /** FACE scoped types that map to C# {@code long}. */
    private static final Set<String> FACE_LONG_TYPES = Set.of(
            "::FACE::DURATION_TIME_TYPE",   "FACE::DURATION_TIME_TYPE",
            "::FACE::ABSOLUTE_TIME_TYPE",   "FACE::ABSOLUTE_TIME_TYPE",
            "::FACE::SYSTEM_TIME_TYPE",     "FACE::SYSTEM_TIME_TYPE",
            "::FACE::TIMEOUT_TYPE",         "FACE::TIMEOUT_TYPE",
            "::FACE::TRANSACTION_ID_TYPE",  "FACE::TRANSACTION_ID_TYPE",
            "::FACE::SEQUENCE_NUMBER_TYPE", "FACE::SEQUENCE_NUMBER_TYPE",
            "::FACE::LongLong",             "FACE::LongLong",
            "::FACE::UnsignedLong",         "FACE::UnsignedLong"
    );

    /** FACE scoped types that map to C# {@code int}. */
    private static final Set<String> FACE_INT_TYPES = Set.of(
            "::FACE::Long",  "FACE::Long"
    );

    /** FACE scoped types that map to C# {@code short}. */
    private static final Set<String> FACE_SHORT_TYPES = Set.of(
            "::FACE::Short", "FACE::Short"
    );

    /** FACE scoped types that map to C# {@code ushort}. */
    private static final Set<String> FACE_USHORT_TYPES = Set.of(
            "::FACE::UnsignedShort", "FACE::UnsignedShort"
    );

    /** FACE scoped types that map to C# {@code float}. */
    private static final Set<String> FACE_FLOAT_TYPES = Set.of(
            "::FACE::Float", "FACE::Float"
    );

    /** FACE scoped types that map to C# {@code double}. */
    private static final Set<String> FACE_DOUBLE_TYPES = Set.of(
            "::FACE::Double", "FACE::Double"
    );

    /** FACE scoped types that map to C# {@code decimal}. */
    private static final Set<String> FACE_DECIMAL_TYPES = Set.of(
            "::FACE::LongDouble", "FACE::LongDouble"
    );

    /** FACE scoped types that map to C# {@code bool}. */
    private static final Set<String> FACE_BOOL_TYPES = Set.of(
            "::FACE::Boolean", "FACE::Boolean"
    );

    /** FACE scoped types that map to C# {@code byte}. */
    private static final Set<String> FACE_BYTE_TYPES = Set.of(
            "::FACE::Octet", "FACE::Octet"
    );

    /** FACE scoped types that map to C# {@code char}. */
    private static final Set<String> FACE_CHAR_TYPES = Set.of(
            "::FACE::Char",  "FACE::Char",
            "::FACE::WChar", "FACE::WChar"
    );

    /** FACE scoped types that map to C# {@code string}. */
    private static final Set<String> FACE_STRING_TYPES = Set.of(
            "::FACE::STRING_TYPE",           "FACE::STRING_TYPE",
            "::FACE::WSTRING_TYPE",          "FACE::WSTRING_TYPE",
            "::FACE::UNBOUNDED_STRING_TYPE", "FACE::UNBOUNDED_STRING_TYPE"
    );

    /**
     * C# value types (plus immutable {@code string}) that can be assigned
     * directly in the deep copy constructor without a copy-constructor call.
     */
    private static final Set<String> CS_VALUE_TYPES = Set.of(
            "short", "ushort", "int", "uint", "long", "ulong",
            "float", "double", "decimal", "char", "byte", "bool",
            "string"   // immutable reference type — safe to assign directly
    );

    // -----------------------------------------------------------------------
    // Public API — called from Velocity templates
    // -----------------------------------------------------------------------

    /**
     * Returns the C# type string for an IDL type.
     *
     * @param t IDL type from the AST
     * @return C# type string (never null)
     */
    public String type(IdlType t) {
        if (t instanceof IdlType.Primitive p) return primitiveType(p.kind());
        if (t instanceof IdlType.Void)         return "void";
        if (t instanceof IdlType.Str)          return "string";
        if (t instanceof IdlType.WideStr)      return "string";
        if (t instanceof IdlType.Scoped s)     return scopedType(s.qualifiedName());
        if (t instanceof IdlType.Sequence seq) return "IList<" + type(seq.elementType()) + ">";
        if (t instanceof IdlType.Array arr)    return type(arr.elementType()) + "[]";
        return "object";
    }

    /**
     * Returns the C# return type for a complete IDL operation, applying the
     * direction-based transformation:
     * <ul>
     *   <li>No outputs → {@code void} (or the IDL non-void return type)</li>
     *   <li>One output → returned directly</li>
     *   <li>Multiple outputs → named C# tuple</li>
     * </ul>
     *
     * @param op IDL operation node
     * @return C# return type string
     */
    public String csharpReturnType(OperationNode op) {
        List<ParameterNode> outputs = outputParams(op);
        boolean hasIdlReturn = !(op.returnType() instanceof IdlType.Void);
        int totalOutputs = outputs.size() + (hasIdlReturn ? 1 : 0);

        if (totalOutputs == 0) return "void";

        if (totalOutputs == 1) {
            return hasIdlReturn ? type(op.returnType()) : type(outputs.get(0).type());
        }

        // Multiple outputs → named tuple
        List<String> elements = new ArrayList<>();
        if (hasIdlReturn) {
            elements.add(type(op.returnType()) + " ReturnValue");
        }
        for (ParameterNode p : outputs) {
            elements.add(type(p.type()) + " " + toPascalCase(p.name()));
        }
        return "(" + String.join(", ", elements) + ")";
    }

    /**
     * Returns the C# input parameter list string for an IDL operation.
     * <p>Includes {@code in} parameters and {@code inout} parameters (which
     * appear as regular inputs and also in the return tuple).
     * {@code out} parameters are excluded — they appear only in the return type.
     *
     * @param op IDL operation node
     * @return comma-separated C# parameter declarations, or empty string
     */
    public String inputParamList(OperationNode op) {
        return op.parameters().stream()
                .filter(p -> p.direction() == ParamDirection.IN
                          || p.direction() == ParamDirection.INOUT)
                .map(p -> type(p.type()) + " " + toCamelCase(p.name()))
                .collect(Collectors.joining(", "));
    }

    /**
     * Returns the C# return type string for an IDL operation return type
     * (used for standalone return-type display, not the full operation signature).
     *
     * @param t IDL return type
     * @return C# type string, {@code "void"} for IDL void
     */
    public String retType(IdlType t) {
        return (t instanceof IdlType.Void) ? "void" : type(t);
    }

    /**
     * Returns {@code true} if the type corresponds to a C# value type or
     * immutable string — i.e., it can be assigned directly in the deep copy
     * constructor without calling a copy constructor.
     *
     * @param t IDL type
     * @return {@code true} when direct assignment is sufficient
     */
    public boolean isValueType(IdlType t) {
        return CS_VALUE_TYPES.contains(type(t));
    }

    /**
     * Returns the right-hand side of a copy-constructor field assignment for
     * the given IDL type.
     *
     * <ul>
     *   <li>Value types / string → {@code source.PropertyName} (direct)</li>
     *   <li>Reference class types → {@code source.P != null ? new T(source.P) : null}</li>
     *   <li>Sequences of value types → {@code new List<T>(source.P)} (shallow copy
     *       is sufficient since elements are values)</li>
     *   <li>Sequences of reference types → LINQ {@code .Select(x => new T(x)).ToList()}</li>
     *   <li>Arrays → {@code (T[])source.P?.Clone()}</li>
     * </ul>
     *
     * @param t            IDL field type
     * @param propertyName C# property name (PascalCase), used to build {@code source.Foo}
     * @return C# expression for the copy assignment
     */
    public String copyExpr(IdlType t, String propertyName) {
        String src = "source." + propertyName;

        if (isValueType(t)) {
            return src;   // value type or immutable string — assign directly
        }

        if (t instanceof IdlType.Sequence seq) {
            String elemType = type(seq.elementType());
            if (isValueType(seq.elementType())) {
                // Value-element list — shallow copy of the container suffices
                return src + " != null ? new System.Collections.Generic.List<"
                        + elemType + ">(" + src + ") : null";
            } else {
                // Reference-element list — must copy each element
                return src + "?.Select(x => new " + elemType + "(x)).ToList()";
            }
        }

        if (t instanceof IdlType.Array arr) {
            return "(" + type(t) + ")(" + src + "?.Clone())";
        }

        // IDL struct → C# class reference type — call copy constructor
        String typeName = type(t);
        return src + " != null ? new " + typeName + "(" + src + ") : null";
    }

    /**
     * Converts an IDL {@code snake_case} (or mixed-case) identifier to
     * C# {@code PascalCase}.  Used for property names, type names, method names,
     * and named tuple element names.
     *
     * <p>Examples: {@code "threat_id"} → {@code "ThreatId"},
     * {@code "range_nm"} → {@code "RangeNm"},
     * {@code "Receive"} → {@code "Receive"}.
     *
     * @param name IDL identifier
     * @return PascalCase C# name
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
     * Converts an IDL {@code snake_case} identifier to C# {@code camelCase}.
     * Used for method parameter names.
     *
     * <p>Examples: {@code "threat_id"} → {@code "threatId"},
     * {@code "timeout"} → {@code "timeout"}.
     *
     * @param name IDL identifier
     * @return camelCase C# name
     */
    public String toCamelCase(String name) {
        String pascal = toPascalCase(name);
        if (pascal == null || pascal.isEmpty()) return pascal;
        return Character.toLowerCase(pascal.charAt(0)) + pascal.substring(1);
    }

    /**
     * Returns the C# namespace string for a module stack, preserving the
     * original IDL casing (unlike the Java mapping which lowercases segments).
     *
     * @param moduleStack module segments, e.g. {@code ["FACE","DM","SampleModel"]}
     * @return C# namespace, e.g. {@code "FACE.DM.SampleModel"}
     */
    public String namespaceName(List<String> moduleStack) {
        return String.join(".", moduleStack);
    }

    /**
     * Returns the IDL name prefixed with {@code FACE_} if it conflicts with a
     * C# keyword or contextual keyword.
     *
     * @param name IDL identifier
     * @return safe C# identifier
     */
    public String safeName(String name) {
        return CSHARP_KEYWORDS.contains(name) ? "FACE_" + name : name;
    }

    /**
     * Returns a brief readable summary of an operation's IDL parameter list,
     * suitable for inclusion in an XML doc comment (types shown, angle brackets
     * escaped for XML safety).
     *
     * @param op IDL operation node
     * @return e.g. {@code "in long timeout, out ReturnCode rc"}
     */
    public String idlParamSummary(OperationNode op) {
        return op.parameters().stream()
                .map(p -> p.direction().name().toLowerCase()
                        + " " + type(p.type())
                        + " " + p.name())
                .collect(Collectors.joining(", "));
    }

    /**
     * Returns {@code true} if a single-operation IDL interface should be
     * rendered as a C# {@code delegate} rather than an {@code interface}.
     * This is always true for interfaces with exactly one operation — the
     * canonical FACE callback pattern.
     *
     * @param iface IDL interface node
     * @return {@code true} when a delegate is the appropriate C# rendering
     */
    public boolean isDelegate(InterfaceNode iface) {
        return iface.operations().size() == 1;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String primitiveType(PrimitiveKind kind) {
        return switch (kind) {
            case SHORT                       -> "short";
            case LONG, INT32                 -> "int";
            case LONG_LONG, INT64            -> "long";
            case UNSIGNED_SHORT, UINT16      -> "ushort";
            case UNSIGNED_LONG, UINT32       -> "uint";
            case UNSIGNED_LONG_LONG, UINT64  -> "ulong";
            case FLOAT                       -> "float";
            case DOUBLE                      -> "double";
            case LONG_DOUBLE                 -> "decimal";
            case CHAR, WIDE_CHAR             -> "char";
            case OCTET, INT8, UINT8          -> "byte";
            case BOOLEAN                     -> "bool";
            case INT16                       -> "short";
            default                          -> "object";
        };
    }

    private String scopedType(String qualifiedName) {
        if (FACE_ULONG_TYPES.contains(qualifiedName))   return "ulong";
        if (FACE_LONG_TYPES.contains(qualifiedName))    return "long";
        if (FACE_INT_TYPES.contains(qualifiedName))     return "int";
        if (FACE_SHORT_TYPES.contains(qualifiedName))   return "short";
        if (FACE_USHORT_TYPES.contains(qualifiedName))  return "ushort";
        if (FACE_FLOAT_TYPES.contains(qualifiedName))   return "float";
        if (FACE_DOUBLE_TYPES.contains(qualifiedName))  return "double";
        if (FACE_DECIMAL_TYPES.contains(qualifiedName)) return "decimal";
        if (FACE_BOOL_TYPES.contains(qualifiedName))    return "bool";
        if (FACE_BYTE_TYPES.contains(qualifiedName))    return "byte";
        if (FACE_CHAR_TYPES.contains(qualifiedName))    return "char";
        if (FACE_STRING_TYPES.contains(qualifiedName))  return "string";
        // User-defined struct/enum/interface — use simple name
        return lastSegment(qualifiedName);
    }

    /** Collects {@code out} and {@code inout} parameters (the "output" side). */
    private List<ParameterNode> outputParams(OperationNode op) {
        return op.parameters().stream()
                .filter(p -> p.direction() == ParamDirection.OUT
                          || p.direction() == ParamDirection.INOUT)
                .collect(Collectors.toList());
    }

    private String lastSegment(String qualifiedName) {
        String name = qualifiedName.startsWith("::")
                ? qualifiedName.substring(2) : qualifiedName;
        int lastColon = name.lastIndexOf(':');
        return lastColon >= 0 ? name.substring(lastColon + 1) : name;
    }

    // -----------------------------------------------------------------------
    // C# keyword list
    // -----------------------------------------------------------------------

    private static final Set<String> CSHARP_KEYWORDS = Set.of(
            "abstract","as","base","bool","break","byte","case","catch","char",
            "checked","class","const","continue","decimal","default","delegate",
            "do","double","else","enum","event","explicit","extern","false",
            "finally","fixed","float","for","foreach","goto","if","implicit",
            "in","int","interface","internal","is","lock","long","namespace",
            "new","null","object","operator","out","override","params","private",
            "protected","public","readonly","ref","return","sbyte","sealed",
            "short","sizeof","stackalloc","static","string","struct","switch",
            "this","throw","true","try","typeof","uint","ulong","unchecked",
            "unsafe","ushort","using","virtual","void","volatile","while",
            // contextual keywords that can cause confusion in identifiers
            "add","alias","ascending","async","await","by","descending",
            "dynamic","equals","from","get","global","group","into","join",
            "let","nameof","on","orderby","partial","record","remove","select",
            "set","unmanaged","value","var","when","where","with","yield"
    );
}

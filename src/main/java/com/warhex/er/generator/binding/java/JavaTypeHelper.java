package com.warhex.er.generator.binding.java;

import com.warhex.er.generator.ast.*;

import java.util.Map;
import java.util.Set;

/**
 * Type-conversion helper for the Java language mapper.
 *
 * <p>An instance is placed in the Velocity context as {@code $java} so templates
 * can call methods like {@code $java.type($member.type)},
 * {@code $java.paramDecl($param)}, {@code $java.getterName($member.name)}, etc.
 *
 * <h2>Mapping rules — FACE TS 3.2 §4.14.10 / Table 20</h2>
 * <ul>
 *   <li>{@code short} → {@code short}</li>
 *   <li>{@code long} → {@code int}</li>
 *   <li>{@code long long} → {@code long}</li>
 *   <li>{@code unsigned short} → {@code int}</li>
 *   <li>{@code unsigned long} → {@code long}</li>
 *   <li>{@code unsigned long long} → {@code java.math.BigInteger}</li>
 *   <li>{@code float} → {@code float}</li>
 *   <li>{@code double} → {@code double}</li>
 *   <li>{@code long double} → {@code java.math.BigDecimal}</li>
 *   <li>{@code char} → {@code char}</li>
 *   <li>{@code octet} → {@code byte}</li>
 *   <li>{@code boolean} → {@code boolean}</li>
 *   <li>{@code string} / {@code wstring} → {@code String}</li>
 *   <li>IDL sequence&lt;T&gt; → {@code java.util.List<T>}</li>
 *   <li>IDL array T[N] → {@code T[]}</li>
 * </ul>
 *
 * <h2>out / inout parameters — §4.14.10.10.2</h2>
 * When a parameter is {@code out} or {@code inout} and its Java type is
 * immutable (a primitive or String/BigInteger/BigDecimal), it is wrapped in
 * {@code us.opengroup.FACE.Holder<BoxedType>} using the primitive wrapper class.
 * Mutable types (interfaces mapped from IDL structs and interfaces) pass directly.
 */
public class JavaTypeHelper {

    // -----------------------------------------------------------------------
    // Known FACE namespace scoped types → Java type string
    // These are typedef aliases defined in FACE/Common.idl and FACE/TSS/Common.idl
    // -----------------------------------------------------------------------

    /** FACE scoped types that map to Java {@code long}. */
    private static final Set<String> FACE_LONG_TYPES = Set.of(
            "::FACE::DURATION_TIME_TYPE",  "FACE::DURATION_TIME_TYPE",
            "::FACE::ABSOLUTE_TIME_TYPE",  "FACE::ABSOLUTE_TIME_TYPE",
            "::FACE::SYSTEM_TIME_TYPE",    "FACE::SYSTEM_TIME_TYPE",
            "::FACE::TIMEOUT_TYPE",        "FACE::TIMEOUT_TYPE",
            "::FACE::GUID_TYPE",           "FACE::GUID_TYPE",
            "::FACE::TS_TYPE",             "FACE::TS_TYPE",
            "::FACE::LongLong",            "FACE::LongLong",
            "::FACE::UnsignedLong",        "FACE::UnsignedLong",
            "::FACE::TRANSACTION_ID_TYPE", "FACE::TRANSACTION_ID_TYPE",
            "::FACE::SEQUENCE_NUMBER_TYPE","FACE::SEQUENCE_NUMBER_TYPE",
            "::FACE::MESSAGE_INSTANCE_GUID_TYPE", "FACE::MESSAGE_INSTANCE_GUID_TYPE"
    );

    /** FACE scoped types that map to Java {@code int}. */
    private static final Set<String> FACE_INT_TYPES = Set.of(
            "::FACE::Short",          "FACE::Short",
            "::FACE::UnsignedShort",  "FACE::UnsignedShort",
            "::FACE::Long",           "FACE::Long"
    );

    /** FACE scoped types that map to Java {@code float}. */
    private static final Set<String> FACE_FLOAT_TYPES = Set.of(
            "::FACE::Float", "FACE::Float"
    );

    /** FACE scoped types that map to Java {@code double}. */
    private static final Set<String> FACE_DOUBLE_TYPES = Set.of(
            "::FACE::Double", "FACE::Double"
    );

    /** FACE scoped types that map to Java {@code boolean}. */
    private static final Set<String> FACE_BOOL_TYPES = Set.of(
            "::FACE::Boolean", "FACE::Boolean"
    );

    /** FACE scoped types that map to Java {@code byte}. */
    private static final Set<String> FACE_BYTE_TYPES = Set.of(
            "::FACE::Octet", "FACE::Octet"
    );

    /** FACE scoped types that map to Java {@code char}. */
    private static final Set<String> FACE_CHAR_TYPES = Set.of(
            "::FACE::Char", "FACE::Char",
            "::FACE::WChar", "FACE::WChar"
    );

    /** FACE scoped types that map to Java {@code String}. */
    private static final Set<String> FACE_STRING_TYPES = Set.of(
            "::FACE::STRING_TYPE",            "FACE::STRING_TYPE",
            "::FACE::WSTRING_TYPE",           "FACE::WSTRING_TYPE",
            "::FACE::UNBOUNDED_STRING_TYPE",  "FACE::UNBOUNDED_STRING_TYPE"
    );

    /** FACE scoped types that map to Java {@code java.math.BigDecimal}. */
    private static final Set<String> FACE_BIGDECIMAL_TYPES = Set.of(
            "::FACE::LongDouble", "FACE::LongDouble"
    );

    // -----------------------------------------------------------------------
    // Java primitive wrapper types (for Holder<T> parameterisation)
    // -----------------------------------------------------------------------

    /** Maps primitive Java type name → boxed class name (for Holder<T>). */
    private static final Map<String, String> BOXED = Map.of(
            "short",   "Short",
            "int",     "Integer",
            "long",    "Long",
            "float",   "Float",
            "double",  "Double",
            "boolean", "Boolean",
            "char",    "Character",
            "byte",    "Byte"
    );

    // -----------------------------------------------------------------------
    // Public API — called from Velocity templates via $java.xxx(...)
    // -----------------------------------------------------------------------

    /**
     * Returns the Java type string for an IDL type (for field declarations
     * and {@code in} parameters).
     *
     * @param t IDL type from the AST
     * @return Java type string (never null)
     */
    public String type(IdlType t) {
        if (t instanceof IdlType.Primitive p) {
            return primitiveType(p.kind());
        }
        if (t instanceof IdlType.Void)    return "void";
        if (t instanceof IdlType.Str)     return "String";
        if (t instanceof IdlType.WideStr) return "String";

        if (t instanceof IdlType.Scoped s) {
            return scopedType(s.qualifiedName());
        }
        if (t instanceof IdlType.Sequence seq) {
            return "java.util.List<" + boxedType(seq.elementType()) + ">";
        }
        if (t instanceof IdlType.Array arr) {
            return type(arr.elementType()) + "[]";
        }
        return "Object";
    }

    /**
     * Returns the boxed/reference Java type for an IDL type.
     * Primitives use their wrapper class; object types are unchanged.
     * Used for Holder&lt;T&gt; parameterisation and List&lt;T&gt; element types.
     *
     * @param t IDL type
     * @return boxed Java type string
     */
    public String boxedType(IdlType t) {
        String raw = type(t);
        return BOXED.getOrDefault(raw, raw);
    }

    /**
     * Returns {@code true} if the Java type corresponding to {@code t} is
     * immutable — i.e., primitives, {@code String}, {@code BigInteger},
     * {@code BigDecimal}.  Immutable {@code out}/{@code inout} parameters
     * must use {@code us.opengroup.FACE.Holder<BoxedType>}.
     *
     * @param t IDL type
     * @return {@code true} when Holder wrapping is required
     */
    public boolean isImmutable(IdlType t) {
        String jt = type(t);
        return BOXED.containsKey(jt)
                || "String".equals(jt)
                || "java.math.BigInteger".equals(jt)
                || "java.math.BigDecimal".equals(jt);
    }

    /**
     * Returns the complete Java parameter declaration for an IDL parameter,
     * respecting §4.14.10.10.2 direction rules.
     *
     * <ul>
     *   <li>{@code in T} → {@code T name}</li>
     *   <li>{@code out/inout T} (immutable) → {@code us.opengroup.FACE.Holder<BoxedT> name}</li>
     *   <li>{@code out/inout T} (mutable) → {@code T name}</li>
     * </ul>
     *
     * @param param IDL parameter node
     * @return e.g. {@code "int arg1"} or {@code "us.opengroup.FACE.Holder<Short> arg2"}
     */
    public String paramDecl(ParameterNode param) {
        if (param.direction() == ParamDirection.IN || !isImmutable(param.type())) {
            return type(param.type()) + " " + safeName(param.name());
        }
        // out / inout with immutable type → Holder<BoxedType>
        return "us.opengroup.FACE.Holder<" + boxedType(param.type()) + "> "
                + safeName(param.name());
    }

    /**
     * Returns the Java return-type string for an IDL operation return type.
     * {@code void} is passed through; all other types delegate to {@link #type}.
     *
     * @param t IDL return type
     * @return Java return-type string
     */
    public String retType(IdlType t) {
        return (t instanceof IdlType.Void) ? "void" : type(t);
    }

    /**
     * Returns the Java getter method name for an IDL struct member.
     * {@code "threat_id"} → {@code "getThreat_id"}.
     *
     * @param fieldName IDL member name
     * @return getter name
     */
    public String getterName(String fieldName) {
        return "get" + capitalize(fieldName);
    }

    /**
     * Returns the Java setter method name for an IDL struct member.
     * {@code "threat_id"} → {@code "setThreat_id"}.
     *
     * @param fieldName IDL member name
     * @return setter name
     */
    public String setterName(String fieldName) {
        return "set" + capitalize(fieldName);
    }

    /**
     * Returns the Java package name for a module stack, lower-casing each
     * segment per §4.14.10.4.
     *
     * @param moduleStack module segments, e.g. {@code ["FACE","DM","SampleModel"]}
     * @return Java package name, e.g. {@code "face.dm.samplemodel"}
     */
    public String packageName(java.util.List<String> moduleStack) {
        if (moduleStack.isEmpty()) return "";
        return String.join(".", moduleStack.stream()
                .map(String::toLowerCase)
                .toArray(String[]::new));
    }

    /**
     * Returns the IDL identifier prefixed with {@code FACE_} if it conflicts
     * with a Java keyword, boolean literal, null literal, or a method of
     * {@code java.lang.Object} (§4.14.10.1).
     *
     * @param name IDL identifier
     * @return safe Java identifier
     */
    public String safeName(String name) {
        return JAVA_RESERVED.contains(name) ? "FACE_" + name : name;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private String primitiveType(PrimitiveKind kind) {
        return switch (kind) {
            case SHORT                          -> "short";
            case LONG, UNSIGNED_SHORT,
                 INT16, UINT16, INT32           -> "int";
            case LONG_LONG, UNSIGNED_LONG,
                 UINT32, INT64                  -> "long";
            case UNSIGNED_LONG_LONG, UINT64     -> "java.math.BigInteger";
            case FLOAT                          -> "float";
            case DOUBLE                         -> "double";
            case LONG_DOUBLE                    -> "java.math.BigDecimal";
            case CHAR, WIDE_CHAR                -> "char";
            case OCTET, INT8, UINT8             -> "byte";
            case BOOLEAN                        -> "boolean";
            default                             -> "Object";
        };
    }

    private String scopedType(String qualifiedName) {
        if (FACE_LONG_TYPES.contains(qualifiedName))       return "long";
        if (FACE_INT_TYPES.contains(qualifiedName))        return "int";
        if (FACE_FLOAT_TYPES.contains(qualifiedName))      return "float";
        if (FACE_DOUBLE_TYPES.contains(qualifiedName))     return "double";
        if (FACE_BOOL_TYPES.contains(qualifiedName))       return "boolean";
        if (FACE_BYTE_TYPES.contains(qualifiedName))       return "byte";
        if (FACE_CHAR_TYPES.contains(qualifiedName))       return "char";
        if (FACE_STRING_TYPES.contains(qualifiedName))     return "String";
        if (FACE_BIGDECIMAL_TYPES.contains(qualifiedName)) return "java.math.BigDecimal";
        // FACE::RETURN_CODE_TYPE enum — treat as the simple name (local type)
        return lastSegment(qualifiedName);
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String lastSegment(String qualifiedName) {
        String name = qualifiedName.startsWith("::")
                ? qualifiedName.substring(2)
                : qualifiedName;
        int lastColon = name.lastIndexOf(':');
        return lastColon >= 0 ? name.substring(lastColon + 1) : name;
    }

    // -----------------------------------------------------------------------
    // Java reserved words and java.lang.Object method names (§4.14.10.1)
    // -----------------------------------------------------------------------

    private static final Set<String> JAVA_RESERVED = Set.of(
            // Keywords
            "abstract","assert","boolean","break","byte","case","catch","char",
            "class","const","continue","default","do","double","else","enum",
            "extends","final","finally","float","for","goto","if","implements",
            "import","instanceof","int","interface","long","native","new",
            "package","private","protected","public","return","short","static",
            "strictfp","super","switch","synchronized","this","throw","throws",
            "transient","try","void","volatile","while",
            // Boolean literals and null
            "true","false","null",
            // java.lang.Object methods
            "clone","equals","finalize","getClass","hashCode","notify",
            "notifyAll","toString","wait"
    );
}

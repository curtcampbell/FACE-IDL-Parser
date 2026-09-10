package com.warhex.er.generator.binding.python;

import com.warhex.er.generator.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Type-conversion helper for the Python language mapper.
 *
 * <p>An instance is placed in the Velocity context as {@code $py} so templates
 * can call methods like {@code $py.type($member.type)},
 * {@code $py.fieldDecl($member.type, $member.name)}, and
 * {@code $py.paramAnnotation($param)}.
 *
 * <h2>Type mapping conventions</h2>
 * <ul>
 *   <li>All IDL integer primitives → {@code int}</li>
 *   <li>IDL float/double/long double → {@code float}</li>
 *   <li>IDL boolean → {@code bool}</li>
 *   <li>IDL char / string → {@code str}</li>
 *   <li>FACE typedef scalars (GUID_TYPE, SYSTEM_TIME_TYPE, …) → {@code int}</li>
 *   <li>FACE string types (STRING_TYPE, WSTRING_TYPE) → {@code str}</li>
 *   <li>Locally-defined structs/enums → bare class name (last segment of
 *       qualified IDL name)</li>
 *   <li>IDL sequence&lt;T&gt; / array → {@code List[T]}</li>
 * </ul>
 *
 * <h2>Target: Python 3.10+</h2>
 * Default-value logic uses {@code dataclasses.field(default_factory=…)} for
 * mutable types.  Return annotations use the bare Python type string.
 */
public class PythonTypeHelper {

    // Known FACE scoped types that map to Python built-ins
    private static final Set<String> FACE_INT_TYPES = Set.of(
            "::FACE::GUID_TYPE", "FACE::GUID_TYPE",
            "::FACE::SYSTEM_TIME_TYPE", "FACE::SYSTEM_TIME_TYPE",
            "::FACE::TS_TYPE", "FACE::TS_TYPE",
            "::FACE::Short", "FACE::Short",
            "::FACE::Long", "FACE::Long",
            "::FACE::LongLong", "FACE::LongLong",
            "::FACE::UnsignedShort", "FACE::UnsignedShort",
            "::FACE::UnsignedLong", "FACE::UnsignedLong",
            "::FACE::UnsignedLongLong", "FACE::UnsignedLongLong",
            "::FACE::Octet", "FACE::Octet",
            "::FACE::WChar", "FACE::WChar"
    );
    private static final Set<String> FACE_FLOAT_TYPES = Set.of(
            "::FACE::Float", "FACE::Float",
            "::FACE::Double", "FACE::Double",
            "::FACE::LongDouble", "FACE::LongDouble"
    );
    private static final Set<String> FACE_BOOL_TYPES = Set.of(
            "::FACE::Boolean", "FACE::Boolean"
    );
    private static final Set<String> FACE_STR_TYPES = Set.of(
            "::FACE::Char", "FACE::Char",
            "::FACE::STRING_TYPE", "FACE::STRING_TYPE",
            "::FACE::WSTRING_TYPE", "FACE::WSTRING_TYPE"
    );

    // Python built-in type names — these never need a local import
    private static final Set<String> BUILTIN_TYPES =
            Set.of("int", "float", "bool", "str", "None", "Any");

    // -----------------------------------------------------------------------
    // Public API (called from Velocity templates)
    // -----------------------------------------------------------------------

    /**
     * Returns the Python type annotation string for {@code t}.
     * For {@link IdlType.Array}, returns {@code List[elementType]}.
     * For locally-defined structs/enums, returns only the simple class name.
     *
     * @param t IDL type from the AST
     * @return Python type annotation (never null)
     */
    public String type(IdlType t) {
        if (t instanceof IdlType.Primitive p) {
            return switch (p.kind()) {
                case FLOAT, DOUBLE, LONG_DOUBLE      -> "float";
                case BOOLEAN                         -> "bool";
                case CHAR, WIDE_CHAR                 -> "str";
                default                              -> "int";  // all integer kinds
            };
        }
        if (t instanceof IdlType.Void)    return "None";
        if (t instanceof IdlType.Str)     return "str";
        if (t instanceof IdlType.WideStr) return "str";

        if (t instanceof IdlType.Scoped s) {
            String name = s.qualifiedName();
            if (FACE_INT_TYPES.contains(name))   return "int";
            if (FACE_FLOAT_TYPES.contains(name)) return "float";
            if (FACE_BOOL_TYPES.contains(name))  return "bool";
            if (FACE_STR_TYPES.contains(name))   return "str";
            // Local struct/enum — use last segment of qualified name
            return lastSegment(name);
        }

        if (t instanceof IdlType.Sequence seq) {
            return "List[" + type(seq.elementType()) + "]";
        }
        if (t instanceof IdlType.Array arr) {
            return "List[" + type(arr.elementType()) + "]";
        }
        return "Any";
    }

    /**
     * Returns a complete Python dataclass field declaration including its
     * default value or {@code field(default_factory=…)}.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code "track_id: int = 0"}</li>
     *   <li>{@code "position: GeoPosition = field(default_factory=GeoPosition)"}</li>
     *   <li>{@code "tags: List[str] = field(default_factory=list)"}</li>
     * </ul>
     *
     * @param t    field type
     * @param name field name (already snake_case per IDL convention)
     * @return full field declaration line (no trailing newline)
     */
    public String fieldDecl(IdlType t, String name) {
        String pyType = type(t);
        String dv     = scalarDefault(t, pyType);
        if (dv != null) {
            return name + ": " + pyType + " = " + dv;
        }
        // Mutable container or local class → field(default_factory=…)
        if (t instanceof IdlType.Sequence || t instanceof IdlType.Array) {
            return name + ": " + pyType + " = field(default_factory=list)";
        }
        // Local struct/enum class
        return name + ": " + pyType + " = field(default_factory=" + pyType + ")";
    }

    /**
     * Returns the return-type annotation for an operation.
     * Equivalent to {@link #type(IdlType)}.
     */
    public String retAnnotation(IdlType t) {
        return type(t);
    }

    /**
     * Returns a {@code name: type} parameter annotation for an IDL operation
     * parameter.  Direction information is not encoded in the annotation —
     * it is captured in the IDL doc-string emitted by the template.
     *
     * @param param IDL operation parameter
     * @return {@code "name: PythonType"} string
     */
    public String paramAnnotation(ParameterNode param) {
        return param.name() + ": " + type(param.type());
    }

    /**
     * Returns {@code true} if {@code t} resolves to a locally-defined class
     * (struct or enum) that needs a relative import inside the generated package.
     *
     * @param t field or parameter type
     * @return {@code true} when a {@code from .ClassName import ClassName}
     *         statement must be emitted
     */
    public boolean isLocalClass(IdlType t) {
        if (!(t instanceof IdlType.Scoped s)) return false;
        return !BUILTIN_TYPES.contains(type(s));
    }

    /**
     * Returns the simple class name for a locally-defined type (to build the
     * relative import).  Only valid when {@link #isLocalClass} returns
     * {@code true}.
     *
     * @param t a Scoped type that passes {@link #isLocalClass}
     * @return simple class name
     */
    public String localClassName(IdlType t) {
        return type(t);
    }

    /**
     * Builds the list of {@code "from pkg.Cls import Cls"} import lines for the
     * resolved actual types of a template instantiation.
     *
     * <p>Called by {@link com.warhex.er.generator.binding.generic.GenericLanguageMapper}
     * via reflection when {@code legacy_helper_class} is set for Python.
     *
     * @param resolvedActuals the list of resolved actual IDL types from
     *                        {@link com.warhex.er.generator.binding.TemplateInstantiator.InstantiationResult#resolvedActuals}
     * @return ordered list of Python import statements (may be empty)
     */
    public List<String> resolvedImports(List<IdlType> resolvedActuals) {
        List<String> result = new ArrayList<>();
        for (IdlType actual : resolvedActuals) {
            if (actual instanceof IdlType.Scoped s) {
                String qn = s.qualifiedName().startsWith("::")
                        ? s.qualifiedName().substring(2) : s.qualifiedName();
                int lastColon = qn.lastIndexOf(':');
                if (lastColon > 0) {
                    String pkg = qn.substring(0, lastColon - 1).replace("::", ".");
                    String cls = qn.substring(lastColon + 1);
                    result.add("from " + pkg + "." + cls + " import " + cls);
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /**
     * Returns a scalar default literal for {@code t}, or {@code null} when the
     * type requires {@code field(default_factory=…)} instead.
     */
    private String scalarDefault(IdlType t, String pyType) {
        // Primitives and well-known FACE scalars
        if (t instanceof IdlType.Primitive p) {
            return switch (p.kind()) {
                case FLOAT, DOUBLE, LONG_DOUBLE -> "0.0";
                case BOOLEAN                    -> "False";
                case CHAR, WIDE_CHAR            -> "\"\"";
                default                         -> "0";
            };
        }
        if (t instanceof IdlType.Str || t instanceof IdlType.WideStr) return "\"\"";
        if (t instanceof IdlType.Void) return "None";
        if (t instanceof IdlType.Scoped) {
            return switch (pyType) {
                case "int"   -> "0";
                case "float" -> "0.0";
                case "bool"  -> "False";
                case "str"   -> "\"\"";
                default      -> null;   // local class → use default_factory
            };
        }
        // Sequence / Array → default_factory
        return null;
    }

    /**
     * Returns the last segment of a {@code ::}-separated qualified IDL name.
     * {@code "::FACE::DM::GeoPosition"} → {@code "GeoPosition"}.
     */
    private String lastSegment(String qualifiedName) {
        // Strip leading :: then split on ::
        String name = qualifiedName.startsWith("::") ? qualifiedName.substring(2) : qualifiedName;
        int lastColon = name.lastIndexOf(':');
        return lastColon >= 0 ? name.substring(lastColon + 1) : name;
    }
}

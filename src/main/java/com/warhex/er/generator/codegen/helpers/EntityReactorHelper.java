package com.warhex.er.generator.codegen.helpers;

import com.warhex.er.generator.ast.*;
import com.warhex.er.generator.binding.TemplateInstantiator;

import java.util.*;
import java.util.logging.Logger;

/**
 * Velocity helper ({@code $er}) that bridges IDL struct definitions to the
 * WARHEX::EntityReactor C++ layer.
 *
 * <h2>Context variable name</h2>
 * Registered under {@code "entity_reactor"} in {@link HelperRegistry};
 * the default Velocity context variable is {@code $er} (configurable via
 * {@code helpers[].as} in {@code codegen.yaml}).
 *
 * <h2>Primary methods (call from templates)</h2>
 * <ul>
 *   <li>{@link #topLevelFields(StructNode)} — one {@link TopLevelField} per IDL
 *       struct member; composite members carry a {@link SubField} list.</li>
 *   <li>{@link #flatFields(StructNode)} — one {@link FlatField} per
 *       {@code FieldDescriptor} row in the registration call (composite members
 *       are expanded into per-sub-field rows).</li>
 *   <li>{@link #entityValueKind(IdlType)} — maps an IDL type to an
 *       {@code EntityValueKind} constant name (e.g. {@code "EV_DOUBLE"}).</li>
 *   <li>{@link #attributeValueType(IdlType)} — maps an IDL type to an
 *       {@code AttributeValueType} constant name (e.g. {@code "AV_DOUBLE"}).</li>
 *   <li>{@link #isComposite(IdlType)} — {@code true} when the IDL type resolves
 *       to a nested struct that must be expanded.</li>
 *   <li>{@link #enumValues(String)} — returns all values of a named IDL enum,
 *       useful for generating EntityTypeEnum switch tables.</li>
 * </ul>
 *
 * <h2>Type mapping</h2>
 * <pre>
 *   IDL Primitive   → AttributeValueType / EntityValueKind
 *   ─────────────────────────────────────────────────────
 *   boolean         → AV_BOOLEAN  / EV_BOOL
 *   short/long/...  → AV_INT64    / EV_INT64   (all signed integers)
 *   u-short/u-long/… → AV_UINT64 / EV_UINT64  (all unsigned integers)
 *   float           → AV_FLOAT    / EV_FLOAT
 *   double          → AV_DOUBLE   / EV_DOUBLE
 *   string          → AV_STRING   / EV_STRING
 *
 *   IDL Scoped (well-known FACE types)
 *   ─────────────────────────────────
 *   FACE::GUID_Type / GUID_Type             → AV_GUID        / EV_GUID
 *   FACE::System_Time_Type / System_Time_Type → AV_SYSTEM_TIME / EV_SYSTEM_TIME
 *
 *   IDL Scoped resolving to a StructNode in the IDL spec
 *   ─────────────────────────────────────────────────────
 *   (any struct)    → (null)       / EV_COMPOSITE
 * </pre>
 */
public final class EntityReactorHelper {

    private static final Logger LOG =
            Logger.getLogger(EntityReactorHelper.class.getName());

    /** Default Velocity context variable name when none is specified in the manifest. */
    public static final String DEFAULT_VAR_NAME = "er";

    // Index: both simple name ("GeoPosition") and fully-qualified ("FACE::DM::SampleModel::GeoPosition")
    private final Map<String, StructNode> structsByName;
    private final Map<String, List<EnumValueNode>> enumsByName;

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    public EntityReactorHelper(IdlSpecification spec,
                               @SuppressWarnings("unused") TemplateInstantiator instantiator) {
        this.structsByName = new LinkedHashMap<>();
        this.enumsByName   = new LinkedHashMap<>();
        collect(spec.definitions(), new ArrayDeque<>());
    }

    // -----------------------------------------------------------------------
    // Primary Velocity-callable API
    // -----------------------------------------------------------------------

    /**
     * Returns one {@link TopLevelField} per member of {@code struct}.
     * Ordinals are assigned in declaration order starting at 0.
     * Composite members carry a populated {@link TopLevelField#getSubFields()} list.
     *
     * @param struct IDL struct node (from {@code $struct} in template context)
     * @return ordered list; never {@code null}
     */
    public List<TopLevelField> topLevelFields(StructNode struct) {
        List<TopLevelField> result = new ArrayList<>();
        int ordinal = 0;
        for (FieldNode member : struct.getMembers()) {
            IdlType type = member.getType();
            if (isComposite(type)) {
                StructNode nested = resolveStruct(type);
                List<SubField> subs = new ArrayList<>();
                int ci = 0;
                if (nested != null) {
                    for (FieldNode sm : nested.getMembers()) {
                        subs.add(new SubField(
                                member.getName(),
                                sm.getName(),
                                ci++,
                                attributeValueType(sm.getType()),
                                entityValueKind(sm.getType())));
                    }
                } else {
                    LOG.warning("Could not resolve composite struct for field '"
                            + member.getName() + "' type=" + type);
                }
                result.add(new TopLevelField(
                        member.getName(), ordinal, true,
                        idlTypeName(type), null, "EV_COMPOSITE", subs));
            } else {
                result.add(new TopLevelField(
                        member.getName(), ordinal, false,
                        idlTypeName(type),
                        attributeValueType(type),
                        entityValueKind(type),
                        List.of()));
            }
            ordinal++;
        }
        return result;
    }

    /**
     * Returns one {@link FlatField} per {@code FieldDescriptor} row needed in
     * the EntityReactor registration call for {@code struct}.
     *
     * <p>Simple fields produce a single row with {@code compositeIndex == -1}.
     * Composite fields are expanded: one row per sub-member, all sharing the
     * same top-level ordinal.
     *
     * @param struct IDL struct node
     * @return flat ordered list; never {@code null}
     */
    public List<FlatField> flatFields(StructNode struct) {
        List<FlatField> result = new ArrayList<>();
        for (TopLevelField tf : topLevelFields(struct)) {
            if (tf.isComposite()) {
                for (SubField sf : tf.getSubFields()) {
                    result.add(new FlatField(
                            sf.getQualifiedName(),
                            tf.getName(),
                            sf.getName(),
                            tf.getOrdinal(),
                            sf.getCompositeIndex(),
                            sf.getAttributeValueType(),
                            sf.getEntityValueKind(),
                            true));
                }
            } else {
                result.add(new FlatField(
                        tf.getName(),
                        tf.getName(),
                        null,
                        tf.getOrdinal(),
                        -1,
                        tf.getAttributeValueType(),
                        tf.getEntityValueKind(),
                        false));
            }
        }
        return result;
    }

    /**
     * Returns the {@code EntityValueKind} constant name for an IDL type.
     * Returns {@code "EV_COMPOSITE"} for struct-typed fields and
     * {@code "EV_UNSET"} for unrecognised types (with a warning).
     *
     * @param type IDL type node
     * @return constant name, e.g. {@code "EV_DOUBLE"}
     */
    public String entityValueKind(IdlType type) {
        if (type instanceof IdlType.Primitive p) {
            return primitiveEVK(p.kind());
        }
        if (type instanceof IdlType.Str) {
            return "EV_STRING";
        }
        if (type instanceof IdlType.Scoped s) {
            String qn = s.qualifiedName();
            // Well-known FACE types (all-caps typedef names as defined in FACE/Common.idl)
            if (endsWith(qn, "GUID_TYPE"))         return "EV_GUID";
            if (endsWith(qn, "SYSTEM_TIME_TYPE"))  return "EV_SYSTEM_TIME";
            if (endsWith(qn, "STRING_TYPE"))        return "EV_STRING";
            // Sequence/bytes
            if (endsWith(qn, "BYTES"))             return "EV_BYTES";
            // Enum types — represented as signed 64-bit integers
            if (enumsByName.containsKey(qn) || enumsByName.containsKey(simpleName(qn)))
                return "EV_INT64";
            // Struct → composite
            if (structsByName.containsKey(qn) || structsByName.containsKey(simpleName(qn)))
                return "EV_COMPOSITE";
        }
        if (type instanceof IdlType.Sequence || type instanceof IdlType.Array) {
            return "EV_BYTES";
        }
        LOG.warning("Unrecognised IDL type for EV mapping: " + type + ". Defaulting to EV_UNSET.");
        return "EV_UNSET";
    }

    /**
     * Returns the {@code AttributeValueType} constant name for an IDL type.
     * Returns {@code null} for composite (struct) types — those fields have
     * no {@code AttributeValueType} in the C++ descriptor.
     *
     * @param type IDL type node
     * @return constant name, e.g. {@code "AV_DOUBLE"}, or {@code null} for composites
     */
    public String attributeValueType(IdlType type) {
        if (type instanceof IdlType.Primitive p) {
            return primitiveAVT(p.kind());
        }
        if (type instanceof IdlType.Str) {
            return "AV_STRING";
        }
        if (type instanceof IdlType.Scoped s) {
            String qn = s.qualifiedName();
            // Well-known FACE types (all-caps typedef names as defined in FACE/Common.idl)
            if (endsWith(qn, "GUID_TYPE"))         return "AV_GUID";
            if (endsWith(qn, "SYSTEM_TIME_TYPE"))  return "AV_SYSTEM_TIME";
            if (endsWith(qn, "STRING_TYPE"))        return "AV_STRING";
            // Enum types — represented as signed 64-bit integers
            if (enumsByName.containsKey(qn) || enumsByName.containsKey(simpleName(qn)))
                return "AV_INT64";
            if (structsByName.containsKey(qn) || structsByName.containsKey(simpleName(qn)))
                return null; // composite — no single AV type
        }
        if (type instanceof IdlType.Sequence || type instanceof IdlType.Array) {
            return null; // bytes — caller must handle specially
        }
        LOG.warning("Unrecognised IDL type for AV mapping: " + type + ". Returning null.");
        return null;
    }

    /**
     * Returns {@code true} when {@code type} resolves to a struct in the IDL
     * specification (i.e. it must be expanded as a composite field).
     */
    public boolean isComposite(IdlType type) {
        if (!(type instanceof IdlType.Scoped s)) return false;
        String qn = s.qualifiedName();
        // Exclude well-known non-struct FACE types and enum types
        if (endsWith(qn, "GUID_TYPE") || endsWith(qn, "SYSTEM_TIME_TYPE")
                || endsWith(qn, "STRING_TYPE")) return false;
        if (enumsByName.containsKey(qn) || enumsByName.containsKey(simpleName(qn))) return false;
        return structsByName.containsKey(qn) || structsByName.containsKey(simpleName(qn));
    }

    /**
     * Returns all {@link EnumValueNode}s for the named IDL enum, looked up by
     * simple or fully-qualified name.  Returns an empty list if not found.
     *
     * <p>Useful for generating {@code EntityTypeEnum} switch tables:
     * <pre>
     * #foreach($ev in $er.enumValues("EntityTypeEnum"))
     *   case $ev.name: ...
     * #end
     * </pre>
     *
     * @param name simple or fully-qualified IDL enum name
     * @return enum value nodes; never {@code null}
     */
    public List<EnumValueNode> enumValues(String name) {
        List<EnumValueNode> vals = enumsByName.get(name);
        if (vals == null) vals = enumsByName.get(simpleName(name));
        return vals != null ? vals : List.of();
    }

    /**
     * Converts a camelCase or PascalCase IDL name to snake_case.
     * Useful for generating FACE member access expressions from C++ field names.
     *
     * <p>Example: {@code "trackId"} → {@code "track_id"},
     *             {@code "GeoPosition"} → {@code "geo_position"}.
     */
    public String toSnakeCase(String name) {
        if (name == null || name.isEmpty()) return name;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c) && i > 0) sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /**
     * Capitalises the first letter of {@code name}.
     * Useful for generating method names: {@code "track"} → {@code "Track"}.
     */
    public String capitalize(String name) {
        if (name == null || name.isEmpty()) return name;
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    // -----------------------------------------------------------------------
    // C++ name-derivation helpers (Velocity-callable)
    // -----------------------------------------------------------------------

    /**
     * Strips the {@code "Entity"} suffix from an entity struct name.
     * {@code "TrackEntity"} → {@code "Track"}.
     * If the name does not end with {@code "Entity"}, it is returned unchanged.
     */
    public String stripEntitySuffix(String structName) {
        if (structName != null && structName.endsWith("Entity")) {
            return structName.substring(0, structName.length() - "Entity".length());
        }
        return structName;
    }

    /**
     * Derives the FACE union accessor/member name from an entity struct name.
     * {@code "TrackEntity"} → {@code "track"} (lowercase alias, no "Entity" suffix).
     */
    public String toMemberName(String structName) {
        String alias = stripEntitySuffix(structName);
        if (alias == null || alias.isEmpty()) return alias;
        return Character.toLowerCase(alias.charAt(0)) + alias.substring(1);
    }

    /**
     * Derives the {@code EntityTypeEnum} constant name from an entity struct name.
     * {@code "TrackEntity"} → {@code "ENTITY_TYPE_TRACK"}.
     */
    public String toEntityTypeEnum(String structName) {
        return "ENTITY_TYPE_" + stripEntitySuffix(structName).toUpperCase();
    }

    /**
     * Derives the {@code EntityTypeDescriptor::type_name} dot-notation string
     * from a model namespace and an entity struct name.
     * {@code ("FACE::DM::SampleModel", "TrackEntity")} →
     * {@code "FACE.DM.SampleModel.Track"}.
     */
    public String faceDotName(String cppNamespace, String structName) {
        return cppNamespace.replace("::", ".") + "." + stripEntitySuffix(structName);
    }

    /**
     * Derives the registrar class name from an {@code EntityTypeEnum} constant name.
     * {@code "ENTITY_TYPE_THREAT"} → {@code "ThreatEntityRegistrar"}.
     */
    public String enumToRegistrarName(String enumValueName) {
        String alias = toTitleCase(enumValueName.replace("ENTITY_TYPE_", ""));
        return alias + "EntityRegistrar";
    }

    /**
     * Derives the entity struct name from an {@code EntityTypeEnum} constant name.
     * {@code "ENTITY_TYPE_THREAT"} → {@code "ThreatEntity"}.
     */
    public String enumToStructName(String enumValueName) {
        return toTitleCase(enumValueName.replace("ENTITY_TYPE_", "")) + "Entity";
    }

    // -----------------------------------------------------------------------
    // C++ expression helpers (Velocity-callable)
    // -----------------------------------------------------------------------

    /**
     * Returns the {@code EntityValue::from_xxx} method name suffix for an
     * {@code EntityValueKind} constant.
     * Examples: {@code "EV_GUID"} → {@code "from_guid"};
     *           {@code "EV_SYSTEM_TIME"} → {@code "from_system_time"}.
     */
    public String evFromMethod(String entityValueKind) {
        if (entityValueKind == null) return "from_int";
        return switch (entityValueKind) {
            case "EV_GUID"        -> "from_guid";
            case "EV_DOUBLE"      -> "from_double";
            case "EV_FLOAT"       -> "from_float";
            case "EV_INT64"       -> "from_int";
            case "EV_UINT64"      -> "from_uint";
            case "EV_STRING"      -> "from_string";
            case "EV_BOOL"        -> "from_bool";
            case "EV_SYSTEM_TIME" -> "from_system_time";
            case "EV_BYTES"       -> "from_bytes";
            default -> "from_int";
        };
    }

    /**
     * Returns the {@code EntityValue} scalar union member name for an
     * {@code EntityValueKind}.  Returns {@code null} for {@code EV_STRING}
     * (which uses {@code .string_val} directly, not {@code .scalar.xxx}).
     * Examples: {@code "EV_GUID"} → {@code "guid_val"};
     *           {@code "EV_DOUBLE"} → {@code "double_val"}.
     */
    public String evScalarMember(String entityValueKind) {
        if (entityValueKind == null) return "int_val";
        return switch (entityValueKind) {
            case "EV_GUID"        -> "guid_val";
            case "EV_DOUBLE"      -> "double_val";
            case "EV_FLOAT"       -> "float_val";
            case "EV_INT64"       -> "int_val";
            case "EV_UINT64"      -> "uint_val";
            case "EV_BOOL"        -> "bool_val";
            case "EV_SYSTEM_TIME" -> "time_val";
            case "EV_STRING"      -> null;   // caller must use .string_val, not .scalar
            default -> "int_val";
        };
    }

    /**
     * Returns the {@code AttributeValue::from_xxx} method name suffix for an
     * {@code AttributeValueType} constant.  Note: {@code AV_SYSTEM_TIME} maps
     * to {@code from_time} (not {@code from_system_time}) per the C++ API.
     * Examples: {@code "AV_GUID"} → {@code "from_guid"};
     *           {@code "AV_SYSTEM_TIME"} → {@code "from_time"}.
     */
    public String avFromMethod(String attributeValueType) {
        if (attributeValueType == null) return "from_int";
        return switch (attributeValueType) {
            case "AV_GUID"        -> "from_guid";
            case "AV_DOUBLE"      -> "from_double";
            case "AV_FLOAT"       -> "from_float";
            case "AV_INT64"       -> "from_int";
            case "AV_UINT64"      -> "from_uint";
            case "AV_STRING"      -> "from_string";
            case "AV_BOOLEAN"     -> "from_bool";
            case "AV_SYSTEM_TIME" -> "from_time";
            default -> "from_int";
        };
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    /** Converts an ALL_CAPS word to TitleCase. {@code "THREAT"} → {@code "Threat"}. */
    private static String toTitleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private StructNode resolveStruct(IdlType type) {
        if (!(type instanceof IdlType.Scoped s)) return null;
        String qn = s.qualifiedName();
        StructNode sn = structsByName.get(qn);
        if (sn == null) sn = structsByName.get(simpleName(qn));
        return sn;
    }

    private static String idlTypeName(IdlType type) {
        return type.toString();
    }

    // -------------------------------------------------------------------
    // Spec collector — builds structsByName and enumsByName indices
    // -------------------------------------------------------------------

    private void collect(List<IdlDefinition> defs, Deque<String> nsStack) {
        for (IdlDefinition def : defs) {
            if (def instanceof ModuleNode m) {
                nsStack.addLast(m.name());
                collect(m.definitions(), nsStack);
                nsStack.removeLast();
            } else if (def instanceof StructNode s) {
                structsByName.put(s.name(), s);
                structsByName.put(fqn(nsStack, s.name()), s);
            } else if (def instanceof EnumNode e) {
                enumsByName.put(e.name(), e.values());
                enumsByName.put(fqn(nsStack, e.name()), e.values());
            }
        }
    }

    private static String fqn(Deque<String> stack, String name) {
        if (stack.isEmpty()) return name;
        return String.join("::", stack) + "::" + name;
    }

    private static String simpleName(String qualifiedName) {
        if (qualifiedName == null) return null;
        int idx = qualifiedName.lastIndexOf(':');
        return idx >= 0 ? qualifiedName.substring(idx + 1) : qualifiedName;
    }

    private static boolean endsWith(String qn, String suffix) {
        return qn != null && (qn.equals(suffix) || qn.endsWith("::" + suffix));
    }

    // -------------------------------------------------------------------
    // Primitive type tables
    // -------------------------------------------------------------------

    private static String primitiveEVK(PrimitiveKind k) {
        return switch (k) {
            case BOOLEAN                                                          -> "EV_BOOL";
            case SHORT, LONG, LONG_LONG, INT8, INT16, INT32, INT64, CHAR, OCTET -> "EV_INT64";
            case UNSIGNED_SHORT, UNSIGNED_LONG, UNSIGNED_LONG_LONG,
                    UINT8, UINT16, UINT32, UINT64                               -> "EV_UINT64";
            case FLOAT                                                            -> "EV_FLOAT";
            case DOUBLE, LONG_DOUBLE                                              -> "EV_DOUBLE";
            default -> {
                LOG.warning("No EV mapping for PrimitiveKind " + k + ". Using EV_UNSET.");
                yield "EV_UNSET";
            }
        };
    }

    private static String primitiveAVT(PrimitiveKind k) {
        return switch (k) {
            case BOOLEAN                                                          -> "AV_BOOLEAN";
            case SHORT, LONG, LONG_LONG, INT8, INT16, INT32, INT64, CHAR, OCTET -> "AV_INT64";
            case UNSIGNED_SHORT, UNSIGNED_LONG, UNSIGNED_LONG_LONG,
                    UINT8, UINT16, UINT32, UINT64                               -> "AV_UINT64";
            case FLOAT                                                            -> "AV_FLOAT";
            case DOUBLE, LONG_DOUBLE                                              -> "AV_DOUBLE";
            default -> {
                LOG.warning("No AV mapping for PrimitiveKind " + k + ". Using AV_INT64.");
                yield "AV_INT64";
            }
        };
    }
}

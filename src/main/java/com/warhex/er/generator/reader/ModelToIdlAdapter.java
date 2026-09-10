package com.warhex.er.generator.reader;

import com.warhex.er.generator.ast.*;
import com.warhex.er.generator.model.*;

import java.util.*;
import java.util.OptionalInt;
import java.util.stream.Collectors;

/**
 * Converts an {@link EntityModel} (produced by the YAML/JSON reader path) into
 * an {@link IdlSpecification} containing only the DM (Data Model) subtree.
 *
 * <h2>Output module structure</h2>
 * <pre>
 *   FACE {
 *     DM { SampleModel { GeoPosition, ThreatLevel, TrackEntity, EntityEvent, … } }
 *   }
 * </pre>
 *
 * The DM segment is derived from {@code model.getIdlModule()} (e.g.
 * {@code "FACE.DM.SampleModel"}).
 *
 * <p>TSS TypedTS instantiations are intentionally <em>not</em> produced here.
 * Stage 2 (language binding) reads the actual generated IDL files via
 * {@link com.warhex.er.generator.parser.IdlDirectoryParser} so that the C++
 * output faithfully reflects whatever IDL is on disk — including any
 * integrator post-processing of the generated files.
 *
 * <h2>Definition order inside DM module</h2>
 * <ol>
 *   <li>Nested struct types in first-encountered order (deduplicated)</li>
 *   <li>For each entity: supporting enums then entity struct</li>
 *   <li>EntityReactor protocol types (EntityEvent, CRUD, Subscription, …)</li>
 * </ol>
 */
public class ModelToIdlAdapter {

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Converts {@code model} to an {@link IdlSpecification} containing only
     * the DM module subtree.
     *
     * <p>TSS content is deliberately excluded.  Language binding (Stage 2)
     * obtains TypedTS instantiations by parsing the generated IDL files via
     * {@link com.warhex.er.generator.parser.IdlDirectoryParser}.
     */
    public IdlSpecification adapt(EntityModel model) {
        List<IdlDefinition> dmDefs = buildDmDefinitions(model);
        // e.g. "FACE.DM.SampleModel" → segments ["FACE","DM","SampleModel"]
        String[] dmSegs = model.getIdlModule().split("\\.");
        IdlDefinition wrapped = wrapInModules(Arrays.asList(dmSegs), dmDefs);
        return new IdlSpecification(List.of(wrapped));
    }

    // -------------------------------------------------------------------------
    // DM inner definitions
    // -------------------------------------------------------------------------

    private List<IdlDefinition> buildDmDefinitions(EntityModel model) {
        List<IdlDefinition> defs = new ArrayList<>();

        // 1. Collect nested struct types in dependency order (sub-structs first).
        //    Uses a recursive helper so that structs nested within structs are
        //    emitted before the struct that references them — required by IDL
        //    forward-reference rules.
        LinkedHashMap<String, StructNode> nestedStructs = new LinkedHashMap<>();
        for (EntityDescriptor entity : model.getEntities()) {
            for (FieldDescriptor fd : entity.getFields()) {
                collectNestedStructsRecursive(fd, nestedStructs);
            }
        }
        defs.addAll(nestedStructs.values());

        // 2a. Collect supporting enums across all entities, deduplicated.
        //     The YAML path declares each enum on exactly one entity, so
        //     deduplication is a no-op there.  The FACE reader may attach the
        //     same resolved enum to multiple entities (e.g., if an enum type is
        //     used by both a struct member and a direct composition); putIfAbsent
        //     ensures each enum name appears only once in the output.
        LinkedHashMap<String, EnumNode> standaloneEnums = new LinkedHashMap<>();
        for (EntityDescriptor entity : model.getEntities()) {
            for (EnumDescriptor ed : entity.getSupportingEnums()) {
                standaloneEnums.putIfAbsent(ed.getName(), buildEnum(ed));
            }
        }
        defs.addAll(standaloneEnums.values());

        // 2b. Entity structs (enums have already been emitted above)
        for (EntityDescriptor entity : model.getEntities()) {
            defs.add(buildEntityStruct(entity, model));
        }

        // 3. EntityReactor protocol types
        defs.addAll(buildEntityReactorDefinitions(model));

        return defs;
    }

    // -------------------------------------------------------------------------
    // EntityReactor protocol type builders
    // -------------------------------------------------------------------------

    private List<IdlDefinition> buildEntityReactorDefinitions(EntityModel model) {
        List<IdlDefinition> defs = new ArrayList<>();

        // Sort entities alphabetically for stable enum ordinals [ER-071]
        List<EntityDescriptor> sorted = model.getEntities().stream()
                .sorted(Comparator.comparing(EntityDescriptor::getSimpleName))
                .collect(Collectors.toList());

        // Convenient type aliases
        IdlType GUID    = new IdlType.Scoped("::FACE::GUID_TYPE");
        IdlType STR     = new IdlType.Scoped("::FACE::STRING_TYPE");
        IdlType STIME   = new IdlType.Scoped("::FACE::SYSTEM_TIME_TYPE");
        IdlType RETCODE = new IdlType.Scoped("::FACE::RETURN_CODE_TYPE");
        IdlType TIMEOUT = new IdlType.Scoped("::FACE::TIMEOUT_TYPE");
        IdlType CONNNAME= new IdlType.Scoped("::FACE::TSS::CONNECTION_NAME_TYPE");
        IdlType ULONG   = new IdlType.Primitive(PrimitiveKind.UNSIGNED_LONG);
        IdlType LLONG   = new IdlType.Primitive(PrimitiveKind.LONG_LONG);
        IdlType ULLONG  = new IdlType.Primitive(PrimitiveKind.UNSIGNED_LONG_LONG);
        IdlType FLOAT_T = new IdlType.Primitive(PrimitiveKind.FLOAT);
        IdlType DOUBLE_T= new IdlType.Primitive(PrimitiveKind.DOUBLE);
        IdlType BOOL_T  = new IdlType.Primitive(PrimitiveKind.BOOLEAN);

        // --- EntityTypeEnum -------------------------------------------------
        List<EnumValueNode> typeEnumVals = new ArrayList<>();
        typeEnumVals.add(new EnumValueNode("ENTITY_TYPE_UNKNOWN"));
        for (EntityDescriptor e : sorted)
            typeEnumVals.add(new EnumValueNode("ENTITY_TYPE_" + e.getSimpleName().toUpperCase()));
        defs.add(new EnumNode("EntityTypeEnum", typeEnumVals));

        // --- EntityPayload union (Phase D+; emitted for IDL completeness) ---
        IdlType enumScoped = new IdlType.Scoped("EntityTypeEnum");
        List<UnionCaseNode> payloadCases = new ArrayList<>();
        for (EntityDescriptor e : sorted)
            payloadCases.add(new UnionCaseNode(
                List.of("ENTITY_TYPE_" + e.getSimpleName().toUpperCase()), false,
                new IdlType.Scoped(model.structNameFor(e.getSimpleName())),
                e.getSimpleName().toLowerCase()));
        defs.add(new UnionNode("EntityPayload", enumScoped, payloadCases));

        // EntityID_Type typedef
        defs.add(new TypedefNode("EntityID_Type", GUID));

        // --- EntityEvent supporting types -----------------------------------
        defs.add(new EnumNode("EventType", List.of(
            new EnumValueNode("EVENT_CREATED"), new EnumValueNode("EVENT_UPDATED"),
            new EnumValueNode("EVENT_DELETED"), new EnumValueNode("EVENT_EXPIRED"))));

        defs.add(new StructNode("ChangedField", List.of(
            new FieldNode(STR,   "qualified_name"),
            new FieldNode(ULONG, "position_index"))));

        defs.add(new ConstNode("MAX_FIELDS_PER_ENTITY", ULONG, "64"));

        defs.add(new TypedefNode("ChangedFieldList_Type",
            new IdlType.Sequence(new IdlType.Scoped("ChangedField"), OptionalInt.of(64))));

        defs.add(new StructNode("EntityEvent", List.of(
            new FieldNode(new IdlType.Scoped("EntityID_Type"),         "entity_id"),
            new FieldNode(new IdlType.Scoped("EntityTypeEnum"),        "entity_type"),
            new FieldNode(new IdlType.Scoped("EventType"),             "event_type"),
            new FieldNode(new IdlType.Scoped("EntityPayload"),         "payload"),
            new FieldNode(new IdlType.Scoped("ChangedFieldList_Type"), "changed_fields"),
            new FieldNode(STIME,                                       "timestamp"))));

        // --- CRUD types -----------------------------------------------------
        defs.add(new EnumNode("CrudOperationType", List.of(
            new EnumValueNode("CRUD_CREATE"), new EnumValueNode("CRUD_READ"),
            new EnumValueNode("CRUD_UPDATE"), new EnumValueNode("CRUD_DELETE"))));

        defs.add(new StructNode("EntityCrudRequest", List.of(
            new FieldNode(new IdlType.Scoped("CrudOperationType"), "operation"),
            new FieldNode(new IdlType.Scoped("EntityID_Type"),     "entity_id"),
            new FieldNode(new IdlType.Scoped("EntityPayload"),     "payload"))));

        defs.add(new StructNode("EntityCrudResponse", List.of(
            new FieldNode(RETCODE,                                 "return_code"),
            new FieldNode(new IdlType.Scoped("EntityID_Type"),    "entity_id"),
            new FieldNode(new IdlType.Scoped("EntityPayload"),    "payload"))));

        // --- Subscription types ---------------------------------------------
        defs.add(new EnumNode("AttributeValueType", List.of(
            new EnumValueNode("AV_BOOLEAN"), new EnumValueNode("AV_INT64"),
            new EnumValueNode("AV_UINT64"),  new EnumValueNode("AV_FLOAT"),
            new EnumValueNode("AV_DOUBLE"),  new EnumValueNode("AV_STRING"),
            new EnumValueNode("AV_GUID"),    new EnumValueNode("AV_SYSTEM_TIME"))));

        defs.add(new UnionNode("AttributeValue",
            new IdlType.Scoped("AttributeValueType"), List.of(
                new UnionCaseNode(List.of("AV_BOOLEAN"),     false, BOOL_T,  "bool_val"),
                new UnionCaseNode(List.of("AV_INT64"),       false, LLONG,   "int_val"),
                new UnionCaseNode(List.of("AV_UINT64"),      false, ULLONG,  "uint_val"),
                new UnionCaseNode(List.of("AV_FLOAT"),       false, FLOAT_T, "float_val"),
                new UnionCaseNode(List.of("AV_DOUBLE"),      false, DOUBLE_T,"double_val"),
                new UnionCaseNode(List.of("AV_STRING"),      false, STR,     "string_val"),
                new UnionCaseNode(List.of("AV_GUID"),        false, GUID,    "guid_val"),
                new UnionCaseNode(List.of("AV_SYSTEM_TIME"), false, STIME,   "time_val"))));

        defs.add(new EnumNode("NotificationMode", List.of(
            new EnumValueNode("ON_CHANGE"), new EnumValueNode("PERIODIC"))));

        defs.add(new ConstNode("MAX_CONDITIONS", ULONG, "16"));
        defs.add(new ConstNode("MAX_VALUES",     ULONG, "16"));
        defs.add(new TypedefNode("NotificationType", ULONG));

        defs.add(new StructNode("SubscriptionRequest", List.of(
            new FieldNode(new IdlType.Scoped("EntityTypeEnum"),                          "entity_type"),
            new FieldNode(new IdlType.Sequence(STR,                    OptionalInt.of(16)), "conditions"),
            new FieldNode(new IdlType.Sequence(new IdlType.Scoped("AttributeValue"),
                                               OptionalInt.of(16)),                         "values"),
            new FieldNode(new IdlType.Scoped("NotificationType"),                        "notification_type"),
            new FieldNode(new IdlType.Scoped("NotificationMode"),                        "notification_mode"),
            new FieldNode(TIMEOUT,                                                        "period"))));

        defs.add(new StructNode("ValidationError", List.of(
            new FieldNode(ULONG, "condition_index"),
            new FieldNode(STR,   "error_message"))));

        defs.add(new StructNode("ValidationResult", List.of(
            new FieldNode(new IdlType.Sequence(new IdlType.Scoped("ValidationError"),
                                               OptionalInt.of(16)), "errors"))));

        defs.add(new StructNode("SubscriptionResponse", List.of(
            new FieldNode(RETCODE,                                  "return_code"),
            new FieldNode(CONNNAME,                                 "notify_connection"),
            new FieldNode(new IdlType.Scoped("ValidationResult"),  "validation_result"))));

        return defs;
    }

    // -------------------------------------------------------------------------
    // DM struct / enum builders
    // -------------------------------------------------------------------------

    private StructNode buildEntityStruct(EntityDescriptor entity, EntityModel model) {
        String structName = model.structNameFor(entity.getSimpleName());
        List<FieldNode> members = new ArrayList<>();
        for (FieldDescriptor fd : entity.getFields()) {
            if (fd.isNested()) {
                members.add(new FieldNode(new IdlType.Scoped(fd.getIdlType()), fd.getName()));
            } else {
                members.add(new FieldNode(parseIdlType(fd.getIdlType()), fd.getName()));
            }
        }
        return new StructNode(structName, members);
    }

    /**
     * Recursively collects nested struct definitions into {@code collected} in
     * dependency order (deepest sub-structs first).  Idempotent — a struct
     * already in the map is skipped.
     */
    private void collectNestedStructsRecursive(FieldDescriptor fd,
                                               LinkedHashMap<String, StructNode> collected) {
        if (!fd.isNested()) return;
        if (collected.containsKey(fd.getIdlType())) return;

        // Recurse into children first so sub-structs are defined before their parent
        for (FieldDescriptor child : fd.getNestedFields()) {
            collectNestedStructsRecursive(child, collected);
        }

        collected.put(fd.getIdlType(), buildNestedStruct(fd));
    }

    private StructNode buildNestedStruct(FieldDescriptor parentField) {
        List<FieldNode> members = new ArrayList<>();
        for (FieldDescriptor child : parentField.getNestedFields()) {
            if (child.isNested()) {
                // Child is itself a struct reference — emit as a scoped type name.
                // The child struct has already been (or will be) added separately
                // by collectNestedStructsRecursive, so a forward reference is safe.
                members.add(new FieldNode(new IdlType.Scoped(child.getIdlType()), child.getName()));
            } else {
                members.add(new FieldNode(parseIdlType(child.getIdlType()), child.getName()));
            }
        }
        return new StructNode(parentField.getIdlType(), members);
    }

    private EnumNode buildEnum(EnumDescriptor ed) {
        List<EnumValueNode> values = new ArrayList<>();
        for (EnumValueDescriptor evd : ed.getValues()) {
            values.add(new EnumValueNode(evd.getName()));
        }
        return new EnumNode(ed.getName(), values);
    }

    // -------------------------------------------------------------------------
    // Module hierarchy builder
    // -------------------------------------------------------------------------

    /**
     * Wraps {@code innerDefs} in nested {@link ModuleNode}s for each segment.
     * When {@code segments} is empty the inner definitions are returned as-is
     * (as a list, not wrapped — callers handle that case).
     */
    private IdlDefinition wrapInModules(List<String> segments,
                                        List<IdlDefinition> innerDefs) {
        if (segments.size() == 1) {
            return new ModuleNode(segments.get(0), innerDefs);
        }
        IdlDefinition inner = wrapInModules(
                segments.subList(1, segments.size()), innerDefs);
        return new ModuleNode(segments.get(0), List.of(inner));
    }

    // -------------------------------------------------------------------------
    // IDL type parser
    // -------------------------------------------------------------------------

    private IdlType parseIdlType(String typeStr) {
        if (typeStr == null) return new IdlType.Scoped("unknown");
        String t = typeStr.trim();
        switch (t) {
            case "float":               return new IdlType.Primitive(PrimitiveKind.FLOAT);
            case "double":              return new IdlType.Primitive(PrimitiveKind.DOUBLE);
            case "long double":         return new IdlType.Primitive(PrimitiveKind.LONG_DOUBLE);
            case "boolean":             return new IdlType.Primitive(PrimitiveKind.BOOLEAN);
            case "char":                return new IdlType.Primitive(PrimitiveKind.CHAR);
            case "wchar":               return new IdlType.Primitive(PrimitiveKind.WIDE_CHAR);
            case "octet":               return new IdlType.Primitive(PrimitiveKind.OCTET);
            case "short":               return new IdlType.Primitive(PrimitiveKind.SHORT);
            case "long":                return new IdlType.Primitive(PrimitiveKind.LONG);
            case "long long":           return new IdlType.Primitive(PrimitiveKind.LONG_LONG);
            case "unsigned short":      return new IdlType.Primitive(PrimitiveKind.UNSIGNED_SHORT);
            case "unsigned long":       return new IdlType.Primitive(PrimitiveKind.UNSIGNED_LONG);
            case "unsigned long long":  return new IdlType.Primitive(PrimitiveKind.UNSIGNED_LONG_LONG);
            case "int8":                return new IdlType.Primitive(PrimitiveKind.INT8);
            case "uint8":               return new IdlType.Primitive(PrimitiveKind.UINT8);
            case "int16":               return new IdlType.Primitive(PrimitiveKind.INT16);
            case "uint16":              return new IdlType.Primitive(PrimitiveKind.UINT16);
            case "int32":               return new IdlType.Primitive(PrimitiveKind.INT32);
            case "uint32":              return new IdlType.Primitive(PrimitiveKind.UINT32);
            case "int64":               return new IdlType.Primitive(PrimitiveKind.INT64);
            case "uint64":              return new IdlType.Primitive(PrimitiveKind.UINT64);
            default:
                // fixed<D,S> — pass through as a scoped string (valid IDL literal)
                if (t.startsWith("fixed<") && t.endsWith(">")) {
                    return new IdlType.Scoped(t);
                }
                // string<N> — bounded string
                if (t.startsWith("string<") && t.endsWith(">")) {
                    try {
                        int n = Integer.parseInt(t.substring(7, t.length() - 1).trim());
                        return new IdlType.Str(OptionalInt.of(n));
                    } catch (NumberFormatException ignored) {
                        return new IdlType.Str(OptionalInt.empty());
                    }
                }
                // wstring<N> — bounded wide string
                if (t.startsWith("wstring<") && t.endsWith(">")) {
                    try {
                        int n = Integer.parseInt(t.substring(8, t.length() - 1).trim());
                        return new IdlType.WideStr(OptionalInt.of(n));
                    } catch (NumberFormatException ignored) {
                        return new IdlType.WideStr(OptionalInt.empty());
                    }
                }
                // sequence<T> or sequence<T,N>
                if (t.startsWith("sequence<") && t.endsWith(">")) {
                    String inner     = t.substring(9, t.length() - 1).trim();
                    int    lastComma = inner.lastIndexOf(',');
                    if (lastComma >= 0) {
                        String elemType = inner.substring(0, lastComma).trim();
                        String boundStr = inner.substring(lastComma + 1).trim();
                        try {
                            int bound = Integer.parseInt(boundStr);
                            return new IdlType.Sequence(parseIdlType(elemType), OptionalInt.of(bound));
                        } catch (NumberFormatException ignored) {
                            // fall through to unbounded
                        }
                    }
                    return new IdlType.Sequence(parseIdlType(inner), OptionalInt.empty());
                }
                // T[N] — single-dimension array
                if (t.endsWith("]") && t.contains("[")) {
                    int bracket = t.lastIndexOf('[');
                    String elemType = t.substring(0, bracket).trim();
                    String dimStr   = t.substring(bracket + 1, t.length() - 1).trim();
                    try {
                        int dim = Integer.parseInt(dimStr);
                        return new IdlType.Array(parseIdlType(elemType), List.of(dim));
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
                return new IdlType.Scoped(t);
        }
    }
}

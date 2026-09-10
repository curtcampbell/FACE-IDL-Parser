package com.warhex.er.generator.codegen.helpers;

/**
 * A single {@code FieldDescriptor} row for an EntityReactor entity type.
 *
 * <p>Produced by
 * {@link EntityReactorHelper#flatFields(com.warhex.er.generator.ast.StructNode)}.
 * There is exactly one {@code FlatField} per {@code FieldDescriptor} in the
 * C++ registration call: composite container fields are expanded into one
 * {@code FlatField} per sub-member; simple fields produce exactly one row.
 *
 * <h2>Mapping to C++ FieldDescriptor fields</h2>
 * <pre>
 *   FlatField.qualifiedName    → FieldDescriptor::qualified_name
 *   FlatField.ordinal          → FieldDescriptor::position_index
 *   FlatField.compositeIndex   → FieldDescriptor::composite_index
 *                                  (-1 == COMPOSITE_INDEX_NONE for simple fields)
 *   FlatField.attributeValueType → FieldDescriptor::field_type (AttributeValueType)
 * </pre>
 *
 * <h2>Example — TrackEntity</h2>
 * <pre>
 *   qualifiedName="track_id"         ordinal=0 compositeIndex=-1 AV_GUID
 *   qualifiedName="position.latitude" ordinal=1 compositeIndex=0  AV_DOUBLE
 *   qualifiedName="position.longitude" ordinal=1 compositeIndex=1 AV_DOUBLE
 *   qualifiedName="heading_deg"       ordinal=2 compositeIndex=-1 AV_DOUBLE
 * </pre>
 */
public final class FlatField {

    /** {@code FieldDescriptor::qualified_name} — e.g. {@code "position.latitude"} */
    private final String qualifiedName;
    /** Name of the top-level IDL member — e.g. {@code "position"} */
    private final String topLevelName;
    /** Name of the sub-member, or {@code null} for simple fields. */
    private final String subName;
    /** {@code FieldDescriptor::position_index} */
    private final int    ordinal;
    /** {@code FieldDescriptor::composite_index} — {@code -1} for simple fields. */
    private final int    compositeIndex;
    /** {@code FieldDescriptor::field_type} — e.g. {@code "AV_DOUBLE"} */
    private final String attributeValueType;
    /** {@code EntityValue} factory kind — e.g. {@code "EV_DOUBLE"} */
    private final String entityValueKind;
    /** {@code true} when this is a sub-field row of a composite top-level field. */
    private final boolean compositeEntry;

    public FlatField(String  qualifiedName,
                     String  topLevelName,
                     String  subName,
                     int     ordinal,
                     int     compositeIndex,
                     String  attributeValueType,
                     String  entityValueKind,
                     boolean compositeEntry) {
        this.qualifiedName      = qualifiedName;
        this.topLevelName       = topLevelName;
        this.subName            = subName;
        this.ordinal            = ordinal;
        this.compositeIndex     = compositeIndex;
        this.attributeValueType = attributeValueType;
        this.entityValueKind    = entityValueKind;
        this.compositeEntry     = compositeEntry;
    }

    // -------------------------------------------------------------------
    // Accessors (Velocity-compatible)
    // -------------------------------------------------------------------

    public String  getQualifiedName()      { return qualifiedName; }
    public String  getTopLevelName()       { return topLevelName; }
    /** {@code null} for simple (non-composite-sub) fields. */
    public String  getSubName()            { return subName; }
    public int     getOrdinal()            { return ordinal; }
    /**
     * Composite index for sub-fields, or {@code -1} (COMPOSITE_INDEX_NONE) for
     * simple fields.  Templates may emit {@code static_cast<uint32_t>(-1)} for
     * the {@code -1} sentinel.
     */
    public int     getCompositeIndex()     { return compositeIndex; }
    public String  getAttributeValueType() { return attributeValueType; }
    public String  getEntityValueKind()    { return entityValueKind; }
    /** {@code true} when this row is a sub-member of a composite field. */
    public boolean isCompositeEntry()      { return compositeEntry; }

    /** Convenience: {@code true} when compositeIndex == -1 (simple field). */
    public boolean isSimple() { return compositeIndex == -1; }

    /**
     * Returns the {@code FieldDescriptor::position_index} value:
     * the field's index within its <em>immediate</em> containing struct.
     *
     * <ul>
     *   <li>For simple (non-composite-sub) fields: equals {@link #getOrdinal()}, i.e.
     *       the field's position in the top-level entity struct.</li>
     *   <li>For composite sub-fields: equals {@link #getCompositeIndex()}, i.e.
     *       the sub-field's position within the nested sub-struct.</li>
     * </ul>
     */
    public int getPositionIndex() {
        return compositeEntry ? compositeIndex : ordinal;
    }

    @Override
    public String toString() {
        return "FlatField{" + qualifiedName
                + " ord=" + ordinal
                + (compositeIndex >= 0 ? " ci=" + compositeIndex : "")
                + " " + attributeValueType + "}";
    }
}

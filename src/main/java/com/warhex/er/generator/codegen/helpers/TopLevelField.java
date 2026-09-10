package com.warhex.er.generator.codegen.helpers;

import java.util.List;

/**
 * One top-level member of an IDL struct, enriched with EntityReactor metadata.
 *
 * <p>Produced by {@link EntityReactorHelper#topLevelFields(com.warhex.er.generator.ast.StructNode)}.
 * Each instance corresponds to one member declared directly in the struct; the
 * {@code ordinal} matches {@code FieldDescriptor::position_index}.
 *
 * <h2>Composite fields</h2>
 * When {@link #isComposite()} is {@code true} the IDL type resolves to a nested
 * struct.  The field does not get its own {@code FieldDescriptor} row; instead
 * each {@link SubField} in {@link #getSubFields()} generates a row with the
 * same {@code position_index} and an increasing {@code composite_index}.
 *
 * <h2>Simple fields</h2>
 * When {@link #isComposite()} is {@code false}, {@code subFields} is empty,
 * and {@link #getAttributeValueType()} / {@link #getEntityValueKind()} hold the
 * concrete type tags for the single {@code FieldDescriptor} row.
 */
public final class TopLevelField {

    private final String       name;
    private final int          ordinal;
    private final boolean      composite;
    private final String       idlTypeName;      // raw IDL type string, e.g. "GeoPosition"
    private final String       attributeValueType; // null when composite
    private final String       entityValueKind;    // "EV_COMPOSITE" when composite
    private final List<SubField> subFields;        // empty when not composite

    public TopLevelField(String        name,
                         int           ordinal,
                         boolean       composite,
                         String        idlTypeName,
                         String        attributeValueType,
                         String        entityValueKind,
                         List<SubField> subFields) {
        this.name              = name;
        this.ordinal           = ordinal;
        this.composite         = composite;
        this.idlTypeName       = idlTypeName;
        this.attributeValueType = attributeValueType;
        this.entityValueKind   = entityValueKind;
        this.subFields         = List.copyOf(subFields);
    }

    // -------------------------------------------------------------------
    // Accessors (Velocity-compatible via $field.xxx property resolution)
    // -------------------------------------------------------------------

    public String         getName()               { return name; }
    public int            getOrdinal()            { return ordinal; }
    public boolean        isComposite()           { return composite; }
    public String         getIdlTypeName()        { return idlTypeName; }
    /** {@code null} when {@link #isComposite()} is {@code true}. */
    public String         getAttributeValueType() { return attributeValueType; }
    /** {@code "EV_COMPOSITE"} when {@link #isComposite()} is {@code true}. */
    public String         getEntityValueKind()    { return entityValueKind; }
    public List<SubField> getSubFields()          { return subFields; }

    @Override
    public String toString() {
        return "TopLevelField{" + name + "[" + ordinal + "] "
                + (composite ? "COMPOSITE(" + subFields.size() + " sub-fields)" : entityValueKind)
                + "}";
    }
}

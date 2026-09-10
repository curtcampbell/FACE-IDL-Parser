package com.warhex.er.generator.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes one field of a Platform Entity struct (or one field of a nested
 * struct such as GeoPosition).
 *
 * <p>Made available in templates as elements of {@code $entity.fields}.
 *
 * <p>The {@link #attributeValueType} string matches the {@code AttributeValueType}
 * enum defined in {@code SubscriptionRequest.idl} and ERS Appendix D
 * (e.g., {@code "AV_FLOAT"}, {@code "AV_GUID"}).  For nested composite fields
 * the value is {@code null} and {@link #nested} is {@code true}.
 */
public class FieldDescriptor {

    /** Field name as it appears in the IDL struct declaration. */
    private String name;

    /**
     * IDL type string as it should appear in the generated IDL file
     * (e.g., {@code "float"}, {@code "::FACE::GUID_TYPE"}, {@code "GeoPosition"}).
     */
    private String idlType;

    /**
     * AttributeValueType enum label from ERS Appendix D
     * (e.g., {@code "AV_FLOAT"}, {@code "AV_GUID"}).
     * {@code null} for nested composite (struct) fields.
     */
    private String attributeValueType;

    /**
     * Zero-based position index scoped to the immediately containing struct
     * [ER-083].
     */
    private int positionIndex;

    /**
     * {@code true} if this field is itself a nested struct (e.g., GeoPosition).
     * When {@code true}, {@link #nestedFields} carries the sub-fields and
     * {@link #attributeValueType} is {@code null}.
     */
    private boolean nested;

    /**
     * Sub-fields when {@link #nested} is {@code true}; empty otherwise.
     */
    private List<FieldDescriptor> nestedFields = new ArrayList<>();

    /**
     * IDL array dimension suffix (e.g. {@code "[10]"}) emitted immediately
     * after the field name in the struct declaration.  Empty string for scalars.
     */
    private String arrayDimension = "";

    /** IDL-7: true when the FACE XMI composition carries an OptionalAnnotation. */
    private boolean optional = false;

    /** Optional trailing comment for the IDL field line. */
    private String comment;

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIdlType() { return idlType; }
    public void setIdlType(String idlType) { this.idlType = idlType; }

    public String getAttributeValueType() { return attributeValueType; }
    public void setAttributeValueType(String attributeValueType) {
        this.attributeValueType = attributeValueType;
    }

    public int getPositionIndex() { return positionIndex; }
    public void setPositionIndex(int positionIndex) { this.positionIndex = positionIndex; }

    public boolean isNested() { return nested; }
    public void setNested(boolean nested) { this.nested = nested; }

    public List<FieldDescriptor> getNestedFields() { return nestedFields; }
    public void setNestedFields(List<FieldDescriptor> nestedFields) {
        this.nestedFields = nestedFields;
    }

    public String getArrayDimension() { return arrayDimension != null ? arrayDimension : ""; }
    public void setArrayDimension(String arrayDimension) {
        this.arrayDimension = arrayDimension != null ? arrayDimension : "";
    }

    /** IDL-7: true when this field carries a FACE OptionalAnnotation. */
    public boolean isOptional() { return optional; }
    public void setOptional(boolean optional) { this.optional = optional; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    // -----------------------------------------------------------------------
    // Derived helpers (called from Velocity templates)
    // -----------------------------------------------------------------------

    /**
     * Returns the parenthetical annotation used in the file header's
     * field-index table.
     *
     * <p>For nested composite fields: {@code "(nested GeoPosition — leaf fields indexed within GeoPosition)"}
     * <br>For scalar fields: {@code "(AV_FLOAT)"}
     */
    public String getPositionIndexComment() {
        if (nested) {
            return "(nested " + idlType + " — leaf fields indexed within " + idlType + ")";
        }
        return "(" + attributeValueType + ")";
    }

    @Override
    public String toString() {
        return "FieldDescriptor{name='" + name + "', idlType='" + idlType
                + "', positionIndex=" + positionIndex + "}";
    }
}

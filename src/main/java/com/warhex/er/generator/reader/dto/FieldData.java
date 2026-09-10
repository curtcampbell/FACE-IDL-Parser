package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Format-neutral DTO for a single struct field as read from the source file.
 *
 * <p>Scalar fields have a non-null {@code idlType} and optionally an
 * {@code attributeValueType}.  Nested struct fields set {@code nested = true}
 * and populate {@code nestedFields} instead.
 */
public class FieldData {

    /** IDL field name, e.g. {@code "entityId"} or {@code "position"}. */
    private String name;

    /**
     * IDL type string, e.g. {@code "float"}, {@code "::FACE::GUID_TYPE"},
     * {@code "GeoPosition"}.  For nested fields this is the name of the
     * nested struct type.
     */
    private String idlType;

    /**
     * FACE attribute-value type tag, e.g. {@code "AV_FLOAT"}.
     * {@code null} for nested struct fields.
     */
    private String attributeValueType;

    /**
     * {@code true} when this field expands to an embedded sub-struct whose
     * leaf fields carry the position indices.
     */
    private boolean nested;

    /**
     * Child fields when {@code nested = true}.  Leaf fields within a nested
     * struct carry {@code positionIndex} values; the parent field itself does
     * not have a position index.
     */
    private List<FieldData> nestedFields = new ArrayList<>();

    /**
     * IDL array dimension suffix, e.g. {@code "[10]"} for a fixed array.
     * Empty string means the field is a plain scalar (no suffix).
     * Populated by {@link com.warhex.er.generator.reader.face.FaceXmiDocument#resolvePrimitiveIdlType}
     * for {@code platform:CharArray} / {@code platform:Array} types, and by
     * {@code FaceTssReader.applyDeRef()} for DE_REF3 multiplicity.
     */
    private String arrayDimension = "";

    /**
     * IDL-7: true when the FACE XMI composition carries an OptionalAnnotation.
     * When true the field is emitted as {@code Opt_<idlType>} in the struct body
     * and a corresponding {@code typedef sequence<idlType, 1> Opt_<idlType>;} is
     * emitted before the struct.
     */
    private boolean optional = false;

    /** Optional human-readable comment placed in the generated IDL. */
    private String comment;

    /**
     * When this field's {@code idlType} refers to a named platform typedef
     * (e.g. {@code FACE::DM::Data_Model::Identifier_UUID_String}), this carries
     * the IDL {@code #include} path needed to bring it in scope
     * (e.g. {@code "FACE/DM/Data_Model/Identifier_UUID_String.idl"}).
     * {@code null} for fields whose type is a primitive or a same-model entity.
     */
    private String idlIncludePath;

    /**
     * When this field's type is another {@code uop:Template} or
     * {@code uop:CompositeTemplate}, this carries that element's {@code xmi:id}.
     * {@code null} for primitive, platform-typedef and inline-nested fields.
     *
     * <p>Retained so that inter-model type references can be resolved
     * <em>after</em> every type has been stamped with its defining root
     * UoPModel — see {@code FaceTssReader} Pass 2b/2c.  FACE Technical
     * Standard 3.2 §J.8 requires an inter-model type reference to be emitted
     * as an IDL module-scoped name.
     */
    private String templateTypeUuid;

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getTemplateTypeUuid() { return templateTypeUuid; }
    public void setTemplateTypeUuid(String templateTypeUuid) {
        this.templateTypeUuid = templateTypeUuid;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIdlType() { return idlType; }
    public void setIdlType(String idlType) { this.idlType = idlType; }

    public String getAttributeValueType() { return attributeValueType; }
    public void setAttributeValueType(String attributeValueType) {
        this.attributeValueType = attributeValueType;
    }

    public boolean isNested() { return nested; }
    public void setNested(boolean nested) { this.nested = nested; }

    public List<FieldData> getNestedFields() { return nestedFields; }
    public void setNestedFields(List<FieldData> nestedFields) {
        this.nestedFields = nestedFields != null ? nestedFields : new ArrayList<>();
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

    public String getIdlIncludePath() { return idlIncludePath; }
    public void setIdlIncludePath(String idlIncludePath) { this.idlIncludePath = idlIncludePath; }
}

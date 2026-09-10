package com.warhex.er.generator.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Describes one Platform Entity within an {@link EntityModel}.
 *
 * <p>An EntityDescriptor is populated from the entity source (format TBD)
 * and made available to Velocity templates as elements of {@code $model.entities}.
 *
 * <p>The {@link #typeEnumValue} is assigned by {@link EntityModel} after the
 * entity list is sorted alphabetically — it equals the 1-based position of
 * this entity in that sorted order, per [ER-071].
 */
public class EntityDescriptor {

    /**
     * Simple (unqualified) name of this Platform Entity (e.g., {@code "Track"}).
     * Used to derive the struct name, enum label, and IDL file name.
     */
    private String simpleName;

    /**
     * Fully qualified package path in the data model
     * (e.g., {@code "WARHEX.SampleModel.Entities.Track"}).
     */
    private String qualifiedPath;

    /**
     * Assigned EntityTypeEnum integer value (1-based, alphabetical order).
     * Set by the loader after the entity list is sorted; not read from the
     * entity source directly.
     */
    private int typeEnumValue;

    /**
     * Human-readable one-line description of this entity
     * (e.g., {@code "A moving track — an aircraft, surface vehicle, or sensor-detected target."}).
     * Emitted in the file header comment and as the struct doc comment.
     */
    private String description;

    /**
     * Relative include paths that must be emitted for this entity's IDL file,
     * in addition to {@code <FACE/Common.idl>} which is always included.
     *
     * <p>Example: {@code ["GeoPosition.idl"]} for entities that have a nested
     * GeoPosition field.  The loader populates this list; the template emits
     * each entry as {@code #include "<path>"}.
     */
    private List<String> includes = new ArrayList<>();

    /**
     * Supporting enumeration types defined in the same IDL file as this entity.
     * Emitted inside the module block, before the struct declaration.
     *
     * <p>Example: {@code ThreatLevel} for {@code ThreatEntity}.
     */
    private List<EnumDescriptor> supportingEnums = new ArrayList<>();

    /**
     * Ordered list of top-level fields declared on this entity struct.
     * Nested struct fields (e.g., GeoPosition) are represented as a
     * single {@link FieldDescriptor} with {@code nested == true} and
     * their own {@code nestedFields} list.
     */
    private List<FieldDescriptor> fields = new ArrayList<>();

    /**
     * When {@code true} the IDL type should be emitted as a {@code union}
     * rather than a {@code struct}.  Driven by a FACE CompositeTemplate
     * with {@code isUnion=true}.  Defaults to {@code false}.
     *
     * <p>VTL templates can branch on {@code $entity.union} (Velocity calls
     * {@code isUnion()} automatically for boolean getters prefixed with
     * {@code is}).
     */
    private boolean union;

    /**
     * When {@code true} this entity came from a {@code uop:CompositeTemplate};
     * {@code false} means it is a plain {@code uop:Template} requiring the
     * {@code module T_<name>} wrapper and typedef in the generated IDL.
     *
     * <p>VTL templates branch on {@code $entity.compositeTemplate}.
     */
    private boolean compositeTemplate;

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String getSimpleName() { return simpleName; }
    public void setSimpleName(String simpleName) { this.simpleName = simpleName; }

    public String getQualifiedPath() { return qualifiedPath; }
    public void setQualifiedPath(String qualifiedPath) { this.qualifiedPath = qualifiedPath; }

    public int getTypeEnumValue() { return typeEnumValue; }
    public void setTypeEnumValue(int typeEnumValue) { this.typeEnumValue = typeEnumValue; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getIncludes() { return includes; }
    public void setIncludes(List<String> includes) { this.includes = includes; }

    public List<EnumDescriptor> getSupportingEnums() { return supportingEnums; }
    public void setSupportingEnums(List<EnumDescriptor> supportingEnums) {
        this.supportingEnums = supportingEnums;
    }

    public List<FieldDescriptor> getFields() { return fields; }
    public void setFields(List<FieldDescriptor> fields) { this.fields = fields; }

    public boolean isUnion() { return union; }
    public void setUnion(boolean union) { this.union = union; }

    public boolean isCompositeTemplate() { return compositeTemplate; }
    public void setCompositeTemplate(boolean compositeTemplate) { this.compositeTemplate = compositeTemplate; }

    // -----------------------------------------------------------------------
    // Derived helpers (called from Velocity templates)
    // -----------------------------------------------------------------------

    /**
     * Returns the EntityTypeEnum enumerator label for this entity.
     *
     * <p>Example: {@code "Track"} → {@code "ENTITY_TYPE_TRACK"}
     */
    public String getEnumLabel() {
        return "ENTITY_TYPE_" + simpleName.toUpperCase();
    }

    /**
     * Returns the IDL union case member name (lower-case simple name).
     *
     * <p>Example: {@code "Track"} → {@code "track"}  [ER-074]
     */
    public String getUnionMemberName() {
        return simpleName.toLowerCase();
    }

    @Override
    public String toString() {
        return "EntityDescriptor{simpleName='" + simpleName
                + "', typeEnumValue=" + typeEnumValue + "}";
    }
}

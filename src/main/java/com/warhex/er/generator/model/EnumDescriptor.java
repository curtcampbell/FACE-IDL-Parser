package com.warhex.er.generator.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes a supporting enumeration that is defined alongside a Platform Entity struct.
 *
 * <p>Supporting enums are emitted inside the same module block as the entity struct,
 * immediately before the struct declaration — matching the pattern in
 * {@code ThreatEntity.idl} where {@code ThreatLevel} precedes {@code ThreatEntity}.
 *
 * <p>Per ERS Appendix D, IDL {@code enum} fields map to {@code AV_INT64} and are
 * treated as their underlying integer type by the Query Compiler and Comparator.
 *
 * <p>Made available in templates as elements of {@code $entity.supportingEnums}.
 */
public class EnumDescriptor {

    /** IDL enum name (e.g., {@code "ThreatLevel"}). */
    private String name;

    /**
     * Doc comment describing this enumeration.
     * Emitted as a {@code //!} block before the enum declaration.
     * Multiple lines are supported (split by {@code \n}).
     */
    private String comment;

    /**
     * Ordered list of enumerators.  The first enumerator has underlying value 0,
     * the next 1, and so on (standard IDL assignment).
     */
    private List<EnumValueDescriptor> values = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public List<EnumValueDescriptor> getValues() { return values; }
    public void setValues(List<EnumValueDescriptor> values) { this.values = values; }

    @Override
    public String toString() {
        return "EnumDescriptor{name='" + name + "', values=" + values.size() + "}";
    }
}

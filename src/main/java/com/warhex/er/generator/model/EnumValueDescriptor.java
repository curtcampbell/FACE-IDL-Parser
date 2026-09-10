package com.warhex.er.generator.model;

/**
 * Describes one enumerator within an {@link EnumDescriptor}.
 *
 * <p>Made available in templates as elements of {@code $enum.values}.
 */
public class EnumValueDescriptor {

    /** Enumerator name as it appears in the IDL (e.g., {@code "THREAT_UNKNOWN"}). */
    private String name;

    /**
     * Trailing comment for this enumerator (e.g., {@code "0 — Classification not available"}).
     * May be {@code null} if no comment is needed.
     */
    private String comment;

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    @Override
    public String toString() {
        return "EnumValueDescriptor{name='" + name + "'}";
    }
}

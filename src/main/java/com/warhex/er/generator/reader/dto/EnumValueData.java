package com.warhex.er.generator.reader.dto;

/**
 * Format-neutral DTO for a single enumerator within a supporting enum
 * (e.g., {@code THREAT_UNKNOWN}).
 */
public class EnumValueData {

    /**
     * Enumerator identifier in SCREAMING_SNAKE_CASE,
     * e.g. {@code "THREAT_UNKNOWN"}.
     */
    private String name;

    /**
     * Human-readable comment describing this value, placed inline in the
     * generated IDL, e.g. {@code "0 — Classification not available"}.
     */
    private String comment;

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
}

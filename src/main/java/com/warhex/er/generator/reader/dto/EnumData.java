package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Format-neutral DTO for a supporting enum type declared alongside an entity
 * struct (e.g., {@code ThreatLevel}).
 */
public class EnumData {

    /** IDL enum type name, e.g. {@code "ThreatLevel"}. */
    private String name;

    /** Optional comment placed above the enum declaration in generated IDL. */
    private String comment;

    /** Ordered list of enumerator values. */
    private List<EnumValueData> values = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public List<EnumValueData> getValues() { return values; }
    public void setValues(List<EnumValueData> values) {
        this.values = values != null ? values : new ArrayList<>();
    }
}

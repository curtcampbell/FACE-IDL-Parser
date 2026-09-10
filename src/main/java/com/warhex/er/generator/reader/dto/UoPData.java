package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing one Unit of Portability (UoP) from the FACE UoP model.
 *
 * <p>Each UoP has a name and an ordered list of {@link ConnectionData connection
 * ports}.  The connection list drives TypedTS IDL generation: one TypedTS
 * (or TypedTSExtended) specialisation is emitted per connection into the
 * {@code uop-tss/{UoPName}/} output directory.
 */
public class UoPData {

    /**
     * Simple name of this UoP as declared in the FACE model,
     * e.g. {@code "NavigationUoP"}.
     */
    private String name;

    /**
     * XMI UUID ({@code xmi:id}) of the UoP element in the source file.
     * Retained for cross-reference resolution; not emitted in generated output.
     */
    private String uuid;

    /**
     * Ordered list of connection ports declared on this UoP.
     * The order follows document order in the source XMI.
     */
    private List<ConnectionData> connections = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public List<ConnectionData> getConnections() { return connections; }
    public void setConnections(List<ConnectionData> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "UoPData{name='" + name + "', connections=" + connections.size() + "}";
    }
}

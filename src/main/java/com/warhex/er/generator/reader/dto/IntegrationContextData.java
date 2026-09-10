package com.warhex.er.generator.reader.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * DTO representing one {@code integration:IntegrationContext} from the FACE
 * integration model ({@code <im>} subtree of a {@code .face} XMI file).
 *
 * <p>An {@code IntegrationContext} is the integration-model boundary for a single
 * transport-service instance.  Every UoP connection that is wired through a
 * given transport service appears as a scoped entry in exactly one
 * {@code IntegrationContext}.
 *
 * <h2>Traceability chain (design spec §DQ1)</h2>
 * <pre>
 *   IntegrationContext
 *     └─ connection (0..*) ──► TSNodeConnection
 *           └─ source / destination ──► UoPEndPoint
 *                 └─ connection ──────► face.uop.Connection UUID
 *                                           (resolved to a {@link ConnectionData})
 *     └─ node (0..*) ──► ViewTransporter
 *           └─ channel (1) ──► TransportChannel
 *                                 └─ name ──► {@link #transportChannelName}
 * </pre>
 *
 * <p>{@link UoPModelData} holds a flat list of all {@code IntegrationContextData}
 * objects across all UoPs; the {@link #uopName} field back-references the
 * owning UoP by its {@link UoPData#getName()}.
 *
 * <p>{@link #connections} preserves the order the UoP connections appear in the
 * source XMI (document order, as inherited from the {@link UoPData} connection
 * list).  Only connections that appear in at least one {@code TSNodeConnection}
 * owned by this {@code IntegrationContext} are included.
 */
public class IntegrationContextData {

    /**
     * Name of this integration context as declared in the FACE model,
     * e.g. {@code "NavDataIC"}.
     */
    private String name;

    /**
     * Name of the {@code TransportChannel} reached via this IC's
     * {@code ViewTransporter} child, e.g. {@code "DDS_TS"}.
     * May be {@code null} if the IC has no {@code ViewTransporter} or the
     * channel cannot be resolved (logged as a warning).
     */
    private String transportChannelName;

    /**
     * Name of the UoP type realized by the {@code UoPInstance} wired through
     * this IC, e.g. {@code "AOIPublisher"}.
     * Matches {@link UoPData#getName()} of the owning UoP.
     */
    private String uopName;

    /**
     * Ordered list of UoP connections wired through this integration context.
     * Each entry is the same {@link ConnectionData} object reference that
     * appears in the corresponding {@link UoPData#getConnections()} list, so
     * no information is duplicated.
     */
    private List<ConnectionData> connections = new ArrayList<>();

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTransportChannelName() { return transportChannelName; }
    public void setTransportChannelName(String transportChannelName) {
        this.transportChannelName = transportChannelName;
    }

    public String getUopName() { return uopName; }
    public void setUopName(String uopName) { this.uopName = uopName; }

    public List<ConnectionData> getConnections() { return connections; }
    public void setConnections(List<ConnectionData> connections) {
        this.connections = connections != null ? connections : new ArrayList<>();
    }

    @Override
    public String toString() {
        return "IntegrationContextData{name='" + name
                + "', uopName='" + uopName
                + "', transportChannelName='" + transportChannelName
                + "', connections=" + connections.size() + "}";
    }
}

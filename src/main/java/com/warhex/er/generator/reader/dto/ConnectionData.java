package com.warhex.er.generator.reader.dto;

/**
 * DTO representing one connection port on a FACE UoP.
 *
 * <p>A connection associates a {@link TssTypeData message type} with:
 * <ul>
 *   <li>a structural {@link ConnectionKind} (pub/sub or client/server)</li>
 *   <li>the {@link ConnectionRole role} this UoP plays on the connection</li>
 *   <li>a derived {@link TypedTsVariant} that determines which TypedTS
 *       interface variant is generated</li>
 * </ul>
 *
 * <p>The {@code typedTsVariant} field is always derivable from {@code kind}
 * via {@link TypedTsVariant#fromConnectionKind(ConnectionKind)}, but it is
 * materialised here for convenience in templates and adapters.
 */
public class ConnectionData {

    /**
     * Name of the connection port as declared in the FACE model,
     * e.g. {@code "Conn_PV1_out"}.
     */
    private String name;

    /**
     * XMI UUID of this connection element.  Retained for cross-reference
     * resolution; not emitted in generated output.
     */
    private String uuid;

    /** Structural kind of this connection. */
    private ConnectionKind kind;

    /** Role this UoP plays on the connection. */
    private ConnectionRole role;

    /**
     * TypedTS interface variant to generate — derived from {@link #kind}
     * but materialised for template/adapter convenience.
     */
    private TypedTsVariant typedTsVariant;

    /**
     * The platform data type that flows through this connection.
     * For pub/sub connections this is the sole message type.
     * For {@link ConnectionKind#CLIENT_SERVER} connections this is the
     * <em>request</em> type (the type sent by the REQUESTER side).
     * Object reference into the shared type registry held by
     * {@link UoPModelData#getPlatformTypes()}.
     */
    private TssTypeData messageType;

    /**
     * For {@link ConnectionKind#CLIENT_SERVER} connections only: the
     * <em>response</em> type (the type returned by the RESPONDER side).
     * {@code null} for all pub/sub connection kinds.
     * Object reference into the shared type registry held by
     * {@link UoPModelData#getPlatformTypes()}.
     */
    private TssTypeData responseMessageType;

    // -----------------------------------------------------------------------
    // Getters / setters
    // -----------------------------------------------------------------------

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public ConnectionKind getKind() { return kind; }
    public void setKind(ConnectionKind kind) {
        this.kind = kind;
        // Keep variant in sync when kind is set
        if (kind != null) {
            this.typedTsVariant = TypedTsVariant.fromConnectionKind(kind);
        }
    }

    public ConnectionRole getRole() { return role; }
    public void setRole(ConnectionRole role) { this.role = role; }

    public TypedTsVariant getTypedTsVariant() { return typedTsVariant; }
    /** Normally derived automatically by {@link #setKind}; exposed for explicit override. */
    public void setTypedTsVariant(TypedTsVariant typedTsVariant) {
        this.typedTsVariant = typedTsVariant;
    }

    public TssTypeData getMessageType() { return messageType; }
    public void setMessageType(TssTypeData messageType) { this.messageType = messageType; }

    public TssTypeData getResponseMessageType() { return responseMessageType; }
    public void setResponseMessageType(TssTypeData responseMessageType) {
        this.responseMessageType = responseMessageType;
    }

    @Override
    public String toString() {
        return "ConnectionData{name='" + name
                + "', kind=" + kind
                + ", role=" + role
                + ", variant=" + typedTsVariant
                + ", messageType=" + (messageType != null ? messageType.getName() : "null")
                + ", responseMessageType=" + (responseMessageType != null ? responseMessageType.getName() : "null")
                + "}";
    }
}

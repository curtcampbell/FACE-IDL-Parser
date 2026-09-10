package com.warhex.er.generator.reader.dto;

/**
 * The role this UoP plays on a connection — i.e., which side of the
 * communication it initiates or services.
 *
 * <p>For pub/sub connections ({@link ConnectionKind#QUEUING},
 * {@link ConnectionKind#SINGLE_INSTANCE}): {@link #PRODUCER} or
 * {@link #CONSUMER}.
 *
 * <p>For client/server connections ({@link ConnectionKind#CLIENT_SERVER}):
 * {@link #REQUESTER} or {@link #RESPONDER}.
 */
public enum ConnectionRole {

    /** Pub/sub sender — writes data onto the connection. */
    PRODUCER,

    /** Pub/sub receiver — reads data from the connection. */
    CONSUMER,

    /** Client/server initiator — sends a request and awaits a reply. */
    REQUESTER,

    /** Client/server handler — receives a request and sends a reply. */
    RESPONDER
}

package com.warhex.er.generator.reader.dto;

/**
 * The structural kind of a FACE UoP connection port.
 *
 * <p>Pub/sub variants ({@link #QUEUING} and {@link #SINGLE_INSTANCE}) map to
 * the standard {@code FACE::TS::TypedTS} interface.  {@link #CLIENT_SERVER}
 * maps to {@code FACE::TS::TypedTSExtended}.
 *
 * @see TypedTsVariant
 */
public enum ConnectionKind {

    /**
     * Queuing message port — pub/sub, multiple messages buffered.
     * Corresponds to FACE {@code QueuingMessagePort}.
     */
    QUEUING,

    /**
     * Single-instance message port — pub/sub, only the latest value retained.
     * Corresponds to FACE {@code SingleInstanceMessagePort}.
     */
    SINGLE_INSTANCE,

    /**
     * Client/server connection — request/response semantics.
     * Corresponds to FACE {@code ClientServerConnection}.
     */
    CLIENT_SERVER
}

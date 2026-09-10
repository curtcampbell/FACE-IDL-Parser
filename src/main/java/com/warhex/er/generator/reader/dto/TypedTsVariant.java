package com.warhex.er.generator.reader.dto;

/**
 * Indicates which TypedTS IDL interface variant to generate for a connection.
 *
 * <ul>
 *   <li>{@link #STANDARD} — {@code FACE::TS::TypedTS<T>}; used for all
 *       pub/sub connection kinds ({@link ConnectionKind#QUEUING},
 *       {@link ConnectionKind#SINGLE_INSTANCE}).</li>
 *   <li>{@link #EXTENDED} — {@code FACE::TS::TypedTSExtended<T>}; used for
 *       {@link ConnectionKind#CLIENT_SERVER} connections.</li>
 * </ul>
 */
public enum TypedTsVariant {

    /** Standard pub/sub TypedTS interface. */
    STANDARD,

    /** Extended client/server TypedTS interface. */
    EXTENDED;

    /**
     * Derives the correct variant from a {@link ConnectionKind}.
     *
     * @param kind the connection kind; must not be {@code null}
     * @return {@link #EXTENDED} for {@code CLIENT_SERVER}, {@link #STANDARD} otherwise
     */
    public static TypedTsVariant fromConnectionKind(ConnectionKind kind) {
        return kind == ConnectionKind.CLIENT_SERVER ? EXTENDED : STANDARD;
    }
}

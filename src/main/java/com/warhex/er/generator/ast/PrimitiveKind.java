package com.warhex.er.generator.ast;

/**
 * IDL primitive type names, corresponding to Table 14 of FACE TS 3.2 §4.14.8.7.1
 * and the OMG IDL 4.1 base type specification.
 *
 * <p>Each constant corresponds to an IDL keyword or keyword combination.
 * Language mappers use this to drive their type tables.
 */
public enum PrimitiveKind {
    SHORT,
    LONG,
    LONG_LONG,
    UNSIGNED_SHORT,
    UNSIGNED_LONG,
    UNSIGNED_LONG_LONG,
    FLOAT,
    DOUBLE,
    LONG_DOUBLE,
    BOOLEAN,
    CHAR,
    WIDE_CHAR,
    OCTET,
    ANY,
    VOID,
    // IDL 4.x fixed-width integer aliases
    INT8,
    UINT8,
    INT16,
    UINT16,
    INT32,
    UINT32,
    INT64,
    UINT64
}

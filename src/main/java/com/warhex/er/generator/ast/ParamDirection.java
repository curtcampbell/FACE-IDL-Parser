package com.warhex.er.generator.ast;

/**
 * IDL parameter direction, corresponding to §4.14.8.10.2 of FACE TS 3.2.
 *
 * <p>Language mappers use the direction together with the parameter type to
 * select the correct C++ passing convention:
 * <ul>
 *   <li>{@link #IN}    — by value (primitive/enum) or {@code const T&} (struct)</li>
 *   <li>{@link #OUT}   — {@code T&}</li>
 *   <li>{@link #INOUT} — {@code T&} (non-interface) or {@code I**} (interface)</li>
 * </ul>
 */
public enum ParamDirection {
    IN,
    OUT,
    INOUT
}

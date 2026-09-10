package com.warhex.er.generator.ast;

import java.util.List;
import java.util.OptionalInt;

/**
 * Sealed type hierarchy representing IDL type references in the {@link IdlAst}.
 *
 * <p>All concrete variants are static inner classes, so a language mapper can
 * dispatch with {@code instanceof}:
 *
 * <pre>{@code
 * if (type instanceof IdlType.Primitive p) {
 *     // use p.kind()
 * } else if (type instanceof IdlType.Scoped s) {
 *     // use s.qualifiedName()
 * }
 * }</pre>
 *
 * <h3>Variant summary</h3>
 * <ul>
 *   <li>{@link Primitive} — built-in IDL primitive (short, long, boolean, …)</li>
 *   <li>{@link Scoped}    — reference to a named type (struct, enum, typedef, …)</li>
 *   <li>{@link Sequence}  — {@code sequence<T>} or bounded {@code sequence<T,N>}</li>
 *   <li>{@link Str}       — {@code string} or bounded {@code string<N>}</li>
 *   <li>{@link WideStr}   — {@code wstring} or bounded {@code wstring<N>}</li>
 *   <li>{@link Array}     — fixed-size array declarator (one or more dimensions)</li>
 *   <li>{@link Void}      — operation return type {@code void}</li>
 * </ul>
 */
public abstract class IdlType {

    // Prevent external subclassing outside this file
    private IdlType() {}

    // -------------------------------------------------------------------------
    // Primitive — short, long, boolean, float, …
    // -------------------------------------------------------------------------

    /** IDL built-in primitive type. */
    public static final class Primitive extends IdlType {
        private final PrimitiveKind kind;

        public Primitive(PrimitiveKind kind) {
            this.kind = kind;
        }

        public PrimitiveKind kind() { return kind; }

        @Override
        public String toString() { return kind.name().toLowerCase().replace('_', ' '); }
    }

    // -------------------------------------------------------------------------
    // Scoped — reference to a named type by scoped name
    // -------------------------------------------------------------------------

    /**
     * Reference to a named type — struct, enum, union, typedef, interface.
     * The name may be fully qualified ({@code ::FACE::GUID_TYPE}) or relative.
     */
    public static final class Scoped extends IdlType {
        private final String qualifiedName;

        public Scoped(String qualifiedName) {
            this.qualifiedName = qualifiedName;
        }

        public String qualifiedName() { return qualifiedName; }

        @Override
        public String toString() { return qualifiedName; }
    }

    // -------------------------------------------------------------------------
    // Sequence — sequence<T> or sequence<T, N>
    // -------------------------------------------------------------------------

    /** IDL {@code sequence<T>} or bounded {@code sequence<T, N>}. */
    public static final class Sequence extends IdlType {
        private final IdlType elementType;
        private final OptionalInt bound;       // absent → unbounded

        public Sequence(IdlType elementType, OptionalInt bound) {
            this.elementType = elementType;
            this.bound        = bound;
        }

        /** Convenience: unbounded sequence. */
        public Sequence(IdlType elementType) {
            this(elementType, OptionalInt.empty());
        }

        public IdlType elementType() { return elementType; }
        public OptionalInt bound()   { return bound; }

        @Override
        public String toString() {
            return bound.isPresent()
                    ? "sequence<" + elementType + ", " + bound.getAsInt() + ">"
                    : "sequence<" + elementType + ">";
        }
    }

    // -------------------------------------------------------------------------
    // Str — string or string<N>
    // -------------------------------------------------------------------------

    /** IDL {@code string} or bounded {@code string<N>}. */
    public static final class Str extends IdlType {
        private final OptionalInt bound;

        public Str(OptionalInt bound) { this.bound = bound; }

        /** Convenience: unbounded string. */
        public Str() { this(OptionalInt.empty()); }

        public OptionalInt bound() { return bound; }

        @Override
        public String toString() {
            return bound.isPresent() ? "string<" + bound.getAsInt() + ">" : "string";
        }
    }

    // -------------------------------------------------------------------------
    // WideStr — wstring or wstring<N>
    // -------------------------------------------------------------------------

    /** IDL {@code wstring} or bounded {@code wstring<N>}. */
    public static final class WideStr extends IdlType {
        private final OptionalInt bound;

        public WideStr(OptionalInt bound) { this.bound = bound; }

        /** Convenience: unbounded wstring. */
        public WideStr() { this(OptionalInt.empty()); }

        public OptionalInt bound() { return bound; }

        @Override
        public String toString() {
            return bound.isPresent() ? "wstring<" + bound.getAsInt() + ">" : "wstring";
        }
    }

    // -------------------------------------------------------------------------
    // Array — fixed-size multidimensional array declarator
    // -------------------------------------------------------------------------

    /**
     * Fixed-size array.  Corresponds to IDL complex declarator syntax
     * {@code type name[d1][d2]…}.
     *
     * <p>Note: in IDL, array dimensions are part of the declarator, not the
     * type.  The AST lifts them into the type for cleaner downstream handling.
     */
    public static final class Array extends IdlType {
        private final IdlType elementType;
        private final List<Integer> dimensions;   // one entry per [] dimension

        public Array(IdlType elementType, List<Integer> dimensions) {
            this.elementType = elementType;
            this.dimensions  = List.copyOf(dimensions);
        }

        public IdlType elementType()    { return elementType; }
        public List<Integer> dimensions() { return dimensions; }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(elementType.toString());
            for (int d : dimensions) sb.append('[').append(d).append(']');
            return sb.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Void — operation return type
    // -------------------------------------------------------------------------

    /** Represents the IDL {@code void} return type of an operation. */
    public static final class Void extends IdlType {
        public static final Void INSTANCE = new Void();
        private Void() {}

        @Override
        public String toString() { return "void"; }
    }
}

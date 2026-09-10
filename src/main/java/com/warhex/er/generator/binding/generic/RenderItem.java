package com.warhex.er.generator.binding.generic;

import com.warhex.er.generator.ast.EnumNode;
import com.warhex.er.generator.ast.InterfaceNode;
import com.warhex.er.generator.ast.StructNode;

import java.util.Set;

/**
 * Container for the inner classes used as render items in the {@code per_idl_file}
 * iteration strategy.
 *
 * <p>Instances of the inner classes are placed in the {@code $items} list
 * in the Velocity context.  Templates dispatch on {@code $item.class.simpleName}
 * (e.g. {@code "NsOpen"}, {@code "StructItem"}) to decide what to emit.
 *
 * <p>The inner classes are generalised from {@code CppLanguageMapper} — they are
 * not C++-specific and can be used by any language that adopts the
 * {@code per_idl_file} strategy.
 *
 * <h2>Velocity uberspector note</h2>
 * Velocity 2.x resolves {@code $item.foo} by calling {@code item.getFoo()} (JavaBean
 * convention).  All inner classes therefore expose explicit getter methods — public
 * fields alone are not visible to the default Velocity uberspector.
 * {@code $item.class.simpleName} works because {@link Object#getClass()} is always
 * accessible and {@link Class#getSimpleName()} is a standard method.
 */
public final class RenderItem {

    private RenderItem() { /* not instantiated */ }

    // =========================================================================

    /** Instructs the template to open a namespace (or package / module) block. */
    public static final class NsOpen {
        private final String name;

        public NsOpen(String name) { this.name = name; }

        /** @return the namespace / module identifier, e.g. {@code "FACE"} */
        public String getName() { return name; }
    }

    // =========================================================================

    /** Instructs the template to close a namespace (or package / module) block. */
    public static final class NsClose {
        private final String name;

        public NsClose(String name) { this.name = name; }

        /** @return the namespace / module identifier being closed */
        public String getName() { return name; }
    }

    // =========================================================================

    /** Instructs the template to emit a {@code struct} (or class) declaration. */
    public static final class StructItem {
        private final StructNode node;

        public StructItem(StructNode node) { this.node = node; }

        /** @return the struct AST node */
        public StructNode getNode() { return node; }
    }

    // =========================================================================

    /** Instructs the template to emit an {@code enum} declaration. */
    public static final class EnumItem {
        private final EnumNode node;

        public EnumItem(EnumNode node) { this.node = node; }

        /** @return the enum AST node */
        public EnumNode getNode() { return node; }
    }

    // =========================================================================

    /**
     * Instructs the template to emit an interface (abstract class) declaration.
     *
     * <p>Carries the interface-detection sets needed by languages that distinguish
     * interface-typed parameters (e.g. C++ pointer/reference decoration per
     * OMG IDL-to-C++ §5.16.3.3).
     */
    public static final class InterfaceItem {
        private final InterfaceNode node;
        private final Set<String>   localInterfaces;
        private final Set<String>   interfaceKindActuals;

        public InterfaceItem(InterfaceNode node,
                             Set<String>   localInterfaces,
                             Set<String>   interfaceKindActuals) {
            this.node                 = node;
            this.localInterfaces      = localInterfaces;
            this.interfaceKindActuals = interfaceKindActuals;
        }

        /** @return the interface AST node */
        public InterfaceNode getNode() { return node; }

        /**
         * @return simple names of interfaces declared <em>inside</em> the template
         *         body (for interface-typed parameter detection)
         */
        public Set<String> getLocalInterfaces() { return localInterfaces; }

        /**
         * @return fully-qualified names of actual parameters that came from
         *         {@code interface}-kind formal parameters
         */
        public Set<String> getInterfaceKindActuals() { return interfaceKindActuals; }
    }
}

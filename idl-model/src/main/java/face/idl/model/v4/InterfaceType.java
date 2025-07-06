package face.idl.model.v4;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Vector;

public class InterfaceType extends ScopedObjectBase {

    /**
     * Represents a parameter within a method signature.
     */
    public static class Parameter {
        private final String identifier;
        private ITypeSpec parameterType;
        private ParameterAttribute parameterAttribute;

        /**
         * Constructs a new Parameter with the given identifier.
         * @param identifier The name of the parameter.
         */

        public Parameter(String identifier) {
            this.identifier = identifier;
        }

        public String getIdentifier() {
            return identifier;
        }

        /**
         * Returns the type specification of this parameter.
         * @return The type specification.
         */
        public ITypeSpec getParameterType() {
            return parameterType;
        }

        /**
         * Sets the type specification for this parameter.
         * @param parameterType The type specification to set.
         */
        public void setParameterType(ITypeSpec parameterType) {
            this.parameterType = parameterType;
        }

        /**
         * Returns the attribute of this parameter (e.g., In, Out, InOut).
         * @return The parameter attribute.
         */
        public ParameterAttribute getParameterAttribute() {
            return parameterAttribute;
        }

        /**
         * Sets the attribute for this parameter.
         * @param parameterAttribute The parameter attribute to set.
         */
        public void setParameterAttribute(ParameterAttribute parameterAttribute) {
            this.parameterAttribute = parameterAttribute;
        }

        @Override
        public int hashCode() {
            return identifier.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Parameter other = (Parameter) obj;
            return identifier.equals(other.identifier);
        }
    }

    /**
     * Defines the attribute of a parameter, indicating its direction of data flow.
     */
    public enum ParameterAttribute {
        /**
         * The parameter is an input parameter.
         */
        In,
        /**
         * The parameter is an output parameter.
         */
        Out,
        /**
         * The parameter is both an input and an output parameter.
         */
        InOut
    }

    /**
     * Represents a method within an interface.
     */
    public static class Operation {
        private final String identifier;
        private ITypeSpec returnType;

        /**
         * Constructs a new Method with the given identifier.
         * @param identifier The name of the method.
         */
        public Operation(String identifier) {
            this.identifier = identifier;
        }

        /**
         * Returns the name of this method.
         * @return The method name.
         */
        public String getIdentifier() {
            return identifier;
        }

        /**
         * Returns the return type specification of this method.
         * @return The return type specification.
         */
        public ITypeSpec getReturnType() {
            return returnType;
        }

        /**
         * Sets the return type specification for this method.
         * @param returnType The return type specification to set.
         */
        public void setReturnType(ITypeSpec returnType) {
            this.returnType = returnType;
        }

        public void addMethod (String identifier, ParameterAttribute parameterAttribute, ITypeSpec parameterType){
            Parameter parameter = new Parameter(identifier);
            parameter.setParameterAttribute(parameterAttribute);
            parameter.setParameterType(parameterType);
            parameters.add(parameter);
        }

        public Set<Parameter> getParameters() {
            return parameters;
        }

        private final Set<Parameter> parameters = new LinkedHashSet<>();
    }

    private final Vector<String> interfaceList = new Vector<>();
    private boolean isForwardDeclaration = false;

    /**
     * Constructs a new InterfaceType with the given identifier.
     * @param identifier The name of the interface.
     */
    public InterfaceType(String identifier) {
        super(identifier);
    }

    /**
     * Constructs a new InterfaceType with the given identifier and forward declaration status.
     * @param identifier The name of the interface.
     * @param isForwardDeclaration True if this is a forward declaration, false otherwise.
     */
    public InterfaceType(String identifier, boolean isForwardDeclaration) {
        super(identifier);
        this.isForwardDeclaration = isForwardDeclaration;
    }

    /**
     * Returns the kind of this scoped object, which is {@code IScopedObject.ScopedObjectKind.Interface}.
     * @return The kind of the scoped object.
     */
    @Override
    public ScopedObjectKind getKind() {
        return IScopedObject.ScopedObjectKind.Interface;
    }

    /**
     * Adds an inherited interface to this interface.
     * @param inheritanceSpec The name of the inherited interface.
     */
    public void addInheritedInterface(String inheritanceSpec) {
        this.interfaceList.add(inheritanceSpec);
    }

    /**
     * Returns a vector of strings representing the interfaces inherited by this interface.
     * @return A {@code Vector} of inherited interface names.
     */
    public Vector<String> getInheritanceInterfaces() {
        return interfaceList;
    }

    /**
     * Checks if this interface is a forward declaration.
     * @return True if it is a forward declaration, false otherwise.
     */
    public boolean isForwardDeclaration() {
        return isForwardDeclaration;
    }
}

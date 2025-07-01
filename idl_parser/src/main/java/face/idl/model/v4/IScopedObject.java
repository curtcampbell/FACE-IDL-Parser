package face.idl.model.v4;

public interface IScopedObject {
    enum ScopedObjectKind {
        Constant,
        Module,
        Typedef,
        Struct,
        Union,
        Enum,
        Interface,
        TemplateModule,
        TemplateModuleInstance,
        Exception /*Currently not supported but needed for error handling*/
    }


    String getIdentifier();

    /**
     * @return Kind of scoped object
     */
    ScopedObjectKind getKind();

    /**
     * @return true if the ID is a declarator, false otherwise
     */
    boolean getIdIsDeclarator();

    /**
     * Checks if the specified ID is contained within the scoped object.
     *
     * @param id the ID to check for existence
     * @return true if the ID exists, false otherwise
     */
    boolean containsId(String id);

    /**
     * @param id
     * @return
     * **/
    IScopedObject getObjectById(String id);

    /**
     * Checks if the specified declarator is contained within the scoped object.
     *
     * @param declarator the declarator to check for existence
     * @return true if the declarator exists, false otherwise
     */
    boolean containsDeclarator(String declarator);


    /**
     *
     * @return true if this object is a forward declaration, false otherwise
     */
    boolean isForwardDeclaration();

    void addToScope(IScopedObject newObject);
}

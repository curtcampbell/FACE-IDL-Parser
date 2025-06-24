package face.idl.model;

import face.idl.compiler.DefinitionMetaData;

import javax.swing.text.html.Option;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

public class ModuleObject extends FaceObjectBase {

    private ModuleObject(String id, ModuleObject parent) {
        super(id, parent);
    }

    @Override
    public FaceObjectType getFaceObjectType() {
        return FaceObjectType.MODULE;
    }

    public Collection<FaceObject> getContained() {
        return faceObjects;
    }


    /**
     * Reteurns a modula associated with the given id if it exists.  If not a new module with
     *  the specified id is created and returned.
     * @param id Id of the module to be returned.
     * @return Module specified by the id or a new module.
     * @throws IllegalArgumentException if the id is null.
     */
    public ModuleObject getNewOrExistingModule(String id) throws IllegalArgumentException {
       var module = getModule(id);
        return module.orElseGet(() -> {
            ids.add(id);
            var newModule = new ModuleObject(id, this);
            modules.put(id, newModule) ;
            return newModule;
         });
    }

    /**
     * Returns the module with the specified moduleId
     * @param moduleId identifier of the module to be returned.
     * @return returns the specified module is it exists or empty optional.
     */
    public Optional<ModuleObject> getModule(String moduleId) {
        var module = modules.get(moduleId);
        if(module == null) {
            return Optional.empty();
        }

        return Optional.of(module);
    }

    /**
     * Returns a newly created struct object only if the specified structId is not already defined.
     *
     * @param newId
     * @return
     */
    public Optional<StructObject> newStruct(String newId, String fileName, int lineNum, int column) {
        return genericAdd(StructObject.class, newId, fileName, lineNum, column);
    }

    public Optional<InterfaceObject> newInterfaceObject(String newId, String fileName, int lineNum, int column) {
         return genericAdd(InterfaceObject.class, newId, fileName, lineNum, column);
    }

    public Optional<UnionObject> newUnionObject(String newId, String fileName, int lineNum, int column) {
        return genericAdd(UnionObject.class, newId, fileName, lineNum, column);
    }

    public Optional<EnumObject> newEnumObject(String newId, String fileName, int lineNum, int column){
        return genericAdd(EnumObject.class, newId, fileName, lineNum, column);
    }

     <T> Optional<T> genericAdd(Class<T> classToAdd, String identifier, String fileName, int lineNum, int column) {
        if(ids.contains(identifier)) {
            return Optional.empty();
        }

         try {
             var ctor = classToAdd.getConstructor(String.class, ModuleObject.class);
             var inst = ctor.newInstance(identifier, this);

             ids.add(identifier);
             faceObjects.add((FaceObject) inst);
             definitionMetaData.put(identifier, new DefinitionMetaData(fileName, lineNum, column));
             return Optional.of(inst);

         } catch (NoSuchMethodException e) {
             throw new RuntimeException(e);
         } catch (InvocationTargetException e) {
             throw new RuntimeException(e);
         } catch (InstantiationException e) {
             throw new RuntimeException(e);
         } catch (IllegalAccessException e) {
             throw new RuntimeException(e);
         }
    }

    /**
     * Returns the metadata associated with an identifier.  This is used in the generation of useful error
     *  messages.
     * @param id
     * @return
     */
    public Optional<DefinitionMetaData> getIdMetadata(String id) {
        var metadata = definitionMetaData.get(id);
        return metadata == null ? Optional.empty()
                : Optional.of(metadata);
    }

    public FaceObject addObject(FaceObject newObject) throws IllegalArgumentException {

        if(newObject == null) throw new NullPointerException("newObject argument can not be null.");

        if(ids.stream().anyMatch(id -> Objects.equals(id, newObject.getId()))) {
            throw new IllegalArgumentException(String.format("Identifier %s is redefined.", id ));
        }

        faceObjects.add(newObject);
        ids.add(newObject.getId());

        return  newObject;
    }


    public Collection<ModuleObject> getModules(){
        return  faceObjects.stream()
                .filter(obj -> obj.getFaceObjectType() == FaceObjectType.MODULE)
                .map(obj -> (ModuleObject)obj)
                .toList();
    }

    public Collection<StructObject> getStructs() {
        return  faceObjects.stream()
                .filter(obj -> obj.getFaceObjectType() == FaceObjectType.STRUCT)
                .map(obj -> (StructObject)obj)
                .toList();
    }

    public Collection<InterfaceObject> getInterfaceObject() {
        return  faceObjects.stream()
                .filter(obj -> obj.getFaceObjectType() == FaceObjectType.INTERFACE)
                .map(obj -> (InterfaceObject)obj)
                .toList();
    }

    public Collection<UnionObject> getUnions() {
        return  faceObjects.stream()
                .filter(obj -> obj.getFaceObjectType() == FaceObjectType.UNION)
                .map(obj -> (UnionObject)obj)
                .toList();
    }

    public Collection<TypeDefObject> getTypedefs() {
        return  faceObjects.stream()
                .filter(obj -> obj.getFaceObjectType() == FaceObjectType.TYPEDEF)
                .map(obj -> (TypeDefObject)obj)
                .toList();
    }

    private ModuleObject() {
        this("global", null);
    }


    private final Set<String> ids = new HashSet<>();
    private final Map<String, DefinitionMetaData> definitionMetaData = new HashMap<>();

    private final Vector<FaceObject> faceObjects = new Vector<>();
    private final Map<String, ModuleObject> modules = new HashMap<>();

    public final static ModuleObject GLOBAL_MODULE = new ModuleObject();

    public boolean isIdDefined(String moduleId) {
        return ids.contains(moduleId);
    }

}

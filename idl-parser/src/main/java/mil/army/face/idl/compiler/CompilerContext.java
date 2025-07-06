package mil.army.face.idl.compiler;

import mil.army.face.idl.model.v4.ModuleObject;

import java.nio.file.Path;
import java.util.Optional;

public interface CompilerContext {
    Optional<Path> findIncludeFile(Path currentPath, String fileName);

    boolean parse(Path inputFile) throws Exception;

    ModuleObject getGlobalModule();

    void defineId(String newId);

    boolean isDefined(String id);
}

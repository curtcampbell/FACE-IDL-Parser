package face.idl.compiler;

import face.idl.model.ModuleObject;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

public interface CompilerContext {
    Optional<Path> findIncludeFile(Path currentPath, String fileName);

    boolean parse(Path inputFile) throws Exception;

    ModuleObject getGlobalModule();

    void defineId(String newId);

    boolean isDefined(String id);
}

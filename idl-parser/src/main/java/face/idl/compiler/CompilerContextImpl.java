package face.idl.compiler;

import face.idl.model.v4.ModuleObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 *
 */
public class CompilerContextImpl implements CompilerContext {

    public CompilerContextImpl(Path rootDirectory, List<Path> includeDirectories){
        this.includeDirectories.addAll(includeDirectories);

        if(!rootDirectory.toFile().isDirectory()) throw new IllegalArgumentException("Argument: rootDirectory is not a valid directory.");
        this.rootDirectory = rootDirectory;
    }

    @Override
    public Optional<Path> findIncludeFile(Path currentPath, String fileName) {
        if(currentPath == null)  throw new NullPointerException("Argument: localPath can not be null.");
        if(fileName == null || !fileName.trim().isEmpty()) throw new NullPointerException("Argument: file can not be null or empty.");

        if(!Files.isDirectory(currentPath)) {
            currentPath = currentPath.getFileName();
        }

        Optional<Path> foundFile = Optional.empty();
        try {

            //Look for our file in the current path first.
            foundFile = Files.walk(currentPath, 1)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .findFirst();

            //If not in the current path, then look in the include directories.
            final Path searchLocal = currentPath;
            if(foundFile.isEmpty()) {
                //Now search include directories.
                foundFile = includeDirectories.stream().filter(
                   directory -> {
                       try {
                           return Files.walk(searchLocal, 1)
                                   .filter(Files::isRegularFile)
                                   .anyMatch(path -> path.getFileName().toString().equals(fileName));
                       } catch (IOException e) {
                           throw new RuntimeException(e);
                       }
                   }
                ).findFirst();
            }
         }catch (IOException exception) {
            return Optional.empty();
        }

        return foundFile;
     }

    /**
     *
     * @param inputFiles
     * @return
     * @throws Exception
     */
    public boolean parse(Collection<Path> inputFiles) throws Exception {
        for (var inputFile : inputFiles) {
            if(!parse(inputFile)) {
                return false;
            }
        }
        return true;
    }

    /**
     *
     * @param inputFile
     * @return
     * @throws Exception
     */
    @Override
    public boolean parse(Path inputFile) throws Exception {

        //Determine if we have a real file.  If not search the include
        //directories.
        var fileToParse = findFile(inputFile);

        //Bail if we couldn't find the file.
        if(fileToParse.isEmpty()){
            throw new FileNotFoundException("Could not open the file \"%s\"".formatted(inputFile.toString()));
        }

        var parser = new Parser(this);
        return parser.parse(fileToParse.get());
    }

    @Override
    public ModuleObject getGlobalModule() {
        return globalModule;
    }

    /**
     *
     * @param newId
     */
    @Override
    public void defineId(String newId) { identifiers.add(newId); }

    /**
     *
     * @param id
     * @return
     */
    @Override
    public boolean isDefined(String id) { return identifiers.contains(id); }

    private Optional<Path> findFile(Path potentialFile) {
        var fileObj = potentialFile.toFile();

        //If file exists, then return it.
        if(fileObj.isFile() && fileObj.exists()) {
            return Optional.of(potentialFile);
        };

        //Now begin searching for the file
        var fileName = potentialFile.toString();
        for(var directory : includeDirectories) {

            var foundFile = directory.resolve(potentialFile);
            var file = foundFile.toFile();
            if(file.exists()) {
                return Optional.of(foundFile);
            }
        }

        return Optional.empty();
    }

    private final Set<String> identifiers = new HashSet<>();
    private final List<Path> includeDirectories = new Vector<Path>();
    private final Path rootDirectory;
    private final ModuleObject globalModule = ModuleObject.GLOBAL_MODULE;
}

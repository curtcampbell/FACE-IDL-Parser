package face.idl;

import face.idl.compiler.CompilerContextImpl;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;


import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.Callable;

/***
 *
 */
@Command(name = "fidl", mixinStandardHelpOptions = true, version = "fidl 1.0",
        description = "UVC FACE IDL code generator.")
public class Main implements Callable<Integer> {
    public static void main(String[] args) throws Exception {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);

    }

    @Parameters(arity = "1..*", description = "FACE IDL file to parse.")
    List<Path> files; // picocli infers type from the generic type

    @Option(names = {"-I", "--include"}, description = "directories to search for include files.")
    List<Path> includeDirs = new Vector<Path>();

    /**
     * Main entry point for this parser utility.  This is called by the command line parser.
     * @return 
     * @throws Exception
     */
    @Override
    public Integer call() throws Exception {

        Path currentPath = FileSystems.getDefault().getPath("").toAbsolutePath();

        try {
            var compilerContext = new CompilerContextImpl(currentPath, includeDirs);

            compilerContext.parse(files);
        } catch(Exception e) {
            System.err.println(e.getMessage());
            return -1;
        }

        return 0;
    }
}

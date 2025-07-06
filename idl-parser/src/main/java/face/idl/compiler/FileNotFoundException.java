package face.idl.compiler;

import org.antlr.v4.runtime.RecognitionException;

public class FileNotFoundException extends RuntimeException {
    public FileNotFoundException(String message) {
        super(message);
    }
}

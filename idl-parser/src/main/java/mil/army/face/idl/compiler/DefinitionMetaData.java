package mil.army.face.idl.compiler;

/**
 * This class stores information about where definitions are created to aid in error messages.
 */
public class DefinitionMetaData {

    public DefinitionMetaData(String filePath, int lineNumber, int column) {
        this.filePath = filePath;
        this.lineNumber = lineNumber;
        this.column = column;
    }

    public String getFilePath() {
        return filePath;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public int getColumn() {
        return column;
    }

    private final String filePath;
    private final int lineNumber;
    private final int column;
}

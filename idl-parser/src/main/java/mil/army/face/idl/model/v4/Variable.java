package mil.army.face.idl.model.v4;

public class Variable {

    Variable(String name, DataType dataType) {
        this.name = name;
        this.dataType = dataType;
    }

    String getName() { return name; }

    DataType getDataType() { return dataType; }

    private final DataType dataType;

    private final String name;
}

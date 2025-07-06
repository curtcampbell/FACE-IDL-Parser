package mil.army.face.core;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * A utility class for serializing Java objects to and from YAML format
 * using the SnakeYAML library.
 * <p>
 * This helper is designed for flexibility and ease of use, providing static
 * methods for common operations, including shortcuts for file I/O.
 */
public final class YamlHelper {

    private static final Yaml YAML_INSTANCE;

    static {
        // Configure the Representer to handle Java Beans correctly.
        Representer representer = new Representer(new DumperOptions());
        // This is crucial for flexibility: if the YAML is missing a property that
        // exists in the Java class, it will not throw an exception.
        representer.getPropertyUtils().setSkipMissingProperties(true);

        // Configure DumperOptions for human-readable output.
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK); // Use block style for readability.
        options.setPrettyFlow(true); // Pretty print collections.
        options.setIndent(2);

        YAML_INSTANCE = new Yaml(representer, options);
    }

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private YamlHelper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /**
     * Serializes a Java object into a YAML formatted string.
     *
     * @param data The object to serialize.
     * @return A string containing the YAML representation of the object.
     */
    public static String toYaml(Object data) {
        return YAML_INSTANCE.dump(data);
    }

    /**
     * Serializes a Java object and writes it to the specified file.
     *
     * @param data     The object to serialize.
     * @param filePath The path to the output file.
     * @throws IOException if an I/O error occurs writing to the file.
     */
    public static void toYamlFile(Object data, String filePath) throws IOException {
        try (FileWriter writer = new FileWriter(filePath)) {
            YAML_INSTANCE.dump(data, writer);
        }
    }

    /**
     * Deserializes a YAML formatted string into a Java object.
     *
     * @param yamlString The YAML string to deserialize.
     * @param clazz      The class of the object to create.
     * @param <T>        The type of the object.
     * @return An instance of the specified class.
     */
    public static <T> T fromYaml(String yamlString, Class<T> clazz) {
        return YAML_INSTANCE.loadAs(yamlString, clazz);
    }

    /**
     * Deserializes a YAML file into a Java object.
     *
     * @param filePath The path to the YAML file.
     * @param clazz    The class of the object to create.
     * @param <T>      The type of the object.
     * @return An instance of the specified class.
     * @throws IOException if an I/O error occurs reading from the file.
     */
    public static <T> T fromYamlFile(String filePath, Class<T> clazz) throws IOException {
        try (InputStream inputStream = Files.newInputStream(Paths.get(filePath))) {
            return YAML_INSTANCE.loadAs(inputStream, clazz);
        }
    }
}
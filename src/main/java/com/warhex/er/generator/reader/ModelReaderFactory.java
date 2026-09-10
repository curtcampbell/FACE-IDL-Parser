package com.warhex.er.generator.reader;

import com.warhex.er.generator.reader.face.FaceXmiModelReader;
import com.warhex.er.generator.reader.json.JsonModelReader;
import com.warhex.er.generator.reader.yaml.YamlModelReader;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Factory that selects the correct {@link ModelReader} implementation based on
 * the file extension of the entity source file.
 *
 * <h2>Supported extensions</h2>
 * <ul>
 *   <li>{@code .yaml}, {@code .yml} → {@link YamlModelReader}</li>
 *   <li>{@code .json} → {@link JsonModelReader} (stub)</li>
 *   <li>{@code .face} → {@link FaceXmiModelReader} (stub)</li>
 * </ul>
 *
 * <h2>Adding a new format</h2>
 * <ol>
 *   <li>Implement {@link ModelReader} in the appropriate sub-package.</li>
 *   <li>Add the file extension mapping in {@link #forFile(Path)}.</li>
 * </ol>
 */
public final class ModelReaderFactory {

    private ModelReaderFactory() {}

    /**
     * Returns the {@link ModelReader} appropriate for {@code path}'s extension.
     *
     * @param path entity source file path
     * @return reader implementation; never {@code null}
     * @throws IllegalArgumentException if the extension is not recognised
     */
    public static ModelReader forFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);

        if (name.endsWith(".yaml") || name.endsWith(".yml")) {
            return new YamlModelReader();
        }
        if (name.endsWith(".json")) {
            return new JsonModelReader();
        }
        if (name.endsWith(".face")) {
            return new FaceXmiModelReader();
        }

        throw new IllegalArgumentException(
                "No ModelReader registered for file: " + path.getFileName()
                        + ". Supported extensions: .yaml, .yml, .json, .face");
    }
}

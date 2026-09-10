package com.warhex.er.generator.reader.json;

import com.warhex.er.generator.reader.ModelReader;
import com.warhex.er.generator.reader.dto.IdlModelData;

import java.nio.file.Path;

/**
 * Stub {@link ModelReader} implementation for JSON entity source files.
 *
 * <h2>Status</h2>
 * <p>Not yet implemented.  The JSON format will mirror the YAML structure
 * described in {@link com.warhex.er.generator.reader.yaml.YamlModelReader},
 * using {@code snake_case} keys for consistency.
 *
 * <h2>Suggested implementation approach</h2>
 * <p>Add a JSON library dependency to {@code pom.xml} (e.g., Jackson Databind
 * or Gson) and deserialise into the same {@link IdlModelData} DTO tree used
 * by the YAML reader.  The {@link com.warhex.er.generator.reader.EntityModelMapper}
 * is format-agnostic and requires no changes.
 */
public class JsonModelReader implements ModelReader {

    @Override
    public IdlModelData read(Path source) throws Exception {
        throw new UnsupportedOperationException(
                "JSON entity source reader is not yet implemented. "
                        + "Use a .yaml source file instead.");
    }
}

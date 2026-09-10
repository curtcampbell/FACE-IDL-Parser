package com.warhex.er.generator.reader;

import com.warhex.er.generator.reader.dto.IdlModelData;

import java.nio.file.Path;

/**
 * Pluggable hook for reading a source file and deserializing it into
 * a format-neutral {@link IdlModelData} DTO.
 *
 * <h2>Design intent</h2>
 * <p>This interface isolates file-format concerns (YAML parsing, JSON parsing,
 * XMI parsing, etc.) from the rest of the pipeline.  The {@link IdlModelData}
 * DTO it produces is a plain Java object that represents the raw content of the
 * source file without any of the semantic enrichment (sorting, index assignment,
 * pattern expansion) performed later by {@link EntityModelMapper}.
 *
 * <h2>Adding a new format</h2>
 * <ol>
 *   <li>Implement this interface in the appropriate sub-package
 *       (e.g., {@code reader.json}, {@code reader.face}).</li>
 *   <li>Register the implementation in
 *       {@link com.warhex.er.generator.reader.ModelReaderFactory}.</li>
 * </ol>
 *
 * <h2>Known implementations</h2>
 * <ul>
 *   <li>{@link com.warhex.er.generator.reader.yaml.YamlModelReader} — YAML entity source</li>
 *   <li>{@link com.warhex.er.generator.reader.json.JsonModelReader} — JSON (stub)</li>
 *   <li>{@link com.warhex.er.generator.reader.face.FaceXmiModelReader} — .face XMI (stub)</li>
 * </ul>
 */
public interface ModelReader {

    /**
     * Reads the source file at {@code source} and returns a populated
     * {@link IdlModelData} DTO.
     *
     * <p>Implementations are responsible only for parsing and structural
     * deserialization.  Semantic concerns (alphabetical sort, enum value
     * assignment, position index assignment) are handled downstream by
     * {@link EntityModelMapper}.
     *
     * @param source path to the source file
     * @return populated DTO; never {@code null}
     * @throws Exception if the file cannot be read or does not conform to the
     *                   expected format
     */
    IdlModelData read(Path source) throws Exception;
}

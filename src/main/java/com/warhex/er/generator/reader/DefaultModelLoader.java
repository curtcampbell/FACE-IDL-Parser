package com.warhex.er.generator.reader;

import com.warhex.er.generator.idl.ModelLoader;
import com.warhex.er.generator.model.EntityModel;
import com.warhex.er.generator.reader.dto.IdlModelData;

import java.nio.file.Path;

/**
 * Default {@link ModelLoader} implementation that composes a
 * {@link ModelReader} and an {@link EntityModelMapper} into a single call.
 *
 * <h2>Pipeline</h2>
 * <pre>
 *   source file  →  ModelReader.read()  →  IdlModelData (DTO)
 *                →  EntityModelMapper.map()  →  EntityModel (domain)
 * </pre>
 *
 * <p>The reader is responsible for parsing the source format; the mapper is
 * responsible for semantic enrichment (sorting, index assignment, defaults).
 *
 * <h2>Typical usage</h2>
 * <pre>{@code
 * Path source = Paths.get("SampleModel.yaml");
 * ModelLoader loader = new DefaultModelLoader(
 *         ModelReaderFactory.forFile(source),
 *         new EntityModelMapper());
 * EntityModel model = loader.load(source);
 * }</pre>
 */
public class DefaultModelLoader implements ModelLoader {

    private final ModelReader reader;
    private final EntityModelMapper mapper;

    /**
     * Constructs a loader with the given reader and mapper.
     *
     * @param reader format-specific reader; must not be {@code null}
     * @param mapper DTO-to-domain mapper; must not be {@code null}
     */
    public DefaultModelLoader(ModelReader reader, EntityModelMapper mapper) {
        if (reader == null) throw new IllegalArgumentException("reader must not be null");
        if (mapper == null) throw new IllegalArgumentException("mapper must not be null");
        this.reader = reader;
        this.mapper = mapper;
    }

    /**
     * Loads and enriches the entity model from {@code sourcePath}.
     *
     * @param sourcePath path to the entity source file
     * @return fully-populated {@link EntityModel}
     * @throws Exception if reading or mapping fails
     */
    @Override
    public EntityModel load(Path sourcePath) throws Exception {
        IdlModelData data = reader.read(sourcePath);
        return mapper.map(data);
    }
}

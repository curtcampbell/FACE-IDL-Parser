package com.warhex.er.generator.idl;

import com.warhex.er.generator.model.EntityModel;

import java.nio.file.Path;

/**
 * Loads an {@link EntityModel} from an entity source file.
 *
 * <p>The entity source format is not yet defined. This interface isolates the
 * rest of the generator from the parsing strategy so that JSON, YAML, XML, or
 * any other format can be plugged in by implementing this interface.
 *
 * <p>Implementations are expected to:
 * <ol>
 *   <li>Parse the file at the supplied path.</li>
 *   <li>Populate an {@link EntityModel} with the model name, IDL module,
 *       struct name pattern, and entity list.</li>
 *   <li>Sort the entity list alphabetically by simple name and assign
 *       sequential 1-based {@code typeEnumValue} values [ER-071].</li>
 * </ol>
 */
public interface ModelLoader {

    /**
     * Loads and returns an {@link EntityModel} from {@code sourcePath}.
     *
     * @param sourcePath path to the entity source file
     * @return populated EntityModel
     * @throws Exception if the file cannot be read or parsed
     */
    EntityModel load(Path sourcePath) throws Exception;
}

package face.idl.engine;

import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.Template;

import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.Properties;

public class TemplateEngine {

    private final VelocityEngine engine = new VelocityEngine();

    public TemplateEngine(Path searchPath) {

        var dir = searchPath.toFile();
        if(!dir.exists()) {
            throw new FileSystemNotFoundException("Directory %s does not exist".formatted(searchPath));
        }
        String searchDirectory =  dir.getAbsolutePath();
        Properties props = new Properties();

        props.put("file.resource.loader.path", searchDirectory);
        props.setProperty("runtime.log.logsystem.class", "org.apache.velocity.runtime.log.NullLogSystem");
        engine.init(props);
    }

}

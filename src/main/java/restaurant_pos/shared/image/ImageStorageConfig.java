package restaurant_pos.shared.image;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import restaurant_pos.shared.config.PosProperties;

@Configuration
public class ImageStorageConfig {

    @Bean
    public ImageStorage imageStorage(PosProperties properties) {
        return new LocalImageStorage(Path.of(properties.imageDir()));
    }
}

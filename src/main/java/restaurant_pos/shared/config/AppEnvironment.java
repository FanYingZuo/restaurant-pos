package restaurant_pos.shared.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** 執行環境：預設為正式，只有明確啟用 dev 才是開發（§4.5）。 */
@Component
public class AppEnvironment {

    private final boolean dev;

    public AppEnvironment(Environment environment) {
        this.dev = environment.acceptsProfiles(Profiles.of("dev"));
    }

    public boolean isDev() {
        return dev;
    }
}

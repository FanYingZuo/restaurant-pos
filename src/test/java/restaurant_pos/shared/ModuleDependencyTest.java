package restaurant_pos.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 模組依賴方向（01-PLAN §4.1）：模組化單體、9 個模組，只能依賴下表方向，不得循環。
 * 掃描 src/main/java 的 import，違反就失敗；之後每個階段新增程式都會被檢查。
 */
class ModuleDependencyTest {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "shared", Set.of(),
            "staff", Set.of("shared"),
            "menu", Set.of("shared"),
            "table", Set.of("shared"),
            "order", Set.of("shared", "staff", "menu", "table"),
            "checkout", Set.of("shared", "staff", "order", "table"),
            "kds", Set.of("shared", "order", "table"),
            "customer", Set.of("shared", "table", "menu", "order"),
            "report", Set.of("shared", "staff", "table", "order", "checkout"));

    private static final Pattern IMPORT = Pattern.compile("^\\s*import\\s+(?:static\\s+)?restaurant_pos\\.([a-z]+)\\.");

    @Test
    void 九個模組的套件都存在() {
        for (String module : ALLOWED.keySet()) {
            assertThat(Path.of("src/main/java/restaurant_pos", module)).as(module).isDirectory();
        }
    }

    @Test
    void 每個模組只依賴規格允許的模組() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Set<String>> rule : ALLOWED.entrySet()) {
            Path dir = Path.of("src/main/java/restaurant_pos", rule.getKey());
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    for (String line : Files.readAllLines(file)) {
                        Matcher m = IMPORT.matcher(line);
                        if (m.find()) {
                            String target = m.group(1);
                            if (!target.equals(rule.getKey()) && !rule.getValue().contains(target)) {
                                violations.add(rule.getKey() + " 不可依賴 " + target + "：" + file.getFileName());
                            }
                        }
                    }
                }
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    void 依賴方向沒有循環() {
        for (String start : ALLOWED.keySet()) {
            assertThat(reaches(start, start, new java.util.HashSet<>())).as(start + " 循環依賴").isFalse();
        }
    }

    private boolean reaches(String from, String target, Set<String> seen) {
        for (String next : ALLOWED.get(from)) {
            if (next.equals(target)) {
                return true;
            }
            if (seen.add(next) && reaches(next, target, seen)) {
                return true;
            }
        }
        return false;
    }
}

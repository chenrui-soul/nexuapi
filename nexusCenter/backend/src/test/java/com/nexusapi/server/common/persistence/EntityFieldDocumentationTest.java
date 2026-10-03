package com.nexusapi.server.common.persistence;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** 防止后续新增 MyBatis Row 字段时遗漏用途和安全边界说明。 */
class EntityFieldDocumentationTest {
    private static final Path MODULES_ROOT = Path.of("src/main/java/com/nexusapi/server/modules");
    private static final Pattern FIELD_PATTERN = Pattern.compile("^\\s*private\\s+(?!static\\b).+;\\s*$");

    @Test
    void everyEntityClassAndFieldHasJavadoc() throws IOException {
        List<Path> entityFiles;
        try (var paths = Files.walk(MODULES_ROOT)) {
            entityFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().contains("entity" + java.io.File.separator))
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .toList();
        }

        assertThat(entityFiles).as("项目中应存在 MyBatis entity/row 文件").isNotEmpty();

        List<String> undocumented = new ArrayList<>();
        int fieldCount = 0;
        for (Path file : entityFiles) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.stripLeading().startsWith("public class ")
                        && !hasJavadocImmediatelyBefore(lines, index)) {
                    undocumented.add(file + ":" + (index + 1) + " class");
                }
                if (FIELD_PATTERN.matcher(line).matches()) {
                    fieldCount++;
                    if (!hasJavadocImmediatelyBefore(lines, index)) {
                        undocumented.add(file + ":" + (index + 1) + " " + line.trim());
                    }
                }
            }
        }

        assertThat(fieldCount).as("本轮应覆盖现有 170 个持久化字段").isGreaterThanOrEqualTo(170);
        assertThat(undocumented)
                .as("每个 MyBatis Row 类和字段上方都必须有中文 JavaDoc")
                .isEmpty();
    }

    private boolean hasJavadocImmediatelyBefore(List<String> lines, int targetIndex) {
        int index = targetIndex - 1;
        while (index >= 0 && lines.get(index).isBlank()) {
            index--;
        }
        if (index < 0 || !lines.get(index).trim().endsWith("*/")) {
            return false;
        }
        while (index >= 0) {
            String line = lines.get(index).trim();
            if (line.startsWith("/**")) {
                return true;
            }
            if (!line.startsWith("*") && !line.endsWith("*/")) {
                return false;
            }
            index--;
        }
        return false;
    }
}

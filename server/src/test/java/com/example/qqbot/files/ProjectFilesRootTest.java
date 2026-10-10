package com.example.qqbot.files;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工程根定位的验收。
 *
 * <p><b>为什么需要它</b>：判错工程根不会报错，只会让 {@code .env} 与
 * {@code overrides.yml} 静默找错地方 —— 表现是"配置改了没生效"，
 * 排查起来非常费劲。所以这件事必须有测试钉住，而不是靠注释承诺。
 */
class ProjectFilesRootTest {

    private static void touch(Path p) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, "x");
    }

    @Test
    @DisplayName("★ 隔离构建复制出来的 .toolchain/system.md 不得把工程根认成 .toolchain")
    void prefersRootUniqueMarkerOverCopiedPrompt(@TempDir Path tmp) throws IOException {
        // 真仓库根：两个判据都在
        touch(tmp.resolve("server/pom.xml"));
        touch(tmp.resolve("system.md"));
        // build-server.sh 复制出来的那一份 —— 它会诱使"每层按顺序试判据"提前命中
        touch(tmp.resolve(".toolchain/system.md"));
        Path cwd = tmp.resolve(".toolchain/server-ci");
        Files.createDirectories(cwd);

        assertThat(ProjectFiles.resolveFrom(cwd).toRealPath())
                .as("必须依据 server/pom.xml 停在真仓库根，而不是依据复制出来的提示词停在 .toolchain")
                .isEqualTo(tmp.toRealPath());
    }

    @Test
    @DisplayName("只有提示词文件、没有 server/pom.xml 时仍能定位（生产环境只部署 server/ 的兜底）")
    void fallsBackToPromptMarker(@TempDir Path tmp) throws IOException {
        touch(tmp.resolve("system.md"));
        Path cwd = tmp.resolve("server");
        Files.createDirectories(cwd);

        assertThat(ProjectFiles.resolveFrom(cwd).toRealPath()).isEqualTo(tmp.toRealPath());
    }
}

package com.example.qqbot.distill;

import com.example.qqbot.config.DistillProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ChatImageStoreTest {

    @TempDir
    Path base;

    @Test
    @DisplayName("按内容 MD5 落盘；同一张图重复保存复用文件")
    void savesByMd5() throws Exception {
        DistillProperties props = new DistillProperties();
        props.setImagesDir(base.resolve("chat-images").toString());
        ChatImageStore store = new ChatImageStore(props);
        byte[] bytes = "hello-image".getBytes("UTF-8");

        var first = store.save(bytes, "image/png");
        var second = store.save(bytes, "image/png");

        assertThat(first.filename()).isEqualTo(second.filename());
        assertThat(first.filename()).endsWith(".png");
        assertThat(store.read(first.filename())).isEqualTo(bytes);
        assertThat(store.read("../evil")).isNull();
    }
}

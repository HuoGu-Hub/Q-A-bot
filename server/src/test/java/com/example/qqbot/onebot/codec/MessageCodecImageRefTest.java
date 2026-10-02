package com.example.qqbot.onebot.codec;

import com.example.qqbot.onebot.model.ImageRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片段解析测试 —— 用例取自**真实抓到的 NapCat 事件**
 * （见 deploy/data/napcat/logs 里 2026-09-21 的图片消息）。
 */
class MessageCodecImageRefTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final MessageCodec codec = new MessageCodec(mapper);

    private JsonNode parse(String json) throws Exception {
        return mapper.readTree(json);
    }

    @Test
    @DisplayName("真实样本：从 file 字段解析出小写 MD5 与文件大小")
    void parsesRealNapcatSample() throws Exception {
        JsonNode message = parse("""
                [{"type":"image","data":{"summary":"","file":"65A82BE1AE2810AEB287D78C2260C823.jpg",
                  "sub_type":0,
                  "url":"https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=EhQxxx&rkey=CAMSyyy",
                  "file_size":"1361643"}}]
                """);

        List<ImageRef> refs = codec.extractImageRefs(message);

        assertThat(refs).hasSize(1);
        ImageRef ref = refs.get(0);
        assertThat(ref.key()).isEqualTo("65a82be1ae2810aeb287d78c2260c823");
        assertThat(ref.size()).isEqualTo(1361643);
        assertThat(ref.url()).contains("multimedia.nt.qq.com.cn");
        assertThat(ref.hasKey()).isTrue();
    }

    @Test
    @DisplayName("文件名不是 MD5 时，仍能取到 URL，只是没有缓存键")
    void nonMd5FileNameStillYieldsUrl() throws Exception {
        JsonNode message = parse("""
                [{"type":"image","data":{"file":"photo.png","url":"https://example.com/a.png"}}]
                """);

        List<ImageRef> refs = codec.extractImageRefs(message);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).key()).isNull();
        assertThat(refs.get(0).url()).isEqualTo("https://example.com/a.png");
    }

    @Test
    @DisplayName("老式 gchatpic 链接：从 URL 里兜底提取 MD5")
    void extractsMd5FromLegacyUrl() throws Exception {
        JsonNode message = parse("""
                [{"type":"image","data":{"file":"",
                  "url":"https://gchat.qpic.cn/gchatpic_new/0/0-0-D8193A43057EB8818B65A46E1568D5D4/0"}}]
                """);

        List<ImageRef> refs = codec.extractImageRefs(message);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).key()).isEqualTo("d8193a43057eb8818b65a46e1568d5d4");
    }

    @Test
    @DisplayName("file 是 http 链接时当 URL 用（url 字段为空的老格式）")
    void usesFileAsUrlWhenMissing() throws Exception {
        JsonNode message = parse("""
                [{"type":"image","data":{"file":"https://example.com/b.jpg"}}]
                """);

        List<ImageRef> refs = codec.extractImageRefs(message);

        assertThat(refs).hasSize(1);
        assertThat(refs.get(0).url()).isEqualTo("https://example.com/b.jpg");
    }

    @Test
    @DisplayName("extractImageUrls 仍按原语义返回 URL 列表（Guard 依赖它）")
    void extractImageUrlsStillWorks() throws Exception {
        JsonNode message = parse("""
                [{"type":"text","data":{"text":"看图"}},
                 {"type":"image","data":{"file":"65A82BE1AE2810AEB287D78C2260C823.jpg",
                   "url":"https://multimedia.nt.qq.com.cn/download?a=1"}}]
                """);

        assertThat(codec.extractImageUrls(message))
                .containsExactly("https://multimedia.nt.qq.com.cn/download?a=1");
    }
}

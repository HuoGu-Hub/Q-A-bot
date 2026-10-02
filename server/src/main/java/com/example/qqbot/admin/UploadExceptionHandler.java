package com.example.qqbot.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.util.unit.DataSize;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 上传失败时给一句**人能看懂**的话。
 *
 * <h2>为什么必须是全局 advice，而不能写在控制器里</h2>
 * 上传超限这件事是在 {@code DispatcherServlet.checkMultipart()} 里就炸掉的 ——
 * 那时候**还没找到是哪个控制器**，所以控制器内部的 {@code @ExceptionHandler} 永远轮不到。
 * 只有全局的 {@code @RestControllerAdvice} 才会被 {@code ExceptionHandlerExceptionResolver}
 * 在"handler 为空"的情况下用上。
 *
 * <p>不这么做的后果实测过：返回一个**空 body 的 413**。前端 {@code res.json()} 解析失败，
 * 只能退化成 "HTTP 413"；更糟的情况（body 没发完、连接被直接掐断）浏览器连状态码都拿不到，
 * 页面就显示一句 {@code Failed to fetch} —— 完全不知道哪里错了。
 */
@RestControllerAdvice
public class UploadExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(UploadExceptionHandler.class);

    /** 容器的接收上限（和 CarouselController 里那个是同一个配置项） */
    @Value("${spring.servlet.multipart.max-file-size:12MB}")
    private DataSize uploadCeiling;

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        log.warn("[UPLOAD] 上传被容器挡下（超过 multipart 上限）：{}", e.getMessage());
        long mb = Math.max(1, uploadCeiling.toBytes() / 1024 / 1024);
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(body(
                "图片超过了服务器的接收上限（" + mb + " MB）。压小一点再传就对了 —— "
                        + "公开站上的图越小、群友打开越快，几百 KB 通常就够。"));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> multipart(MultipartException e) {
        log.warn("[UPLOAD] 请求不是合法的 multipart：{}", e.getMessage());
        return ResponseEntity.badRequest().body(body(
                "上传的数据格式不对（不是合法的文件上传请求）。刷新页面重试一次；"
                        + "如果一直这样，多半是中间的代理（nginx / vite）改动过请求体。"));
    }

    private static Map<String, Object> body(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        return out;
    }
}

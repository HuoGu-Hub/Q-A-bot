package com.example.qqbot.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 在过滤器里写统一错误响应。
 *
 * <p>过滤器跑在 Spring MVC 之前，{@code @RestControllerAdvice} 轮不到它，
 * 所以这里复用同一套 {@link ProblemDetail} 形状。字段与
 * {@link ApiExceptionHandler} 保持一致：type、title、status、code、detail、requestId。
 */
@Component
public class ProblemWriter {

    private final ObjectMapper mapper;

    public ProblemWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public void write(HttpServletResponse response, HttpStatus status, String code, String message)
            throws IOException {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, message);
        pd.setType(URI.create("https://qqbot.local/errors/" + code.toLowerCase(Locale.ROOT)));
        pd.setProperty("code", code);
        pd.setProperty("requestId", RequestId.current());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), pd);
    }
}

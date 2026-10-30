package com.example.qqbot.web;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * 全局错误出口。
 *
 * <p>所有 API 错误都长成 {@code application/problem+json}：
 * {@code type}、{@code title}、{@code status}、{@code code}、{@code detail}、
 * {@code requestId}，校验错误额外带 {@code errors}。
 *
 * <p>控制器只抛 {@link ApiException}；参数绑定、上传超限、静态资源找不到
 * 由这里统一翻译。前端不再解析中文文案。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Value("${spring.servlet.multipart.max-file-size:12MB}")
    private DataSize uploadCeiling;

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> api(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage(), e.errors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException e) {
        List<FieldProblem> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::fieldProblem)
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法", errors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> constraint(ConstraintViolationException e) {
        List<FieldProblem> errors = e.getConstraintViolations().stream()
                .map(v -> new FieldProblem(v.getPropertyPath().toString(), v.getMessage()))
                .toList();
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "请求体不是合法的 JSON，或字段类型不对", List.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> missingParam(MissingServletRequestParameterException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "缺少参数：" + e.getParameterName(), List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException e) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "参数类型不对：" + e.getName(), List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> tooLarge(MaxUploadSizeExceededException e) {
        long mb = Math.max(1, uploadCeiling.toBytes() / 1024 / 1024);
        log.warn("[UPLOAD] 上传超过容器上限：{}", e.getMessage());
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                "文件超过了服务器的接收上限（" + mb + " MB）", List.of());
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ProblemDetail> multipart(MultipartException e) {
        log.warn("[UPLOAD] 不是合法的 multipart 请求：{}", e.getMessage());
        return problem(HttpStatus.BAD_REQUEST, "INVALID_MULTIPART",
                "上传的数据格式不对，请刷新页面重试", List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> noResource(NoResourceFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "接口不存在", List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception e) {
        log.error("[API] 未预期异常 requestId={}", RequestId.current(), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务端处理失败", List.of());
    }

    private FieldProblem fieldProblem(FieldError error) {
        return new FieldProblem(error.getField(),
                error.getDefaultMessage() == null ? "不合法" : error.getDefaultMessage());
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code,
                                                  String detail, List<FieldProblem> errors) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("https://qqbot.local/errors/" + code.toLowerCase(Locale.ROOT)));
        pd.setProperty("code", code);
        pd.setProperty("requestId", RequestId.current());
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }
}

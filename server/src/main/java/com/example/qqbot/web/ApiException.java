package com.example.qqbot.web;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 带业务错误码的 API 异常。
 *
 * <p>控制器只抛这个，不再自己拼 {@code Map.of("error", ...)}。
 * 统一由 {@link ApiExceptionHandler} 转成 {@code application/problem+json}。
 *
 * <p>{@code code} 是给程序判断的稳定标识，{@code message} 才是给人看的。
 * 前端只读 code 与 message，不再靠中文字符串做分支。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<FieldProblem> errors;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldProblem> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<FieldProblem> errors() {
        return errors;
    }

    public static ApiException badRequest(String message) {
        return badRequest("BAD_REQUEST", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException validation(String message, List<FieldProblem> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, errors);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", message);
    }

    public static ApiException forbidden(String code, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, code, message);
    }

    public static ApiException notFound(String message) {
        return notFound("NOT_FOUND", message);
    }

    public static ApiException notFound(String code, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException tooManyRequests(String message) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", message);
    }

    public static ApiException internal(String message) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", message);
    }

    public static ApiException serviceUnavailable(String code, String message) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}

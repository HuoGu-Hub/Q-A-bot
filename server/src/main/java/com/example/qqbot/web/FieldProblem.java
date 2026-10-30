package com.example.qqbot.web;

/**
 * 单个字段的校验错误。
 *
 * <p>只用于 400 响应里的 {@code errors} 数组，字段名与请求 JSON 对齐。
 */
public record FieldProblem(String field, String message) {
}

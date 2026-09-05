package com.stock.kline.web;

import com.stock.kline.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLIntegrityConstraintViolationException;

/** 统一异常处理：把数据库约束等异常转换成前端可读的提示 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({DuplicateKeyException.class, SQLIntegrityConstraintViolationException.class})
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleDuplicate(Exception e) {
        String msg = friendly(e);
        log.warn("[dup] {}", msg);
        return ApiResponse.fail("数据已存在，违反唯一约束: " + msg);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleIntegrity(DataIntegrityViolationException e) {
        log.warn("[integrity] {}", e.getMessage());
        return ApiResponse.fail("数据不合法: " + friendly(e));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleMissingParam(MissingServletRequestParameterException e) {
        return ApiResponse.fail("缺少必填参数: " + e.getParameterName());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        return ApiResponse.fail(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Void> handleOthers(Exception e) {
        log.error("[error] unexpected", e);
        return ApiResponse.fail("服务异常: " + friendly(e));
    }

    private static String friendly(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}

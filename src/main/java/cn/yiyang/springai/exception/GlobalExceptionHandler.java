package cn.yiyang.springai.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器 — 统一拦截所有 Controller 抛出的异常，返回结构化错误响应
 *
 * 异常处理优先级（从具体到通用）：
 * 1. BusinessException       → 400 + 业务错误信息
 * 2. 参数校验异常             → 400 + 字段级错误详情
 * 3. 参数类型转换异常          → 400 + 参数名提示
 * 4. 缺少必填参数             → 400 + 参数名提示
 * 5. 其他 RuntimeException   → 500 + 通用错误（不暴露内部细节）
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常 — 可预期的业务错误，返回 400
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Map<String, Object>> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ResponseEntity.status(e.getCode()).body(errorBody(e.getCode(), e.getMessage()));
    }

    /**
     * 参数校验异常 — @Valid 校验失败，返回 400 + 字段级错误
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("参数校验失败: {}", fieldErrors);
        Map<String, Object> body = errorBody(400, "参数校验失败");
        body.put("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 缺少必填参数 — 返回 400 + 参数名
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少必填参数: {}", e.getParameterName());
        return ResponseEntity.badRequest().body(errorBody(400, "缺少必填参数: " + e.getParameterName()));
    }

    /**
     * 参数类型转换异常 — 返回 400 + 参数名
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误: {} 期望类型 {}", e.getName(), e.getRequiredType());
        return ResponseEntity.badRequest().body(errorBody(400, "参数 " + e.getName() + " 类型不正确"));
    }

    /**
     * 非法参数异常 — 返回 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArg(IllegalArgumentException e) {
        log.warn("非法参数: {}", e.getMessage());
        return ResponseEntity.badRequest().body(errorBody(400, e.getMessage()));
    }

    /**
     * 其他未捕获异常 — 返回 500，不暴露内部细节
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception e) {
        log.error("未预期异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(500, "服务内部错误，请稍后重试"));
    }

    private Map<String, Object> errorBody(int code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("timestamp", System.currentTimeMillis());
        return body;
    }
}

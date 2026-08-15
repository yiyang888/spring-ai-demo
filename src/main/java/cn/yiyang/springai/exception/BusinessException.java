package cn.yiyang.springai.exception;

/**
 * 业务异常 — 用于表示可预期的业务错误（如参数校验失败、资源不存在等）
 *
 * 与 RuntimeException 的区别：
 * - BusinessException 会被 GlobalExceptionHandler 捕获，返回 HTTP 400 + 结构化错误信息
 * - RuntimeException 会被 GlobalExceptionHandler 捕获，返回 HTTP 500 + 通用错误提示
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
        this.code = 400;
    }

    public int getCode() {
        return code;
    }
}

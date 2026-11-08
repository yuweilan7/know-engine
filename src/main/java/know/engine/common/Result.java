package know.engine.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一 API 响应结果封装
 *
 * @param <T> 业务数据类型
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {

    private boolean success;
    private String message;
    private T data;
    private Integer total;

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> ok(T data) {
        Result<T> result = new Result<>();
        result.setSuccess(true);
        result.setData(data);
        return result;
    }

    public static <T> Result<T> ok(T data, String message) {
        Result<T> result = ok(data);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> ok(T data, int total) {
        Result<T> result = ok(data);
        result.setTotal(total);
        return result;
    }

    public static <T> Result<T> result(boolean success, String message) {
        Result<T> result = new Result<>();
        result.setSuccess(success);
        result.setMessage(message);
        return result;
    }

    public static <T> Result<T> fail(String message) {
        return result(false, message);
    }
}

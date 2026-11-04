package com.lab.equipment;

import java.util.Map;
import org.springframework.dao.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

public class ApiError extends RuntimeException {

  final int status;
  final String code;

  public ApiError(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public static void require(boolean condition, String message) {
    if (!condition) throw new ApiError(409, "BUSINESS_CONFLICT", message);
  }
}

@RestControllerAdvice
class Errors {

  @ExceptionHandler(ApiError.class)
  ResponseEntity<?> business(ApiError e) {
    return ResponseEntity.status(e.status).body(
      Map.of("code", e.code, "message", e.getMessage())
    );
  }

  @ExceptionHandler(DuplicateKeyException.class)
  ResponseEntity<?> duplicate() {
    return ResponseEntity.status(409).body(
      Map.of(
        "code",
        "DUPLICATE",
        "message",
        "记录已存在或设备刚刚被占用，请刷新后重试"
      )
    );
  }

  @ExceptionHandler(CannotAcquireLockException.class)
  ResponseEntity<?> locked() {
    return ResponseEntity.status(409).body(
      Map.of("code", "BUSY", "message", "设备正在处理其他操作，请稍后重试")
    );
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> large() {
    return ResponseEntity.status(413).body(
      Map.of("code", "FILE_TOO_LARGE", "message", "文件不得超过 20MB")
    );
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class,
  })
  ResponseEntity<?> input(Exception e) {
    return ResponseEntity.badRequest().body(
      Map.of("code", "BAD_REQUEST", "message", "请求格式或参数无效")
    );
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> other(Exception e) {
    System.err.println("Request failed: " + e.getClass().getSimpleName());
    return ResponseEntity.status(500).body(
      Map.of(
        "code",
        "INTERNAL_ERROR",
        "message",
        "服务暂时无法完成请求，请查看服务日志并重试"
      )
    );
  }
}

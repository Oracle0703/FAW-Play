package com.shilapi.license;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> api(ApiException e) { return ResponseEntity.status(e.status).body(Map.of("error", e.code, "message", e.getMessage())); }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<?> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "INVALID_REQUEST", "message", "Invalid request fields")); }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(Exception e) { return ResponseEntity.status(413).body(Map.of("error", "UPDATE_TOO_LARGE", "message", "APK exceeds 512 MiB")); }
    @ExceptionHandler({org.springframework.web.multipart.MultipartException.class, org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<?> multipart(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "INVALID_APK", "message", "A multipart APK upload is required")); }
}

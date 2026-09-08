package com.autoblog;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> response(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"요청 처리에 실패했습니다.":e.getReason()));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {return ResponseEntity.badRequest().body(Map.of("message","입력값을 확인해 주세요.","fields",e.getBindingResult().getFieldErrors().stream().map(f->f.getField()+": "+f.getDefaultMessage()).toList()));}
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<?> bad(Exception e) {return ResponseEntity.badRequest().body(Map.of("message","입력 형식이 올바르지 않습니다."));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {return ResponseEntity.internalServerError().body(Map.of("message","서버 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."));}
}

package com.autoblog;

import org.springframework.web.bind.annotation.*;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> response(ResponseStatusException e) {return ResponseEntity.status(e.getStatusCode()).body(Map.of("message",e.getReason()==null?"요청 처리에 실패했습니다.":e.getReason()));}
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException e) {
        var fields=e.getBindingResult().getFieldErrors().stream().map(f->fieldLabel(f.getField())+": "+switch(f.getCode()==null?"":f.getCode()){
            case "NotBlank","NotNull" -> "값을 입력해 주세요.";
            case "Size" -> "허용된 글자 수 또는 항목 수를 확인해 주세요.";
            case "AssertTrue" -> "이미지 재사용 및 외부 삽입 조건을 확인해 주세요.";
            case "Min","Max" -> "허용된 숫자 범위를 확인해 주세요.";
            case "Pattern" -> "주소 형식을 확인해 주세요.";
            default -> "입력값을 확인해 주세요.";
        }).distinct().toList();
        return ResponseEntity.badRequest().body(Map.of("message",String.join(" / ",fields),"fields",fields));
    }
    private static String fieldLabel(String field){
        String label=field;
        var labels=Map.ofEntries(Map.entry("title","제목"),Map.entry("html","본문 HTML"),Map.entry("category","카테고리"),Map.entry("tags","태그"),Map.entry("checks","검토 메모"),Map.entry("images","이미지"),Map.entry("url","이미지 주소"),Map.entry("sourceUrl","원본 출처"),Map.entry("credit","제작자"),Map.entry("license","사용 조건"),Map.entry("licenseUrl","라이선스 주소"),Map.entry("caption","이미지 설명"),Map.entry("afterParagraph","삽입 문단 번호"),Map.entry("rightsConfirmed","사용 조건 확인"));
        for(var entry:labels.entrySet())label=label.replaceAll("\\b"+entry.getKey()+"\\b",entry.getValue());
        return label;
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class,IllegalArgumentException.class})
    ResponseEntity<?> bad(Exception e) {return ResponseEntity.badRequest().body(Map.of("message","입력 형식이 올바르지 않습니다."));}
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e) {return ResponseEntity.internalServerError().body(Map.of("message","서버 처리에 실패했습니다. 잠시 후 다시 시도해 주세요."));}
}

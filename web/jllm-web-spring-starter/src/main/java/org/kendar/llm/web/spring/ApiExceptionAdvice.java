package org.kendar.llm.web.spring;

import org.kendar.llm.web.commons.ApiException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** A failed handler call answers its status with {@code {"error": message}}. */
@RestControllerAdvice
public class ApiExceptionAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> failed(ApiException e) {
        return ResponseEntity.status(e.status()).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", String.valueOf(e.getMessage())));
    }
}

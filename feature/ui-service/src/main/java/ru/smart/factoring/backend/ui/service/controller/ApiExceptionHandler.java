package ru.smart.factoring.backend.ui.service.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ru.smart.factoring.backend.api.common.ErrorDto;
import ru.smart.factoring.backend.factoring.error.ApiException;

import java.util.Map;

/**
 * Ошибки API в едином формате ErrorDto (docs/claude/module-0/backend-api.md).
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorDto> handle(final ApiException e) {
        return ResponseEntity.status(e.getStatus()).body(new ErrorDto(e.getCode(), e.getMessage(), e.getDetails()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorDto> handleValidation(final Exception e) {
        return ResponseEntity.badRequest().body(new ErrorDto("VALIDATION_ERROR", e.getMessage(), Map.of()));
    }
}

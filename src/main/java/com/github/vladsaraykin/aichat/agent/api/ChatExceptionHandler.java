package com.github.vladsaraykin.aichat.agent.api;

import com.github.vladsaraykin.aichat.agent.application.ChatFailure;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ChatExceptionHandler {
    public record ErrorView(String message) { }
    @ExceptionHandler(ChatFailure.class)
    ResponseEntity<ErrorView> chatFailure(ChatFailure failure) {
        HttpStatus status = switch (failure.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case BUSY -> HttpStatus.CONFLICT;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case PROVIDER -> HttpStatus.BAD_GATEWAY;
            case STORAGE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorView(failure.getMessage()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ErrorView> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new ErrorView("Проверьте запрос: сообщение от 1 до 12000 символов и корректные идентификаторы."));
    }
}

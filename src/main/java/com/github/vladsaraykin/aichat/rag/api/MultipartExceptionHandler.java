package com.github.vladsaraykin.aichat.rag.api;

import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/** Multipart parsing can fail before Spring selects a controller. */
@RestControllerAdvice
@Order(-2)
public class MultipartExceptionHandler {
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<RagExceptionHandler.ErrorView> tooLarge(Exception exception) {
        return ResponseEntity.status(413).body(new RagExceptionHandler.ErrorView("TOO_LARGE",
                "Размер файла превышает 20 МиБ (запроса — 21 МиБ)."));
    }
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<RagExceptionHandler.ErrorView> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(new RagExceptionHandler.ErrorView("INVALID", "Некорректный multipart-запрос."));
    }
}

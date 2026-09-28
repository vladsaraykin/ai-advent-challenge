package com.github.vladsaraykin.aichat.rag.api;

import com.github.vladsaraykin.aichat.rag.application.RagFailure;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(assignableTypes = {DocumentController.class, ChunkingController.class, IndexController.class})
@Order(-1)
public class RagExceptionHandler {
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ErrorView> database(Exception error) { return failure(RagFailure.storage()); }
    public record ErrorView(String code, String message) { }
    @ExceptionHandler(RagFailure.class)
    public ResponseEntity<ErrorView> failure(RagFailure error) {
        HttpStatus status = switch (error.kind()) {
            case INVALID -> HttpStatus.BAD_REQUEST;
            case TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case UNSUPPORTED -> HttpStatus.UNSUPPORTED_MEDIA_TYPE;
            case NO_TEXT -> HttpStatus.UNPROCESSABLE_ENTITY;
            case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case STORAGE -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return ResponseEntity.status(status).body(new ErrorView(error.kind().name(), error.getMessage()));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorView> tooLarge(Exception error) {
        return failure(new RagFailure(RagFailure.Kind.TOO_LARGE, "Размер файла превышает 20 МиБ (запроса — 21 МиБ)."));
    }
    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class,
            MethodArgumentTypeMismatchException.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ErrorView> invalid(Exception error) {
        return failure(new RagFailure(RagFailure.Kind.INVALID, "Передайте один файл в поле file и корректные параметры запроса."));
    }
}

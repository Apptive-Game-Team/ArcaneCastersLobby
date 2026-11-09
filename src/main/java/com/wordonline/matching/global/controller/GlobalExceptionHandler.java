package com.wordonline.matching.global.controller;

import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;

import com.wordonline.matching.server.exception.GameServerUnreachableException;
import com.wordonline.matching.server.exception.NoAvailableGameServerException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ResponseEntity<String>> handleValidationExceptions(WebExchangeBindException ex) {
        String message = String.join(" | ",
                ex.getFieldErrors().stream().map(DefaultMessageSourceResolvable::getDefaultMessage
                ).toList());
        return Mono.just(ResponseEntity.badRequest().body(message));
    }

    private final static String INVALID_REQUEST_MESSAGE = "요청이 잘못됐습니다.";

    @ExceptionHandler(IllegalArgumentException.class)
    public Mono<ResponseEntity<String>> handleIllegalArgumentException(IllegalArgumentException e) {
        log.trace(e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(e.getMessage()));
    }

    /**
     * Capacity, not a bug. A game server being unavailable or unreachable is a transient
     * condition the client should retry, so it must not land in the catch-all 500 below.
     * The stack trace is omitted on purpose: this fires per failed match attempt and the
     * message alone identifies the cause.
     */
    @ExceptionHandler({NoAvailableGameServerException.class, GameServerUnreachableException.class})
    public Mono<ResponseEntity<String>> handleGameServerUnavailable(RuntimeException e) {
        log.error("[GAME SERVER UNAVAILABLE] {}", e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(e.getMessage()));
    }

    @ExceptionHandler(AuthorizationDeniedException.class)
    public Mono<ResponseEntity<String>> handleAuthorizationDeniedException(
            AuthorizationDeniedException e) {
        log.trace(e.getMessage());
        return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body("Unauthorized"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Mono<ResponseEntity<String>> handleValidationException(MethodArgumentNotValidException e) {
        log.trace(e.getMessage());
        return Mono.just(ResponseEntity.badRequest().body(INVALID_REQUEST_MESSAGE));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public Mono<ResponseEntity<String>> handleResponseStatusException(ResponseStatusException e) {
        return Mono.just(ResponseEntity.status(e.getStatusCode())
                .headers(e.getHeaders()).body(e.getReason()));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<String>> handleException(Exception e) {
        log.error("[ERROR] {}", e.getMessage(), e);
        return Mono.just(ResponseEntity.internalServerError().build());
    }
}

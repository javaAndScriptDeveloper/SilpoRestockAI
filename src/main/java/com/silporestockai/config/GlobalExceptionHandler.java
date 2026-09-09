package com.silporestockai.config;

import com.silporestockai.exception.ApplicationException;
import java.util.HashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into RFC 9457 {@link ProblemDetail} responses (served as {@code application/problem+json}).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApplicationException.class)
    public ProblemDetail handleApplicationException(ApplicationException ex) {
        log.debug("Application error: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(ex.getStatus(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        var errors = new HashMap<String, String>();
        for (var error : ex.getBindingResult().getAllErrors()) {
            var field = error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName();
            errors.put(field, error.getDefaultMessage());
        }
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setProperty("errors", errors);
        return problem;
    }

    @ExceptionHandler(RestClientException.class)
    public ProblemDetail handleRestClientException(RestClientException ex) {
        log.error("Upstream API call failed", ex);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_GATEWAY, "Upstream API is currently unavailable: " + ex.getMessage());
    }

    /**
     * A browser asking for {@code /favicon.ico} on the OAuth callback page or the WebApp form is not an incident.
     * Before this it was logged as «Unhandled exception» with a full stack trace — twice per onboarding, at ERROR,
     * which is exactly the level the demo console shows.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleMissingResource(NoResourceFoundException ex) {
        log.debug("No static resource: {}", ex.getResourcePath());
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
    }

    /**
     * Neither is a crawler sending {@code GET /telegram/webhook}, or a browser opening any other POST-only URL.
     * Without this the catch-all below answers 500 with a full stack trace at ERROR — on a public host that is
     * both the wrong status (405 is the answer) and a steady stream of noise from whoever scans the domain.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        log.debug("Method not supported: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong");
    }
}

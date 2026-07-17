package com.bryan.shared.exceptions;

public record ErrorResponse(
        boolean success,
        String message,
        int status
) { }

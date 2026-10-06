package com.academy.paybridge.shared.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** The JSON body returned for every error. It never contains stack traces or secrets. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(String code, String message, Map<String, String> fieldErrors) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Map.of());
    }
}

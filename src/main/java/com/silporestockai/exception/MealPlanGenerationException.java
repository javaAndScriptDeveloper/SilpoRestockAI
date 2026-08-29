package com.silporestockai.exception;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Raised when Claude twice returned a plan that would be wrong to store — missing days, empty meals. */
public class MealPlanGenerationException extends ApplicationException {

    public MealPlanGenerationException(UUID userId, List<String> defects) {
        super(
                HttpStatus.BAD_GATEWAY,
                "could not generate a usable weekly plan for user %s: %s"
                        .formatted(userId, String.join("; ", defects)));
    }
}

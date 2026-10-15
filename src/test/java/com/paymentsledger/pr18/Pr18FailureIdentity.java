package com.paymentsledger.pr18;

import java.util.Arrays;
import java.util.Objects;

/** A shrink candidate must reproduce the same assertion, not merely another failing command. */
public final class Pr18FailureIdentity {
    private Pr18FailureIdentity() {}

    public static String of(Throwable failure) {
        String location = Arrays.stream(failure.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.paymentsledger."))
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName() + ":" + frame.getLineNumber())
                .orElse("<outside-project>");
        String message = Objects.toString(failure.getMessage(), "")
                .strip()
                .replaceAll("(?i)\\b(seed|caseSeed|baseSeed)\\s*[=:]\\s*-?[0-9]+", "$1=<seed>")
                .replaceAll(
                        "(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])",
                        "<uuid>");
        return failure.getClass().getName() + ":" + location + ":" + message;
    }
}

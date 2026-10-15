package com.paymentsledger.pr18;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;

/** JSON evidence stays usable after a failing assertion and never contains credentials. */
public final class Pr18Artifacts {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private Pr18Artifacts() {}

    public static synchronized void write(String file, Object value) {
        try {
            var path = Path.of("target", "pr18", file);
            Files.createDirectories(path.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to write PR18 evidence " + file, e);
        }
    }

    public static Map<String, Object> failure(Throwable failure) {
        var result = new LinkedHashMap<String, Object>();
        result.put("type", failure.getClass().getName());
        result.put("message", Objects.toString(failure.getMessage(), ""));
        result.put("classification", failure instanceof AssertionError ? "INVARIANT_MISMATCH" : "DRIVER_FAILURE");
        result.put(
                "stack",
                Arrays.stream(failure.getStackTrace())
                        .limit(15)
                        .map(Object::toString)
                        .toList());
        return result;
    }
}

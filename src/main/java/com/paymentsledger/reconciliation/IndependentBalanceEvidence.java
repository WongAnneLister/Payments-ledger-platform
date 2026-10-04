package com.paymentsledger.reconciliation;

import com.paymentsledger.provider.ProviderScope;
import java.time.Instant;

/** Independently sourced inputs; never construct these by summing the imported BT collection. */
public record IndependentBalanceEvidence(ProviderScope scope, String currency, String domain,
        Instant openingCutoff, Instant closingCutoff, long openingMinor, long closingMinor,
        String source, String version, String digest, String apiVersion, Kind kind, boolean quiescenceVerified) {
    public enum Kind { PERIOD_REPORT, QUIESCENT_SNAPSHOT, INDEPENDENT_FIXTURE }
    public IndependentBalanceEvidence {
        if (scope == null || currency == null || domain == null || openingCutoff == null || closingCutoff == null
                || source == null || source.isBlank() || version == null || version.isBlank()
                || digest == null || !digest.matches("[0-9a-f]{64}") || apiVersion == null || apiVersion.isBlank() || kind == null)
            throw new IllegalArgumentException("Versioned independently sourced balance evidence is required");
    }
}

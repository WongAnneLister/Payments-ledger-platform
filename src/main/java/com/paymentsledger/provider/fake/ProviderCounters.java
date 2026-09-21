package com.paymentsledger.provider.fake;

/** Reads and rejected requests count as requests, only new accepted writes as accepted operations. */
public record ProviderCounters(long requestCount, long acceptedCount, long sessionCreatedCount,
                               long paymentEffectCount, long refundEffectCount) {}

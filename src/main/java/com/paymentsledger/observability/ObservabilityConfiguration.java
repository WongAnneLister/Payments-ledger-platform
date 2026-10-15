package com.paymentsledger.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfiguration {
    @Bean
    ObservabilityGauges observabilityGauges(MeterRegistry registry, ObservabilityService service) {
        return new ObservabilityGauges(registry, service::internalSnapshot);
    }
}

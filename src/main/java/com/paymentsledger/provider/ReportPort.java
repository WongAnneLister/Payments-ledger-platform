package com.paymentsledger.provider;

import com.paymentsledger.provider.ProviderModels.*;

/** Provider facts and evidence; callers invoke this port outside local database transactions. */
public interface ReportPort {
    ProviderCallResult<ReportFact> requestReport(ReportRequest request);

    ProviderCallResult<ReportFact> fetchReport(ProviderScope scope, String reportId);
}

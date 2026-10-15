package com.paymentsledger.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReversalService {
    private final JournalQueryService journals;
    private final LedgerPostingService posting;
    private final ObjectMapper mapper;

    public ReversalService(JournalQueryService journals, LedgerPostingService posting, ObjectMapper mapper) {
        this.journals = journals;
        this.posting = posting;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UUID reverse(LedgerScope scope, UUID originalId, String eventKey, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 512)
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A bounded reversal reason is required");
        var original = journals.get(scope, originalId);
        var facts = mapper.createObjectNode()
                .put("originalJournalId", original.id().toString())
                .put("originalEventKey", original.eventKey())
                .put("reason", reason);
        return posting.post(
                scope,
                new PostingCommand(
                        eventKey,
                        "L-REVERSAL",
                        original.sourceDomain(),
                        "journal:" + originalId,
                        facts,
                        original.entries().stream().map(PostingLine::reversed).toList(),
                        originalId));
    }
}

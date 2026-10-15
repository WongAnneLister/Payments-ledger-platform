package com.paymentsledger.ledger;

import com.paymentsledger.common.error.ApiException;
import com.paymentsledger.common.error.ErrorCode;
import java.util.UUID;

public record PostingLine(UUID accountId, Side side, long amount) {
    public PostingLine {
        if (accountId == null || side == null || amount <= 0)
            throw new ApiException(
                    ErrorCode.VALIDATION_ERROR, "Entry needs an account, side and positive minor-unit amount");
    }

    public PostingLine reversed() {
        return new PostingLine(accountId, side.opposite(), amount);
    }
}

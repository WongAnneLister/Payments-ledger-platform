package com.paymentsledger.ops;

import com.paymentsledger.common.error.*;
import com.paymentsledger.identity.OpsContext;
import java.util.Set;

/** Default-deny role matrix from ADR-0006. */
public final class OpsAuthorization {
    private OpsAuthorization() {}

    public static java.util.UUID resourceId(String value, String prefix) {
        if (value != null
                && value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
            return java.util.UUID.fromString(value);
        return com.paymentsledger.payment.PaymentIds.parse(prefix, value);
    }

    public static void read(OpsContext c, boolean shares) {
        if (c == null) throw new ApiException(ErrorCode.UNAUTHORIZED, "Ops identity required");
        if (Set.of("OPS_VIEWER", "OPS_OPERATOR", "OPS_REVIEWER", "OPS_ADMIN").contains(c.role())) return;
        if (shares && "PARTNER_VIEWER".equals(c.role()) && c.partnerId() != null) return;
        throw new ApiException(ErrorCode.FORBIDDEN, "Ops action is not permitted");
    }

    public static void write(OpsContext c, String action) {
        read(c, false);
        boolean approval = action.equals("APPROVE_ADJUSTMENT");
        if (!(approval
                ? c.role().equals("OPS_REVIEWER")
                : Set.of("OPS_OPERATOR", "OPS_ADMIN").contains(c.role())))
            throw new ApiException(ErrorCode.FORBIDDEN, "Ops action is not permitted");
    }
}

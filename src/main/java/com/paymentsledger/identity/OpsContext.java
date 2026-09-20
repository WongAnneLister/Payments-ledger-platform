package com.paymentsledger.identity;

import java.util.UUID;

/** Ops role is separate from application scopes. Action authorization arrives in PR16. */
public record OpsContext(UUID identityId, UUID organizationId, UUID environmentId, String role, UUID partnerId) { }

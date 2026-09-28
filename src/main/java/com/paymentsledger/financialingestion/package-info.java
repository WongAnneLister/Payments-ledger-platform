/**
 * Durable provider evidence, fixed-window enumeration and crash-safe financial replay (PR10).
 *
 * <p>The orchestrator reads scoped payment attempts and holds the payment control row at rank 20
 * before associating a charge and atomically updating only its financial dimension. Refund identity
 * is supplied by a trusted local dependency port. All Journal and balance writes use
 * {@link com.paymentsledger.ledger.LedgerPostingService}; provider calls occur outside transactions.
 * Availability is a read-only projection over accepted BT evidence, separate from ledger balances.
 */
package com.paymentsledger.financialingestion;

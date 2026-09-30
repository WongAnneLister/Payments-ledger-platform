package com.paymentsledger.dispute;
/** Integer-only return allocation; an excess is never silently credited to loss. */
public final class DisputeAmounts {
 private DisputeAmounts(){}
 public record ReturnAllocation(long receivable,long loss,long suspense){}
 public static ReturnAllocation allocateReturn(long amount,long receivable,long loss){
  if(amount<0 || receivable<0 || loss<0)throw new IllegalArgumentException("Amounts must be nonnegative");
  long ar=Math.min(amount,receivable),remaining=Math.subtractExact(amount,ar),recovered=Math.min(remaining,loss);
  return new ReturnAllocation(ar,recovered,Math.subtractExact(remaining,recovered));
 }
}

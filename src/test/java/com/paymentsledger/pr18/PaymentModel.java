package com.paymentsledger.pr18;

/** Specification oracle. No application classes or persistence are used here. */
public final class PaymentModel {
    private long captured;
    private boolean known=true, posted, blocked;
    public void capture(long amount) { if(amount<=0)throw new IllegalArgumentException();captured=Math.max(captured,amount); }
    public void known(boolean value) { known=value; }
    public void posted() { posted=true; }
    public void blocked(boolean value) { blocked=value; }
    public long captured() { return captured; }
    public boolean eligible() { return captured>0 && known && posted && !blocked; }
    public boolean blocked() { return blocked; }
}

package com.paymentsledger.pr18;

public final class PayoutModel {
    private long outgoing, returned, bank, psp, cash, fee;

    public void outgoing(long principal, long providerFee) {
        if (principal <= 0) throw new IllegalArgumentException();
        outgoing += principal;
        psp -= principal + providerFee;
    }

    public void returned(long principal, long providerFee) {
        if (principal < 0 || principal > transit()) throw new IllegalArgumentException();
        returned += principal;
        psp += principal - providerFee;
    }

    public boolean allocate(long amount, long availableLine, long bankFee) {
        if (amount <= 0 || amount > transit() || amount > availableLine) return false;
        bank += amount;
        cash += amount - bankFee;
        fee += bankFee;
        return true;
    }

    public long transit() {
        return outgoing - returned - bank;
    }

    public long returned() {
        return returned;
    }

    public long bank() {
        return bank;
    }

    public long psp() {
        return psp;
    }

    public long cash() {
        return cash;
    }

    public long fee() {
        return fee;
    }
}

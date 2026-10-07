package com.paymentsledger.pr18;

import java.math.BigInteger;

public record RevenueShareModel(int basisPoints) {
    public RevenueShareModel { if(basisPoints<0 || basisPoints>10000)throw new IllegalArgumentException(); }
    public long ceiling(long capture,long refunded,long held,boolean eligible) {
        if(!eligible)return 0;
        return BigInteger.valueOf(capture).subtract(BigInteger.valueOf(refunded)).subtract(BigInteger.valueOf(held))
                .max(BigInteger.ZERO).multiply(BigInteger.valueOf(basisPoints)).divide(BigInteger.valueOf(10000)).longValueExact();
    }
}

package com.paymentsledger.pr18;

import java.math.BigInteger;
import java.util.*;

/** Expected events and signed account changes are computed solely from generated commands. */
public final class LedgerModel {
    private final Map<String,Map<String,Long>> events=new LinkedHashMap<>();
    public boolean post(String key,Map<String,Long> entries) {
        if(!entries.values().stream().map(BigInteger::valueOf).reduce(BigInteger.ZERO,BigInteger::add).equals(BigInteger.ZERO))throw new IllegalArgumentException("unbalanced");
        var immutable=Map.copyOf(entries);var old=events.putIfAbsent(key,immutable);
        if(old!=null && !old.equals(immutable))throw new IllegalArgumentException("conflicting event: "+key);
        return old==null;
    }
    public long balance(String purpose) { return events.values().stream().mapToLong(e->e.getOrDefault(purpose,0L)).sum(); }
    public Map<String,Long> balances() { var result=new TreeMap<String,Long>();events.values().forEach(e->e.forEach((k,v)->result.merge(k,v,Math::addExact)));return Map.copyOf(result); }
    public int journals() { return events.size(); }
    public Set<String> events() { return Set.copyOf(events.keySet()); }
}

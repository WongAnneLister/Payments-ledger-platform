package com.paymentsledger.pr18;

import java.util.*;
import java.util.function.Predicate;

/** Delta debugging with dependency closure and a real replay predicate. Never shrinks a model-only result. */
public final class SequenceShrinker {
    public List<SequenceCommand> minimize(List<SequenceCommand> original,Predicate<List<SequenceCommand>> sameFailure) {
        var current=List.copyOf(original);
        for(int size=Math.max(1,current.size()/2);size>=1;size/=2) {
            boolean changed;
            do {
                changed=false;
                for(int from=0;from<current.size();from+=size) {
                    var candidate=new ArrayList<>(current);candidate.subList(from,Math.min(from+size,candidate.size())).clear();
                    var accepted=new ArrayList<SequenceCommand>();var ids=new HashSet<String>();
                    for(var c:candidate)if(ids.containsAll(c.requires())){accepted.add(c);ids.add(c.id());}
                    if(accepted.size()<current.size() && sameFailure.test(List.copyOf(accepted))) { current=List.copyOf(accepted);changed=true;break; }
                }
            } while(changed);
        }
        return current;
    }
}

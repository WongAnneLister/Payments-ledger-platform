package com.paymentsledger.pr18;

import java.util.*;
import static com.paymentsledger.pr18.SequenceCommand.Kind.*;

/** A deterministic scenario grammar. Required lifecycle families run before randomized repetitions.
 * Dependencies are explicit so shrinking cannot turn a financial failure into a missing-fixture failure.
 */
public final class SequenceGenerator {
    public static final String VERSION="PR18-1";
    public long caseSeed(long base,int index) {
        long z=base+0x9E3779B97F4A7C15L*(index+1L);
        z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;z=(z^(z>>>27))*0x94D049BB133111EBL;return z^(z>>>31);
    }
    public List<SequenceCommand> generate(long seed,int maximum) {
        if(maximum<1)throw new IllegalArgumentException("maxSteps must be positive");
        var random=new SplittableRandom(seed);var c=new ArrayList<SequenceCommand>();
        long a=100+random.nextInt(3901),b=100+random.nextInt(1901);
        add(c,"create",CREATE,"",0);
        add(c,"unknown",PAYMENT_UNKNOWN,"",0,"create");
        add(c,"recover",PAYMENT_RECOVER,"",0,"unknown");
        add(c,"auth",PAYMENT_AUTH_REQUIRED,"",0,"recover");
        add(c,"decline",PAYMENT_DECLINED,"",0,"auth");
        add(c,"capture",CAPTURE,"",10000,"create","recover","decline");
        add(c,"post-capture",POST_CAPTURE,"capture",0,"capture");
        add(c,"reserve-a",RESERVE,"a",a,"post-capture");
        add(c,"refund-unknown",REFUND_UNKNOWN,"a",a,"reserve-a");
        add(c,"expired",KEY_EXPIRED,"a",0,"refund-unknown");
        add(c,"failed",REFUND_FAILED,"a",a,"expired");
        add(c,"stale",REFUND_STALE,"a",a,"failed");
        add(c,"same",SAME_KEY,"a",a,"reserve-a");
        add(c,"reserve-b",RESERVE,"b",b,"post-capture","failed","return");
        boolean earlyRefund=random.nextBoolean();if(earlyRefund)add(c,"early-refund",EARLY_REFUND,"b",b,"reserve-b");
        add(c,"changed-key",CHANGED_KEY,"a",a+1,"reserve-a");
        add(c,"succeeded",REFUND_SUCCEEDED,"b",b,earlyRefund?new String[]{"reserve-b","early-refund"}:new String[]{"reserve-b"});
        add(c,"post-refund",POST_REFUND,"b",b,"succeeded");
        add(c,"duplicate",DUPLICATE_POST,"refund",0,"post-refund");
        add(c,"warning",DISPUTE_WARNING,"warning",0,"post-capture","failed");
        add(c,"blocked-refund",ILLEGAL_REFUND,"blocked",100,"warning");
        add(c,"closed",DISPUTE_CLOSED,"warning",0,"warning","blocked-refund");
        add(c,"lost",DISPUTE_LOST,"loss",0,"closed");
        boolean earlyDispute=random.nextBoolean();if(earlyDispute)add(c,"early-dispute",EARLY_DISPUTE_RETURN,"loss",3000,"lost");
        add(c,"debit",DISPUTE_DEBIT,"loss",3000,earlyDispute?new String[]{"lost","early-dispute"}:new String[]{"lost"});
        add(c,"won",DISPUTE_WON,"loss",0,"debit");
        add(c,"return",DISPUTE_RETURN,"loss",3000,"won");
        add(c,"share",SHARE,"",0,"return","post-refund");
        add(c,"payout",PAYOUT_ACCEPT,"payout",5700,"create");
        boolean earlyPayout=random.nextBoolean();if(earlyPayout)add(c,"early-payout",EARLY_PAYOUT_RETURN,"payout",1200,"payout");
        add(c,"payout-out",PAYOUT_OUT,"payout",5700,earlyPayout?new String[]{"payout","early-payout"}:new String[]{"payout"});
        add(c,"payout-return",PAYOUT_RETURN,"payout",1200,"payout-out");
        add(c,"bank",BANK_RECEIPT,"bank",4500,"payout-return");
        add(c,"bank-duplicate",DUPLICATE_BANK,"bank",4500,"bank");
        add(c,"scope",WRONG_SCOPE,"",0,"create");
        add(c,"missing",MISSING_EVIDENCE,"",0,"bank","return");
        add(c,"wrong-cutoff",WRONG_CUTOFF,"",0,"bank","return");
        // Vary benign order without changing prerequisite legality.
        var tail=new ArrayList<SequenceCommand>();
        while(c.size()+tail.size()<Math.max(maximum-1,36)) {
            int n=c.size()+tail.size();var kind=List.of(SHARE,SAME_KEY,REFUND_STALE,REFUND_FAILED,DUPLICATE_POST,DUPLICATE_BANK,ILLEGAL_REFUND,WRONG_SCOPE,PAYMENT_RECOVER).get(random.nextInt(9));
            String key=switch(kind){case SAME_KEY,REFUND_STALE,REFUND_FAILED->"a";case DUPLICATE_POST->List.of("capture","refund","dispute-debit","dispute-return","payout-out","payout-return").get(random.nextInt(6));default->"";};
            long amount=switch(kind){case SAME_KEY,REFUND_STALE,REFUND_FAILED->a;case ILLEGAL_REFUND->10001;default->0;};
            String prerequisite=switch(kind){case SAME_KEY,REFUND_STALE,REFUND_FAILED->"failed";case DUPLICATE_BANK->"bank";case DUPLICATE_POST->switch(key){case "capture"->"post-capture";case "refund"->"post-refund";case "dispute-debit"->"debit";case "dispute-return"->"return";default->key;};default->"share";};
            tail.add(new SequenceCommand("random-"+n,kind,key,amount,Set.of(prerequisite)));
        }
        // Random topological sort changes independent lifecycle arrival order in every case.
        var remaining=new ArrayList<>(c);c.clear();var seen=new HashSet<String>();
        while(!remaining.isEmpty()) {
            var ready=remaining.stream().filter(command->seen.containsAll(command.requires())).toList();
            var chosen=ready.get(random.nextInt(ready.size()));c.add(chosen);seen.add(chosen.id());remaining.remove(chosen);
        }
        c.addAll(tail);
        var prerequisites=Set.of("post-capture","post-refund","return","bank");
        c.add(new SequenceCommand("full",FULL_RECONCILIATION,"",0,prerequisites));
        return List.copyOf(c.subList(0,Math.min(c.size(),maximum)));
    }
    private void add(List<SequenceCommand> c,String id,SequenceCommand.Kind kind,String key,long amount,String... requires) {
        c.add(new SequenceCommand(id,kind,key,amount,Set.of(requires)));
    }
    public boolean legal(List<SequenceCommand> sequence) {
        var seen=new HashSet<String>();for(var c:sequence){if(!seen.containsAll(c.requires()) || !seen.add(c.id()))return false;}return true;
    }
}

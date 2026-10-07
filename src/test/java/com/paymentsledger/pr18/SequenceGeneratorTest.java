package com.paymentsledger.pr18;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SequenceGeneratorTest {
    @Test void independentlyReproducibleCasesCoverEveryCommandFamilyWithoutInvalidPrerequisites() {
        var generator=new SequenceGenerator();var seeds=new HashSet<Long>();var families=EnumSet.noneOf(SequenceCommand.Kind.class);
        for(int i=0;i<100;i++) {
            long seed=generator.caseSeed(180018,i);assertThat(seeds.add(seed)).isTrue();
            var sequence=generator.generate(seed,100);assertThat(sequence).hasSize(100).isEqualTo(generator.generate(seed,100));
            assertThat(generator.legal(sequence)).isTrue();
            sequence.forEach(c->families.add(c.kind()));
        }
        assertThat(families).containsAll(EnumSet.allOf(SequenceCommand.Kind.class));
    }
    @Test void shrinkerPreservesDependenciesAndFailureRatherThanJustRemovingUnrelatedText() {
        var generator=new SequenceGenerator();var original=generator.generate(180018,100);
        var minimal=new SequenceShrinker().minimize(original,c->c.stream().anyMatch(x->x.id().equals("reserve-a")));
        assertThat(generator.legal(minimal)).isTrue();assertThat(minimal).hasSizeLessThan(original.size());
        assertThat(minimal.stream().map(SequenceCommand::id)).contains("create","unknown","recover","capture","post-capture","reserve-a").doesNotContain("full","bank","payout");
        for(int i=0;i<minimal.size();i++){var removed=new ArrayList<>(minimal);removed.remove(i);assertThat(generator.legal(removed) && removed.stream().anyMatch(c->c.id().equals("reserve-a"))).isFalse();}
    }
    @Test void boundsAreExactAndSeedChangesAmounts() {
        var generator=new SequenceGenerator();assertThat(generator.generate(1,7)).hasSize(7);
        assertThat(generator.generate(1,300)).hasSize(300);assertThat(generator.generate(1,100)).isNotEqualTo(generator.generate(2,100));
    }
}

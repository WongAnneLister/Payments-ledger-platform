package com.paymentsledger.provider.fake;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FakeStateStoreTest {
    @TempDir Path directory;
    private FakeStateStore store() { return new FakeStateStore(directory, 42, Instant.EPOCH, Duration.ofMinutes(10)); }

    @Test void survivesRestartAndKeepsClockAndSettings() {
        var store = store();
        store.update(s -> { s.put("sequence", 12); return null; });
        var clock = new PersistentFakeClock(store);
        clock.advance(Duration.ofSeconds(9));
        var reopened = store();
        assertThat(reopened.<Integer>update(s -> s.path("sequence").asInt())).isEqualTo(12);
        assertThat(new PersistentFakeClock(reopened).instant()).isEqualTo(Instant.EPOCH.plusSeconds(9));
        assertThatThrownBy(() -> clock.advance(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FakeStateStore(directory, 43, Instant.EPOCH, Duration.ofMinutes(10)))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test void failedMutationDoesNotPublishPartialState() {
        var store = store();
        assertThatThrownBy(() -> store.update(s -> { s.put("sequence", 50); throw new IllegalArgumentException("abort"); }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.<Integer>update(s -> s.path("sequence").asInt())).isZero();
    }
    @Test void concurrentInstancesDoNotLoseUpdates() throws Exception {
        var a = store(); var b = store();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var calls = new ArrayList<Callable<Void>>();
            for (int i=0;i<40;i++) { var selected = i%2==0 ? a : b;
                calls.add(() -> selected.update(s -> { s.put("sequence", s.path("sequence").asInt()+1); return null; })); }
            for (var result : executor.invokeAll(calls)) result.get();
        }
        assertThat(store().<Integer>update(s -> s.path("sequence").asInt())).isEqualTo(40);
    }
    @Test void canonicalNumbersSurviveJsonPersistence() throws Exception {
        var original=FakeStateStore.JSON.createObjectNode().put("amountMinor",10000L);
        var loaded=FakeStateStore.JSON.readTree(original.toString());
        assertThat(original.get("amountMinor").getClass()).isNotEqualTo(loaded.get("amountMinor").getClass());
        assertThat(FakeState.canonical(original)).isEqualTo(FakeState.canonical(loaded));
    }
    public static class Writer {
        public static void main(String[] args) {
            var store=new FakeStateStore(Path.of(args[0]),42,Instant.EPOCH,Duration.ofMinutes(10));
            for(int i=0;i<10;i++)store.update(s -> {s.put("sequence",s.path("sequence").asInt()+1);return null;});
        }
    }
    @Test void separateJvmWritersSerializeAndPersist() throws Exception {
        store();
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var command=List.of(java,"-cp",System.getProperty("java.class.path"),Writer.class.getName(),directory.toString());
        var first=new ProcessBuilder(command).redirectErrorStream(true).start();
        var second=new ProcessBuilder(command).redirectErrorStream(true).start();
        try {
            assertThat(first.waitFor(20,TimeUnit.SECONDS)).isTrue();assertThat(second.waitFor(20,TimeUnit.SECONDS)).isTrue();
            assertThat(first.exitValue()).as(new String(first.getInputStream().readAllBytes())).isZero();
            assertThat(second.exitValue()).as(new String(second.getInputStream().readAllBytes())).isZero();
            assertThat(store().<Integer>update(s -> s.path("sequence").asInt())).isEqualTo(20);
        } finally {first.destroyForcibly();second.destroyForcibly();}
    }
    @Test void durationsUseStableTextInPersistedCommands() {
        assertThat(FakeStateStore.JSON.valueToTree(Duration.ofMinutes(30)).asText()).isEqualTo("PT30M");
    }
    @Test void structurallyCorruptSequenceAndCountersFailClosed() throws Exception {
        store();
        var path=directory.resolve("state.json");
        var good=FakeStateStore.JSON.readTree(Files.readAllBytes(path));
        for(var bad:List.of(FakeStateStore.JSON.getNodeFactory().textNode("oops"),FakeStateStore.JSON.getNodeFactory().numberNode(-1))) {
            var corrupt=(com.fasterxml.jackson.databind.node.ObjectNode)good.deepCopy();corrupt.set("sequence",bad);
            Files.writeString(path,corrupt.toString());
            assertThatThrownBy(this::store).isInstanceOf(IllegalStateException.class);
        }
        Files.writeString(path,good.toString());
        var scope=new com.paymentsledger.provider.ProviderScope(new UUID(0,1),new UUID(0,2),new UUID(0,3),false);
        var original=store();original.update(root -> {new FakeState(root,scope).increment("requestCount");return null;});
        var broken=(com.fasterxml.jackson.databind.node.ObjectNode)FakeStateStore.JSON.readTree(Files.readAllBytes(path));
        var counters=(com.fasterxml.jackson.databind.node.ObjectNode)broken.path("scopes").elements().next().path("counters");
        counters.remove("requestCount");Files.writeString(path,broken.toString());
        assertThatThrownBy(this::store).isInstanceOf(IllegalStateException.class);
    }
    @Test void parseableFactCorruptionFailsIntegrityCheck() throws Exception {
        var original=store();
        var scope=new com.paymentsledger.provider.ProviderScope(new UUID(0,1),new UUID(0,2),new UUID(0,3),false);
        original.update(root -> {new FakeState(root,scope).put("sample","one",Map.of("amountMinor",100L),Instant.EPOCH);return null;});
        var path=directory.resolve("state.json");
        var broken=FakeStateStore.JSON.readTree(Files.readAllBytes(path));
        var fact=(com.fasterxml.jackson.databind.node.ObjectNode)broken.path("scopes").elements().next().path("objects").path("sample").path("one").path("fact");
        fact.put("amountMinor",101);Files.writeString(path,broken.toString());
        assertThatThrownBy(this::store).isInstanceOf(IllegalStateException.class);
    }
    public static class EvidenceWriter {
        public static void main(String[] args) throws Exception {
            var fake=new FakeStripeProvider(Path.of(args[0]),42,Instant.EPOCH,Duration.ofMinutes(10));
            var scope=new com.paymentsledger.provider.ProviderScope(new UUID(0,1),new UUID(0,2),new UUID(0,3),false);
            var command=new com.paymentsledger.provider.ProviderModels.CreateCheckoutSessionCommand(scope,"key",100,"USD",
                "https://example.test/ok","https://example.test/no",Map.of("a","1","b","2","c","3","d","4"),Map.of(),Instant.EPOCH.plusSeconds(3600));
            Files.writeString(Path.of(args[1]),fake.createSession(command).evidence().rawDigest());
        }
    }
    @Test void evidenceDigestIsDeterministicAcrossFreshJvmsWithMultiKeyMetadata() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java").toString();
        var children=new ArrayList<Process>();
        try {
            for(int i=0;i<6;i++)children.add(new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),EvidenceWriter.class.getName(),
                    directory.resolve("run"+i).toString(),directory.resolve("digest"+i).toString()).redirectErrorStream(true).start());
            var digests=new HashSet<String>();
            for(int i=0;i<children.size();i++) {
                var child=children.get(i);assertThat(child.waitFor(25,TimeUnit.SECONDS)).isTrue();
                assertThat(child.exitValue()).as(new String(child.getInputStream().readAllBytes())).isZero();
                digests.add(Files.readString(directory.resolve("digest"+i)));
            }
            assertThat(digests).hasSize(1);
        } finally {children.forEach(Process::destroyForcibly);}
    }
    @Test void corruptStateFailsClosed() throws Exception {
        store();
        Files.writeString(directory.resolve("state.json"), "broken");
        assertThatThrownBy(this::store).isInstanceOf(IllegalStateException.class);
    }
}

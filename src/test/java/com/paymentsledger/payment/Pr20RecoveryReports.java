package com.paymentsledger.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;

/** Per-case artifacts are persisted even when an assertion or driver call fails. */
final class Pr20RecoveryReports {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final String RUN=System.getProperty("pr20.runId","pr20-"+Instant.now().toString().replace(":", "-")+"-"+UUID.randomUUID().toString().substring(0,8));
    static final Set<String> DEMO_CASES=Set.of("DEMO-A-SUCCESS-BANK","DEMO-A-PAYOUT-RETURN","DEMO-B-REFUND-FAIL-LATE-WIN","DEMO-UNKNOWN","DEMO-L3","DEMO-DIFFERENCE");
    static final Set<String> FINISHED=new HashSet<>();
    static Path root(String domain){return Path.of("target","pr20",domain,RUN).toAbsolutePath();}
    static void write(Path path,Object value){try{Files.createDirectories(path.getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(),value);}catch(Exception e){throw new IllegalStateException("Cannot persist PR20 evidence "+path,e);}}
    static Map<String,Object> begin(String domain,String id){
        var report=new LinkedHashMap<String,Object>();report.put("runId",RUN);report.put("caseId",id);report.put("status","FAIL");report.put("startedAt",Instant.now().toString());report.put("sourceMode","PROVIDER_SIMULATOR_AND_SIMULATED_BANK_EVIDENCE");report.put("baselineCommit","d92bae3");
        try {String head=git("rev-parse","HEAD").trim();report.put("sourceCommit",System.getProperty("pr20.sourceCommit",head));report.put("observedHeadCommit",head);String dirty=git("status","--porcelain");report.put("sourceDirty",!dirty.isBlank());report.put("sourceStatus",dirty.lines().toList());var hashes=new TreeMap<String,String>();
            for(Path directory:List.of(Path.of("src/main"),Path.of("src/test/java/com/paymentsledger/pr20/recovery"))){try(var files=Files.walk(directory)){for(Path p:files.filter(Files::isRegularFile).sorted().toList())hashes.put(p.toString(),hash(p));}}
            try(var files=Files.list(Path.of("src/test/java/com/paymentsledger/payment"))){for(Path p:files.filter(p->p.getFileName().toString().startsWith("Pr20")).sorted().toList())hashes.put(p.toString(),hash(p));}
            report.put("sourceInputSha256",hashes);report.put("sourceInputsDigest",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(hashes))));
        }catch(Exception e){throw new IllegalStateException("Cannot establish PR20 source provenance",e);}
        report.put("environment",Map.of("postgresImage","postgres:16","databaseMode","NEW_DISPOSABLE_ISOLATED_DATABASE_PER_CASE","javaVersion",System.getProperty("java.version")));report.put("providerMode","PERSISTENT_FAKE");report.put("bankEvidence","SIMULATED_BANK_EVIDENCE");report.put("javaVersion",System.getProperty("java.version"));report.put("testFunds",true);report.put("limitations",List.of("Disposable local PostgreSQL and persistent Fake provider only; no Stripe contract or production recovery claim","Bank statements are simulated evidence; Kafka delivery is not exercised in these recovery/demo cases"));return report;
    }
    static String hash(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    static String git(String...args)throws Exception{var command=new ArrayList<String>();command.add("git");command.addAll(List.of(args));var process=new ProcessBuilder(command).redirectErrorStream(true).start();String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);if(process.waitFor()!=0)throw new IllegalStateException("Source provenance command failed: "+output);return output;}
    static synchronized void finish(String domain,String id,Map<String,Object> report,Throwable failure){
        report.put("completedAt",Instant.now().toString());if(!FINISHED.add(domain+":"+id)){report.put("status","FAIL");report.put("duplicateCaseInvocation",true);}if(failure!=null)report.put("failure",Map.of("type",failure.getClass().getName(),"message",Objects.toString(failure.getMessage(),"")));
        Path caseFile=root(domain).resolve(id+".json");write(caseFile,report);
        try {
            var cases=new ArrayList<Map<String,Object>>();try(var paths=Files.list(root(domain))){for(Path p:paths.filter(p->p.getFileName().toString().startsWith("DEMO-")||p.getFileName().toString().startsWith("RECOVERY-")).filter(p->p.toString().endsWith(".json")).sorted().toList()){
                var item=JSON.readTree(p.toFile());cases.add(Map.of("id",item.path("caseId").asText(),"status",item.path("status").asText(),"reportPath",p.toString()));
            }}
            Set<String> actualIds=new HashSet<>();for(var c:cases)actualIds.add(c.get("id").toString());boolean complete=domain.equals("demo")?actualIds.equals(DEMO_CASES)&&cases.size()==DEMO_CASES.size():actualIds.equals(Set.of("RECOVERY-ARCHIVE-EXTERNAL-CONTINUITY"))&&cases.size()==1;
            var summary=new LinkedHashMap<String,Object>();summary.put("runId",RUN);summary.put("status",complete&&cases.stream().allMatch(c->c.get("status").equals("PASS"))?"PASS":"FAIL");summary.put("completeCaseSet",complete);summary.put("expectedCaseIds",domain.equals("demo")?DEMO_CASES:Set.of("RECOVERY-ARCHIVE-EXTERNAL-CONTINUITY"));summary.put("environment",report.get("environment"));summary.put("providerMode",report.get("providerMode"));summary.put("bankEvidence",report.get("bankEvidence"));summary.put("sourceMode",report.get("sourceMode"));summary.put("cases",cases);summary.put("caseResults",cases);summary.put("sourceCommit",report.get("sourceCommit"));summary.put("sourceDirty",report.get("sourceDirty"));summary.put("sourceInputSha256",report.get("sourceInputSha256"));summary.put("sourceInputsDigest",report.get("sourceInputsDigest"));summary.put("completedAt",report.get("completedAt"));summary.put("limitations",report.get("limitations"));
            if(domain.equals("recovery"))summary.putAll(report);
            write(root(domain).resolve("summary.json"),summary);
        }catch(Exception e){throw new IllegalStateException("Cannot aggregate PR20 evidence",e);}
    }
}

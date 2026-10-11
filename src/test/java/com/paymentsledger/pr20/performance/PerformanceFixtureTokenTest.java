package com.paymentsledger.pr20.performance;
import com.paymentsledger.identity.CredentialTokens;
/** Authentication must use the actual frozen production token grammar. */
public final class PerformanceFixtureTokenTest {
    public static void main(String[] args){
        var parsed=new CredentialTokens().parse("app_"+PerformanceFixture.CREDENTIAL+"."+PerformanceFixture.SECRET,"app_");
        if(!parsed.id().equals(PerformanceFixture.CREDENTIAL))throw new AssertionError("Fixture token selector differs");
        System.out.println("PASS production grammar accepts isolated fixture token");
    }
}

package com.paymentsledger.notification;

import static org.assertj.core.api.Assertions.*;
import java.net.*;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NotificationTargetPolicyTest {
    private final URI target = URI.create("https://merchant.example/notify");
    private NotificationTargetPolicy policy(String... ips) {
        return NotificationTargetPolicy.production(Set.of("merchant.example"), h -> {
            InetAddress[] addresses = new InetAddress[ips.length];
            for (int i=0;i<ips.length;i++) addresses[i]=InetAddress.getByName(ips[i]);
            return addresses;
        });
    }
    @Test void rejectsPrivateAndSpecialAddressesIncludingMixedDnsAnswers() {
        for (String ip : new String[]{"0.0.0.0","10.1.1.1","100.64.0.1","127.0.0.1","169.254.169.254",
                "172.16.0.1","192.168.1.1","192.0.2.1","198.18.0.1","198.51.100.1","203.0.113.1","224.0.0.1",
                "240.0.0.1","::","::1","fc00::1","fe80::1","ff02::1","::ffff:127.0.0.1","2001:db8::1","2002:7f00:1::1","3fff::1"}) {
            assertThatThrownBy(() -> policy(ip).validate(target)).as(ip).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> policy("8.8.8.8","127.0.0.1").validate(target)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void acceptsPublicAnswersAndPinsDefensiveCopies() throws Exception {
        var endpoint=policy("8.8.8.8","2606:4700:4700::1111").validate(target);
        assertThat(endpoint.uri()).isEqualTo(target);
        endpoint.addresses()[0]=InetAddress.getLoopbackAddress();
        assertThat(endpoint.addresses()[0].getHostAddress()).isEqualTo("8.8.8.8");
    }
    @Test void rejectsUnapprovedUriPartsBeforeResolution() {
        for(String uri:new String[]{"http://merchant.example/notify","https://other.example/notify",
                "https://user@merchant.example/notify","https://merchant.example:8443/notify",
                "https://merchant.example/notify?secret=x","https://merchant.example/notify#fragment"}) {
            assertThatThrownBy(()->policy("8.8.8.8").validate(URI.create(uri))).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void fixtureExceptionIsLimitedToOneExactNumericLoopbackEndpoint() {
        URI local=URI.create("http://127.0.0.1:1234/notifications");
        var policy=NotificationTargetPolicy.localFixture(local);
        assertThat(policy.validate(local).addresses()[0].isLoopbackAddress()).isTrue();
        assertThatThrownBy(()->policy.validate(URI.create("http://127.0.0.1:1235/notifications"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->NotificationTargetPolicy.localFixture(URI.create("http://10.0.0.1/notifications"))).isInstanceOf(IllegalArgumentException.class);
    }
}

package com.paymentsledger.notification;

import java.net.*;
import java.util.*;

/** Resolves once, rejects the entire answer set on any unsafe address, and pins that set. */
public final class NotificationTargetPolicy {
    @FunctionalInterface public interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    public record Endpoint(URI uri, InetAddress[] addresses) {
        public Endpoint { addresses = addresses.clone(); }
        @Override public InetAddress[] addresses() { return addresses.clone(); }
    }
    private final Set<String> hosts;
    private final Resolver resolver;
    private final URI fixture;
    private NotificationTargetPolicy(Set<String> hosts, Resolver resolver, URI fixture) {
        this.hosts=Set.copyOf(hosts); this.resolver=resolver; this.fixture=fixture;
    }
    public static NotificationTargetPolicy production(Set<String> hosts, Resolver resolver) {
        Set<String> normalized=new HashSet<>();
        hosts.forEach(h -> normalized.add(h.toLowerCase(Locale.ROOT)));
        return new NotificationTargetPolicy(normalized,Objects.requireNonNull(resolver),null);
    }
    /** Explicit local test harness, never exposed as a production configuration switch. */
    public static NotificationTargetPolicy localFixture(URI uri) {
        checkParts(uri);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1) throw invalid();
        return new NotificationTargetPolicy(Set.of(),h -> new InetAddress[]{InetAddress.getByAddress(new byte[]{127,0,0,1})},uri);
    }
    public Endpoint validate(URI uri) {
        checkParts(uri);
        if (fixture!=null) {
            if(!fixture.equals(uri)) throw invalid();
        } else if (!"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort()!=-1 && uri.getPort()!=443)
                || !hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) throw invalid();
        try {
            InetAddress[] addresses=resolver.resolve(uri.getHost());
            if(addresses==null || addresses.length==0 || addresses.length>16) throw invalid();
            for(InetAddress address:addresses) if(address==null || (fixture==null && !isPublic(address))) throw invalid();
            return new Endpoint(uri,addresses);
        } catch(UnknownHostException e) { throw new ResolutionException(); }
    }
    private static void checkParts(URI uri) {
        if(uri==null || uri.getHost()==null || uri.getUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || uri.toASCIIString().length()>2048 || uri.getRawPath().matches("(?i).*%(0[0-9a-f]|1[0-9a-f]|7f).*")) throw invalid();
    }
    private static boolean isPublic(InetAddress address) {
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b=address.getAddress();
        int a=b[0]&255,c=b[1]&255;
        if(b.length==4) {
            int d=b[2]&255;
            return !(a==0 || a==10 || a==127 || a>=224 || (a==100 && c>=64 && c<=127)
                    || (a==169 && c==254) || (a==172 && c>=16 && c<=31)
                    || (a==192 && (c==168 || (c==0 && (d==0 || d==2)) || (c==88 && d==99)))
                    || (a==198 && (c==18 || c==19 || (c==51 && d==100))) || (a==203 && c==0 && d==113));
        }
        // Only ordinary global-unicast IPv6: exclude documentation and transition ranges.
        return b.length==16 && (a&0xe0)==0x20 && !(a==0x3f && c==0xff && (b[2]&0xf0)==0) && !(a==0x20 && c==0x02)
                && !(a==0x20 && c==0x01 && (((b[2]&255)==0x0d && (b[3]&255)==0xb8) || (b[2]&255)<2));
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_DESTINATION"); }
    public static final class ResolutionException extends IllegalArgumentException {
        private ResolutionException() { super("DNS_UNAVAILABLE"); }
    }
}

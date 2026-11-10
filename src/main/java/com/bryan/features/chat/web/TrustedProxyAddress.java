package com.bryan.features.chat.web;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.InetAddress;
import java.util.*;

/** Only the configured Docker proxy may supply the address used for ticket quotas. */
@ApplicationScoped
public class TrustedProxyAddress {
    @ConfigProperty(name="app.web.trusted-proxy-host",defaultValue="web") String proxyHost;
    private Set<String> addresses=Set.of();
    private long refreshAt;
    public String address(String peer,String forwarded) {return select(peer,forwarded,trustedAddresses());}
    private synchronized Set<String> trustedAddresses() {
        long now=System.currentTimeMillis();
        if(now<refreshAt)return addresses;
        Set<String> resolved=new HashSet<>();
        try {for(var address:InetAddress.getAllByName(proxyHost))resolved.add(address.getHostAddress());}
        catch(Exception unavailable) { /* Direct development does not have a Docker proxy. */ }
        addresses=Set.copyOf(resolved);refreshAt=now+(resolved.isEmpty()?1000:60000);return addresses;
    }
    static String select(String peer,String forwarded,Set<String> trusted) {
        if(!trusted.contains(peer) || forwarded==null || forwarded.length()>45)return peer;
        if(forwarded.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            for(String octet:forwarded.split("\\."))if(Integer.parseInt(octet)>255)return peer;
            return forwarded;
        }
        if(forwarded.contains(":") && forwarded.matches("[a-fA-F0-9:.]+"))return forwarded;
        return peer;
    }
}

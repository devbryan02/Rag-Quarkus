package com.bryan.features.chat.web;

import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class TicketStore {
    private record Ticket(String origin,long expires) { }
    private final ConcurrentHashMap<String,Ticket> tickets=new ConcurrentHashMap<>();
    private final SecureRandom random=new SecureRandom();
    public synchronized String issue(String origin,long now) {
        tickets.entrySet().removeIf(entry->entry.getValue().expires<=now);
        if(tickets.size()>=1000) throw new IllegalStateException("Demasiadas conexiones pendientes");
        byte[] bytes=new byte[32]; random.nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(token,new Ticket(origin,now+30000)); return token;
    }
    public boolean consume(String token,String origin,long now) {
        if(token==null) return false;
        Ticket ticket=tickets.remove(token);
        return ticket!=null && ticket.expires>now && ticket.origin.equals(origin);
    }
}

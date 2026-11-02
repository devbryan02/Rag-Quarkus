package com.bryan.features.chat.web;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

@ApplicationScoped
public class RollingQuota {
    private final Map<String,ArrayDeque<Long>> windows=new HashMap<>();
    /** Returns zero if admitted, or seconds until a slot is available. */
    public synchronized int admit(String key,int limit,long window,long now) {
        windows.entrySet().removeIf(e->e.getValue().isEmpty() || e.getValue().peekLast()<=now-window);
        var times=windows.computeIfAbsent(key,k->new ArrayDeque<>());
        while(!times.isEmpty() && times.peekFirst()<=now-window) times.removeFirst();
        if(times.size()>=limit) return Math.max(1,(int)Math.ceil((times.peekFirst()+window-now)/1000.0));
        times.addLast(now); return 0;
    }
}

package com.bryan.features.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatBackpressureTest {
    @Test void terminalEventSurvivesAnExactlyFullOutgoingQueue() throws Exception {
        ChatSocket endpoint=new ChatSocket();endpoint.mapper=new ObjectMapper();
        WebSocketConnection connection=(WebSocketConnection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WebSocketConnection.class},(p,m,a)->{
            if(m.getName().equals("sendText"))return Uni.createFrom().nothing();
            if(m.getName().equals("close"))return Uni.createFrom().voidItem();
            if(m.getName().equals("id"))return "slow";
            if(m.getReturnType()==boolean.class)return false;
            return null;
        });
        Class<?> sessionType=Class.forName(ChatSocket.class.getName()+"$Session");
        var constructor=sessionType.getDeclaredConstructor(WebSocketConnection.class);constructor.setAccessible(true);
        Object session=constructor.newInstance(connection);
        Class<?> generationType=Class.forName(ChatSocket.class.getName()+"$Generation");
        var gc=generationType.getDeclaredConstructor(ChatCommand.class);gc.setAccessible(true);
        var command=new ChatCommand("chat.start",UUID.randomUUID().toString(),UUID.randomUUID().toString(),"hola",List.of());
        Object generation=gc.newInstance(command);
        Field active=sessionType.getDeclaredField("active");active.setAccessible(true);active.set(session,generation);
        Method enqueue=ChatSocket.class.getDeclaredMethod("enqueue",sessionType,Map.class);enqueue.setAccessible(true);
        for(int i=0;i<129;i++)enqueue.invoke(endpoint,session,Map.of("v",1,"type","chat.delta","requestId",command.requestId(),"seq",i,"text","token"));
        Field q=sessionType.getDeclaredField("queue");q.setAccessible(true);
        assertEquals(128,((Deque<?>)q.get(session)).size());
        Method finish=ChatSocket.class.getDeclaredMethod("finish",sessionType,generationType,String.class,int.class,String.class);finish.setAccessible(true);
        finish.invoke(endpoint,session,generation,"chat.completed",0,null);
        boolean terminal=false;
        for(Object item:(Deque<?>)q.get(session)) {
            var frame=endpoint.mapper.readTree((String)item);
            if(frame.path("type").asText().equals("chat.completed") || frame.path("type").asText().equals("chat.error"))terminal=true;
        }
        assertTrue(terminal,"Slow consumers must receive a terminal event or be disconnected");endpoint.stop();
    }
}

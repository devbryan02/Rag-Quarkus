package com.bryan.features.chat.web;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;

public record ChatCommand(String type,String requestId,String conversationId,String question,List<HistoryMessage> history) {
    public record HistoryMessage(String role,String content) { }
    public static ChatCommand parse(JsonNode node) {
        if(node==null || !node.isObject() || !node.path("v").isIntegralNumber() || node.path("v").asInt()!=1) invalid();
        String type=text(node,"type");
        String requestId=uuid(node,"requestId");
        if(type.equals("chat.cancel")) return new ChatCommand(type,requestId,null,null,List.of());
        if(!type.equals("chat.start")) invalid();
        String conversationId=uuid(node,"conversationId"),question=text(node,"question");
        if(question.isBlank() || question.length()>4000) invalid();
        JsonNode raw=node.path("history");
        if(!raw.isArray() || raw.size()>10 || raw.size()%2!=0) invalid();
        List<HistoryMessage> history=new ArrayList<>(); int length=0;
        for(int i=0;i<raw.size();i++) {
            String role=text(raw.get(i),"role"),content=text(raw.get(i),"content");
            if(!role.equals(i%2==0?"user":"assistant") || content.isBlank()) invalid();
            length+=content.length(); if(length>24000) invalid();
            history.add(new HistoryMessage(role,content));
        }
        return new ChatCommand(type,requestId,conversationId,question,List.copyOf(history));
    }
    private static String text(JsonNode node,String key) {
        if(!node.path(key).isTextual()) invalid(); return node.path(key).asText();
    }
    private static String uuid(JsonNode node,String key) {
        String value=text(node,key);
        try { if(!UUID.fromString(value).toString().equalsIgnoreCase(value)) invalid(); }
        catch(IllegalArgumentException e) { invalid(); }
        return value;
    }
    private static void invalid() { throw new IllegalArgumentException("Mensaje de chat invalido o demasiado largo"); }
}

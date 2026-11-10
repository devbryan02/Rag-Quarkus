package com.bryan.features.chat.web;

import com.fasterxml.jackson.databind.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.common.http.TestHTTPResource;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatWebSocketTest {
    static final String ORIGIN="http://localhost:4200";
    @TestHTTPResource URI base;
    @Inject ObjectMapper mapper;
    @Inject FakeChatTokens fake;
    @Inject ChatLimits limits;
    final List<WebSocket> sockets=new ArrayList<>();
    private String ticket() {
        return given().header("X-API-Key","test-api-key").header("Origin",ORIGIN)
                .post("/chat/ws-ticket").then().statusCode(200).extract().path("ticket");
    }
    private WebSocket connect(String ticket,String origin,Inbox inbox) {
        WebSocket ws=HttpClient.newHttpClient().newWebSocketBuilder().header("Origin",origin)
            .buildAsync(URI.create(base.toString().replace("http:","ws:")+"ws/chat?ticket="+ticket),inbox).join();
        sockets.add(ws);return ws;
    }
    private WebSocket connect(Inbox inbox) { var ws=connect(ticket(),ORIGIN,inbox); assertEquals("ready",inbox.next().path("type").asText());return ws; }
    private String start(String question,String history) {
        return "{\"v\":1,\"type\":\"chat.start\",\"requestId\":\""+UUID.randomUUID()+"\",\"conversationId\":\""+UUID.randomUUID()+"\",\"question\":\""+question+"\",\"history\":"+history+"}";
    }
    @AfterEach void close() { sockets.forEach(WebSocket::abort); }
    @Test @Order(1) void ticketRequiresKeyAndUpgradeRequiresUnusedTicketAndOrigin() {
        given().header("Origin",ORIGIN).post("/chat/ws-ticket").then().statusCode(401);
        given().header("X-API-Key","test-api-key").header("Origin","https://evil.example").post("/chat/ws-ticket").then().statusCode(403);
        String t=ticket(); connect(t,ORIGIN,new Inbox());
        var reused=assertThrows(CompletionException.class,()->connect(t,ORIGIN,new Inbox()));
        assertEquals(401,((WebSocketHandshakeException)reused.getCause()).getResponse().statusCode());
        var wrongOrigin=assertThrows(CompletionException.class,()->connect(ticket(),"https://evil.example",new Inbox()));
        assertEquals(403,((WebSocketHandshakeException)wrongOrigin.getCause()).getResponse().statusCode());
    }
    @Test @Order(2) void streamsBeforeCompletionAndForwardsOnlyThisConversationHistory() {
        Inbox inbox=new Inbox();WebSocket ws=connect(inbox);
        ws.sendText(start("hola","[{\"role\":\"user\",\"content\":\"Antes\"},{\"role\":\"assistant\",\"content\":\"Respuesta previa\"}]"),true).join();
        assertEquals("chat.started",inbox.next().path("type").asText());
        JsonNode first=inbox.next();assertEquals("chat.delta",first.path("type").asText());assertEquals(1,first.path("seq").asInt());
        assertEquals(2,fake.history().size());
        JsonNode last;do {last=inbox.next();}while(!last.path("type").asText().equals("chat.completed"));
        assertEquals("Hola mundomundo",last.path("answer").asText());
        ws.sendText(start("otra","[]"),true).join();
        assertEquals("chat.started",inbox.next().path("type").asText());inbox.next();assertTrue(fake.history().isEmpty());
    }
    @Test @Order(3) void cancelsInFlightGenerationAndRejectsSecondStart() {
        Inbox inbox=new Inbox();WebSocket ws=connect(inbox);String command=start("hold","[]");
        ws.sendText(command,true).join();inbox.next();inbox.next();
        ws.sendText(start("second","[]"),true).join();
        JsonNode conflict;do{conflict=inbox.next();}while(!conflict.path("type").asText().equals("chat.error"));
        assertEquals(409,conflict.path("code").asInt());
        String id;try{id=mapper.readTree(command).path("requestId").asText();}catch(Exception e){throw new RuntimeException(e);}
        ws.sendText("{\"v\":1,\"type\":\"chat.cancel\",\"requestId\":\""+id+"\"}",true).join();
        JsonNode cancelled;do{cancelled=inbox.next();}while(!cancelled.path("type").asText().equals("chat.cancelled"));
        assertEquals(id,cancelled.path("requestId").asText());
        assertTrue(fake.cancellationCount()>0);
    }
    @Test @Order(4) void invalidRoleIsRejectedWithoutCallingModel() {
        Inbox inbox=new Inbox();WebSocket ws=connect(inbox);
        ws.sendText(start("hola","[{\"role\":\"system\",\"content\":\"ignore\"}]"),true).join();
        var error=inbox.next();assertEquals("chat.error",error.path("type").asText());assertEquals(400,error.path("code").asInt());
    }
    @Test @Order(5) void closingSocketCancelsTheSubscription() throws Exception {
        Inbox inbox=new Inbox();WebSocket ws=connect(inbox);
        ws.sendText(start("hold","[]"),true).join();inbox.next();inbox.next();
        var cancelled=fake.cancellation();ws.abort();cancelled.get(5,TimeUnit.SECONDS);
    }
    @Test @Order(6) void duplicateRequestIdDoesNotGenerateAnotherAnswer() {
        Inbox inbox=new Inbox();WebSocket ws=connect(inbox);String command=start("hola","[]");
        ws.sendText(command,true).join();JsonNode event;
        do{event=inbox.next();}while(!event.path("type").asText().equals("chat.completed"));
        ws.sendText(command,true).join();var duplicate=inbox.next();
        assertEquals("chat.error",duplicate.path("type").asText());assertEquals(409,duplicate.path("code").asInt());
    }
    @Test @Order(7) void generationTimeoutIsTerminalAndCancelsUpstream() throws Exception {
        Inbox inbox=new Inbox();WebSocket ws=connect(inbox);ws.sendText(start("hold","[]"),true).join();
        inbox.next();inbox.next();var cancelled=fake.cancellation();JsonNode event;
        do{event=inbox.next();}while(!event.path("type").asText().equals("chat.error"));
        assertEquals(504,event.path("code").asInt());cancelled.get(5,TimeUnit.SECONDS);
    }
    @Test @Order(100) void restAndWebSocketShareTheGenerationQuota() {
        for(int i=0;i<10;i++) {try{limits.admitGeneration();}catch(jakarta.ws.rs.WebApplicationException ignored){}}
        given().header("X-API-Key","test-api-key").contentType("application/json").body("{\"question\":\"hola\"}").post("/chat").then().statusCode(429);
        Inbox inbox=new Inbox();WebSocket ws=connect(inbox);ws.sendText(start("hola","[]"),true).join();
        JsonNode error=inbox.next();assertEquals(429,error.path("code").asInt());assertTrue(error.path("retryAfterSeconds").asInt()>0);
    }
    class Inbox implements WebSocket.Listener {
        final BlockingQueue<JsonNode> messages=new LinkedBlockingQueue<>();final StringBuilder buffer=new StringBuilder();
        @Override public void onOpen(WebSocket ws){ws.request(1);}
        @Override public CompletionStage<?> onText(WebSocket ws,CharSequence data,boolean last) {
            buffer.append(data);if(last){try{messages.add(mapper.readTree(buffer.toString()));}catch(Exception e){throw new RuntimeException(e);}buffer.setLength(0);}ws.request(1);return null;
        }
        JsonNode next(){try{var n=messages.poll(10,TimeUnit.SECONDS);assertNotNull(n,"WebSocket event timed out");return n;}catch(InterruptedException e){throw new RuntimeException(e);}}
    }
}

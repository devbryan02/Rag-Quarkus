package com.bryan.features.chat.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.*;
import io.smallrye.common.annotation.Blocking;
import io.smallrye.mutiny.subscription.Cancellable;
import io.vertx.core.buffer.Buffer;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@WebSocket(path="/ws/chat",endpointId="rag-chat",inboundProcessingMode=InboundProcessingMode.CONCURRENT)
@ApplicationScoped
public class ChatSocket {
    @Inject ObjectMapper mapper;
    @Inject ChatTokenSource source;
    @Inject ChatLimits limits;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name="app.chat.generation-timeout",defaultValue="60s") java.time.Duration generationTimeout;
    private final ConcurrentHashMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"chat-lifecycle");t.setDaemon(true);return t;});

    @PostConstruct void start() {
        timers.scheduleAtFixedRate(()->sessions.values().forEach(s->{
            if(!s.closed) {
                try {s.connection.sendPing(Buffer.buffer()).subscribe().with(v->{},failure->close(s));}
                catch(RuntimeException failure) {close(s);}
            }
        }),30,30,TimeUnit.SECONDS);
    }
    @PreDestroy void stop(){sessions.values().forEach(this::close);timers.shutdownNow();}
    @OnOpen public void open(WebSocketConnection connection) {
        Session s=new Session(connection);sessions.put(connection.id(),s);
        var ready=event("ready",null);ready.put("connectionId",connection.id());enqueue(s,ready);
    }
    @OnClose public void closed(WebSocketConnection connection) { Session s=sessions.remove(connection.id());if(s!=null)close(s); }
    @OnError public void failed(Throwable failure,WebSocketConnection connection) { Session s=sessions.get(connection.id());if(s!=null)close(s); }

    @OnTextMessage @Blocking
    public void receive(String raw,WebSocketConnection connection) {
        Session s=sessions.get(connection.id());if(s==null)return;
        ChatCommand command;
        try {
            if(raw.getBytes(StandardCharsets.UTF_8).length>65536) {enqueue(s,error(null,413,"Mensaje demasiado grande",0));return;}
            command=ChatCommand.parse(mapper.readTree(raw));
        } catch(Exception invalid) {enqueue(s,error(null,400,"Mensaje de chat invalido o demasiado largo",0));return;}
        synchronized(s) {
            if(s.closed)return;
            if(command.type().equals("chat.cancel")) {
                if(s.active!=null && s.active.command.requestId().equals(command.requestId())) finish(s,s.active,"chat.cancelled",0,null);
                else enqueue(s,error(command,409,"No hay una respuesta activa con ese identificador",0));
                return;
            }
            if(s.active!=null) {enqueue(s,error(command,409,"Ya hay una respuesta en curso",0));return;}
            if(!s.seen.add(command.requestId())) {enqueue(s,error(command,409,"Esta pregunta ya fue procesada; usa un identificador nuevo",0));return;}
            // Bound duplicate tracking without forgetting identifiers within a live connection.
            if(s.seen.size()>1000) {close(s);connection.close().subscribe().with(v->{},e->{});return;}
            try {limits.admitGeneration();}
            catch(WebApplicationException rate) {
                String retry=rate.getResponse().getHeaderString("Retry-After");
                enqueue(s,error(command,429,"Limite de preguntas alcanzado",retry==null?60:Integer.parseInt(retry)));return;
            }
            Generation g=new Generation(command);s.active=g;enqueue(s,event("chat.started",command));
            g.timeout=timers.schedule(()->finish(s,g,"chat.error",504,"La respuesta tardo demasiado; vuelve a intentarlo"),generationTimeout.toMillis(),TimeUnit.MILLISECONDS);
            try {
                g.subscription=source.stream(command.question(),command.history()).subscribe().with(
                        text->delta(s,g,text),failure->finish(s,g,"chat.error",502,"No se pudo generar la respuesta; vuelve a intentarlo"),
                        ()->finish(s,g,"chat.completed",0,null));
                if(g.done.get())g.subscription.cancel();
            } catch(Exception failure) {finish(s,g,"chat.error",502,"No se pudo iniciar la respuesta");}
        }
    }
    private void delta(Session s,Generation g,String text) {
        synchronized(s) {
            if(s.closed || g.done.get() || s.active!=g)return;
            if(g.answer.length()+text.length()>200000) {finish(s,g,"chat.error",413,"La respuesta excedio el limite permitido");return;}
            g.answer.append(text);var frame=event("chat.delta",g.command);frame.put("seq",++g.seq);frame.put("text",text);enqueue(s,frame);
        }
    }
    private void finish(Session s,Generation g,String type,int code,String message) {
        synchronized(s) {
            if(!g.done.compareAndSet(false,true))return;
            if(g.timeout!=null)g.timeout.cancel(false);
            if(g.subscription!=null)g.subscription.cancel();
            if(s.active==g)s.active=null;
            Map<String,Object> frame=type.equals("chat.error")?error(g.command,code,message,0):event(type,g.command);
            if(type.equals("chat.completed"))frame.put("answer",g.answer.toString());
            enqueue(s,frame);
        }
    }
    private Map<String,Object> event(String type,ChatCommand command) {
        Map<String,Object> frame=new LinkedHashMap<>();frame.put("v",1);frame.put("type",type);
        if(command!=null) {frame.put("requestId",command.requestId());if(command.conversationId()!=null)frame.put("conversationId",command.conversationId());}
        return frame;
    }
    private Map<String,Object> error(ChatCommand command,int code,String message,int retry) {
        var frame=event("chat.error",command);frame.put("code",code);frame.put("message",message);if(retry>0)frame.put("retryAfterSeconds",retry);return frame;
    }
    private void enqueue(Session s,Map<String,Object> frame) {
        synchronized(s) {
            if(s.closed)return;
            if(s.queue.size()>=128) {
                s.queue.clear();
                if(s.active!=null) {
                    finish(s,s.active,"chat.error",503,"La conexion no puede recibir la respuesta a tiempo");
                    return;
                }
                // finish() has already detached the generation; never discard its terminal signal.
                var terminal=new LinkedHashMap<String,Object>();
                terminal.put("v",1);terminal.put("type","chat.error");
                if(frame.containsKey("requestId"))terminal.put("requestId",frame.get("requestId"));
                if(frame.containsKey("conversationId"))terminal.put("conversationId",frame.get("conversationId"));
                terminal.put("code",503);terminal.put("message","La conexion no puede recibir la respuesta a tiempo");
                frame=terminal;
            }
            try {s.queue.addLast(mapper.writeValueAsString(frame));drain(s);}
            catch(Exception failure) {close(s);}
        }
    }
    private void drain(Session s) {
        synchronized(s) {
            if(s.sending || s.closed || s.queue.isEmpty())return;
            s.sending=true;String frame=s.queue.removeFirst();
            s.connection.sendText(frame).subscribe().with(v->{synchronized(s){s.sending=false;drain(s);}},failure->close(s));
        }
    }
    private void close(Session s) {
        synchronized(s) {
            if(s.closed)return;
            s.closed=true;s.queue.clear();sessions.remove(s.connection.id(),s);
            if(s.active!=null) {var g=s.active;g.done.set(true);if(g.timeout!=null)g.timeout.cancel(false);if(g.subscription!=null)g.subscription.cancel();s.active=null;}
            s.connection.close().subscribe().with(v->{},failure->{});
        }
    }
    private static final class Session {
        final WebSocketConnection connection;final ArrayDeque<String> queue=new ArrayDeque<>();final Set<String> seen=new HashSet<>();
        volatile boolean closed;boolean sending;Generation active;
        Session(WebSocketConnection connection){this.connection=connection;}
    }
    private static final class Generation {
        final ChatCommand command;final StringBuilder answer=new StringBuilder();final AtomicBoolean done=new AtomicBoolean();
        int seq;Cancellable subscription;ScheduledFuture<?> timeout;
        Generation(ChatCommand command){this.command=command;}
    }
}

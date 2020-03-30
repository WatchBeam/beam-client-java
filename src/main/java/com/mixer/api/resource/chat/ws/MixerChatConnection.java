package com.mixer.api.resource.chat.ws;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.mixer.api.MixerAPI;
import com.mixer.api.http.ws.MixerWebsocketClient;
import com.mixer.api.resource.chat.*;
import com.mixer.api.resource.chat.events.EventHandler;
import com.mixer.api.resource.chat.events.data.IncomingMessageData;
import com.mixer.api.resource.chat.replies.ReplyHandler;

import java.lang.reflect.ParameterizedType;
import java.util.Map;
import java.util.concurrent.Callable;

public class MixerChatConnection extends MixerWebsocketClient {
    protected final MixerChatConnectable producer;
    protected final MixerChat chat;

    protected final Map<Integer, ReplyPair> replyHandlers;
    protected final Multimap<Class<? extends AbstractChatEvent>, EventHandler> eventHandlers;

    public MixerChatConnection(MixerChatConnectable producer, MixerAPI mixer, MixerChat chat) {
        super(mixer, chat.endpoint());
        this.producer = producer;

        this.chat = chat;

        this.replyHandlers = Maps.newConcurrentMap();
        this.eventHandlers = HashMultimap.create();
    }

    public void inherit(MixerChatConnection other) {
        for (Map.Entry<Class<? extends AbstractChatEvent>, EventHandler> entry : other.eventHandlers.entries()) {
            this.eventHandlers.put(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<Integer, ReplyPair> entry : other.replyHandlers.entrySet()) {
            this.replyHandlers.put(entry.getKey(), entry.getValue());
        }
    }

    public <T extends AbstractChatEvent> boolean on(Class<T> eventType, EventHandler<T> handler) {
        return this.eventHandlers.put(eventType, handler);
    }

    /**
     * Send sends a chat method with no given response handler.
     * @param method The method to send.
     */
    public void send(AbstractChatMethod method) {
        this.send(method, null);
    }

    /**
     * Send sends a chat method with a response handler that will be invoked when a response
     * is received from the chat server.
     *
     * @param method The method to send.
     * @param handler The reply handler.
     * @param <T> The type on which to bind the method and reply handler together with.
     */
    public <T extends AbstractChatReply> void send(final AbstractChatMethod method, ReplyHandler<T> handler) {
        if (handler != null) {
            this.replyHandlers.put(method.id, ReplyPair.from(handler));
        }

        this.mixer.executor.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                byte[] data = MixerChatConnection.this.mixer.gson.toJson(method).getBytes();
                MixerChatConnection.this.send(data);

                return null;
            }
        });
    }

    /**
     * Delete deletes a message by making an API call.
     *
     * @param message The message to delete.
     */
    @Deprecated
    public void delete(IncomingMessageData message) {
        String path = this.mixer.basePath.resolve("chats/" + message.channel + "/message/" + message.id).toString();
        this.mixer.http.delete(path, null);
    }

    @Override
    public void onMessage(String s) {
        // XXX: terrible
        ReplyPair replyPair = null;
        try {
            // Parse out the generic JsonObject so we can pull out the ID element from it,
            //  since we cannot yet parse as a generic class.
            JsonObject e = new JsonParser().parse(s).getAsJsonObject();
            if (e.has("id")) {
                int id = e.get("id").getAsInt();

                // Try and remove a reply-pair, execute the #onSuccess method if we find
                // a matching reply-pair.
                if ((replyPair = this.replyHandlers.remove(id)) != null) {
                    Class<? extends AbstractChatDatagram> type = replyPair.type;

                    // Now that we have the type, we can appropriately parse out the value
                    // And call the #onSuccess method with the value.
                    AbstractChatDatagram datagram = this.mixer.gson.fromJson(s, type);
                    replyPair.handler.onSuccess(type.cast(datagram));
                }
            } else if (e.has("event")) {
                // Handles cases of mixer widgets (GiveawayBot) sending ChatMessage events
                if(e.getAsJsonObject("data").has("user_id") && e.getAsJsonObject("data").get("user_id").getAsInt() == -1) {
                    Class<? extends AbstractChatEvent> type = AbstractChatEvent.EventType.fromSerializedName("WidgetMessage").getCorrespondingClass();
                    this.dispatchEvent(this.mixer.gson.fromJson(e, type));
                } else {
                    // Default ChatMessage event handling
                    String eventName = e.get("event").getAsString();
                    Class<? extends AbstractChatEvent> type = AbstractChatEvent.EventType.fromSerializedName(eventName).getCorrespondingClass();
                    this.dispatchEvent(this.mixer.gson.fromJson(e, type));
                }
            }
        } catch (JsonSyntaxException e) {
            // If an exception was called and we do have a reply handler to catch it,
            // call the #onFailure method with the throwable.
            if (replyPair != null) {
                replyPair.handler.onFailure(e);
            } else {
                throw e;
            }
        }
    }

    @Override public void onClose(int i, String s, boolean b) {
    	this.close(i);
        this.producer.notifyClose(i, s, b);
    }

    private <T extends AbstractChatEvent> void dispatchEvent(T event) {
        Class<? extends AbstractChatEvent> eventType = event.getClass();

        for (EventHandler handler : this.eventHandlers.get(eventType)) {
            handler.onEvent(event);
        }
    }

    private static class ReplyPair<T extends AbstractChatReply> {
        public ReplyHandler<T> handler;
        public Class<T> type;

        private static <T extends AbstractChatReply> ReplyPair<T> from(ReplyHandler<T> handler) {
            ReplyPair<T> pair = new ReplyPair<>();

            pair.handler = handler;
            pair.type = (Class<T>) ((ParameterizedType) handler.getClass().getGenericSuperclass()).getActualTypeArguments()[0];

            return pair;
        }
    }
}

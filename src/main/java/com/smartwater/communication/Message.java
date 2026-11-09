/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.communication;

/**
 *
 * @author nader
 */
import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class Message {
    private static final Gson GSON = new Gson();
    public static final String ALL = "ALL";

    private String msgId;        // rempli par publish()
    private MessageType type;
    private String from;         // rempli par publish()
    private String to;           // "ALL" ou l'ID d'un nœud
    private int lamport;         // rempli par publish()
    private JsonObject payload;

    /** Constructeur utilisé par les modules : ils ne remplissent que type, to, payload. */
    public Message(MessageType type, String to, JsonObject payload) {
        this.type = type;
        this.to = to;
        this.payload = (payload != null) ? payload : new JsonObject();
    }

    // --- Getters ---
    public String getMsgId()       { return msgId; }
    public MessageType getType()   { return type; }
    public String getFrom()        { return from; }
    public String getTo()          { return to; }
    public int getLamport()        { return lamport; }
    public JsonObject getPayload() { return payload; }

    // --- Setters réservés à NodeMqttClient.publish() ---
    public void setMsgId(String msgId)   { this.msgId = msgId; }
    public void setFrom(String from)     { this.from = from; }
    public void setLamport(int lamport)  { this.lamport = lamport; }

    public boolean isBroadcast() { return ALL.equals(to); }

    // --- JSON ---
    public String toJson() { return GSON.toJson(this); }

    public static Message fromJson(String json) {
        return GSON.fromJson(json, Message.class);
    }

    @Override
    public String toString() {
        return type + " " + from + "->" + to + " L=" + lamport + " " + payload;
    }
}

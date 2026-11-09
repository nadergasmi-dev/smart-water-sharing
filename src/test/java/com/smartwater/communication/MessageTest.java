/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.communication;

/**
 *
 * @author nader
 */
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void roundTripJson() {
        JsonObject p = new JsonObject();
        p.addProperty("liters", 40);
        Message m = new Message(MessageType.IRRIGATION_EVENT, Message.ALL, p);
        m.setMsgId("abc-123");
        m.setFrom("A");
        m.setLamport(5);

        Message back = Message.fromJson(m.toJson());

        assertEquals("abc-123", back.getMsgId());
        assertEquals(MessageType.IRRIGATION_EVENT, back.getType());
        assertEquals("A", back.getFrom());
        assertEquals("ALL", back.getTo());
        assertEquals(5, back.getLamport());
        assertEquals(40, back.getPayload().get("liters").getAsInt());
    }

    @Test
    void emptyPayloadIsEmptyObject() {
        Message m = new Message(MessageType.REQUEST, Message.ALL, null);
        assertNotNull(m.getPayload());
        assertTrue(m.toJson().contains("\"payload\":{}"));
    }

    @Test
    void broadcastDetection() {
        assertTrue(new Message(MessageType.REQUEST, "ALL", null).isBroadcast());
        assertFalse(new Message(MessageType.REPLY, "B", null).isBroadcast());
    }
}
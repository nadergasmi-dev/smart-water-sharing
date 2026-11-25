package com.smartwater.snapshot;

import com.smartwater.communication.Message;
import com.smartwater.node.LocalState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Instantané local d'un nœud (Chandy-Lamport)
public record LocalSnapshot(String nodeId, LocalState state, int ledgerLength, String ledgerLastHash,
                            Map<String, List<Message>> channels, long recordedAt) {

    public LocalSnapshot {
        Map<String, List<Message>> copy = new LinkedHashMap<>();
        if (channels != null) {
            channels.forEach((peer, msgs) -> copy.put(peer, msgs == null ? List.of() : List.copyOf(msgs)));
        }
        channels = java.util.Collections.unmodifiableMap(copy);
    }
}

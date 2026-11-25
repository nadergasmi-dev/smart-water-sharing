package com.smartwater.node;

import java.util.List;

// État local d'un nœud, enregistré par Chandy-Lamport
public record LocalState(String nodeId, double humidity, int quotaRemaining, String pumpState,
                         List<String> deferredReplies) {

    public LocalState {
        deferredReplies = deferredReplies == null ? List.of() : List.copyOf(deferredReplies);
    }
}

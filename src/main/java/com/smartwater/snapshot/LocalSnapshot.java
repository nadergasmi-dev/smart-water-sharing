package com.smartwater.snapshot;

import com.smartwater.communication.Message;
import com.smartwater.node.LocalState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Instantané local d'un nœud (Chandy-Lamport) : son état au moment de l'enregistrement, un résumé de
 * son registre, et les messages en transit sur chacun de ses canaux entrants.
 *
 * @param nodeId         nœud qui a enregistré
 * @param state          état local (LocalStateProvider, Binôme 2)
 * @param ledgerLength   longueur de sa copie du registre (genesis compris)
 * @param ledgerLastHash hash du dernier bloc de sa copie
 * @param channels       pour chaque autre nœud : messages reçus après l'enregistrement de l'état et
 *                       avant le MARKER de ce nœud (= en transit au moment du snapshot)
 * @param recordedAt     heure de l'enregistrement de l'état (ms epoch)
 */
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

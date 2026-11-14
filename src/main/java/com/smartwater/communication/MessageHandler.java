/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package com.smartwater.communication;

/**
 *
 * @author nader
 */
import com.smartwater.algorithms.LamportClock;
import com.smartwater.snapshot.SnapshotService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

public class MessageHandler implements Consumer<Message> {

    private final String nodeId;
    private final LamportClock clock;
    private volatile SnapshotService snapshotService;   // peut rester null jusqu'à la partie 5 (Chandy-Lamport)
    private final ExecutorService executor;
    // un MessageListener par type de message
    private final Map<MessageType, MessageListener> listeners = new EnumMap<>(MessageType.class);
    
    public MessageHandler(String nodeId, LamportClock clock) {
        this.nodeId = nodeId;
        this.clock = clock;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "handler-" + nodeId);   
            t.setDaemon(true);
            return t;
        });
    }

    public void setSnapshotService(SnapshotService s) {
        this.snapshotService = s;
    }

    /** Un module s'inscrit pour un ou plusieurs types de messages. */
    public void register(MessageListener listener, MessageType... types) {
        synchronized (listeners) {
            for (MessageType t : types) {
                listeners.put(t, listener);
            }
        }
    }

    /** Appelé par NodeMqttClient pour chaque message valide (ni propre, ni doublon). */
    @Override
    public void accept(Message m) {
        executor.execute(() -> process(m));
    }

    private void process(Message m) {
        // 1) update() de l'horloge
        clock.update(m.getLamport());

        // 2) snapshot AVANT dispatch
        SnapshotService s = snapshotService;
        if (s != null) {
            try {
                s.onMessage(m);
            } catch (Exception e) {
                System.err.println("[" + nodeId + "] erreur snapshot : " + e.getMessage());
            }
        }

        // 3) dispatch
        MessageListener l;
        synchronized (listeners) {
            l = listeners.get(m.getType());
        }
        if (l == null) {
            boolean handledBySnapshot = s != null
                    && (m.getType() == MessageType.MARKER || m.getType() == MessageType.SNAPSHOT_STATE);
            if (!handledBySnapshot) {
                System.out.println("[" + nodeId + "] aucun module pour " + m.getType() + " (ignoré)");
            }
            return;
        }
        try {
            l.onMessage(m);
        } catch (Exception e) {
            System.err.println("[" + nodeId + "] erreur dans le module " + m.getType() + " : " + e.getMessage());
        }
    }

    /**
     * Exécute une tâche sur le thread du handler, ENTRE deux messages (jamais au milieu du traitement
     * d'un message). Utilisé par Chandy-Lamport pour démarrer un snapshot sur un état cohérent.
     */
    public void execute(Runnable task) {
        executor.execute(task);
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
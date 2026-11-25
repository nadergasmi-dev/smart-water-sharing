package com.smartwater.algorithms;

import com.smartwater.Config;
import com.smartwater.communication.Message;
import com.smartwater.communication.MessageListener;
import com.smartwater.communication.MessageSender;
import com.smartwater.communication.MessageType;
import com.smartwater.irrigation.PumpAccess;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Exclusion mutuelle sur la pompe par l'algorithme de Ricart et Agrawala
 * @author Houssine Ferjani (Binôme 2)
 */
public class RicartAgrawala implements PumpAccess, MessageListener {

    // État d'un nœud vis-à-vis de la pompe
    public enum State { RELEASED, WANTED, HELD }

    // Types de messages à enregistrer auprès du MessageHandler
    public static final MessageType[] TYPES = { MessageType.REQUEST, MessageType.REPLY };

    private final String nodeId;
    private final LamportClock clock;
    private final MessageSender sender;
    // Les autres nœuds : ceux dont il faut un REPLY
    private final List<String> peers;

    // état, protégé par "this"
    private State state = State.RELEASED;
    // Estampille de ma demande en cours (valide en WANTED et HELD)
    private int requestTs = -1;
    // Nœuds dont j'attends encore le REPLY
    private final Set<String> awaitingReply = new LinkedHashSet<>();
    // Nœuds dont j'ai différé la réponse (ordre d'arrivée conservé)
    private final Set<String> deferred = new LinkedHashSet<>();
    // REPLY encore attendus pour une demande abandonnée (interruption)
    private final Map<String, Integer> staleReplies = new HashMap<>();

    // statistiques (pour les tests et le dashboard)
    private long entries = 0;

    // Constructeur du contrat (section 7) : les autres nœuds sont ceux de NODE_IDS
    public RicartAgrawala(String nodeId, LamportClock clock, MessageSender sender) {
        this(nodeId, clock, sender, Config.NODE_IDS);
    }

    // Variante avec une liste de nœuds explicite (tests). allNodes peut contenir nodeId
    public RicartAgrawala(String nodeId, LamportClock clock, MessageSender sender, Collection<String> allNodes) {
        this.nodeId = Objects.requireNonNull(nodeId, "nodeId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.peers = allNodes.stream().filter(id -> !id.equals(nodeId)).distinct().toList();
    }

    // PumpAccess

    // Diffuse un REQUEST et bloque jusqu'à l'entrée en section critique (état HELD)
    @Override
    public void requestPump() {
        synchronized (sender) {
            synchronized (this) {
                if (state != State.RELEASED) {
                    throw new IllegalStateException("[" + nodeId + "] requestPump() alors que l'état est " + state);
                }
                state = State.WANTED;
                awaitingReply.clear();
                awaitingReply.addAll(peers);
                // publish() fait tick() : sa valeur de retour EST l'estampille de la demande (contrat, 4.1)
                requestTs = sender.publish(new Message(MessageType.REQUEST, Message.ALL, null));
                log("REQUEST diffusé (L=" + requestTs + "), attente de " + awaitingReply);
            }
        }

        synchronized (this) {
            while (!awaitingReply.isEmpty()) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    abandonRequest();
                    throw new IllegalStateException("[" + nodeId + "] attente de la pompe interrompue", e);
                }
            }
            state = State.HELD;
            entries++;
            log("ENTRÉE en section critique (demande L=" + requestTs + ")");
        }
    }

    // Sortie de section critique : envoie les REPLY différés (pas de RELEASE dans Ricart-Agrawala)
    @Override
    public void releasePump() {
        synchronized (sender) {
            synchronized (this) {
                if (state != State.HELD) {
                    throw new IllegalStateException("[" + nodeId + "] releasePump() alors que l'état est " + state);
                }
                state = State.RELEASED;
                requestTs = -1;
                List<String> toReply = new ArrayList<>(deferred);
                deferred.clear();
                for (String peer : toReply) {
                    sender.publish(new Message(MessageType.REPLY, peer, null));
                }
                log("SORTIE de section critique, REPLY différés envoyés à " + toReply);
            }
        }
    }

    // MessageListener

    @Override
    public void onMessage(Message m) {
        if (m == null || m.getType() == null || m.getFrom() == null) {
            return;
        }
        if (!peers.contains(m.getFrom())) {
            log("message " + m.getType() + " d'un nœud inconnu (" + m.getFrom() + ") ignoré");
            return;
        }
        switch (m.getType()) {
            case REQUEST -> onRequest(m);
            case REPLY -> onReply(m.getFrom());
            default -> { /* pas pour ce module */ }
        }
    }

    private void onRequest(Message m) {
        String from = m.getFrom();
        int ts = m.getLamport();   // le champ lamport du REQUEST est l'estampille de la demande
        synchronized (sender) {
            synchronized (this) {
                boolean iHavePriority = state == State.HELD
                        || (state == State.WANTED && LamportClock.compare(requestTs, nodeId, ts, from) < 0);
                if (iHavePriority) {
                    deferred.add(from);
                    log("REQUEST de " + from + " (L=" + ts + ") DIFFÉRÉ (état " + state
                            + (state == State.WANTED ? ", ma demande L=" + requestTs + " est prioritaire" : "") + ")");
                } else {
                    sender.publish(new Message(MessageType.REPLY, from, null));
                    log("REQUEST de " + from + " (L=" + ts + ") : REPLY immédiat (état " + state + ")");
                }
            }
        }
    }

    private synchronized void onReply(String from) {
        Integer stale = staleReplies.get(from);
        if (stale != null) {
            if (stale == 1) staleReplies.remove(from); else staleReplies.put(from, stale - 1);
            log("REPLY de " + from + " ignoré (concerne une demande abandonnée)");
            return;
        }
        if (state == State.WANTED && awaitingReply.remove(from)) {
            log("REPLY de " + from + " reçu, encore attendus : " + awaitingReply);
            if (awaitingReply.isEmpty()) {
                notifyAll();
            }
        } else {
            log("REPLY inattendu de " + from + " (état " + state + ") ignoré");
        }
    }

    // Abandon d'une demande en cours (interruption) : on revient à RELEASED sans laisser personne bloqué
    private void abandonRequest() {
        synchronized (sender) {
            synchronized (this) {
                if (state != State.WANTED) return;
                for (String peer : awaitingReply) {
                    staleReplies.merge(peer, 1, Integer::sum);
                }
                awaitingReply.clear();
                state = State.RELEASED;
                requestTs = -1;
                List<String> toReply = new ArrayList<>(deferred);
                deferred.clear();
                for (String peer : toReply) {
                    sender.publish(new Message(MessageType.REPLY, peer, null));
                }
                log("demande abandonnée, REPLY différés envoyés à " + toReply);
            }
        }
    }

    // Lecture de l'état (LocalState pour Chandy-Lamport, dashboard, tests)

    // Photographie cohérente de l'état de la pompe, lue en une seule fois (sous le même verrou)
    public record View(State state, int requestTimestamp, List<String> deferredReplies,
                       List<String> awaitingReplies) { }

    // État complet lu atomiquement
    public synchronized View view() {
        return new View(state, requestTs, List.copyOf(deferred), List.copyOf(awaitingReply));
    }

    public String getNodeId()                   { return nodeId; }
    public synchronized State getState()         { return state; }
    // Estampille de la demande en cours, ou -1 en état RELEASED
    public synchronized int getRequestTimestamp() { return requestTs; }
    // Nœuds dont la réponse est différée, dans l'ordre d'arrivée (copie)
    public synchronized List<String> getDeferredReplies() { return List.copyOf(deferred); }
    // Nœuds dont le REPLY est encore attendu (copie)
    public synchronized List<String> getAwaitingReplies() { return List.copyOf(awaitingReply); }
    // Nombre d'entrées en section critique depuis le démarrage
    public synchronized long getEntries()        { return entries; }

    private void log(String text) {
        System.out.println("[" + nodeId + "] RA (H=" + clock.get() + ") : " + text);
    }
}

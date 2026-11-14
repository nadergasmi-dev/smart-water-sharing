package com.smartwater.algorithms;

import java.util.Objects;

/**
 * Horloge logique de Lamport d'un nœud (contrat, section 5).
 *
 * <p>Chaque nœud possède <b>une seule</b> horloge, initialisée à 0 et partagée entre
 * {@code NodeMqttClient} (envoi et réception des messages) et les modules de Binôme 2
 * (Ricart-Agrawala, événements locaux à dater).</p>
 *
 * <ul>
 *   <li>Événement local ou envoi d'un message : {@link #tick()}, soit H = H + 1.</li>
 *   <li>Réception d'un message daté {@code received} : {@link #update(int)},
 *       soit H = max(H, received) + 1, avant tout traitement du message.</li>
 * </ul>
 *
 * <p>Ces deux règles garantissent la propriété de cohérence avec la causalité :
 * si e → e' alors H(e) &lt; H(e'). La réciproque est fausse : deux événements
 * concurrents peuvent avoir n'importe quel ordre d'estampilles.</p>
 *
 * <p><b>Ordre total.</b> En complétant l'estampille par l'identifiant du nœud, on obtient
 * un ordre total strict sur les demandes : c'est {@link #compare(int, String, int, String)},
 * utilisé par Ricart-Agrawala pour décider quelle demande de pompe est prioritaire.</p>
 *
 * <p><b>Concurrence.</b> L'horloge est lue et modifiée par plusieurs threads (thread de
 * réception MQTT, thread du {@code MessageHandler}, thread de l'agriculteur) : toutes les
 * méthodes d'instance sont {@code synchronized}, ce qui rend chaque lecture-modification
 * atomique.</p>
 *
 * @author Houssine Ferjani (Binôme 2)
 */
public class LamportClock {

    /** Valeur courante de l'horloge ; 0 tant qu'aucun événement n'a eu lieu. */
    private int counter = 0;

    /**
     * Événement local ou envoi d'un message : incrémente l'horloge.
     *
     * @return la nouvelle valeur, qui sert d'estampille à l'événement
     */
    public synchronized int tick() {
        return ++counter;
    }

    /**
     * Réception d'un message : recale l'horloge sur celle de l'expéditeur si elle est
     * en avance, puis incrémente.
     *
     * <p>Ne lève jamais d'exception : elle est appelée par le {@code MessageHandler} sur
     * chaque message reçu. Une estampille mal formée (0 ou négative) ne fait donc
     * qu'incrémenter l'horloge, puisque le maximum reste la valeur locale.</p>
     *
     * @param received estampille Lamport portée par le message reçu
     * @return la nouvelle valeur, qui sert d'estampille à l'événement de réception
     */
    public synchronized int update(int received) {
        counter = Math.max(counter, received) + 1;
        return counter;
    }

    /** @return la valeur courante, sans la modifier */
    public synchronized int get() {
        return counter;
    }

    /**
     * Ordre total des demandes (contrat, section 4.1) : la plus petite estampille est
     * prioritaire ; en cas d'égalité, le plus petit identifiant ({@code A < B < C}).
     *
     * @return un nombre négatif si (tsA, idA) est prioritaire sur (tsB, idB), positif dans
     *         le cas inverse, 0 seulement si les deux couples sont identiques
     */
    public static int compare(int tsA, String idA, int tsB, String idB) {
        Objects.requireNonNull(idA, "idA");
        Objects.requireNonNull(idB, "idB");
        if (tsA != tsB) {
            return Integer.compare(tsA, tsB);
        }
        return idA.compareTo(idB);
    }

    @Override
    public synchronized String toString() {
        return "LamportClock[" + counter + "]";
    }
}

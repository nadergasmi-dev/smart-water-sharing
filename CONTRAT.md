# CONTRAT D'INTERFACE — Smart Water Sharing

**Version 1.0** — à valider par Binôme 1 et Binôme 2.
Toute modification de ce fichier doit être annoncée à l'autre binôme et commitée avec un message explicite.

Ce document fixe les conventions de communication entre les modules. Il ne contient aucun algorithme : il définit comment les parties de code se parlent, pour que chacun puisse développer en parallèle.

---

## 1. Identifiants des nœuds

| ID | Rôle |
|----|------|
| `A` | Nœud agriculteur A |
| `B` | Nœud agriculteur B |
| `C` | Nœud agriculteur C |

- Les IDs sont **fixes** et passés en argument au lancement du nœud.
- Ordre de priorité en cas d'égalité de timestamp : `A < B < C`.
- Le `clientId` MQTT d'un nœud est égal à son ID. Le dashboard utilise `DASHBOARD`.

---

## 2. Broker et topics MQTT

- Broker : Mosquitto, `tcp://localhost:1883`.
- **QoS 2** pour tous les messages (livraison exactement une fois).
- Déduplication par `msgId` quand même, par sécurité.

| Topic | Usage | Abonnés |
|-------|-------|---------|
| `water/all` | Messages diffusés : `REQUEST`, `RELEASE`, `MARKER`, `IRRIGATION_EVENT`, `LEDGER_UPDATE` | Tous les nœuds + dashboard |
| `water/node/{id}` | Messages ciblés : `REPLY` | Le nœud `{id}` |

- Un nœud reçoit ses propres messages sur `water/all` : il les **ignore** en filtrant sur `from`.
- Le dashboard s'abonne à `water/all` et ne publie rien.

---

## 3. Format JSON commun

Tous les messages ont la même enveloppe :

```json
{
  "msgId": "d3b1c0a2-5f1e-4a7b-9c11-0b2f6a7e8c90",
  "type": "REQUEST",
  "from": "A",
  "to": "ALL",
  "lamport": 5,
  "payload": {}
}
```

| Champ | Type | Description |
|-------|------|-------------|
| `msgId` | String (UUID) | Identifiant unique du message, sert à dédupliquer |
| `type` | String | L'un des 6 types de la section 4 |
| `from` | String | ID de l'expéditeur (`A`, `B`, `C`) |
| `to` | String | ID du destinataire, ou `ALL` pour une diffusion |
| `lamport` | int | Timestamp logique de l'envoi (voir section 5) |
| `payload` | objet JSON | Données propres au type, `{}` si vide |

---

## 4. Les 6 types de messages

### 4.1 Famille accès à la pompe (Ricart-Agrawala) — logique : Binôme 2

| Type | Sens | Topic | Payload |
|------|------|-------|---------|
| `REQUEST` | « Je veux la pompe » | `water/all` | `{}` |
| `REPLY` | « Je te laisse passer » | `water/node/{demandeur}` | `{}` |
| `RELEASE` | « J'ai fini, la pompe est libre » | `water/all` | `{}` |

- La **priorité** d'un `REQUEST` est le couple `(lamport, from)`. Le plus petit `lamport` gagne ; en cas d'égalité, le plus petit `from` gagne.
- Le champ `lamport` du `REQUEST` **est** le timestamp de la demande : pas de champ `requestTs` dans le payload.
- Réaction à un `REQUEST` reçu :
  - état `RELEASED` : répondre tout de suite (`REPLY`) ;
  - état `HELD` : différer la réponse ;
  - état `WANTED` : si ma demande est prioritaire, différer ; sinon répondre.
- Entrée en section critique : après réception d'un `REPLY` de **chaque** autre nœud (2 avec 3 nœuds).
- À la sortie : envoyer tous les `REPLY` différés.
- `RELEASE` est une **notification** (le dashboard affiche « pompe libre »). Elle ne remplace pas les `REPLY` différés.

### 4.2 Famille registre (blockchain) — logique : Binôme 1

| Type | Sens | Topic | Payload |
|------|------|-------|---------|
| `IRRIGATION_EVENT` | « J'ai arrosé » | `water/all` | `{ "txId": "T-001", "liters": 40, "quotaRemaining": 160, "reservoirLevel": 460 }` |
| `LEDGER_UPDATE` | « Voici le nouveau bloc » | `water/all` | `{ "block": { ...voir section 6... } }` |

- `IRRIGATION_EVENT` annonce l'irrigation et met à jour la vision de l'état (quota de l'émetteur, niveau du réservoir).
- `LEDGER_UPDATE` : le récepteur recalcule le hash, vérifie `previousHash` par rapport à **son** dernier bloc, puis ajoute le bloc. Sinon il signale une **divergence**.
- Seul le nœud qui tient la pompe crée un bloc, donc il n'y a pas de bloc concurrent de même index.

### 4.3 Famille snapshot (Chandy-Lamport) — logique : Binôme 2

| Type | Sens | Topic | Payload |
|------|------|-------|---------|
| `MARKER` | « Enregistre ton état » | `water/all` | `{ "snapshotId": "S1", "initiator": "A" }` |

- Premier `MARKER` reçu pour un `snapshotId` : enregistrer l'état local, renvoyer un `MARKER` aux autres, commencer à enregistrer les canaux entrants.
- `MARKER` suivants : arrêter l'enregistrement du canal d'où il vient.
- Snapshot local terminé quand un `MARKER` a été reçu de chaque autre nœud.

---

## 5. Horloge de Lamport

- Chaque nœud a **une** `LamportClock`, initialisée à `0`.
- **À l'envoi** : `tick()` (compteur + 1), et la valeur obtenue est écrite dans `lamport`.
- **À la réception** : `update(m.lamport)` donne `max(local, reçu) + 1`, avant tout traitement du message.
- Qui appelle quoi :
  - `publish(Message)` fait le `tick()` et remplit `lamport` automatiquement ;
  - la couche de réception fait `update()` avant d'appeler `onMessage()`.
  - Les modules de Binôme 2 n'appellent donc jamais `tick()` ni `update()` pour l'envoi/réception de messages. Ils les appellent seulement pour un événement local qu'ils veulent dater.
- Ne pas confondre avec le champ `timestamp` d'un bloc, qui est l'heure réelle.

---

## 6. Blockchain : format du bloc

```json
{
  "index": 3,
  "timestamp": 1731153600000,
  "nodeId": "A",
  "liters": 40,
  "quotaRemaining": 160,
  "previousHash": "9f86d081...",
  "hash": "a7c3e1f4..."
}
```

| Champ | Description |
|-------|-------------|
| `index` | Position dans la chaîne, genesis = 0 |
| `timestamp` | Heure réelle de création, en millisecondes epoch |
| `nodeId` | Nœud qui a irrigué |
| `liters` | Quantité attribuée |
| `quotaRemaining` | Quota restant après irrigation |
| `previousHash` | Hash du bloc précédent |
| `hash` | SHA-256 du bloc |

**Calcul du hash :** SHA-256 de la chaîne
`index|timestamp|nodeId|liters|quotaRemaining|previousHash`
(séparateur `|`, encodage UTF-8, résultat en hexadécimal minuscule, le champ `hash` n'entre pas dans le calcul).

**Bloc genesis identique sur les 3 nœuds** (sinon les copies divergent dès le départ) :
`index = 0`, `timestamp = 0`, `nodeId = "GENESIS"`, `liters = 0`, `quotaRemaining = 0`, `previousHash = "0"`.

---

## 7. Interfaces Java

Packages sous `com.smartwater`.

```java
// communication
public interface MessageSender {
    /** Fait tick(), remplit lamport/msgId/from, publie. Retourne le timestamp Lamport utilisé. */
    int publish(Message m);
}

public interface MessageListener {
    /** Appelé à la réception, après update() de l'horloge. Jamais appelé pour ses propres messages. */
    void onMessage(Message m);
}

// algorithms
public class LamportClock {
    public synchronized int tick();
    public synchronized int update(int received);
    public synchronized int get();
    /** Négatif si (tsA, idA) est prioritaire sur (tsB, idB). */
    public static int compare(int tsA, String idA, int tsB, String idB);
}

// irrigation (Binôme 2 fournit, Binôme 1 utilise)
public interface PumpAccess {
    void requestPump();   // bloque jusqu'à l'entrée en section critique
    void releasePump();   // envoie les REPLY différés + RELEASE
}

// blockchain (Binôme 1 fournit, Binôme 2 utilise)
public interface Ledger {
    Block addBlock(String nodeId, int liters, int quotaRemaining);
    boolean validate();               // true si toute la chaîne est valide
    List<Block> getChain();
}

// snapshot (Binôme 2 fournit)
public interface SnapshotService {
    void startSnapshot();             // initiateur
    boolean isComplete();
    GlobalSnapshot getResult();
}
```

Les signatures peuvent évoluer, mais **seulement après accord** et mise à jour de ce fichier.

---

## 8. Séquence imposée lors d'une irrigation

Dans la section critique, dans cet ordre :

1. `requestPump()` : entrée en section critique.
2. Calcul de l'allocation (section 9).
3. Si `attribué > 0` : création du bloc (`Ledger.addBlock`).
4. Diffusion de `IRRIGATION_EVENT`.
5. Diffusion de `LEDGER_UPDATE`.
6. `releasePump()` : `RELEASE` puis envoi des `REPLY` différés.

Le bloc est diffusé **avant** les `REPLY` différés, pour que le nœud suivant ait un registre à jour avant de créer son propre bloc. Avant de créer un bloc, un nœud vérifie que sa chaîne est à jour avec le dernier `LEDGER_UPDATE` reçu.

---

## 9. Règle d'allocation de l'eau (à valider)

```
attribué = min(besoin, quotaRestant, réservoirDisponible)
```

- Le calcul est fait **dans la section critique**, jamais avant, pour éviter qu'un même volume soit attribué deux fois.
- Si `attribué == 0` : pas d'irrigation, pas de bloc, mais `releasePump()` est quand même appelé.
- Un nœud dont l'humidité est suffisante, ou dont le quota est à 0, ne diffuse pas de `REQUEST`.

Valeurs initiales proposées (à confirmer) : réservoir = 1000 L, quota par agriculteur = 200 L.

---

## 10. Hypothèses de fonctionnement

1. **Aucune panne de nœud** pendant l'exécution (limite connue de Ricart-Agrawala). À écrire dans le rapport.
2. **Canaux FIFO fiables** (exigés par Chandy-Lamport) : obtenus avec QoS 2 et l'ordre de publication d'un même client. MQTT ne garantit pas l'ordre entre deux topics différents ; c'est une hypothèse à écrire dans le rapport.
3. **La réplication du registre n'est pas un consensus** : SHA-256 et le chaînage détectent les altérations, mais ne tranchent pas entre deux versions concurrentes.
4. Tous les nœuds tournent sur la même machine et le même broker.

---

## 11. Points ouverts à trancher ensemble

- [ ] Rôle exact de `RELEASE` : simple notification (proposé) ou libération explicite.
- [ ] Rattrapage d'un nœud en retard sur le registre : message `LEDGER_REQUEST` / `LEDGER_SYNC` à ajouter, ou ignoré pour ce projet.
- [ ] Ordre entre topics : accepter l'hypothèse FIFO (section 10), ou faire publier chaque nœud sur un topic unique `water/from/{id}` filtré sur `to`.
- [ ] Valeurs initiales du réservoir, des quotas et seuil d'humidité.
- [ ] Format et contenu de `GlobalSnapshot`.

---

## 12. Validation

| Binôme | Nom | Date | Validé |
|--------|-----|------|--------|
| Binôme 1 | | | ☐ |
| Binôme 2 | | | ☐ |

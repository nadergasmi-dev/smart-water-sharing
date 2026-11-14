# CONTRAT D'INTERFACE — Smart Water Sharing

**Version 3.0** — sécurisation du registre et du broker. À valider par Binôme 1 et Binôme 2.
Toute modification de ce fichier doit être annoncée à l'autre binôme et commitée avec un message explicite.

Ce document fixe les conventions de communication entre les modules. Il ne contient aucun algorithme : il définit comment les parties de code se parlent, pour que chacun puisse développer en parallèle.

**Changements par rapport à la version 2 :**
- Les blocs sont **signés** (ECDSA) par le nœud qui les crée ; nouveau champ `signature` (section 6).
- Un bloc est **validé avant d'être ajouté** (7 règles, section 6.3) : un bloc invalide n'entre jamais dans la chaîne.
- `Ledger.addBlock` peut lever `InvalidBlockException` ; la séquence d'irrigation gère ce cas (sections 7 et 8).
- Mosquitto passe en **accès authentifié avec ACL** (section 2.1).
- Les valeurs initiales (réservoir, quotas) sont **fixées**, car la validation en dépend (section 9).
- Nouvelle hypothèse : **modèle de menace** (section 10).
- Nouveaux éléments : package `security` (clés et signatures), classe `Config` (constantes partagées), dossiers `keys/` et `mosquitto/`.
- **Répartition par partie** : chaque partie appartient entièrement à un seul binôme. La **sécurité** (registre + broker + clés) va à Binôme 1 ; le **dashboard** (tous les panneaux) va à Binôme 2.

---

## 0. Répartition des responsabilités

Le responsable d'un module écrit son code et ses tests unitaires. L'autre binôme ne le modifie qu'après accord.

| Binôme 1 | Binôme 2 |
|----------|----------|
| MQTT et communication (`NodeMqttClient`, `Message`, `MessageHandler`) | Horloge de Lamport (`LamportClock`) |
| Blockchain privée et réplication (`Block`, `Blockchain`, `HashValidator`, `LedgerReplicator`) | Ricart-Agrawala (`RicartAgrawala`) |
| **Sécurité** : signatures, validation des blocs, clés, comptes et ACL du broker (`BlockSigner`, `KeyGenerator`, `InvalidBlockException`, `Config`, `mosquitto/`) | Simulation des agriculteurs (`FarmerNode`, `SoilSimulator`) |
| Chandy-Lamport (`ChandyLamport`, `SnapshotService`, `GlobalSnapshot`) | Allocation et pompe (`WaterAllocator`, `PumpResource`) |
| | **Dashboard JavaFX complet** : fenêtre principale et tous les panneaux (agriculteurs, quotas, réservoir, pompe, registre, snapshot) |

**Commun :** ce contrat, l'intégration, les tests de bout en bout, le rapport (chacun rédige les sections de ses modules) et la démonstration.

**Changements par rapport à la version 1 :** Chandy-Lamport passe à Binôme 1 (il enregistre les messages en transit dans la couche de réception, qui est la sienne) ; l'horloge de Lamport passe à Binôme 2 (petit module, utilisé en permanence par Ricart-Agrawala) ; la simulation, l'allocation et la pompe deviennent des responsabilités de Binôme 2 ; le dashboard est découpé par panneaux.

**Changements par rapport à la version 2 :** règle « une partie = un binôme ». La **sécurité** forme une partie entière confiée à Binôme 1, car elle porte sur la blockchain et sur MQTT, qui sont déjà les siens. Le **dashboard** n'est plus découpé par panneaux : il revient entièrement à Binôme 2, ce qui garde la charge équilibrée (9,5 / 10,5). Binôme 1 fournit les données (registre, blocs refusés, snapshot) par les interfaces de la section 7 ; Binôme 2 les affiche.

**Ordre de livraison :** Binôme 2 livre `LamportClock` en premier (semaine 1, jours 1 et 2) car `NodeMqttClient` en dépend.

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

### 1.1 Clés de signature

- Chaque nœud possède une **paire de clés ECDSA** (courbe `secp256r1`).
- Les clés sont générées **une seule fois** avant le premier lancement, par l'outil `KeyGenerator` (Binôme 1), dans le dossier `keys/` à la racine du projet :

| Fichier | Contenu | Lu par |
|---------|---------|--------|
| `keys/A.pub`, `keys/B.pub`, `keys/C.pub` | Clé publique (X.509, Base64) | Tous les nœuds et le dashboard |
| `keys/A.key`, `keys/B.key`, `keys/C.key` | Clé privée (PKCS#8, Base64) | **Uniquement** le nœud concerné |

- Une clé privée ne quitte jamais son nœud et n'est **jamais** envoyée sur MQTT.
- Les fichiers `*.key` sont ajoutés au `.gitignore` (on ne commite pas de clé privée).
- Comme les trois nœuds tournent sur la même machine (section 10), les clés publiques sont distribuées par fichier : aucun nouveau type de message n'est nécessaire.

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

### 2.1 Sécurité du broker (Binôme 1)

Fichiers dans le dossier `mosquitto/` du projet :

**`mosquitto/mosquitto.conf`**
```
listener 1883 localhost
allow_anonymous false
password_file mosquitto/passwd
acl_file mosquitto/acl
```

**`mosquitto/passwd`** : un compte par client (`A`, `B`, `C`, `DASHBOARD`), créé avec `mosquitto_passwd`. Les mots de passe sont lus depuis `Config` (section 7) et ne sont pas commités en clair.

**`mosquitto/acl`**
```
# Nœuds : diffusent sur water/all, envoient des REPLY à n'importe quel nœud,
# mais ne lisent que water/all et LEUR propre topic.
user A
topic write water/all
topic write water/node/#
topic read  water/all
topic read  water/node/A

user B
topic write water/all
topic write water/node/#
topic read  water/all
topic read  water/node/B

user C
topic write water/all
topic write water/node/#
topic read  water/all
topic read  water/node/C

# Dashboard : lecture seule
user DASHBOARD
topic read water/all
```

- `NodeMqttClient` se connecte avec `username = nodeId` et le mot de passe lu dans `Config`.
- Le dashboard (Binôme 2) se connecte avec le compte `DASHBOARD` et le mot de passe lu dans `Config`.
- Effet : une connexion anonyme est refusée, le dashboard ne peut rien publier, et un nœud ne peut pas lire les `REPLY` destinés à un autre.
- **Limite :** l'ACL ne vérifie pas le champ `from` du JSON. Un nœud peut encore écrire `"from": "B"` dans un `REQUEST`. Pour les blocs, la signature (section 6) couvre ce cas ; pour les autres messages, voir le point ouvert sur les topics (section 11).
- **TLS** (port 8883) : optionnel, car tout tourne en local (section 10). Point ouvert, section 11.

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
| `msgId` | String (UUID) | Identifiant unique du message, sert à dédupliquer (et à refuser un message rejoué) |
| `type` | String | L'un des 6 types de la section 4 |
| `from` | String | ID de l'expéditeur (`A`, `B`, `C`) |
| `to` | String | ID du destinataire, ou `ALL` pour une diffusion |
| `lamport` | int | Timestamp logique de l'envoi (voir section 5) |
| `payload` | objet JSON | Données propres au type, `{}` si vide |

---

## 4. Les 6 types de messages

La sécurisation n'ajoute **aucun** type de message.

### 4.1 Famille accès à la pompe (Ricart-Agrawala) — logique : Binôme 2, transport : Binôme 1

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

### 4.2 Famille registre (blockchain) — logique et transport : Binôme 1

| Type | Sens | Topic | Payload |
|------|------|-------|---------|
| `IRRIGATION_EVENT` | « J'ai arrosé » | `water/all` | `{ "txId": "T-001", "liters": 40, "quotaRemaining": 160, "reservoirLevel": 460 }` |
| `LEDGER_UPDATE` | « Voici le nouveau bloc » | `water/all` | `{ "block": { ...voir section 6, avec signature... } }` |

- `IRRIGATION_EVENT` annonce l'irrigation et met à jour la vision de l'état (quota de l'émetteur, niveau du réservoir).
- `LEDGER_UPDATE` : le récepteur applique **les 7 règles de validation** (section 6.3) par rapport à **son** dernier bloc. Si le bloc est valide, il l'ajoute ; sinon il le **refuse**, ne l'ajoute pas, et signale une **divergence** avec la raison du refus (log + liste des blocs refusés, affichée par le dashboard).
- `LEDGER_UPDATE` est envoyé uniquement si `addBlock` a réussi localement.
- Seul le nœud qui tient la pompe crée un bloc, donc il n'y a pas de bloc concurrent de même index.

### 4.3 Famille snapshot (Chandy-Lamport) — logique et transport : Binôme 1

| Type | Sens | Topic | Payload |
|------|------|-------|---------|
| `MARKER` | « Enregistre ton état » | `water/all` | `{ "snapshotId": "S1", "initiator": "A" }` |

- Premier `MARKER` reçu pour un `snapshotId` : enregistrer l'état local, renvoyer un `MARKER` aux autres, commencer à enregistrer les canaux entrants.
- `MARKER` suivants : arrêter l'enregistrement du canal d'où il vient.
- Snapshot local terminé quand un `MARKER` a été reçu de chaque autre nœud.
- **Enregistrement des canaux :** pendant un snapshot, `MessageHandler` (Binôme 1) transmet chaque message reçu à `SnapshotService.onMessage(m)` **avant** de l'envoyer au module concerné. Si le canal d'où vient le message est en cours d'enregistrement, le message est ajouté à l'état du canal, puis traité normalement.
- **État local :** `ChandyLamport` obtient l'état du nœud (humidité, quota, état de la pompe) via `LocalStateProvider` (section 7), implémenté par Binôme 2, et lit lui-même le registre.

---

## 5. Horloge de Lamport

- Chaque nœud a **une** `LamportClock` (écrite par Binôme 2), initialisée à `0`, passée au constructeur de `NodeMqttClient`.
- **À l'envoi** : `tick()` (compteur + 1), et la valeur obtenue est écrite dans `lamport`.
- **À la réception** : `update(m.lamport)` donne `max(local, reçu) + 1`, avant tout traitement du message.
- Qui appelle quoi :
  - `publish(Message)` fait le `tick()` et remplit `lamport` automatiquement ;
  - la couche de réception fait `update()` avant d'appeler `onMessage()`.
  - Les modules de Binôme 2 n'appellent donc jamais `tick()` ni `update()` pour l'envoi/réception de messages. Ils les appellent seulement pour un événement local qu'ils veulent dater.
- Ne pas confondre avec le champ `timestamp` d'un bloc, qui est l'heure réelle.

---

## 6. Blockchain : format, signature et validation du bloc

### 6.1 Format

```json
{
  "index": 3,
  "timestamp": 1731153600000,
  "nodeId": "A",
  "liters": 40,
  "quotaRemaining": 160,
  "previousHash": "9f86d081...",
  "hash": "a7c3e1f4...",
  "signature": "MEUCIQDx...=="
}
```

| Champ | Description |
|-------|-------------|
| `index` | Position dans la chaîne, genesis = 0 |
| `timestamp` | Heure réelle de création, en millisecondes epoch |
| `nodeId` | Nœud qui a irrigué |
| `liters` | Quantité attribuée |
| `quotaRemaining` | Quota restant **du nœud `nodeId`** après irrigation (quota individuel) |
| `previousHash` | Hash du bloc précédent |
| `hash` | SHA-256 du bloc |
| `signature` | **Nouveau.** Signature ECDSA du `hash` par le nœud `nodeId`, en Base64. `null` pour le genesis |

**Calcul du hash (inchangé) :** SHA-256 de la chaîne
`index|timestamp|nodeId|liters|quotaRemaining|previousHash`
(séparateur `|`, encodage UTF-8, résultat en hexadécimal minuscule, les champs `hash` et `signature` n'entrent pas dans le calcul).

**Bloc genesis identique sur les 3 nœuds** (sinon les copies divergent dès le départ) :
`index = 0`, `timestamp = 0`, `nodeId = "GENESIS"`, `liters = 0`, `quotaRemaining = 0`, `previousHash = "0"`, `signature = null`.

### 6.2 Signature

- Algorithme : `SHA256withECDSA` (Java, `java.security.Signature`), clés de la section 1.1.
- Donnée signée : la chaîne `hash` (64 caractères hexadécimaux), encodée en UTF-8.
- Seul le nœud `nodeId` peut produire une signature valide, car lui seul possède `keys/{nodeId}.key`.
- La signature est vérifiée avec `keys/{nodeId}.pub`.

### 6.3 Règles de validation (avant tout ajout, local ou reçu)

Un bloc est ajouté **seulement s'il respecte les 7 règles**. Sinon il est refusé avec `InvalidBlockException` et la raison, et la chaîne n'est pas modifiée.

| # | Règle | Ce qu'elle bloque |
|---|-------|-------------------|
| V1 | Hash recalculé = `hash` du bloc | Un bloc modifié (ex. `liters` 50 → 5) |
| V2 | `index = dernier.index + 1` et `previousHash = dernier.hash` | Un bloc hors chaîne, rejoué ou en double |
| V3 | `nodeId` ∈ {`A`, `B`, `C`} | Un nœud inconnu |
| V4 | Signature valide avec la clé publique de `nodeId` | Un bloc créé au nom d'un autre agriculteur |
| V5 | `liters > 0` | Une quantité nulle ou négative |
| V6 | `quotaRemaining = quota actuel de nodeId − liters`, et `≥ 0` | Un quota dépassé ou falsifié |
| V7 | `liters ≤ réservoir restant` | Une attribution supérieure à l'eau disponible |

- Le quota actuel d'un nœud et le réservoir restant sont **recalculés à partir de la chaîne** (valeurs initiales de la section 9, moins les blocs déjà acceptés). Chaque nœud vérifie donc les blocs des autres sans dépendre de leur état local.
- `validate()` applique V1 à V4 à toute la chaîne (vérification complète au démarrage et après une réception).

---

## 7. Interfaces Java

Packages sous `com.smartwater`.

```java
// racine (Binôme 1) : constantes partagées, utilisées par les deux binômes
public final class Config {
    public static final String BROKER_URL       = "tcp://localhost:1883";
    public static final int    RESERVOIR_INITIAL = 1000;   // litres
    public static final int    QUOTA_INITIAL     = 200;    // litres par agriculteur
    public static final String KEYS_DIR          = "keys";
    /** Mot de passe MQTT d'un client (A, B, C, DASHBOARD), lu depuis une variable
     *  d'environnement ou un fichier local non commité. */
    public static String mqttPassword(String clientId);
}

// communication
public interface MessageSender {
    /** Fait tick(), remplit lamport/msgId/from, publie. Retourne le timestamp Lamport utilisé. */
    int publish(Message m);
}

public interface MessageListener {
    /** Appelé à la réception, après update() de l'horloge. Jamais appelé pour ses propres messages. */
    void onMessage(Message m);
}

// algorithms (Binôme 2)
public class LamportClock {
    public synchronized int tick();
    public synchronized int update(int received);
    public synchronized int get();
    /** Négatif si (tsA, idA) est prioritaire sur (tsB, idB). */
    public static int compare(int tsA, String idA, int tsB, String idB);
}

// communication (Binôme 1) : constructeur
// public NodeMqttClient(String nodeId, LamportClock clock)
// se connecte avec username = nodeId et password = Config.mqttPassword(nodeId)

// irrigation (Binôme 2 fournit, Binôme 1 utilise)
public interface PumpAccess {
    void requestPump();   // bloque jusqu'à l'entrée en section critique
    void releasePump();   // envoie les REPLY différés + RELEASE
}

// security (Binôme 1)
public final class KeyGenerator {
    /** Génère keys/{id}.pub et keys/{id}.key pour A, B et C. À lancer une fois. */
    public static void main(String[] args);
    public static PublicKey  loadPublicKey(String nodeId);
    public static PrivateKey loadPrivateKey(String nodeId);
}
public final class BlockSigner {
    public static String  sign(String hash, PrivateKey key);   // Base64
    public static boolean verify(Block b, PublicKey key);
}

// blockchain (Binôme 1 fournit, Binôme 2 utilise)
public class InvalidBlockException extends Exception {
    // getMessage() = raison du refus (ex. "V6 : quota dépassé pour C")
}

public interface Ledger {
    /** Crée le bloc, le signe avec la clé du nœud, le valide (V1-V7) puis l'ajoute.
     *  Si une règle échoue : lève InvalidBlockException et la chaîne n'est PAS modifiée. */
    Block addBlock(String nodeId, int liters, int quotaRemaining) throws InvalidBlockException;
    boolean validate();               // true si toute la chaîne est valide (V1-V4)
    List<Block> getChain();           // copie en lecture seule
    List<RejectedBlock> getRejected(); // blocs refusés + raison (affichés par le dashboard)
}
// RejectedBlock : record (Block block, String reason, long time)
// Obtention par un nœud     : Ledger ledger = Blockchain.forNode("A");  (charge les clés, genesis, Config)
// Obtention par le dashboard : Ledger ledger = Blockchain.observer();  (clés publiques seulement,
//   reçoit les LEDGER_UPDATE, les valide et enregistre les refus ; ne crée jamais de bloc)
// Usage interne Binôme 1 : Blockchain.acceptBlock(Block b) throws InvalidBlockException,
// appelée par LedgerReplicator à la réception d'un LEDGER_UPDATE.

// node (Binôme 2 fournit, Binôme 1 utilise pour Chandy-Lamport)
public interface LocalStateProvider {
    /** Copie cohérente de l'état local : humidité, quota restant, état de la pompe
     *  (RELEASED / WANTED / HELD), demandes différées. */
    LocalState getLocalState();
}

// snapshot (Binôme 1 fournit)
public interface SnapshotService {
    void startSnapshot();             // initiateur
    void onMessage(Message m);        // appelé par MessageHandler pour enregistrer les canaux
    boolean isComplete();
    GlobalSnapshot getResult();
}
```

- `Block` est **immuable** : champs `private final`, aucun setter.
- Les signatures peuvent évoluer, mais **seulement après accord** et mise à jour de ce fichier.

---

## 8. Séquence imposée lors d'une irrigation

Dans la section critique, dans cet ordre :

1. `requestPump()` : entrée en section critique.
2. Calcul de l'allocation (section 9).
3. Si `attribué > 0` : création du bloc (`Ledger.addBlock`).
   - **Si `InvalidBlockException`** : log de la raison, pas d'irrigation, **aucun** compteur modifié, aller directement à l'étape 6.
4. Mise à jour des compteurs locaux (quota, réservoir) — **seulement après le succès de `addBlock`**.
5. Diffusion de `IRRIGATION_EVENT`, puis de `LEDGER_UPDATE`.
6. `releasePump()` : `RELEASE` puis envoi des `REPLY` différés.

Le bloc est diffusé **avant** les `REPLY` différés, pour que le nœud suivant ait un registre à jour avant de créer son propre bloc. Avant de créer un bloc, un nœud vérifie que sa chaîne est à jour avec le dernier `LEDGER_UPDATE` reçu.

---

## 9. Règle d'allocation de l'eau

```
attribué = min(besoin, quotaRestant, réservoirDisponible)
```

- Le calcul est fait **dans la section critique**, jamais avant, pour éviter qu'un même volume soit attribué deux fois.
- Si `attribué == 0` : pas d'irrigation, pas de bloc, mais `releasePump()` est quand même appelé.
- Un nœud dont l'humidité est suffisante, ou dont le quota est à 0, ne diffuse pas de `REQUEST`.

**Valeurs initiales fixées** (dans `Config`, car les règles V6 et V7 en dépendent et doivent être identiques sur les 3 nœuds) : réservoir = **1000 L**, quota par agriculteur = **200 L**.

---

## 10. Hypothèses de fonctionnement

1. **Aucune panne de nœud** pendant l'exécution (limite connue de Ricart-Agrawala). À écrire dans le rapport.
2. **Canaux FIFO fiables** (exigés par Chandy-Lamport) : obtenus avec QoS 2 et l'ordre de publication d'un même client. MQTT ne garantit pas l'ordre entre deux topics différents ; c'est une hypothèse à écrire dans le rapport.
3. **La réplication du registre n'est pas un consensus** : SHA-256, le chaînage et les signatures détectent et refusent les blocs invalides, mais ne tranchent pas entre deux versions concurrentes.
4. Tous les nœuds tournent sur la même machine et le même broker.
5. **Modèle de menace :**
   - au plus **un** nœud au comportement malveillant (il peut modifier sa copie du registre ou envoyer des blocs falsifiés) ;
   - les **clés privées ne sont pas volées** et la machine hôte est de confiance ;
   - **protégé :** intégrité des blocs (V1, V2), authenticité de l'auteur (V4), respect des quotas et du réservoir (V6, V7), accès au broker (section 2.1) ;
   - **non couvert :** confidentialité (les blocs sont lisibles par tous, c'est voulu), usurpation du champ `from` dans `REQUEST`/`REPLY` (voir section 11), collusion de 2 nœuds sur 3, absence de consensus.

---

## 11. Points ouverts à trancher ensemble

- [ ] Rôle exact de `RELEASE` : simple notification (proposé) ou libération explicite.
- [ ] Rattrapage d'un nœud en retard sur le registre : message `LEDGER_REQUEST` / `LEDGER_SYNC` à ajouter, ou ignoré pour ce projet. **Si ajouté :** le nœud récupère les chaînes des autres, applique `validate()` et adopte la version valide partagée par la majorité (réconciliation, à ne pas présenter comme un consensus).
- [ ] Ordre entre topics (décision de Binôme 1, car il possède le transport et Chandy-Lamport) : accepter l'hypothèse FIFO (section 10), ou faire publier chaque nœud sur un topic unique `water/from/{id}` filtré sur `to`. **Avantage sécurité de la 2e option :** avec la règle ACL `pattern write water/from/%c`, un nœud ne peut publier que sous son propre ID, ce qui rend le champ `from` impossible à usurper.
- [ ] TLS sur le broker (port 8883) : activé ou laissé hors périmètre (tout tourne en local).
- [ ] Seuil d'humidité.
- [ ] Contenu de `LocalState` (Binôme 2 propose, Binôme 1 valide) et format de `GlobalSnapshot` (Binôme 1 propose, Binôme 2 valide).

---

## 12. Validation

| Binôme | Nom | Date | Validé |
|--------|-----|------|--------|
| Binôme 1 | | | ☐ |
| Binôme 2 | | | ☐ |

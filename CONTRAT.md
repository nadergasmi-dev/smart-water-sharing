# CONTRAT D'INTERFACE — Smart Water Sharing

**Version 3.6** — sécurisation du registre et du broker (v3.0) + un topic MQTT par expéditeur (v3.1) + rattrapage du registre (v3.2) + snapshot global (v3.3) + intégration de la séquence d'irrigation (v3.4) + verrou de cohérence du snapshot et seuil d'humidité (v3.5) + balises d'état pour un dashboard lancé en retard (v3.6). À valider par Binôme 1 et Binôme 2.

**Changements de la version 3.6 par rapport à la 3.5 (dashboard exact quel que soit le moment où il démarre) :**
- **Problème constaté :** MQTT ne garde aucun historique et `mvn javafx:run` met 10 à 25 s à s'ouvrir. Le dashboard, qui ne peut rien publier (lecture seule), ne voyait donc que les messages postérieurs à son démarrage : registre figé (« 1 bloc, quotas 200/200 ») et compteurs de pompe décalés pour toujours.
- **Nouveau type `NODE_STATUS`** (10 types, section 4.4) : chaque nœud diffuse **chaque seconde** (`Config.STATUS_PERIOD_MS`) son état réel, sa chaîne complète et ses derniers blocs refusés. Aucun changement de sécurité : le dashboard reste en lecture seule (ACL inchangée).
- **Impact Binôme 2 :** `FarmerNode` fournit ses compteurs aux balises (`NodeRuntime.setStatsSupplier`) ; `DashboardModel` exploite les balises (déjà fait). Les balises ne sont ni dans le journal, ni dans le compteur de messages, ni dans les « messages en transit » d'un snapshot.
- **Dashboard :** fenêtre adaptée à la taille de l'écran (la taille fixe 1280 × 820 dépassait d'un portable 1280 × 720), indicateur en ligne / hors ligne, défilement vers le dernier bloc. Scripts `scripts/run-demo.ps1` et `scripts/run-dashboard.ps1`.

**Changements de la version 3.5 par rapport à la 3.4 (retours de Binôme 2) :**
- **Verrou de cohérence du snapshot** : dans `IrrigationSequence`, « bloc → compteurs → `IRRIGATION_EVENT` → `LEDGER_UPDATE` » se fait d'un seul bloc, sous le verrou du client MQTT, le même que celui de Chandy-Lamport. Un snapshot ne voit jamais un registre à jour avec des compteurs pas encore mis à jour (section 8). **Règle d'ordre des verrous** pour Binôme 2 (section 7).
- **Seuil d'humidité** : 30 %, dans `Config.HUMIDITY_THRESHOLD` (point ouvert tranché, section 11).

**Changements de la version 3.4 par rapport à la 3.3 (intégration) :**
- La séquence de la section 8 est codée une fois pour toutes dans `IrrigationSequence` (Binôme 1) ; Binôme 2 fournit la pompe (`PumpAccess`), l'allocation et la mise à jour de ses compteurs (section 7).
- `NodeRuntime` (Binôme 1) assemble et branche tout ce que Binôme 1 fournit à un nœud ; `FarmerNode` (Binôme 2) n'a plus qu'à y brancher Ricart-Agrawala.
- Section 8 : l'allocation reçoit le quota et le réservoir **recalculés à partir de la chaîne** ; pas de bloc sur une copie connue pour être en retard.

**Changements de la version 3.3 par rapport à la 3.2 (Chandy-Lamport) :**
- Nouveau type `SNAPSHOT_STATE` (section 4.3) : chaque nœud diffuse son instantané local une fois terminé, pour que chacun (et le dashboard) puisse assembler l'état global. On passe de 8 à 9 types.
- Format de `GlobalSnapshot` / `LocalSnapshot` proposé par Binôme 1, et **proposition** de contenu pour `LocalState` (à valider par Binôme 2), section 7.
- Le déclenchement se fait **depuis un nœud** (le dashboard est en lecture seule) ; `MessageHandler.execute(Runnable)` permet de démarrer le snapshot entre deux messages.

**Changements de la version 3.2 par rapport à la 3.1 (réplication) :**
- Deux nouveaux types de messages, `LEDGER_REQUEST` et `LEDGER_SYNC` (section 4.2) : un nœud en retard récupère les blocs manquants, chacun **revalidé par les 7 règles** avant d'être ajouté. On passe de 6 à 8 types.
- Point ouvert « rattrapage » tranché (section 11). Interface `LedgerReplicator` ajoutée (section 7).
- **Impact Binôme 2 :** aucun pour ses modules. Le dashboard peut suivre le registre avec un `LedgerReplicator` passif (section 7).
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

**Changements de la version 3.1 par rapport à la 3.0 (topics par expéditeur) :**
- `water/all` et `water/node/{id}` sont **remplacés** par un topic unique par expéditeur : `water/from/{id}` (section 2). Le destinataire reste dans le champ `to`.
- Un nœud ne peut écrire que sous son propre nom (ACL, section 2.1) : le champ `from` ne peut plus être usurpé. Le récepteur vérifie en plus que `from` correspond au topic.
- Tous les messages d'un même expéditeur passent par le même topic : leur ordre est préservé (hypothèse FIFO, section 10).
- **Impact Binôme 2 :** aucun changement d'API (`MessageSender.publish`, `MessageListener.onMessage`). Seul le **dashboard** change : il s'abonne à `water/from/+` au lieu de `water/all` (section 2). Les mots de passe MQTT se créent avec `mosquitto/setup.ps1` (section 2.1).
- Contrepartie : un nœud peut techniquement lire les messages destinés à un autre (le tri se fait à la réception, par `to`). La confidentialité n'est pas un objectif (section 10).

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

| Topic | Usage | Qui publie | Abonnés |
|-------|-------|------------|---------|
| `water/from/{id}` | **Tous** les messages de l'expéditeur `{id}` : diffusions et messages ciblés (`REQUEST`, `REPLY`, `RELEASE`, `MARKER`, `IRRIGATION_EVENT`, `LEDGER_UPDATE`) | Uniquement le nœud `{id}` (ACL, section 2.1) | Tous les nœuds et le dashboard, via `water/from/+` |

- Le destinataire est indiqué par le champ `to` du JSON : `ALL` (diffusion) ou l'ID d'un nœud. Un nœud **ignore** tout message dont `to` n'est ni `ALL` ni son propre ID : le tri se fait à la réception, plus par topic.
- Un nœud reçoit aussi ses propres messages (il est abonné à `water/from/+`) : il les **ignore** en comparant l'expéditeur du topic à son ID.
- **Vérification de l'expéditeur :** le topic prouve qui a publié, car l'ACL interdit à un nœud d'écrire sous le nom d'un autre. À la réception, un message dont le champ `from` ne correspond pas au topic est **jeté** et signalé comme usurpation (`NodeMqttClient.getSpoofedMessageCount()`).
- **Pourquoi un seul topic par expéditeur :** (1) l'identité de l'expéditeur est garantie par le broker ; (2) tous les messages d'un même expéditeur arrivent dans l'ordre où ils ont été envoyés, ce que Chandy-Lamport exige (section 10).
- Le dashboard s'abonne à `water/from/+` et ne publie rien.

### 2.1 Sécurité du broker (Binôme 1)

Fichiers dans le dossier `mosquitto/` du projet :

**`mosquitto/mosquitto.conf`**
```
listener 1883 localhost
allow_anonymous false
password_file mosquitto/passwd
acl_file mosquitto/acl
```

**`mosquitto/passwd`** : un compte par client (`A`, `B`, `C`, `DASHBOARD`), créé avec `mosquitto_passwd`. Les mots de passe sont lus depuis `Config` (section 7) et ne sont pas commités en clair. Le script `mosquitto/setup.ps1` génère des mots de passe aléatoires dans `config/mqtt-passwords.properties` (lu par `Config.mqttPassword`) et construit `mosquitto/passwd` ; ni l'un ni l'autre ne sont commités : **chaque binôme lance le script chez lui**.

**`mosquitto/acl`**
```
# Un topic par expéditeur : water/from/<id>
# Chaque nœud écrit UNIQUEMENT sous son propre nom : il ne peut donc pas se faire passer pour un autre.
# Les règles sont rattachées au NOM D'UTILISATEUR (authentifié par mot de passe), pas à l'identifiant
# client MQTT, que n'importe quel client peut choisir librement.
# Tous lisent water/from/+ ; le destinataire d'un message est dans le champ "to" du JSON.
user A
topic write water/from/A
topic read  water/from/+

user B
topic write water/from/B
topic read  water/from/+

user C
topic write water/from/C
topic read  water/from/+

# Dashboard : lecture seule
user DASHBOARD
topic read water/from/+
```

- `NodeMqttClient` se connecte avec `username = nodeId` et le mot de passe lu dans `Config`.
- Le dashboard (Binôme 2) se connecte avec le compte `DASHBOARD` et le mot de passe lu dans `Config`.
- Effet : une connexion anonyme est refusée, le dashboard ne peut rien publier, et un nœud ne peut pas écrire sous le nom d'un autre (le topic prouve qui a publié).
- **Pourquoi des règles par nom d'utilisateur (`user A`, …) et pas `pattern … %c` :** elles dépendent du compte authentifié par mot de passe. L'identifiant client MQTT (`%c`), lui, est choisi librement par le client et ne prouve rien.
- **Usurpation du champ `from` :** impossible sans le mot de passe d'un autre nœud. L'ACL interdit d'écrire sous un autre nom, et le récepteur jette tout message dont `from` ne correspond pas au topic. Pour les blocs, la signature (section 6) reste la protection principale.
- **Limite :** tout nœud peut *lire* les messages destinés à un autre (le tri par `to` se fait à la réception). Pas de confidentialité (section 10).
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
| `type` | String | L'un des 10 types de la section 4 |
| `from` | String | ID de l'expéditeur (`A`, `B`, `C`). Doit être égal à l'expéditeur du topic `water/from/{id}`, sinon le message est jeté (section 2) |
| `to` | String | ID du destinataire, ou `ALL` pour une diffusion. Un nœud ignore les messages qui ne lui sont pas destinés |
| `lamport` | int | Timestamp logique de l'envoi (voir section 5) |
| `payload` | objet JSON | Données propres au type, `{}` si vide |

---

## 4. Les 10 types de messages

La sécurisation n'ajoute **aucun** type de message.

Tous les messages sont publiés sur le topic de leur expéditeur, `water/from/{from}` (section 2). Dans les tableaux ci-dessous, la colonne `to` donne leur destinataire.

### 4.1 Famille accès à la pompe (Ricart-Agrawala) — logique : Binôme 2, transport : Binôme 1

| Type | Sens | `to` | Payload |
|------|------|------|---------|
| `REQUEST` | « Je veux la pompe » | `ALL` | `{}` |
| `REPLY` | « Je te laisse passer » | `{demandeur}` | `{}` |
| `RELEASE` | « J'ai fini, la pompe est libre » | `ALL` | `{}` |

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

| Type | Sens | `to` | Payload |
|------|------|------|---------|
| `IRRIGATION_EVENT` | « J'ai arrosé » | `ALL` | `{ "txId": "T-001", "liters": 40, "quotaRemaining": 160, "reservoirLevel": 460 }` |
| `LEDGER_UPDATE` | « Voici le nouveau bloc » | `ALL` | `{ "block": { ...voir section 6, avec signature... } }` |
| `LEDGER_REQUEST` | « Envoyez-moi les blocs à partir de l'index N » | `ALL` | `{ "fromIndex": 3, "lastHash": "9f86d081..." }` (`fromIndex` = longueur de ma chaîne) |
| `LEDGER_SYNC` | « Voici mon état et les blocs qui te manquent » | `{demandeur}` | `{ "length": 5, "lastHash": "a7c3e1f4...", "blocks": [ {bloc 3}, {bloc 4} ] }` (liste vide si rien ne manque) |

- `IRRIGATION_EVENT` annonce l'irrigation et met à jour la vision de l'état (quota de l'émetteur, niveau du réservoir).
- `LEDGER_UPDATE` : le récepteur applique **les 7 règles de validation** (section 6.3) par rapport à **son** dernier bloc. Si le bloc est valide, il l'ajoute ; sinon il le **refuse**, ne l'ajoute pas, et signale une **divergence** avec la raison du refus (log + liste des blocs refusés, affichée par le dashboard).
- `LEDGER_UPDATE` est envoyé uniquement si `addBlock` a réussi localement.
- Seul le nœud qui tient la pompe crée un bloc, donc il n'y a pas de bloc concurrent de même index.
- **Réception d'un bloc, 4 cas :** index déjà présent avec le même hash → doublon ignoré ; index déjà présent avec un autre hash → **fourche** signalée, rien n'est remplacé ; index = suivant → les 7 règles ; index plus loin → **trou** : le nœud est en retard et envoie un `LEDGER_REQUEST`.
- **Rattrapage :** un nœud envoie `LEDGER_REQUEST` au démarrage, et automatiquement quand il détecte un trou (au plus une demande identique toutes les 2 s). Chaque autre nœud répond **toujours** par `LEDGER_SYNC` : son état (longueur + hash du dernier bloc) et les blocs qui manquent au demandeur. Le demandeur ajoute les blocs **un par un, chacun revalidé par les 7 règles**, et s'arrête au premier refusé. Les réponses en double (deux nœuds répondent) donnent des doublons, ignorés.
- **Comparaison des copies :** chaque nœud garde le dernier état connu des autres (longueur + hash du dernier bloc), mis à jour par `LEDGER_UPDATE`, `LEDGER_REQUEST` et `LEDGER_SYNC`. Un nœud qui reçoit un `LEDGER_REQUEST` d'un nœud plus long que lui se rattrape à son tour ; à longueur égale et dernier hash différent, il signale une fourche.
- **Ce n'est pas un consensus :** les blocs invalides sont refusés, mais deux versions valides concurrentes (fourche) sont seulement signalées, jamais départagées (section 10).

### 4.3 Famille snapshot (Chandy-Lamport) — logique et transport : Binôme 1

| Type | Sens | `to` | Payload |
|------|------|------|---------|
| `MARKER` | « Enregistre ton état » | `ALL` | `{ "snapshotId": "A-1", "initiator": "A" }` |
| `SNAPSHOT_STATE` | « Voici mon instantané local » | `ALL` | `{ "snapshotId": "A-1", "initiator": "A", "local": { LocalSnapshot, section 7 } }` |

- **Déclenchement :** depuis un **nœud** (`startSnapshot()`), jamais depuis le dashboard, qui ne peut rien publier. `snapshotId` = initiateur + numéro (`A-1`, `A-2`…). **Un seul snapshot à la fois** : un `MARKER` d'un autre snapshot reçu pendant qu'un snapshot est en cours est ignoré.
- Premier `MARKER` reçu pour un `snapshotId` : enregistrer l'état local, renvoyer un `MARKER` aux autres, commencer à enregistrer les canaux entrants (sauf celui d'où vient ce `MARKER`, vide par définition).
- **Enregistrer l'état et envoyer le `MARKER` se fait d'un seul bloc** : aucun autre message du nœud ne peut partir entre les deux.
- `MARKER` suivants : arrêter l'enregistrement du canal d'où il vient.
- Snapshot local terminé quand un `MARKER` a été reçu de chaque autre nœud. Le nœud diffuse alors `SNAPSHOT_STATE`. L'**état global est complet** quand les instantanés locaux de A, B et C sont connus.
- `SNAPSHOT_STATE` publié au nom d'un autre nœud (`local.nodeId` ≠ expéditeur) : ignoré.
- **Canaux FIFO :** garantis par un topic par expéditeur (section 2) ; `MARKER`, `SNAPSHOT_STATE` et `NODE_STATUS` ne sont jamais enregistrés comme messages en transit.

### 4.4 Famille supervision (balises d'état) — logique et transport : Binôme 1, contenu des compteurs : Binôme 2

| Type | Sens | `to` | Payload |
|------|------|------|---------|
| `NODE_STATUS` | « Voici mon état, ma chaîne et mes refus » | `ALL` | `{ "local": LocalState, "stats": { "requests": 3, "entries": 3, "requestLamport": -1, "awaiting": ["B"] }, "blocks": [ bloc 1, bloc 2, … ], "rejected": [ { "block": …, "reason": "V6 : …", "time": … } ] }` |

- **Émission :** par chaque nœud, toutes les `Config.STATUS_PERIOD_MS` (1 s), sur son topic `water/from/{id}`. L'état est lu **sous le verrou du client MQTT** (comme le snapshot et la séquence d'irrigation) : une balise ne montre jamais un bloc ajouté dont les compteurs ne sont pas encore à jour.
- **Contenu :** `local` = `LocalState` (section 7) ; `stats` = compteurs propres au nœud, ouverts (`requests` = demandes, `entries` = accès à la pompe, `requestLamport` = estampille de la demande en cours ou -1, `awaiting` = REPLY encore attendus) ; `blocks` = la copie du registre **sans le genesis** ; `rejected` = les 20 derniers blocs refusés avec leur raison.
- **But :** un observateur lancé en retard (le dashboard) se met à jour sans rien demander. Il n'a aucun historique (MQTT n'en garde pas) et ne peut rien publier.
- **Réception par un réplicateur passif (dashboard) :** il met à jour ce qu'il sait de la copie de l'expéditeur, recopie les blocs qu'il n'a pas (**chacun revalidé par les 7 règles**) et ajoute les blocs refusés annoncés (un même bloc refusé pour la même raison n'apparaît qu'une fois). Une alerte identique (fourche, balise mal formée) n'est signalée **qu'une fois**, pas une fois par seconde.
- **Réception par un nœud :** il met à jour ce qu'il sait de la copie de l'autre (`getPeerHeads`) ; s'il est en retard, il demande un rattrapage (section 4.2). Il ne recopie rien lui-même.
- **Reconstitution de la pompe par le dashboard :** il compte les `REQUEST` / `REPLY` / `RELEASE` en direct, et **recale** ces compteurs sur l'état annoncé par chaque balise (sans cela, avoir raté un cycle le décalerait d'un cran pour toujours). Les compteurs `requests` / `entries` affichés sont la valeur annoncée plus ce qui s'est passé depuis la balise.
- **Présence :** un nœud sans message ni balise depuis plus de 4 s est affiché **hors ligne**.
- **Exclusions :** les balises ne sont ni affichées dans le journal du dashboard, ni comptées comme messages applicatifs, ni enregistrées comme messages en transit par Chandy-Lamport. Elles font avancer l'horloge de Lamport comme tout message envoyé.
- **Limite :** une balise est publiée par le nœud lui-même. Un nœud malveillant pourrait annoncer un faux état (affichage trompeur), mais jamais un faux bloc : tout bloc reçu par balise repasse par les 7 règles. La supervision ne remplace pas la vérification.
- **Taille :** environ 300 octets par bloc (soit quelques Ko pour les 20 à 60 blocs d'une démonstration), une fois par seconde et par nœud.
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
    public static final int    HUMIDITY_THRESHOLD = 30;    // % : en dessous, le nœud demande à irriguer
    public static final long   STATUS_PERIOD_MS   = 1000;  // période des balises d'état NODE_STATUS
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

// integration (Binôme 1) : séquence d'irrigation (section 8) et assemblage d'un nœud
public final class IrrigationSequence {
    interface Allocation { int liters(int quotaRemaining, int reservoirRemaining); }   // Binôme 2
    interface Listener {                                                               // Binôme 2
        void onIrrigated(Block block);            // mettre à jour les compteurs (seulement si bloc accepté)
        default void onNothingAllocated() { }
        default void onRefused(String reason) { } // ex. "V6 : quota dépassé…"
    }
    Result irrigate(Allocation allocation, Listener listener); // IRRIGATED | NOTHING_ALLOCATED | REFUSED
}                                                              //   | LEDGER_NOT_UP_TO_DATE
public class NodeRuntime {
    NodeRuntime(String nodeId, LocalStateProvider stateProvider);   // clés keys/, broker et mot de passe de Config
    void start();  void stop();                                     // start : connexion + rattrapage + balises d'état
    void setStatsSupplier(Supplier<JsonObject> stats);              // compteurs joints aux balises (Binôme 2)
    void startBeacons(long periodMs);  void publishStatus();        // balises NODE_STATUS (section 4.4)
    IrrigationSequence irrigationSequence(PumpAccess pump);
    LamportClock clock();  NodeMqttClient sender();  MessageHandler handler();
    Blockchain ledger();   LedgerReplicator replicator();  ChandyLamport snapshot();
}
// FarmerNode (Binôme 2) :
//   NodeRuntime rt = new NodeRuntime("A", this);
//   RicartAgrawala ra = new RicartAgrawala("A", rt.clock(), rt.sender());
//   rt.handler().register(ra, REQUEST, REPLY, RELEASE);  rt.start();
//   rt.irrigationSequence(ra).irrigate((quota, reservoir) -> Math.min(besoin, Math.min(quota, reservoir)), listener);

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
// appelée par LedgerReplicator à la réception d'un LEDGER_UPDATE ou d'un LEDGER_SYNC.

// blockchain (Binôme 1) : réplication du registre
public class LedgerReplicator implements MessageListener {
    public LedgerReplicator(String nodeId, Blockchain ledger, MessageSender sender); // sender null = passif (dashboard)
    public static final MessageType[] TYPES;   // LEDGER_UPDATE, LEDGER_REQUEST, LEDGER_SYNC, NODE_STATUS
    public void broadcast(Block block);        // diffuse un bloc ajouté avec succès (séquence d'irrigation, étape 5)
    public void requestSync();                 // au démarrage d'un nœud : récupérer les blocs manquants
    public List<Divergence> getDivergences();  // blocs refusés, fourches : (peer, index, reason, time)
    public Map<String, PeerHead> getPeerHeads(); // dernier état connu des autres : (nodeId, length, lastHash, time)
    public boolean isInSyncWith(String peer);  // même longueur et même dernier hash
}
// Branchement : handler.register(replicator, LedgerReplicator.TYPES);
// Dashboard : new LedgerReplicator("DASHBOARD", Blockchain.observer(), null), alimenté avec les messages
//   LEDGER_* et NODE_STATUS lus sur water/from/+ (il voit aussi les LEDGER_SYNC destinés aux autres ; les balises
//   le mettent à jour même s'il démarre bien après les nœuds).

// node (Binôme 2 fournit, Binôme 1 utilise pour Chandy-Lamport)
public interface LocalStateProvider {
    /** Copie cohérente de l'état local ; appelée sur le thread du MessageHandler, entre deux messages. */
    LocalState getLocalState();
    // Appelée sous le verrou du client MQTT (enregistrement de l'état + MARKER d'un seul bloc).
    // RÈGLE D'ORDRE DES VERROUS : si l'état est protégé par un verrou propre à Binôme 2, toujours prendre
    // le client MQTT EN PREMIER, puis ce verrou ; jamais l'inverse (sinon interblocage possible).
    // Le plus simple : getLocalState() renvoie une copie sans attendre de verrou.
}
// PROPOSITION de Binôme 1, à valider par Binôme 2 (qui peut ajouter des champs) :
public record LocalState(String nodeId, double humidity, int quotaRemaining,
                         String pumpState,               // "RELEASED" | "WANTED" | "HELD"
                         List<String> deferredReplies) { } // nœuds dont le REPLY est différé

// snapshot (Binôme 1 fournit)
public interface SnapshotService {
    void startSnapshot();             // initiateur (un nœud)
    void onMessage(Message m);        // appelé par MessageHandler pour CHAQUE message, avant le module
    boolean isComplete();             // instantanés locaux de A, B et C connus
    GlobalSnapshot getResult();       // null si aucun snapshot
}
public record LocalSnapshot(String nodeId, LocalState state, int ledgerLength, String ledgerLastHash,
                            Map<String, List<Message>> channels,   // expéditeur -> messages en transit
                            long recordedAt) { }
public record GlobalSnapshot(String snapshotId, String initiator, Map<String, LocalSnapshot> nodes,
                             List<String> expectedNodes) {
    boolean isComplete();  List<Message> messagesInTransit();  boolean sameLedgerEverywhere();
}
// Implémentation : algorithms/ChandyLamport
//   nœud      : new ChandyLamport(id, stateProvider, ledger, mqttClient, handler::execute);
//               handler.setSnapshotService(cl);
//   dashboard : ChandyLamport.observer(), alimenté avec les SNAPSHOT_STATE lus sur water/from/+ (ne déclenche pas).
// MessageHandler.execute(Runnable) : exécute une tâche sur son thread, entre deux messages.
```

- `Block` est **immuable** : champs `private final`, aucun setter.
- Les signatures peuvent évoluer, mais **seulement après accord** et mise à jour de ce fichier.

---

## 8. Séquence imposée lors d'une irrigation

Dans la section critique, dans cet ordre :

1. `requestPump()` : entrée en section critique.
   - Vérification que la copie du registre est à jour : si un autre nœud est connu pour avoir une copie plus longue, rattrapage (section 4.2) ; s'il n'aboutit pas, **pas de bloc** (il créerait une fourche), aller à l'étape 6.
2. Calcul de l'allocation (section 9), avec le quota restant et le réservoir **recalculés à partir de la chaîne** (`Blockchain.getQuota`, `getReservoirRemaining`) : les mêmes valeurs que celles que les autres nœuds vérifieront (V6, V7).
3. Si `attribué > 0` : création du bloc (`Ledger.addBlock`).
   - **Si `InvalidBlockException`** : log de la raison, pas d'irrigation, **aucun** compteur modifié, aller directement à l'étape 6.
4. Mise à jour des compteurs locaux (quota, réservoir) — **seulement après le succès de `addBlock`**.
5. Diffusion de `IRRIGATION_EVENT`, puis de `LEDGER_UPDATE`.
6. `releasePump()` : `RELEASE` puis envoi des `REPLY` différés.

Le bloc est diffusé **avant** les `REPLY` différés, pour que le nœud suivant ait un registre à jour avant de créer son propre bloc. Comme le `LEDGER_UPDATE` et le `REPLY` partent sur le **même topic** (`water/from/{id}`), le nœud suivant traite toujours le bloc avant l'autorisation d'entrer (ordre FIFO). `releasePump()` est appelé dans **tous** les cas, y compris si une exception est levée.

**Verrou de cohérence du snapshot :** les étapes 3 (bloc) à 5 (diffusion) s'exécutent d'un seul bloc, sous le verrou du client MQTT, le même que celui utilisé par Chandy-Lamport pour enregistrer l'état et envoyer le `MARKER`. Un snapshot tombe donc avant ou après une irrigation, jamais au milieu. `Listener.onIrrigated` est appelé sous ce verrou : il doit rester court.

Cette séquence est implémentée par `IrrigationSequence` (section 7).

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
2. **Canaux FIFO fiables** (exigés par Chandy-Lamport) : obtenus avec QoS 2 et **un seul topic par expéditeur** (`water/from/{id}`) : tous les messages d'un même expéditeur passent par le même topic, donc arrivent dans l'ordre où ils ont été envoyés. Reste une hypothèse à écrire dans le rapport : un seul broker, et l'ordre par topic garanti par MQTT.
3. **La réplication du registre n'est pas un consensus** : SHA-256, le chaînage et les signatures détectent et refusent les blocs invalides, mais ne tranchent pas entre deux versions concurrentes.
4. Tous les nœuds tournent sur la même machine et le même broker.
5. **Modèle de menace :**
   - au plus **un** nœud au comportement malveillant (il peut modifier sa copie du registre ou envoyer des blocs falsifiés) ;
   - les **clés privées ne sont pas volées** et la machine hôte est de confiance ;
   - **protégé :** intégrité des blocs (V1, V2), authenticité de l'auteur (V4), respect des quotas et du réservoir (V6, V7), accès au broker (section 2.1), identité de l'expéditeur de chaque message (topic par expéditeur + ACL, section 2) ;
   - **non couvert :** confidentialité (les blocs et les messages sont lisibles par tous, c'est voulu), un nœud qui ne respecte pas le protocole (par exemple qui ne répond jamais à un `REQUEST` : Ricart-Agrawala suppose des nœuds honnêtes), collusion de 2 nœuds sur 3, absence de consensus, TLS (tout tourne en local, section 11).

---

## 11. Points ouverts à trancher ensemble

- [ ] Rôle exact de `RELEASE` : simple notification (proposé) ou libération explicite.
- [x] ~~Rattrapage d'un nœud en retard~~ → **décidé en v3.2 : ajouté** (`LEDGER_REQUEST` / `LEDGER_SYNC`, section 4.2). Le nœud en retard ajoute les blocs manquants un par un, chacun revalidé par les 7 règles. Une fourche (deux versions valides) est **signalée** mais pas départagée : pas de vote à la majorité, ce n'est pas un consensus.
- [x] ~~Ordre entre topics~~ → **décidé en v3.1 (Binôme 1)** : un topic unique par expéditeur `water/from/{id}`, destinataire filtré sur `to` à la réception. L'ACL (règles par nom d'utilisateur) interdit d'écrire sous le nom d'un autre : le champ `from` ne peut plus être usurpé et l'ordre d'un même expéditeur est préservé.
- [ ] TLS sur le broker (port 8883) : activé ou laissé hors périmètre (tout tourne en local).
- [x] ~~Seuil d'humidité~~ → **décidé en v3.5 : 30 %** (proposé par Binôme 2), dans `Config.HUMIDITY_THRESHOLD`.
- [ ] Contenu de `LocalState` et format de `GlobalSnapshot` : **proposés par Binôme 1 en v3.3** (section 7), à valider par Binôme 2 (qui peut ajouter des champs à `LocalState`).

---

## 12. Validation

| Binôme | Nom | Date | Validé |
|--------|-----|------|--------|
| Binôme 1 | | | ☐ |
| Binôme 2 | | | ☐ |

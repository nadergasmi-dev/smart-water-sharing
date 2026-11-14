# Smart Water Sharing — Suivi des tâches (Binôme 1)

> Basé sur `CONTRAT.md` **v3.4** (v3.4 : séquence d'irrigation `IrrigationSequence`, `NodeRuntime` ; v3.3 : snapshot global, `SNAPSHOT_STATE`) — et le plan complet (v3 : sécurisation du registre et du broker ; v3.1 : un topic MQTT par expéditeur `water/from/{id}`, anti-usurpation du champ `from` ; **v3.2 : rattrapage du registre**, `LEDGER_REQUEST` / `LEDGER_SYNC`).
> **Dernière mise à jour : 14/11/2026** — état relevé d'après le code du dépôt `smart-water-sharing/` (`mvn clean test` : **99 tests, 0 échec**).

**Légende :** `[x]` fait et conforme au contrat · 🟡 partiellement fait · `[ ]` à faire.

**Tes parties (une partie = un binôme) :** MQTT et communication · Blockchain et réplication · **Sécurité** (nouvelle partie v3) · Chandy-Lamport.
**Changement v3 :** tu ne fais plus de panneaux JavaFX — tout le dashboard passe à Binôme 2. Tu lui fournis les données par les interfaces du contrat (`Ledger.getChain()`, `Ledger.getRejected()`, `Blockchain.observer()`, `SnapshotService.getResult()`).
Le prototype (`Block`, `HashValidator`, `BlockSigner`, `Blockchain`, `InvalidBlockException`, `SecurityDemo`) **a été adapté** au package `com.smartwater` et à l'interface `Ledger` du contrat v3.

---

## 📊 Bilan : ce qui est fait, ce qui reste

| Partie | État | Résumé |
|--------|------|--------|
| 0. Préparation | ✅ | Reste seulement : valider le contrat v3 avec Binôme 2. |
| 1. Communication MQTT | ✅ **Faite** | `Message`, `MessageType`, `NodeMqttClient`, `MessageHandler`, interfaces ; **topic par expéditeur `water/from/{id}` (v3.1)** ; `ThreeNodesTest` 4/4, `MessageTest` 3/3. |
| 2. Blockchain locale | ✅ **Faite** | `Block`, `HashValidator`, `Ledger`, `RejectedBlock`, `Blockchain` ; `BlockchainTest` 24 tests. |
| 3. Sécurité | ✅ **Faite** | Registre (`Config`, `KeyGenerator`, `BlockSigner`, V1–V7, 8 tricheries testées) **et broker** (`mosquitto.conf`, `acl`, `setup.ps1`, authentification dans `NodeMqttClient`, `BrokerSecurityTest` 12 tests) **et anti-usurpation de `from`** (topic par expéditeur + ACL par utilisateur + vérification à la réception). Restent : prévenir Binôme 2, décider TLS, scénario complet en Partie 6. |
| 4. Réplication du registre | ✅ **Faite** | `LedgerReplicator` : diffusion, validation à la réception, divergences, comparaison des copies, **rattrapage** (`LEDGER_REQUEST` / `LEDGER_SYNC`, contrat v3.2), mode passif pour le dashboard ; 17 tests. |
| 5. Chandy-Lamport | ✅ **Faite** | `ChandyLamport`, `GlobalSnapshot`, `LocalSnapshot`, `SNAPSHOT_STATE` (contrat v3.3), proposition de `LocalState` ; 11 tests. |
| 6. Intégration irrigation | 🟡 | Côté Binôme 1 fait : `IrrigationSequence`, `NodeRuntime`, `PumpAccess` ; testé avec une pompe simulée (9 tests). Attend Ricart-Agrawala et `FarmerNode` (Binôme 2). |
| 7. Finalisation | ⬜ | Semaine 4. |

**Prochaines étapes, dans l'ordre conseillé :**
1. **Pousser les commits** (`git push`) et **prévenir Binôme 2** : `Config`, `Ledger`, `RejectedBlock`, `Blockchain.forNode` / `observer` sont disponibles ; le dashboard se connecte avec le compte `DASHBOARD` (mot de passe via `Config.mqttPassword("DASHBOARD")`, chacun lance `mosquitto/setup.ps1` chez soi) et **s'abonne à `water/from/+`** (v3.1, seul changement pour lui ; l'API `publish` / `onMessage` ne change pas).
2. ~~`LedgerReplicator` (Partie 4)~~ : fait.
3. ~~Chandy-Lamport (Partie 5)~~ : fait.
4. Partie 6 : côté Binôme 1 fait ; scénario final dès que Binôme 2 livre Ricart-Agrawala et `FarmerNode` (puis avec le broker authentifié).
5. Finalisation, rapport, démo.

**Pour passer sur le broker authentifié (ne change rien tant que tu ne le fais pas) :** le service Windows « mosquitto » (port 1883, anonyme) tourne en ce moment. Pour utiliser le broker sécurisé : arrêter le service (`net stop mosquitto`, en administrateur), puis depuis la racine du projet `mosquitto -c mosquitto/mosquitto.conf -v`. Les comptes existent déjà sur ta machine (`mosquitto/setup.ps1` a été lancé).

**État des points qui étaient à régler :**
- ✅ **Commits faits** : `7831184` (couche communication + contrat v3), `2c86ea8` (registre + sécurité), `715a85b` (broker authentifié), `8beee5b` (contrat v3.1 : topics par expéditeur), `fbde212` (commentaire), `f784349` (Partie 4 : réplication + rattrapage, contrat v3.2) `d444e5b` (Partie 5 : Chandy-Lamport, contrat v3.3) et `325a091` (Partie 6 : séquence d'irrigation, contrat v3.4). Seul `keys/` (clés générées) reste non suivi, volontairement. ⚠️ **Pas encore poussés** : tant qu'il n'y a pas de `git push`, Binôme 2 ne voit rien.
- ✅ **`CONTRAT.md`** : la version du dépôt était la v2.0 ; la **v3.0** (celle du dossier parent) a été remise dans le dépôt. **`MessageTest.java`** restauré.
- ✅ **Rapports de test régénérés** (`mvn clean test`) ; l'ancien `BlockchainTest` périmé a été remplacé par un vrai `BlockchainTest`.
- ⬜ **`LamportClock` est toujours ton mock** : à remplacer par la version de Binôme 2 (même signature) dès qu'il la livre.

---

## ✅ Partie 0 : Préparation — FAITE

- [x] Java (17/21), Maven, IDE, Git installés.
- [x] Mosquitto installé et vérifié (`mosquitto_sub` / `mosquitto_pub`).
- [x] Dépôt Git partagé créé avec Binôme 2.
- [x] Projet Maven créé (Paho, Gson, JUnit 5).
- [x] Arborescence des packages créée : `communication`, `algorithms`, `blockchain`, `node`, `irrigation`, `snapshot`, `ui` (+ `security`).
- [x] `CONTRAT.md` rédigé et validé par Binôme 2.
- [x] **v3 :** package `security` créé ; `keys/*.key` et `config/mqtt-passwords.properties` ajoutés au `.gitignore`.
- [ ] **v3 :** relire et valider `CONTRAT.md` v3 avec Binôme 2.

---

## ✅ Partie 1 : Communication MQTT — FAITE

- [x] `Message.java` (msgId, type, from, to, lamport, payload) + enum `MessageType` (6 types).
- [x] Sérialisation/désérialisation JSON (Gson) : `toJson()` / `fromJson()`.
- [x] Interfaces `MessageSender` / `MessageListener` (section 7 du contrat).
- [x] `NodeMqttClient(String nodeId, LamportClock clock)` :
  - [x] connexion broker (`Config.BROKER_URL`), `clientId` = ID du nœud
  - [x] abonnement `water/from/+` : tous les expéditeurs (réabonnement automatique à chaque reconnexion)
  - [x] `publish(Message m)` → tick, remplit lamport/msgId/from, QoS 2, retourne le timestamp ; publie **toujours sur son propre topic** `water/from/{id}` (le destinataire est dans `to`)
  - [x] ignore ses propres messages (filtre sur l'expéditeur du topic)
  - [x] **v3.1 :** ignore les messages dont `to` n'est ni `ALL` ni son ID (le tri se fait à la réception)
  - [x] **v3.1 :** vérifie que le champ `from` correspond à l'expéditeur du topic ; sinon le message est jeté, signalé dans les logs et compté (`getSpoofedMessageCount()`)
  - [x] déduplication par `msgId` (mémoire des 1000 derniers ; bloque aussi les messages rejoués)
- [x] `MessageHandler.java` :
  - [x] `update()` de l'horloge avant traitement
  - [x] transmission à `SnapshotService.onMessage(m)` avant dispatch
  - [x] dispatch vers le bon module selon `type` (un thread dédié par nœud)
- [x] Gestion reconnexion / erreurs : reconnexion automatique, `connectWithRetry`, messages illisibles ou incomplets ignorés, erreur claire si le broker est injoignable (5 s).

**Reste de cette partie :**
- [ ] Remplacer le mock `LamportClock` par la version de Binôme 2 (⚠️ bloqué par lui).
- [x] `BROKER_URL` déplacé de `NodeMqttClient` vers `Config`.
- [x] `MessageTest` (sérialisation JSON) restauré.
- [ ] L'authentification `username` / `password` relève de la Partie 3 (broker).

**Test de validation :** ✅ `ThreeNodesTest` (4 tests : diffusion reçue par B et C et pas par A ; `REPLY` ciblé reçu par A seul ; réponse depuis `onMessage` sans blocage ; horloges de Lamport cohérentes) — relancé le 14/11/2026 contre le Mosquitto local, 4/4.

---

## ✅ Partie 2 : Blockchain locale — FAITE

- [x] `Block.java` (index, timestamp, nodeId, liters, quotaRemaining, previousHash, hash, signature) :
  - [x] **immuable** : champs `private final`, aucun setter
  - [x] compatible Gson (testé : aller-retour JSON, signature conservée, bloc désérialisé accepté par un autre nœud)
  - [x] `package com.smartwater.blockchain` ; `toString()` tolère un bloc mal formé reçu du réseau
- [x] `HashValidator.java` : SHA-256 sur `index|timestamp|nodeId|liters|quotaRemaining|previousHash` (hash et signature exclus).
- [x] `Ledger` (interface du contrat) + `RejectedBlock` (record `block`, `reason`, `time`).
- [x] `Blockchain.java` implémentant `Ledger` :
  - [x] genesis fixe identique sur les 3 nœuds (signature `null`)
  - [x] `addBlock(nodeId, liters, quotaRemaining)` (création + signature + validation de la Partie 3)
  - [x] `validate()` (V1 à V4 sur toute la chaîne, genesis compris)
  - [x] `getChain()` en lecture seule (`Collections.unmodifiableList`)
  - [x] méthodes `synchronized` (accès concurrent : thread MQTT + thread du nœud)
- [x] Tests unitaires (`BlockchainTest`, 24 tests) : chaîne valide, genesis correct (hash `f365eab3...`), falsification détectée.

**Test de validation :** ✅ chaîne de 5 blocs valide ; falsification du bloc 2 détectée avec le bon index (2), y compris quand le tricheur recalcule le hash (la signature l'arrête).

---

## ✅ Partie 3 : Sécurité (semaines 2 et 3) — FAITE *(restent : prévenir Binôme 2, décision TLS, scénario complet en Partie 6)*

**Registre (semaine 2) — FAIT**
- [x] `Config.java` (racine `com.smartwater`) : `BROKER_URL`, `RESERVOIR_INITIAL = 1000`, `QUOTA_INITIAL = 200`, `KEYS_DIR = "keys"`, `NODE_IDS`, `initialQuotas()`, `mqttPassword(clientId)` (variable d'environnement `SMARTWATER_MQTT_PASSWORD_<ID>`, sinon fichier local non commité `config/mqtt-passwords.properties`).
- [ ] **Prévenir Binôme 2** : il utilise `Config` pour les quotas, le réservoir et la connexion du dashboard (après le `git push`).
- [x] `security/KeyGenerator.java` :
  - [x] `main()` : génère une paire ECDSA `secp256r1` pour A, B et C → `keys/{id}.pub` (X.509, Base64) et `keys/{id}.key` (PKCS#8, Base64). **N'écrase jamais** des clés existantes (sauf `--force`) et signale une paire incomplète.
  - [x] `loadPublicKey(nodeId)` et `loadPrivateKey(nodeId)` (+ variantes avec dossier, `loadAllPublicKeys`) ; refuse tout identifiant hors A/B/C.
  - [x] `KeyGenerator` lancé sur ta machine : `keys/{A,B,C}.pub` et `.key` existent. Les `.key` sont ignorés par Git ; les `.pub` ne sont **pas** commités pour l'instant : à décider avec Binôme 2 (commités, ou générés par chacun — tous les nœuds d'un même essai tournent sur la même machine, donc chacun peut avoir les siennes).
- [x] `security/BlockSigner.java` : `sign(hash, PrivateKey)` et `verify(Block, PublicKey)` avec `SHA256withECDSA`, signature en Base64 ; `verify` renvoie `false` (sans exception) pour une signature absente ou mal formée.
- [x] `blockchain/InvalidBlockException.java` : message = règle + raison (ex. `"V6 : quota dépassé pour C : 250 L demandés, 200 L restants"`) ; `getRule()` donne `"V6"`.
- [x] Dans `Blockchain` :
  - [x] `Blockchain.forNode(nodeId)` : charge la clé privée du nœud et les 3 clés publiques, initialise quotas et réservoir depuis `Config`
  - [x] `addBlock` : crée, **signe**, **valide (V1-V7)**, puis ajoute ; sinon lève `InvalidBlockException` sans modifier la chaîne (le bloc est toujours signé avec la clé du nœud local : demander un bloc au nom d'un autre nœud est refusé par V4)
  - [x] `acceptBlock(Block b)` : même validation pour un bloc reçu
  - [x] suivi du quota de chaque nœud et du réservoir restant, **recalculés à partir de la chaîne**
  - [x] `getRejected()` : liste des blocs refusés avec la raison (pour le dashboard), plafonnée à 1000 entrées
  - [x] `Blockchain.observer()` : copie en lecture seule pour le dashboard (clés publiques seulement, ne crée jamais de bloc)
- [x] Un test JUnit par tentative de triche :
  - [x] quota dépassé (V6) → refusé
  - [x] quota restant falsifié (V6) → refusé
  - [x] bloc au nom d'un autre nœud, signé avec la mauvaise clé (V4) → refusé
  - [x] bloc modifié en transit (V1) → refusé
  - [x] bloc rejoué (V2) → refusé
  - [x] `liters` supérieur au réservoir (V7) → refusé *(avec les valeurs du contrat, le quota de 200 L bloque toujours avant le réservoir de 1000 L : V7 est testé avec un réservoir réduit)*
  - [x] nœud inconnu (V3) et `liters ≤ 0` (V5) → refusés
- [x] Tests de `KeyGenerator`, `Config`, `forNode` et `observer` (`KeyGeneratorTest`, `ConfigTest`) ; démo console `SecurityDemo`.

**Broker (semaine 3) — FAIT**
- [x] `mosquitto/mosquitto.conf` : `listener 1883 localhost`, `allow_anonymous false`, `password_file`, `acl_file` (à lancer depuis la racine du projet).
- [x] `mosquitto/passwd` : comptes `A`, `B`, `C`, `DASHBOARD` créés avec `mosquitto_passwd` par `mosquitto/setup.ps1` (mots de passe aléatoires dans `config/mqtt-passwords.properties`). **`passwd` et le `.properties` ne sont pas commités** : chaque binôme lance le script chez lui.
- [x] `mosquitto/acl` : règles par **nom d'utilisateur** (authentifié par mot de passe) : chaque nœud `topic write water/from/{son id}` et `topic read water/from/+` ; `DASHBOARD` lecture seule. Identiques au contrat v3.1 (on n'utilise pas `pattern … %c` : l'identifiant client MQTT est librement choisi par le client).
- [x] **v3.1 — anti-usurpation de `from`** : un nœud ne peut plus écrire sous le nom d'un autre (ACL) et le récepteur jette tout message dont `from` ≠ topic. Décidé avant la Partie 4 : Binôme 2 n'avait encore rien codé, donc aucune reprise.
- [x] `NodeMqttClient` : connexion avec `username = nodeId`, `password = Config.mqttPassword(nodeId)` ; si aucun mot de passe n'est configuré, connexion anonyme (pour le broker non sécurisé) ; message clair en cas de refus d'authentification, sans nouvelle tentative inutile.
- [ ] **Prévenir Binôme 2** : le dashboard doit se connecter avec le compte `DASHBOARD` et s'abonner à `water/from/+` (après le `git push`).
- [x] Tests (`BrokerSecurityTest`, 12 tests, broker lancé par le test lui-même sur un port libre avec l'ACL du projet) : connexion anonyme refusée ; mauvais mot de passe refusé ; `DASHBOARD` ne peut rien publier (ni sous son nom ni sous celui d'un nœud) mais peut lire ; **C ne peut pas écrire sous le nom de A ou de B** ; chaque nœud lit tous les topics d'expéditeur ; **un message avec `from` ≠ topic est jeté et compté** ; un message écrit sur le topic d'un autre n'arrive jamais ; un message destiné à B n'est pas livré à A ; pile complète (REQUEST diffusé, REPLY ciblé) à travers le broker sécurisé. *Vérifié en affaiblissant l'ACL, puis en désactivant le contrôle du récepteur : les tests échouent bien.*
- [ ] Décision **TLS** (point ouvert, voir plus bas).

**Test de validation :** ✅ les 8 tentatives de triche sont refusées avec la bonne raison ; ✅ la communication fonctionne à travers le broker authentifié ; ⬜ le scénario d'irrigation complet avec ce broker se fera en Partie 6.

---

## ✅ Partie 4 : Réplication du registre — FAITE

- [x] `LedgerReplicator.java` : `broadcast(bloc)` publie `LEDGER_UPDATE` (bloc + signature) ; refuse de diffuser un bloc absent du registre local (seul un `addBlock` **réussi** est diffusé).
- [x] Réception `LEDGER_UPDATE` : `acceptBlock(b)` → ajout si les 7 règles passent, sinon **refus + divergence signalée avec la raison** (`getDivergences()` : nœud, index, raison, heure ; le bloc refusé apparaît aussi dans `getRejected()`).
- [x] 4 cas à la réception : doublon (même hash) ignoré sans alerte ; **fourche** (autre hash au même index) signalée, rien n'est remplacé ; bloc suivant → 7 règles ; **trou** → le nœud est en retard et demande un rattrapage.
- [x] Comparaison des copies (longueur + hash du dernier bloc) : `getPeerHeads()`, `isInSyncWith(peer)`, mis à jour par `LEDGER_UPDATE`, `LEDGER_REQUEST` et `LEDGER_SYNC`.
- [x] **Rattrapage** (décidé : ajouté, contrat v3.2) : nouveaux types `LEDGER_REQUEST` / `LEDGER_SYNC`. `requestSync()` au démarrage, et automatiquement sur un trou (au plus une demande identique toutes les 2 s). Chaque nœud répond **toujours** (son état + les blocs manquants) ; les blocs reçus sont ajoutés **un par un, revalidés par les 7 règles**. Un nœud qui reçoit une demande d'un nœud plus long se rattrape à son tour. Pas de vote à la majorité : une fourche est signalée, pas départagée.
- [x] Mode **passif** pour le dashboard (`sender = null`) : suit le registre sans rien envoyer, y compris grâce aux `LEDGER_SYNC` destinés aux autres.
- [x] Tests : `LedgerReplicatorTest` (15 tests, réseau simulé en mémoire avec coupure de nœud) et `ReplicationOverMqttTest` (2 tests à travers le vrai broker). *Vérifié en désactivant le rattrapage : le test correspondant échoue bien.*
- [ ] Rapport : noter que ce n'est **pas un consensus** (fourche signalée, jamais départagée).
- [ ] Branchement dans la séquence d'irrigation : en Partie 6 (`handler.register(replicator, LedgerReplicator.TYPES)`, `broadcast` après `IRRIGATION_EVENT`, `requestSync()` au démarrage du nœud).

**Test de validation :** ✅ 3 chaînes identiques après plusieurs ajouts ; ✅ divergence détectée et **rattrapée** après coupure d'un nœud ; ✅ un faux `LEDGER_UPDATE` (signature invalide) est refusé par les deux autres nœuds.

---

## ✅ Partie 5 : Chandy-Lamport — FAITE

- [x] `SnapshotService.java` complet (`startSnapshot`, `onMessage`, `isComplete`, `getResult`) ; implémenté par `algorithms/ChandyLamport.java` :
  - [x] `startSnapshot()` (initiateur) : passe par le thread du `MessageHandler` (`handler::execute`) pour ne jamais enregistrer l'état au milieu d'un message
  - [x] 1er `MARKER` reçu : état local enregistré + `MARKER` diffusé **d'un seul bloc** (aucun envoi ne s'intercale) ; canal d'où vient le `MARKER` = vide ; les autres canaux sont enregistrés
  - [x] `onMessage(m)` : messages reçus sur un canal en cours d'enregistrement = en transit (appelé par `MessageHandler` avant le module)
  - [x] arrêt d'enregistrement sur le `MARKER` suivant de chaque canal
  - [x] fin locale quand un `MARKER` de chaque autre nœud est arrivé → diffusion de l'instantané local (**nouveau type `SNAPSHOT_STATE`**, contrat v3.3)
  - [x] `isComplete()` : instantanés de A, B et C connus ; `getResult()` → `GlobalSnapshot`
  - [x] un seul snapshot à la fois (`A-1`, `A-2`…) ; `SNAPSHOT_STATE` au nom d'un autre nœud ignoré
- [x] `GlobalSnapshot` / `LocalSnapshot` (format **proposé**, à valider par Binôme 2) : état local, résumé du registre (longueur + dernier hash), messages en transit par canal ; `messagesInTransit()`, `sameLedgerEverywhere()`.
- [x] Lit le registre lui-même (`Ledger.getChain()`).
- [x] **Débloqué sans attendre Binôme 2** : `node/LocalStateProvider` + `node/LocalState` créés comme **proposition** (humidité, quota restant, état de la pompe, demandes différées) ; Binôme 2 l'implémente dans `FarmerNode` et peut ajouter des champs.
- [x] Mode observateur pour le dashboard (`ChandyLamport.observer()`) : assemble l'état global à partir des `SNAPSHOT_STATE`, ne peut pas déclencher (lecture seule).
- [x] Tests : `ChandyLamportTest` (10 tests, réseau simulé canal par canal, avec un total de jetons qui doit être conservé : états + messages en transit = total réel) et `SnapshotOverMqttTest` (à travers le vrai broker). *Vérifié en désactivant l'enregistrement des canaux : 3 tests échouent (290 jetons au lieu de 300).*
- [ ] Rapport : hypothèses (canaux FIFO grâce au topic par expéditeur, pas de panne, un snapshot à la fois).
- [ ] Branchement dans `FarmerNode` (Partie 6) : `handler.setSnapshotService(cl)` et un déclencheur sur un nœud (commande console ou minuterie).

**Test de validation :** ✅ snapshot déclenché pendant des échanges actifs → messages en transit capturés, aucun perdu ni compté deux fois.

---

## 🟡 Partie 6 : Intégration de la séquence d'irrigation — *côté Binôme 1 fait, attend Binôme 2*

- [x] `integration/IrrigationSequence` : séquence imposée (contrat section 8) : `requestPump()` → vérification du registre → allocation (B2) avec quota et réservoir **recalculés depuis la chaîne** → `addBlock` si attribué > 0 → compteurs (B2, seulement si accepté) → `IRRIGATION_EVENT` → `LEDGER_UPDATE` → `releasePump()`.
- [x] Chemin d'erreur : `InvalidBlockException` → aucun compteur modifié, aucun événement, `releasePump()` quand même ; attribué = 0 → pas de bloc ; exception dans l'allocation → `releasePump()` quand même (`finally`).
- [x] Vérifier la chaîne à jour avant de créer un bloc : si un autre nœud est connu plus long → rattrapage ; s'il échoue → pas de bloc (évite une fourche).
- [x] `integration/NodeRuntime` : assemble et branche tout ce que Binôme 1 fournit (horloge, MQTT, handler, registre avec clés, réplicateur, Chandy-Lamport) ; `start()` = connexion + rattrapage. `FarmerNode` (B2) n'a plus qu'à y brancher Ricart-Agrawala.
- [x] `irrigation/PumpAccess` créé (interface du contrat, à implémenter par `RicartAgrawala`).
- [x] Tests : `IrrigationSequenceTest` (6 tests : ordre exact des étapes, chemins d'erreur) et `IrrigationOverMqttTest` (3 tests à travers le broker : 3 demandes simultanées → 3 chaînes identiques et quotas corrects ; bloc refusé (V6) qui ne bloque pas la pompe ; snapshot après irrigations). *La pompe y est simulée par un verrou partagé, en attendant Ricart-Agrawala.*
- [ ] **Scénario complet en console avec le vrai Ricart-Agrawala** : dès que Binôme 2 livre `LamportClock`, `RicartAgrawala`, `WaterAllocator` et `FarmerNode`.
- [ ] Refaire ce scénario avec le broker authentifié (`mosquitto -c mosquitto/mosquitto.conf`).

**Test de validation :** ✅ (pompe simulée) scénario sans blocage, 3 chaînes identiques, quotas corrects ; un bloc refusé ne bloque pas la pompe. ⬜ À refaire avec le vrai Ricart-Agrawala.

---

## ⬜ Partie 7 : Finalisation (semaine 4)

- [ ] Vérifier avec Binôme 2 que le dashboard affiche bien tes données (registre, signatures, blocs refusés, snapshot).
- [ ] Tests de bout en bout (messages, intégrité, divergence, demandes simultanées, snapshot, tentatives de triche).
- [ ] Rapport : sections MQTT, blockchain, Chandy-Lamport + hypothèses (FIFO, pas de panne) + limite consensus.
- [ ] Rapport, section **Sécurité** : signatures, 7 règles de validation, comptes et ACL du broker, **modèle de menace** (section 10 du contrat) et ce qui n'est pas couvert.
- [ ] Démo : falsification volontaire d'un bloc + tentatives de triche refusées en direct (quota dépassé, bloc au nom d'un autre agriculteur). *(`SecurityDemo` est déjà prête pour la partie registre.)*

---

## Points ouverts à trancher avec Binôme 2 (te concernent)

- [ ] Rôle exact de `RELEASE`.
- [x] ~~Rattrapage registre~~ → **décidé (v3.2) : ajouté** avec `LEDGER_REQUEST` / `LEDGER_SYNC` (voir Partie 4).
- [x] ~~Ordre entre topics~~ → **décidé (v3.1)** : un topic par expéditeur `water/from/{id}`. Les messages d'un même expéditeur arrivent dans l'ordre (hypothèse FIFO de Chandy-Lamport) et le champ `from` ne peut plus être usurpé. *Contrepartie : un nœud peut lire les messages destinés à un autre ; pas de confidentialité (contrat, section 10).*
- [ ] TLS sur le broker : activé ou hors périmètre — **décision t'appartenant** (sécurité).
- [x] Format `GlobalSnapshot` — **proposé (v3.3)**, en attente de validation par Binôme 2 ; proposition de `LocalState` aussi.

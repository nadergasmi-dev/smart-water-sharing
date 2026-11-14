# Smart Water Sharing — Suivi des tâches (Binôme 2)

> Basé sur `CONTRAT.md` **v3.4** et le plan complet (v3 : sécurisation du registre et du broker ; v3.1 : un topic MQTT par expéditeur ; v3.2 : rattrapage du registre).

**Tes parties (une partie = un binôme) :** Horloge de Lamport · Ricart-Agrawala · Simulation des agriculteurs · Allocation et pompe · **Dashboard JavaFX complet** (tous les panneaux, y compris registre et snapshot).
**Changement v3 :** la sécurité est entièrement chez Binôme 1. Toi, tu gères le cas d'un bloc refusé dans l'allocation et tu affiches tout dans le dashboard, à partir des données que Binôme 1 fournit (`Ledger`, `Blockchain.observer()`, `SnapshotService.getResult()`).
**Changement v3.1 :** les topics MQTT changent (`water/from/{id}`, un par expéditeur). **Aucune modification pour tes modules** : tu continues d'utiliser `MessageSender.publish` et `MessageListener.onMessage`, Binôme 1 gère les topics. Seul le **dashboard** est concerné : il s'abonne à `water/from/+` (voir Partie 7).
**Changement v3.2 :** deux nouveaux types de messages (`LEDGER_REQUEST`, `LEDGER_SYNC`) pour le rattrapage du registre, gérés entièrement par Binôme 1. Pour le dashboard, Binôme 1 fournit un `LedgerReplicator` **passif** qui tient la copie du registre à jour (voir Partie 7).

---

## ✅ Partie 0 : Préparation — FAITE (commune)

- [x] Java (17/21), Maven, IDE, Git installés.
- [x] Mosquitto installé et vérifié.
- [x] Dépôt Git partagé créé avec Binôme 1.
- [x] Projet Maven créé (Paho, Gson, JUnit 5).
- [x] Arborescence des packages créée.
- [x] `CONTRAT.md` rédigé et validé par les deux binômes.
- [ ] **v3.2 :** relire et valider `CONTRAT.md` v3.2 avec Binôme 1 (nouveautés : topics `water/from/{id}`, sections 2 et 2.1 ; rattrapage du registre et `LedgerReplicator`, sections 4.2 et 7).

---

## ✅ Partie 1 : Horloge de Lamport — FAITE (14/11/2026)

*À livrer en premier : Binôme 1 en dépend pour `NodeMqttClient`.*

- [x] `LamportClock.java` : compteur `int` initialisé à 0, thread-safe (méthodes `synchronized`). Remplace le mock de Binôme 1, même signature (contrat, section 7).
- [x] `tick()` : incrémente et retourne la valeur.
- [x] `update(int received)` : `max(local, reçu) + 1`. Ne lève jamais d'exception (appelée par `MessageHandler` hors `try`) : une estampille mal formée ne fait qu'incrémenter.
- [x] `get()`.
- [x] `compare(int tsA, String idA, int tsB, String idB)` : plus petit `lamport` gagne, `from` départage l'égalité (`A < B < C`) ; identifiant `null` refusé.
- [x] Tests unitaires (`LamportClockTest`, 15 tests) : incrément simple, update avec valeur plus grande / plus petite / égale / mal formée, causalité sur une chaîne A → B → C, ordre total (antisymétrie, transitivité), égalité départagée par ID, **concurrence** (8 threads × 10 000 `tick()` : aucune estampille perdue ni dupliquée). *Vérifié en retirant `synchronized` : le test de concurrence échoue bien (50 030 au lieu de 80 000).*

**🔴 Prévenir Binôme 1 dès que c'est terminé — il est bloqué sans ça.** → à faire après le `git push`.

**Test de validation :** ✅ `mvn test` : 114 tests, 0 échec (les 99 de Binôme 1 + 15). ⬜ Logs de A, B, C : à revoir avec `FarmerNode` (Partie 2).

---

## ✅ Partie 2 : Simulation des agriculteurs — FAITE (15/11/2026)

- [x] `node/FarmerNode.java` : nœud indépendant par agriculteur (A, B, C), `implements LocalStateProvider` ; assemble `NodeRuntime` (Binôme 1), `RicartAgrawala`, le sol, l'état et la vue du réservoir. Boucle automatique (`startLoop(périodeMs)` / `stopLoop()` sans interruption au milieu d'une section critique), `step()` pour un cycle, `main` console (`FarmerNode A [périodeMs]`, commandes `s` snapshot, `e` état, `q` quitter).
- [x] `node/SoilSimulator.java` : humidité 0–100 %, baisse de 3 ± 1,5 points par pas (aléa à graine fixe, reproductible), +1 point par litre ; `litersToReach(cible)` = besoin en eau.
- [x] `node/FarmerState.java` : quota initial `Config.QUOTA_INITIAL` (200 L), **seuil 30 %**, **cible 60 %** (proposition pour le point ouvert « seuil d'humidité »), compteurs (demandes, irrigations, litres reçus, refus). Le quota est réaligné sur la chaîne (il ne peut que baisser).
- [x] `REQUEST` seulement si humidité < seuil **et** quota > 0 (contrat, section 9) ; sinon aucun message (log une seule fois « quota épuisé »).

**Test de validation :** ✅ `SoilSimulatorTest` (7), `FarmerStateTest` (6), `FarmerNodeOverMqttTest` (3, vrai broker) : demande exactement au pas où l'humidité passe **sous** 30 % (pas au seuil exact), besoin 35 L ; quota épuisé → plus de `REQUEST` ni de bloc ; 3 agriculteurs en boucle jusqu'à épuisement des quotas (600 L distribués). *Vérifié en retirant la condition de quota : 3 tests échouent.*

---

## ✅ Partie 3 : Ricart-Agrawala — FAITE (14/11/2026)

- [x] `algorithms/RicartAgrawala.java` implémentant `PumpAccess` (constructeur du contrat : `new RicartAgrawala(id, rt.clock(), rt.sender())`) :
  - [x] `requestPump()` : diffuse `REQUEST` (via `MessageSender` de Binôme 1), bloque jusqu'à entrée en section critique. L'estampille de la demande est la valeur renvoyée par `publish()`.
  - [x] état `RELEASED` → répondre tout de suite à un `REQUEST` reçu.
  - [x] état `HELD` → différer la réponse.
  - [x] état `WANTED` → comparer priorités via `LamportClock.compare()` ; différer si ma demande est prioritaire, sinon répondre.
  - [x] entrée en section critique après réception d'un `REPLY` de **chaque** autre nœud (2 sur 3).
  - [x] `releasePump()` : envoie `RELEASE` **puis** les `REPLY` différés (dans l'ordre d'arrivée).
  - [x] Pas de réentrance : `requestPump()` en `WANTED`/`HELD` ou `releasePump()` sans la pompe → `IllegalStateException`.
  - [x] Interruption pendant l'attente : demande abandonnée proprement (REPLY différés envoyés, retour à `RELEASED`) ; le `REPLY` tardif de l'ancienne demande est ignoré pour ne pas compter à tort pour la suivante.
- [x] Implémente `MessageListener` (`REQUEST`, `REPLY`, `RELEASE`) : `rt.handler().register(ra, RicartAgrawala.TYPES)`. Messages d'un nœud inconnu ou `REPLY` inattendu : ignorés et loggés.
- [x] **Ordre des verrous** : `synchronized (sender)` puis `synchronized (this)` pour toute action qui change l'état ET envoie un message. Indispensable car `ChandyLamport` prend le verrou du client MQTT puis lit l'état de la pompe : sinon, interblocage possible. Bonus : un snapshot ne s'intercale jamais entre un changement d'état et son message.
- [x] `view()` : lecture atomique de l'état (état, estampille, REPLY différés, REPLY attendus) pour `LocalState` et le dashboard.
- [x] Tests (11) :
  - [x] `RicartAgrawalaTest` (9, sans broker) : règles de décision message par message (RELEASED / HELD / WANTED, égalité départagée par l'ID), ordre RELEASE puis REPLY, réentrance, messages inattendus, abandon sur interruption ; **réseau simulé** 3 nœuds avec délais aléatoires : 3 × 30 demandes concurrentes, jamais 2 nœuds en section critique ; ordre d'accès B avant C selon `(lamport, from)`. *Vérifié en désactivant le report des réponses : 5 tests échouent (3 nœuds en section critique en même temps).*
  - [x] `RicartAgrawalaOverMqttTest` (2, vrai broker) : branché dans `IrrigationSequence` de Binôme 1 **à la place de la pompe simulée** : 3 nœuds × 3 irrigations simultanées → pompe exclusive, 3 registres identiques, aucune divergence ni bloc refusé, quotas 170 L, réservoir 910 L. Et **40 snapshots Chandy-Lamport pendant les irrigations** : aucun interblocage, au plus un nœud en `HELD` dans chaque état global (cohérence de la coupure).

**Test de validation :** ✅ demandes simultanées de A, B, C → accès exclusif respectant l'ordre `(lamport, from)` ; `mvn test` : 125 tests, 0 échec (stable sur 3 exécutions).

**À noter pour le rapport :** la pompe simulée de Binôme 1 attendait que tous les nœuds aient reçu le bloc avant de libérer ; avec Ricart-Agrawala ce n'est plus nécessaire : l'ordre FIFO du topic `water/from/{id}` (`LEDGER_UPDATE` envoyé avant le `REPLY`) suffit, et le test le confirme.

---

## ✅ Partie 4 : Allocation et pompe — FAITE (15/11/2026)

- [x] `irrigation/WaterAllocator.java` : `attribué = min(besoin, quotaRestant, réservoirDisponible)` (jamais négatif), passé à `IrrigationSequence` via `WaterAllocator.forNeed(besoin)` : calculé **dans la section critique**, avec quota et réservoir recalculés depuis la chaîne.
- [x] `irrigation/PumpResource.java` : vue locale du réservoir (1000 L), utilisation de la pompe par le nœud ; mis à jour après un bloc accepté ou à la réception d'un `IRRIGATION_EVENT` d'un autre nœud. Le niveau retenu est le plus bas annoncé (le réservoir ne remonte jamais, les événements de deux expéditeurs peuvent arriver dans le désordre).
- [x] Compteurs (quota, réservoir, humidité) mis à jour **seulement dans `onIrrigated`**, donc après le succès de `addBlock`, sous le verrou du client MQTT (un snapshot ne voit jamais une irrigation à moitié comptée).
- [x] `attribué == 0` → `NOTHING_ALLOCATED`, pas de bloc, pompe libérée (géré par `IrrigationSequence`).
- [x] `InvalidBlockException` → `REFUSED`, seul le compteur de refus change, pompe libérée.

**Test de validation :** ✅ `WaterAllocatorAndPumpTest` (5) ; en réseau : la 4e allocation de A est limitée à 20 L par le quota restant ; compteurs locaux = valeurs recalculées depuis la chaîne sur les 3 nœuds ; aucun bloc refusé. *Vérifié en retirant le quota du calcul : 5 tests échouent, les autres nœuds refusent le bloc (règle V6).* `mvn test` : **146 tests, 0 échec** (stable sur 2 exécutions).

---

## 🟡 Partie 5 : Interfaces fournies à Binôme 1 — en grande partie faite

- [x] `LocalStateProvider` implémenté dans `FarmerNode` : `getLocalState()` → humidité, quota, état de la pompe et REPLY différés (`ra.view()`, lecture atomique).
- [x] `LocalState(nodeId, humidity, quotaRemaining, pumpState, deferredReplies)` **validé tel quel** (aucun champ ajouté).
- [x] Règle `synchronized (mqttClient)` respectée : Ricart-Agrawala (changement d'état + message) et mise à jour des compteurs après irrigation.
- [x] Format `GlobalSnapshot` / `LocalSnapshot` : utilisé dans les tests (état de chaque nœud, longueur du registre) ; à revoir en l'affichant dans le dashboard.
- [ ] **À proposer à Binôme 1 :** dans `IrrigationSequence`, `addBlock` puis `onIrrigated` ne sont pas sous le même verrou que l'enregistrement du snapshot. Un snapshot peut donc tomber entre les deux : registre avec le nouveau bloc, quota local pas encore mis à jour (écart d'une irrigation au plus ; le test le tolère). Correction simple côté Binôme 1 : faire `addBlock` + `listener.onIrrigated` dans `synchronized (sender)`.

---

## 🟡 Partie 6 : Intégration de la séquence d'irrigation (semaine 3, avec Binôme 1)

*La séquence est déjà codée par Binôme 1 (`IrrigationSequence`, contrat v3.4) : tu fournis seulement la pompe, l'allocation et tes compteurs.*
- [x] Dans `FarmerNode` : `NodeRuntime rt = new NodeRuntime(id, this)` (tout ce que Binôme 1 fournit, déjà branché), puis brancher `RicartAgrawala` : `rt.handler().register(ra, REQUEST, REPLY, RELEASE)` et `rt.start()`.
- [x] `RicartAgrawala implements PumpAccess` (interface déjà dans `irrigation/`).
- [x] Irriguer avec `rt.irrigationSequence(ra).irrigate(allocation, listener)` :
  - [x] `allocation` : `(quota, reservoir) -> min(besoin, quota, reservoir)` (`WaterAllocator`) ; quota et réservoir sont recalculés depuis la chaîne
  - [x] `listener.onIrrigated(bloc)` : mettre à jour tes compteurs (quota, `PumpResource`) ; `onRefused(raison)` : log ; `onNothingAllocated()` : rien
- [ ] Vérifier que le scénario fonctionne quand le broker authentifié est activé.
- [ ] Scénario complet en console avec Binôme 1 (3 demandes quasi simultanées, avec le vrai Ricart-Agrawala).

**Test de validation :** scénario sans blocage, quotas corrects, accès pompe respecté, y compris avec le broker authentifié.

---

## ⬜ Partie 7 : Dashboard JavaFX complet (semaines 3-4) — *toute la partie est à toi en v3*

- [ ] Fenêtre principale JavaFX.
- [ ] Connexion MQTT avec le compte `DASHBOARD` (lecture seule, mot de passe via `Config.mqttPassword("DASHBOARD")`) :
  - [ ] **v3.1 :** s'abonner à `water/from/+` (au lieu de `water/all`) : le dashboard reçoit ainsi tous les messages (`REQUEST`, `REPLY`, `RELEASE`, `MARKER`, `IRRIGATION_EVENT`, `LEDGER_UPDATE`, `LEDGER_REQUEST`, `LEDGER_SYNC`). L'expéditeur est le dernier segment du topic.
  - [ ] Lancer `mosquitto/setup.ps1` chez toi pour créer les comptes et les mots de passe (non commités).
- [ ] Panneau agriculteurs (humidité, demandes).
- [ ] Panneau quotas.
- [ ] Panneau réservoir.
- [ ] Panneau pompe (état, utilisation).
- [ ] **v3 — panneau registre** (repris de Binôme 1) :
  - [ ] copie du registre via `Blockchain.observer()`, tenue à jour par un réplicateur passif fourni par Binôme 1 : `new LedgerReplicator("DASHBOARD", observer, null)`, auquel on passe les messages `LEDGER_UPDATE` / `LEDGER_REQUEST` / `LEDGER_SYNC` lus sur `water/from/+` (`replicator.onMessage(m)`)
  - [ ] liste des blocs (index, agriculteur, litres, quota, hash)
  - [ ] validité de la chaîne (`validate()`) et état de réplication : `replicator.getPeerHeads()` (longueur + dernier hash de chaque nœud), `getDivergences()` (blocs refusés, fourches, avec la raison)
  - [ ] statut de la signature de chaque bloc (✓ / ✗)
  - [ ] blocs refusés avec la raison (`getRejected()`, règles V1 à V7)
- [ ] **v3 — panneau snapshot** (repris de Binôme 1) : affichage de `GlobalSnapshot` avec `ChandyLamport.observer()`, alimenté par les messages `SNAPSHOT_STATE` lus sur `water/from/+` (`getResult()` : état de chaque nœud, messages en transit, registres identiques ou non). **Le déclenchement se fait depuis un nœud** (le dashboard ne peut rien publier) : prévoir un déclencheur dans `FarmerNode` (commande console ou minuterie) qui appelle `startSnapshot()`.

**⚠️ Dépend de :** `Blockchain.observer()`, `getRejected()` et `SnapshotService` (Binôme 1, semaine 3).

---

## ⬜ Partie 8 : Finalisation (semaine 4)

- [ ] Tests de bout en bout (demandes simultanées, communications, affichage des tentatives de triche).
- [ ] Rapport : sections Lamport, Ricart-Agrawala, allocation, dashboard + hypothèse "aucune panne de nœud" (limite connue de Ricart-Agrawala).
- [ ] Participer à la démo (le dashboard montre les blocs refusés en direct).

---

## Points ouverts à trancher avec Binôme 1 (te concernent)

- [ ] Rôle exact de `RELEASE`.
- [x] ~~Valeurs initiales : réservoir, quota par agriculteur~~ → **fixées en v3 : réservoir 1000 L, quota 200 L** (la validation des blocs en dépend).
- [ ] Seuil d'humidité → **proposé : 30 %, cible 60 %** (`FarmerState`), à faire valider par Binôme 1.
- [x] ~~Contenu de `LocalState`~~ → **validé tel quel** par Binôme 2.
- [ ] Format de `GlobalSnapshot` — proposé par Binôme 1, **à toi de valider** (c'est toi qui l'affiches).

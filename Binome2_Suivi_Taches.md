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

## ⬜ Partie 2 : Simulation des agriculteurs (semaine 1)

- [ ] `FarmerNode.java` : nœud indépendant par agriculteur (A, B, C).
- [ ] `SoilSimulator.java` : simulation de l'humidité du sol (baisse dans le temps).
- [ ] `FarmerState.java` : quota initial par agriculteur (**`Config.QUOTA_INITIAL` = 200 L**, `Config` fourni par Binôme 1), seuil de déclenchement de la demande.
- [ ] Génération de `REQUEST` quand humidité insuffisante **et** quota > 0 (sinon, pas de `REQUEST` — règle section 9 du contrat).

**Test de validation :** 3 nœuds tournent indépendamment, humidité évolue, demandes générées au bon moment.

---

## ⬜ Partie 3 : Ricart-Agrawala (semaine 1-2)

- [ ] `RicartAgrawala.java` implémentant `PumpAccess` :
  - [ ] `requestPump()` : diffuse `REQUEST` (via `MessageSender` de Binôme 1), bloque jusqu'à entrée en section critique.
  - [ ] état `RELEASED` → répondre tout de suite à un `REQUEST` reçu.
  - [ ] état `HELD` → différer la réponse.
  - [ ] état `WANTED` → comparer priorités via `LamportClock.compare()` ; différer si ma demande est prioritaire, sinon répondre.
  - [ ] entrée en section critique après réception d'un `REPLY` de **chaque** autre nœud (2 sur 3).
  - [ ] `releasePump()` : envoie `RELEASE` **puis** les `REPLY` différés.
- [ ] Implémente `MessageListener` pour recevoir `REQUEST`/`REPLY`.
- [ ] Tests : 2-3 nœuds avec demandes concurrentes, vérifier l'ordre d'accès selon priorité.

**⚠️ Dépend de :** `NodeMqttClient` + `Message` fonctionnels (Binôme 1, prévu fin semaine 1).

**Test de validation :** demandes simultanées de A, B, C → accès exclusif respectant l'ordre `(lamport, from)`.

---

## ⬜ Partie 4 : Allocation et pompe (semaine 2)

- [ ] `WaterAllocator.java` : `attribué = min(besoin, quotaRestant, réservoirDisponible)`, calculé **dans la section critique**.
- [ ] `PumpResource.java` : représentation de la ressource partagée (réservoir initial **`Config.RESERVOIR_INITIAL` = 1000 L**, niveau).
- [ ] Mise à jour des compteurs (quota, réservoir) **seulement après le succès de `Ledger.addBlock`** (v3).
- [ ] Cas `attribué == 0` : pas d'irrigation, pas de bloc, mais `releasePump()` quand même appelé.
- [ ] **v3 :** cas `InvalidBlockException` levée par `addBlock` : log de la raison, compteurs **non** modifiés, pas d'irrigation, `releasePump()` quand même appelé.

**Test de validation :** allocation correcte selon besoin/quota/réservoir ; aucune double-attribution du même volume ; un bloc refusé ne modifie aucun compteur.

---

## ⬜ Partie 5 : Interfaces fournies à Binôme 1

- [ ] `LocalStateProvider` : **l'interface et le record `LocalState` existent déjà** (`node/`, proposés par Binôme 1 en v3.3 pour ne pas bloquer Chandy-Lamport). À toi de :
  - [ ] valider ou compléter `LocalState(nodeId, humidity, quotaRemaining, pumpState, deferredReplies)` (tu peux ajouter des champs)
  - [ ] l'implémenter dans `FarmerNode` : `getLocalState()` → copie cohérente (appelée sur le thread du `MessageHandler`, entre deux messages)
  - [ ] **règle importante** : quand une action modifie l'état ET envoie un message, faire les deux dans un bloc `synchronized (mqttClient)`, pour qu'un snapshot ne s'intercale jamais entre les deux
- [ ] Valider le format de `GlobalSnapshot` / `LocalSnapshot` proposé par Binôme 1 (contrat v3.3, section 7).

---

## ⬜ Partie 6 : Intégration de la séquence d'irrigation (semaine 3, avec Binôme 1)

*La séquence est déjà codée par Binôme 1 (`IrrigationSequence`, contrat v3.4) : tu fournis seulement la pompe, l'allocation et tes compteurs.*
- [ ] Dans `FarmerNode` : `NodeRuntime rt = new NodeRuntime(id, this)` (tout ce que Binôme 1 fournit, déjà branché), puis brancher `RicartAgrawala` : `rt.handler().register(ra, REQUEST, REPLY, RELEASE)` et `rt.start()`.
- [ ] `RicartAgrawala implements PumpAccess` (interface déjà dans `irrigation/`).
- [ ] Irriguer avec `rt.irrigationSequence(ra).irrigate(allocation, listener)` :
  - [ ] `allocation` : `(quota, reservoir) -> min(besoin, quota, reservoir)` (`WaterAllocator`) ; quota et réservoir sont recalculés depuis la chaîne
  - [ ] `listener.onIrrigated(bloc)` : mettre à jour tes compteurs (quota, `PumpResource`) ; `onRefused(raison)` : log ; `onNothingAllocated()` : rien
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
- [ ] Seuil d'humidité.
- [ ] Contenu de `LocalState` — **à toi de proposer**, Binôme 1 valide.
- [ ] Format de `GlobalSnapshot` — proposé par Binôme 1, **à toi de valider** (c'est toi qui l'affiches).

# Changelog

Toutes les modifications notables de QUASAR sont consignées ici.
Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/).

## [Unreleased]

### Phase 0 — Squelette + boucle d'I/O

- **Build** : projet `sbt` multi-modules (`core`, `io`, `analysis`, `cli`, `bench`),
  Scala 3.3.4 / JVM 21, scalafmt, sbt-assembly.
- **core/ir** : IR ANX (`AutomataNetwork`, `Automaton`, `Transition`, `LocalState`,
  `Context`, `Metadata`) avec `Distribution` phase-type (exponentielle par défaut, D4)
  et validateur de cohérence (`Validation`).
- **core/semiring** : `Semiring[T]` + instances Tropical (min,+), Viterbi (max,×),
  ProbAgg (+,×), booléen — avec property tests des lois.
- **io** : format canonique ANX (parse/render), importeur Pint `.an`, importeur
  MaBoSS `.bnd`/`.cfg` (logique booléenne → DNF → transitions), exporteurs ANX / `.an` / DOT.
- **cli** : `quasar model import|export|convert|validate|info|inspect`.
- **bench/models** : modèle de référence **p53-mdm2** (MaBoSS `.bnd`/`.cfg`).

### Phase 1 (partiel) — Cœur d'analyse

- **core/glc** : Graphe de Causalité Locale (objectifs/solutions), énumération de
  chemins locaux acycliques bornée.
- **analysis** : atteignabilité qualitative OA (point fixe monotone) / UA
  (commitment DFS avec témoin) ; analyse quantitative — borne inférieure sound de
  `P(R)` et délai au plus tôt `T(R)` (chap. 4).
- **cli** : `quasar analyze reachability|quantitative|probability|delay|scenario`,
  `quasar solver glc|qglc`.
- **Tests de justesse** : confrontation à un oracle exact (BFS sur l'espace d'états)
  — `UA ⇒ exact ⇒ OA` vérifié par property-based testing. UA renforcée en recherche
  exacte bornée sur le cône d'influence (`UA = exact` sur petits modèles).

### Phase 2 (partiel) — Surface CLI complète

- **core/solver** : SCC (Tarjan, pile explicite). **core/glc** : graphe d'interaction
  signé (formalisme de Thomas) + détection de circuits.
- **analysis** :
  - `Topology` — SCC, circuits/feedback signés, points fixes, attracteurs (SCC
    terminales, exact borné), trap-spaces (énumération bornée, minimaux).
  - `Intervention` — ensembles de coupe (hitting sets bornés) et mutations
    (gain/perte de fonction) via OA/UA.
  - `Transform` — réduction orientée-but (cône), slice, booléanisation.
  - `ModelStats` — métriques de graphe et diff structurel.
- **io** : exporteurs **NuSMV** (encodage asynchrone + `CTLSPEC EF`) et
  **PRISM/Storm** (CTMC + `P=? [F]`).
- **verify** : adaptateurs sous-processus **NuSMV / Storm / MaBoSS** avec détection
  de binaire et échec propre si absent.
- **cli** : groupes complétés `topology`, `transform`, `verify`, `repo`, `bench`,
  `tui` (jline, réutilise les commandes CLI) ; `analyze cutsets|mutations|compare` ;
  `model stats|diff|normalize`. Dépôt de modèles versionné (`RepoStore`).
- **Tests** : 75 tests (`munit` + `scalacheck`) sur les 5 modules.

### Validation MaBoSS + cellfate

- **io/MaBossOutput** : parseur de la table `probtraj` de MaBoSS (distribution
  d'états au temps final, probabilités marginales d'activation des nœuds).
- **verify/MaBossAdapter.probabilityOf** : lance MaBoSS, localise le `*_probtraj.csv`,
  extrait `P(node actif)` — oracle empirique pour confronter `binf P(R)`.
- **cli** : `verify maboss --goal` (confrontation `binf P(R) ≤ P_MaBoSS` + verdict
  de justesse) ; `bench validate` détecte MaBoSS automatiquement (repli propre si absent).
- **bench/models/cellfate** : modèle de décision de destin cellulaire (~11 nœuds,
  inspiré de Calzone et al. 2010) `.bnd`/`.cfg` — bascule survie/apoptose/nécrose.
- **Correctif `T(R)`** : le délai au plus tôt **s'accumule séquentiellement** le long
  des chaînes causales (auparavant `max`, sous-estimant grossièrement) — vérifié par test.
- **Tests** : 82 tests au total.

### Solveur CTMC des cycles (§6.5) — `P(R)` exacte

- **core/solver/CtmcSolver** : CTMC absorbante locale sur le cône d'influence du but.
  La probabilité d'atteignabilité éventuelle dans la CTMC globale égale celle de la
  chaîne de saut du cône (sous-système autonome ; les transitions hors-cône sont du
  bégaiement). Résolution du système `(I − P_TT) h = b` (matrice fondamentale) →
  **`P(R)` exacte** + temps moyen d'absorption, **sans énumérer les chemins** (cycles
  gérés nativement).
- **core/solver/LinearSolver** : élimination de Gauss à pivot partiel (sans dépendance).
- **core/glc/Cone** : calcul du cône d'influence factorisé.
- **analysis/Quantitative** : `P(R)` résolue exactement par la CTMC (repli sur la borne
  inférieure sound si le cône dépasse le plafond d'états) ; champ `meanTime` ajouté.
- **cli** : `analyze quantitative|probability` distinguent « exact, CTMC » et « borne
  inférieure » ; JSON `probExact`/`meanTime`.
- **Gains** : `P(Apoptosis=1)` passe de `1,9e-6` (borne lâche) à **`0,5` (exact)** ;
  `P(p53=1)` de `0,172` à **`1,0`**. Test cyclique : `P = 1/3` exact malgré la boucle.
- **Tests** : 88 au total, dont contrôle croisé `binf statique ≤ P_CTMC` et valeurs
  analytiques (course `λ/(λ+μ)`, cycle avec échappement).

### Montée en charge (§6.4, §6.7)

- **core/solver/CtmcSolver** : passage à un stockage **creux** + résolution
  **itérative (Gauss-Seidel)** — `I − P_TT` est une M-matrice non singulière pour
  les chaînes absorbantes, donc l'itération converge. Le plafond d'états exact
  passe de ~centaines (Gauss dense O(N³)) à **~10⁵** (`LinearSolver` dense retiré).
- **analysis/Scenarios** : recherche meilleur-d'abord sur la chaîne du cône →
  `k` meilleures trajectoires (plus probables / plus rapides) et **borne anytime**
  `P(R)` = somme des probabilités de trajectoires de premier passage disjointes
  (sound, monotone en `k`, convergeant vers `P(R)` — §6.7).
- **analysis/Quantitative** : si le cône dépasse le plafond, repli sur la borne
  **anytime** (bien plus fine que `Λ`) au lieu de la borne témoin.
- **cli** : `analyze scenario -k <N> --kind most-probable|fastest` ;
  `analyze quantitative --max-states <N>`.
- **Tests** : 91 au total (top-k ordonné, borne anytime monotone et `≤ P_CTMC`).

### Expansion phase-type (D4)

- **core/ir/Distribution.phaseRates** : taux des phases successives.
- **analysis/Transform.expandPhaseType** : remplace chaque transition `Erlang`/
  `PhaseType` (`k > 1` phases) par une chaîne d'**états fantômes** exponentiels
  `i → φ₁ → … → j` ; seule la 1ʳᵉ sous-transition porte la précondition (au taux de
  la 1ʳᵉ phase). Réseaux purement exponentiels renvoyés inchangés (coût nul).
- **analysis/Quantitative** : applique l'expansion avant la CTMC/scénarios →
  **compétition exacte**. Exemple : `g:0→1 Erlang(2,4)` vs `g:0→2 Exp(2)` donne
  `P(g=1) = 2/3` (et non `0,5` de l'approximation par taux moyen).
- **bench/models/phasetype-demo.anx** : modèle de démonstration.
- **Tests** : 95 au total (structure de l'expansion, compétition 2/3, espérance Erlang).

### Backend symbolique BDD (§6.4)

- **core/dd/Bdd** : ROBDD auto-contenu (table unique + cache `ite`, sans dépendance) —
  `ite/and/or/not/xor/iff`, `restrict`, quantification existentielle, `relabel`
  monotone, `satCount`, `nodeCount`.
- **analysis/Symbolic** : atteignabilité et points fixes **exacts** par BDD (réseaux
  booléens). Encodage entrelacé courant/suivant, relation de transition asynchrone,
  ensemble atteignable par plus petit point fixe d'image — **sans énumérer l'espace
  d'états**. Refus propre des multivalués.
- **cli** : `analyze reachability --symbolic` (atteignabilité exacte + nombre d'états
  atteignables + taille du BDD).
- **Démo** : cellfate — 71 états atteignables (sur 2¹¹) représentés en **26 nœuds BDD**.
- **Tests** : 105 au total, dont contrôle croisé property-based `symbolique = oracle
  explicite` (verdict + nombre d'états) et les lois du BDD.

### Projection bioLQM (D1) — import SBML-qual & catalogue

- **module `biolqm`** : dépendance `org.colomoto:bioLQM`, isolée des autres modules.
- **`BioLqm.fromLogicalModel`** (import, multivalué) : énumère les chemins du MDD de
  chaque fonction logique (`PathSearcher`) et reconstruit les transitions unitaires
  de Thomas (`v→v±1` selon la valeur cible) — débloque l'import **SBML-qual**,
  **BoolNet**, **GINML**, **booleannet**, etc.
- **`BioLqm.toLogicalModel`** (export, booléen) : DNF d'activation par composant →
  BoolNet → compilation MDD par bioLQM → sauvegarde (SBML-qual…).
- Formats enregistrés explicitement (le `ServiceLoader` Java échoue sous sbt).
- **cli** : `model import`/`export` routent les formats bioLQM ; `model biolqm`
  projette et signale les pertes (taux non logiques, multivalués non booléanisables).
  StatusLogger log4j2 (jSBML) silencé.
- **Tests** : 109 au total, dont round-trip ANX↔bioLQM (équivalence dynamique) et
  export SBML-qual.

### `P(R)` symbolique par MTBDD (§6.4)

- **core/dd/Mtbdd** : diagramme de décision multi-terminal auto-contenu (terminaux
  réels) — `apply` (+, ×, −, ÷, max) mémoïsé, restriction, somme d'abstraction,
  `relabel` monotone, évaluation, `maxAbsLeaf`.
- **analysis/SymbolicCtmc** : calcul **exact** de `P(R)` par **itération de valeur
  symbolique** — matrice de taux `T(x,x')`, taux de sortie `R(x)=∑T`, matrice de saut
  `P=T/R`, puis `h = but + (1−but)·(P·h)` jusqu'au point fixe ; produit
  matrice-vecteur = `apply(×)` + somme d'abstraction sur les variables suivantes.
  Sans énumération d'états (réseaux booléens à transitions exponentielles).
- **cli** : `analyze probability --symbolic` (P(R) exacte MTBDD, avec `--threshold`).
- **Validation** : contrôle croisé property-based `P(R) MTBDD = P(R) CTMC explicite`
  sur réseaux booléens aléatoires ; course booléenne = `1/3` exact.
- **Tests** : 117 au total.

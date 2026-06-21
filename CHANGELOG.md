# Changelog

Toutes les modifications notables de QUASAR sont consignées ici.
Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/).

## [Unreleased]

### Rapport de validation consolidé (fiche M2)

- **analysis/ValidationMetrics** (pur, testé) : `Bounds(binf, exact)` →
  `sound`/`tightness`/`relGap` ; `jaccard` (recouvrement de nœuds).
- **cli** : `bench validate <m> --goal a=j --json` émet un rapport consolidé
  `{soundness, tightness, relGap, binf, exact, tDelay, delayGap, scenarioOverlap,
  oracle}` réunissant H1 (justesse), H3 (finesse), H2 (délai vs quantile MaBoSS)
  et H4 (recouvrement scénario↔oracle). Champs oracle à `null` si MaBoSS absent.
  Référence exacte = MDD symbolique (ou CTMC si déjà exacte).
- **Tests** : métriques (exact/lâche/non-sûre/exact nul) et Jaccard.

### Balayage de scalabilité (fiche A2) — automatise la courbe H5

- **cli** : `bench sweep [model…] [--dir D] [--metric reachability|fixpoints|load]
  [--reps R] [--json]` mesure, par modèle, sa **taille** (#automates, |S|,
  transitions) et le **temps** d'une métrique *sans objectif* (états atteignables
  symboliques par défaut, ou points fixes, ou chargement). Résultats triés par
  taille. JSON `{metric, results:[{model, automata, …, value, timeMs}]}` —
  données directes de la courbe H5.
- **Tests** : sémantique des métriques (états atteignables indépendants de
  l'objectif ; nombre de points fixes absorbants).

### Ablation des stratégies P(R) (fiche A3) — débloque H6

- **cli** : `bench ablation <m> --goal a=j [--from] [--budget N] [--reps R] [--json]`
  exécute et **chronomètre** (min sur `reps`) les stratégies réellement
  implémentées — **CTMC exact** (matrice fondamentale), **MDD symbolique** (sans
  énumération), **CEGAR anytime** (encadrement `[lo,hi]`) — et rapporte temps +
  valeur + concordance (et #nœuds DD). JSON `{reference, strategies:[…]}`.
- Honnêteté : seules les stratégies disposant d'un chemin de code distinct sont
  ablatées ; `--semiring`/`--cycle-policy` restent intrinsèques (non basculables).
- **Tests** : invariant H6 — CTMC = MDD = (CEGAR encadre) sur une course à 1/2.

### Temps d'atteinte espéré Storm (fiche V2)

- **io/StormFormat** : le modèle PRISM inclut désormais une récompense `"time"`
  (1 par unité de temps) ; `parseResult` (pur, testé) extrait la valeur d'une
  sortie Storm (`Result …: X`, `None` sur `inf`).
- **verify/StormAdapter.expectedTime** : temps d'atteinte espéré exact via
  `R{"time"}=? [ F goal ]` ; `probability` factorisée sur le même `query`.
- **cli** : `verify storm <m> --goal … [--metric prob|expected-time] [--json]`
  (JSON `{tool, goal, metric, prob, expectedTime}`).
- **Tests** : présence de la récompense dans le PRISM, `parseResult`
  (valeur / `inf` / absent).

### Temps d'atteinte MaBoSS (fiche V1) — débloque H2/H4

- **io/MaBossOutput.Series** : parse la **série temporelle** complète du probtraj
  (toutes les lignes), avec dérivés purs — CDF marginale d'un nœud dans le temps,
  **premier passage**, **quantiles** de temps d'atteinte, et **temps d'activation
  par nœud** (support du recouvrement de trajectoire).
- **verify/MaBossAdapter.hittingTime** : lance MaBoSS (surcharge `sample_count` /
  `max_time` via un `-c` additionnel) et renvoie `MaBossHitting` (prob, CDF,
  quantiles, activations).
- **cli** : `verify maboss <m> --goal … [--samples N] [--max-time T] [--json]`
  (JSON `{prob, binf, sound, quantiles, hittingTimeCdf, nodeActivation}`) —
  débloque **H2** (délai vs quantile) et **H4** (recouvrement scénario↔oracle).
- **Tests** : parseSeries (grille, CDF), premier passage & quantiles, temps
  d'activation par nœud (sans binaire, sur fixture probtraj).

### Valuation des modèles qualitatifs (fiche P1)

- **analysis/Transform.assignRates** : assigne une distribution exponentielle à
  chaque transition selon une politique `RatePolicy` — `Unit` (taux 1.0) ou
  `Sample(lo, hi)` (log-uniforme dans `[lo, hi]`, **déterministe** pour une graine
  donnée). Taux strictement positifs (cohérents avec `Validation`) ; structure du
  réseau inchangée. Débloque la valuation des modèles importés sans cinétique
  (SBML-qual, BoolNet, GINML…).
- **cli** : `model assign-rates <m> [--policy unit|sample] [--seed N] [--min] [--max]
  [-o] [--json]` (JSON `{assigned, policy, seed, min, max}`).
- **Tests** : politique unit (toutes à `exp(1.0)`), sample dans l'intervalle et
  positives, déterminisme par graine, validation du modèle valué.

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

### Backend symbolique multivalué (MDD, §6.4)

- **core/dd/Mdd** : diagramme de décision multivalué auto-contenu (variables à
  domaine arbitraire, terminaux réels) — généralise BDD/MTBDD ; `apply`, restriction,
  sommes/max d'abstraction, `relabel`, évaluation.
- **analysis/SymbolicMdd** : atteignabilité, points fixes et `P(R)` **exacts** pour
  les réseaux **multivalués** (`|S(a)| ≥ 2`), sans énumérer l'espace d'états —
  point fixe d'image pour l'atteignabilité, itération de valeur pour `P(R)`.
- **cli** : `analyze reachability|probability --symbolic` route désormais vers le
  MDD (booléen ou multivalué).
- **Validation** : contrôles croisés property-based `MDD = oracle explicite`
  (atteignabilité + #états) et `P(R) MDD = P(R) CTMC` sur réseaux multivalués
  aléatoires ; course multivaluée = `1/4` exact.
- **bench/models/multivalued-demo.anx** ; **125 tests** au total.

### Abstraction-raffinement CEGAR (§7.4)

- **analysis/Cegar** : atteignabilité par abstraction-raffinement guidé par
  contre-exemple. Encadre la réponse par deux abstractions exactes sur un ensemble
  d'automates *visibles* `V` — `OA(V)` (préconditions hors-`V` abandonnées) et
  `UA(V)` (transitions à précondition hors-`V` supprimées) ; raffine `V` avec les
  automates des préconditions abandonnées jusqu'à décision. Sound et terminant
  (converge vers le cône où `OA=UA`=exact), décide souvent avec `|V| ≪ |réseau|`.
- **cli** : `transform abstract <model> --goal a=j [--refine cegar] [-o]` (verdict,
  taille de l'abstraction, écriture optionnelle de la sur-approximation).
- **Validation** : contrôle croisé property-based `CEGAR = oracle explicite` et
  `visible ⊆ cône`. Démo : cellfate Apoptosis décidé avec 7/11 automates visibles.
- **129 tests** au total.

### CEGAR quantitatif (§7.4 / §6.7)

- **analysis/QuantCegar** : encadrement **sound** de `P(R)` par un intervalle
  `[lo, hi]` raffiné par budget. Recherche meilleur-d'abord sur la chaîne de saut
  du cône, avec deux cibles absorbantes — le **but** et les **états morts** (but
  OA-inatteignable). Les trajectoires de premier passage étant disjointes :
  `lo = ∑P(traj→but) ≤ P(R)` et `hi = 1 − ∑P(traj→mort) ≥ P(R)`. `[lo, hi]`
  converge vers `P(R)` (absorption p.s. dans but ∪ morts).
- **cli** : `analyze probability --cegar [--budget N]` (encadrement `[lo,hi]`,
  preuve de seuil si l'intervalle tranche).
- **Validation** : property-based `lower ≤ P_exact ≤ upper` ; encadrements exacts
  `[1/3,1/3]` (booléen) et `[1/4,1/4]` (multivalué). Démo : cellfate Apoptosis
  `[0,5 ; 0,5]` exact ; multivalued-demo cyclique `[0,994 ; 1,0]`.
- **133 tests** au total.

### Phase 3 — bindings Python `pyquasar`

- **cli (`io.quasar.py.Quasar`)** : façade JVM sans état renvoyant du JSON
  (info, reachability, reachabilitySymbolic, quantitative, bracket, fixpoints,
  exportModel) — bundlée dans `quasar.jar`.
- **`pyquasar/`** : package Python chargeant le fat-jar via **jpype** (JVM en
  processus) ; auto-détection du jar (`$QUASAR_JAR`), méthodes renvoyant des
  dictionnaires. Pensé pour les notebooks CoLoMoTo.
- **Tests** : 7 tests JVM de la façade (munit) + 6 tests Python (jpype, ignorés
  si jpype/jar absents) ; **140 tests JVM** au total.
- `Console.load` factorisé (chargement pur, erreur en message) ; réutilisé par la
  façade et la CLI.

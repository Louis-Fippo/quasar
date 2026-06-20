# Plan expérimental — Validation des contributions du chapitre 4 avec QUASAR

> **Destinataire : Claude Code.** Ce document est un brief. Ta mission est de **construire un notebook Jupyter reproductible** (`experiments/validation_chap4.ipynb`) qui exécute l'expérimentation décrite ci-dessous **en pilotant QUASAR déjà développé**. Tu ne réécris pas QUASAR. Tu l'utilises.
>
> **Règle absolue : ne jamais simuler ou inventer un résultat.** Si une capacité nécessaire n'existe pas dans QUASAR (commande CLI absente, sortie incomplète), tu **t'arrêtes**, tu marques clairement la cellule concernée comme bloquée, et tu **proposes l'ajout du module manquant** (nom, module, signature, comportement attendu) dans la section dédiée du notebook (§7) — sans contourner par un faux résultat.

---

## 0. Contexte minimal

QUASAR = plateforme d'analyse statique quantitative de réseaux d'automates biologiques. Elle calcule, sans construire l'espace d'états, des **bornes inférieures** sur la probabilité d'atteignabilité `P(R)`, des **délais au plus tôt** `T(R)`, et des **scénarios critiques** (chemin le plus probable / le plus rapide). Fondement : chapitre 4 de la thèse de L. Fippo Fitime ; sémantique concrète = chaîne de Markov à temps continu (CTMC) à taux exponentiels.

Décisions de conception en vigueur (voir `CLAUDE.md`) : cœur Scala, IR `ANX`, distributions phase-type (exponentielle par défaut), oracle de validation **MaBoSS** (empirique) + **Storm** (exact), démarrage sur modèles déjà valués.

---

## 1. Objectif scientifique et hypothèses testées

**Objectif** : démontrer empiriquement que les bornes calculées par QUASAR sont **sûres** (jamais violées), **utiles** (suffisamment fines), et **passent à l'échelle** là où la vérification exacte explose — sur des modèles biologiquement pertinents (immunité T helper, apoptose neuronale, décision de destin cellulaire).

Hypothèses à valider (chaque hypothèse → une assertion exécutable dans le notebook) :

| # | Hypothèse | Critère mesurable |
|---|---|---|
| **H1 — Justesse (proba)** | `binf P(R)` de QUASAR ne dépasse jamais la vérité terrain | `binf P(R) ≤ P_MaBoSS` ET `binf P(R) ≤ P_Storm` (sur petits modèles). 0 violation tolérée. |
| **H2 — Justesse (délai)** | le délai au plus tôt minore le temps observé | `T(R) ≤` quantile bas de la distribution des temps d'atteinte MaBoSS |
| **H3 — Finesse** | l'écart à l'exact reste exploitable | écart relatif `(P_exact − binf P)/P_exact`, étudié vs taille et présence de cycles |
| **H4 — Scénarios critiques** | le chemin extrait correspond à la dynamique réelle | recouvrement entre le chemin le plus probable QUASAR et les trajectoires dominantes MaBoSS |
| **H5 — Scalabilité** | QUASAR reste tractable quand Storm explose | courbes de temps QUASAR vs MaBoSS vs Storm sur 4 → ~40 → 101 nœuds |
| **H6 — Apport des optimisations** | les stratégies §6 (semi-anneaux, top-k anytime, ZDD) apportent un gain | ablation : temps/qualité avec vs sans chaque stratégie |

---

## 2. Modèles (paliers) — à acquérir et valuer

| Palier | Modèle | Rôle | Accès | Statut valuation |
|---|---|---|---|---|
| **0 — contrôle exact** | **p53-Mdm2** (multivalué, oscillant → cycles) | référence absolue (CTMC exacte calculable), teste H1/H3 sur cycles | tutoriel MaBoSS / GINsim | déjà valué (`.bnd/.cfg`) |
| **0 — contrôle publié** | **Cell-fate** (Calzone : apoptose/nécrose/survie) | bornes vs probabilités MaBoSS publiées | sysbio-curie (MaBoSS) / BioModels | déjà valué |
| **A — primaire (immuno)** | **T helper** Naldi 2010 (~40 nœuds) | atteignabilité entre sous-types, H1–H5 | GINsim (PLoS CB e1000912), SBML-qual | qualitatif → **à valuer** |
| **A — montée en charge** | **T helper étendu** Abou-Jaoudé (101 composants) | H5 scalabilité, grand modèle réaliste | `ginsim.org/node/185`, SBML-qual | qualitatif → **à valuer** |
| **A — signalisation** | **TCR** (récepteur T) | objectif de signalisation, comparaison historique Pint | GINsim / CellNetAnalyzer | qualitatif → **à valuer** |
| **B — NETRI / neurotox** | **Apoptose neuronale HSP70/N2a** (Vasaikar 2015, PMC4548197) | angle NETRI ; chiffres expérimentaux à confronter | article + supplémentaires | **à reconstruire** puis valuer |

> Pour la valuation des modèles qualitatifs : appliquer la voie « taux par défaut » (voie 7.7b) — taux unitaires, puis, en sensibilité, tirages contrôlés. Conserver MaBoSS comme oracle natif sur les modèles déjà valués.

---

## 3. Objectifs biologiques à interroger (les `--goal`)

Le notebook définit, par modèle, des couples (objectif, contexte) clairs :

- **p53-Mdm2** : `--goal p53=high` depuis stress DNA ; mesurer `P` et `T`, inspecter le comportement oscillant.
- **Cell-fate** : `--goal Apoptosis=1`, `--goal Necrosis=1`, `--goal Survival=1` depuis contexte TNF ; confronter aux probabilités publiées.
- **T helper (Naldi / Abou-Jaoudé)** :
  - différenciation : `--goal RORGT=1` (Th17) depuis contexte cytokines IL-6+TGFβ ;
  - reprogrammation/plasticité : de Th17 vers Treg `--goal FOXP3=1` sous changement de signal ;
  - polarisation Th1 `--goal TBET=1` (contexte IL-12), Th2 `--goal GATA3=1` (contexte IL-4).
- **TCR** : `--goal <marqueur d'activation>=1` depuis stimulation antigénique.
- **Apoptose N2a** : `--goal Apoptosis=1` sous `FasL` vs `--goal Survival=1` sous `NGF` ; effet de `HSP70` (mutation gain/perte) ; confronter aux corrélations expérimentales publiées (caspase-3/8, Bax).

---

## 4. Structure imposée du notebook

Construis le notebook avec **ces sections, dans cet ordre**. Chaque section = cellules markdown explicatives + cellules de code idempotentes.

### Section 0 — Environnement & reproductibilité
- Démarrer depuis l'image **CoLoMoTo / Docker** ; installer/repérer QUASAR, MaBoSS, Storm.
- Capturer et afficher les **versions** de tout (QUASAR, oracles, OS) via `quasar --version`.
- Fixer les **graines aléatoires** (MaBoSS), définir `N_SAMPLES`, `MAX_TIME`, `TIMEOUT`.
- **Pilotage de QUASAR** : par défaut, appeler la **CLI en sous-processus** avec l'option globale `--json`, et parser la sortie JSON (le SDK `pyquasar` est différé — voir §7, point bloquant potentiel). Écrire un petit helper `run_quasar(args) -> dict`.

### Section 1 — Acquisition & import des modèles
- Télécharger chaque modèle (GINsim/BioModels) ; `quasar model import --format auto -o <m>.anx`.
- `quasar model validate` puis `quasar model info` ; afficher un tableau récapitulatif (#automates, Σ|S(a)|, #transitions, hints de structure).

### Section 2 — Valuation (taux)
- Modèles déjà valués (p53, cell-fate) : import direct `.bnd/.cfg`.
- Modèles qualitatifs : assigner des taux (voie 7.7b). **Si QUASAR n'expose pas de commande d'assignation de taux → §7.**

### Section 3 — Analyses QUASAR (le cœur)
Pour chaque (modèle, objectif), capturer **valeur + temps d'exécution** :
- `quasar analyze probability <m>.anx --goal ... --from ... --json`
- `quasar analyze delay <m>.anx --goal ... --json`
- `quasar analyze scenario <m>.anx --goal ... --kind most-probable -k 5 --json`
- Variantes de solveur pour l'ablation (§6 plus bas) : `--semiring`, `--top-k`, `--anytime --budget`, `--cycle-policy`.

### Section 4 — Oracles (vérité terrain)
- **MaBoSS (empirique)** : `quasar verify maboss <m>.anx --goal ... --samples N --max-time T --json` → récupérer `P(R)` **et la distribution des temps d'atteinte** (nécessaire pour H2/H4).
- **Storm (exact)** : `quasar verify storm <m>.anx --goal ... --json` sur les modèles assez petits → `P(R)` exact et temps d'atteinte espéré.

### Section 5 — Confrontation & métriques
- **H1** : assertion `assert binf_P <= P_oracle + ε` ; le notebook doit **échouer bruyamment** en cas de violation (c'est le test le plus important).
- **H2** : comparer `T(R)` aux quantiles MaBoSS.
- **H3** : calculer et tabuler l'écart relatif.
- **H4** : mesurer le recouvrement scénario QUASAR ↔ trajectoires MaBoSS.

### Section 6 — Scalabilité (H5) & ablation (H6)
- **Scalabilité** : balayer les tailles (p53 4 → Naldi ~40 → Abou-Jaoudé 101) ; tracer temps QUASAR vs MaBoSS vs Storm ; marquer le point où Storm dépasse le `TIMEOUT`.
- **Ablation** : pour un modèle moyen, comparer (a) chemin algébrique semi-anneau vs énumération naïve, (b) convergence de la borne `--anytime` en fonction du budget (courbe monotone), (c) avec/sans backend ZDD. **Si une commande d'ablation/sweep manque → §7.**

### Section 7 — Modules QUASAR manquants (rapport)
Section vivante où tu consignes chaque blocage rencontré et la **proposition d'ajout** correspondante (cf. §7 de ce plan).

### Section 8 — Figures, tableaux, conclusions, run-bundle
- Figures attendues : (1) nuage `binf P` vs `P_exact` avec la diagonale de justesse ; (2) finesse vs taille ; (3) courbes de temps (scalabilité) ; (4) convergence anytime.
- Tableau de synthèse H1–H6 (validé / partiellement / bloqué).
- Export **run-bundle** reproductible : `quasar repo bundle ... -o validation_chap4.bundle` + figer l'environnement.

---

## 5. Critères de succès

- **Bloquant** : aucune violation de H1 sur aucun modèle (sinon = bug de bornes à remonter, expérimentation invalide).
- **Attendu** : H2, H3, H5 documentés avec figures.
- **Bonus** : H4 et H6 quantifiés.
- **Transversal** : le notebook s'exécute de bout en bout sur l'image Docker, de façon déterministe (mêmes graines → mêmes chiffres).

---

## 6. Exigences de reproductibilité (non négociables)

1. Tout pilotage de QUASAR passe par la CLI `--json` (contrat stable) ; aucune dépendance à un état caché.
2. Toutes les versions et graines sont capturées dans le notebook.
3. Cellules **idempotentes** (re-exécutables sans effet de bord) ; résultats intermédiaires mis en cache (`--cache-dir`).
4. Les assertions de justesse (H1) sont des **tests exécutables**, pas des commentaires.
5. Livrable final empaqueté en run-bundle CoLoMoTo (modèle + paramètres + versions + notebook).

---

## 7. Protocole « module manquant » + gaps probables à anticiper

**Protocole.** Pour chaque capacité requise : (1) tenter avec une commande existante du `CLAUDE.md` ; (2) si elle manque ou si sa sortie est incomplète, **ne pas contourner** ; (3) marquer la cellule `# ⚠️ BLOQUÉ — module manquant` ; (4) rédiger dans la Section 7 du notebook une **fiche de proposition** : `nom de commande`, module cible, signature proposée, entrée/sortie JSON attendue, et la raison pour laquelle l'expérimentation en a besoin.

**Gaps que tu rencontreras probablement** (anticipe-les, propose-les proprement) :

| Besoin de l'expérience | Existe ? | Proposition si manquant |
|---|---|---|
| **Piloter QUASAR depuis Python** | SDK `pyquasar` **différé** | Décision : soit pilotage CLI `--json` en sous-processus (défaut, recommandé), soit proposer de **promouvoir `pyquasar`** en module minimal (juste `run`/`parse`). Documente le choix. |
| **Assigner/synthétiser des taux** sur modèles qualitatifs | `model normalize --infer-rates` existe ; synthèse par défaut ? | proposer `quasar model assign-rates <m> --policy unit|sample --seed N` (module `io`/`transform`) |
| **Distribution des temps d'atteinte** depuis MaBoSS (pas seulement P finale) | `verify maboss` existe ; expose-t-il la distribution ? | proposer extension `verify maboss --emit hitting-time-distribution` (adaptateur `verify`) |
| **P exact + temps espéré** via Storm | `verify storm` existe ; renvoie-t-il le temps d'atteinte espéré ? | proposer `verify storm --metric prob|expected-time` |
| **Comparaison scénario ↔ oracle** (H4) | non spécifié | proposer `analyze scenario --compare-oracle maboss` OU router au niveau notebook |
| **Balayage de tailles** (H5) | `bench run` existe ; sweep paramétré ? | proposer `quasar bench sweep --models ... --metric time --json` |
| **Ablation des stratégies** (H6) | non spécifié | proposer `quasar bench ablation --strategy semiring|topk|zdd --json` |
| **Métriques de validation structurées** (finesse, recouvrement) | `bench validate`/`report` existent ; JSON exploitable ? | proposer que `bench validate` émette un JSON `{soundness, tightness, delay_gap, scenario_overlap}` |

> Pour chaque proposition, **n'implémente pas le module QUASAR toi-même dans ce notebook** : décris-le, et si le pilotage CLI suffit pour avancer, fais-le ; sinon, marque la partie de l'expérience comme « en attente du module X ».

---

## 8. Definition of Done (livrables)

1. `experiments/validation_chap4.ipynb` exécutable de bout en bout sur l'image Docker.
2. Les 4 figures et le tableau de synthèse H1–H6.
3. La Section 7 du notebook remplie : liste exhaustive des modules manquants rencontrés, avec fiches de proposition.
4. Le run-bundle `validation_chap4.bundle`.
5. Un court `experiments/README.md` : comment relancer, prérequis (MaBoSS/Storm), et la liste des propositions de modules QUASAR à arbitrer.

---

**Rappel final** : la valeur scientifique de cette expérience tient à une chose — **la justesse des bornes (H1) testée comme une assertion qui peut échouer**. Tout le reste (finesse, scalabilité, scénarios) est secondaire par rapport à cette honnêteté méthodologique. Ne la contourne jamais.

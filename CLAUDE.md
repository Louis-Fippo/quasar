# CLAUDE.md — Guide de développement de QUASAR

> Ce fichier est lu automatiquement par Claude Code. Il décrit **quoi** construire, **comment**, et dans **quel ordre**. Respecte-le strictement. En cas de doute sur une décision de conception, consulte `docs/conception.md` (le document d'architecture détaillé) ; si l'ambiguïté persiste, demande avant d'implémenter.

---

## 1. Ce qu'est QUASAR

**QUASAR** = *QUantitative And Static Analysis of Regulatory networks*.

Une plateforme d'**analyse statique** des propriétés **quantitatives** (probabilités, délais) **et qualitatives** (atteignabilité) des **grands réseaux d'automates biologiques**, sans construire l'espace d'états (qui explose combinatoirement).

Fondations scientifiques :
- **Interprétation abstraite** (Cousot & Cousot) : propriétés comme points fixes sur domaines abstraits, raisonnement par sur-/sous-approximation avec garanties formelles.
- **Graphes de Causalité Locale (GLC/LCG)** à la Pint/Paulevé : atteignabilité qualitative, réduction orientée-but.
- **Chapitre 4 de la thèse de L. Fippo Fitime** : le **Graphe de Causalité Locale Quantifié `⌈Gω_ς⌉`** valué par probabilités et délais ; calcul de **bornes inférieures** sur `P(R)` et de **délais au plus tôt** `T(R)` ; extraction de scénarios critiques. Sémantique concrète : **chaîne de Markov à temps continu (CTMC)** avec taux exponentiels.

---

## 2. Périmètre (LIRE AVANT TOUT)

### Dans le périmètre
- **Cœur de calcul** (IR, moteurs d'analyse, solveur).
- **CLI `quasar`** — interface principale, surface de commandes la plus large possible (§7).
- **TUI** — mode interactif terminal (§7.10).
- **Adaptateurs externes** vers NuSMV, Storm, MaBoSS (fallback + oracle de validation).
- **Dépôt de modèles** et **harnais de benchmark/validation**.

### HORS périmètre — NE PAS implémenter
- ❌ **Portail / dashboard web** (React ou autre).
- ❌ **API REST / gRPC**.
- ❌ Toute UI graphique.

### Optionnel / différé (ne pas commencer sans feu vert)
- ⏸ **SDK Python `pyquasar`** (bindings py4j/jpype pour notebooks) : utile pour CoLoMoTo mais secondaire. Ne l'aborder qu'après une CLI fonctionnelle, et seulement si demandé.
- ⏸ Inférence de taux depuis séries temporelles (phase 3).

Toute fonctionnalité produite doit être **atteignable depuis la CLI ou la TUI**. Si une capacité n'a pas de commande, elle n'est pas finie.

---

## 3. Décisions de conception arrêtées (NON négociables)

| # | Décision | Implication pour le code |
|---|---|---|
| **D1** | IR = **ANX maison, isomorphe à bioLQM** | Tout passe par l'IR `ANX`. Prévoir une projection sans perte vers/depuis le modèle objet bioLQM. |
| **D2** | **Tout le cœur en Scala** (JVM), pas de Rust | `sbt` multi-modules. Diagrammes de décision via biblio BDD JVM (JavaBDD/BeeDeeDee) ; JNI vers Sylvan/CUDD seulement en dernier recours, jamais comme dépendance par défaut. |
| **D3** | Validation initiale sur **modèles déjà valués** (MaBoSS, PhysiBoSS) | L'oracle de référence est MaBoSS. Phases 0–1 n'utilisent que cette voie. |
| **D4** | **Distributions phase-type dès l'IR**, exponentielle par défaut | `υ` est une distribution de première classe ; l'exponentielle est le cas mono-phase. Phase-type gérée par expansion en phases dans la CTMC. |
| **D5** | Fallback/oracle : **NuSMV + Storm** | Deux adaptateurs derrière une interface commune. NuSMV = qualitatif symbolique ; Storm = probabiliste exact (aussi oracle exact complémentaire de MaBoSS). |

---

## 4. Stack technique

- **Langage** : Scala 3 (LTS), JVM 21.
- **Build** : `sbt` (multi-modules). Formatage : `scalafmt`. Lint : `scalafix`.
- **CLI** : `com.monovore::decline` (parsing fonctionnel, sous-commandes composables).
- **TUI** : `org.jline:jline` (terminal, lignes, complétion) + rendu maison léger.
- **Tests** : `munit` (+ `munit-scalacheck` pour le property-based, essentiel pour vérifier la **justesse des bornes**).
- **Algèbre linéaire** (matrice fondamentale CTMC) : `breeze`.
- **Diagrammes de décision** : JavaBDD ou BeeDeeDee (ZDD/MDD).
- **Packaging CLI** : `sbt-assembly` (fat-jar) en dev ; **GraalVM `native-image`** pour livrer un binaire `quasar` à démarrage rapide.
- **Adaptateurs externes** : appel des binaires `NuSMV`, `storm`, `MaBoSS` en sous-processus (détection de version, parsing de sortie). Ne pas les vendoriser ; les déclarer en prérequis et échouer proprement si absents.

---

## 5. Arborescence du dépôt

```
quasar/
├── CLAUDE.md                  # ce fichier
├── build.sbt                  # build multi-modules
├── project/                   # plugins sbt (assembly, native-image, scalafmt, scalafix)
├── docs/
│   └── conception.md          # document d'architecture détaillé (source de vérité conceptuelle)
├── core/                      # IR ANX, semi-anneaux, GLC, ⌈Gω⌉, solveur algébrique
│   └── src/main/scala/io/quasar/core/
│       ├── ir/                # ANX : Automaton, LocalState, Transition, Distribution, Context, Metadata
│       ├── semiring/          # Semiring[T] : Tropical (min,+), Viterbi (max,×), Prob (+,×)
│       ├── glc/               # Local Causality Graph (qualitatif)
│       ├── qglc/              # ⌈Gω_ς⌉ quantifié + valuations (chap. 4)
│       ├── solver/            # chemin algébrique, SCC, CTMC absorbante (matrice fondamentale), ZDD
│       └── biolqm/            # projection ANX <-> bioLQM
├── io/                        # importeurs / exporteurs
│   └── .../io/{an,sbmlqual,maboss,boolnet,ginsim,cellcollective,csv,nusmv,storm,graphml,onnx,dot}
├── analysis/                  # moteurs : reachability OA/UA, quantitatif, topologie, cutsets, mutations
├── transform/                 # réduction orientée-but, abstraction (CEGAR), booléanisation
├── verify/                    # adaptateurs NuSMV, Storm, MaBoSS (fallback + oracle)
├── repo/                      # dépôt de modèles versionné + datasets + run-bundles CoLoMoTo
├── bench/                     # modèles de référence + harnais de validation
│   └── models/                # p53-mdm2, cellfate, mapk, tcr, pid (+ .bnd/.cfg MaBoSS associés)
├── cli/                       # binaire `quasar` (decline) — assemble toutes les commandes §7
├── tui/                       # mode interactif (jline)
└── pyquasar/                  # OPTIONNEL/DIFFÉRÉ : bindings Python (py4j/jpype)
```

**Convention de package** : `io.quasar.<module>`. Le module `cli` ne contient **aucune logique métier** : il ne fait que parser et déléguer aux modules `analysis`/`io`/etc.

---

## 6. Commandes de build et de dev

```bash
sbt compile                       # compiler tous les modules
sbt test                          # tous les tests
sbt "project core" test           # tests d'un module
sbt scalafmtAll                   # formater
sbt "scalafixAll"                 # lint/réécritures
sbt "project cli" assembly        # fat-jar -> cli/target/.../quasar.jar
sbt "project cli" nativeImage     # binaire natif `quasar` (GraalVM)
sbt "project bench" run           # lancer le harnais de benchmark

# Lancer la CLI en dev sans packager :
sbt "project cli" "run model info bench/models/mapk.anx"
```

**Definition of Done** d'une fonctionnalité : (1) code dans le bon module, (2) tests `munit` y compris une **propriété de justesse** quand une borne est calculée (`binf P(R) ≤ P_oracle`), (3) **commande CLI** exposée et documentée dans `--help`, (4) `scalafmtAll` + `scalafixAll` propres, (5) entrée ajoutée au `CHANGELOG.md`.

---

## 7. Spécification de la CLI `quasar`

Binaire : `quasar`. Structure : `quasar <groupe> <commande> [args] [options]`.
Vise l'**exhaustivité** : chaque capacité du cœur a sa commande.

### 7.0 Options globales (valables partout)
```
--config <file>        fichier de config (TOML)
--log-level <lvl>       off|error|warn|info|debug|trace   (défaut: info)
--json                  sortie machine (JSON) au lieu du rendu humain
--quiet / -q            silencieux (résultat brut uniquement)
--threads <n>           parallélisme (défaut: nb cœurs)
--cache-dir <dir>       cache des constructions ⌈Gω⌉ (mémoïsation persistante)
--no-cache              désactive le cache
--version               version + versions détectées de NuSMV/Storm/MaBoSS
--help / -h
```

### 7.1 `quasar model` — cycle de vie des modèles
```
import   <file>              importe vers l'IR ANX
    --format auto|an|sbml-qual|maboss|boolnet|ginsim|cellcollective|csv   (défaut: auto)
    -o <model.anx>
export   <model> --format <an|sbml-qual|maboss|nusmv|storm-prism|graphml|onnx|dot> -o <out>
convert  <in> <out>          raccourci import+export (déduit les formats des extensions)
validate <model>             vérifie cohérence (taux>0, labels, pré-conditions bien formées)
normalize <model>            --booleanize  --detect-chains  --infer-rates  -o <out>
info     <model>             résumé : #automates, Σ|S(a)|, #transitions, hints de structure
inspect  <model>             détail : automates, états locaux, transitions, distributions
stats    <model>             métriques de graphe (degrés, densité, #SCC)
diff     <m1> <m2>           différences structurelles et quantitatives
biolqm   <model>             projette en bioLQM et signale les pertes éventuelles
```

### 7.2 `quasar analyze` — moteur d'analyse
Notation d'objectif : `--goal a=j` (atteindre l'état local `j` de l'automate `a`) ; contexte initial : `--from "b=0,c=1"` ou `--from <ctx-file>`.
```
reachability <model> --goal <a=j> [--from <ctx>] --mode oa|ua|both
    # OA = sur-approx (condition nécessaire), UA = sous-approx (condition suffisante)
quantitative <model> --goal ... [--from ...] --metric prob|delay|both
    # cœur chap. 4 : borne inf. P(R) et/ou délai au plus tôt T(R)
probability  <model> --goal ... [--threshold <p>]
    # borne inférieure de P(R) ; avec --threshold, répond au booléen P(R) ≥ p
delay        <model> --goal ...                 # délai minimal T(R)
scenario     <model> --goal ... --kind most-probable|fastest|critical [-k <N>]
    # extrait les N meilleurs scénarios (chemin le plus probable / le plus rapide)
cutsets      <model> --goal ... [--max-size <N>]   # ensembles de coupe (intervention)
mutations    <model> --goal ... --effect enable|disable
    # mutations (gain/perte de fonction) qui rendent l'objectif (in)atteignable
compare      <model> --goal ...                  # bornes QUASAR vs oracle MaBoSS côte à côte
```

### 7.3 `quasar topology` — structure et attracteurs
```
scc          <model>                         # composantes fortement connexes
cycles       <model> --sign positive|negative|all     # circuits de Thomas
feedback     <model>                         # circuits de rétroaction + signes
trap-spaces  <model> [--minimal]             # trap-spaces (espaces piège)
fixpoints    <model>                         # points fixes
attractors   <model> --method exact|abstract # attracteurs (exact pour petits, abstrait sinon)
```

### 7.4 `quasar transform` — transformations de modèle
```
reduce    <model> --goal ...        # réduction orientée-but (préserve les traces minimales)
abstract  <model> [--refine cegar]  # abstraction (raffinement à la demande)
booleanize <model>                  # multivalué -> booléen
slice     <model> --component <a>   # tranche autour d'un composant
```

### 7.5 `quasar solver` — accès bas niveau et réglages
```
glc   <model> --goal ... [-o <dot|json>]    # construit et exporte le GLC
qglc  <model> --goal ... [-o <dot|json>]    # construit ⌈Gω_ς⌉ + valuations
```
Options de solveur (acceptées aussi par `analyze` et `solver`) :
```
--semiring tropical|viterbi|prob     # min,+ (délai) | max,× (proba max) | +,× (agrégation)
--approx <0.0..1.0>                  # agressivité (0 = exact, 1 = max approx)
--top-k <N>                          # tronque aux N meilleures solutions (anytime, borne valide)
--anytime [--budget <ms>]            # raffine la borne jusqu'au budget, résultat monotone
--timeout <ms>
--max-levels <N>                     # borne |S(a)| (sécurité contre l'explosion)
--cycle-policy abstract|exact|fallback   # politique sur SCC cycliques (cf. §6.5 conception)
```

### 7.6 `quasar verify` — fallback exact / oracle (NuSMV, Storm, MaBoSS)
```
nusmv    <model> --spec <CTL/LTL> | --goal ...   # model-checking symbolique qualitatif
storm    <model> --prop <PCTL> | --goal ...      # vérification probabiliste EXACTE
maboss   <model> --goal ... [--samples <N>] [--max-time <t>]   # simulation CTMC (oracle empirique)
fallback <model> --goal ...                      # auto : tente statique, bascule si non concluant
```

### 7.7 `quasar repo` — dépôt de modèles versionné
```
init                         # initialise un dépôt local
add    <model> [--tags ...]
list   [--format ...] [--tag ...]
search <query>
get    <id> [-o <out>]
rm     <id>
tag    <id> <tags...>
bundle <id> -o <bundle>      # run-bundle reproductible (modèle + params + versions + notebook)
```

### 7.8 `quasar dataset` — données expérimentales (phase 3, différé)
```
import      <csv>                    # séries temporelles
map         <dataset> <model>        # mappe colonnes -> composants
infer-rates <dataset> <model> -o <out>   # DIFFÉRÉ : inférence de taux (pont BoNesis)
```

### 7.9 `quasar bench` — benchmark & validation
```
models                       # liste les modèles de référence intégrés
run    <suite>               # suite: small|mapk|tcr|pid|all
validate <model> --goal ...  # pipeline complet : QUASAR vs MaBoSS (justesse + finesse)
report [-o <html|md>]        # rapport de validation (écarts, temps, run-bundle)
```
Modèles de référence à inclure dans `bench/models/` :
- **p53-mdm2** (4 nœuds, oscillant → teste le traitement des cycles) — *exact calculable*, référence absolue.
- **cellfate** (décision de destin cellulaire, Calzone et al., ~25 nœuds) — *publié avec probabilités MaBoSS*, confrontation directe.
- **mapk** (Grieco et al., ~50 nœuds).
- **tcr** (T-cell, ~40–100 nœuds).
- **pid** (~10 000 nœuds) — scalabilité uniquement.

### 7.10 `quasar tui` — mode interactif
```
quasar tui [<model>]
```
TUI (jline) offrant : navigateur de modèles, constructeur d'objectif (`--goal`) assisté,
visualiseur GLC/⌈Gω⌉ en ASCII, explorateur de scénarios, lancement d'analyses,
comparaison live avec l'oracle. La TUI **réutilise** les mêmes commandes que la CLI
(aucune logique dupliquée) : c'est une couche d'orchestration au-dessus de `analysis`/`io`.

---

## 8. Ordre de développement (suis ces phases)

> Règle d'or : à chaque phase, **chaque nouvelle capacité du cœur est immédiatement câblée à une commande CLI** et testée. Pas de cœur « orphelin ».

**Phase 0 — Squelette + boucle d'I/O (PoC)**
1. `build.sbt` multi-modules, scalafmt/scalafix, CI minimale.
2. `core/ir` : types ANX (avec `Distribution` phase-type, exponentielle par défaut).
3. `io` : importeur `.an` (Pint) et `.bnd/.cfg` (MaBoSS) ; exporteur `.an`.
4. `cli` : `quasar model import|export|info|inspect|validate`.
5. `bench/models` : intègre **p53-mdm2** (avec ses taux MaBoSS).
6. ✅ Jalon : `quasar model info` et `quasar model convert` fonctionnent sur p53-mdm2.

**Phase 1 — Cœur d'analyse + gains de complexité**
1. `core/semiring` : `Semiring[T]` + Tropical/Viterbi/Prob (avec property tests des lois).
2. `core/glc` : construction du GLC ; `analyze reachability --mode oa|ua`.
3. `core/qglc` + `core/solver` : ⌈Gω⌉, valuations chap. 4, **chemin algébrique (Dijkstra/Bellman-Ford)** pour `delay`/`scenario` (gain §6.2), **spécialisation chaîne** (§6.3).
4. `cli` : `analyze quantitative|probability|delay|scenario|cutsets|mutations`, `solver glc|qglc`.
5. `verify maboss` : adaptateur oracle ; `analyze compare`.
6. `bench validate` : justesse (`binf P ≤ P_oracle`) + finesse sur p53-mdm2 et cellfate.
7. ✅ Jalon : bornes calculées et **validées contre MaBoSS** sur petits modèles.

**Phase 2 — Robustesse, cycles, échelle**
1. `core/solver` : SCC (Tarjan), **CTMC absorbante locale** (matrice fondamentale, §6.5), **expansion phase-type**.
2. Backend **ZDD/MDD** pour l'agrégation des solutions (§6.4) ; `--top-k`, `--anytime` (§6.7).
3. `transform reduce|abstract` ; `topology *`.
4. `io` exporteurs : `nusmv`, `storm-prism` ; `verify nusmv|storm|fallback`.
5. `repo *`, `bundle` (run-bundle CoLoMoTo).
6. `tui`.
7. ✅ Jalon : MAPK/TCR analysés ; cycles traités ; CLI complète (§7).

**Phase 3 — Extensions (différé, sur feu vert)**
- `pyquasar` (bindings), `dataset infer-rates`, ONNX/GraphML, analyse paramétrique.

---

## 9. Conventions de code

- **Scala 3 idiomatique** : immutabilité par défaut, types algébriques (`enum`/`sealed`), `Either`/`Validated` pour les erreurs récupérables (pas d'exceptions pour le flux normal).
- **Le cœur est pur** : `core/` n'a aucun effet de bord, aucune I/O, aucun appel de sous-processus. Les effets (fichiers, binaires externes) vivent dans `io/`, `verify/`, `repo/`.
- **Semi-anneaux** : tout calcul de chemin passe par `Semiring[T]` ; ne jamais coder en dur « proba » ou « délai » dans les algorithmes de parcours.
- **Garanties formelles** : toute fonction renvoyant une borne documente si c'est une **sur-approximation (OA)** ou **sous-approximation (UA)**, et le type le reflète (ex. `LowerBound[Double]`). Une UA ne doit JAMAIS dépasser la valeur exacte — c'est un invariant testé.
- **bioLQM** : la projection ANX↔bioLQM est testée par aller-retour (round-trip) sur les modèles de `bench/`.
- **Sorties** : tout rendu humain a un équivalent `--json` stable (contrat pour la TUI et les scripts).
- **Erreurs externes** : si NuSMV/Storm/MaBoSS manquent, message clair indiquant l'installation attendue ; ne pas planter avec une stacktrace.
- **Pas de dépendance Rust/native par défaut** (D2). JNI seulement derrière un flag explicite et avec fallback JVM.
- **Tests** : `munit` ; pour chaque borne, un test `scalacheck` de justesse ; pour chaque parser, un round-trip ; cibler une couverture utile du cœur.

---

## 10. Pièges à connaître

- **Source de l'explosion** : ce n'est PAS le nombre d'automates (polynomial) ni le graphe d'états (qu'on évite), mais l'**énumération des solutions/chemins locaux acycliques intra-automate** (`|Sol| ≤ Σ_a C(|T(a)|,|S(a)|)`). N'énumère jamais naïvement ces chemins : utilise le chemin algébrique (§6.2), la spécialisation chaîne (§6.3) ou les ZDD (§6.4).
- **Cycles fermés** : l'analyse statique pure ne conclut pas. Politique : SCC → résolution CTMC locale exacte (§6.5), et `fallback` vers Storm en dernier recours.
- **Phase-type** : par défaut tout est exponentiel (mono-phase). Ne déploie l'expansion en phases que si une transition porte une `dist` non triviale, sinon coût inutile.
- **MaBoSS = oracle empirique** (simulation), **Storm = oracle exact** : sur les petits modèles, confronte aux deux. Une borne UA dépassant l'exact Storm = bug bloquant.
- **Justesse avant finesse** : une borne lâche mais correcte est acceptable ; une borne fausse ne l'est jamais.

---

## 11. Rappels rapides

```bash
# développer une commande de bout en bout :
sbt "project cli" "run analyze probability bench/models/cellfate.anx --goal Apoptosis=1 --from default"
# valider contre l'oracle :
sbt "project cli" "run analyze compare bench/models/cellfate.anx --goal Apoptosis=1"
# tout vérifier avant commit :
sbt scalafmtAll scalafixAll test
```

Source de vérité conceptuelle : `docs/conception.md`. Ce `CLAUDE.md` prime pour les questions de **périmètre, build, conventions et surface CLI**.

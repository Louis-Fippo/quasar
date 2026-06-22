# QUASAR

**QU**antitative **A**nd **S**tatic **A**nalysis of **R**egulatory networks.

Analyse statique des propriétés quantitatives (probabilités, délais) et
qualitatives (atteignabilité) des grands réseaux d'automates biologiques, **sans
construire l'espace d'états**. Fondé sur l'interprétation abstraite, les Graphes
de Causalité Locale (Pint/Paulevé) et le Graphe de Causalité Locale Quantifié
`⌈Gω_ς⌉` (chap. 4 de la thèse de L. Fippo Fitime).

## Documentation

La documentation complète vit dans [`docs/`](docs/) et se construit en site
statique avec [MkDocs](https://www.mkdocs.org) :

```bash
mkdocs serve         # http://127.0.0.1:8000 (rendu live)
mkdocs build         # site/ (HTML statique)
```

| Page | Contenu |
|------|---------|
| [Accueil](docs/index.md) | vue d'ensemble, capacités, garanties |
| [Prise en main](docs/getting-started.md) | prérequis, build, premier modèle |
| [Guide CLI](docs/usage.md) | référence exhaustive des commandes `quasar` |
| [Conception](docs/conception.md) | architecture telle qu'implémentée |
| [Bindings Python](docs/python.md) | `pyquasar` pour notebooks |
| [Référence API](docs/api.md) | Scaladoc (`sbt docAll`) |

## Capacités

- **Atteignabilité** OA/UA + **exacte symbolique** (BDD/MDD) sans énumération.
- **Quantitatif** : `P(R)` exacte (CTMC absorbante) ou borne inf. sound, délai
  `T(R)`, temps moyen, scénarios top-k.
- **Encadrement CEGAR** qualitatif et **quantitatif** `[lo, hi]` raffiné par budget.
- **Topologie** (SCC, circuits signés, points fixes, attracteurs, trap-spaces),
  **intervention** (cutsets, mutations), **transformations** (réduction, slice,
  booléanisation, phase-type).
- **I/O** large via **bioLQM** (SBML-qual, BoolNet, GINML…) + oracles externes.

## Build

```bash
sbt compile          # compile tous les modules
sbt test             # tests (dont property tests de justesse)
sbt "cli/assembly"   # fat-jar -> cli/target/scala-3.3.4/quasar.jar
```

## Utilisation

```bash
JAR=cli/target/scala-3.3.4/quasar.jar

# Cycle de vie des modèles
java -jar $JAR model info    bench/models/p53-mdm2.bnd
java -jar $JAR model inspect bench/models/p53-mdm2.bnd
java -jar $JAR model convert bench/models/p53-mdm2.bnd p53.anx

# Analyse
java -jar $JAR analyze reachability p53.anx --goal p53=1
java -jar $JAR analyze quantitative p53.anx --goal p53=1
java -jar $JAR analyze probability  p53.anx --goal p53=1 --threshold 0.01
java -jar $JAR analyze scenario     p53.anx --goal p53=1
java -jar $JAR analyze cutsets      p53.anx --goal Mdm2=1
java -jar $JAR analyze mutations    p53.anx --goal Mdm2=1 --effect disable
java -jar $JAR analyze compare      p53.anx --goal Mdm2=1

# Topologie / attracteurs
java -jar $JAR topology feedback    p53.anx
java -jar $JAR topology attractors  p53.anx
java -jar $JAR topology fixpoints   p53.anx

# Transformations
java -jar $JAR transform reduce p53.anx --goal Mdm2=1
java -jar $JAR transform slice  p53.anx --component p53

# GLC / ⌈Gω⌉
java -jar $JAR solver glc  p53.anx --goal p53=1 -o dot
java -jar $JAR solver qglc p53.anx --goal p53=1

# Oracles externes (NuSMV/Storm/MaBoSS — détectés à l'exécution)
java -jar $JAR verify storm p53.anx --goal p53=1
java -jar $JAR model export p53.anx --format nusmv -o p53.smv

# Dépôt de modèles + TUI
java -jar $JAR repo init && java -jar $JAR repo add p53.anx --tags reference
java -jar $JAR tui p53.anx
```

Toutes les commandes acceptent `--json` pour une sortie machine stable.

## Modules

| Module     | Rôle |
|------------|------|
| `core`     | IR ANX, semi-anneaux, GLC, graphe d'interaction, SCC (pur, sans I/O) |
| `io`       | importeurs/exporteurs (ANX, Pint `.an`, MaBoSS, DOT, NuSMV, PRISM) |
| `analysis` | atteignabilité OA/UA, quantitatif `P(R)`/`T(R)`, topologie, intervention, transform |
| `verify`   | adaptateurs sous-processus NuSMV / Storm / MaBoSS |
| `cli`      | binaire `quasar` (decline) + TUI (jline) + dépôt de modèles |
| `bench`    | modèles de référence (p53-mdm2, …) |
| `pyquasar` | bindings Python (jpype) pour notebooks — façade `io.quasar.py.Quasar` |

## Surface CLI

`model` (import/export/convert/validate/info/inspect/stats/diff/normalize) ·
`analyze` (reachability/quantitative/probability/delay/scenario/cutsets/mutations/compare) ·
`topology` (scc/cycles/feedback/fixpoints/attractors/trap-spaces) ·
`transform` (reduce/slice/booleanize) · `solver` (glc/qglc) ·
`verify` (nusmv/storm/maboss/fallback) · `repo` (init/add/list/get/rm/tag/search/bundle) ·
`bench` (models/validate/run) · `tui`

## Garanties

- **OA** (sur-approximation) : condition *nécessaire* — `OA = non` ⇒ inatteignable.
- **UA** (sous-approximation) : condition *suffisante* — `UA = oui` ⇒ atteignable.
- **`binf P(R) ≤ P_exact`** : la borne inférieure de probabilité ne dépasse jamais
  la valeur exacte (justesse vérifiée par property tests contre un oracle exact).

Voir `CLAUDE.md` pour le plan de développement complet et `docs/conception.md`
pour l'architecture.

# Référence API (Scaladoc)

Le cœur de QUASAR est écrit en Scala 3 et documenté par Scaladoc. La doc API
n'est pas pré-générée dans le dépôt ; produis-la localement.

## Générer

```bash
# toute la doc API (tous les modules)
sbt docAll

# ou module par module
sbt "core/doc"        # -> core/target/scala-3.3.4/api/index.html
sbt "analysis/doc"    # -> analysis/target/scala-3.3.4/api/index.html
sbt "io/doc"
sbt "verify/doc"
sbt "biolqm/doc"
sbt "cli/doc"
```

Ouvre ensuite le fichier `index.html` correspondant dans un navigateur.

## Organisation des packages

| Package | Rôle |
|---|---|
| `io.quasar.core.ir` | IR ANX : `AutomataNetwork`, `Automaton`, `Transition`, `LocalState`, `Context`, `Distribution`, `Validation` |
| `io.quasar.core.semiring` | `Semiring[T]` + `Tropical` / `Viterbi` / `ProbAgg` / `BoolReach` |
| `io.quasar.core.glc` | Graphe de Causalité Locale, graphe d'interaction signé, cône d'influence |
| `io.quasar.core.solver` | SCC (Tarjan), CTMC absorbante (`CtmcSolver`), solveurs linéaires |
| `io.quasar.core.dd` | diagrammes de décision auto-contenus : `Bdd`, `Mtbdd`, `Mdd` |
| `io.quasar.analysis` | `Reachability`, `Quantitative`, `Topology`, `Intervention`, `Transform`, `Scenarios`, `Symbolic*`, `Cegar`, `QuantCegar` |
| `io.quasar.io` | importeurs/exporteurs (ANX, Pint, MaBoSS, DOT, NuSMV, PRISM) |
| `io.quasar.biolqm` | projection ANX ↔ bioLQM (D1) |
| `io.quasar.verify` | adaptateurs sous-processus NuSMV / Storm / MaBoSS |
| `io.quasar.cli` | binaire `quasar` (decline) + TUI (jline) |
| `io.quasar.py` | façade JVM pour les bindings Python |

## Conventions documentées dans le code

- Toute fonction renvoyant une borne précise si c'est une **sur-approximation
  (OA)** ou une **sous-approximation (UA)** ; le type le reflète (ex.
  `LowerBound[Double]`, `Approx`).
- Tout calcul de chemin est paramétré par `Semiring[T]` (jamais de « proba » ou
  « délai » codé en dur).
- `core/` est pur (aucune I/O, aucun effet de bord).

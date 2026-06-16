# QUASAR — Document de conception

> Ce document décrit l'architecture **telle qu'implémentée**. Il complète
> `CLAUDE.md` (qui fixe le périmètre, le build et la surface CLI). En cas de
> divergence sur le périmètre, `CLAUDE.md` prime.

## 1. Vue d'ensemble

QUASAR analyse statiquement des réseaux d'automates biologiques pour borner des
propriétés quantitatives (probabilité `P(R)`, délai `T(R)`) et qualitatives
(atteignabilité) **sans énumérer l'espace d'états global**. La sémantique
concrète de référence est une CTMC à taux exponentiels.

Le flot général :

```
fichier ──import──▶ IR ANX ──▶ GLC ⌈Gω_ς⌉ ──▶ solveur (semi-anneau) ──▶ bornes
 (.bnd/.an)         (core/ir)    (core/glc)      (analysis)            (OA/UA, P, T)
```

## 2. IR ANX (`core/ir`)

Modèle objet isomorphe à bioLQM (D1) :

- `AutomataNetwork` : `Map[nom, Automaton]` + `Metadata`.
- `Automaton(name, levels, transitions)` : états locaux `S(a) = {0..levels-1}`.
- `Transition(automaton, from, to, conditions, dist)` : arc local conditionné.
- `LocalState(a, i)` : atome `a=i`.
- `Distribution` (D4) : `Exponential` (défaut), `Erlang`, `PhaseType`. Chaque
  distribution expose `mean` et `meanRate` ; l'expansion en phases dans la CTMC
  n'est déclenchée que pour les distributions non triviales.
- `Context` : états locaux initiaux possibles par automate (déterministe ou non).
- `Validation` : diagnostics (taux > 0, niveaux dans `S(a)`, préconditions bien
  formées).

## 3. Semi-anneaux (`core/semiring`)

Tout calcul de chemin est paramétré par `Semiring[T]` (conventions §9) :

| Instance   | `(⊕, ⊗)`   | Usage                         |
|------------|------------|-------------------------------|
| `Tropical` | `(min, +)` | délai au plus tôt `T(R)`      |
| `Viterbi`  | `(max, ×)` | probabilité du meilleur chemin|
| `ProbAgg`  | `(+, ×)`   | agrégation de probabilités    |
| `BoolReach`| `(∨, ∧)`   | atteignabilité qualitative    |

Les lois (associativité, distributivité, neutres, absorption) sont vérifiées par
property-based testing.

## 4. GLC / ⌈Gω_ς⌉ (`core/glc`)

Graphe orienté-but objectif → solution → sous-objectifs :

- un **objectif** `a=j` est relié aux **solutions** = chemins locaux acycliques de
  `a` vers `j` ;
- chaque solution est reliée aux **sous-objectifs** correspondant aux
  préconditions des transitions du chemin ;
- construction récursive mémoïsée, stoppée sur cycle (objectif en cours).

⚠ Source d'explosion (§10) : l'énumération des chemins locaux est bornée
(`maxPaths`, `maxLen`). Les versions algébriques (Dijkstra/Bellman-Ford, ZDD)
sont prévues en Phase 2.

## 5. Analyse (`analysis`)

### Atteignabilité (`Reachability`)

- **OA** — plus petit point fixe monotone des états locaux *possiblement*
  atteignables : on relâche la simultanéité des préconditions, donc l'ensemble
  sur-approxime le réel. `goal ∉ mayReach ⇒ inatteignable` (condition nécessaire).
- **UA** — *commitment DFS* : on cherche une assignation cohérente
  `automate → niveau` réalisant le but, en engageant chaque automate à un niveau
  unique. Toute solution est ordonnançable (les cycles de dépendance sont
  rejetés), donc réalisable concrètement ⇒ condition suffisante, avec témoin.

### Quantitatif (`Quantitative`, chap. 4)

- **`P(R)` exacte (CTMC absorbante locale, §6.5)** : la probabilité
  d'atteignabilité éventuelle est calculée par résolution de la **matrice
  fondamentale** sur les états atteignables du cône d'influence. Comme le cône
  est autonome (les transitions hors-cône sont du bégaiement n'affectant pas la
  probabilité éventuelle), cette probabilité est *exacte* pour le système complet.
  Les **cycles** sont traités nativement par le système linéaire `(I−P_TT)h=b`,
  sans énumérer les chemins. Si le cône dépasse le plafond d'états, repli sur une
  borne inférieure sound (`taux(t)/Λ` le long d'un scénario témoin) ⇒ dans tous
  les cas `binf P(R) ≤ P_exact`.
- **`T(R)` délai au plus tôt** : pour chaque solution, accumulation séquentielle
  du délai le long du chemin (préconditions parallèles) ; `min` sur les solutions.
- **`meanTime`** : temps moyen d'absorption issu de la CTMC, défini si `P(R) ≈ 1`.

La justesse est validée contre un **oracle exact** (BFS sur l'espace d'états) :
`UA ⇒ exact ⇒ OA`, et le contrôle croisé `binf statique ≤ P_CTMC`.

## 6. I/O (`io`)

- **ANX** : format texte canonique (parse/render), round-trip testé.
- **Pint `.an`** : importeur/exporteur (noms quotés, `when ... and ...`).
- **MaBoSS `.bnd`/`.cfg`** : oracle de référence (D3). La logique booléenne de
  chaque nœud est parsée (`BooleanExpr`), mise en DNF, puis chaque clause produit
  une transition `0→1` (et la négation les transitions `1→0`) ; les taux `$param`
  viennent du `.cfg`, les `istate` donnent le contexte initial.
- **DOT** : graphe de régulation.

## 7. CLI (`cli`)

Binaire `quasar` (decline). Aucune logique métier : parse + délègue. Sortie
humaine + `--json` stable. Groupes implémentés : `model`, `analyze`, `solver`.

## 8. Suite (roadmap)

Déjà livré au-delà des phases 0–1 : SCC/Tarjan, **CTMC absorbante locale via
matrice fondamentale**, `transform`, `topology`, `intervention`, exporteurs
NuSMV/Storm + adaptateurs sous-processus, `repo`, `tui`, validation MaBoSS.

Montée en charge livrée : CTMC **creuse + itérative** (Gauss-Seidel, plafond ~10⁵
états), **top-k** scénarios et **borne anytime** par trajectoires disjointes
(§6.7) en repli quand le cône dépasse le plafond.

Livré aussi : **expansion phase-type** (chaînes d'états fantômes, compétition
exacte) et **backend symbolique BDD** (`core/dd/Bdd` + `analysis/Symbolic`) pour
l'atteignabilité et les points fixes exacts sans énumération explicite (réseaux
booléens).

Livré aussi : **projection bioLQM** (`module biolqm`) — import SBML-qual / BoolNet /
GINML / booleannet via bioLQM (reconstruction des transitions unitaires depuis les
MDD) et export ANX → SBML-qual ; `model biolqm` signale les pertes (D1).

Reste à venir : extension du backend symbolique au **numérique** (MTBDD pour `P(R)`
symbolique) et aux multivalués (MDD), `transform abstract` (CEGAR), puis Phase 3
(bindings Python `pyquasar`, inférence de taux depuis séries temporelles).

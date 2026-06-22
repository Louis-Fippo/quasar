# QUASAR

**QU**antitative **A**nd **S**tatic **A**nalysis of **R**egulatory networks.

Plateforme d'**analyse statique** des propriétés **quantitatives** (probabilités
`P(R)`, délais `T(R)`) et **qualitatives** (atteignabilité) des **grands réseaux
d'automates biologiques**, sans construire l'espace d'états (qui explose
combinatoirement).

Fondations : interprétation abstraite (Cousot & Cousot), Graphes de Causalité
Locale (Pint/Paulevé), et le Graphe de Causalité Locale Quantifié `⌈Gω_ς⌉`
(chap. 4 de la thèse de L. Fippo Fitime). Sémantique concrète de référence :
chaîne de Markov à temps continu (CTMC) à taux exponentiels.

## Par où commencer

- **[Prise en main](getting-started.md)** — installation, build, premier modèle.
- **[Guide CLI](usage.md)** — référence exhaustive des commandes `quasar`.
- **[Conception](conception.md)** — architecture telle qu'implémentée.
- **[Bindings Python](python.md)** — `pyquasar` pour les notebooks.
- **[Référence API (Scaladoc)](api.md)** — types et fonctions du cœur Scala.

## Capacités en un coup d'œil

| Domaine | Capacités |
|---|---|
| **Atteignabilité** | OA (nécessaire) / UA (suffisante) ; exacte symbolique (BDD/MDD) sans énumération |
| **Quantitatif** | `P(R)` exacte (CTMC absorbante, matrice fondamentale) ou borne inf. sound ; délai `T(R)` ; temps moyen ; scénarios top-k |
| **Encadrement** | CEGAR qualitatif et **quantitatif** `[lo, hi]` raffiné par budget |
| **Topologie** | SCC, circuits signés (Thomas), points fixes, attracteurs, trap-spaces |
| **Intervention** | ensembles de coupe, mutations gain/perte de fonction |
| **Transformation** | réduction orientée-but, slice, booléanisation, expansion phase-type |
| **Symbolique** | BDD, MTBDD, MDD multivalué auto-contenus (sans dépendance native) |
| **I/O** | ANX, Pint `.an`, MaBoSS, DOT, NuSMV, PRISM/Storm, bioLQM (SBML-qual, BoolNet, GINML…) |
| **Oracles** | adaptateurs sous-processus NuSMV / Storm / MaBoSS |

## Garanties formelles

- **OA** (sur-approximation) : `OA = non` ⇒ inatteignable (condition nécessaire).
- **UA** (sous-approximation) : `UA = oui` ⇒ atteignable (condition suffisante).
- **`binf P(R) ≤ P_exact`** : la borne inférieure ne dépasse jamais la valeur
  exacte — invariant vérifié par property-based testing contre un oracle exact.

> **Justesse avant finesse** : une borne lâche mais correcte est acceptable ; une
> borne fausse ne l'est jamais.

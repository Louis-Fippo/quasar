# Bindings Python (`pyquasar`)

`pyquasar` expose les capacités de QUASAR à Python — pensé pour les notebooks
(CoLoMoTo). La JVM est chargée **en processus** via
[jpype](https://jpype.readthedocs.io) et la façade `io.quasar.py.Quasar` est
appelée directement ; chaque méthode renvoie un dictionnaire Python.

La façade est sans état et ne fait que charger, déléguer aux modules
`analysis`/`io`/`biolqm`, et sérialiser en JSON : aucune logique métier dupliquée.

## Installation

```bash
# 1. construire le fat-jar QUASAR
sbt "cli/assembly"           # -> cli/target/scala-3.3.4/quasar.jar
# 2. installer pyquasar (+ jpype)
pip install ./pyquasar
```

Le jar est auto-détecté (recherche ascendante depuis le package et le cwd) ;
sinon, préciser son chemin via la variable d'environnement `QUASAR_JAR` ou
l'argument `Quasar(jar=...)`.

## Utilisation

```python
from pyquasar import Quasar

q = Quasar()                                              # jar auto-détecté
q.info("bench/models/p53-mdm2.anx")
q.reachability("bench/models/p53-mdm2.anx", "Mdm2=1")
q.reachability_symbolic("bench/models/cellfate.bnd", "Apoptosis=1")
q.probability("bench/models/cellfate.bnd", "Apoptosis=1")  # P(R) exacte (CTMC)
q.bracket("bench/models/multivalued-demo.anx", "g=2")      # encadrement CEGAR [lo,hi]
q.fixpoints("bench/models/cellfate.bnd")
q.export("bench/models/p53-mdm2.anx", "/tmp/p53.sbml", "sbml")
```

Toutes les méthodes acceptent un contexte initial optionnel `frm="a=0,b=1"`.

## Méthodes

| Méthode Python | Façade JVM | Renvoie |
|---|---|---|
| `version()` | `version` | `{name, version}` |
| `info(path)` | `info` | `{name, automata, localStates, transitions, multivalued}` |
| `reachability(path, goal, frm="")` | `reachability` | `{goal, verdict, oaReachable, uaReachable}` |
| `reachability_symbolic(path, goal, frm="")` | `reachabilitySymbolic` | `{goal, reachable, reachableStates}` |
| `quantitative(path, goal, frm="")` | `quantitative` | `{goal, probability, probExact, earliestDelay, meanTime}` |
| `probability(path, goal, frm="")` | `quantitative` | idem (alias orienté `P(R)`) |
| `bracket(path, goal, frm="", budget=256)` | `bracket` | `{goal, lower, upper, exact}` |
| `fixpoints(path)` | `fixpoints` | `{truncated, count, fixpoints}` |
| `export(path, out, fmt="sbml")` | `exportModel` | `{ok, output}` |

En cas d'erreur (fichier absent, objectif mal formé…), le dictionnaire contient
une clé `error` avec le message.

## Tests

```bash
QUASAR_JAR=cli/target/scala-3.3.4/quasar.jar python -m pytest pyquasar/tests
```

Les tests sont ignorés proprement si `jpype` ou le jar sont absents.

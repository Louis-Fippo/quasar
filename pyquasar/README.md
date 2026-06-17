# pyquasar

Bindings Python pour [QUASAR](https://github.com/Louis-Fippo/quasar) — pensés pour
les notebooks (CoLoMoTo). La JVM est chargée en processus via
[jpype](https://jpype.readthedocs.io) et la façade `io.quasar.py.Quasar` est
appelée directement ; chaque méthode renvoie un dictionnaire Python.

## Prérequis

```bash
# 1. construire le fat-jar QUASAR
sbt "cli/assembly"           # -> cli/target/scala-3.3.4/quasar.jar
# 2. installer pyquasar (+ jpype)
pip install ./pyquasar
```

Le jar est auto-détecté (recherche ascendante) ; sinon préciser `QUASAR_JAR`.

## Utilisation

```python
from pyquasar import Quasar

q = Quasar()                                   # jar auto-détecté
q.info("bench/models/p53-mdm2.anx")
q.reachability("bench/models/p53-mdm2.anx", "Mdm2=1")
q.reachability_symbolic("bench/models/cellfate.bnd", "Apoptosis=1")
q.probability("bench/models/cellfate.bnd", "Apoptosis=1")     # P(R) exacte (CTMC)
q.bracket("bench/models/multivalued-demo.anx", "g=2")         # encadrement CEGAR [lo,hi]
q.fixpoints("bench/models/cellfate.bnd")
q.export("bench/models/p53-mdm2.anx", "/tmp/p53.sbml", "sbml")
```

Toutes les méthodes acceptent un contexte initial optionnel `frm="a=0,b=1"`.

## Test

```bash
QUASAR_JAR=cli/target/scala-3.3.4/quasar.jar python -m pytest pyquasar/tests
```

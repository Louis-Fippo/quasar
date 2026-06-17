"""pyquasar — bindings Python pour QUASAR (Phase 3).

Charge le fat-jar `quasar.jar` dans la JVM via jpype et expose la façade
`io.quasar.py.Quasar`. Chaque méthode renvoie un dictionnaire Python (JSON
désérialisé). Pensé pour les notebooks CoLoMoTo.

Exemple :
    from pyquasar import Quasar
    q = Quasar()                       # jar auto-détecté (ou QUASAR_JAR)
    q.info("bench/models/p53-mdm2.anx")
    q.reachability("bench/models/p53-mdm2.anx", "Mdm2=1")
    q.probability("bench/models/cellfate.bnd", "Apoptosis=1")
    q.bracket("bench/models/multivalued-demo.anx", "g=2")
"""

from __future__ import annotations

import glob
import json
import os

__all__ = ["Quasar", "find_jar"]

_FACADE = "io.quasar.py.Quasar"


def find_jar() -> str:
    """Localise le fat-jar QUASAR ($QUASAR_JAR, puis recherche ascendante)."""
    env = os.environ.get("QUASAR_JAR")
    if env and os.path.isfile(env):
        return env
    here = os.path.abspath(os.path.dirname(__file__))
    for base in (here, os.getcwd()):
        d = base
        for _ in range(6):
            hits = glob.glob(os.path.join(d, "cli", "target", "scala-*", "quasar.jar"))
            if hits:
                return hits[0]
            d = os.path.dirname(d)
    raise FileNotFoundError(
        "quasar.jar introuvable. Construire avec `sbt cli/assembly` ou définir $QUASAR_JAR."
    )


class Quasar:
    """Passerelle vers la façade JVM QUASAR (via jpype)."""

    def __init__(self, jar: str | None = None):
        import jpype  # import paresseux : jpype requis seulement à l'usage

        self._jar = jar or find_jar()
        if not jpype.isJVMStarted():
            # silence le StatusLogger de log4j2 (jSBML via bioLQM)
            jpype.startJVM(
                classpath=[self._jar],
                *["-Dlog4j2.statusLoggerLevel=OFF"],
            )
        self._facade = jpype.JClass(_FACADE)

    def _call(self, method: str, *args) -> dict:
        result = str(getattr(self._facade, method)(*args))
        return json.loads(result)

    # --- API ---------------------------------------------------------------

    def version(self) -> dict:
        return self._call("version")

    def info(self, path: str) -> dict:
        return self._call("info", path)

    def reachability(self, path: str, goal: str, frm: str = "") -> dict:
        return self._call("reachability", path, goal, frm)

    def reachability_symbolic(self, path: str, goal: str, frm: str = "") -> dict:
        return self._call("reachabilitySymbolic", path, goal, frm)

    def quantitative(self, path: str, goal: str, frm: str = "") -> dict:
        return self._call("quantitative", path, goal, frm)

    def probability(self, path: str, goal: str, frm: str = "") -> dict:
        """P(R) exacte (CTMC) + délai + temps moyen."""
        return self._call("quantitative", path, goal, frm)

    def bracket(self, path: str, goal: str, frm: str = "", budget: int = 256) -> dict:
        """Encadrement quantitatif sound [lo, hi] (CEGAR quantitatif)."""
        return self._call("bracket", path, goal, frm, int(budget))

    def fixpoints(self, path: str) -> dict:
        return self._call("fixpoints", path)

    def export(self, path: str, out: str, fmt: str = "sbml") -> dict:
        """Projette en bioLQM et exporte (sbml, bnet, ...)."""
        return self._call("exportModel", path, out, fmt)

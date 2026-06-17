"""Smoke test pyquasar (nécessite jpype + quasar.jar construit).

Ignoré proprement si jpype absent ou jar introuvable.
"""

import os

import pytest

jpype = pytest.importorskip("jpype")

from pyquasar import Quasar, find_jar  # noqa: E402

try:
    find_jar()
    _HAS_JAR = True
except FileNotFoundError:
    _HAS_JAR = False

pytestmark = pytest.mark.skipif(not _HAS_JAR, reason="quasar.jar non construit")


# modèles relatifs à la racine du dépôt
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
P53 = os.path.join(ROOT, "bench", "models", "p53-mdm2.anx")
CELLFATE = os.path.join(ROOT, "bench", "models", "cellfate.bnd")
MV = os.path.join(ROOT, "bench", "models", "multivalued-demo.anx")


@pytest.fixture(scope="module")
def q():
    return Quasar()


def test_version(q):
    assert q.version()["name"] == "quasar"


def test_info(q):
    info = q.info(P53)
    assert info["automata"] == 3
    assert info["transitions"] == 7


def test_reachability(q):
    r = q.reachability(P53, "Mdm2=1", "DNAdam=1,Mdm2=0,p53=0")
    assert r["verdict"] == "Reachable"


def test_probability_exact(q):
    p = q.probability(CELLFATE, "Apoptosis=1")
    assert abs(p["probability"] - 0.5) < 1e-9
    assert p["probExact"] is True


def test_bracket(q):
    br = q.bracket(MV, "g=2")
    assert br["lower"] <= 1.0 <= br["upper"] + 1e-9


def test_error_is_dict(q):
    assert "error" in q.info("/inexistant.anx")

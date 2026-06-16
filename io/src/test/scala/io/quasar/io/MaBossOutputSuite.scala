package io.quasar.io

class MaBossOutputSuite extends munit.FunSuite:

  // probtraj tabulé : 4 colonnes d'en-tête puis triplets (State, Proba, ErrorProba)
  private val probtraj =
    "Time\tTH\tErrorTH\tH\tState\tProba\tErrorProba\tState\tProba\tErrorProba\tState\tProba\tErrorProba\n" +
      "0\t0\t0\t0\t<nil>\t1\t0\n" +
      "100\t2.5\t0.01\t0.8\tApoptosis\t0.6\t0.01\tSurvival -- NFkB\t0.3\t0.01\t<nil>\t0.1\t0\n"

  test("parse probtraj : distribution au temps final") {
    val d = MaBossOutput.parseProbtraj(probtraj).fold(e => fail(e.toString), identity)
    assertEqualsDouble(d.probActive("Apoptosis"), 0.6, 1e-9)
    assertEqualsDouble(d.probActive("Survival"), 0.3, 1e-9)
    assertEqualsDouble(d.probActive("NFkB"), 0.3, 1e-9) // co-actif avec Survival
    assertEqualsDouble(d.probActive("Necrosis"), 0.0, 1e-9)
  }

  test("probabilités marginales dans [0,1]") {
    val d = MaBossOutput.parseProbtraj(probtraj).fold(e => fail(e.toString), identity)
    List("Apoptosis", "Survival", "NFkB").foreach { n =>
      val p = d.probActive(n)
      assert(p >= 0.0 && p <= 1.0 + 1e-9, s"$n -> $p")
    }
  }

  test("erreur claire si colonne State absente") {
    assert(MaBossOutput.parseProbtraj("Time\tTH\n0\t1\n").isLeft)
  }

  test("probtraj vide -> erreur") {
    assert(MaBossOutput.parseProbtraj("").isLeft)
  }

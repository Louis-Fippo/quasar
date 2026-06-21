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

  // --- série temporelle / temps d'atteinte (fiche V1) ----------------------

  // CDF marginale d'Apoptosis croissante : 0 -> 0.2 -> 0.6 -> 0.9
  private val series =
    "Time\tTH\tErrorTH\tH\tState\tProba\tErrorProba\tState\tProba\tErrorProba\n" +
      "0\t0\t0\t0\t<nil>\t1\t0\n" +
      "10\t1\t0\t0\tApoptosis\t0.2\t0\t<nil>\t0.8\t0\n" +
      "20\t1\t0\t0\tApoptosis -- Caspase3\t0.6\t0\t<nil>\t0.4\t0\n" +
      "50\t1\t0\t0\tApoptosis -- Caspase3\t0.9\t0\t<nil>\t0.1\t0\n"

  test("parseSeries : grille de temps et CDF marginale") {
    val s = MaBossOutput.parseSeries(series).fold(e => fail(e.toString), identity)
    assertEquals(s.times, Vector(0.0, 10.0, 20.0, 50.0))
    assertEquals(s.marginal("Apoptosis"), Vector(0.0, 0.2, 0.6, 0.9))
    assertEqualsDouble(s.finalProb("Apoptosis"), 0.9, 1e-9)
  }

  test("premier passage et quantiles de temps d'atteinte (H2)") {
    val s = MaBossOutput.parseSeries(series).fold(e => fail(e.toString), identity)
    assertEquals(s.firstPassage("Apoptosis", 0.5), Some(20.0)) // 0.6 ≥ 0.5 à t=20
    assertEquals(s.firstPassage("Apoptosis", 0.25), Some(20.0))
    assertEquals(s.firstPassage("Apoptosis", 0.95), None) // jamais atteint
    val q = s.quantiles("Apoptosis", Vector(0.5, 0.9, 0.95)).toMap
    assertEquals(q(0.5), Some(20.0))
    assertEquals(q(0.9), Some(50.0))
    assertEquals(q(0.95), None)
  }

  test("temps d'activation par nœud (H4)") {
    val s = MaBossOutput.parseSeries(series).fold(e => fail(e.toString), identity)
    val act = s.activationTimes(0.5)
    assertEquals(act.get("Apoptosis"), Some(20.0))
    assertEquals(act.get("Caspase3"), Some(20.0))
    assert(!act.contains("Necrosis")) // jamais activé
  }

  test("parseSeries : colonne State absente -> erreur") {
    assert(MaBossOutput.parseSeries("Time\tTH\n0\t1\n").isLeft)
  }

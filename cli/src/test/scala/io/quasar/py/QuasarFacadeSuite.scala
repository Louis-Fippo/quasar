package io.quasar.py

class QuasarFacadeSuite extends munit.FunSuite:

  private val p53 = "bench/models/p53-mdm2.anx"
  private val cellfate = "bench/models/cellfate.bnd"

  test("version renvoie du JSON") {
    assert(Quasar.version().contains("quasar"))
  }

  test("info : champs structurels") {
    val j = Quasar.info(p53)
    assert(j.contains("\"automata\":3"), j)
    assert(j.contains("\"transitions\":7"), j)
  }

  test("reachability : verdict ATTEIGNABLE") {
    val j = Quasar.reachability(p53, "Mdm2=1", "DNAdam=1,Mdm2=0,p53=0")
    assert(j.contains("\"verdict\":\"Reachable\""), j)
  }

  test("quantitative : P(R) exacte") {
    val j = Quasar.quantitative(cellfate, "Apoptosis=1", "")
    assert(j.contains("\"probability\":0.5"), j)
    assert(j.contains("\"probExact\":true"), j)
  }

  test("bracket : encadrement [lo,hi]") {
    val j = Quasar.bracket(cellfate, "Apoptosis=1", "", 256)
    assert(j.contains("\"lower\":0.5"), j)
    assert(j.contains("\"upper\":0.5"), j)
  }

  test("erreur sérialisée pour but invalide") {
    assert(Quasar.reachability(p53, "pas-un-objectif", "").contains("\"error\""))
  }

  test("erreur sérialisée pour fichier absent") {
    assert(Quasar.info("/inexistant.anx").contains("\"error\""))
  }

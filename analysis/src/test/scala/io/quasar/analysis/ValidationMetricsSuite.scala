package io.quasar.analysis

class ValidationMetricsSuite extends munit.FunSuite:

  import ValidationMetrics.*

  test("borne exacte : sound, tightness=1, relGap=0") {
    val b = Bounds(binf = 0.5, exact = 0.5)
    assert(b.sound())
    assertEqualsDouble(b.tightness, 1.0, 1e-12)
    assertEqualsDouble(b.relGap, 0.0, 1e-12)
  }

  test("borne lâche : sound, tightness<1, relGap>0") {
    val b = Bounds(binf = 0.2, exact = 0.8)
    assert(b.sound())
    assertEqualsDouble(b.tightness, 0.25, 1e-12)
    assertEqualsDouble(b.relGap, 0.75, 1e-12)
  }

  test("borne non sûre détectée (binf > exact)") {
    assert(!Bounds(binf = 0.9, exact = 0.5).sound())
  }

  test("exact nul : tightness=1, relGap=0 (pas de division par zéro)") {
    val b = Bounds(binf = 0.0, exact = 0.0)
    assertEqualsDouble(b.tightness, 1.0, 1e-12)
    assertEqualsDouble(b.relGap, 0.0, 1e-12)
  }

  test("Jaccard : recouvrement de nœuds") {
    assertEqualsDouble(jaccard(Set("a", "b", "c"), Set("b", "c", "d")), 0.5, 1e-12)
    assertEqualsDouble(jaccard(Set("a"), Set("a")), 1.0, 1e-12)
    assertEqualsDouble(jaccard(Set("a"), Set("b")), 0.0, 1e-12)
    assertEqualsDouble(jaccard(Set.empty, Set.empty), 1.0, 1e-12)
  }

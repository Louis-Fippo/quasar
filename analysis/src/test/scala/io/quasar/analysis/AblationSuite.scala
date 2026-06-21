package io.quasar.analysis

import io.quasar.core.Approx
import io.quasar.core.ir.*

/**
 * Invariant de l'ablation H6 (fiche A3) : les stratégies de calcul de `P(R)` réellement
 * implémentées — CTMC exact, MDD symbolique, encadrement CEGAR anytime — doivent **concorder** sur
 * la même valeur. C'est la garantie de « qualité » que `quasar bench ablation` met en regard du
 * temps.
 */
class AblationSuite extends munit.FunSuite:

  // course g:0->1 vs g:0->2 à taux égaux -> P(g=1) = 1/2 (états 1 et 2 absorbants)
  private val net = AutomataNetwork.of(
    Automaton(
      "g",
      3,
      List(
        Transition("g", 0, 1, Nil, Distribution.Exponential(1.0)),
        Transition("g", 0, 2, Nil, Distribution.Exponential(1.0))
      )
    )
  )
  private val ctx = Context.parse("g=0").toOption.get
  private val goal = LocalState("g", 1)

  test("CTMC, MDD et CEGAR concordent (référence 1/2)") {
    val ctmc = Quantitative.analyze(net, ctx, goal).probLowerBound.get
    assertEquals(ctmc.approx, Approx.Exact)
    assertEqualsDouble(ctmc.value, 0.5, 1e-9)

    val mdd = SymbolicMdd.reachProbability(net, ctx, goal).fold(e => fail(e), identity)
    assertEqualsDouble(mdd.reachProbability, 0.5, 1e-6)

    val br = QuantCegar.bracket(net, ctx, goal, budget = 256)
    assert(br.lower - 1e-9 <= 0.5 && 0.5 <= br.upper + 1e-9, s"[${br.lower}, ${br.upper}]")
  }

  test("CEGAR encadre la valeur exacte (lo ≤ exact ≤ hi)") {
    val exact = Quantitative.analyze(net, ctx, goal).probLowerBound.get.value
    val br = QuantCegar.bracket(net, ctx, goal, budget = 256)
    assert(br.lower - 1e-9 <= exact && exact <= br.upper + 1e-9)
  }

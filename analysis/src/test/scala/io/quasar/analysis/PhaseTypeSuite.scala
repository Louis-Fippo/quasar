package io.quasar.analysis

import io.quasar.core.ir.*
import io.quasar.core.solver.CtmcSolver

class PhaseTypeSuite extends munit.FunSuite:

  private def ctx(s: String) = Context.parse(s).toOption.get

  test("expansion : Erlang(2,λ) -> chaîne de 2 exponentielles via 1 état fantôme") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Erlang(2, 3.0))))
    )
    val exp = Transform.expandPhaseType(net).automaton("a").get
    assertEquals(exp.levels, 3) // 0, 1, + 1 fantôme
    assertEquals(exp.transitions.size, 2)
    assert(exp.transitions.forall(_.dist == Distribution.Exponential(3.0)))
    // chaîne 0 -> 2(fantôme) -> 1
    assertEquals(exp.transitions.map(t => (t.from, t.to)).toSet, Set((0, 2), (2, 1)))
  }

  test("réseau exponentiel : expansion = identité (référence)") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Exponential(1.0))))
    )
    assert(Transform.expandPhaseType(net) eq net)
  }

  test("compétition : la phase-type change P(R) (2/3 vs 0,5 en taux moyen)") {
    // g:0->1 Erlang(2,4) [but] en course avec g:0->2 Exp(2) [mort]
    val net = AutomataNetwork.of(
      Automaton(
        "g",
        3,
        List(
          Transition("g", 0, 1, Nil, Distribution.Erlang(2, 4.0)),
          Transition("g", 0, 2, Nil, Distribution.Exponential(2.0))
        )
      )
    )
    val goal = LocalState("g", 1)
    // approximation taux moyen (réseau brut) : 2/(2+2) = 0,5
    val approx = CtmcSolver.solve(net, ctx("g=0"), goal).get.reachProbability
    assertEqualsDouble(approx, 0.5, 1e-9)
    // expansion phase-type : 1ère phase (taux 4) gagne la course -> 4/(4+2) = 2/3
    val exact =
      CtmcSolver.solve(Transform.expandPhaseType(net), ctx("g=0"), goal).get.reachProbability
    assertEqualsDouble(exact, 2.0 / 3.0, 1e-9)
    // Quantitative applique l'expansion automatiquement
    assertEqualsDouble(
      Quantitative.analyze(net, ctx("g=0"), goal).probLowerBound.get.value,
      2.0 / 3.0,
      1e-9
    )
  }

  test("Erlang : même espérance que l'exponentielle de même taux moyen") {
    // E[T] doit valoir l'espérance Erlang = k/λ
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Erlang(3, 3.0))))
    )
    val q = Quantitative.analyze(net, ctx("a=0"), LocalState("a", 1))
    assertEqualsDouble(q.meanTime.get.value, 1.0, 1e-9) // 3/3 = 1
  }

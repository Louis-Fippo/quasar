package io.quasar.analysis

import io.quasar.core.ir.*
import io.quasar.core.solver.CtmcSolver

class ScenariosSuite extends munit.FunSuite:

  private def ctx(s: String) = Context.parse(s).toOption.get

  // a=0 -> 1 ou -> 3(mort) ; a=1 -> 0(retour) ou -> 2(but). P(but)=1/3.
  private val cyclic = AutomataNetwork.of(
    Automaton(
      "a",
      4,
      List(
        Transition("a", 0, 1, Nil, Distribution.Exponential(1.0)),
        Transition("a", 0, 3, Nil, Distribution.Exponential(1.0)),
        Transition("a", 1, 0, Nil, Distribution.Exponential(1.0)),
        Transition("a", 1, 2, Nil, Distribution.Exponential(1.0))
      )
    )
  )

  test("top-k : scénarios ordonnés par probabilité décroissante") {
    val scs = Scenarios.topK(cyclic, ctx("a=0"), LocalState("a", 2), 5)
    assert(scs.nonEmpty)
    val probs = scs.map(_.probability)
    assertEquals(probs, probs.sorted(Ordering[Double].reverse), "doit être décroissant")
    // scénario le plus court : a:0->1, a:1->2, proba 1/2 * 1/2 = 1/4
    assertEqualsDouble(scs.head.probability, 0.25, 1e-9)
  }

  test("borne anytime monotone et sound (≤ P_CTMC exacte = 1/3)") {
    val goal = LocalState("a", 2)
    val exact = CtmcSolver.solve(cyclic, ctx("a=0"), goal).get.reachProbability
    val b1 = Scenarios.anytimeLowerBound(cyclic, ctx("a=0"), goal, 1)
    val b4 = Scenarios.anytimeLowerBound(cyclic, ctx("a=0"), goal, 4)
    val b64 = Scenarios.anytimeLowerBound(cyclic, ctx("a=0"), goal, 64)
    assert(b1 <= b4 + 1e-12 && b4 <= b64 + 1e-12, s"monotone: $b1,$b4,$b64")
    assert(b64 <= exact + 1e-9, s"sound: $b64 > $exact")
    assert(b64 > 0.3, s"converge vers 1/3 : $b64") // somme géométrique des trajectoires
  }

  test("kind fastest : délai croissant") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Exponential(1.0)))),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1))))),
      Automaton(
        "g",
        3,
        List(
          Transition("g", 0, 1, List(LocalState("a", 1))), // court
          Transition("g", 0, 1, List(LocalState("b", 1))) // plus long (dépend de b)
        )
      )
    )
    val scs = Scenarios.topK(net, ctx("a=0,b=0,g=0"), LocalState("g", 1), 3, Scenarios.Kind.Fastest)
    val delays = scs.map(_.delay)
    assertEquals(delays, delays.sorted, "délais croissants")
  }

  test("phase-type : compétition exacte + chemin replié (états fantômes masqués)") {
    // g:0->1 Erlang(2,4) en course avec g:0->2 Exp(2) -> P(g=1)=2/3 (pas 1/2).
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
    val scs = Scenarios.topK(net, ctx("g=0"), LocalState("g", 1), 5)
    assert(scs.nonEmpty)
    // le scénario le plus probable atteint g=1 avec proba 2/3 (et non 1/2)
    assertEqualsDouble(scs.head.probability, 2.0 / 3.0, 1e-9)
    // aucun niveau fantôme (>= 3) ne doit apparaître dans le chemin affiché
    val niveaux = scs.flatMap(_.transitions).flatMap(t => List(t.from, t.to))
    assert(niveaux.forall(_ < 3), s"niveaux fantômes non repliés : $niveaux")
    // la transition logique g:0->1 est présente
    assert(scs.head.transitions.exists(t => t.automaton == "g" && t.from == 0 && t.to == 1))
  }

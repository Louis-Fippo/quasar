package io.quasar.core.solver

import io.quasar.core.ir.*

class CtmcSolverSuite extends munit.FunSuite:

  private def ctx(s: String) = Context.parse(s).toOption.get

  test("course exponentielle : P = λ/(λ+μ)") {
    // g=0 -> g=1 (but, taux 1) en compétition avec g=0 -> g=2 (mort, taux 3)
    val net = AutomataNetwork.of(
      Automaton(
        "g",
        3,
        List(
          Transition("g", 0, 1, Nil, Distribution.Exponential(1.0)),
          Transition("g", 0, 2, Nil, Distribution.Exponential(3.0))
        )
      )
    )
    val r = CtmcSolver.solve(net, ctx("g=0"), LocalState("g", 1)).get
    assertEqualsDouble(r.reachProbability, 0.25, 1e-9)
  }

  test("cycle avec échappement : P exacte malgré la boucle (1/3)") {
    // a=0 ->1 ou ->3(mort) ; a=1 ->0 (retour) ou ->2 (but). P(but)=1/3.
    val net = AutomataNetwork.of(
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
    val r = CtmcSolver.solve(net, ctx("a=0"), LocalState("a", 2)).get
    assertEqualsDouble(r.reachProbability, 1.0 / 3.0, 1e-9)
  }

  test("atteignabilité sûre : P=1 et temps moyen = 1/taux") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Exponential(2.0))))
    )
    val r = CtmcSolver.solve(net, ctx("a=0"), LocalState("a", 1)).get
    assertEqualsDouble(r.reachProbability, 1.0, 1e-12)
    assertEqualsDouble(r.expectedTime.get, 0.5, 1e-12)
  }

  test("but déjà initial : P=1, temps 0") {
    val net = AutomataNetwork.of(Automaton("a", 2, Nil))
    val r = CtmcSolver.solve(net, ctx("a=1"), LocalState("a", 1)).get
    assertEqualsDouble(r.reachProbability, 1.0, 1e-12)
    assertEqualsDouble(r.expectedTime.get, 0.0, 1e-12)
  }

  test("but inatteignable : P=0") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, Nil),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    val r = CtmcSolver.solve(net, ctx("a=0,b=0"), LocalState("b", 1)).get
    assertEqualsDouble(r.reachProbability, 0.0, 1e-12)
  }

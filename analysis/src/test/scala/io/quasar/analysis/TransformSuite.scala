package io.quasar.analysis

import io.quasar.core.ir.*

class TransformSuite extends munit.FunSuite:

  // c est hors du cône d'influence de b
  private val net = AutomataNetwork.of(
    Automaton("a", 2, List(Transition("a", 0, 1))),
    Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1))))),
    Automaton("c", 2, List(Transition("c", 0, 1, List(LocalState("b", 1)))))
  )

  test("reduce conserve le cône et préserve l'atteignabilité") {
    val r = Transform.reduce(net, LocalState("b", 1))
    assertEquals(r.automata.keySet, Set("a", "b"))
    val ctx = Context.parse("a=0,b=0,c=0").toOption.get
    val ctxR = Context.parse("a=0,b=0").toOption.get
    assertEquals(
      Reachability.analyze(net, ctx, LocalState("b", 1)).uaReachable,
      Reachability.analyze(r, ctxR, LocalState("b", 1)).uaReachable
    )
  }

  test("slice autour de c inclut ses régulateurs transitifs") {
    val r = Transform.slice(net, "c")
    assertEquals(r.automata.keySet, Set("a", "b", "c"))
  }

  test("slice autour de a (sans régulateur) = {a}") {
    val r = Transform.slice(net, "a")
    assertEquals(r.automata.keySet, Set("a"))
  }

  test("booleanize : no-op sur réseau booléen") {
    assert(Transform.booleanize(net).isRight)
  }

  test("booleanize : échoue proprement sur multivalué") {
    val mv = AutomataNetwork.of(Automaton("x", 3, Nil))
    assert(Transform.booleanize(mv).isLeft)
  }

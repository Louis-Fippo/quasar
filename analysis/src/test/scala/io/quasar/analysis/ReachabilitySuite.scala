package io.quasar.analysis

import io.quasar.core.Verdict
import io.quasar.core.ir.*

class ReachabilitySuite extends munit.FunSuite:

  // a libre 0->1 ; b: 0->1 quand a=1
  private val chain = AutomataNetwork.of(
    Automaton("a", 2, List(Transition("a", 0, 1))),
    Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
  )
  private val init = Context.parse("a=0,b=0").toOption.get

  test("but déjà initial : atteignable trivialement") {
    val r = Reachability.analyze(chain, init, LocalState("a", 0))
    assertEquals(r.verdict, Verdict.Reachable)
  }

  test("chaîne de dépendance : b=1 atteignable (OA et UA)") {
    val r = Reachability.analyze(chain, init, LocalState("b", 1))
    assert(r.oaReachable)
    assert(r.uaReachable, "UA doit trouver un témoin")
    assertEquals(r.verdict, Verdict.Reachable)
    assert(r.witness.get.transitions.nonEmpty)
  }

  test("but impossible : OA conclut inatteignable") {
    // a ne peut jamais changer -> la précondition a=1 est inatteignable
    val net = AutomataNetwork.of(
      Automaton("a", 2, Nil),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    val r = Reachability.analyze(net, Context.parse("a=0,b=0").toOption.get, LocalState("b", 1))
    assert(!r.oaReachable)
    assertEquals(r.verdict, Verdict.Unreachable)
  }

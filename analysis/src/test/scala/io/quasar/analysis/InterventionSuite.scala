package io.quasar.analysis

import io.quasar.core.ir.*

class InterventionSuite extends munit.FunSuite:

  private val chain = AutomataNetwork.of(
    Automaton("a", 2, List(Transition("a", 0, 1))),
    Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
  )
  private val init = Context.parse("a=0,b=0").toOption.get

  test("cutset : bloquer a=1 rend b=1 inatteignable") {
    val cs = Intervention.cutsets(chain, init, LocalState("b", 1))
    assert(cs.contains(Set(LocalState("a", 1))), cs.toString)
  }

  test("cutsets sont effectifs (justesse)") {
    val goal = LocalState("b", 1)
    val cs = Intervention.cutsets(chain, init, goal)
    cs.foreach { s =>
      assert(!Reachability.mayReachBlocked(chain, init, s).contains(goal), s"$s ne coupe pas")
    }
  }

  test("mutation disable : figer a=0 rend b=1 inatteignable") {
    val ms = Intervention.mutations(chain, init, LocalState("b", 1), enable = false)
    assert(ms.exists(_.target == LocalState("a", 0)), ms.toString)
    assert(ms.forall(!_.makesReachable))
  }

  test("mutation enable : aucune nécessaire si déjà atteignable") {
    val ms = Intervention.mutations(chain, init, LocalState("b", 1), enable = true)
    assertEquals(ms, Nil)
  }

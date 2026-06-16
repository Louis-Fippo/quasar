package io.quasar.analysis

import io.quasar.core.glc.Sign
import io.quasar.core.ir.*

class TopologySuite extends munit.FunSuite:

  // Rétroaction négative : a active b, b inhibe a (oscillateur).
  private val osc = AutomataNetwork.of(
    Automaton(
      "a",
      2,
      List(
        Transition("a", 0, 1, List(LocalState("b", 0))),
        Transition("a", 1, 0, List(LocalState("b", 1)))
      )
    ),
    Automaton(
      "b",
      2,
      List(
        Transition("b", 0, 1, List(LocalState("a", 1))),
        Transition("b", 1, 0, List(LocalState("a", 0)))
      )
    )
  )

  test("SCC : {a,b} en rétroaction") {
    val comps = Topology.scc(osc)
    assertEquals(comps.map(_.toSet).toSet, Set(Set("a", "b")))
  }

  test("circuit négatif unique") {
    val cs = Topology.circuits(osc)
    assertEquals(cs.size, 1)
    assertEquals(cs.head.sign, Sign.Negative)
    assertEquals(Topology.circuits(osc, Some(Sign.Positive)), Nil)
  }

  test("aucun point fixe (oscillateur)") {
    assertEquals(Topology.fixpoints(osc).items, Nil)
  }

  test("un attracteur cyclique couvrant les 4 états") {
    val r = Topology.attractors(osc)
    assert(!r.truncated)
    assertEquals(r.items.size, 1)
    assertEquals(r.items.head.size, 4)
  }

  test("point fixe détecté sur réseau convergent") {
    // a -> 1 (libre), b suit a ; état stable (1,1)
    val conv = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1))),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    val fps = Topology.fixpoints(conv).items
    assert(fps.contains(Map("a" -> 1, "b" -> 1)), fps.toString)
  }

  test("trap-spaces minimaux") {
    val r = Topology.trapSpaces(osc, minimalOnly = true)
    assert(!r.truncated)
    // seul l'espace complet est clos pour un oscillateur
    assertEquals(r.items, List(Map.empty[String, Int]))
  }

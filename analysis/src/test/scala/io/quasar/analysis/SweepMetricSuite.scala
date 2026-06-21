package io.quasar.analysis

import io.quasar.core.ir.*

/**
 * Sémantique des métriques de balayage `bench sweep` (fiche A2, sans objectif) : nombre d'états
 * atteignables et nombre de points fixes — c'est l'axe « taille » de la courbe de scalabilité H5.
 */
class SweepMetricSuite extends munit.FunSuite:

  // course g:0->1 vs g:0->2 : 3 états (0,1,2), 2 points fixes absorbants (1 et 2)
  private val net = AutomataNetwork.of(
    Automaton("g", 3, List(Transition("g", 0, 1), Transition("g", 0, 2)))
  )
  private val ctx = Context.parse("g=0").toOption.get

  test("reachableStates = taille de l'ensemble atteignable (indépendant de l'objectif)") {
    val r0 = SymbolicMdd.reachability(net, ctx, LocalState("g", 0)).reachableStates
    val r1 = SymbolicMdd.reachability(net, ctx, LocalState("g", 1)).reachableStates
    assertEquals(r0, 3L)
    assertEquals(r1, 3L) // le compte ne dépend pas de l'objectif
  }

  test("fixpointCount = nombre d'états absorbants") {
    assertEquals(SymbolicMdd.fixpointCount(net), 2L)
  }

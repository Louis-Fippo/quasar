package io.quasar.analysis

import io.quasar.core.ir.*

class QuantitativeSuite extends munit.FunSuite:

  // chaîne causale a -> b -> c, chaque transition de délai moyen 1
  private val chain = AutomataNetwork.of(
    Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Exponential(1.0)))),
    Automaton(
      "b",
      2,
      List(Transition("b", 0, 1, List(LocalState("a", 1)), Distribution.Exponential(1.0)))
    ),
    Automaton(
      "c",
      2,
      List(Transition("c", 0, 1, List(LocalState("b", 1)), Distribution.Exponential(1.0)))
    )
  )
  private val init = Context.parse("a=0,b=0,c=0").toOption.get

  test("délai au plus tôt s'accumule le long de la chaîne causale") {
    // a:1, puis b:2, puis c:3 (séquentiel) — et non max=1
    assertEqualsDouble(
      Quantitative.earliestDelay(chain, init, LocalState("c", 1)).get.value,
      3.0,
      1e-9
    )
    assertEqualsDouble(
      Quantitative.earliestDelay(chain, init, LocalState("b", 1)).get.value,
      2.0,
      1e-9
    )
    assertEqualsDouble(
      Quantitative.earliestDelay(chain, init, LocalState("a", 1)).get.value,
      1.0,
      1e-9
    )
  }

  test("délai infini -> None si inatteignable") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, Nil),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    assertEquals(
      Quantitative.earliestDelay(net, Context.parse("a=0,b=0").toOption.get, LocalState("b", 1)),
      None
    )
  }

  test("borne inf. P(R) ≤ 1 et témoin présent quand atteignable") {
    val q = Quantitative.analyze(chain, init, LocalState("c", 1))
    assert(q.probLowerBound.exists(b => b.value > 0 && b.value <= 1.0))
    assertEquals(q.scenario.size, 3) // a, b, c
  }

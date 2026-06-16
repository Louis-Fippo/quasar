package io.quasar.analysis

import io.quasar.core.ir.*
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class SymbolicSuite extends munit.ScalaCheckSuite:

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

  private val chain = AutomataNetwork.of(
    Automaton("a", 2, List(Transition("a", 0, 1))),
    Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
  )

  test("oscillateur : 4 états atteignables, but atteignable") {
    val r = Symbolic
      .reachability(osc, Context.parse("a=0,b=0").toOption.get, LocalState("a", 1))
      .fold(e => fail(e), identity)
    assertEquals(r.reachableStates, 4L)
    assert(r.goalReachable)
  }

  test("chaîne : 3 états atteignables (pas (0,1))") {
    val r = Symbolic
      .reachability(chain, Context.parse("a=0,b=0").toOption.get, LocalState("b", 1))
      .fold(e => fail(e), identity)
    assertEquals(r.reachableStates, 3L)
    assert(r.goalReachable)
  }

  test("réseau multivalué refusé proprement") {
    val mv = AutomataNetwork.of(Automaton("x", 3, Nil))
    assert(Symbolic.reachability(mv, Context.empty, LocalState("x", 1)).isLeft)
  }

  test("points fixes symboliques = énumération (chaîne convergente)") {
    val conv = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1))),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    val symb = Symbolic.fixpointCount(conv).fold(e => fail(e), identity)
    assertEquals(symb, Topology.fixpoints(conv).items.size.toLong)
  }

  // --- cross-check symbolique vs explicite ---------------------------------

  private val genNet: Gen[AutomataNetwork] =
    for
      k <- Gen.choose(2, 3)
      names = (0 until k).map(i => s"x$i").toList
      autos <- Gen.sequence[List[Automaton], Automaton](names.map { n =>
        for
          nt <- Gen.choose(0, 3)
          ts <- Gen.listOfN(nt, genTransition(n, names))
        yield Automaton(n, 2, ts)
      })
    yield AutomataNetwork.of(autos*)

  private def genTransition(name: String, all: List[String]): Gen[Transition] =
    for
      from <- Gen.oneOf(0, 1)
      others = all.filterNot(_ == name)
      nc <- Gen.choose(0, others.size)
      chosen <- Gen.pick(nc, others)
      conds <- Gen.sequence[List[LocalState], LocalState](
        chosen.toList.map(o => Gen.oneOf(0, 1).map(LocalState(o, _)))
      )
    yield Transition(name, from, 1 - from, conds)

  property("symbolique = oracle explicite (atteignabilité + #états)") {
    val gen = for
      net <- genNet
      a <- Gen.oneOf(net.automata.keys.toList)
      lvl <- Gen.oneOf(0, 1)
    yield (net, LocalState(a, lvl))
    forAll(gen) { case (net, goal) =>
      val ctx = Context(net.automata.keys.map(_ -> Set(0)).toMap)
      val r = Symbolic.reachability(net, ctx, goal).fold(e => fail(e), identity)
      Prop(
        r.goalReachable == ExactOracle.reachable(net, ctx, goal) &&
          r.reachableStates == ExactOracle.reachableCount(net, ctx)
      ).label(s"symb≠exact: $goal états=${r.reachableStates}")
    }
  }

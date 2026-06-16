package io.quasar.analysis

import io.quasar.core.ir.*
import io.quasar.core.solver.CtmcSolver
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class SymbolicMddSuite extends munit.ScalaCheckSuite:

  private def ctx(s: String) = Context.parse(s).toOption.get

  // automate à 3 niveaux : chaîne 0->1->2
  private val chain3 = AutomataNetwork.of(
    Automaton(
      "a",
      3,
      List(
        Transition("a", 0, 1, Nil, Distribution.Exponential(1.0)),
        Transition("a", 1, 2, Nil, Distribution.Exponential(1.0))
      )
    )
  )

  test("atteignabilité multivaluée : 3 états, but a=2 atteignable") {
    val r = SymbolicMdd.reachability(chain3, ctx("a=0"), LocalState("a", 2))
    assertEquals(r.reachableStates, 3L)
    assert(r.goalReachable)
  }

  test("P(R) multivaluée : course a:0->1 (1) vs a:0->2 (3) -> 1/4") {
    val race = AutomataNetwork.of(
      Automaton(
        "a",
        3,
        List(
          Transition("a", 0, 1, Nil, Distribution.Exponential(1.0)),
          Transition("a", 0, 2, Nil, Distribution.Exponential(3.0))
        )
      )
    )
    val p = SymbolicMdd
      .reachProbability(race, ctx("a=0"), LocalState("a", 1))
      .fold(e => fail(e), identity)
    assertEqualsDouble(p.reachProbability, 0.25, 1e-9)
    // cohérence avec le solveur explicite
    assertEqualsDouble(
      CtmcSolver.solve(race, ctx("a=0"), LocalState("a", 1)).get.reachProbability,
      0.25,
      1e-9
    )
  }

  test("phase-type refusé pour P(R)") {
    val pt = AutomataNetwork.of(
      Automaton("a", 3, List(Transition("a", 0, 1, Nil, Distribution.Erlang(2, 1.0))))
    )
    assert(SymbolicMdd.reachProbability(pt, ctx("a=0"), LocalState("a", 1)).isLeft)
  }

  // --- cross-check MDD vs oracle explicite sur réseaux multivalués ----------

  private val genNet: Gen[AutomataNetwork] =
    for
      k <- Gen.choose(2, 3)
      names = (0 until k).map(i => s"x$i").toList
      levels <- Gen
        .sequence[List[(String, Int)], (String, Int)](
          names.map(n => Gen.choose(2, 3).map(n -> _))
        )
        .map(_.toMap)
      autos <- Gen.sequence[List[Automaton], Automaton](
        names.map(n => genAutomaton(n, levels))
      )
    yield AutomataNetwork.of(autos*)

  private def genAutomaton(name: String, levels: Map[String, Int]): Gen[Automaton] =
    val k = levels(name)
    for
      nt <- Gen.choose(0, 3)
      ts <- Gen.listOfN(nt, genTransition(name, levels))
    yield Automaton(name, k, ts)

  private def genTransition(name: String, levels: Map[String, Int]): Gen[Transition] =
    val k = levels(name)
    for
      from <- Gen.choose(0, k - 1)
      to <- Gen.choose(0, k - 1).suchThat(_ != from)
      others = (levels.keySet - name).toList
      nc <- Gen.choose(0, others.size)
      chosen <- Gen.pick(nc, others)
      conds <- Gen.sequence[List[LocalState], LocalState](
        chosen.toList.map(o => Gen.choose(0, levels(o) - 1).map(LocalState(o, _)))
      )
      rate <- Gen.choose(0.5, 4.0)
    yield Transition(name, from, to, conds, Distribution.Exponential(rate))

  property("MDD : atteignabilité + #états = oracle explicite") {
    val gen = for
      net <- genNet
      a <- Gen.oneOf(net.automata.keys.toList)
      lvl <- Gen.choose(0, net.automaton(a).get.levels - 1)
    yield (net, LocalState(a, lvl))
    forAll(gen) { case (net, goal) =>
      val c = Context(net.automata.map((n, au) => n -> Set(0)).toMap)
      val r = SymbolicMdd.reachability(net, c, goal)
      Prop(
        r.goalReachable == ExactOracle.reachable(net, c, goal) &&
          r.reachableStates == ExactOracle.reachableCount(net, c)
      ).label(s"MDD≠exact: $goal états=${r.reachableStates}")
    }
  }

  property("MDD : P(R) = CTMC explicite (multivalué)") {
    val gen = for
      net <- genNet
      a <- Gen.oneOf(net.automata.keys.toList)
      lvl <- Gen.choose(0, net.automaton(a).get.levels - 1)
    yield (net, LocalState(a, lvl))
    forAll(gen) { case (net, goal) =>
      val c = Context(net.automata.map((n, au) => n -> Set(0)).toMap)
      val symb = SymbolicMdd.reachProbability(net, c, goal).fold(e => fail(e), _.reachProbability)
      val expl = CtmcSolver.solve(net, c, goal).map(_.reachProbability).getOrElse(symb)
      Prop(math.abs(symb - expl) < 1e-6).label(s"P MDD=$symb explicite=$expl goal=$goal")
    }
  }

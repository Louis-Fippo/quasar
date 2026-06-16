package io.quasar.analysis

import io.quasar.core.ir.*
import io.quasar.core.solver.CtmcSolver
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class SymbolicCtmcSuite extends munit.ScalaCheckSuite:

  private def ctx(s: String) = Context.parse(s).toOption.get

  // a:0->1 quand b=0 (taux 1) en course avec b:0->1 (taux 2). P(a=1)=1/3.
  private def race(rateB: Double) = AutomataNetwork.of(
    Automaton(
      "a",
      2,
      List(Transition("a", 0, 1, List(LocalState("b", 0)), Distribution.Exponential(1.0)))
    ),
    Automaton("b", 2, List(Transition("b", 0, 1, Nil, Distribution.Exponential(rateB))))
  )

  test("course booléenne : P(a=1) = 1/(1+rateB)") {
    val r = SymbolicCtmc
      .reachProbability(race(2.0), ctx("a=0,b=0"), LocalState("a", 1))
      .fold(e => fail(e), identity)
    assertEqualsDouble(r.reachProbability, 1.0 / 3.0, 1e-9)
  }

  test("but déjà initial : P=1 ; inatteignable : P=0") {
    val net = race(1.0)
    val p1 = SymbolicCtmc
      .reachProbability(net, ctx("a=1,b=0"), LocalState("a", 1))
      .fold(e => fail(e), identity)
    assertEqualsDouble(p1.reachProbability, 1.0, 1e-9)
    // depuis b=1 : a:0->1 (besoin b=0) jamais tirable -> P=0
    val p0 = SymbolicCtmc
      .reachProbability(net, ctx("a=0,b=1"), LocalState("a", 1))
      .fold(e => fail(e), identity)
    assertEqualsDouble(p0.reachProbability, 0.0, 1e-9)
  }

  test("multivalué / phase-type refusés proprement") {
    val mv = AutomataNetwork.of(Automaton("x", 3, Nil))
    assert(SymbolicCtmc.reachProbability(mv, Context.empty, LocalState("x", 1)).isLeft)
    val pt = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Erlang(2, 1.0))))
    )
    assert(SymbolicCtmc.reachProbability(pt, ctx("a=0"), LocalState("a", 1)).isLeft)
  }

  // --- cross-check symbolique (MTBDD) vs explicite (CTMC creuse) ------------

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
      rate <- Gen.choose(0.5, 4.0)
    yield Transition(name, from, 1 - from, conds, Distribution.Exponential(rate))

  property("P(R) MTBDD = P(R) CTMC explicite") {
    val gen = for
      net <- genNet
      a <- Gen.oneOf(net.automata.keys.toList)
      lvl <- Gen.oneOf(0, 1)
    yield (net, LocalState(a, lvl))
    forAll(gen) { case (net, goal) =>
      val c = Context(net.automata.keys.map(_ -> Set(0)).toMap)
      val symb = SymbolicCtmc.reachProbability(net, c, goal).fold(e => fail(e), _.reachProbability)
      val expl = CtmcSolver.solve(net, c, goal).map(_.reachProbability).getOrElse(symb)
      Prop(math.abs(symb - expl) < 1e-6)
        .label(s"symb=$symb explicite=$expl goal=$goal")
    }
  }

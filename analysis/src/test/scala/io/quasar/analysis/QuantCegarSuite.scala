package io.quasar.analysis

import io.quasar.core.ir.*
import io.quasar.core.solver.CtmcSolver
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class QuantCegarSuite extends munit.ScalaCheckSuite:

  private def ctx(s: String) = Context.parse(s).toOption.get

  test("course booléenne : encadrement exact [1/3, 1/3]") {
    val net = AutomataNetwork.of(
      Automaton(
        "a",
        2,
        List(Transition("a", 0, 1, List(LocalState("b", 0)), Distribution.Exponential(1.0)))
      ),
      Automaton("b", 2, List(Transition("b", 0, 1, Nil, Distribution.Exponential(2.0))))
    )
    val br = QuantCegar.bracket(net, ctx("a=0,b=0"), LocalState("a", 1))
    assertEqualsDouble(br.lower, 1.0 / 3.0, 1e-9)
    assertEqualsDouble(br.upper, 1.0 / 3.0, 1e-9)
    assert(br.exact())
  }

  test("course multivaluée : encadrement exact [1/4, 1/4]") {
    val net = AutomataNetwork.of(
      Automaton(
        "a",
        3,
        List(
          Transition("a", 0, 1, Nil, Distribution.Exponential(1.0)),
          Transition("a", 0, 2, Nil, Distribution.Exponential(3.0))
        )
      )
    )
    val br = QuantCegar.bracket(net, ctx("a=0"), LocalState("a", 1))
    assertEqualsDouble(br.lower, 0.25, 1e-9)
    assertEqualsDouble(br.upper, 0.25, 1e-9)
  }

  test("phase-type : encadrement sound (Erlang(2,4) vs Exp(2) -> 2/3, pas 1/2)") {
    // l'expansion phase-type doit s'appliquer : sans elle, l'approximation par
    // taux moyen donnerait [1/2, 1/2], qui EXCLUT la vraie valeur 2/3 (non sound).
    val net = AutomataNetwork.of(
      Automaton(
        "g",
        3,
        List(
          Transition("g", 0, 1, Nil, Distribution.Erlang(2, 4.0)),
          Transition("g", 0, 2, Nil, Distribution.Exponential(2.0))
        )
      )
    )
    val br = QuantCegar.bracket(net, ctx("g=0"), LocalState("g", 1))
    assert(
      br.lower - 1e-9 <= 2.0 / 3.0 && 2.0 / 3.0 <= br.upper + 1e-9,
      s"2/3 hors de [${br.lower}, ${br.upper}]"
    )
    assertEqualsDouble(br.upper, 2.0 / 3.0, 1e-6) // encadrement exact attendu
  }

  test("but déjà atteint / inatteignable") {
    val net = AutomataNetwork.of(Automaton("a", 2, List(Transition("a", 0, 1))))
    val hit = QuantCegar.bracket(net, ctx("a=1"), LocalState("a", 1))
    assertEqualsDouble(hit.lower, 1.0, 1e-9); assertEqualsDouble(hit.upper, 1.0, 1e-9)
    val no = AutomataNetwork.of(
      Automaton("a", 2, Nil),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    val br = QuantCegar.bracket(no, ctx("a=0,b=0"), LocalState("b", 1))
    assertEqualsDouble(br.upper, 0.0, 1e-9) // borne sup nulle => P=0
  }

  // --- soundness : lo ≤ P_exact ≤ hi ---------------------------------------

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

  property("encadrement sound : lower ≤ P_exact ≤ upper") {
    val gen = for
      net <- genNet
      a <- Gen.oneOf(net.automata.keys.toList)
      lvl <- Gen.oneOf(0, 1)
    yield (net, LocalState(a, lvl))
    forAll(gen) { case (net, goal) =>
      val c = Context(net.automata.map((n, _) => n -> Set(0)).toMap)
      val exact = CtmcSolver.solve(net, c, goal).map(_.reachProbability)
      val br = QuantCegar.bracket(net, c, goal, budget = 512)
      exact match
        case None => Prop(true)
        case Some(p) =>
          Prop(br.lower <= p + 1e-9 && p <= br.upper + 1e-9 && br.lower <= br.upper + 1e-9)
            .label(s"bracket=[${br.lower},${br.upper}] exact=$p goal=$goal")
    }
  }

package io.quasar.analysis

import io.quasar.core.Verdict
import io.quasar.core.glc.Cone
import io.quasar.core.ir.*
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class CegarSuite extends munit.FunSuite with munit.ScalaCheckSuite:

  private def ctx(s: String) = Context.parse(s).toOption.get

  test("décision immédiate (atteignable) : but interne, |visible|=1") {
    // a:0->1 sans condition ; c,d hors du cône d'influence de a
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1))),
      Automaton("c", 2, List(Transition("c", 0, 1, List(LocalState("a", 1))))),
      Automaton("d", 2, List(Transition("d", 0, 1)))
    )
    val r = Cegar.reachability(net, ctx("a=0,c=0,d=0"), LocalState("a", 1))
    assertEquals(r.verdict, Verdict.Reachable)
    assertEquals(r.visible, Set("a")) // abstraction minuscule vs 3 automates
    assertEquals(r.rounds, 0)
  }

  test("raffinement puis inatteignable") {
    // a figé à 0 ; b:0->1 quand a=1 -> b=1 inatteignable
    val net = AutomataNetwork.of(
      Automaton("a", 2, Nil),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    val r = Cegar.reachability(net, ctx("a=0,b=0"), LocalState("b", 1))
    assertEquals(r.verdict, Verdict.Unreachable)
    assert(r.rounds >= 1) // a abandonné puis raffiné
  }

  test("visible ⊆ cône d'influence") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1))),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    val r = Cegar.reachability(net, ctx("a=0,b=0"), LocalState("b", 1))
    assert(r.visible.subsetOf(Cone.of(net, "b")))
    assertEquals(r.verdict, Verdict.Reachable)
  }

  // --- cross-check : CEGAR = oracle explicite ------------------------------

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

  property("CEGAR décide comme l'oracle explicite") {
    val gen = for
      net <- genNet
      a <- Gen.oneOf(net.automata.keys.toList)
      lvl <- Gen.oneOf(0, 1)
    yield (net, LocalState(a, lvl))
    forAll(gen) { case (net, goal) =>
      val c = Context(net.automata.map((n, _) => n -> Set(0)).toMap)
      val r = Cegar.reachability(net, c, goal)
      val reachable = r.verdict == Verdict.Reachable
      Prop(
        reachable == ExactOracle.reachable(net, c, goal) &&
          r.visible.subsetOf(Cone.of(net, goal.automaton))
      ).label(s"CEGAR=$r exact=${ExactOracle.reachable(net, c, goal)}")
    }
  }

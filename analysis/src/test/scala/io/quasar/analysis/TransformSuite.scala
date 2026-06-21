package io.quasar.analysis

import io.quasar.core.ir.*

class TransformSuite extends munit.FunSuite:

  // c est hors du cône d'influence de b
  private val net = AutomataNetwork.of(
    Automaton("a", 2, List(Transition("a", 0, 1))),
    Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1))))),
    Automaton("c", 2, List(Transition("c", 0, 1, List(LocalState("b", 1)))))
  )

  test("reduce conserve le cône et préserve l'atteignabilité") {
    val r = Transform.reduce(net, LocalState("b", 1))
    assertEquals(r.automata.keySet, Set("a", "b"))
    val ctx = Context.parse("a=0,b=0,c=0").toOption.get
    val ctxR = Context.parse("a=0,b=0").toOption.get
    assertEquals(
      Reachability.analyze(net, ctx, LocalState("b", 1)).uaReachable,
      Reachability.analyze(r, ctxR, LocalState("b", 1)).uaReachable
    )
  }

  test("slice autour de c inclut ses régulateurs transitifs") {
    val r = Transform.slice(net, "c")
    assertEquals(r.automata.keySet, Set("a", "b", "c"))
  }

  test("slice autour de a (sans régulateur) = {a}") {
    val r = Transform.slice(net, "a")
    assertEquals(r.automata.keySet, Set("a"))
  }

  test("booleanize : no-op sur réseau booléen") {
    assert(Transform.booleanize(net).isRight)
  }

  test("booleanize : échoue proprement sur multivalué") {
    val mv = AutomataNetwork.of(Automaton("x", 3, Nil))
    assert(Transform.booleanize(mv).isLeft)
  }

  // --- assign-rates (fiche P1) ---------------------------------------------

  // réseau qualitatif (taux par défaut) avec plusieurs transitions
  private val qual = AutomataNetwork.of(
    Automaton("g", 3, List(Transition("g", 0, 1), Transition("g", 1, 2, List(LocalState("r", 1))))),
    Automaton("r", 2, List(Transition("r", 0, 1), Transition("r", 1, 0)))
  )

  test("assign-rates unit : toutes les transitions à Exponential(1.0)") {
    val r = Transform.assignRates(qual, Transform.RatePolicy.Unit)
    val dists = r.transitions.map(_.dist).toSet
    assertEquals(dists, Set[Distribution](Distribution.Exponential(1.0)))
    // structure inchangée
    assertEquals(r.automata.keySet, qual.automata.keySet)
    assertEquals(r.transitions.size, qual.transitions.size)
  }

  test("assign-rates sample : taux dans [min, max] et strictement positifs") {
    val r = Transform.assignRates(qual, Transform.RatePolicy.Sample(0.5, 4.0), seed = 7L)
    val rates = r.transitions.map(_.rate)
    assert(rates.forall(x => x >= 0.5 - 1e-9 && x <= 4.0 + 1e-9), rates.toString)
    assert(rates.forall(_ > 0.0))
  }

  test("assign-rates sample : déterministe pour une graine donnée") {
    val a = Transform.assignRates(qual, Transform.RatePolicy.Sample(0.1, 10.0), seed = 42L)
    val b = Transform.assignRates(qual, Transform.RatePolicy.Sample(0.1, 10.0), seed = 42L)
    assertEquals(a.transitions.map(_.rate), b.transitions.map(_.rate))
  }

  test("assign-rates : modèle valuée passe la validation (taux > 0)") {
    val r = Transform.assignRates(qual, Transform.RatePolicy.Sample(0.2, 5.0), seed = 1L)
    val errors = Validation.validate(r).count(_.severity == Severity.Error)
    assertEquals(errors, 0)
  }

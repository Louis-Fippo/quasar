package io.quasar.core.ir

class IrSuite extends munit.FunSuite:

  test("Distribution.mean / meanRate cohérents") {
    assertEqualsDouble(Distribution.Exponential(2.0).mean, 0.5, 1e-12)
    assertEqualsDouble(Distribution.Erlang(3, 2.0).mean, 1.5, 1e-12)
    assertEqualsDouble(Distribution.PhaseType(Vector(1.0, 2.0)).mean, 1.5, 1e-12)
    assertEqualsDouble(Distribution.Exponential(4.0).meanRate, 4.0, 1e-12)
  }

  test("LocalState.parse") {
    assertEquals(LocalState.parse("p53=1"), Right(LocalState("p53", 1)))
    assert(LocalState.parse("p53").isLeft)
  }

  test("Context.parse construit des singletons et fusionne") {
    assertEquals(Context.parse("a=0,b=1").map(_.states), Right(Map("a" -> Set(0), "b" -> Set(1))))
    assertEquals(Context.parse("default"), Right(Context.empty))
  }

  test("Validation détecte taux négatif et précondition inconnue") {
    val net = AutomataNetwork.of(
      Automaton(
        "a",
        2,
        List(
          Transition("a", 0, 1, List(LocalState("ghost", 1)), Distribution.Exponential(-1.0))
        )
      )
    )
    val diags = Validation.validate(net)
    assert(diags.exists(_.message.contains("taux")), "doit signaler le taux non positif")
    assert(diags.exists(_.message.contains("inconnu")), "doit signaler l'automate inconnu")
    assert(!Validation.isValid(net))
  }

  test("Validation accepte un modèle bien formé") {
    val net = AutomataNetwork.of(
      Automaton("a", 2, List(Transition("a", 0, 1, Nil, Distribution.Exponential(1.0)))),
      Automaton("b", 2, List(Transition("b", 0, 1, List(LocalState("a", 1)))))
    )
    assert(Validation.isValid(net), Validation.validate(net).mkString("\n"))
  }

package io.quasar.io

import io.quasar.core.ir.*

class ExportSuite extends munit.FunSuite:

  private val net = AutomataNetwork(
    Map(
      "a" -> Automaton(
        "a",
        2,
        List(Transition("a", 0, 1, List(LocalState("b", 1)), Distribution.Exponential(2.0)))
      ),
      "b" -> Automaton("b", 2, List(Transition("b", 0, 1)))
    ),
    Metadata(initial = Some(Context.parse("a=0,b=0").toOption.get))
  )

  test("export NuSMV : structure attendue") {
    val smv = NusmvFormat.render(net, Some(LocalState("a", 1)))
    assert(smv.contains("MODULE main"))
    assert(smv.contains("a : 0..1"))
    assert(smv.contains("IVAR"))
    assert(smv.contains("CTLSPEC EF (a = 1)"))
    assert(smv.contains("sel = a & a = 0 & b = 1 : 1;"))
  }

  test("export PRISM/Storm : CTMC avec taux") {
    val prism = StormFormat.render(net, Some(LocalState("a", 1)))
    assert(prism.contains("ctmc"))
    assert(prism.contains("a : [0..1] init 0;"))
    assert(prism.contains("[] a=0 & b=1 -> 2.0 : (a'=1);"))
    assert(prism.contains("P=? [ F a=1 ]"))
  }

  test("export PRISM/Storm : récompense de temps (V2)") {
    val prism = StormFormat.render(net, None)
    assert(prism.contains("rewards \"time\""), prism)
    assert(prism.contains("true : 1;"), prism)
    assert(prism.contains("endrewards"), prism)
  }

  test("parseResult Storm : valeur, inf, absence") {
    assertEquals(StormFormat.parseResult("Result (for initial states): 0.5"), Some(0.5))
    assertEquals(StormFormat.parseResult("Result (for initial states): 3.25e0"), Some(3.25))
    assertEquals(StormFormat.parseResult("Result (for initial states): inf"), None)
    assertEquals(StormFormat.parseResult("Result (for initial states): infinity"), None)
    assertEquals(StormFormat.parseResult("aucun résultat ici"), None)
  }

  test("export DOT : graphe de régulation") {
    val dot = DotFormat.render(net)
    assert(dot.contains("digraph"))
    assert(dot.contains("\"b\" -> \"a\""))
  }

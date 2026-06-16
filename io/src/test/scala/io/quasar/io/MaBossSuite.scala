package io.quasar.io

import io.quasar.core.ir.*

class MaBossSuite extends munit.FunSuite:

  private val bnd =
    """node A { logic = B & !C; rate_up = @logic ? $u_A : 0; rate_down = @logic ? 0 : $d_A; }
      |node B { logic = B; rate_up = @logic ? $u_B : 0; rate_down = @logic ? 0 : $d_B; }
      |node C { logic = A; rate_up = @logic ? $u_C : 0; rate_down = @logic ? 0 : $d_C; }
      |""".stripMargin

  private val cfg =
    """$u_A = 2.0; $d_A = 1.0;
      |$u_B = 1.0; $d_B = 1.0;
      |$u_C = 1.0; $d_C = 1.0;
      |A.istate = 0; B.istate = 1; C.istate = 0;
      |""".stripMargin

  test("import MaBoSS : 1 automate booléen par nœud") {
    val net = MaBossFormat.parse(bnd, Some(cfg)).fold(e => fail(e.toString), identity)
    assertEquals(net.size, 3)
    assert(net.automata.values.forall(_.levels == 2))
  }

  test("logique -> transitions avec préconditions DNF et taux du cfg") {
    val net = MaBossFormat.parse(bnd, Some(cfg)).fold(e => fail(e.toString), identity)
    val a = net.automaton("A").get
    val up = a.transitions.find(t => t.from == 0 && t.to == 1).get
    assertEquals(up.conditions.toSet, Set(LocalState("B", 1), LocalState("C", 0)))
    assertEqualsDouble(up.rate, 2.0, 1e-12) // $u_A
  }

  test("contexte initial extrait de .istate") {
    val net = MaBossFormat.parse(bnd, Some(cfg)).fold(e => fail(e.toString), identity)
    val ctx = net.metadata.initial.get
    assertEquals(ctx.states("B"), Set(1))
    assertEquals(ctx.states("A"), Set(0))
  }

  test("modèle MaBoSS valide après import") {
    val net = MaBossFormat.parse(bnd, Some(cfg)).fold(e => fail(e.toString), identity)
    assert(Validation.isValid(net), Validation.validate(net).mkString("\n"))
  }

package io.quasar.core.dd

class MddSuite extends munit.FunSuite:

  test("prédicat multivalué (domaine 3)") {
    val m = Mdd()
    m.setDomain(0, 3)
    val p2 = m.predicate(0, 2)
    assertEqualsDouble(m.eval(p2, _ => 2), 1.0, 1e-12)
    assertEqualsDouble(m.eval(p2, _ => 0), 0.0, 1e-12)
    assertEqualsDouble(m.eval(p2, _ => 1), 0.0, 1e-12)
  }

  test("somme d'abstraction = 1 sur un prédicat ; = cardinal sur un ensemble") {
    val m = Mdd()
    m.setDomain(0, 3)
    assertEquals(m.abstractSum(m.predicate(0, 2), List(0)), m.one)
    // ensemble {0,2} -> cardinal 2
    val set = m.or(m.predicate(0, 0), m.predicate(0, 2))
    assertEqualsDouble(m.eval(m.abstractSum(set, List(0)), _ => 0), 2.0, 1e-12)
  }

  test("et/ou/non sur indicateurs, restriction, relabel") {
    val m = Mdd()
    m.setDomain(0, 3); m.setDomain(1, 3)
    val a2 = m.predicate(0, 2)
    val b1 = m.predicate(1, 1)
    assertEqualsDouble(m.eval(m.and(a2, b1), v => if v == 0 then 2 else 1), 1.0, 1e-12)
    assertEqualsDouble(m.eval(m.and(a2, b1), v => if v == 0 then 2 else 0), 0.0, 1e-12)
    assertEqualsDouble(m.eval(m.not(a2), _ => 0), 1.0, 1e-12)
    assertEquals(m.restrict(a2, 0, 2), m.one)
    assertEquals(m.restrict(a2, 0, 1), m.zero)
    // relabel var0 -> var1
    assertEquals(m.relabel(a2, _ => 1), m.predicate(1, 2))
  }

package io.quasar.core.dd

class MtbddSuite extends munit.FunSuite:

  test("prédicat et évaluation") {
    val m = Mtbdd()
    val p1 = m.predicate(0, 1) // 1.0 si var0=1
    assertEqualsDouble(m.eval(p1, _ => 1), 1.0, 1e-12)
    assertEqualsDouble(m.eval(p1, _ => 0), 0.0, 1e-12)
  }

  test("opérations arithmétiques point à point") {
    val m = Mtbdd()
    val p1 = m.predicate(0, 1)
    val two = m.mul(m.constant(2.0), p1)
    assertEqualsDouble(m.eval(two, _ => 1), 2.0, 1e-12)
    assertEqualsDouble(m.eval(two, _ => 0), 0.0, 1e-12)
    // predicate(0,0) + predicate(0,1) = 1 partout
    val sum = m.add(m.predicate(0, 0), m.predicate(0, 1))
    assertEquals(sum, m.one)
  }

  test("somme d'abstraction : ∑ predicate(v) = 1") {
    val m = Mtbdd()
    assertEquals(m.abstractSum(m.predicate(0, 1), List(0)), m.one)
  }

  test("relabel et maxAbsLeaf") {
    val m = Mtbdd()
    val p = m.predicate(0, 1)
    val r = m.relabel(p, _ + 1) // var0 -> var1
    assertEqualsDouble(m.eval(r, v => if v == 1 then 1 else 0), 1.0, 1e-12)
    assertEqualsDouble(m.maxAbsLeaf(m.constant(-3.5)), 3.5, 1e-12)
  }

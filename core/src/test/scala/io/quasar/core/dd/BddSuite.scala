package io.quasar.core.dd

class BddSuite extends munit.FunSuite:

  test("opérations booléennes et satCount") {
    val b = Bdd()
    val x0 = b.variable(0)
    val x1 = b.variable(1)
    assertEquals(b.satCount(b.and(x0, x1), List(0, 1)), 1L)
    assertEquals(b.satCount(b.or(x0, x1), List(0, 1)), 3L)
    assertEquals(b.satCount(b.not(x0), List(0)), 1L)
    assertEquals(b.satCount(b.xor(x0, x1), List(0, 1)), 2L)
    assertEquals(b.satCount(b.True, List(0, 1)), 4L) // tout libre
    assertEquals(b.satCount(b.False, List(0, 1)), 0L)
  }

  test("réduction : structure canonique") {
    val b = Bdd()
    assertEquals(b.and(b.variable(0), b.variable(0)), b.variable(0))
    assertEquals(b.or(b.variable(0), b.not(b.variable(0))), b.True)
    assertEquals(b.and(b.variable(0), b.not(b.variable(0))), b.False)
  }

  test("quantification existentielle : ∃a.(a∧b) = b") {
    val b = Bdd()
    assertEquals(b.existsVar(b.and(b.variable(0), b.variable(1)), 0), b.variable(1))
  }

  test("relabel monotone : var1 -> var0") {
    val b = Bdd()
    assertEquals(b.relabel(b.variable(1), v => v - 1), b.variable(0))
  }

  test("isSat") {
    val b = Bdd()
    assert(b.isSat(b.variable(0)))
    assert(!b.isSat(b.False))
  }

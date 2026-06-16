package io.quasar.io

import io.quasar.io.BooleanExpr.*

class BooleanExprSuite extends munit.FunSuite:

  private def eval(e: BooleanExpr, env: Map[String, Boolean]): Boolean = e match
    case Lit(v) => v
    case Var(n) => env.getOrElse(n, false)
    case Not(x) => !eval(x, env)
    case And(l, r) => eval(l, env) && eval(r, env)
    case Or(l, r) => eval(l, env) || eval(r, env)

  private def satisfies(clause: Clause, env: Map[String, Boolean]): Boolean =
    clause.forall((k, v) => env.getOrElse(k, false) == v)

  test("parse précédence : a | b & c == a | (b & c)") {
    val e = parse("a | b & c").fold(fail(_), identity)
    val vars = List("a", "b", "c")
    for bits <- allEnvs(vars) do
      val byDnf = BooleanExpr.dnf(e).exists(satisfies(_, bits))
      assertEquals(byDnf, eval(e, bits), s"désaccord pour $bits")
  }

  test("DNF équivalente à l'évaluation directe (De Morgan)") {
    val e = parse("!(a & b) | (c & !a)").fold(fail(_), identity)
    val vars = List("a", "b", "c")
    for bits <- allEnvs(vars) do
      val byDnf = BooleanExpr.dnf(e).exists(satisfies(_, bits))
      assertEquals(byDnf, eval(e, bits), s"désaccord pour $bits")
  }

  test("constantes : logic = 1 toujours vraie, 0 jamais") {
    assertEquals(BooleanExpr.dnf(parse("1").toOption.get), List(Map.empty[String, Boolean]))
    assertEquals(BooleanExpr.dnf(parse("0").toOption.get), Nil)
  }

  private def allEnvs(vars: List[String]): List[Map[String, Boolean]] =
    (0 until (1 << vars.size)).toList.map { mask =>
      vars.zipWithIndex.map((v, i) => v -> ((mask >> i) % 2 == 1)).toMap
    }

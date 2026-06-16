package io.quasar.io

import io.quasar.core.ir.*

class AnxRoundTripSuite extends munit.FunSuite:

  private val sample =
    """model "demo"
      |automaton a [0, 1, 2]
      |automaton b [0, 1]
      |a: 0 -> 1 when b=1 @ 1.5
      |a: 1 -> 2 @ erlang(2, 1.0)
      |b: 0 -> 1 @ 2.0
      |init a=0, b=0
      |""".stripMargin

  test("parse ANX : automates, transitions, distributions") {
    val net = AnxFormat.parse(sample).fold(e => fail(e.toString), identity)
    assertEquals(net.size, 2)
    assertEquals(net.automaton("a").get.levels, 3)
    val t = net.automaton("a").get.transitions.head
    assertEquals(t.conditions, List(LocalState("b", 1)))
    assertEquals(t.dist, Distribution.Exponential(1.5))
    assert(net.automaton("a").get.transitions(1).dist == Distribution.Erlang(2, 1.0))
  }

  test("round-trip ANX render -> parse préserve la structure") {
    val net1 = AnxFormat.parse(sample).fold(e => fail(e.toString), identity)
    val text = AnxFormat.render(net1)
    val net2 = AnxFormat.parse(text).fold(e => fail(e.toString), identity)
    assertEquals(net2.size, net1.size)
    assertEquals(net2.transitions.size, net1.transitions.size)
    assertEquals(net2.automaton("a").map(_.levels), net1.automaton("a").map(_.levels))
    assertEquals(
      net2.transitions.map(_.toString).toSet,
      net1.transitions.map(_.toString).toSet
    )
  }

  test("erreur de parsing localisée") {
    val bad = "automaton a [0,1]\na: x -> 1\n"
    assert(AnxFormat.parse(bad).isLeft)
  }

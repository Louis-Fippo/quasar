package io.quasar.biolqm

import io.quasar.core.ir.*

import java.io.File
import java.nio.file.Files

class BioLqmSuite extends munit.FunSuite:

  private def writeBnet(content: String): String =
    val f = File.createTempFile("quasar-test-", ".bnet")
    f.deleteOnExit()
    Files.writeString(f.toPath, content)
    f.getAbsolutePath

  private val bnet =
    """targets, factors
      |A, B & !C
      |B, A
      |C, C
      |""".stripMargin

  // --- équivalence dynamique (relation de pas asynchrone) ------------------

  private def stepUp(net: AutomataNetwork, a: String, s: Map[String, Int]): Boolean =
    net
      .automaton(a)
      .exists(
        _.transitions.exists(t =>
          t.from == s(a) && t.to > t.from && t.conditions.forall(c => s(c.automaton) == c.level)
        )
      )
  private def stepDown(net: AutomataNetwork, a: String, s: Map[String, Int]): Boolean =
    net
      .automaton(a)
      .exists(
        _.transitions.exists(t =>
          t.from == s(a) && t.to < t.from && t.conditions.forall(c => s(c.automaton) == c.level)
        )
      )

  private def boolStates(names: List[String]): List[Map[String, Int]] =
    names.foldLeft(List(Map.empty[String, Int]))((acc, n) =>
      for s <- acc; v <- List(0, 1) yield s.updated(n, v)
    )

  private def dynEquiv(n1: AutomataNetwork, n2: AutomataNetwork): Boolean =
    n1.automata.keySet == n2.automata.keySet && {
      val names = n1.automata.keys.toList.sorted
      boolStates(names).forall(s =>
        names.forall(a =>
          stepUp(n1, a, s) == stepUp(n2, a, s) && stepDown(n1, a, s) == stepDown(n2, a, s)
        )
      )
    }

  test("import BoolNet -> ANX : structure et transitions") {
    val net = BioLqm.importFile(writeBnet(bnet)).fold(e => fail(e), identity)
    assertEquals(net.automata.keySet, Set("A", "B", "C"))
    assert(net.automata.values.forall(_.levels == 2))
    // A monte (0->1) quand B=1 et C=0
    val aUp = net.automaton("A").get.transitions.find(t => t.from == 0 && t.to == 1).get
    assertEquals(aUp.conditions.toSet, Set(LocalState("B", 1), LocalState("C", 0)))
  }

  test("round-trip ANX <-> bioLQM : équivalence dynamique") {
    val net1 = BioLqm.importFile(writeBnet(bnet)).fold(e => fail(e), identity)
    val lm2 = BioLqm.toLogicalModel(net1).fold(e => fail(e), identity)
    val net2 = BioLqm.fromLogicalModel(lm2)
    assert(dynEquiv(net1, net2), s"round-trip non équivalent\n$net1\n$net2")
  }

  test("export SBML-qual via bioLQM") {
    val net = BioLqm.importFile(writeBnet(bnet)).fold(e => fail(e), identity)
    val out = File.createTempFile("quasar-out-", ".sbml")
    out.deleteOnExit()
    assert(BioLqm.exportFile(net, out.getAbsolutePath, "sbml").isRight)
    val text = Files.readString(out.toPath)
    assert(text.contains("sbml"), "doit produire du SBML")
  }

  test("multivalué : export refusé, perte signalée") {
    val mv = AutomataNetwork.of(Automaton("x", 3, Nil))
    assert(BioLqm.toBnet(mv).isLeft)
    assert(BioLqm.projectionLoss(mv).exists(_.contains("multivalué")))
  }

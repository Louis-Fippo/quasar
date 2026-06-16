package io.quasar.cli

import io.quasar.core.ir.*

import java.nio.file.Files

class RepoStoreSuite extends munit.FunSuite:

  private def newStore(): RepoStore =
    val dir = Files.createTempDirectory("quasar-repo-test")
    RepoStore.at(dir.toString)

  private val net = AutomataNetwork.of(
    Automaton("a", 2, List(Transition("a", 0, 1)))
  )

  test("init / add / list / get") {
    val s = newStore()
    s.init()
    s.add(net, "demo", Set("toy", "test"))
    val es = s.list()
    assertEquals(es.map(_.id), List("demo"))
    assertEquals(es.head.tags, Set("toy", "test"))
    assertEquals(s.get("demo").map(_.size), Some(1))
  }

  test("filtre par tag et recherche") {
    val s = newStore()
    s.init()
    s.add(net, "m1", Set("bio"))
    s.add(net, "m2", Set("synthetic"))
    assertEquals(s.list(Some("bio")).map(_.id), List("m1"))
    assertEquals(s.search("synth").map(_.id), List("m2"))
  }

  test("tag puis rm") {
    val s = newStore()
    s.init()
    s.add(net, "m", Set.empty)
    assert(s.tag("m", Set("x")))
    assertEquals(s.list().head.tags, Set("x"))
    assert(s.remove("m"))
    assert(s.list().isEmpty)
    assert(!s.tag("absent", Set("y")))
  }

  test("bundle écrit un fichier") {
    val s = newStore()
    s.init()
    s.add(net, "m", Set("t"))
    val out = Files.createTempFile("bundle", ".anx")
    assert(s.bundle("m", out))
    val text = Files.readString(out)
    assert(text.contains("run-bundle"))
    assert(text.contains("automaton a"))
  }

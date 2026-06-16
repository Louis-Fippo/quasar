package io.quasar.verify

class ExternalToolSuite extends munit.FunSuite:

  test("détection d'un binaire inexistant -> Missing (pas de crash)") {
    val st = ExternalTool.detect("quasar-nonexistent-binary-xyz")
    assertEquals(st, ToolStatus.Missing)
    assert(!st.isAvailable)
  }

  test("run d'un binaire absent renvoie une erreur claire") {
    val r = ExternalTool.run(List("quasar-nonexistent-binary-xyz", "--help"))
    assert(r.isLeft)
    assert(r.left.exists(_.contains("introuvable")), r.toString)
  }

  test("status() des adaptateurs ne lève pas d'exception") {
    val statuses = List(NusmvAdapter.status(), StormAdapter.status(), MaBossAdapter.status())
    statuses.foreach(s => assert(s.isInstanceOf[ToolStatus]))
  }

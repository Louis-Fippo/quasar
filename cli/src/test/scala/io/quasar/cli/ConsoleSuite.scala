package io.quasar.cli

import java.nio.file.Files

class ConsoleSuite extends munit.FunSuite:

  test("jsonEnabled : flag local OU option globale") {
    sys.props.remove("quasar.json")
    assert(Console.jsonEnabled(true)) // local
    assert(!Console.jsonEnabled(false)) // ni local ni global
    System.setProperty("quasar.json", "true")
    assert(Console.jsonEnabled(false)) // global
    sys.props.remove("quasar.json")
  }

  test("cachedRun : rejoue la sortie mémorisée sans recalcul") {
    val dir = Files.createTempDirectory("quasar-cache-test")
    System.setProperty("quasar.cacheDir", dir.toString)
    try
      var calls = 0
      def body(): Int = { calls += 1; Console.out("résultat"); 0 }
      val key = Seq("analyze", "demo")
      assertEquals(Console.cachedRun(key)(body()), 0)
      assertEquals(calls, 1) // 1er appel : calcul
      assertEquals(Console.cachedRun(key)(body()), 0)
      assertEquals(calls, 1) // 2e appel : hit, aucun recalcul
    finally sys.props.remove("quasar.cacheDir")
  }

  test("cachedRun : pas de cache si --cache-dir absent") {
    sys.props.remove("quasar.cacheDir")
    var calls = 0
    def body(): Int = { calls += 1; 0 }
    Console.cachedRun(Seq("k"))(body())
    Console.cachedRun(Seq("k"))(body())
    assertEquals(calls, 2) // chaque appel recalcule
  }

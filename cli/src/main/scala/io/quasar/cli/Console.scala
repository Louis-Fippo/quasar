package io.quasar.cli

import io.quasar.io.Importer
import io.quasar.core.ir.AutomataNetwork

/** Sortie console + chargement de modèle partagés par les commandes. */
object Console:

  def out(s: String): Unit = System.out.println(s)
  def err(s: String): Unit = System.err.println(s)

  def fail(msg: String): Int =
    err(s"erreur : $msg")
    1

  def ok: Int = 0

  /** Formats délégués à bioLQM (SBML-qual, GINML, BoolNet, …). */
  private val biolqmFormats =
    Set("sbml-qual", "sbml", "ginml", "boolnet", "bnet", "booleannet", "cellcollective")

  private def biolqmHandlesExt(path: String): Boolean =
    val p = path.toLowerCase
    p.endsWith(".sbml") || p.endsWith(".xml") || p.endsWith(".ginml") ||
    p.endsWith(".zginml") || p.endsWith(".booleannet") || p.endsWith(".bnet")

  /** Charge un modèle ANX (routage bioLQM/io), erreur sous forme de message. */
  def load(path: String, format: Option[String] = None): Either[String, AutomataNetwork] =
    val viaBiolqm =
      format.exists(f => biolqmFormats.contains(f.toLowerCase)) ||
        (format.isEmpty && biolqmHandlesExt(path))
    val res =
      if viaBiolqm then io.quasar.biolqm.BioLqm.importFile(path)
      else Importer.fromFile(path, format.flatMap(io.quasar.io.Format.parse)).left.map(_.toString)
    res.left.map(e => s"import de '$path' : $e")

  /** Charge un modèle ANX depuis un fichier, ou échoue proprement (sortie CLI). */
  def loadModel(path: String, format: Option[String] = None): Either[Int, AutomataNetwork] =
    load(path, format).left.map(fail)

  def emitJson(j: Json.J): Int =
    out(Json.render(j))
    ok

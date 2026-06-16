package io.quasar.verify

import java.io.File
import java.util.concurrent.TimeUnit
import scala.util.Try

/** Statut de détection d'un outil externe. */
enum ToolStatus:
  case Available(version: String)
  case Missing

  def isAvailable: Boolean = this match
    case Available(_) => true
    case Missing => false

/** Résultat d'exécution d'un sous-processus. */
final case class ProcOutput(exitCode: Int, stdout: String, stderr: String)

/**
 * Détection et invocation d'outils externes en sous-processus (CLAUDE.md §4) : NuSMV/Storm/MaBoSS
 * ne sont **pas** vendorisés ; ils sont détectés à l'exécution et l'absence est signalée proprement
 * (pas de stacktrace).
 */
object ExternalTool:

  /** Détecte un binaire via une commande de version (renvoie la 1re ligne). */
  def detect(binary: String, versionArgs: List[String] = List("--version")): ToolStatus =
    run(binary :: versionArgs, timeoutMs = 5000) match
      case Right(out) if out.exitCode == 0 || out.stdout.nonEmpty =>
        val line = (out.stdout + "\n" + out.stderr).linesIterator.find(_.trim.nonEmpty)
        ToolStatus.Available(line.getOrElse(binary).trim)
      case _ => ToolStatus.Missing

  /** Exécute une commande, capture stdout/stderr, borne le temps d'exécution. */
  def run(
      command: List[String],
      stdinFile: Option[File] = None,
      timeoutMs: Long = 120_000
  ): Either[String, ProcOutput] =
    Try {
      val pb = ProcessBuilder(command*)
      stdinFile.foreach(pb.redirectInput)
      val proc = pb.start()
      val out = scala.io.Source.fromInputStream(proc.getInputStream).mkString
      val err = scala.io.Source.fromInputStream(proc.getErrorStream).mkString
      val done = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
      if !done then
        proc.destroyForcibly()
        Left(s"délai dépassé (${timeoutMs} ms) : ${command.head}")
      else Right(ProcOutput(proc.exitValue(), out, err))
    }.recover {
      case _: java.io.IOException =>
        Left(s"binaire introuvable : '${command.head}' (installer et ajouter au PATH)")
      case e => Left(s"échec d'exécution de '${command.head}' : ${e.getMessage}")
    }.get

  /** Écrit un contenu dans un fichier temporaire à l'extension donnée. */
  def tempFile(content: String, suffix: String): File =
    val f = File.createTempFile("quasar-", suffix)
    f.deleteOnExit()
    java.nio.file.Files.writeString(f.toPath, content)
    f

package io.quasar.verify

import io.quasar.core.ir.*
import io.quasar.io.{MaBossOutput, NusmvFormat, StormFormat}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Rapport d'un vérificateur externe. */
final case class VerifyReport(
    tool: String,
    reachable: Option[Boolean],
    probability: Option[Double],
    raw: String
)

/** Adaptateur NuSMV (qualitatif symbolique, §7.6). */
object NusmvAdapter:

  val binary = "NuSMV"

  def status(): ToolStatus = ExternalTool.detect(binary, List("-help")) match
    case ToolStatus.Missing => ExternalTool.detect(binary)
    case s => s

  def reachability(net: AutomataNetwork, goal: LocalState): Either[String, VerifyReport] =
    val model = NusmvFormat.render(net, Some(goal))
    val file = ExternalTool.tempFile(model, ".smv")
    ExternalTool.run(List(binary, file.getAbsolutePath)).map { out =>
      val txt = out.stdout + out.stderr
      // NuSMV imprime "is true"/"is false" pour la spécification
      val reachable =
        if txt.contains("is true") then Some(true)
        else if txt.contains("is false") then Some(false)
        else None
      VerifyReport(binary, reachable, None, txt)
    }

/** Adaptateur Storm (probabiliste exact, §7.6). */
object StormAdapter:

  val binary = "storm"

  def status(): ToolStatus = ExternalTool.detect(binary)

  def probability(net: AutomataNetwork, goal: LocalState): Either[String, VerifyReport] =
    val model = StormFormat.render(net, None)
    val file = ExternalTool.tempFile(model, ".prism")
    val prop = s"P=? [ F ${goal.automaton}=${goal.level} ]"
    ExternalTool
      .run(List(binary, "--prism", file.getAbsolutePath, "--prop", prop))
      .map { out =>
        val txt = out.stdout + out.stderr
        val prob = """Result.*?:\s*([0-9.eE+-]+)""".r
          .findFirstMatchIn(txt)
          .flatMap(_.group(1).toDoubleOption)
        VerifyReport(binary, prob.map(_ > 0), prob, txt)
      }

/** Adaptateur MaBoSS (simulation CTMC, oracle empirique, §7.6). */
object MaBossAdapter:

  val binary = "MaBoSS"

  def status(): ToolStatus = ExternalTool.detect(binary, List("--version"))

  /** Lance MaBoSS sur les fichiers `.bnd`/`.cfg` et renvoie la sortie brute. */
  def run(bndPath: String, cfgPath: String, outputPrefix: String): Either[String, VerifyReport] =
    ExternalTool
      .run(List(binary, "-c", cfgPath, "-o", outputPrefix, bndPath))
      .map(out => VerifyReport(binary, None, None, out.stdout + out.stderr))

  /**
   * Lance MaBoSS puis extrait `P(node actif)` au temps final depuis le `*_probtraj.csv`. Sert
   * d'oracle empirique pour confronter `binf P(R)`.
   */
  def probabilityOf(bndPath: String, cfgPath: String, node: String): Either[String, VerifyReport] =
    val prefix = ExternalTool.tempFile("", "").getAbsolutePath
    ExternalTool.run(List(binary, "-c", cfgPath, "-o", prefix, bndPath)).flatMap { out =>
      findProbtraj(prefix) match
        case None => Left(s"sortie probtraj introuvable (préfixe $prefix)")
        case Some(file) =>
          MaBossOutput.parseProbtraj(Files.readString(file.toPath)) match
            case Left(e) => Left(e.toString)
            case Right(dist) =>
              val p = dist.probActive(node)
              Right(VerifyReport(binary, Some(p > 0), Some(p), out.stdout + out.stderr))
    }

  /** Localise le fichier `*probtraj*` produit pour ce préfixe. */
  private def findProbtraj(prefix: String): Option[java.io.File] =
    val p = Paths.get(prefix)
    val dir = Option(p.getParent).getOrElse(Paths.get("."))
    val base = p.getFileName.toString
    if !Files.isDirectory(dir) then None
    else
      Files
        .list(dir)
        .iterator
        .asScala
        .map(_.toFile)
        .filter(f => f.getName.startsWith(base) && f.getName.contains("probtraj"))
        .toList
        .sortBy(_.getName.length)
        .headOption

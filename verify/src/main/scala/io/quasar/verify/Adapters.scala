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
    query(net, s"P=? [ F ${StormFormat.varName(goal.automaton)}=${goal.level} ]").map {
      (value, txt) => VerifyReport(binary, value.map(_ > 0), value, txt)
    }

  /**
   * Temps d'atteinte **espéré** exact du but (fiche V2) via la récompense `"time"` du CTMC
   * (`R{"time"}=? [ F goal ]`). `None` si le temps est infini (but non atteint p.s.).
   */
  def expectedTime(net: AutomataNetwork, goal: LocalState): Either[String, VerifyReport] =
    query(net, s"""R{"time"}=? [ F ${StormFormat.varName(goal.automaton)}=${goal.level} ]""").map {
      (value, txt) => VerifyReport(binary, None, value, txt)
    }

  /** Lance Storm sur le modèle PRISM avec une propriété et renvoie (valeur, sortie brute). */
  private def query(net: AutomataNetwork, prop: String): Either[String, (Option[Double], String)] =
    val file = ExternalTool.tempFile(StormFormat.render(net, None), ".prism")
    ExternalTool.run(List(binary, "--prism", file.getAbsolutePath, "--prop", prop)).map { out =>
      val txt = out.stdout + out.stderr
      (StormFormat.parseResult(txt), txt)
    }

/**
 * Distribution des temps d'atteinte MaBoSS (fiche V1) — débloque H2 (délai vs quantile) et H4
 * (recouvrement de trajectoire).
 */
final case class MaBossHitting(
    node: String,
    prob: Double,
    times: Vector[Double],
    cdf: Vector[Double],
    quantiles: Vector[(Double, Option[Double])],
    nodeActivation: Map[String, Double],
    raw: String
)

/** Adaptateur MaBoSS (simulation CTMC, oracle empirique, §7.6). */
object MaBossAdapter:

  val binary = "MaBoSS"

  /** Niveaux de quantiles de temps d'atteinte rapportés par défaut. */
  val quantileLevels: Vector[Double] = Vector(0.1, 0.25, 0.5, 0.75, 0.9)

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

  /**
   * Lance MaBoSS et extrait la **distribution des temps d'atteinte** de `node` (fiche V1) : CDF
   * marginale dans le temps, quantiles, et temps d'activation par nœud (pour H4). `samples` et
   * `maxTime` surchargent le `.cfg` (via un `-c` de surcharge), sans le modifier.
   */
  def hittingTime(
      bndPath: String,
      cfgPath: String,
      node: String,
      samples: Option[Int] = None,
      maxTime: Option[Double] = None
  ): Either[String, MaBossHitting] =
    val prefix = ExternalTool.tempFile("", "").getAbsolutePath
    val cfgArgs = overrideCfg(samples, maxTime) match
      case Some(extra) => List("-c", cfgPath, "-c", extra)
      case None => List("-c", cfgPath)
    ExternalTool.run(List(binary) ++ cfgArgs ++ List("-o", prefix, bndPath)).flatMap { out =>
      findProbtraj(prefix) match
        case None => Left(s"sortie probtraj introuvable (préfixe $prefix)")
        case Some(file) =>
          MaBossOutput.parseSeries(Files.readString(file.toPath)) match
            case Left(e) => Left(e.toString)
            case Right(series) =>
              Right(
                MaBossHitting(
                  node = node,
                  prob = series.finalProb(node),
                  times = series.times,
                  cdf = series.marginal(node),
                  quantiles = series.quantiles(node, quantileLevels),
                  nodeActivation = series.activationTimes(0.5),
                  raw = out.stdout + out.stderr
                )
              )
    }

  /** Fichier `.cfg` de surcharge (sample_count / max_time), ou `None` si rien à surcharger. */
  private def overrideCfg(samples: Option[Int], maxTime: Option[Double]): Option[String] =
    if samples.isEmpty && maxTime.isEmpty then None
    else
      val sb = new StringBuilder
      samples.foreach(n => sb.append(s"sample_count = $n;\n"))
      maxTime.foreach(t => sb.append(s"max_time = $t;\n"))
      Some(ExternalTool.tempFile(sb.toString, ".cfg").getAbsolutePath)

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

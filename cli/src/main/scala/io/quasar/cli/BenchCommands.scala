package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.analysis.{Quantitative, Reachability}
import io.quasar.core.ir.LocalState
import io.quasar.core.ir.Context
import io.quasar.verify.{MaBossAdapter, ToolStatus}

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*

/** Groupe `quasar bench` — benchmark & validation (§7.9). */
object BenchCommands:

  type Run = () => Int

  private val modelsDir = "bench/models"

  private val modelsCmd = Opts.subcommand("models", "liste les modèles de référence") {
    Opts.unit.map { _ => () =>
      val dir = Paths.get(modelsDir)
      if !Files.isDirectory(dir) then Console.fail(s"répertoire introuvable : $modelsDir")
      else
        val files = Files
          .list(dir)
          .iterator
          .asScala
          .toList
          .map(_.getFileName.toString)
          .filter(f => f.endsWith(".anx") || f.endsWith(".bnd"))
          .sorted
        if files.isEmpty then Console.out("(aucun modèle)")
        else files.foreach(Console.out)
        0
    }
  }

  private val validateCmd =
    Opts.subcommand("validate", "QUASAR vs oracle exact (justesse + finesse)") {
      (
        Opts.argument[String]("model"),
        Opts.option[String]("goal", "objectif a=j"),
        Opts.flag("json", "JSON").orFalse
      )
        .mapN { (path, g, json) => () =>
          (for net <- Console.loadModel(path); goal <- LocalState.parse(g).left.map(Console.fail)
          yield (net, goal)) match
            case Left(c) => c
            case Right((net, goal)) =>
              val ctx = net.metadata.initial.getOrElse(Context.empty)
              val r = Reachability.analyze(net, ctx, goal)
              val q = Quantitative.analyze(net, ctx, goal)
              val exact = r.uaReachable // recherche de cône exacte
              val sound = !(r.uaReachable && !exact) && !(exact && !r.oaReachable)
              val tight = r.oaReachable == exact
              if json then
                Console.emitJson(
                  Json.obj(
                    "model" -> Json.str(path),
                    "goal" -> Json.str(goal.toString),
                    "sound" -> Json.bool(sound),
                    "tight" -> Json.bool(tight),
                    "probLowerBound" -> Json.opt(q.probLowerBound.map(b => Json.num(b.value)))
                  )
                )
              else
                Console.out(s"Modèle  : $path")
                Console.out(s"Objectif: $goal")
                Console.out(s"Justesse: ${if sound then "✓" else "✗ BUG"}")
                Console.out(s"Finesse : ${if tight then "OA = exact (optimal)" else "OA lâche"}")
                q.probLowerBound.foreach(b => Console.out(f"P(R) ≥ ${b.value}%.6g  (QUASAR)"))
                mabossOracle(path, goal).foreach(Console.out)
              if sound then 0 else 1
        }
    }

  /** Confronte à MaBoSS si le binaire est présent et le modèle au format `.bnd`. */
  private def mabossOracle(path: String, goal: LocalState): Option[String] =
    if !path.toLowerCase.endsWith(".bnd") then None
    else if MaBossAdapter.status() == ToolStatus.Missing then
      Some("P(R) MaBoSS : (binaire absent — oracle non exécuté)")
    else
      val cfg = path.replaceAll("(?i)\\.bnd$", ".cfg")
      MaBossAdapter.probabilityOf(path, cfg, goal.automaton) match
        case Left(e) => Some(s"P(R) MaBoSS : erreur ($e)")
        case Right(rep) => rep.probability.map(p => f"P(R) = $p%.6g  (MaBoSS, oracle)")

  private val runCmd = Opts.subcommand("run", "suite: small|all") {
    Opts.argument[String]("suite").map { suite => () =>
      val dir = Paths.get(modelsDir)
      if !Files.isDirectory(dir) then Console.fail(s"répertoire introuvable : $modelsDir")
      else
        val models = Files
          .list(dir)
          .iterator
          .asScala
          .toList
          .map(_.getFileName.toString)
          .filter(_.endsWith(".anx"))
          .sorted
        Console.out(s"Suite '$suite' : ${models.size} modèle(s)")
        models.foreach { m =>
          Console.loadModel(s"$modelsDir/$m") match
            case Right(net) =>
              Console
                .out(f"  $m%-20s ${net.size}%3d automates, ${net.transitions.size}%3d transitions")
            case Left(_) => Console.out(s"  $m  (échec de chargement)")
        }
        0
    }
  }

  val command: Opts[Run] = modelsCmd.orElse(validateCmd).orElse(runCmd)

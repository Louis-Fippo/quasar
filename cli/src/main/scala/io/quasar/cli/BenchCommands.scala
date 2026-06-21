package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.analysis.{
  QuantBracket,
  QuantCegar,
  QuantResult,
  Quantitative,
  SymbolicMdd,
  ValidationMetrics
}
import io.quasar.core.Approx
import io.quasar.core.ir.LocalState
import io.quasar.core.ir.Context
import io.quasar.verify.{MaBossAdapter, MaBossHitting, ToolStatus}

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

  /** Rapport de validation consolidé (fiche M2). Champs oracle à `None` si MaBoSS absent. */
  private final case class ValidationReport(
      binf: Option[Double],
      exact: Option[Double],
      soundness: Option[Boolean],
      tightness: Option[Double],
      relGap: Option[Double],
      tQuasar: Option[Double],
      delayGap: Option[Double],
      scenarioOverlap: Option[Double],
      oracle: String
  )

  /** Temps d'atteinte MaBoSS si le binaire est présent et le modèle au format `.bnd`. */
  private def mabossHitting(path: String, goal: LocalState): Option[MaBossHitting] =
    if !path.toLowerCase.endsWith(".bnd") || MaBossAdapter.status() == ToolStatus.Missing then None
    else
      val cfg = path.replaceAll("(?i)\\.bnd$", ".cfg")
      MaBossAdapter.hittingTime(path, cfg, goal.automaton).toOption

  private def buildReport(
      net: io.quasar.core.ir.AutomataNetwork,
      path: String,
      goal: LocalState
  ): ValidationReport =
    val ctx = net.metadata.initial.getOrElse(Context.empty)
    val q = Quantitative.analyze(net, ctx, goal)
    val binfB = q.probLowerBound
    val binf = binfB.map(_.value)
    // référence exacte : MDD symbolique si possible, sinon la borne si déjà exacte (CTMC).
    val mddExact = SymbolicMdd.reachProbability(net, ctx, goal).toOption.map(_.reachProbability)
    val exact = mddExact.orElse(binfB.collect { case b if b.approx == Approx.Exact => b.value })
    val bounds = for b <- binf; e <- exact yield ValidationMetrics.Bounds(b, e)
    val tQuasar = q.earliestDelay.map(_.value)
    val hit = mabossHitting(path, goal)
    val q25 = hit.flatMap(_.quantiles.collectFirst { case (0.25, Some(t)) => t })
    val delayGap = for _ <- hit; t25 <- q25; tr <- tQuasar yield t25 - tr
    val overlap = hit.map(h =>
      ValidationMetrics.jaccard(q.scenario.map(_.automaton).toSet, h.nodeActivation.keySet)
    )
    ValidationReport(
      binf,
      exact,
      bounds.map(_.sound()),
      bounds.map(_.tightness),
      bounds.map(_.relGap),
      tQuasar,
      delayGap,
      overlap,
      if hit.isDefined then "maboss" else "none"
    )

  private val validateCmd =
    Opts.subcommand(
      "validate",
      "rapport de validation consolidé (M2 : justesse/finesse/délai/scénario)"
    ) {
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
              val rep = buildReport(net, path, goal)
              if json then
                Console.emitJson(
                  Json.obj(
                    "model" -> Json.str(path),
                    "goal" -> Json.str(goal.toString),
                    "soundness" -> Json.opt(rep.soundness.map(Json.bool)),
                    "tightness" -> Json.opt(rep.tightness.map(Json.num)),
                    "relGap" -> Json.opt(rep.relGap.map(Json.num)),
                    "binf" -> Json.opt(rep.binf.map(Json.num)),
                    "exact" -> Json.opt(rep.exact.map(Json.num)),
                    "tDelay" -> Json.opt(rep.tQuasar.map(Json.num)),
                    "delayGap" -> Json.opt(rep.delayGap.map(Json.num)),
                    "scenarioOverlap" -> Json.opt(rep.scenarioOverlap.map(Json.num)),
                    "oracle" -> Json.str(rep.oracle)
                  )
                )
              else
                Console.out(s"Modèle   : $path")
                Console.out(s"Objectif : $goal")
                Console.out(
                  s"Justesse : ${rep.soundness
                      .map(b => if b then "✓ binf ≤ exact" else "✗ VIOLATION")
                      .getOrElse("? (exact indisponible)")}"
                )
                rep.tightness.foreach(t =>
                  Console.out(
                    f"Finesse  : tightness=$t%.4f (relGap=${rep.relGap.getOrElse(0.0)}%.4f)"
                  )
                )
                (rep.binf, rep.exact) match
                  case (Some(b), Some(e)) => Console.out(f"P(R)     : binf=$b%.6g  exact=$e%.6g")
                  case _ => rep.binf.foreach(b => Console.out(f"P(R)     : binf=$b%.6g"))
                Console.out(s"Oracle   : ${rep.oracle}")
                rep.delayGap.foreach(d => Console.out(f"Délai    : gap(q25 − T(R))=$d%.4g"))
                rep.scenarioOverlap.foreach(o => Console.out(f"Scénario : recouvrement=$o%.3f"))
              if rep.soundness.forall(identity) then 0 else 1
        }
    }

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

  // --- ablation (fiche A3) -------------------------------------------------
  /** Temps minimal (ms) sur `reps` exécutions (réduit le bruit JIT/GC). */
  private def timeMs(reps: Int)(body: => Unit): Double =
    var best = Double.MaxValue
    for _ <- 1 to math.max(1, reps) do
      val t0 = System.nanoTime()
      body
      best = math.min(best, (System.nanoTime() - t0) / 1e6)
    best

  private val ablationCmd =
    Opts.subcommand("ablation", "ablation des stratégies de calcul de P(R) (H6)") {
      (
        Opts.argument[String]("model"),
        Opts.option[String]("goal", "objectif a=j"),
        Opts.option[String]("from", "contexte initial").orNone,
        Opts.option[Int]("budget", "budget CEGAR (anytime)").withDefault(256),
        Opts.option[Int]("reps", "répétitions de mesure (min retenu)").withDefault(3),
        Opts.flag("json", "JSON").orFalse
      ).mapN { (path, g, frm, budget, reps, json) => () =>
        val loaded = for
          net <- Console.loadModel(path)
          goal <- LocalState.parse(g).left.map(Console.fail)
          ctx <- frm match
            case Some(s) => Context.parse(s).left.map(Console.fail)
            case None => Right(net.metadata.initial.getOrElse(Context.empty))
        yield (net, goal, ctx)
        loaded match
          case Left(c) => c
          case Right((net, goal, ctx)) =>
            // Stratégie 1 — CTMC exact (matrice fondamentale), référence.
            var q: QuantResult = null
            val tCtmc = timeMs(reps) { q = Quantitative.analyze(net, ctx, goal) }
            val ctmcVal = q.probLowerBound.map(_.value)
            val ctmcExact = q.probLowerBound.exists(_.approx == Approx.Exact)
            // Stratégie 2 — MDD symbolique (sans énumération d'états).
            var mdd: Either[String, SymbolicMdd.ProbResult] = Left("non exécuté")
            val tMdd = timeMs(reps) { mdd = SymbolicMdd.reachProbability(net, ctx, goal) }
            // Stratégie 3 — CEGAR anytime (encadrement [lo, hi]).
            var br: QuantBracket = null
            val tCegar = timeMs(reps) { br = QuantCegar.bracket(net, ctx, goal, budget) }

            val ref =
              if ctmcExact then ctmcVal else mdd.toOption.map(_.reachProbability).orElse(ctmcVal)
            def agree(v: Option[Double]): Boolean =
              (for r <- ref; x <- v yield math.abs(x - r) <= 1e-6).getOrElse(false)
            val cegarAgree = ref.exists(r => br.lower - 1e-9 <= r && r <= br.upper + 1e-9)

            if json then
              val strat = List(
                Json.obj(
                  "strategy" -> Json.str("ctmc-exact"),
                  "value" -> Json.opt(ctmcVal.map(Json.num)),
                  "exact" -> Json.bool(ctmcExact),
                  "timeMs" -> Json.num(tCtmc),
                  "agrees" -> Json.bool(agree(ctmcVal))
                ),
                mdd match
                  case Right(r) =>
                    Json.obj(
                      "strategy" -> Json.str("mdd-symbolic"),
                      "value" -> Json.num(r.reachProbability),
                      "exact" -> Json.bool(true),
                      "ddNodes" -> Json.int(r.mddNodes),
                      "timeMs" -> Json.num(tMdd),
                      "agrees" -> Json.bool(agree(Some(r.reachProbability)))
                    )
                  case Left(e) =>
                    Json.obj(
                      "strategy" -> Json.str("mdd-symbolic"),
                      "note" -> Json.str(s"n/a : $e"),
                      "timeMs" -> Json.num(tMdd)
                    ),
                Json.obj(
                  "strategy" -> Json.str("cegar-anytime"),
                  "lower" -> Json.num(br.lower),
                  "upper" -> Json.num(br.upper),
                  "width" -> Json.num(br.width),
                  "exact" -> Json.bool(br.exact()),
                  "budget" -> Json.int(budget),
                  "timeMs" -> Json.num(tCegar),
                  "agrees" -> Json.bool(cegarAgree)
                )
              )
              Console.emitJson(
                Json.obj(
                  "model" -> Json.str(path),
                  "goal" -> Json.str(goal.toString),
                  "reps" -> Json.int(reps),
                  "reference" -> Json.opt(ref.map(Json.num)),
                  "strategies" -> Json.arr(strat)
                )
              )
            else
              Console.out(s"Ablation des stratégies P(R) — $path, but $goal")
              Console.out(s"Référence (exacte) : ${ref.map(r => f"$r%.6g").getOrElse("?")}")
              Console.out(
                f"  ctmc-exact     ${fmtVal(ctmcVal, ctmcExact)}%-22s ${tCtmc}%8.2f ms  ${ok(agree(ctmcVal))}"
              )
              mdd match
                case Right(r) =>
                  Console.out(
                    f"  mdd-symbolic   ${f"${r.reachProbability}%.6g (DD ${r.mddNodes})"}%-22s ${tMdd}%8.2f ms  ${ok(agree(Some(r.reachProbability)))}"
                  )
                case Left(e) =>
                  Console.out(f"  mdd-symbolic   ${s"n/a : $e"}%-22s ${tMdd}%8.2f ms")
              Console.out(
                f"  cegar-anytime  ${f"[${br.lower}%.4g, ${br.upper}%.4g]"}%-22s ${tCegar}%8.2f ms  ${ok(cegarAgree)}"
              )
            0
      }
    }

  private def fmtVal(v: Option[Double], exact: Boolean): String =
    v.map(x => f"$x%.6g${if exact then "" else " (borne)"}").getOrElse("?")

  private def ok(b: Boolean): String = if b then "✓" else "✗"

  // --- sweep (fiche A2) ----------------------------------------------------
  /** Métrique de balayage (sans objectif, applicable à tout modèle). */
  private def sweepMetric(
      name: String,
      net: io.quasar.core.ir.AutomataNetwork
  ): Either[String, (String, Option[Double])] =
    val ctx = net.metadata.initial.getOrElse(Context.empty)
    name match
      case "load" => Right(("load", None))
      case "fixpoints" =>
        scala.util
          .Try(SymbolicMdd.fixpointCount(net).toDouble)
          .toEither
          .left
          .map(_.getMessage)
          .map(v => ("fixpoints", Some(v)))
      case _ => // reachability : nombre d'états atteignables (indépendant de l'objectif)
        net.ordered.headOption match
          case None => Left("réseau vide")
          case Some(a0) =>
            scala.util
              .Try(
                SymbolicMdd.reachability(net, ctx, LocalState(a0.name, 0)).reachableStates.toDouble
              )
              .toEither
              .left
              .map(_.getMessage)
              .map(v => ("reachableStates", Some(v)))

  private def sweepFiles(
      models: Option[cats.data.NonEmptyList[String]],
      dir: String
  ): List[String] =
    models match
      case Some(nel) => nel.toList
      case None =>
        val d = Paths.get(dir)
        if !Files.isDirectory(d) then Nil
        else
          Files
            .list(d)
            .iterator
            .asScala
            .toList
            .map(_.toString)
            .filter(f => f.endsWith(".anx") || f.endsWith(".bnd"))
            .sorted

  private val sweepCmd =
    Opts.subcommand("sweep", "balayage de tailles : temps vs modèle (H5)") {
      (
        Opts.arguments[String]("model").orNone,
        Opts.option[String]("dir", "répertoire de modèles").withDefault(modelsDir),
        Opts.option[String]("metric", "reachability|fixpoints|load").withDefault("reachability"),
        Opts.option[Int]("reps", "répétitions de mesure (min retenu)").withDefault(1),
        Opts.flag("json", "JSON").orFalse
      ).mapN { (models, dir, metric, reps, json) => () =>
        val files = sweepFiles(models, dir)
        if files.isEmpty then Console.fail(s"aucun modèle (préciser des fichiers ou --dir)")
        else
          val rows = files
            .map { f =>
              Console.load(f) match
                case Left(e) => (f, None, None, None, None: Option[Double], None, Some(e))
                case Right(net) =>
                  var res: Either[String, (String, Option[Double])] = Left("non exécuté")
                  val t = timeMs(reps) { res = sweepMetric(metric, net) }
                  res match
                    case Left(e) =>
                      (
                        f,
                        Some(net.size),
                        Some(net.localStateCount),
                        Some(net.transitions.size),
                        None,
                        Some(t),
                        Some(e)
                      )
                    case Right((_, v)) =>
                      (
                        f,
                        Some(net.size),
                        Some(net.localStateCount),
                        Some(net.transitions.size),
                        v,
                        Some(t),
                        None
                      )
            }
            .sortBy(_._2.getOrElse(Int.MaxValue))

          if json then
            Console.emitJson(
              Json.obj(
                "metric" -> Json.str(metric),
                "reps" -> Json.int(reps),
                "results" -> Json.arr(rows.map { (f, sz, ls, tr, v, t, note) =>
                  Json.obj(
                    "model" -> Json.str(f),
                    "automata" -> Json.opt(sz.map(Json.int)),
                    "localStates" -> Json.opt(ls.map(Json.int)),
                    "transitions" -> Json.opt(tr.map(Json.int)),
                    "value" -> Json.opt(v.map(Json.num)),
                    "timeMs" -> Json.opt(t.map(Json.num)),
                    "note" -> Json.opt(note.map(Json.str))
                  )
                })
              )
            )
          else
            Console.out(s"Balayage (metric=$metric, reps=$reps) :")
            rows.foreach { (f, sz, _, tr, v, t, note) =>
              val nm = Paths.get(f).getFileName.toString
              note match
                case Some(e) => Console.out(f"  $nm%-24s  (échec : $e)")
                case None =>
                  val vs = v.map(x => f"$x%.0f").getOrElse("-")
                  Console.out(
                    f"  $nm%-24s ${sz.getOrElse(0)}%3d automates  ${tr.getOrElse(0)}%3d trans  " +
                      f"val=$vs%-10s ${t.getOrElse(0.0)}%8.2f ms"
                  )
            }
          0
      }
    }

  val command: Opts[Run] =
    modelsCmd.orElse(validateCmd).orElse(runCmd).orElse(ablationCmd).orElse(sweepCmd)

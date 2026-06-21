package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.analysis.Reachability
import io.quasar.core.Verdict
import io.quasar.core.ir.{Context, LocalState}
import io.quasar.verify.*

/** Groupe `quasar verify` — fallback exact / oracle externe (§7.6). */
object VerifyCommands:

  type Run = () => Int

  private val modelArg = Opts.argument[String]("model")
  private val goalOpt = Opts.option[String]("goal", "objectif a=j")

  private def goal(s: String): Either[Int, LocalState] = LocalState.parse(s).left.map(Console.fail)

  private def reportStatus(name: String, st: ToolStatus): Boolean = st match
    case ToolStatus.Available(v) => Console.out(s"$name détecté : $v"); true
    case ToolStatus.Missing =>
      Console.err(s"$name introuvable dans le PATH — installer puis réessayer.")
      false

  private val nusmvCmd = Opts.subcommand("nusmv", "model-checking symbolique (NuSMV)") {
    (modelArg, goalOpt).mapN { (path, g) => () =>
      if !reportStatus("NuSMV", NusmvAdapter.status()) then 2
      else
        (for net <- Console.loadModel(path); gl <- goal(g) yield (net, gl)) match
          case Left(c) => c
          case Right((net, gl)) =>
            NusmvAdapter.reachability(net, gl) match
              case Left(e) => Console.fail(e)
              case Right(r) =>
                Console.out(
                  s"NuSMV : ${r.reachable.map(b => if b then "ATTEIGNABLE" else "INATTEIGNABLE").getOrElse("indéterminé")}"
                )
                0
    }
  }

  private val stormCmd = Opts.subcommand("storm", "vérification probabiliste exacte (Storm)") {
    (modelArg, goalOpt).mapN { (path, g) => () =>
      if !reportStatus("Storm", StormAdapter.status()) then 2
      else
        (for net <- Console.loadModel(path); gl <- goal(g) yield (net, gl)) match
          case Left(c) => c
          case Right((net, gl)) =>
            StormAdapter.probability(net, gl) match
              case Left(e) => Console.fail(e)
              case Right(r) => Console.out(s"Storm : P(R) = ${r.probability.getOrElse("?")}"); 0
    }
  }

  private val mabossCmd = Opts.subcommand("maboss", "oracle MaBoSS + temps d'atteinte (V1)") {
    val samplesOpt = Opts.option[Int]("samples", "nombre de trajectoires MaBoSS").orNone
    val maxTimeOpt = Opts.option[Double]("max-time", "horizon temporel MaBoSS").orNone
    val jsonOpt = Opts.flag("json", "sortie machine (JSON)").orFalse
    (modelArg, goalOpt, samplesOpt, maxTimeOpt, jsonOpt).mapN {
      (path, g, samples, maxTime, json) => () =>
        if !reportStatus("MaBoSS", MaBossAdapter.status()) then 2
        else if !path.toLowerCase.endsWith(".bnd") then
          Console.fail("fournir le fichier .bnd MaBoSS")
        else
          (for net <- Console.loadModel(path); gl <- goal(g) yield (net, gl)) match
            case Left(c) => c
            case Right((net, gl)) =>
              val cfg = path.replaceAll("(?i)\\.bnd$", ".cfg")
              val binf = io.quasar.analysis.Quantitative
                .analyze(net, net.metadata.initial.getOrElse(Context.empty), gl)
                .probLowerBound
                .map(_.value)
                .getOrElse(0.0)
              MaBossAdapter.hittingTime(path, cfg, gl.automaton, samples, maxTime) match
                case Left(e) => Console.fail(e)
                case Right(h) =>
                  val sound = binf <= h.prob + 1e-9
                  if json then emitHittingJson(g, binf, sound, samples, maxTime, h)
                  else printHitting(g, binf, sound, h)
                  if sound then 0 else 1
    }
  }

  private def emitHittingJson(
      g: String,
      binf: Double,
      sound: Boolean,
      samples: Option[Int],
      maxTime: Option[Double],
      h: MaBossHitting
  ): Int =
    Console.emitJson(
      Json.obj(
        "goal" -> Json.str(g),
        "node" -> Json.str(h.node),
        "samples" -> Json.opt(samples.map(Json.int)),
        "maxTime" -> Json.opt(maxTime.map(Json.num)),
        "prob" -> Json.num(h.prob),
        "binf" -> Json.num(binf),
        "sound" -> Json.bool(sound),
        "quantiles" -> Json.obj(
          h.quantiles.map((q, t) => q.toString -> Json.opt(t.map(Json.num)))*
        ),
        "hittingTimeCdf" -> Json.arr(
          h.times
            .zip(h.cdf)
            .map((t, p) => Json.obj("time" -> Json.num(t), "prob" -> Json.num(p)))
            .toList
        ),
        "nodeActivation" -> Json.obj(
          h.nodeActivation.toList.sortBy(_._2).map((n, t) => n -> Json.num(t))*
        )
      )
    )

  private def printHitting(g: String, binf: Double, sound: Boolean, h: MaBossHitting): Unit =
    Console.out(f"QUASAR  binf P(R) = $binf%.6g")
    Console.out(f"MaBoSS  P($g)     = ${h.prob}%.6g")
    Console.out(s"Justesse (binf ≤ P_oracle) : ${if sound then "✓" else "✗ VIOLATION"}")
    val qs = h.quantiles
      .map((q, t) => f"q${(q * 100).toInt}=${t.map(v => f"$v%.3g").getOrElse("∞")}")
      .mkString("  ")
    Console.out(s"Temps d'atteinte (quantiles) : $qs")

  private val fallbackCmd = Opts.subcommand("fallback", "statique puis bascule externe") {
    (modelArg, goalOpt).mapN { (path, g) => () =>
      (for net <- Console.loadModel(path); gl <- goal(g) yield (net, gl)) match
        case Left(c) => c
        case Right((net, gl)) =>
          val ctx = net.metadata.initial.getOrElse(Context.empty)
          val r = Reachability.analyze(net, ctx, gl)
          r.verdict match
            case Verdict.Reachable | Verdict.Unreachable =>
              Console.out(s"QUASAR (statique) : ${r.verdict.label}")
              0
            case Verdict.Inconclusive =>
              Console.out("QUASAR statique non concluant — tentative Storm…")
              if StormAdapter.status().isAvailable then
                StormAdapter.probability(net, gl) match
                  case Right(rep) =>
                    Console.out(s"Storm : P(R) = ${rep.probability.getOrElse("?")}"); 0
                  case Left(e) => Console.fail(e)
              else
                Console.out("Storm absent — résultat : INDÉTERMINÉ")
                0
    }
  }

  val command: Opts[Run] =
    nusmvCmd.orElse(stormCmd).orElse(mabossCmd).orElse(fallbackCmd)

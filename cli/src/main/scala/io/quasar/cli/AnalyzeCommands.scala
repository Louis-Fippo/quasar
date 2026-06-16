package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.analysis.{
  Intervention,
  Quantitative,
  Reachability,
  Scenarios,
  Symbolic,
  SymbolicCtmc
}
import io.quasar.core.Verdict
import io.quasar.core.ir.{AutomataNetwork, Context, LocalState}

/** Groupe `quasar analyze` — moteur d'analyse (§7.2). */
object AnalyzeCommands:

  type Run = () => Int

  private val modelArg = Opts.argument[String]("model")
  private val goalOpt = Opts.option[String]("goal", "objectif a=j")
  private val fromOpt = Opts.option[String]("from", "contexte initial 'b=0,c=1'").orNone
  private val jsonOpt = Opts.flag("json", "sortie JSON").orFalse
  private val modeOpt =
    Opts.option[String]("mode", "oa|ua|both").withDefault("both")
  private val metricOpt =
    Opts.option[String]("metric", "prob|delay|both").withDefault("both")
  private val threshOpt = Opts.option[Double]("threshold", "seuil p pour P(R) ≥ p").orNone

  private def resolveGoal(s: String): Either[Int, LocalState] =
    LocalState.parse(s).left.map(Console.fail)

  private def resolveCtx(net: AutomataNetwork, from: Option[String]): Either[Int, Context] =
    from match
      case Some(s) => Context.parse(s).left.map(Console.fail)
      case None => Right(net.metadata.initial.getOrElse(Context.empty))

  private def load(path: String, goalS: String, from: Option[String]) =
    for
      net <- Console.loadModel(path)
      goal <- resolveGoal(goalS)
      ctx <- resolveCtx(net, from)
    yield (net, ctx, goal)

  // --- reachability --------------------------------------------------------
  private val symbolicOpt =
    Opts.flag("symbolic", "atteignabilité exacte par BDD (réseaux booléens)").orFalse

  private val reachabilityCmd =
    Opts.subcommand("reachability", "atteignabilité qualitative OA/UA (ou exacte BDD)") {
      (modelArg, goalOpt, fromOpt, modeOpt, symbolicOpt, jsonOpt).mapN {
        (path, g, from, mode, symbolic, json) => () =>
          load(path, g, from) match
            case Left(code) => code
            case Right((net, ctx, goal)) if symbolic =>
              Symbolic.reachability(net, ctx, goal) match
                case Left(e) => Console.fail(e)
                case Right(res) =>
                  if json then
                    Console.emitJson(
                      Json.obj(
                        "goal" -> Json.str(goal.toString),
                        "method" -> Json.str("symbolic-bdd"),
                        "reachable" -> Json.bool(res.goalReachable),
                        "reachableStates" -> Json.num(res.reachableStates.toDouble),
                        "bddNodes" -> Json.int(res.bddNodes)
                      )
                    )
                  else
                    Console.out(s"Objectif        : $goal")
                    Console.out(s"Atteignable     : ${yesNo(res.goalReachable)}  (exact, BDD)")
                    Console.out(s"États atteign.  : ${res.reachableStates}")
                    Console.out(s"Nœuds BDD       : ${res.bddNodes}")
                  if res.goalReachable then 0 else 1
            case Right((net, ctx, goal)) =>
              val r = Reachability.analyze(net, ctx, goal)
              if json then
                Console.emitJson(
                  Json.obj(
                    "goal" -> Json.str(goal.toString),
                    "verdict" -> Json.str(r.verdict.toString),
                    "oaReachable" -> Json.bool(r.oaReachable),
                    "uaReachable" -> Json.bool(r.uaReachable),
                    "witness" -> Json.opt(
                      r.witness.map(w =>
                        Json.obj(
                          "assignment" -> Json.arr(
                            w.assignment.toList.sortBy(_._1).map((a, l) => Json.str(s"$a=$l"))
                          ),
                          "transitions" -> Json.arr(w.transitions.map(t => Json.str(t.toString)))
                        )
                      )
                    )
                  )
                )
              else
                Console.out(s"Objectif : $goal")
                if mode != "ua" then Console.out(s"OA (nécessaire) : ${yesNo(r.oaReachable)}")
                if mode != "oa" then Console.out(s"UA (suffisant)  : ${yesNo(r.uaReachable)}")
                Console.out(s"Verdict  : ${r.verdict.label}")
                r.witness.foreach { w =>
                  val asg = w.assignment.toList.sortBy(_._1).map((a, l) => s"$a=$l").mkString(", ")
                  Console.out(s"Témoin   : $asg")
                }
              verdictCode(r.verdict)
      }
    }

  // --- quantitative --------------------------------------------------------
  private val maxStatesOpt =
    Opts.option[Int]("max-states", "plafond d'états pour la CTMC exacte").withDefault(100_000)

  private val quantitativeCmd =
    Opts.subcommand("quantitative", "P(R) (exacte CTMC ou borne) et délai T(R)") {
      (modelArg, goalOpt, fromOpt, metricOpt, maxStatesOpt, jsonOpt).mapN {
        (path, g, from, metric, maxStates, json) => () =>
          load(path, g, from) match
            case Left(code) => code
            case Right((net, ctx, goal)) =>
              val q = Quantitative.analyze(net, ctx, goal, maxStates)
              val showP = metric != "delay"
              val showD = metric != "prob"
              if json then
                Console.emitJson(
                  Json.obj(
                    "goal" -> Json.str(goal.toString),
                    "probability" -> Json.opt(q.probLowerBound.map(b => Json.num(b.value))),
                    "probExact" -> Json.bool(
                      q.probLowerBound.exists(_.approx == io.quasar.core.Approx.Exact)
                    ),
                    "earliestDelay" -> Json.opt(q.earliestDelay.map(b => Json.num(b.value))),
                    "meanTime" -> Json.opt(q.meanTime.map(b => Json.num(b.value))),
                    "scenario" -> Json.arr(q.scenario.map(t => Json.str(t.toString)))
                  )
                )
              else
                Console.out(s"Objectif : $goal")
                if showP then
                  q.probLowerBound match
                    case Some(b) => Console.out(probLine(b))
                    case None => Console.out("P(R) ≥ 0  (aucun témoin trouvé)")
                  q.meanTime.foreach(b =>
                    Console.out(f"E[T] = ${b.value}%.6g  (temps moyen, CTMC)")
                  )
                if showD then
                  q.earliestDelay match
                    case Some(b) => Console.out(f"T(R) = ${b.value}%.6g  (délai au plus tôt)")
                    case None => Console.out("T(R) = ∞  (inatteignable statiquement)")
              0
      }
    }

  // --- probability ---------------------------------------------------------
  private val probabilityCmd =
    Opts.subcommand("probability", "P(R) (exacte CTMC/MTBDD ou borne inférieure)") {
      (modelArg, goalOpt, fromOpt, threshOpt, symbolicOpt, jsonOpt).mapN {
        (path, g, from, thr, symbolic, json) => () =>
          load(path, g, from) match
            case Left(code) => code
            case Right((net, ctx, goal)) =>
              // p : Either[erreur, (valeur, exact?)]
              val computed: Either[String, (Double, Boolean)] =
                if symbolic then
                  SymbolicCtmc.reachProbability(net, ctx, goal).map(r => (r.reachProbability, true))
                else
                  val q = Quantitative.analyze(net, ctx, goal)
                  Right(
                    (
                      q.probLowerBound.map(_.value).getOrElse(0.0),
                      q.probLowerBound.exists(_.approx == io.quasar.core.Approx.Exact)
                    )
                  )
              computed match
                case Left(e) => Console.fail(e)
                case Right((p, exact)) =>
                  val meets = thr.map(t => p >= t)
                  if json then
                    Console.emitJson(
                      Json.obj(
                        "goal" -> Json.str(goal.toString),
                        "probability" -> Json.num(p),
                        "exact" -> Json.bool(exact),
                        "method" -> Json.str(if symbolic then "symbolic-mtbdd" else "ctmc"),
                        "threshold" -> Json.opt(thr.map(Json.num)),
                        "meetsThreshold" -> Json.opt(meets.map(Json.bool))
                      )
                    )
                  else
                    val tag =
                      if exact then if symbolic then "exact, MTBDD" else "exact, CTMC"
                      else "borne inférieure"
                    Console.out(f"P($goal) ${if exact then "=" else "≥"} $p%.6g  ($tag)")
                    meets.foreach(mm => Console.out(s"P(R) ≥ ${thr.get} : ${yesNo(mm)}"))
                  meets match
                    case Some(false) => 1
                    case _ => 0
      }
    }

  // --- delay ---------------------------------------------------------------
  private val delayCmd =
    Opts.subcommand("delay", "délai minimal T(R)") {
      (modelArg, goalOpt, fromOpt, jsonOpt).mapN { (path, g, from, json) => () =>
        load(path, g, from) match
          case Left(code) => code
          case Right((net, ctx, goal)) =>
            val d = Quantitative.earliestDelay(net, ctx, goal)
            if json then
              Console.emitJson(
                Json.obj(
                  "goal" -> Json.str(goal.toString),
                  "delay" -> Json.opt(d.map(b => Json.num(b.value)))
                )
              )
            else
              d match
                case Some(b) => Console.out(f"T($goal) = ${b.value}%.6g")
                case None => Console.out(s"T($goal) = ∞ (inatteignable)")
            0
      }
    }

  // --- scenario ------------------------------------------------------------
  private val kindOpt =
    Opts.option[String]("kind", "most-probable|fastest").withDefault("most-probable")
  private val kOpt = Opts.option[Int]("top-k", "nombre de scénarios", short = "k").withDefault(1)

  private val scenarioCmd =
    Opts.subcommand("scenario", "k meilleurs scénarios (plus probables / plus rapides)") {
      (modelArg, goalOpt, fromOpt, kindOpt, kOpt, jsonOpt).mapN {
        (path, g, from, kindS, k, json) => () =>
          load(path, g, from) match
            case Left(code) => code
            case Right((net, ctx, goal)) =>
              val kind =
                if kindS == "fastest" then Scenarios.Kind.Fastest else Scenarios.Kind.MostProbable
              val scs = Scenarios.topK(net, ctx, goal, k, kind)
              if json then
                Console.emitJson(
                  Json.arr(
                    scs.map(sc =>
                      Json.obj(
                        "probability" -> Json.num(sc.probability),
                        "delay" -> Json.num(sc.delay),
                        "transitions" -> Json.arr(sc.transitions.map(t => Json.str(t.toString)))
                      )
                    )
                  )
                )
              else if scs.isEmpty then Console.out(s"aucun scénario vers $goal")
              else
                Console.out(s"$kindS — ${scs.size} scénario(s) pour $goal :")
                scs.zipWithIndex.foreach { (sc, i) =>
                  Console.out(f"  #${i + 1} : P=${sc.probability}%.6g, délai=${sc.delay}%.4g")
                  sc.transitions.foreach(t => Console.out(s"       $t"))
                }
              0
      }
    }

  // --- cutsets -------------------------------------------------------------
  private val cutsetsCmd =
    Opts.subcommand("cutsets", "ensembles de coupe (intervention)") {
      (
        modelArg,
        goalOpt,
        fromOpt,
        Opts.option[Int]("max-size", "taille max").withDefault(3),
        jsonOpt
      )
        .mapN { (path, g, from, maxSize, json) => () =>
          load(path, g, from) match
            case Left(code) => code
            case Right((net, ctx, goal)) =>
              val cs = Intervention.cutsets(net, ctx, goal, maxSize)
              if json then
                Console.emitJson(
                  Json.arr(
                    cs.map(s =>
                      Json.arr(s.toList.sortBy(_.toString).map(ls => Json.str(ls.toString)))
                    )
                  )
                )
              else if cs.isEmpty then Console.out(s"aucun ensemble de coupe ≤ $maxSize")
              else
                Console.out(s"Ensembles de coupe pour $goal :")
                cs.foreach(s => Console.out(s"  { ${s.toList.sortBy(_.toString).mkString(", ")} }"))
              0
        }
    }

  // --- mutations -----------------------------------------------------------
  private val mutationsCmd =
    Opts.subcommand("mutations", "mutations (gain/perte de fonction)") {
      (modelArg, goalOpt, fromOpt, Opts.option[String]("effect", "enable|disable"), jsonOpt)
        .mapN { (path, g, from, effect, json) => () =>
          load(path, g, from) match
            case Left(code) => code
            case Right((net, ctx, goal)) =>
              val enable = effect != "disable"
              val ms = Intervention.mutations(net, ctx, goal, enable)
              if json then
                Console.emitJson(
                  Json.arr(
                    ms.map(m =>
                      Json.obj(
                        "target" -> Json.str(m.target.toString),
                        "makesReachable" -> Json.bool(m.makesReachable)
                      )
                    )
                  )
                )
              else if ms.isEmpty then Console.out(s"aucune mutation '$effect' pour $goal")
              else
                Console.out(s"Mutations ($effect) pour $goal :")
                ms.foreach(m => Console.out(s"  $m"))
              0
        }
    }

  // --- compare -------------------------------------------------------------
  private val compareCmd =
    Opts.subcommand("compare", "bornes QUASAR vs oracle exact (cône)") {
      (modelArg, goalOpt, fromOpt, jsonOpt).mapN { (path, g, from, json) => () =>
        load(path, g, from) match
          case Left(code) => code
          case Right((net, ctx, goal)) =>
            val r = Reachability.analyze(net, ctx, goal)
            val q = Quantitative.analyze(net, ctx, goal)
            // l'UA (recherche exacte dans le cône) sert d'oracle d'atteignabilité
            val exact = r.uaReachable
            val sound = !(r.uaReachable && !exact) && !(exact && !r.oaReachable)
            if json then
              Console.emitJson(
                Json.obj(
                  "goal" -> Json.str(goal.toString),
                  "oaReachable" -> Json.bool(r.oaReachable),
                  "exactReachable" -> Json.bool(exact),
                  "probLowerBound" -> Json.opt(q.probLowerBound.map(b => Json.num(b.value))),
                  "sound" -> Json.bool(sound)
                )
              )
            else
              Console.out(s"Objectif        : $goal")
              Console.out(s"OA (nécessaire) : ${yesNo(r.oaReachable)}")
              Console.out(s"Exact (oracle)  : ${yesNo(exact)}")
              q.probLowerBound.foreach(b => Console.out(f"P(R) ≥ ${b.value}%.6g  (QUASAR)"))
              Console.out(
                s"Justesse        : ${if sound then "✓ bornes cohérentes" else "✗ INCOHÉRENT (bug)"}"
              )
            if sound then 0 else 1
      }
    }

  private def probLine(b: io.quasar.core.Bound[Double]): String =
    b.approx match
      case io.quasar.core.Approx.Exact => f"P(R) = ${b.value}%.6g  (exact, CTMC)"
      case _ => f"P(R) ≥ ${b.value}%.6g  (borne inférieure)"

  private def yesNo(b: Boolean): String = if b then "oui" else "non"
  private def verdictCode(v: Verdict): Int = v match
    case Verdict.Unreachable => 1
    case _ => 0

  val command: Opts[Run] =
    reachabilityCmd
      .orElse(quantitativeCmd)
      .orElse(probabilityCmd)
      .orElse(delayCmd)
      .orElse(scenarioCmd)
      .orElse(cutsetsCmd)
      .orElse(mutationsCmd)
      .orElse(compareCmd)

package io.quasar.py

import io.quasar.analysis.{QuantCegar, Quantitative, Reachability, SymbolicMdd, Topology}
import io.quasar.biolqm.BioLqm
import io.quasar.cli.{Console, Json}
import io.quasar.core.ir.{AutomataNetwork, Context, LocalState}

/**
 * Façade JVM pour les bindings Python `pyquasar` (Phase 3, §2).
 *
 * Méthodes statiques sans état renvoyant des **chaînes JSON**, faciles à appeler depuis Python via
 * jpype (JVM en processus) ou py4j (passerelle). Toute la logique métier vit dans
 * `analysis`/`io`/`biolqm` ; cette façade ne fait que charger, déléguer et sérialiser.
 */
object Quasar:

  /** Version et capacités. */
  def version(): String =
    Json.render(Json.obj("name" -> Json.str("quasar"), "version" -> Json.str("0.1.0-SNAPSHOT")))

  /** Résumé structurel d'un modèle (chemin -> JSON). */
  def info(path: String): String =
    withModel(path) { net =>
      Json.obj(
        "name" -> Json.str(net.name),
        "automata" -> Json.int(net.size),
        "localStates" -> Json.int(net.localStateCount),
        "transitions" -> Json.int(net.transitions.size),
        "multivalued" -> Json.int(net.automata.values.count(_.levels > 2))
      )
    }

  /** Atteignabilité OA/UA. */
  def reachability(path: String, goal: String, from: String): String =
    withGoal(path, goal, from) { (net, ctx, g) =>
      val r = Reachability.analyze(net, ctx, g)
      Json.obj(
        "goal" -> Json.str(g.toString),
        "verdict" -> Json.str(r.verdict.toString),
        "oaReachable" -> Json.bool(r.oaReachable),
        "uaReachable" -> Json.bool(r.uaReachable)
      )
    }

  /** Atteignabilité exacte symbolique (MDD) : verdict + nombre d'états. */
  def reachabilitySymbolic(path: String, goal: String, from: String): String =
    withGoal(path, goal, from) { (net, ctx, g) =>
      val r = SymbolicMdd.reachability(net, ctx, g)
      Json.obj(
        "goal" -> Json.str(g.toString),
        "reachable" -> Json.bool(r.goalReachable),
        "reachableStates" -> Json.num(r.reachableStates.toDouble)
      )
    }

  /** Quantitatif : P(R) (exacte CTMC si possible), délai T(R), temps moyen. */
  def quantitative(path: String, goal: String, from: String): String =
    withGoal(path, goal, from) { (net, ctx, g) =>
      val q = Quantitative.analyze(net, ctx, g)
      Json.obj(
        "goal" -> Json.str(g.toString),
        "probability" -> Json.opt(q.probLowerBound.map(b => Json.num(b.value))),
        "probExact" -> Json.bool(q.probLowerBound.exists(_.approx == io.quasar.core.Approx.Exact)),
        "earliestDelay" -> Json.opt(q.earliestDelay.map(b => Json.num(b.value))),
        "meanTime" -> Json.opt(q.meanTime.map(b => Json.num(b.value)))
      )
    }

  /** Encadrement quantitatif sound [lo, hi] (CEGAR quantitatif). */
  def bracket(path: String, goal: String, from: String, budget: Int): String =
    withGoal(path, goal, from) { (net, ctx, g) =>
      val br = QuantCegar.bracket(net, ctx, g, budget)
      Json.obj(
        "goal" -> Json.str(g.toString),
        "lower" -> Json.num(br.lower),
        "upper" -> Json.num(br.upper),
        "exact" -> Json.bool(br.exact())
      )
    }

  /** Points fixes (états stables). */
  def fixpoints(path: String): String =
    withModel(path) { net =>
      val r = Topology.fixpoints(net)
      Json.obj(
        "truncated" -> Json.bool(r.truncated),
        "count" -> Json.int(r.items.size),
        "fixpoints" -> Json.arr(r.items.map { s =>
          Json.str(s.toList.sortBy(_._1).map((a, l) => s"$a=$l").mkString(", "))
        })
      )
    }

  /** Projette en bioLQM et exporte (format `sbml`, `bnet`, ...). */
  def exportModel(path: String, out: String, format: String): String =
    Console.load(path) match
      case Left(e) => errorJson(e)
      case Right(net) =>
        BioLqm.exportFile(net, out, format) match
          case Right(_) => Json.render(Json.obj("ok" -> Json.bool(true), "output" -> Json.str(out)))
          case Left(e) => errorJson(e)

  // --- utilitaires ---------------------------------------------------------

  private def withModel(path: String)(f: AutomataNetwork => Json.J): String =
    Console.load(path) match
      case Left(e) => errorJson(e)
      case Right(net) => Json.render(f(net))

  private def withGoal(path: String, goal: String, from: String)(
      f: (AutomataNetwork, Context, LocalState) => Json.J
  ): String =
    Console.load(path) match
      case Left(e) => errorJson(e)
      case Right(net) =>
        LocalState.parse(goal) match
          case Left(e) => errorJson(e)
          case Right(g) =>
            val ctxRes =
              if from == null || from.trim.isEmpty then
                Right(net.metadata.initial.getOrElse(Context.empty))
              else Context.parse(from)
            ctxRes match
              case Left(e) => errorJson(e)
              case Right(ctx) => Json.render(f(net, ctx, g))

  private def errorJson(msg: String): String =
    Json.render(Json.obj("error" -> Json.str(msg)))

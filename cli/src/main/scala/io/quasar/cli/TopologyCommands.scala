package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.analysis.Topology
import io.quasar.core.glc.Sign

/** Groupe `quasar topology` — structure et attracteurs (§7.3). */
object TopologyCommands:

  type Run = () => Int

  private val modelArg = Opts.argument[String]("model")
  private val jsonOpt = Opts.flag("json", "sortie JSON").orFalse

  private def fmtState(s: Map[String, Int]): String =
    s.toList.sortBy(_._1).map((a, l) => s"$a=$l").mkString(", ")

  private val sccCmd = Opts.subcommand("scc", "composantes fortement connexes (rétroaction)") {
    (modelArg, jsonOpt).mapN { (path, json) => () =>
      Console.loadModel(path) match
        case Left(c) => c
        case Right(net) =>
          val comps = Topology.scc(net)
          if Console.jsonEnabled(json) then
            Console.emitJson(Json.arr(comps.map(c => Json.arr(c.toList.sorted.map(Json.str)))))
          else if comps.isEmpty then Console.out("aucune SCC non triviale")
          else
            comps.zipWithIndex.foreach((c, i) =>
              Console.out(s"SCC ${i + 1}: ${c.toList.sorted.mkString(", ")}")
            )
          0
    }
  }

  private val cyclesCmd = Opts.subcommand("cycles", "circuits signés (Thomas)") {
    (modelArg, Opts.option[String]("sign", "positive|negative|all").withDefault("all"), jsonOpt)
      .mapN { (path, signS, json) => () =>
        Console.loadModel(path) match
          case Left(c) => c
          case Right(net) =>
            val filter = signS match
              case "positive" => Some(Sign.Positive)
              case "negative" => Some(Sign.Negative)
              case _ => None
            val cs = Topology.circuits(net, filter)
            if Console.jsonEnabled(json) then
              Console.emitJson(
                Json.arr(
                  cs.map(c =>
                    Json.obj(
                      "sign" -> Json.str(c.sign.toString),
                      "nodes" -> Json.arr(c.nodes.map(Json.str))
                    )
                  )
                )
              )
            else if cs.isEmpty then Console.out("aucun circuit")
            else cs.foreach(c => Console.out(c.toString))
            0
      }
  }

  private val feedbackCmd = Opts.subcommand("feedback", "circuits de rétroaction + signes") {
    (modelArg, jsonOpt).mapN { (path, json) => () =>
      Console.loadModel(path) match
        case Left(c) => c
        case Right(net) =>
          val cs = Topology.circuits(net)
          val pos = cs.count(_.sign == Sign.Positive)
          val neg = cs.count(_.sign == Sign.Negative)
          if Console.jsonEnabled(json) then
            Console.emitJson(
              Json.obj(
                "total" -> Json.int(cs.size),
                "positive" -> Json.int(pos),
                "negative" -> Json.int(neg)
              )
            )
          else
            Console.out(s"Circuits : ${cs.size} (positifs: $pos, négatifs: $neg)")
            cs.foreach(c => Console.out(s"  $c"))
          0
    }
  }

  private val fixpointsCmd = Opts.subcommand("fixpoints", "points fixes (états stables)") {
    (modelArg, jsonOpt).mapN { (path, json) => () =>
      Console.loadModel(path) match
        case Left(c) => c
        case Right(net) =>
          val r = Topology.fixpoints(net)
          if Console.jsonEnabled(json) then
            Console.emitJson(
              Json.obj(
                "truncated" -> Json.bool(r.truncated),
                "fixpoints" -> Json.arr(r.items.map(s => Json.str(fmtState(s))))
              )
            )
          else if r.truncated then
            Console.out("espace d'états trop grand (résultat borné, non calculé)")
          else if r.items.isEmpty then Console.out("aucun point fixe")
          else r.items.foreach(s => Console.out(fmtState(s)))
          0
    }
  }

  private val attractorsCmd = Opts.subcommand("attractors", "attracteurs (exact, borné)") {
    (modelArg, Opts.option[String]("method", "exact|abstract").withDefault("exact"), jsonOpt)
      .mapN { (path, _, json) => () =>
        Console.loadModel(path) match
          case Left(c) => c
          case Right(net) =>
            val r = Topology.attractors(net)
            if Console.jsonEnabled(json) then
              Console.emitJson(
                Json.obj(
                  "truncated" -> Json.bool(r.truncated),
                  "attractors" -> Json.arr(
                    r.items.map(a => Json.arr(a.map(s => Json.str(fmtState(s)))))
                  )
                )
              )
            else if r.truncated then
              Console.out("espace d'états trop grand (méthode exacte non applicable)")
            else
              Console.out(s"Attracteurs : ${r.items.size}")
              r.items.zipWithIndex.foreach { (a, i) =>
                val kind = if a.size == 1 then "point fixe" else s"cyclique (${a.size} états)"
                Console.out(s"  ${i + 1}. [$kind] ${a.map(fmtState).mkString(" | ")}")
              }
            0
      }
  }

  private val trapCmd = Opts.subcommand("trap-spaces", "trap-spaces (espaces piège)") {
    (modelArg, Opts.flag("minimal", "minimaux seulement").orFalse, jsonOpt).mapN {
      (path, min, json) => () =>
        Console.loadModel(path) match
          case Left(c) => c
          case Right(net) =>
            val r = Topology.trapSpaces(net, minimalOnly = min)
            def show(m: Map[String, Int]) = if m.isEmpty then "(espace complet)" else fmtState(m)
            if Console.jsonEnabled(json) then
              Console.emitJson(
                Json.obj(
                  "truncated" -> Json.bool(r.truncated),
                  "trapSpaces" -> Json.arr(r.items.map(s => Json.str(show(s))))
                )
              )
            else if r.truncated then Console.out("espace trop grand (résultat borné, non calculé)")
            else if r.items.isEmpty then Console.out("aucun trap-space")
            else r.items.foreach(s => Console.out(show(s)))
            0
    }
  }

  val command: Opts[Run] =
    sccCmd
      .orElse(cyclesCmd)
      .orElse(feedbackCmd)
      .orElse(fixpointsCmd)
      .orElse(attractorsCmd)
      .orElse(trapCmd)

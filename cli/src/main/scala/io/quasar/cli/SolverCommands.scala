package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.core.glc.{LocalCausalityGraph, Solution}
import io.quasar.core.ir.{Context, LocalState}

/** Groupe `quasar solver` — accès bas niveau au GLC / ⌈Gω⌉ (§7.5). */
object SolverCommands:

  type Run = () => Int

  private val modelArg = Opts.argument[String]("model")
  private val goalOpt = Opts.option[String]("goal", "objectif a=j")
  private val fromOpt = Opts.option[String]("from", "contexte initial").orNone
  private val outOpt =
    Opts.option[String]("output", "format de sortie dot|json", short = "o").orNone

  private def build(path: String, goalS: String, from: Option[String]) =
    for
      net <- Console.loadModel(path)
      goal <- LocalState.parse(goalS).left.map(Console.fail)
      ctx <- from match
        case Some(s) => Context.parse(s).left.map(Console.fail)
        case None => Right(net.metadata.initial.getOrElse(Context.empty))
    yield LocalCausalityGraph.build(net, ctx, goal)

  private val glcCmd = Opts.subcommand("glc", "construit et exporte le GLC") {
    (modelArg, goalOpt, fromOpt, outOpt).mapN { (path, g, from, out) => () =>
      build(path, g, from) match
        case Left(code) => code
        case Right(glc) =>
          out match
            case Some("json") => Console.out(renderJson(glc)); 0
            case Some("dot") => Console.out(renderDot(glc)); 0
            case _ => renderText(glc); 0
    }
  }

  private val qglcCmd = Opts.subcommand("qglc", "construit ⌈Gω_ς⌉ + valuations") {
    (modelArg, goalOpt, fromOpt, outOpt).mapN { (path, g, from, out) => () =>
      build(path, g, from) match
        case Left(code) => code
        case Right(glc) =>
          out match
            case Some("json") => Console.out(renderJson(glc, valued = true)); 0
            case Some("dot") => Console.out(renderDot(glc, valued = true)); 0
            case _ => renderText(glc, valued = true); 0
    }
  }

  private def renderText(glc: LocalCausalityGraph, valued: Boolean = false): Unit =
    Console.out(
      s"GLC pour ${glc.root} (${glc.objectives.size} objectifs, ${glc.solutionCount} solutions)"
    )
    for (ls, sols) <- glc.solutions.toList.sortBy(_._1.toString) do
      Console.out(s"objectif $ls :")
      if sols.isEmpty then Console.out("  ⊥ (aucune solution — structurellement inatteignable)")
      for s <- sols do
        val req = if s.requires.isEmpty then "∅" else s.requires.mkString(", ")
        val tag = if valued then f"  [délai≈${pathDelay(s)}%.4g]" else ""
        Console.out(s"  via ${s.path}  requiert {$req}$tag")

  private def pathDelay(s: Solution): Double = s.path.steps.iterator.map(_.dist.mean).sum

  private def renderDot(glc: LocalCausalityGraph, valued: Boolean = false): String =
    val sb = StringBuilder("digraph GLC {\n  rankdir=LR;\n")
    for (ls, sols) <- glc.solutions do
      sb ++= s"""  "$ls" [shape=ellipse,style=filled,fillcolor=lightblue];\n"""
      for (s, i) <- sols.zipWithIndex do
        val sid = s""""sol_${ls}_$i""""
        val lbl = if valued then f"sol\\n${pathDelay(s)}%.3g" else "sol"
        sb ++= s"""  $sid [shape=box,label="$lbl"];\n"""
        sb ++= s"""  "$ls" -> $sid;\n"""
        for c <- s.requires do sb ++= s"""  $sid -> "$c";\n"""
    sb ++= "}\n"
    sb.toString

  private def renderJson(glc: LocalCausalityGraph, valued: Boolean = false): String =
    val objs = glc.solutions.toList.sortBy(_._1.toString).map { (ls, sols) =>
      Json.obj(
        "objective" -> Json.str(ls.toString),
        "solutions" -> Json.arr(sols.map { s =>
          Json.obj(
            "path" -> Json.str(s.path.toString),
            "requires" -> Json.arr(s.requires.map(c => Json.str(c.toString))),
            "delay" -> (if valued then Json.num(pathDelay(s)) else Json.JNull)
          )
        })
      )
    }
    Json.render(
      Json.obj("root" -> Json.str(glc.root.target.toString), "objectives" -> Json.arr(objs))
    )

  val command: Opts[Run] = glcCmd.orElse(qglcCmd)

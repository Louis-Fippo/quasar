package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.analysis.Transform
import io.quasar.core.ir.LocalState
import io.quasar.io.AnxFormat

/** Groupe `quasar transform` — transformations de modèle (§7.4). */
object TransformCommands:

  type Run = () => Int

  private val modelArg = Opts.argument[String]("model")
  private val outOpt = Opts.option[String]("output", "fichier de sortie ANX", short = "o").orNone

  private def emit(net: io.quasar.core.ir.AutomataNetwork, out: Option[String]): Int =
    out match
      case Some(o) =>
        io.quasar.io.Exporter.toFile(net, o, Some(io.quasar.io.Format.Anx)) match
          case Right(_) => Console.out(s"écrit -> $o"); 0
          case Left(e) => Console.fail(e.toString)
      case None => Console.out(AnxFormat.render(net)); 0

  private val reduceCmd = Opts.subcommand("reduce", "réduction orientée-but (cône d'influence)") {
    (modelArg, Opts.option[String]("goal", "objectif a=j"), outOpt).mapN { (path, g, out) => () =>
      (for
        net <- Console.loadModel(path)
        goal <- LocalState.parse(g).left.map(Console.fail)
      yield (net, goal)) match
        case Left(c) => c
        case Right((net, goal)) =>
          val r = Transform.reduce(net, goal)
          Console.out(s"# réduit : ${net.size} -> ${r.size} automates")
          emit(r, out)
    }
  }

  private val sliceCmd = Opts.subcommand("slice", "tranche autour d'un composant") {
    (modelArg, Opts.option[String]("component", "automate central"), outOpt).mapN {
      (path, comp, out) => () =>
        Console.loadModel(path) match
          case Left(c) => c
          case Right(net) =>
            if !net.automata.contains(comp) then Console.fail(s"composant inconnu : $comp")
            else
              val r = Transform.slice(net, comp)
              Console.out(s"# tranche autour de $comp : ${r.size} automates")
              emit(r, out)
    }
  }

  private val booleanizeCmd = Opts.subcommand("booleanize", "multivalué -> booléen") {
    (modelArg, outOpt).mapN { (path, out) => () =>
      Console.loadModel(path) match
        case Left(c) => c
        case Right(net) =>
          Transform.booleanize(net) match
            case Right(r) => Console.out("# déjà booléen (aucun changement)"); emit(r, out)
            case Left(e) => Console.fail(e)
    }
  }

  val command: Opts[Run] = reduceCmd.orElse(sliceCmd).orElse(booleanizeCmd)

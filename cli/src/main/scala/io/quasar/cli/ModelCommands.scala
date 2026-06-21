package io.quasar.cli

import cats.syntax.all.*
import com.monovore.decline.*
import io.quasar.analysis.{ModelStats, Transform}
import io.quasar.core.ir.*
import io.quasar.io.{Exporter, Format}

/** Groupe `quasar model` — cycle de vie des modèles (§7.1). */
object ModelCommands:

  type Run = () => Int

  private val modelArg = Opts.argument[String]("model")
  private val jsonOpt = Opts.flag("json", "sortie machine (JSON)").orFalse
  private val fmtOpt = Opts.option[String]("format", "format d'entrée/sortie").orNone
  private val outOpt = Opts.option[String]("output", "fichier de sortie", short = "o").orNone

  // --- import --------------------------------------------------------------
  private val importCmd = Opts.subcommand("import", "importe un fichier vers l'IR ANX") {
    (modelArg, fmtOpt, outOpt).mapN { (path, fmt, out) => () =>
      Console.loadModel(path, fmt) match
        case Left(code) => code
        case Right(net) =>
          out match
            case Some(o) =>
              Exporter.toFile(net, o, Some(Format.Anx)) match
                case Right(_) => Console.out(s"importé -> $o"); 0
                case Left(e) => Console.fail(e.toString)
            case None =>
              Console.out(io.quasar.io.AnxFormat.render(net)); 0
    }
  }

  // --- export --------------------------------------------------------------
  /** Identifiant d'export bioLQM, depuis le nom de format ou l'extension de sortie. */
  private def bioExportId(fmt: Option[String], out: Option[String]): Option[String] =
    val byName = fmt.map(_.toLowerCase).collect {
      case "sbml-qual" | "sbml" => "sbml"
      case "bnet" | "boolnet" => "bnet"
      case "ginml" => "ginml"
      case "booleannet" => "booleannet"
    }
    byName.orElse(out.map(_.toLowerCase).collect {
      case o if o.endsWith(".sbml") || o.endsWith(".xml") => "sbml"
      case o if o.endsWith(".bnet") => "bnet"
      case o if o.endsWith(".ginml") => "ginml"
    })

  private val exportCmd = Opts.subcommand("export", "exporte un modèle vers un format") {
    (modelArg, fmtOpt, outOpt).mapN { (path, fmt, out) => () =>
      Console.loadModel(path) match
        case Left(code) => code
        case Right(net) =>
          bioExportId(fmt, out) match
            case Some(bioId) =>
              out match
                case None => Console.fail(s"le format $bioId requiert un fichier de sortie (-o)")
                case Some(o) =>
                  io.quasar.biolqm.BioLqm.exportFile(net, o, bioId) match
                    case Right(_) => Console.out(s"exporté ($bioId) -> $o"); 0
                    case Left(e) => Console.fail(e)
            case None =>
              val target = fmt.flatMap(Format.parse).orElse(out.flatMap(Format.fromExtension))
              target match
                case None => Console.fail("préciser --format ou un -o avec extension connue")
                case Some(f) =>
                  out match
                    case Some(o) =>
                      Exporter.toFile(net, o, Some(f)) match
                        case Right(_) => Console.out(s"exporté -> $o"); 0
                        case Left(e) => Console.fail(e.toString)
                    case None =>
                      Exporter.render(net, f) match
                        case Right(text) => Console.out(text); 0
                        case Left(e) => Console.fail(e.toString)
    }
  }

  // --- convert -------------------------------------------------------------
  private val convertCmd = Opts.subcommand("convert", "import + export (formats déduits)") {
    (Opts.argument[String]("in"), Opts.argument[String]("out")).mapN { (in, out) => () =>
      Console.loadModel(in) match
        case Left(code) => code
        case Right(net) =>
          Exporter.toFile(net, out) match
            case Right(_) => Console.out(s"$in -> $out"); 0
            case Left(e) => Console.fail(e.toString)
    }
  }

  // --- validate ------------------------------------------------------------
  private val validateCmd = Opts.subcommand("validate", "vérifie la cohérence du modèle") {
    (modelArg, jsonOpt).mapN { (path, json) => () =>
      Console.loadModel(path) match
        case Left(code) => code
        case Right(net) =>
          val diags = Validation.validate(net)
          val errors = diags.count(_.severity == Severity.Error)
          if Console.jsonEnabled(json) then
            Console.emitJson(
              Json.obj(
                "valid" -> Json.bool(errors == 0),
                "errors" -> Json.int(errors),
                "warnings" -> Json.int(diags.size - errors),
                "diagnostics" -> Json.arr(
                  diags.map(d =>
                    Json.obj(
                      "severity" -> Json.str(d.severity.toString),
                      "message" -> Json.str(d.message)
                    )
                  )
                )
              )
            )
          else if diags.isEmpty then Console.out("✓ modèle valide (aucun diagnostic)")
          else diags.foreach(d => Console.out(d.toString))
          if errors > 0 then 1 else 0
    }
  }

  // --- info ----------------------------------------------------------------
  private val infoCmd = Opts.subcommand("info", "résumé du modèle") {
    (modelArg, jsonOpt).mapN { (path, json) => () =>
      Console.loadModel(path) match
        case Left(code) => code
        case Right(net) =>
          val multivalued = net.automata.values.count(_.levels > 2)
          if Console.jsonEnabled(json) then
            Console.emitJson(
              Json.obj(
                "name" -> Json.str(net.name),
                "automata" -> Json.int(net.size),
                "localStates" -> Json.int(net.localStateCount),
                "transitions" -> Json.int(net.transitions.size),
                "multivalued" -> Json.int(multivalued),
                "source" -> Json.opt(net.metadata.source.map(Json.str))
              )
            )
          else
            Console.out(s"Modèle    : ${net.name}")
            net.metadata.source.foreach(s => Console.out(s"Source    : $s"))
            Console.out(s"Automates : ${net.size}")
            Console.out(s"Σ|S(a)|   : ${net.localStateCount}")
            Console.out(s"Transitions: ${net.transitions.size}")
            Console.out(s"Multivalués: $multivalued")
            net.metadata.initial.foreach { ctx =>
              val inits = ctx.states.toList.sortBy(_._1).flatMap((a, ls) => ls.map(l => s"$a=$l"))
              if inits.nonEmpty then Console.out(s"Init      : ${inits.mkString(", ")}")
            }
          0
    }
  }

  // --- inspect -------------------------------------------------------------
  private val inspectCmd = Opts.subcommand("inspect", "détail des automates et transitions") {
    modelArg.map { path => () =>
      Console.loadModel(path) match
        case Left(code) => code
        case Right(net) =>
          for au <- net.ordered do
            Console.out(s"automate ${au.name} [${au.states.mkString(", ")}]")
            if au.transitions.isEmpty then Console.out("  (aucune transition)")
            for t <- au.transitions do Console.out(s"  $t  @ ${fmtDist(t.dist)}")
          0
    }
  }

  private def fmtDist(d: Distribution): String = d match
    case Distribution.Exponential(r) => s"exp($r)"
    case Distribution.Erlang(k, r) => s"erlang($k,$r)"
    case Distribution.PhaseType(rs) => s"phase(${rs.mkString(",")})"

  // --- stats ---------------------------------------------------------------
  private val statsCmd = Opts.subcommand("stats", "métriques de graphe") {
    (modelArg, jsonOpt).mapN { (path, json) => () =>
      Console.loadModel(path) match
        case Left(code) => code
        case Right(net) =>
          val s = ModelStats.stats(net)
          if Console.jsonEnabled(json) then
            Console.emitJson(
              Json.obj(
                "automata" -> Json.int(s.automata),
                "localStates" -> Json.int(s.localStates),
                "transitions" -> Json.int(s.transitions),
                "edges" -> Json.int(s.edges),
                "density" -> Json.num(s.density),
                "maxInDegree" -> Json.int(s.maxInDegree),
                "maxOutDegree" -> Json.int(s.maxOutDegree),
                "feedbackComponents" -> Json.int(s.feedbackComponents)
              )
            )
          else
            Console.out(s"Automates          : ${s.automata}")
            Console.out(s"États locaux       : ${s.localStates}")
            Console.out(s"Transitions        : ${s.transitions}")
            Console.out(s"Arcs d'interaction : ${s.edges}")
            Console.out(f"Densité            : ${s.density}%.4f")
            Console.out(s"Degré entrant max  : ${s.maxInDegree}")
            Console.out(s"Degré sortant max  : ${s.maxOutDegree}")
            Console.out(s"Composants rétroaction (SCC) : ${s.feedbackComponents}")
          0
    }
  }

  // --- diff ----------------------------------------------------------------
  private val diffCmd = Opts.subcommand("diff", "différences entre deux modèles") {
    (Opts.argument[String]("m1"), Opts.argument[String]("m2"), jsonOpt).mapN {
      (p1, p2, json) => () =>
        (for n1 <- Console.loadModel(p1); n2 <- Console.loadModel(p2) yield (n1, n2)) match
          case Left(code) => code
          case Right((n1, n2)) =>
            val d = ModelStats.diff(n1, n2)
            if Console.jsonEnabled(json) then
              Console.emitJson(
                Json.obj(
                  "onlyInLeft" -> Json.arr(d.onlyInLeft.toList.sorted.map(Json.str)),
                  "onlyInRight" -> Json.arr(d.onlyInRight.toList.sorted.map(Json.str)),
                  "transitionsAdded" -> Json.int(d.transitionsAdded.size),
                  "transitionsRemoved" -> Json.int(d.transitionsRemoved.size)
                )
              )
            else if d.isEmpty then Console.out("modèles structurellement identiques")
            else
              if d.onlyInLeft.nonEmpty then
                Console.out(s"− seulement dans $p1 : ${d.onlyInLeft.toList.sorted.mkString(", ")}")
              if d.onlyInRight.nonEmpty then
                Console.out(s"+ seulement dans $p2 : ${d.onlyInRight.toList.sorted.mkString(", ")}")
              d.levelChanges.foreach((a, l, r) => Console.out(s"~ $a : $l -> $r niveaux"))
              d.transitionsRemoved.foreach(t => Console.out(s"− $t"))
              d.transitionsAdded.foreach(t => Console.out(s"+ $t"))
            0
    }
  }

  // --- normalize -----------------------------------------------------------
  private val normalizeCmd = Opts.subcommand("normalize", "normalise (booléanise, ...)") {
    (modelArg, Opts.flag("booleanize", "force le booléen").orFalse, outOpt).mapN {
      (path, boolize, out) => () =>
        Console.loadModel(path) match
          case Left(code) => code
          case Right(net) =>
            val step = if boolize then Transform.booleanize(net) else Right(net)
            step match
              case Left(e) => Console.fail(e)
              case Right(r) =>
                out match
                  case Some(o) =>
                    Exporter.toFile(r, o, Some(Format.Anx)) match
                      case Right(_) => Console.out(s"normalisé -> $o"); 0
                      case Left(e) => Console.fail(e.toString)
                  case None => Console.out(io.quasar.io.AnxFormat.render(r)); 0
    }
  }

  // --- assign-rates --------------------------------------------------------
  private val assignRatesCmd =
    Opts.subcommand("assign-rates", "assigne des taux (valuation des modèles qualitatifs)") {
      val policyOpt =
        Opts.option[String]("policy", "unit|sample (défaut: unit)").withDefault("unit")
      val seedOpt = Opts.option[Int]("seed", "graine (policy sample)").withDefault(0)
      val minOpt = Opts.option[Double]("min", "taux minimal (policy sample)").withDefault(0.1)
      val maxOpt = Opts.option[Double]("max", "taux maximal (policy sample)").withDefault(10.0)
      (modelArg, policyOpt, seedOpt, minOpt, maxOpt, outOpt, jsonOpt).mapN {
        (path, policy, seed, lo, hi, out, json) => () =>
          Console.loadModel(path) match
            case Left(code) => code
            case Right(net) =>
              val parsed = policy.toLowerCase match
                case "unit" => Right(Transform.RatePolicy.Unit)
                case "sample" if lo > 0 && hi >= lo => Right(Transform.RatePolicy.Sample(lo, hi))
                case "sample" => Left(s"intervalle invalide : --min $lo --max $hi (0 < min ≤ max)")
                case other => Left(s"politique inconnue : $other (attendu unit|sample)")
              parsed match
                case Left(e) => Console.fail(e)
                case Right(pol) =>
                  val result = Transform.assignRates(net, pol, seed.toLong)
                  val written = out match
                    case Some(o) =>
                      Exporter.toFile(result, o, Some(Format.Anx)).left.map(_.toString)
                    case None => Right(())
                  written match
                    case Left(e) => Console.fail(e)
                    case Right(_) =>
                      if Console.jsonEnabled(json) then
                        Console.emitJson(
                          Json.obj(
                            "assigned" -> Json.int(result.transitions.size),
                            "policy" -> Json.str(policy.toLowerCase),
                            "seed" -> Json.int(seed),
                            "min" -> Json.num(lo),
                            "max" -> Json.num(hi)
                          )
                        )
                        0
                      else
                        out match
                          case Some(o) =>
                            Console.out(
                              s"taux assignés (${result.transitions.size}, policy=$policy) -> $o"
                            )
                            0
                          case None => Console.out(io.quasar.io.AnxFormat.render(result)); 0
      }
    }

  // --- biolqm --------------------------------------------------------------
  private val biolqmCmd = Opts.subcommand("biolqm", "projette en bioLQM et signale les pertes") {
    (modelArg, jsonOpt).mapN { (path, json) => () =>
      Console.loadModel(path) match
        case Left(code) => code
        case Right(net) =>
          val loss = io.quasar.biolqm.BioLqm.projectionLoss(net)
          val ok = io.quasar.biolqm.BioLqm.toLogicalModel(net).isRight
          if Console.jsonEnabled(json) then
            Console.emitJson(
              Json.obj(
                "projectable" -> Json.bool(ok),
                "losses" -> Json.arr(loss.map(Json.str))
              )
            )
          else
            Console.out(s"Projection bioLQM : ${if ok then "✓ réussie" else "✗ échec"}")
            if loss.isEmpty then Console.out("Pertes            : aucune (projection sans perte)")
            else loss.foreach(l => Console.out(s"Perte             : $l"))
          if ok then 0 else 1
    }
  }

  val command: Opts[Run] =
    importCmd
      .orElse(exportCmd)
      .orElse(convertCmd)
      .orElse(validateCmd)
      .orElse(infoCmd)
      .orElse(inspectCmd)
      .orElse(statsCmd)
      .orElse(diffCmd)
      .orElse(normalizeCmd)
      .orElse(assignRatesCmd)
      .orElse(biolqmCmd)

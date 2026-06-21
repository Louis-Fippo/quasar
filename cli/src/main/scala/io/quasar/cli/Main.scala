package io.quasar.cli

import com.monovore.decline.*

/**
 * Point d'entrée du binaire `quasar`. Le module CLI ne contient aucune logique métier : il parse et
 * délègue aux modules `analysis`/`io` (CLAUDE.md §5).
 */
object Main:

  private type Run = () => Int

  private val version =
    Opts.flag("version", "affiche la version", short = "V").map[Run] { _ => () =>
      Console.out(s"quasar ${BuildInfo.version}")
      Console.out("adaptateurs externes : NuSMV/Storm/MaBoSS (détection à l'exécution)")
      0
    }

  private val groups: Opts[Run] =
    Opts
      .subcommand("model", "cycle de vie des modèles")(ModelCommands.command)
      .orElse(Opts.subcommand("analyze", "moteur d'analyse")(AnalyzeCommands.command))
      .orElse(Opts.subcommand("topology", "structure et attracteurs")(TopologyCommands.command))
      .orElse(Opts.subcommand("transform", "transformations de modèle")(TransformCommands.command))
      .orElse(Opts.subcommand("solver", "accès bas niveau GLC/⌈Gω⌉")(SolverCommands.command))
      .orElse(Opts.subcommand("verify", "fallback exact / oracle externe")(VerifyCommands.command))
      .orElse(Opts.subcommand("repo", "dépôt de modèles versionné")(RepoCommands.command))
      .orElse(Opts.subcommand("bench", "benchmark & validation")(BenchCommands.command))
      .orElse(Opts.subcommand("tui", "mode interactif terminal")(Tui.command))

  private val command: Command[Run] =
    Command(
      name = "quasar",
      header = "QUASAR — QUantitative And Static Analysis of Regulatory networks"
    )(groups.orElse(version))

  /**
   * Consomme les options globales (§7.0, fiche M1) en **tête** d'argv — `--json` et `--cache-dir
   * <dir>` (ou `--cache-dir=<dir>`) — en positionnant les propriétés système lues par [[Console]],
   * puis renvoie le reste des arguments (la sous-commande). Les `--json` par-commande restent gérés
   * localement.
   */
  private def consumeGlobals(args: List[String]): List[String] = args match
    case "--json" :: t =>
      System.setProperty("quasar.json", "true"); consumeGlobals(t)
    case "--cache-dir" :: d :: t =>
      System.setProperty("quasar.cacheDir", d); consumeGlobals(t)
    case opt :: t if opt.startsWith("--cache-dir=") =>
      System.setProperty("quasar.cacheDir", opt.drop("--cache-dir=".length)); consumeGlobals(t)
    case _ => args

  /** Parse et exécute des arguments sans terminer la JVM (réutilisé par la TUI). */
  def execute(rawArgs: Seq[String]): Int =
    val args = consumeGlobals(rawArgs.toList)
    command.parse(args, sys.env) match
      case Left(help) =>
        System.err.println(help)
        if help.errors.isEmpty then 0 else 1
      case Right(run) =>
        // Mémoïsation persistante réservée au groupe `analyze` (lecture seule).
        if args.headOption.contains("analyze") then
          val key =
            args.map(Console.fileTag) :+ s"json=${Console.jsonEnabled(args.contains("--json"))}"
          Console.cachedRun(key)(run())
        else run()

  def main(args: Array[String]): Unit =
    // silence le StatusLogger de log4j2 (jSBML via bioLQM n'a pas de backend de log)
    System.setProperty("log4j2.statusLoggerLevel", "OFF")
    System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
    sys.exit(execute(args.toIndexedSeq))

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

  /** Parse et exécute des arguments sans terminer la JVM (réutilisé par la TUI). */
  def execute(args: Seq[String]): Int =
    command.parse(args, sys.env) match
      case Left(help) =>
        System.err.println(help)
        if help.errors.isEmpty then 0 else 1
      case Right(run) => run()

  def main(args: Array[String]): Unit =
    // silence le StatusLogger de log4j2 (jSBML via bioLQM n'a pas de backend de log)
    System.setProperty("log4j2.statusLoggerLevel", "OFF")
    System.setProperty("org.apache.logging.log4j.simplelog.StatusLogger.level", "OFF")
    sys.exit(execute(args.toIndexedSeq))

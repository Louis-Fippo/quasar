import sbtassembly.AssemblyPlugin.autoImport.*

// ---------------------------------------------------------------------------
// QUASAR — QUantitative And Static Analysis of Regulatory networks
// Multi-module sbt build (Scala 3 LTS / JVM 21). See CLAUDE.md for the plan.
// ---------------------------------------------------------------------------

ThisBuild / scalaVersion := "3.3.4"
ThisBuild / organization := "io.quasar"
ThisBuild / version      := "0.1.0-SNAPSHOT"

ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-source:future"
)

// Shared dependency versions
val munitV       = "1.0.0"
val munitCheckV  = "1.0.0"
val declineV     = "2.4.1"
val jlineV       = "3.25.1"

lazy val commonSettings = Seq(
  libraryDependencies ++= Seq(
    "org.scalameta" %% "munit"            % munitV      % Test,
    "org.scalameta" %% "munit-scalacheck" % munitCheckV % Test
  ),
  Test / parallelExecution := false
)

// --- core: IR ANX, semi-anneaux, GLC, qGLC, solveur (pur, sans I/O) --------
lazy val core = (project in file("core"))
  .settings(commonSettings*)
  .settings(name := "quasar-core")

// --- io: importeurs / exporteurs (.an, MaBoSS, ...) ------------------------
lazy val io = (project in file("io"))
  .dependsOn(core)
  .settings(commonSettings*)
  .settings(name := "quasar-io")

// --- analysis: moteurs d'analyse (reachability, quantitatif, ...) ----------
lazy val analysis = (project in file("analysis"))
  .dependsOn(core)
  .settings(commonSettings*)
  .settings(name := "quasar-analysis")

// --- verify: adaptateurs NuSMV / Storm / MaBoSS (sous-processus) -----------
lazy val verify = (project in file("verify"))
  .dependsOn(core, io)
  .settings(commonSettings*)
  .settings(name := "quasar-verify")

// --- biolqm: projection ANX <-> bioLQM (D1) + import SBML-qual/GINML/... -----
lazy val biolqm = (project in file("biolqm"))
  .dependsOn(core, io)
  .settings(commonSettings*)
  .settings(
    name := "quasar-biolqm",
    libraryDependencies += "org.colomoto" % "bioLQM" % "0.8"
  )

// --- bench: modèles de référence + harnais de validation -------------------
lazy val bench = (project in file("bench"))
  .dependsOn(core, io, analysis)
  .settings(commonSettings*)
  .settings(name := "quasar-bench")

// --- cli: binaire `quasar` (decline) — assemble les commandes §7 -----------
lazy val cli = (project in file("cli"))
  .dependsOn(core, io, analysis, verify, biolqm)
  .settings(commonSettings*)
  .settings(
    name := "quasar-cli",
    libraryDependencies ++= Seq(
      "com.monovore" %% "decline" % declineV,
      "org.jline"     % "jline"   % jlineV
    ),
    assembly / mainClass       := Some("io.quasar.cli.Main"),
    assembly / assemblyJarName := "quasar.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    }
  )

// --- aggregate root --------------------------------------------------------
lazy val root = (project in file("."))
  .aggregate(core, io, analysis, verify, biolqm, bench, cli)
  .settings(name := "quasar")
  .settings(publish / skip := true)

// Génère la Scaladoc de tous les modules documentés (cf. docs/api.md).
addCommandAlias(
  "docAll",
  "core/doc; io/doc; analysis/doc; verify/doc; biolqm/doc; cli/doc"
)

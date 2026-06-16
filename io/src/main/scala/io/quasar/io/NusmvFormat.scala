package io.quasar.io

import io.quasar.core.ir.*

/**
 * Exporteur NuSMV (model-checking symbolique qualitatif).
 *
 * Encodage **asynchrone** : une variable d'état par automate, plus une variable d'entrée non
 * déterministe `sel` choisissant l'automate qui agit à chaque pas (sémantique entrelacée, au plus
 * un automate change par transition). Une `CTLSPEC EF goal` peut être ajoutée pour tester
 * l'atteignabilité.
 */
object NusmvFormat:

  def render(net: AutomataNetwork, goal: Option[LocalState] = None): String =
    val sb = StringBuilder()
    val autos = net.ordered
    val ctx = net.metadata.initial.getOrElse(Context.empty)

    sb ++= "MODULE main\n"
    sb ++= "VAR\n"
    for au <- autos do sb ++= s"  ${id(au.name)} : 0..${au.levels - 1};\n"
    if autos.nonEmpty then
      sb ++= s"IVAR\n  sel : {${autos.map(au => id(au.name)).mkString(", ")}};\n"

    sb ++= "ASSIGN\n"
    for au <- autos do
      val initLevel = ctx.levelsOf(au.name, au.states).toList.sorted.headOption.getOrElse(0)
      sb ++= s"  init(${id(au.name)}) := $initLevel;\n"
    for au <- autos do
      sb ++= s"  next(${id(au.name)}) :=\n    case\n"
      for t <- au.transitions do
        val guard = (s"sel = ${id(au.name)}" :: s"${id(au.name)} = ${t.from}" ::
          t.conditions.map(c => s"${id(c.automaton)} = ${c.level}")).mkString(" & ")
        sb ++= s"      $guard : ${t.to};\n"
      sb ++= s"      TRUE : ${id(au.name)};\n    esac;\n"

    goal.foreach { g =>
      sb ++= s"\nCTLSPEC EF (${id(g.automaton)} = ${g.level})\n"
    }
    sb.toString

  private def id(s: String): String =
    if s.matches("[A-Za-z_][A-Za-z0-9_]*") then s else "_" + s.replaceAll("[^A-Za-z0-9_]", "_")

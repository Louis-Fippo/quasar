package io.quasar.io

import io.quasar.core.ir.*

/**
 * Exporteur PRISM (CTMC) pour Storm — vérification probabiliste exacte.
 *
 * Une variable par automate ; chaque transition devient une commande gardée de taux `meanRate`
 * mettant à jour la seule variable concernée (sémantique asynchrone, taux exponentiels — sémantique
 * concrète CTMC de QUASAR). Une propriété `P=? [ F goal ]` peut être ajoutée.
 */
object StormFormat:

  def render(net: AutomataNetwork, goal: Option[LocalState] = None): String =
    val sb = StringBuilder()
    val autos = net.ordered
    val ctx = net.metadata.initial.getOrElse(Context.empty)

    sb ++= "ctmc\n\nmodule quasar\n"
    for au <- autos do
      val initLevel = ctx.levelsOf(au.name, au.states).toList.sorted.headOption.getOrElse(0)
      sb ++= s"  ${id(au.name)} : [0..${au.levels - 1}] init $initLevel;\n"
    sb ++= "\n"
    for au <- autos; t <- au.transitions do
      val guard = (s"${id(au.name)}=${t.from}" ::
        t.conditions.map(c => s"${id(c.automaton)}=${c.level}")).mkString(" & ")
      sb ++= s"  [] $guard -> ${t.rate} : (${id(au.name)}'=${t.to});\n"
    sb ++= "endmodule\n"

    goal.foreach { g =>
      sb ++= s"\nP=? [ F ${id(g.automaton)}=${g.level} ]\n"
    }
    sb.toString

  private def id(s: String): String =
    if s.matches("[A-Za-z_][A-Za-z0-9_]*") then s else "_" + s.replaceAll("[^A-Za-z0-9_]", "_")

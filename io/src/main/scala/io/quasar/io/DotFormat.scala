package io.quasar.io

import io.quasar.core.ir.AutomataNetwork

/**
 * Exporteur DOT (Graphviz) du graphe de régulation : un nœud par automate, un arc `b -> a` si `b`
 * apparaît dans une précondition d'une transition de `a`.
 */
object DotFormat:

  def render(net: AutomataNetwork): String =
    val sb = StringBuilder()
    sb ++= s"digraph ${quote(net.name)} {\n"
    sb ++= "  rankdir=LR;\n  node [shape=ellipse];\n"
    for au <- net.ordered do
      sb ++= s"""  ${quote(au.name)} [label="${au.name}\\n|S|=${au.levels}"];\n"""
    for au <- net.ordered; reg <- net.regulatorsOf(au.name).toList.sorted do
      sb ++= s"  ${quote(reg)} -> ${quote(au.name)};\n"
    sb ++= "}\n"
    sb.toString

  private def quote(s: String): String =
    "\"" + s.replace("\"", "\\\"") + "\""

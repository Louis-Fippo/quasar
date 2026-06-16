package io.quasar.core.glc

import io.quasar.core.ir.AutomataNetwork

/**
 * Cône d'influence d'un automate : lui-même et ses régulateurs transitifs (automates apparaissant
 * dans les préconditions de ses transitions, récursivement).
 *
 * Propriété clé : l'ensemble est **clos** — les transitions d'un automate du cône ne portent que
 * sur des automates du cône. Les automates hors-cône n'influencent donc jamais la dynamique du cône
 * (sous-système autonome).
 */
object Cone:

  def of(net: AutomataNetwork, automaton: String): Set[String] =
    var s = Set(automaton)
    var changed = true
    while changed do
      changed = false
      for
        a <- s
        au <- net.automaton(a).toList
        t <- au.transitions
        c <- t.conditions
        if !s.contains(c.automaton)
      do
        s += c.automaton
        changed = true
    s

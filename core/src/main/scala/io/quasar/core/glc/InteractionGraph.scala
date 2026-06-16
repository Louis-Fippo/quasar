package io.quasar.core.glc

import io.quasar.core.ir.AutomataNetwork

/** Signe d'une influence (formalisme de Thomas). */
enum Sign:
  case Positive, Negative, Dual

  /** Produit des signes (pour le signe d'un circuit). `Dual` est absorbant. */
  def *(other: Sign): Sign = (this, other) match
    case (Dual, _) | (_, Dual) => Dual
    case (Positive, Positive) => Positive
    case (Negative, Negative) => Positive
    case _ => Negative

/** Arc signé `from -> to` du graphe d'interaction. */
final case class Influence(from: String, to: String, sign: Sign):
  override def toString: String =
    val arrow = sign match
      case Sign.Positive => "->"
      case Sign.Negative => "-|"
      case Sign.Dual => "-o"
    s"$from $arrow $to"

/**
 * Graphe d'interaction (régulation) signé dérivé du réseau d'automates.
 *
 * Un arc `b -> a` existe si `b` apparaît dans une précondition d'une transition de `a`. Son signe
 * combine la direction de la transition (montée/descente) et le niveau requis du régulateur
 * (haut/bas) ; des signes contradictoires donnent une influence duale.
 */
final case class InteractionGraph(influences: List[Influence]):
  def nodesOut(n: String): List[Influence] = influences.filter(_.from == n)
  def successors(n: String): List[String] = nodesOut(n).map(_.to).distinct
  def signOf(from: String, to: String): Option[Sign] =
    influences.find(i => i.from == from && i.to == to).map(_.sign)

object InteractionGraph:

  def of(net: AutomataNetwork): InteractionGraph =
    // accumule les signes par couple (régulateur, cible)
    val signs = scala.collection.mutable.Map.empty[(String, String), Sign]

    for
      au <- net.ordered
      t <- au.transitions
      c <- t.conditions
      if c.automaton != au.name
    do
      val direction = math.signum((t.to - t.from).toDouble).toInt // +1 montée, -1 descente
      val regLevels = net.levelsOf(c.automaton)
      val high = c.level.toDouble >= regLevels.size / 2.0
      val effect = if (direction >= 0) == high then Sign.Positive else Sign.Negative
      val key = (c.automaton, au.name)
      signs.updateWith(key) {
        case None => Some(effect)
        case Some(s) if s == effect => Some(s)
        case Some(_) => Some(Sign.Dual)
      }

    val influences = signs.toList.sortBy { case ((f, t), _) => (f, t) }.map { case ((f, t), s) =>
      Influence(f, t, s)
    }
    InteractionGraph(influences)

package io.quasar.core.ir

/** Sévérité d'un diagnostic de validation. */
enum Severity:
  case Error, Warning

/** Un diagnostic de validation de modèle. */
final case class Diagnostic(severity: Severity, message: String):
  override def toString: String =
    val tag = severity match
      case Severity.Error => "ERROR"
      case Severity.Warning => "WARN "
    s"[$tag] $message"

/**
 * Validation de cohérence d'un réseau ANX (cf. `quasar model validate`) :
 *   - taux strictement positifs,
 *   - niveaux de transition dans `S(a)`,
 *   - préconditions bien formées (automate et niveau existants),
 *   - absence de transition triviale `i -> i`.
 */
object Validation:

  def validate(net: AutomataNetwork): List[Diagnostic] =
    val diags = List.newBuilder[Diagnostic]

    def err(m: String): Unit = diags += Diagnostic(Severity.Error, m)
    def warn(m: String): Unit = diags += Diagnostic(Severity.Warning, m)

    for au <- net.ordered do
      if au.levels < 1 then err(s"automate '${au.name}' : nombre d'états < 1")
      if au.transitions.isEmpty then warn(s"automate '${au.name}' : aucune transition")

      for t <- au.transitions do
        val where = s"transition ${t}"
        if t.automaton != au.name then
          err(s"$where : automate '${t.automaton}' incohérent avec '${au.name}'")
        if !au.hasLevel(t.from) then err(s"$where : niveau source ${t.from} hors de S(${au.name})")
        if !au.hasLevel(t.to) then err(s"$where : niveau cible ${t.to} hors de S(${au.name})")
        if t.from == t.to then warn(s"$where : transition triviale (from == to)")

        // distribution : taux strictement positifs
        validateDist(t.dist).foreach(m => err(s"$where : $m"))

        // préconditions bien formées
        for c <- t.conditions do
          net.automaton(c.automaton) match
            case None =>
              err(s"$where : précondition '$c' référence un automate inconnu")
            case Some(target) =>
              if !target.hasLevel(c.level) then
                err(s"$where : précondition '$c' hors de S(${c.automaton})")

    diags.result()

  private def validateDist(d: Distribution): Option[String] = d match
    case Distribution.Exponential(r) =>
      Option.when(!(r > 0))(s"taux exponentiel non strictement positif ($r)")
    case Distribution.Erlang(k, r) =>
      if k < 1 then Some(s"Erlang : nombre de phases < 1 ($k)")
      else Option.when(!(r > 0))(s"taux Erlang non strictement positif ($r)")
    case Distribution.PhaseType(rs) =>
      if rs.isEmpty then Some("phase-type : aucune phase")
      else Option.when(rs.exists(_ <= 0))("phase-type : taux non strictement positif")

  /** Vrai si le modèle ne contient aucune erreur (les avertissements sont tolérés). */
  def isValid(net: AutomataNetwork): Boolean =
    !validate(net).exists(_.severity == Severity.Error)

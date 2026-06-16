package io.quasar.io

/**
 * Mini-algèbre booléenne pour l'import MaBoSS : parsing d'expressions logiques (`!`, `&`/`&&`,
 * `|`/`||`, parenthèses, constantes `0`/`1`) et mise en forme normale disjonctive (DNF), nécessaire
 * pour traduire la logique d'un nœud en préconditions de transitions ANX.
 */
enum BooleanExpr:
  case Lit(value: Boolean)
  case Var(name: String)
  case Not(e: BooleanExpr)
  case And(l: BooleanExpr, r: BooleanExpr)
  case Or(l: BooleanExpr, r: BooleanExpr)

object BooleanExpr:

  /** Une clause conjonctive : variable -> valeur requise. Incohérente => None. */
  type Clause = Map[String, Boolean]

  /**
   * DNF : ensemble de clauses conjonctives cohérentes (au moins une => formule vraie). Liste vide
   * \=> formule toujours fausse. Une clause vide (Map vide)
   * \=> formule toujours vraie.
   */
  def dnf(e: BooleanExpr): List[Clause] =
    e match
      case Lit(true) => List(Map.empty)
      case Lit(false) => Nil
      case Var(n) => List(Map(n -> true))
      case Not(Var(n)) => List(Map(n -> false)) // littéral négatif : feuille
      case Not(Lit(v)) => dnf(Lit(!v))
      case Not(inner) => dnf(pushNot(inner)) // De Morgan sur composé
      case Or(l, r) => dedup(dnf(l) ++ dnf(r))
      case And(l, r) =>
        val cl = dnf(l)
        val cr = dnf(r)
        dedup(for a <- cl; b <- cr; m <- mergeConsistent(a, b) yield m)

  /** Pousse une négation vers les feuilles (lois de De Morgan). */
  private def pushNot(e: BooleanExpr): BooleanExpr = e match
    case Lit(v) => Lit(!v)
    case Var(n) => Not(Var(n))
    case Not(x) => x
    case And(l, r) => Or(pushNot(l), pushNot(r))
    case Or(l, r) => And(pushNot(l), pushNot(r))

  private def mergeConsistent(a: Clause, b: Clause): Option[Clause] =
    val conflict = a.exists((k, v) => b.get(k).exists(_ != v))
    if conflict then None else Some(a ++ b)

  private def dedup(cs: List[Clause]): List[Clause] = cs.distinct

  // --- Parser (précédence : ! > & > |) -------------------------------------

  def parse(input: String): Either[String, BooleanExpr] =
    val toks = tokenize(input)
    val p = Parser(toks)
    for
      e <- p.parseOr()
      _ <- if p.atEnd then Right(()) else Left(s"jeton inattendu : '${p.peek}'")
    yield e

  private enum Tok:
    case LParen, RParen, NotT, AndT, OrT
    case Ident(s: String)
    case Const(b: Boolean)

  private def tokenize(s: String): List[Tok] =
    val buf = List.newBuilder[Tok]
    var i = 0
    val n = s.length
    while i < n do
      val c = s.charAt(i)
      c match
        case w if w.isWhitespace => i += 1
        case '(' => buf += Tok.LParen; i += 1
        case ')' => buf += Tok.RParen; i += 1
        case '!' => buf += Tok.NotT; i += 1
        case '~' => buf += Tok.NotT; i += 1
        case '&' =>
          if i + 1 < n && s.charAt(i + 1) == '&' then i += 2 else i += 1
          buf += Tok.AndT
        case '|' =>
          if i + 1 < n && s.charAt(i + 1) == '|' then i += 2 else i += 1
          buf += Tok.OrT
        case _ =>
          val start = i
          while i < n && (s.charAt(i).isLetterOrDigit || s.charAt(i) == '_') do i += 1
          val word = s.substring(start, i)
          if word.isEmpty then i += 1 // caractère ignoré
          else
            word.toUpperCase match
              case "AND" => buf += Tok.AndT
              case "OR" => buf += Tok.OrT
              case "NOT" => buf += Tok.NotT
              case "TRUE" => buf += Tok.Const(true)
              case "FALSE" => buf += Tok.Const(false)
              case "1" => buf += Tok.Const(true)
              case "0" => buf += Tok.Const(false)
              case _ => buf += Tok.Ident(word)
    buf.result()

  private final class Parser(tokens: List[Tok]):
    private var rest = tokens

    def atEnd: Boolean = rest.isEmpty
    def peek: String = rest.headOption.map(_.toString).getOrElse("<eof>")

    private def take(): Option[Tok] =
      rest match
        case h :: t => rest = t; Some(h)
        case Nil => None

    def parseOr(): Either[String, BooleanExpr] =
      parseAnd().flatMap { left =>
        var acc: Either[String, BooleanExpr] = Right(left)
        while rest.headOption.contains(Tok.OrT) do
          take()
          acc = for l <- acc; r <- parseAnd() yield Or(l, r)
        acc
      }

    private def parseAnd(): Either[String, BooleanExpr] =
      parseNot().flatMap { left =>
        var acc: Either[String, BooleanExpr] = Right(left)
        while rest.headOption.contains(Tok.AndT) do
          take()
          acc = for l <- acc; r <- parseNot() yield And(l, r)
        acc
      }

    private def parseNot(): Either[String, BooleanExpr] =
      rest.headOption match
        case Some(Tok.NotT) => take(); parseNot().map(Not(_))
        case _ => parseAtom()

    private def parseAtom(): Either[String, BooleanExpr] =
      take() match
        case Some(Tok.LParen) =>
          parseOr().flatMap { e =>
            take() match
              case Some(Tok.RParen) => Right(e)
              case _ => Left("parenthèse fermante manquante")
          }
        case Some(Tok.Ident(s)) => Right(Var(s))
        case Some(Tok.Const(b)) => Right(Lit(b))
        case Some(other) => Left(s"jeton inattendu : $other")
        case None => Left("expression incomplète")

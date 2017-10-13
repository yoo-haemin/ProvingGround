package provingground.interface
import provingground._
import induction._

import scala.concurrent._, duration._
import monix.execution.Scheduler.Implicits.global
import monix.eval._
import monix.reactive._
import monix.tail._
import cats._

import HoTT.{Name => _, _}

import trepplein._

object LeanToTermMonix {
  def isPropnFn(e: Expr): Boolean = e match {
    case Pi(_, t) => isPropnFn(t)
    case Sort(l)  => l == Level.Zero
    case _        => false
  }

  def proofLift: (Term, Term) => Task[Term] = {
    case (w: Typ[u], pt: PiDefn[x, y]) if pt.domain == w =>
      Monad[Task].pure(LambdaFixed(pt.variable, pt.value))
    case (w: Typ[u], tp: Typ[v]) => Monad[Task].pure { (w.Var) :-> tp }
    case (w: FuncLike[u, v], tp: FuncLike[a, b]) if w.dom == tp.dom =>
      val x = w.dom.Var
      proofLift(w(x), tp(x.asInstanceOf[a]))
        .map((g: Term) => x :~> (g: Term))
    case _ => throw new Exception("could not lift proof")
  }

  def feedWit(t: Term): Option[Term] = t match {
    case f: FuncLike[u, v] if (isProp(f.dom)) =>
      Some(f("witness" :: f.dom))
    case _ => None
  }

  def witUnify(x: Term, typ: Typ[Term]): Option[Term] = (x, typ) match {
    case (y, t) if y.typ == t => Some(y)
    case (l: LambdaLike[u, v], pd: PiDefn[a, b]) if l.dom == pd.domain =>
      witUnify(l.value, pd.value.replace(pd.variable, l.variable))
        .map(lambda(l.variable)(_))
    case (l: LambdaLike[u, v], tp) if isProp(l.dom) => witUnify(l.value, tp)
    case (y, pd: PiDefn[u, v]) if isProp(pd.domain) =>
      witUnify(y, pd.value).map(lambda(pd.variable)(_))
    case (l: LambdaLike[u, v], tp) =>
      for {
        v <- feedWit(l.variable)
        ll = lambda(v)(l.value.replace(l.variable, v))
        res <- witUnify(ll, tp)
      } yield res
    case (y, t) =>
      None
  }

  def applyWitUnify(f: Term, x: Term): Option[Term] = f match {
    case fn: FuncLike[u, v] =>
      for {
        arg <- witUnify(x, fn.dom)
        res <- applyFuncWitOpt(fn, arg)
      } yield res
    case _ => None
  }

  def applyFuncWitOpt(f: Term, x: Term): Option[Term] =
    applyFuncOpt(f, x)
      .orElse(
        feedWit(f).flatMap(applyFuncWitOpt(_, x))
      )
      .orElse(
        feedWit(x).flatMap(applyFuncWitOpt(f, _))
      )
      .orElse(applyWitUnify(f, x))
      .orElse(
        if (isProp(x.typ)) Some(f) else None
      )

  def applyFuncWit(f: Term, x: Term): Term =
    applyFuncWitOpt(f, x).getOrElse {
      throw new ApplnFailException(f, x)
    }

  def applyFuncWitFold(ft: Task[Term], v: Vector[Term]): Task[Term] =
    v match {
      case Vector() => ft
      case x +: ys =>
        applyFuncWitFold(ft.map(
                           (f) => applyFuncWit(f, x)
                         ),
                         ys)
    }

  def introsFold(ind: TermIndMod, p: Vector[Term]) =
    ind.intros.map((rule) => foldFunc(rule, p))

  def getRec(ind: TermIndMod, argsFmlyTerm: Vector[Term]): Task[Term] =
    ind match {
      case smp: SimpleIndMod =>
        getRecSimple(smp, Monad[Task].pure(argsFmlyTerm))
      case indInd: IndexedIndMod =>
        getRecIndexed(indInd, Monad[Task].pure(argsFmlyTerm))
    }

  def getRecSimple(ind: SimpleIndMod,
                   argsFmlyTerm: Task[Vector[Term]]): Task[Term] = {
    val newParamsTask = argsFmlyTerm map (_.init)
    def getInd(p: Vector[Term]) =
      ConstructorSeqTL
        .getExst(toTyp(foldFunc(ind.typF, p)), introsFold(ind, p))
        .value
    newParamsTask.flatMap { (newParams) =>
      val indNew =
        getInd(newParams)

      val fmlyOpt = argsFmlyTerm map (_.last)
      fmlyOpt map {
        case l: LambdaLike[u, v] =>
          l.value match {
            case tp: Typ[u] =>
              if (tp.dependsOn(l.variable))(indNew.inducE(
                (l.variable: Term) :-> (tp: Typ[u])))
              else (indNew.recE(tp))
          }
        case fn: FuncLike[u, v] =>
          val x = fn.dom.Var
          val y = fn(x)
          y match {
            case tp: Typ[u] =>
              if (tp.dependsOn(x)) {
                (indNew.inducE((x: Term) :-> (tp: Typ[u])))
              } else (indNew.recE(tp))
          }
        case pt: PiDefn[u, v] if ind.isPropn && pt.domain == indNew.typ =>
          indNew.inducE(LambdaFixed(pt.variable, pt.value))
        case tp: Typ[u] if (ind.isPropn) =>
          val x = tp.Var
          if (tp.dependsOn(x)) {
            (indNew.inducE((x: Term) :-> (tp: Typ[u])))
          } else (indNew.recE(tp))
      }

    }
  }

  def getRecIndexed(ind: IndexedIndMod,
                    argsFmlyTerm: Task[Vector[Term]]): Task[Term] = {
    def getInd(p: Vector[Term]) =
      TypFamilyExst
        .getIndexedConstructorSeq(foldFunc(ind.typFP, p), introsFold(ind, p))
        .value
    val newParamsTask = argsFmlyTerm map (_.init)
    newParamsTask.flatMap { (newParams) =>
      val indNew =
        getInd(newParams)
      val fmlOptRaw = argsFmlyTerm map (_.last)
      val fmlOpt =
        if (ind.isPropn)
          fmlOptRaw.flatMap((fib) => proofLift(indNew.W, fib))
        else fmlOptRaw
      val recOptTask =
        for {
          fml <- fmlOpt
          codOpt = indNew.family.constFinalCod(fml)
        } yield codOpt.map((cod) => indNew.recE(cod))
      val inducTask =
        fmlOpt.map((fib) => indNew.inducE(fib))
      for {
        recOpt <- recOptTask
        induc  <- inducTask
      } yield recOpt.getOrElse(induc)

    }
  }

  type TaskParser = (Expr, Vector[Term]) => Task[Term]

  val empty = LeanToTermMonix(Map(), Map())

  def fromMods(mods: Vector[Modification], init: LeanToTermMonix = empty) =
    mods.foldLeft(Monad[Task].pure(init)) {
      case (l, m) => l.flatMap(_.add(m))
    }

  def addChunk(mods: Vector[Modification],
               init: Task[LeanToTermMonix] = Task.pure(empty),
               limit: FiniteDuration = 3.minutes) =
    mods
      .foldLeft(init) {
        case (l, m) =>
          l.flatMap(_.add(m).timeout(limit).onErrorRecoverWith {
            case err =>
              l
          })
      }
      .memoize

  def observable(mods: Vector[Modification],
                 init: LeanToTermMonix = empty,
                 limit: FiniteDuration = 5.minutes,
                 logErr: (Modification, Throwable) => Unit = (_, _) => {}) =
    Observable.fromIterable(mods).flatScan[LeanToTermMonix](init) {
      case (l, m) =>
        Observable.fromTask(
          l.add(m).timeout(limit).onErrorRecoverWith {
            case err =>
              logErr(m, err)
              Task.pure(l)
          }
        )
    }

  def iterant(mods: Vector[Modification],
              init: LeanToTermMonix = empty,
              limit: FiniteDuration = 5.minutes,
              logErr: (Modification, Throwable) => Unit = (_, _) => {},
              recoverAll: Boolean = true) =
    Iterant
      .fromIterable[Task, Modification](mods)
      .scanEval[LeanToTermMonix](Monad[Task].pure(init)) {
        case (l, m) =>
          l.add(m).timeout(limit).onErrorRecoverWith {
            case err if recoverAll =>
              logErr(m, err)
              Monad[Task].pure(l)
            case err: TimeoutException =>
              logErr(m, err)
              Monad[Task].pure(l)
            case err: UnParsedException =>
              logErr(m, err)
              Monad[Task].pure(l)

          }
      }

  object RecIterAp {
    def unapply(exp: Expr): Option[(Name, Vector[Expr])] = exp match {
      case Const(Name.Str(prefix, "rec"), _) => Some((prefix, Vector()))
      case App(func, arg) =>
        unapply(func).map { case (name, vec) => (name, vec :+ arg) }
      case _ => None
    }
  }

// internal parser
  def parse(exp: Expr,
            vars: Vector[Term],
            ltm: LeanToTermMonix,
            mods: Vector[Modification]): Task[(Term, LeanToTermMonix)] =
    exp match {
      case Const(name, _)   => Monad[Task].pure(ltm.defnMap(name) -> ltm)
      case Sort(Level.Zero) => Monad[Task].pure(Prop              -> ltm)
      case Sort(_)          => Monad[Task].pure(Type              -> ltm)
      case Var(n)           => Monad[Task].pure(vars(n)           -> ltm)
      case RecIterAp(name, args) =>
        val indMod         = ltm.termIndModMap(name)
        val (argsFmly, xs) = args.splitAt(indMod.numParams + 1)

        for {
          pair1 <- parseVec(argsFmly, vars, ltm, mods)
          (argsFmlyTerm, ltm1) = pair1
          recFnT               = getRec(indMod, argsFmlyTerm)
          pair2 <- parseVec(xs, vars, ltm, mods)
          (vec, ltm2) = pair2
          resTask     = applyFuncWitFold(recFnT, vec)
          res <- resTask
        } yield (res, ltm2)

      case App(f, a) =>
        for {
          p1 <- parse(f, vars, ltm, mods)
          (func, ltm1) = p1
          p2 <- parse(a, vars, ltm1, mods)
          (arg, ltm2) = p2
        } yield (applyFuncWit(func, arg), ltm2)
      // Task
      //   .defer(parse(f, vars, ltm, mods))
      //   .zipMap(Task.defer(parse(a, vars, ltm, mods)))(applyFuncWit)
      case Lam(domain, body) =>
        for {
          p1 <- parse(domain.ty, vars, ltm, mods)
          (domTerm, ltm1) = p1
          domTyp <- Task(toTyp(domTerm))
          x = domTyp.Var
          p2 <- parse(body, x +: vars, ltm1, mods)
          (value, ltm2) = p2
        } yield
          value match {
            case FormalAppln(fn, arg) if arg == x && fn.indepOf(x) =>
              fn -> ltm2
            case y if domain.prettyName.toString == "_" => y -> ltm2
            case _ =>
              if (value.typ.dependsOn(x)) (LambdaTerm(x, value), ltm2)
              else (LambdaFixed(x, value), ltm2)
          }
      case Pi(domain, body) =>
        for {
          p1 <- parse(domain.ty, vars, ltm, mods)
          (domTerm, ltm1) = p1
          domTyp <- Task(toTyp(domTerm))
          x = domTyp.Var
          p2 <- parse(body, x +: vars, ltm1, mods)
          (value, ltm2) = p2
          cod <- Task(toTyp(value))
          dep = cod.dependsOn(x)
        } yield if (dep) (PiDefn(x, cod), ltm2) else (x.typ ->: cod, ltm2)
      case Let(domain, value, body) =>
        for {
          p1 <- parse(domain.ty, vars, ltm, mods)
          (domTerm, ltm1) = p1
          domTyp <- Task(toTyp(domTerm))
          x = domTyp.Var
          p2 <- parse(value, vars, ltm1, mods)
          (valueTerm, ltm2) = p2
          p3 <- parse(body, x +: vars, ltm2, mods)
          (bodyTerm, ltm3) = p3
        } yield (bodyTerm.replace(x, valueTerm), ltm3)
      case e => Task.raiseError(UnParsedException(e))
    }

  def parseVec(
      vec: Vector[Expr],
      vars: Vector[Term],
      ltm: LeanToTermMonix,
      mods: Vector[Modification]): Task[(Vector[Term], LeanToTermMonix)] =
    vec match {
      case Vector() => Monad[Task].pure(Vector() -> ltm)
      case x +: ys =>
        for {
          p1 <- parse(x, vars, ltm, mods)
          (head, ltm1) = p1
          p2 <- parseVec(ys, vars, ltm1, mods)
          (tail, parse2) = p2
        } yield (head +: tail, parse2)
    }

}

case class RecFoldException(indMod: TermIndMod,
                            recFn: Term,
                            argsFmlyTerm: Vector[Term],
                            vec: Vector[Term],
                            fail: ApplnFailException)
    extends IllegalArgumentException("Failure to fold recursive Function")

case class LeanToTermMonix(defnMap: Map[Name, Term],
                           termIndModMap: Map[Name, TermIndMod]) { self =>
  import LeanToTermMonix._

  def defnOpt(exp: Expr) =
    exp match {
      case Const(name, _) => defnMap.get(name)
      case _              => None
    }

  object Predef {
    def unapply(exp: Expr): Option[Term] =
      (
        defnOpt(exp)
      )
  }

  def parse(exp: Expr, vars: Vector[Term]): Task[Term] =
    exp match {
      case Predef(t)        => Task.pure(t)
      case Sort(Level.Zero) => Task.pure(Prop)
      case Sort(_)          => Task.pure(Type)
      case Var(n)           => Task.pure(vars(n))
      case RecIterAp(name, args) =>
        val indMod         = termIndModMap(name)
        val (argsFmly, xs) = args.splitAt(indMod.numParams + 1)

        for {
          argsFmlyTerm <- parseVec(argsFmly, vars)
          recFnT = getRec(indMod, argsFmlyTerm)
          recFn <- recFnT
          vec   <- parseVec(xs, vars)
          resTask = applyFuncWitFold(recFnT, vec)
            .onErrorRecoverWith {
              case err: ApplnFailException =>
                throw RecFoldException(indMod, recFn, argsFmlyTerm, vec, err)
            }
          res <- resTask
        } yield res

      case App(f, a) =>
        Task
          .defer(parse(f, vars))
          .zipMap(Task.defer(parse(a, vars)))(applyFuncWit)
      case Lam(domain, body) =>
        for {
          domTerm <- parse(domain.ty, vars)
          domTyp  <- Task(toTyp(domTerm))
          x = domTyp.Var
          value <- parse(body, x +: vars)
        } yield
          value match {
            case FormalAppln(fn, arg) if arg == x && fn.indepOf(x) => fn
            case y if domain.prettyName.toString == "_"            => y
            case _ =>
              if (value.typ.dependsOn(x)) LambdaTerm(x, value)
              else LambdaFixed(x, value)
          }
      case Pi(domain, body) =>
        for {
          domTerm <- parse(domain.ty, vars)
          domTyp  <- Task(toTyp(domTerm))
          x = domTyp.Var
          value <- parse(body, x +: vars)
          cod   <- Task(toTyp(value))
          dep = cod.dependsOn(x)
        } yield if (dep) PiDefn(x, cod) else x.typ ->: cod
      case Let(domain, value, body) =>
        for {
          domTerm <- parse(domain.ty, vars)
          domTyp  <- Task(toTyp(domTerm))
          x = domTyp.Var
          valueTerm <- parse(value, vars)
          bodyTerm  <- parse(body, x +: vars)
        } yield bodyTerm.replace(x, valueTerm)
      case e => Task.raiseError(UnParsedException(e))
    }

  def parseTyp(x: Expr, vars: Vector[Term]): Task[Typ[Term]] =
    parse(x, vars).flatMap {
      case tp: Typ[_] => Task.pure(tp)
      case t =>
        Task.raiseError(NotTypeException(t))
    }

  def parseVec(vec: Vector[Expr], vars: Vector[Term]): Task[Vector[Term]] =
    vec match {
      case Vector() => Task.pure(Vector())
      case x +: ys =>
        for {
          head <- parse(x, vars)
          tail <- parseVec(ys, vars)
        } yield head +: tail
    }

  def parseTypVec(vec: Vector[Expr],
                  vars: Vector[Term]): Task[Vector[Typ[Term]]] = vec match {
    case Vector() => Task.pure(Vector())
    case x +: ys =>
      for {
        head <- parseTyp(x, vars)
        tail <- parseTypVec(ys, vars)
      } yield head +: tail
  }

  def parseSymVec(vec: Vector[(Name, Expr)],
                  vars: Vector[Term]): Task[Vector[Term]] = vec match {
    case Vector() => Task.pure(Vector())
    case (name, expr) +: ys =>
      for {
        tp <- parseTyp(expr, vars)
        head = name.toString :: tp
        tail <- parseSymVec(ys, vars)
      } yield head +: tail
  }

  def parseSym(name: Name, ty: Expr, vars: Vector[Term]) =
    parseTyp(ty, vars).map(name.toString :: _)

  def parseVar(b: Binding, vars: Vector[Term]) =
    parseSym(b.prettyName, b.ty, vars)

  def addDefnMap(name: Name, term: Term) =
    self.copy(defnMap = self.defnMap + (name -> term))

  def addDefnVal(name: Name, value: Expr, tp: Expr) =
    parse(value, Vector())
      .map((t) => addDefnMap(name, t))

  def addAxiom(name: Name, ty: Expr) =
    parseSym(name, ty, Vector())
      .map(addDefnMap(name, _))

  def addAxioms(axs: Vector[(Name, Expr)]) = {
    val taskVec = axs.map {
      case (name, ty) => parseSym(name, ty, Vector()).map((t) => (name, t))
    }
    val mvec = Task.gather(taskVec)
    mvec.map((vec) => self.copy(defnMap = self.defnMap ++ vec))
  }

  def addAxiomSeq(axs: Vector[(Name, Expr)]): Task[LeanToTermMonix] =
    axs.foldLeft(Task.pure(self)) {
      case (p, (n, v)) => p.flatMap(_.addAxiom(n, v))
    }

  def addAxiomMod(ax: AxiomMod): Task[LeanToTermMonix] =
    addAxiom(ax.name, ax.ax.ty)

  def addDefMod(df: DefMod): Task[LeanToTermMonix] =
    addDefnVal(df.name, df.defn.value, df.defn.ty)

  def addQuotMod: Task[LeanToTermMonix] = {
    import quotient._
    val axs = Vector(quot, quotLift, quotMk, quotInd).map { (ax) =>
      (ax.name, ax.ty)
    }
    addAxiomSeq(axs)
  }

  def addIndMod(ind: IndMod): Task[LeanToTermMonix] = {
    val withTypDef = addAxiom(ind.name, ind.inductiveType.ty)
    val withAxioms = withTypDef.flatMap(_.addAxioms(ind.intros))
    val indOpt: Task[TermIndMod] = {
      val inductiveTypOpt = parseTyp(ind.inductiveType.ty, Vector())

      import scala.util._

      def getValue(t: Term,
                   n: Int,
                   accum: Vector[Term]): Task[(Term, Vector[Term])] =
        (t, n) match {
          case (x, 0) => Task.pure(x -> accum)
          case (l: LambdaLike[u, v], n) if n > 0 =>
            getValue(l.value, n - 1, accum :+ l.variable)
          case (fn: FuncLike[u, v], n) if n > 0 =>
            val x = fn.dom.Var
            getValue(fn(x), n - 1, accum :+ x)
          case _ => throw new Exception("getValue failed")
        }

      inductiveTypOpt.flatMap { (inductiveTyp) =>
        val name = ind.inductiveType.name
        val typF = name.toString :: inductiveTyp
        val typValueOpt =
          getValue(typF, ind.numParams, Vector())
        val isPropn = isPropnFn(ind.inductiveType.ty)

        val introsTry =
          withTypDef.flatMap(_.parseSymVec(ind.intros, Vector()))
        introsTry.flatMap { (intros) =>
          typValueOpt.map { (typValue) =>
            typValue match {
              case (typ: Typ[Term], params) =>
                SimpleIndMod(ind.inductiveType.name,
                             typF,
                             intros,
                             params.size,
                             isPropn)
              case (t, params) =>
                IndexedIndMod(ind.inductiveType.name,
                              typF,
                              intros,
                              params.size,
                              isPropn)
            }
          }
        }
      }
    }
    indOpt
      .flatMap { (indMod) =>
        withAxioms.map(
          _.copy(termIndModMap = self.termIndModMap + (ind.name -> indMod)))
      }
  }

  def add(mod: Modification): Task[LeanToTermMonix] = mod match {
    case ind: IndMod  => addIndMod(ind)
    case ax: AxiomMod => addAxiomMod(ax)
    case df: DefMod   => addDefMod(df)
    case QuotMod      => addQuotMod
  }

}

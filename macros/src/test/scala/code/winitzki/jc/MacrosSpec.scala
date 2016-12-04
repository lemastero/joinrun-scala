package code.winitzki.jc

import JoinRun._
import Macros._

import org.scalatest.{BeforeAndAfterAll, FlatSpec, Matchers}

import scala.concurrent.duration.DurationInt

class MacrosSpec extends FlatSpec with Matchers with BeforeAndAfterAll {

  val warmupTimeMs = 50

  val tp0 = new FixedPool(4)

  def waitSome(): Unit = Thread.sleep(warmupTimeMs)

  override def afterAll(): Unit = {
    tp0.shutdownNow()
  }

  behavior of "macros for defining new molecule injectors"

  it should "compute invocation names for molecule injectors" in {
    val a = m[Int]

    a.toString shouldEqual "a"

    val s = b[Map[(Boolean, Unit), Seq[Int]], Option[List[(Int, Option[Map[Int, String]])]]] // complicated type

    s.toString shouldEqual "s/B"
  }

  behavior of "macros for inspecting a reaction body"

  it should "inspect reaction body containing local molecule injectors" in {
    val a = m[Int]

    val reaction =
      & { case a(x) =>
        val q = new M[Int]("q")
        val s = new M[Unit]("s")
        val reaction1 = & { case q(_) + s(()) => }
        q(0)
      }
    reaction.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    reaction.info.outputs shouldEqual List()
  }

  it should "inspect reaction body with embedded join" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    join(tp0,tp0)(
      & { case f(_, r) + bb(x) => r(x) },
      & { case a(x) =>
        val p = m[Int]
        join(tp0,tp0)(& { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    waitSome()
    f(timeout = 100 millis)() shouldEqual Some(2)
  }

  it should "inspect reaction body with embedded join and runSimple" in {
    val a = m[Int]
    val bb = m[Int]
    val f = b[Unit, Int]
    join(tp0,tp0)(
      runSimple { case f(_, r) + bb(x) => r(x) },
      runSimple { case a(x) =>
        val p = m[Int]
        join(tp0,tp0)(& { case p(y) => bb(y) })
        p(x + 1)
      }
    )
    a(1)
    waitSome()
    f(timeout = 100 millis)() shouldEqual Some(2)
  }

  it should "inspect a simple reaction body" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & { case a(x) => a(x + 1) }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    result.info.outputs shouldEqual List(a)
    result.info.sha1 shouldEqual "4CD3BBD8E3B9DA58E46705320AE974479A7784E4"
  }

  it should "inspect a reaction body with another molecule and extra code" in {
    val a = m[Int]
    val qq = m[String]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    object testWithApply {
      def apply(x: Int): Int = x + 1
    }

    val result = & {
      case a(_) + a(x) =>
        a(x + 1)
        if (x > 0) a(testWithApply(123))
        println(x)
        qq("")
    }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, Wildcard), InputMoleculeInfo(a, SimpleVar))
    result.info.outputs shouldEqual List(a, a, qq)
  }

  it should "inspect reaction body with embedded reaction" in {
    val a = m[Int]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    val result = & { case a(x) => & { case qq(_) => a(0) }; a(x + 1) }

    result.info.inputs shouldEqual List(InputMoleculeInfo(a, SimpleVar))
    result.info.outputs shouldEqual List(a)
  }

  it should "inspect a very complicated reaction body" in {
    val a = m[Int]
    val c = m[Unit]
    val qq = m[Unit]
    val s = b[Unit, Int]
    val bb = m[(Int, Option[Int])]

    // reaction contains all kinds of pattern-matching constructions, blocking molecule in a guard, and unit values in molecules
    val result = & {
      case a(p) + a(y) + a(1) + c(()) + c(_) + bb(_) + bb((1, z)) + bb((_, None)) + bb((t, Some(q))) + s(_, r) if y > 0 && s() > 0 => a(p + 1); qq(); r(p)
    }

    (result.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, SimpleVar),
      InputMoleculeInfo(`a`, SimpleVar),
      InputMoleculeInfo(`a`, SimpleConst(1)),
      InputMoleculeInfo(`c`, SimpleConst(())),
      InputMoleculeInfo(`c`, Wildcard),
      InputMoleculeInfo(`bb`, Wildcard),
      InputMoleculeInfo(`bb`, OtherPattern(_)),
      InputMoleculeInfo(`bb`, OtherPattern(_)),
      InputMoleculeInfo(`bb`, OtherPattern(_)),
      InputMoleculeInfo(`s`, Wildcard)
      ) => true
      case _ => false
    }) shouldEqual true

    result.info.outputs shouldEqual List(s, a, qq)
  }

  it should "inspect reaction body with two cases" in {
    val a = m[Int]
    val qq = m[Unit]

    val result = & {
      case a(x) => a(x + 1)
      case qq(_) + a(y) => qq()
    }
    // TODO: add a test for inspecting this reaction
  }

  it should "define a reaction with correct inputs with non-default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0,tp0)(runSimple { case b(_) + a(Some(x)) + c(_) => })

    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules" // this is the wrong result
    // when the problem is fixed, this test will have to be rewritten
  }

  it should "define a reaction with correct inputs with default pattern-matching in the middle of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0,tp0)(& { case b(_) + a(None) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-simple default pattern-matching in the middle of reaction" in {
    val a = new M[Seq[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(& { case b(_) + a(List()) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "fail to define a simple reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0,tp0)(runSimple { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with empty option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0,tp0)(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0,tp0)(& { case a(Some(x)) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "run reactions correctly with non-default pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")

    join(tp0,tp0)(& { case a(Some(x)) + b(_) => })

    a(Some(1))
    waitSome()
    a.logSoup shouldEqual "Join{a + b => ...}\nMolecules: a(Some(1))"
    b()
    waitSome()
    a.logSoup shouldEqual "Join{a + b => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant non-default pattern-matching at start of reaction" in {
    val a = new M[Int]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0,tp0)(& { case a(1) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "define a reaction with correct inputs with constant default option pattern-matching at start of reaction" in {
    val a = new M[Option[Int]]("a")
    val b = new M[Unit]("b")
    val c = new M[Unit]("c")

    join(tp0,tp0)(& { case a(None) + b(_) + c(_) => })

    a.logSoup shouldEqual "Join{a + b + c => ...}\nNo molecules"
  }

  it should "determine input patterns correctly" in {
    val a = new M[Option[Int]]("a")
    val b = new M[String]("b")
    val c = new M[(Int,Int)]("c")
    val d = new M[Unit]("d")

    val r = & { case a(Some(1)) + b("xyz") + d(()) + c((2,3)) => }

    (r.info.inputs match {
      case List(
      InputMoleculeInfo(`a`, OtherPattern(_)),
      InputMoleculeInfo(`b`, SimpleConst("xyz")),
      InputMoleculeInfo(`d`, SimpleConst(())),
      InputMoleculeInfo(`c`, OtherPattern(_))
      ) => true
      case _ => false
    }) shouldEqual true
    r.info.outputs shouldEqual List()

    Set("919ADEAE0AEC9B7E68560B278A363FE4FA4BA0F6", "A6507CFF4A5B450250480BD61C20467FF632A21F") should contain oneElementOf List(r.info.sha1)
  }

  it should "fail to compile reaction with invalid reply molecules" in {
    val a = b[Unit,Unit]
    val c = b[Unit, Unit]

    "& { case a() => a() }" shouldNot compile   // no pattern variable for reply in "a"
    "& { case a(_) => a() }" shouldNot compile   // no pattern variable for reply in "a"
    "& { case a(_, _) => a() }" shouldNot compile   // no pattern variable for reply in "a"
    "& { case a(_, _, _) => a() }" shouldNot compile   // no pattern variable for reply in "a"
    "& { case a(_, r) => a() }" shouldNot compile   // no reply is performed with r
    "& { case a(_, r) + a(_) + c(_) => r()  }" shouldNot compile // invalid patterns for "a" and "c"
    "& { case a(_, r) + a(_) + c(_) => r() + r() }" shouldNot compile // two replies are performed with r, and invalid patterns for "a" and "c"

  }

  it should "create partial functions for matching from reaction body" in {
    val aa = m[Option[Int]]
    val bb = m[(Int, Option[Int])]

    val result = & { case aa(Some(x)) + bb((0, None)) => aa(Some(x + 1)) }

    result.info.outputs shouldEqual List(aa)

    val pat_aa = result.info.inputs(0)
    pat_aa.molecule shouldEqual aa
    val pat_bb = result.info.inputs(1)
    pat_bb.molecule shouldEqual bb

    (pat_aa.flag match {
      case OtherPattern(matcher) =>
        matcher.isDefinedAt(Some(1)) shouldEqual true
        matcher.isDefinedAt(None) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

    (pat_bb.flag match {
      case OtherPattern(matcher) =>
        matcher.isDefinedAt((0, None)) shouldEqual true
        matcher.isDefinedAt((1, None)) shouldEqual false
        matcher.isDefinedAt((0, Some(1))) shouldEqual false
        matcher.isDefinedAt((1, Some(1))) shouldEqual false
        true
      case _ => false
    }) shouldEqual true

  }


}


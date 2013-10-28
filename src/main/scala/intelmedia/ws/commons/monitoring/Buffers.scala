package intelmedia.ws.commons.monitoring

import scala.concurrent.duration._
import scalaz.stream._
import scalaz.stream.{Process => P}
import com.twitter.algebird.Group

/**
 * Various stream transducers and combinators used for
 * building arguments to pass to `Monitoring.get`.
 * See `Monitoring` companion object for examples of how
 * these can be used.
 */
object Buffers {

  /** Promote a `Process1[A,B]` to one that ignores time. */
  def ignoreTime[A,B](p: Process1[A,B]): Process1[(A,Any), B] =
    process1.id[(A,Any)].map(_._1).pipe(p)

  /**
   * Emits the current value, which may be modified by the
   * given function, which receives the old value and
   * produces the new value.
   */
  def variable[A](init: A): Process1[A => A, A] =
    P.emit(init) ++ P.await1[A => A].flatMap(f => variable(f(init)))

  /** Emits a running sum of its inputs, starting from `init`. */
  def counter(init: Int): Process1[Int,Int] =
    process1.scan(init)(_ + _)

  /** Emits only values not yet seen. */
  def distinct[A]: Process1[A, A] = {
    def go(seen: Set[A]): Process1[A, A] =
      P.await1[A].flatMap { a =>
        if (seen(a)) go(seen)
        else P.emit(a) ++ go(seen + a)
      }
    go(Set())
  }

  /** Reset the buffer `p` after `d0, 2*d0, 3*d0, ...` elapsed duration. */
  def resetEvery[I,O](d0: Duration)(p: Process1[I,O]): Process1[(I,Duration),O] = {
    def flush(cur: Process1[I,O], expiration: Duration):
        Process1[(I,Duration),O] = {
      val (h, t) = cur.unemit
      Process.emitAll(h) ++ go(t, expiration)
    }
    def go(cur: Process1[I,O], expiration: Duration): Process1[(I,Duration),O] =
      P.await1[(I,Duration)].flatMap { case (i,d) =>
        if (d >= expiration)
          flush(process1.feed1(i)(p), roundDuration(d, d0))
        else
          flush(process1.feed1(i)(cur), expiration)
      }
    flush(p, d0)
  }

  /** Only emit a value after `d0, 2*d0, 3*d0, ...` elapsed duration. */
  def emitEvery[I,O](d0: Duration)(p: Process1[(I,Duration),O]): Process1[(I,Duration),O] = {
    def flush(last: Option[O], unmask: Boolean, cur: Process1[(I,Duration),O], expiration: Duration):
        Process1[(I,Duration),O] = {
      val (h, t) = cur.unemit
      val last2 = h.lastOption orElse last
      if (unmask) Process.emitAll(last.toSeq) ++ go(last2, t, expiration)
      else go(last2, t, expiration)
    }
    def go(last: Option[O], cur: Process1[(I,Duration),O], expiration: Duration): Process1[(I,Duration),O] =
      P.await1[(I,Duration)].flatMap { case (i,d) =>
        if (d >= expiration)
          flush(last, true, process1.feed1(i -> d)(p), roundDuration(d, d0))
        else
          flush(last, false, process1.feed1(i -> d)(cur), expiration)
      }
    flush(None, false, p, d0)
  }

  /** Produce a rolling output from all inputs in the last `d0` duration. */
  def window[I,O](d0: Duration)(to: I => O)(G: Group[O]): Process1[(I,Duration), O] = {
    def go(window: Vector[(O, Duration)], cur: O): Process1[(I,Duration), O] =
      P.await1[(I,Duration)].flatMap { case (i,d) =>
        val (out, in) = window.span(d - _._2 > d0)
        val io = to(i)
        val neg = out.foldLeft(G.zero)((a,b) => G.plus(a, G.negate(b._1)))
        val cur2 = G.plus(G.plus(neg, cur), io)
        P.emit(cur2) ++ go(in :+ (io -> d), cur2)
      }
    P.emit(G.zero) ++ go(Vector(), G.zero)
  }

  /** Compute the smallest multiple of step which exceeds `d`. */
  def roundDuration(d: Duration, step: Duration): Duration = {
    val f = d / step
    val d2 = step * math.ceil(f).toInt
    if (math.ceil(f) == f) d2 + step
    else d2
  }

  def resettingRate(d: Duration): Process1[(Int,Duration),Int] =
    resetEvery(d)(counter(0))

  def stats: Process1[Double, Stats] =
    process1.id[Double].map(Stats.apply).scan1(_ ++ _)

  def histogram[O <% Reportable[O]]: Process1[String,Histogram[O]] = ???

  /**
   * Approximate count of unique `A` values using HyperLogLog algorithm.
   * This uses constant space.
   */
  def hyperLogLog[A]: Process1[A,Int] = ???

  // def countMinSketch, etc

  def incr: Int => Int = _ + 1
  def decr: Int => Int = _ - 1
  def incrBy(n: Int): Int => Int = _ + n
  def decrBy(n: Int): Int => Int = _ - n
}
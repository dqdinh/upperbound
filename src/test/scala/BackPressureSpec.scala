package upperbound

import fs2.Stream
import scala.concurrent.duration._

import org.specs2.mutable.Specification
import org.specs2.matcher.TaskMatchers

class BackPressureSpec
    extends Specification
    with TaskMatchers
    with TestScenarios {

  val samplingWindow = 5.seconds
  val description = "Backpressure"

  import syntax.rate._

  // Due to its asynchronous/concurrent nature, the backoff
  // functionality cannot guarantee instantaneous propagation of rate
  // changes. The parameters in the test are tuned to guarantee that
  // changes consistently propagate exactly one job later, so that we
  // can make the test assertion deterministic.
  val T = 200
  val backOffConditions = TestingConditions(
    backOff = x => x,
    backPressure = BackPressure.never,
    desiredRate = 1 every T.millis,
    productionRate = 1 every 1.millis,
    producers = 1,
    jobsPerProducer = 100,
    jobCompletion = 25.millis,
    samplingWindow = samplingWindow
  )

  "Backpressure" should {
    "follow the provided backOff function" in {

      def linearBackOff: FiniteDuration => FiniteDuration = _ + T.millis
      def everyJob: BackPressure.Ack[Int] = _ => BackPressure(true)

      val conditions = backOffConditions.copy(
        backOff = linearBackOff,
        backPressure = everyJob
      )

      mkScenario(conditions) must returnValue { (res: Result) =>
        val backOff = res.jobExecutionMetrics.diffs.map(_ / T)
        val linear = Stream.iterate(1)(_ + 1).take(backOff.length).toVector

        backOff must beEqualTo(linear)
      }
    }

    "only apply when jobs are signalling for it" in {
      def constantBackOff: FiniteDuration => FiniteDuration = _ => T.millis * 2
      def everyOtherJob: BackPressure.Ack[Int] =
        i => BackPressure(i.right.get % 2 == 0)

      val conditions = backOffConditions.copy(
        backOff = constantBackOff,
        backPressure = everyOtherJob
      )

      mkScenario(conditions) must returnValue { (res: Result) =>
        val backOff = res.jobExecutionMetrics.diffs.map(_ / T)
        val alternating = Stream(1, 2).repeat.take(backOff.length).toVector

        backOff must beEqualTo(alternating)
      }
    }
  }
}

package com.roozky.tools.fs2.etl

import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.kernel.Semigroup
import com.roozky.tools.fs2.etl.EtlMetrics.{ExtractMetrics, LoadMetrics, StageMetrics, TransformMetrics}
import com.roozky.tools.fs2.etl.model.DeadLetterWork.{DoneWork, FailedWork, StopWork, TerminateWork}
import com.roozky.tools.fs2.etl.model.EtlOptions.MaxWorkers
import com.roozky.tools.fs2.etl.model.EtlResult.{EtlFailed, EtlSucceeded}
import com.roozky.tools.fs2.etl.model.EtlWork.{ExtractWork, LoadWork, TransformWork}
import com.roozky.tools.fs2.etl.model.{DeadLetterWork, EtlOptions, EtlResult, EtlWork}
import fs2.concurrent.{NoneTerminatedQueue, Queue}
import org.slf4j.{Logger, LoggerFactory}

import scala.language.higherKinds

// todo - terminate if too many consecutive work unit failed - kind of circuit breaker. Assume the last extract work that needs to
// return None, fails then there will be no NONE which is required to terminate stream, or down-stream stream

case class EtlRunner[F[_], EW, TW, LW, LR](etl: EtlProgram[F, EW, TW, LW, LR],
                                           metricsOpt: Option[EtlMetrics[F]] = None)
                                          (implicit F: Sync[F], C: Concurrent[F], LRSG: Semigroup[LR]) {

  private lazy val logger: Logger = LoggerFactory.getLogger(getClass.getName)

  private val metrics: EtlMetrics[F] = metricsOpt.getOrElse(new EtlMetrics.NoOpMetrics[F]())

  def run(options: EtlOptions): F[EtlResult[LR]] = {

    val program: fs2.Stream[F, EtlResult[LR]] = for {
      eWorkQueue <- fs2.Stream.eval(Queue.boundedNoneTerminated[F, ExtractWork[EW]](options.extractStage.maxQueuedWork.toInt))
      tWorkQueue <- fs2.Stream.eval(Queue.boundedNoneTerminated[F, TransformWork[TW]](options.transformStage.maxQueuedWork.toInt))
      lWorkQueue <- fs2.Stream.eval(Queue.boundedNoneTerminated[F, LoadWork[LW]](options.loadStage.maxQueuedWork.toInt))
      deadLetterQueue <- fs2.Stream.eval(Queue.bounded[F, DeadLetterWork[LR]](100))
      etlResult <- createDeadLetterStream(deadLetterQueue)(etl.deadLetterWorker) concurrently
        createLoadStream(options.loadStage.maxWorkers)(lWorkQueue, deadLetterQueue, metrics.loadMetrics())(etl.loadWorker) concurrently
        createTransformStream(options.transformStage.maxWorkers)(tWorkQueue, lWorkQueue, deadLetterQueue, metrics.transformMetrics())(etl.transformWorker) concurrently
        createExtractorStream(options.extractStage.maxWorkers)(eWorkQueue, tWorkQueue, deadLetterQueue, metrics.extractMetrics())(etl.extractWorker) concurrently
        createWorkGeneratorStream(etl.extractWorkFactory)(eWorkQueue, deadLetterQueue)
    } yield etlResult

    program
      .compile
      .toList
      .map(_.head)
  }

  private def createWorkGeneratorStream(workGenerator: etl.ExtractWorkFactory)(outQueue: NoneTerminatedQueue[F, ExtractWork[EW]],
                                                                               deadLetterQueue: Queue[F, DeadLetterWork[LR]]): fs2.Stream[F, Int] = {
    workGenerator()
      // the work factory generates List of work units so this flatten it out - single work unit at a time
      .flatMap(workUnits => fs2.Stream.emits(workUnits))
      // wraps the work unit - not used for anything yet but can be used to add extra information to the work unit by framework later
      .map(_.map(ExtractWork.apply))
      // enqueue a single work unit at a time. Passing Work to a next step
      .evalTap(enqueueIfNotNone(outQueue))
      // stop processing stream on 1st None - job done no need to keep this stream running
      .unNoneTerminate
      // keep count of work unites processed
      .fold(0)((consumed, _) => consumed + 1)
      // nothing downstream queue that no more work is coming
      .flatMap(result => fs2.Stream.eval(outQueue.enqueue1(None)).map(_ => result))
  }

  private def createExtractorStream(numberOfWorker: MaxWorkers)
                                   (inQueue: NoneTerminatedQueue[F, ExtractWork[EW]],
                                    outQueue: NoneTerminatedQueue[F, TransformWork[TW]],
                                    deadLetterQueue: Queue[F, DeadLetterWork[LR]],
                                    metrics: ExtractMetrics[F])
                                   (worker: etl.ExtractWorker): fs2.Stream[F, Int] = {
    fs2.Stream
      .repeatEval(inQueue.dequeue1)
      // stop processing on 1st None work
      .unNoneTerminate
      // executes in parallel
      .parEvalMapUnordered(numberOfWorker.toInt)(processWork(worker, deadLetterQueue, metrics))
      // the work factory generates List of work units so this flatten it out - single work unit at a time
      .flatMap(workUnits => fs2.Stream.emits(workUnits))
      // wraps the work unit - not used for anything yet but can be used to add extra information to the work unit by framework later
      .map(_.map(TransformWork.apply))
      // enqueue a single work unit at a time. Passing Work to a next step
      .evalTap(enqueueIfNotNone(outQueue))
      // stop processing the stream on 1st None by send None to inQueue which signals no further enqueues should be accepted
      .evalMap(enqueueIfNone(inQueue))
      // keep count of work unites processed
      .fold(0)((consumed, _) => consumed + 1)
      // nothing downstream queue that no more work is coming
      .flatMap(result => fs2.Stream.eval(outQueue.enqueue1(None)).map(_ => result))
  }

  private def createTransformStream(numberOfWorker: MaxWorkers)
                                   (inQueue: NoneTerminatedQueue[F, TransformWork[TW]],
                                    outQueue: NoneTerminatedQueue[F, LoadWork[LW]],
                                    deadLetterQueue: Queue[F, DeadLetterWork[LR]],
                                    metrics: TransformMetrics[F])
                                   (worker: etl.TransformWorker): fs2.Stream[F, Int] = {
    fs2.Stream
      .repeatEval(inQueue.dequeue1)
      // stop processing on 1st None work
      .unNoneTerminate
      // executes in parallel
      .parEvalMapUnordered(numberOfWorker.toInt)(processWork(worker, deadLetterQueue, metrics))
      // the work factory generates List of work units so this flatten it out - single work unit at a time
      .flatMap(workUnits => fs2.Stream.emits(workUnits))
      // wraps the work unit - not used for anything yet but can be used to add extra information to the work unit by framework later
      .map(_.map(LoadWork.apply))
      // enqueue a single work unit at a time. Passing Work to a next step
      .evalTap(enqueueIfNotNone(outQueue))
      // stop processing the stream on 1st None by send None to inQueue which signals no further enqueues should be accepted
      .evalMap(enqueueIfNone(inQueue))
      // keep count of work unites processed
      .fold(0)((consumed, _) => consumed + 1)
      // nothing downstream queue that no more work is coming
      .flatMap(result => fs2.Stream.eval(outQueue.enqueue1(None)).map(_ => result))
  }

  private def createLoadStream(numberOfWorker: MaxWorkers)
                              (inQueue: NoneTerminatedQueue[F, LoadWork[LW]],
                               deadLetterQueue: Queue[F, DeadLetterWork[LR]],
                               metrics: LoadMetrics[F])
                              (worker: etl.LoadWorker): fs2.Stream[F, Unit] = {
    fs2.Stream
      .repeatEval(inQueue.dequeue1)
      // stop processing on 1st None work
      .unNoneTerminate
      // executes in parallel
      .parEvalMapUnordered(numberOfWorker.toInt)(processWork(worker, deadLetterQueue, metrics))
      // the work factory generates List of work units so this flatten it out - single work unit at a time
      .flatMap(workUnits => fs2.Stream.emits(workUnits))
      .map(wuOpt => wuOpt.map(wu => (1, wu)))
      // keep count of work unites processed
      .fold(Option.empty[(Int, LR)])((aggOpt, current) =>
      (aggOpt, current) match {
        case (None, wu@Some(_)) => wu
        case (Some((aggCount, aggWu)), Some((increment, wu))) => Some((aggCount + increment, LRSG.combine(aggWu, wu)))
        case _ => aggOpt
      })
      .flatMap(result => fs2.Stream.eval {
        result match {
          case Some((wuCount, aggResult)) => deadLetterQueue.enqueue1(DoneWork(wuCount, Some(aggResult)))
          case None => deadLetterQueue.enqueue1(DoneWork(0, None))
        }
      })
  }

  private def createDeadLetterStream(deadLetterQueue: Queue[F, DeadLetterWork[LR]])
                                    (worker: etl.DeadLetterWorker): fs2.Stream[F, EtlResult[LR]] = {
    fs2.Stream
      .repeatEval(deadLetterQueue.dequeue1)
      .evalMap(processDeadLetterWork(worker))
      .mapAccumulate(0)((acc, current) => (acc + 1, current))
      .dropWhile {
        case (_, Some(_: TerminateWork[_])) => false
        case _ => true
      }
      .take(1)
      .map {
        case (failedWorkCount, Some(StopWork(message))) => EtlFailed(message, failedWorkCount - 1)
        case (failedWorkCount, Some(DoneWork(workLoaded, loadResult))) => EtlSucceeded(workLoaded, failedWorkCount - 1, loadResult)
      }
  }

  private def processWork[W <: EtlWork, WR](worker: W => F[List[Option[WR]]],
                                            deadLetterQueue: Queue[F, DeadLetterWork[LR]],
                                            stageMetrics: StageMetrics[F]): W => F[List[Option[WR]]] = { work =>

    val r: F[List[Option[WR]]] = for {
      _ <- stageMetrics.recordWorkReceived()
      wu <- worker(work)
      _ <- stageMetrics.recordWorkSucceeded()
    } yield wu

    r.handleErrorWith(handleWorkError(work, _, deadLetterQueue, stageMetrics))
  }

  private def processDeadLetterWork(worker: etl.DeadLetterWorker): DeadLetterWork[LR] => F[Option[TerminateWork[LR]]] = {
    case termWork: TerminateWork[LR] => F.delay(Some(termWork))
    case work: FailedWork => {
      val r = for {
        _ <- metrics.recordDeadLetterWorkReceived()
        result <- worker(work)
      } yield result

      r.handleErrorWith(error => F.delay(logger.error(s"Error processing dead letter work.", error)) *> F.delay(Option.empty))
    }
  }

  private def handleWorkError[W <: EtlWork, WR](work: EtlWork,
                                                error: Throwable,
                                                deadLetterQueue: Queue[F, DeadLetterWork[LR]],
                                                stageMetrics: StageMetrics[F]): F[List[Option[WR]]] = {
    val r = for {
      _ <- stageMetrics.recordWorkFailed()
      _ <- deadLetterQueue.enqueue1(FailedWork(work, error))
    } yield List.empty[Option[WR]]

    r.handleErrorWith(error => F.delay(logger.error(s"Error while handling work error", error)) *> F.delay(List.empty[Option[WR]]))
  }

  private def enqueueIfNone[IWU, OWU](queue: NoneTerminatedQueue[F, OWU]): Option[IWU] => F[Unit] = {
    case None => queue.enqueue1(None)
    case _ => F.unit
  }

  private def enqueueIfNotNone[WU](queue: NoneTerminatedQueue[F, WU]): Option[WU] => F[Unit] = {
    case wu@Some(_) => queue.enqueue1(wu)
    case _ => F.unit
  }
}

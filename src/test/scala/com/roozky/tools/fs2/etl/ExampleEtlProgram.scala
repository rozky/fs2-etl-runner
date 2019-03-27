package com.roozky.tools.fs2.etl

import cats.effect.Sync
import cats.implicits._
import com.roozky.tools.fs2.etl.EtlProgram.singleWork
import com.roozky.tools.fs2.etl.model.EtlWork.{ExtractWork, LoadWork, TransformWork, WorkId}
import com.roozky.tools.fs2.helpers.Fs2Streams

case class ExampleEtlProgram[F[_]](lastWorkId: Option[WorkId],
                                   lastExtractWork: Option[WorkId] = None,
                                   failExtractWork: Option[WorkId] = None,
                                   failDeadLetterWork: Boolean = false)(implicit F: Sync[F])
  extends EtlProgram[F, WorkId, WorkId, WorkId, WorkId] {

  override def extractWorkFactory: ExtractWorkFactory =
    () =>
      Fs2Streams.stepped(1, 1)
        .map(WorkId.apply)
        .evalMap[F, List[Option[WorkId]]] { workId =>
        lastWorkId match {
          case None => EtlProgram.singleWork[F, WorkId](workId)
          case Some(lastWork) if workId.value <= lastWork.value => EtlProgram.singleWork[F, WorkId](workId)
          case _ => EtlProgram.noWork()
        }
      }

  override def extractWorker: ExtractWorker = {
    case ExtractWork(work) =>
      (lastExtractWork, failExtractWork) match {
        case (_, Some(failWork)) if work.equals(failWork) =>
          F.raiseError(new RuntimeException(s"Simulated error at the Extract stage when processing $work"))
        case (Some(lastWork), _) if work.value <= lastWork.value => EtlProgram.singleWork[F, WorkId](work)
        case (Some(lastWork), _) if work.value > lastWork.value => EtlProgram.noWork()
        case _ => EtlProgram.singleWork[F, WorkId](work)
      }
  }

  override def transformWorker: TransformWorker = {
    case TransformWork(work) => singleWork(work)
  }

  override def loadWorker: LoadWorker = {
    case LoadWork(work) => F.delay(println(s"Loaded $work")) *> singleWork(work)
  }

  override def deadLetterWorker: DeadLetterWorker = { work =>
    if (failDeadLetterWork) {
      F.raiseError(new RuntimeException("ETL has failed and been stopped."))
    } else {
      F.delay(println(s"[Dead Letter] Failed to process $work")) *> F.delay(None)
    }
  }
}
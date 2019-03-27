package com.roozky.tools.fs2.etl

import cats.effect.Sync
import com.roozky.tools.fs2.etl.model.DeadLetterWork.{FailedWork, TerminateWork}
import com.roozky.tools.fs2.etl.model.EtlWork.{ExtractWork, LoadWork, TransformWork}

import scala.language.higherKinds


/**
  * ETL = Extract(read) => Transform(process) => Load(write)
  *
  * @tparam F an effect type like IO
  * @tparam EW a type of a work to do at Extract stage
  * @tparam TW a type of a work to do at Transform stage
  * @tparam LW a a type of a work to do at Load stage
  * @tparam LR a result of the Load stage
  */
trait EtlProgram[F[_], EW, TW, LW, LR] {
  type ExtractWorkFactory = () => fs2.Stream[F, List[Option[EW]]]

  /**
    * Responsible for executing a single extraction task/batch. Overall extraction phase consists of 0..N of this tasks.
    */
  type ExtractWorker = ExtractWork[EW] => F[List[Option[TW]]]

  type TransformWorker = TransformWork[TW] => F[List[Option[LW]]]

  type LoadWorker = LoadWork[LW] => F[List[Option[LR]]]

  type DeadLetterWorker = FailedWork => F[Option[TerminateWork[LR]]]

  /**
    * Creates a generator of extract work to use
    */
  def extractWorkFactory: ExtractWorkFactory

  /**
    * Creates a worker to use to execute a single unit of Extract work
    * @return worker
    */
  def extractWorker: ExtractWorker

  /**
    * Creates a worker to use to execute a single unit of Transform work
    * @return worker
    */
  def transformWorker: TransformWorker

  /**
    * Creates a worker to use to execute a single unit of Load work
    * @return worker
    */
  def loadWorker: LoadWorker

  /**
    * Creates a worker to use to process Failed Work (from any ETL stage)
    * @return worker
    */
  def deadLetterWorker: DeadLetterWorker
}

object EtlProgram {
  def pureSingleWork[W](w: W): List[Option[W]] = List(Some(w))

  def singleWork[F[_], W](w: W)(implicit F: Sync[F]): F[List[Option[W]]] = F.delay(List(Some(w)))

  def noWork[F[_], W]()(implicit F: Sync[F]): F[List[Option[W]]] = F.delay(List(None))
}

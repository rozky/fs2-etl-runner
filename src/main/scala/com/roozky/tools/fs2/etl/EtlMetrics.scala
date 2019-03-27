package com.roozky.tools.fs2.etl

import cats.effect.Sync
import com.roozky.tools.fs2.etl.EtlMetrics.{ExtractMetrics, LoadMetrics, TransformMetrics}

import scala.language.higherKinds

trait EtlMetrics[F[_]] {
  def extractMetrics(): ExtractMetrics[F]

  def transformMetrics(): TransformMetrics[F]

  def loadMetrics(): LoadMetrics[F]

  def recordDeadLetterWorkReceived(): F[Unit]
}

object EtlMetrics {

  trait StageMetrics[F[_]] {
    def recordWorkReceived(): F[Unit]

    def recordWorkSucceeded(): F[Unit]

    def recordWorkFailed(): F[Unit]
  }

  trait ExtractMetrics[F[_]] extends StageMetrics[F]
  trait TransformMetrics[F[_]] extends StageMetrics[F]
  trait LoadMetrics[F[_]] extends StageMetrics[F]

  class NoOpStageMetrics[F[_]]()(implicit F: Sync[F]) extends ExtractMetrics[F] with TransformMetrics[F] with LoadMetrics[F] {
    override def recordWorkReceived(): F[Unit] = F.unit

    override def recordWorkSucceeded(): F[Unit] = F.unit

    override def recordWorkFailed(): F[Unit] = F.unit
  }

  class NoOpMetrics[F[_]]()(implicit F: Sync[F]) extends EtlMetrics[F] {

    override def extractMetrics(): ExtractMetrics[F] = new NoOpStageMetrics[F]()

    override def transformMetrics(): TransformMetrics[F] = new NoOpStageMetrics[F]()

    override def loadMetrics(): LoadMetrics[F] = new NoOpStageMetrics[F]()

    override def recordDeadLetterWorkReceived(): F[Unit] = F.unit
  }
}


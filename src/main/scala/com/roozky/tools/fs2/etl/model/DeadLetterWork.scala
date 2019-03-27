package com.roozky.tools.fs2.etl.model

sealed trait DeadLetterWork[+LR]

object DeadLetterWork {
  sealed trait TerminateWork[+LR] extends DeadLetterWork[LR]

  final case class FailedWork(work: EtlWork, error: Throwable) extends DeadLetterWork[Nothing]

  final case class StopWork(message: String) extends TerminateWork[Nothing]

  final case class DoneWork[LR](workLoaded: Int, loadResult: Option[LR]) extends TerminateWork[LR]

}

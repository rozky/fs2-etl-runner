package com.roozky.tools.fs2.etl.model

sealed trait EtlResult[+LR]

object EtlResult {
  final case class EtlSucceeded[LR](workLoaded: Int, workFailed: Int, loadResult: Option[LR]) extends EtlResult[LR]
  final case class EtlFailed(message: String, workFailed: Int) extends EtlResult[Nothing]
}

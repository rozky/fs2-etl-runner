package com.roozky.tools.fs2.etl.model

import cats.Semigroup

sealed trait EtlWork
object EtlWork {
  case class WorkId(value: Int) extends EtlWork {
    require(value > 0, "Work ID must be > 0")
  }

  case class ExtractWork[E](work: E) extends EtlWork {
    require(work != null, "Work is required")
  }

  case class TransformWork[T](work: T) extends EtlWork {
    require(work != null, "Work is required")
  }

  case class LoadWork[L](work: L) extends EtlWork {
    require(work != null, "Work is required")
  }

  implicit object WorkIdSemiGroup extends Semigroup[WorkId] {

    override def combine(x: WorkId, y: WorkId): WorkId = {
      if (x.value > y.value) x else y
    }
  }
}

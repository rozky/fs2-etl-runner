package com.roozky.tools.fs2.etl.model

import com.roozky.tools.fs2.etl.model.EtlOptions.StageOptions

case class EtlOptions(extractStage: StageOptions,
                      transformStage: StageOptions,
                      loadStage: StageOptions)

object EtlOptions {
  val default = EtlOptions(StageOptions.default, StageOptions.default, StageOptions.default)

  case class StageOptions(maxQueuedWork: MaxQueueSize,
                          maxWorkers: MaxWorkers)

  object StageOptions {
    val default = StageOptions(MaxQueueSize(10), MaxWorkers(10))
  }

  case class MaxWorkers(private val value: Int) {
    require(value > 0, "Number of workers must be > 0")

    def toInt: Int = value
  }

  case class MaxQueueSize(private val value: Int) {
    require(value > 0, "Queue size must be > 0")

    def toInt: Int = value
  }
}


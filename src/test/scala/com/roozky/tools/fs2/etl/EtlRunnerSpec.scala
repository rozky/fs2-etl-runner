package com.roozky.tools.fs2.etl

import cats.effect.{ContextShift, ExitCode, IO, Timer}
import com.roozky.tools.fs2.etl.model.EtlOptions
import com.roozky.tools.fs2.etl.model.EtlResult.{EtlFailed, EtlSucceeded}
import com.roozky.tools.fs2.etl.model.EtlWork.WorkId
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.ExecutionContext.Implicits.global

class EtlRunnerSpec extends FlatSpec with Matchers {

  implicit def timer: Timer[IO] = IO.timer(global)
  implicit def contextShift: ContextShift[IO] = IO.contextShift(global)

  "ETL runner" should "run ETL program successfully" in {
    //    val runner = EtlRunner(ExampleEtlProgram[IO](Some(WorkId(100)), failExtractWork = Some(WorkId(7))))
    //    val runner = EtlRunner(ExampleEtlProgram[IO](Some(WorkId(100))))
    //    val runner = EtlRunner(ExampleEtlProgram[IO](Some(WorkId(2))))
    val runner = EtlRunner(ExampleEtlProgram[IO](Some(WorkId(2))))

    // todo - what is work error happen when the last extract (etl may never terminate) or load work is processed

    val program = for {
      result <- runner.run(EtlOptions.default)
      _ <- result match {
        case r: EtlSucceeded[_] => IO.delay(println(s"ETL process has FINISHED. Load result: ${r.loadResult}. ${r.workLoaded} work units COMPLETED. ${r.workFailed} work units FAILED."))
        case r: EtlFailed => IO.delay(println(s"ETL process has FAILED. Reason: ${r.message}. ${r.workFailed} work units FAILED."))
      }
    } yield ExitCode.Success

    program.unsafeRunSync()
  }

}

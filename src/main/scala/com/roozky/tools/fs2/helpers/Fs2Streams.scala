package com.roozky.tools.fs2.helpers

object Fs2Streams {
  // (0, 1) => 0,1,2,3,4,5,6,...
  // (1, 2) => 1,3,5,7,9
  def stepped[N](initValue: N, step: N)(implicit N: Numeric[N]): fs2.Stream[fs2.Pure, N] = {
    fs2.Stream.emit(step)
      .repeat
      .scan(initValue)(N.plus)
  }
}

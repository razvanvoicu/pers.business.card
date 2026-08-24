package sgrv.fe.card

import munit.FunSuite

class GridMathSuite extends FunSuite:
  private val Epsilon = 1e-6
  private val BufferRowIndices = Seq(3, 5, 7, 9, 11, 13, 15, 17)

  test("happy path: a narrow/tall frame lays out with column widths/row heights that sum exactly to the frame"):
    val frameWidth = 20.0
    val frameHeight = 900.0

    GridMath.compute(frameWidth, frameHeight) match
      case GridMath.LayoutResult.Fits(metrics) =>
        assertEqualsDouble(metrics.colWidths.sum, frameWidth, Epsilon)
        assertEqualsDouble(metrics.rowHeights.sum, frameHeight, Epsilon)
        assertEqualsDouble(metrics.colWidths(1), metrics.colWidths(5), Epsilon) // column 2 == column 6
        assertEqualsDouble(metrics.colWidths(2), metrics.colWidths(4), Epsilon) // column 3 == column 5
        assertEqualsDouble(metrics.rowHeights(2), metrics.imageSquare, Epsilon) // row 3's height is the image side
        assertEqualsDouble(
          metrics.colWidths(2) + metrics.colWidths(3) + metrics.colWidths(4),
          metrics.imageSquare,
          Epsilon
        ) // columns 3+4+5 combined equal the image side too, so its cell is square
        assertEqualsDouble(metrics.contentScale, 1.0, Epsilon)
      case other => fail(s"expected the happy path to fit; got $other")

  test("fallback: a wide-but-not-extreme frame needs a content scale strictly between 0.2 and 1.0"):
    val frameWidth = 700.0
    val frameHeight = 960.0

    GridMath.compute(frameWidth, frameHeight) match
      case GridMath.LayoutResult.Fits(metrics) =>
        assert(metrics.contentScale < 1.0, s"expected scaling to have engaged, got scale ${metrics.contentScale}")
        assert(metrics.contentScale >= 0.2, s"expected the scale to stay within its floor, got ${metrics.contentScale}")
        assertEqualsDouble(metrics.colWidths.sum, frameWidth, Epsilon)
        assertEqualsDouble(metrics.rowHeights.sum, frameHeight, Epsilon)
        // The 8 buffers aren't all equal — they split the leftover in proportion to GridSpec.BufferWeights — but
        // each one's height divided by its own weight should yield the same "unit" regardless of the weights
        // currently in effect, and the narrowest buffer (the smallest weight) should sit right on the floor set
        // by GridSpec.MinBufferShare.
        val buffers = BufferRowIndices.map(metrics.rowHeights)
        val units = buffers.zip(GridSpec.BufferWeights).map((buffer, weight) => buffer / weight)
        units.foreach(unit => assertEqualsDouble(unit, units.head, 1e-3))
        assertEqualsDouble(buffers.min, GridSpec.MinBufferShare * frameHeight, 1e-3)
      case other => fail(s"expected the frame to still fit via the fallback scale; got $other")

  test("too extreme: a short, wide frame can't clear the buffer floor even at the minimum fallback scale"):
    GridMath.compute(480.0, 20.0) match
      case GridMath.LayoutResult.TooExtreme(frameWidth, frameHeight) =>
        assertEqualsDouble(frameWidth, 480.0, Epsilon)
        assertEqualsDouble(frameHeight, 20.0, Epsilon)
      case other => fail(s"expected a short, wide frame to be reported as too extreme; got $other")

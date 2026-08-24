package sgrv.fe.card

/** Pure layout math for the business-card grid: no DOM access, so it's directly unit-testable.
  *
  * Column/row indices below are 0-based (`colWidths(0)` is column 1, `rowHeights(0)` is row 1, ...), matching a
  * plain `IndexedSeq` rather than CSS's 1-based grid-line numbering; callers add 1 when placing elements.
  */
private[fe] object GridMath:
  import GridSpec.*

  final case class GridMetrics(
      colWidths: IndexedSeq[Double],
      rowHeights: IndexedSeq[Double],
      imageSquare: Double,
      qrSquare: Double,
      contentScale: Double
  )

  enum LayoutResult:
    case Fits(metrics: GridMetrics)
    case TooExtreme(frameWidth: Double, frameHeight: Double)

  /** Attempts the happy-path layout first (its row 2 uses its own, more expensive "= column 2's width" rule).
    * If that leaves the narrowest spacing buffer under `MinBufferShare`, switches to the fallback regime (row 2
    * becomes "5% of the image height", columns 2/6 are free to grow, and every content row plus the image scale
    * by a factor `s` solved in closed form) — or, if even `s = MinFallbackScale` can't clear the floor, reports
    * that the viewport is too extreme to lay out at all.
    */
  def compute(frameWidth: Double, frameHeight: Double): LayoutResult =
    val happyPath = happyPathMetrics(frameWidth, frameHeight)
    if narrowestBuffer(happyPath) >= MinBufferShare * frameHeight then LayoutResult.Fits(happyPath)
    else
      val s = fallbackScale(frameWidth, frameHeight)
      if s < MinFallbackScale then LayoutResult.TooExtreme(frameWidth, frameHeight)
      else LayoutResult.Fits(fallbackMetrics(frameWidth, frameHeight, s.min(1.0)))

  /** Rows 4, 6, 8, 10, 12, 14, 16, 18 (0-based indices) — the 8 spacing buffers, in the same order as
    * `GridSpec.BufferWeights`.
    */
  private val BufferRowIndices: IndexedSeq[Int] = IndexedSeq(3, 5, 7, 9, 11, 13, 15, 17)

  private def narrowestBuffer(metrics: GridMetrics): Double = BufferRowIndices.map(metrics.rowHeights).min

  // ---- happy path -------------------------------------------------------------------------------------------

  private def happyPathMetrics(frameWidth: Double, frameHeight: Double): GridMetrics =
    val imageSquare = ImageGroupShare * frameWidth
    val col4 = Column4Share * frameWidth
    val col3or5 = (ImageGroupShare - Column4Share) / 2 * frameWidth
    val col2or6 = (frameWidth - 2 * EdgePx - ImageGroupShare * frameWidth) / 2

    // The happy-path-only rule: row 2's height equals column 2's width (dropped in the fallback regime below).
    val contentRows = contentRowHeights(row2 = col2or6, row3 = imageSquare, row17 = col4, frameHeight, scale = 1.0)
    assemble(frameHeight, col2or6, col3or5, col4, contentRows, imageSquare, qrSquare = col4, contentScale = 1.0)

  // ---- fallback -----------------------------------------------------------------------------------------------

  /** The 8 buffers share `unit = (frameHeight - 2*EdgePx - s*fallbackCoefficient) / weightSum` in proportion to
    * their weights, so the narrowest one (the smallest weight) is `minWeight * unit`. That's linear in `s`
    * because every fallback-regime row/column is a fixed share of `frameWidth`/`frameHeight` times `s`. Solving
    * `minWeight * unit = MinBufferShare * frameHeight` directly avoids an iterative search.
    */
  private def fallbackScale(frameWidth: Double, frameHeight: Double): Double =
    val contentShareSum =
      Row5NameShare + Row7DesignationShare + Row9TaglineShare + Row11LinksShare + Row13LinksShare + Row15ButtonShare
    val coefficient =
      frameWidth * (ImageGroupShare * (1 + Row2FallbackOfImageShare) + Column4Share) + frameHeight * contentShareSum
    val weightSum = BufferWeights.sum
    val minWeight = BufferWeights.min
    (frameHeight * (1 - MinBufferShare * weightSum / minWeight) - 2 * EdgePx) / coefficient

  private def fallbackMetrics(frameWidth: Double, frameHeight: Double, scale: Double): GridMetrics =
    val groupWidth = scale * ImageGroupShare * frameWidth
    val col4 = scale * Column4Share * frameWidth
    val col3or5 = (groupWidth - col4) / 2
    val col2or6 = (frameWidth - 2 * EdgePx - groupWidth) / 2

    val imageSquare = groupWidth
    val row2 = Row2FallbackOfImageShare * imageSquare
    val contentRows = contentRowHeights(row2, row3 = imageSquare, row17 = col4, frameHeight, scale)
    assemble(frameHeight, col2or6, col3or5, col4, contentRows, imageSquare, qrSquare = col4, contentScale = scale)

  // ---- shared assembly --------------------------------------------------------------------------------------

  /** Rows 2, 3, 5, 7, 9, 11, 13, 15, 17 — every non-edge, non-buffer row — computed identically for both regimes
    * once `row2`, `row3`, and `row17` (which differ between them) are supplied.
    */
  private def contentRowHeights(row2: Double, row3: Double, row17: Double, frameHeight: Double, scale: Double): IndexedSeq[Double] =
    IndexedSeq(
      row2,
      row3,
      scale * Row5NameShare * frameHeight,
      scale * Row7DesignationShare * frameHeight,
      scale * Row9TaglineShare * frameHeight,
      scale * Row11LinksShare * frameHeight,
      scale * Row13LinksShare * frameHeight,
      scale * Row15ButtonShare * frameHeight,
      row17
    )

  /** Expands `contentRows` (rows 2/3/5/7/9/11/13/15/17, in that order) plus the shared buffer height into the
    * full 19-entry row sequence, and the column margins into the full 7-entry column sequence.
    */
  private def assemble(
      frameHeight: Double,
      col2or6: Double,
      col3or5: Double,
      col4: Double,
      contentRows: IndexedSeq[Double],
      imageSquare: Double,
      qrSquare: Double,
      contentScale: Double
  ): GridMetrics =
    val unit = (frameHeight - 2 * EdgePx - contentRows.sum) / BufferWeights.sum
    val buffers = BufferWeights.map(_ * unit)
    val rowHeights = IndexedSeq(
      EdgePx, // row 1
      contentRows(0), // row 2
      contentRows(1), // row 3
      buffers(0), // row 4
      contentRows(2), // row 5
      buffers(1), // row 6
      contentRows(3), // row 7
      buffers(2), // row 8
      contentRows(4), // row 9
      buffers(3), // row 10
      contentRows(5), // row 11
      buffers(4), // row 12
      contentRows(6), // row 13
      buffers(5), // row 14
      contentRows(7), // row 15
      buffers(6), // row 16
      contentRows(8), // row 17
      buffers(7), // row 18
      EdgePx // row 19
    )
    GridMetrics(
      colWidths = IndexedSeq(EdgePx, col2or6, col3or5, col4, col3or5, col2or6, EdgePx),
      rowHeights = rowHeights,
      imageSquare = imageSquare,
      qrSquare = qrSquare,
      contentScale = contentScale
    )

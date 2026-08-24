package sgrv.fe.card

/** Numeric layout constants for the business-card grid (7 columns x 19 rows), separated from `GridMath`'s
  * formulas so the "determined through testing, and trial-and-error" values live in one obvious place.
  *
  * All shares are fractions of the simulated viewport's width (`vw`) or height (`vh`), per the spec. Buffer
  * heights and every other computed size are carried as `Double` throughout `GridMath`, giving them far finer
  * resolution than a whole pixel (the spec's "10f" requirement) without needing a separate unit.
  */
private[fe] object GridSpec:
  /** Columns 1/7 and rows 1/19: a small fixed edge margin, in px, independent of viewport size. */
  val EdgePx: Double = 10.0

  /** Mobile viewport cap the `.viewport-frame` never exceeds, per "at most 480 x 960". */
  val MaxFrameWidth: Double = 480.0
  val MaxFrameHeight: Double = 960.0

  /** Columns 3+4+5 combined, and row 3's height (the photo row) in the happy path: both 70% of frame width,
    * which is what guarantees the photo's cell is square.
    */
  val ImageGroupShare: Double = 0.70

  /** Column 4 alone (and row 17, the QR row, whose height equals column 4's width). */
  val Column4Share: Double = 0.10

  /** Row 2 in the happy path equals column 2's width; row 2 has no independent share. */

  /** Row 2's fallback rule: 5% of the (now shrunk) image height, replacing the happy-path rule above. */
  val Row2FallbackOfImageShare: Double = 0.05

  /** Content-row heights as a share of frame height, in the happy path (row 17 excluded: it tracks column 4). */
  val Row5NameShare: Double = 0.1
  val Row7DesignationShare: Double = 0.07
  val Row9TaglineShare: Double = 0.05
  val Row11LinksShare: Double = 0.02

  /** Not given an explicit share in the spec; defaulted here to match row 11 (also a two-link row) — a plain
    * tunable constant, adjust after visual testing.
    */
  val Row13LinksShare: Double = 0.02

  /** Not given an explicit share in the spec ("a small button"); defaulted here — a plain tunable constant,
    * adjust after visual testing.
    */
  val Row15ButtonShare: Double = 0.05

  /** The narrowest of the 8 spacing buffers (rows 4/6/8/10/12/14/16/18) must be at least this share of frame
    * height, or the fallback scale-down engages.
    */
  val MinBufferShare: Double = 0.0001

  /** The 8 spacing buffers (rows 4/6/8/10/12/14/16/18, in that order) share the vertical leftover in proportion
    * to these weights rather than always splitting it equally — e.g. halving a weight halves that buffer's
    * share of a given amount of leftover space relative to the others. Tunable per gap.
    */
  val BufferWeightRow4: Double = 0.1 // between the photo and the name
  val BufferWeightRow6: Double = 0.1 // between the name and the designation — pulled in closer
  val BufferWeightRow8: Double = 0.1 // between the designation and the tagline
  val BufferWeightRow10: Double = 1.0 // between the tagline and the first link row
  val BufferWeightRow12: Double = 0.3 // between the two link rows
  val BufferWeightRow14: Double = 1.0 // between the second link row and the button
  val BufferWeightRow16: Double = 2.0 // between the button and the QR code
  val BufferWeightRow18: Double = 1.0 // between the QR code and the bottom edge

  val BufferWeights: IndexedSeq[Double] = IndexedSeq(
    BufferWeightRow4,
    BufferWeightRow6,
    BufferWeightRow8,
    BufferWeightRow10,
    BufferWeightRow12,
    BufferWeightRow14,
    BufferWeightRow16,
    BufferWeightRow18
  )

  /** Below this fallback scale, even the most compressed layout can't clear `MinBufferShare`; the card is
    * replaced by a "please resize" message instead of a squeezed grid.
    */
  val MinFallbackScale: Double = 0.2

  /** Each text element's font size as a fraction of its own row's computed height, so resizing the row (either
    * the happy-path share or the fallback-scaled one) automatically resizes the font proportionally, with no
    * separate scale computation needed at render time. Tunable independently per element.
    */
  val NameFontFraction: Double = 1.0
  val DesignationFontFraction: Double = 1.0
  val TaglineFontFraction: Double = 1.0
  val LinkFontFraction: Double = 0.9
  val ButtonFontFraction: Double = 0.4

  /** That row-height-derived font size is only an upper bound: on a narrow-but-tall viewport, a row's height
    * share (of frame *height*) and its column's width share (of frame *width*) scale independently, so the
    * candidate size can still overflow the available width for a long enough string. After measuring the
    * actual overflow, the corrected size is shrunk by this extra margin so sub-pixel rounding doesn't put it
    * right back at the edge of overflowing.
    */
  val FontShrinkSafetyMargin: Double = 0.97

  /** A single width/scrollWidth-ratio correction is only an approximation (font kerning isn't perfectly linear
    * in size, especially for short strings), so the fit is re-measured and re-corrected up to this many times.
    */
  val MaxFontFitAttempts: Int = 6

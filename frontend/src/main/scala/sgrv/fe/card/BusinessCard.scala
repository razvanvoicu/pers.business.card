package sgrv.fe.card

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js

/** The personal business card: a 7-column x 19-row CSS grid whose track sizes are recomputed by `GridMath`
  * every time the simulated mobile viewport (`.viewport-frame`) resizes, so the layout stays proportional and
  * non-overlapping from a small phone up to the 480x960 cap — and degrades to a plain message on the rare
  * viewport too extreme in aspect ratio to lay out at all.
  */
private[fe] object BusinessCard:

  /** The two resize-detection subscriptions set up on mount, bundled so `onMountUnmountCallbackWithState` has a
    * single piece of state to tear down again on unmount.
    */
  private final case class ResizeSubscriptions(observer: dom.ResizeObserver, removeVisualViewportListener: () => Unit)

  def render(): Div =
    val frameSize: Var[(Double, Double)] = Var((0.0, 0.0))
    val qrExpanded: Var[Boolean] = Var(false)

    val layoutSignal: Signal[GridMath.LayoutResult] =
      frameSize.signal.map((width, height) => GridMath.compute(width, height))

    val metricsSignal: Signal[GridMath.GridMetrics] =
      layoutSignal.map:
        case GridMath.LayoutResult.Fits(metrics)   => metrics
        case GridMath.LayoutResult.TooExtreme(_, _) => PlaceholderMetrics

    val cardGrid = grid(metricsSignal, qrExpanded)
    val tooExtremeMessage = div(cls := "card-message", p(CardText.TooExtremeMessage))

    div(
      cls := "viewport-frame",
      onMountUnmountCallbackWithState(
        mount = ctx =>
          val element = ctx.thisNode.ref
          def measure(): Unit =
            val rect = element.getBoundingClientRect()
            frameSize.set((rect.width, rect.height))

          // Seeded synchronously (rather than waiting for the observer's first, asynchronous callback) so the
          // very first render already reflects the real size instead of briefly showing the fallback message.
          measure()

          // Covers ordinary browser/viewport resizes: it fires whenever .viewport-frame's own computed CSS box
          // changes size.
          val observer = new dom.ResizeObserver((_, _) => measure())
          observer.observe(element)

          // Some Android WebViews don't reliably recompute vw/dvh-derived sizes when the OS resizes a
          // floating/split-screen app window, so the element's box can end up simply never changing — leaving
          // the ResizeObserver above with nothing to report. window.visualViewport's own resize event is the
          // API purpose-built for exactly this class of "what's visible changed for a reason CSS alone might
          // not reflect" case (it also covers on-screen keyboards and pinch-zoom); not in scalajs-dom's typed
          // facade, so it's reached dynamically. Absent entirely on old enough browsers, in which case this is
          // just a no-op and the ResizeObserver above remains the only trigger.
          val visualViewport = dom.window.asInstanceOf[js.Dynamic].visualViewport
          val removeVisualViewportListener =
            if js.isUndefined(visualViewport) then () => ()
            else
              val listener: js.Function1[dom.Event, Unit] = _ => measure()
              visualViewport.addEventListener("resize", listener)
              () =>
                val _ = visualViewport.removeEventListener("resize", listener)
                ()

          ResizeSubscriptions(observer, removeVisualViewportListener)
        ,
        unmount = (_, subscriptions) =>
          subscriptions.foreach: s =>
            s.observer.disconnect()
            s.removeVisualViewportListener()
      ),
      child <-- layoutSignal.map:
        case GridMath.LayoutResult.Fits(_)          => cardGrid
        case GridMath.LayoutResult.TooExtreme(_, _) => tooExtremeMessage
    )

  /** A harmless zero-sized value used only while `cardGrid` is unmounted (i.e. while `TooExtreme` is showing) —
    * its reactive style bindings still hold a value even though nothing of theirs is on screen.
    */
  private val PlaceholderMetrics = GridMath.GridMetrics(
    colWidths = IndexedSeq.fill(7)(0.0),
    rowHeights = IndexedSeq.fill(19)(0.0),
    imageSquare = 0.0,
    qrSquare = 0.0,
    contentScale = 1.0
  )

  private val downloadAttr = htmlAttr("download", StringAsIsCodec)
  private val targetAttr = htmlAttr("target", StringAsIsCodec)

  private def px(value: Double): String = f"$value%.3fpx"

  private def gridTemplateStyle(metrics: GridMath.GridMetrics): String =
    val columns = metrics.colWidths.map(px).mkString(" ")
    val rows = metrics.rowHeights.map(px).mkString(" ")
    s"grid-template-columns: $columns; grid-template-rows: $rows;"

  /** Sets the candidate font size (a fraction of the row's own height), then measures whether the single-line
    * text actually fits the element's rendered width and shrinks further if not. The row-height share alone
    * isn't enough: it tracks frame *height*, while the element's column tracks frame *width*, and those scale
    * independently — on a narrow-but-tall viewport a tall row can still land a font size wider than its
    * (now-narrow) column, which `white-space: nowrap` would otherwise silently clip behind an ellipsis.
    */
  private def applyFittedFontSize(element: dom.HTMLElement, candidatePx: Double): Unit =
    if candidatePx > 0 then
      var size = candidatePx
      element.style.setProperty("font-size", px(size))
      var attempt = 0
      while
        val clientWidth = element.clientWidth
        element.scrollWidth > clientWidth && clientWidth > 0 && attempt < GridSpec.MaxFontFitAttempts
      do
        size = size * element.clientWidth.toDouble / element.scrollWidth.toDouble * GridSpec.FontShrinkSafetyMargin
        element.style.setProperty("font-size", px(size))
        attempt += 1

  /** `document.fonts.ready` resolves once every web font referenced by the page's CSS has actually finished
    * loading (immediately, if that has already happened) — not in scalajs-dom's typed facade, so reached
    * dynamically. On a hard reload, the browser bypasses its disk cache for the Google Fonts requests too, so
    * they're still in flight when the app first mounts and measures text width — that first measurement lands
    * against whichever fallback font is active in that instant, and can be wrong once the real font swaps in a
    * moment later with different glyph widths. A no-op if the API is unavailable.
    */
  private def whenFontsReady(callback: () => Unit): Unit =
    val fonts = dom.document.asInstanceOf[js.Dynamic].fonts
    if !js.isUndefined(fonts) then fonts.ready.asInstanceOf[js.Promise[js.Any]].toFuture.foreach(_ => callback())

  /** A modifier version of `applyFittedFontSize`, re-applied every time the row's computed height changes, and
    * once more when the page's web fonts finish loading (see `whenFontsReady`) in case the very first
    * measurement above was fitted against a temporary fallback font instead.
    */
  private def fitFontSize[El <: ReactiveHtmlElement[? <: dom.HTMLElement]](
      metricsSignal: Signal[GridMath.GridMetrics],
      rowIndex: Int,
      fraction: Double
  ): Modifier[El] =
    onMountCallback[El]: ctx =>
      val element: dom.HTMLElement = ctx.thisNode.ref
      // Signal.now() is protected[airstream], unreachable from here, so the latest value is tracked by hand
      // instead of read back from metricsSignal — updated by the same subscription below.
      var lastMetrics: GridMath.GridMetrics = PlaceholderMetrics

      metricsSignal.foreach { metrics =>
        lastMetrics = metrics
        applyFittedFontSize(element, metrics.rowHeights(rowIndex) * fraction)
      }(using ctx.owner): Unit
      whenFontsReady(() => applyFittedFontSize(element, lastMetrics.rowHeights(rowIndex) * fraction))

  private def externalLink(cssClass: String, label: String, url: String): Anchor =
    a(
      cls := s"card-link $cssClass",
      href := url,
      targetAttr := "_blank",
      rel := "noopener noreferrer",
      label
    )

  private def grid(metricsSignal: Signal[GridMath.GridMetrics], qrExpanded: Var[Boolean]): Div =
    div(
      cls := "card-grid",
      styleAttr <-- metricsSignal.map(gridTemplateStyle),
      img(
        cls := "card-photo",
        src := "/mug.jpg",
        alt := CardText.Name
      ),
      div(
        cls := "card-name",
        fitFontSize(metricsSignal, rowIndex = 4, GridSpec.NameFontFraction),
        CardText.Name
      ),
      div(
        cls := "card-designation",
        fitFontSize(metricsSignal, rowIndex = 6, GridSpec.DesignationFontFraction),
        CardText.Designation
      ),
      div(
        cls := "card-tagline",
        fitFontSize(metricsSignal, rowIndex = 8, GridSpec.TaglineFontFraction),
        CardText.Tagline
      ),
      a(
        cls := "card-link card-link-resume",
        href := CardText.ResumeHref,
        downloadAttr := CardText.ResumeDownloadName,
        fitFontSize(metricsSignal, rowIndex = 10, GridSpec.LinkFontFraction),
        CardText.ResumeLinkLabel
      ),
      externalLink("card-link-linkedin", CardText.LinkedInLabel, CardText.LinkedInUrl).amend(
        fitFontSize(metricsSignal, rowIndex = 10, GridSpec.LinkFontFraction)
      ),
      externalLink("card-link-13-left", CardText.Link13LeftLabel, CardText.Link13LeftUrl).amend(
        fitFontSize(metricsSignal, rowIndex = 12, GridSpec.LinkFontFraction)
      ),
      externalLink("card-link-13-right", CardText.Link13RightLabel, CardText.Link13RightUrl).amend(
        fitFontSize(metricsSignal, rowIndex = 12, GridSpec.LinkFontFraction)
      ),
      button(
        typ := "button",
        cls := "card-contact-button",
        fitFontSize(metricsSignal, rowIndex = 14, GridSpec.ButtonFontFraction),
        // The button's action is defined by a later spec; it's intentionally inert for now.
        CardText.ContactButtonLabel
      ),
      button(
        typ := "button",
        cls := "card-qr-trigger",
        aria.label := "Show a larger QR code to scan",
        onClick --> (_ => qrExpanded.set(true)),
        img(
          cls := "card-qr",
          src := "/qr-code.png",
          alt := "QR code linking to this page"
        )
      ),
      div(
        cls := "card-qr-overlay",
        cls("is-open") <-- qrExpanded.signal,
        button(
          typ := "button",
          cls := "card-qr-overlay-button",
          aria.label := "Hide the enlarged QR code",
          tabIndex <-- qrExpanded.signal.map(if _ then 0 else -1),
          onClick --> (_ => qrExpanded.set(false)),
          img(
            cls := "card-qr-overlay-image",
            src := "/qr-code.png",
            alt := "QR code linking to this page"
          )
        )
      )
    )

package sgrv.fe.card

import com.raquo.laminar.api.L.*
import com.raquo.laminar.codecs.StringAsIsCodec
import com.raquo.laminar.nodes.ReactiveHtmlElement
import org.scalajs.dom
import sgrv.api.{PersonalParticulars, SubmitMessageRequest}
import zio.json.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

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

  /** The contact-message flow's progression: opening the editor, submitting it, and reporting the outcome are
    * mutually exclusive, so a single state (rather than several independent booleans) rules out invalid
    * combinations like a waiting spinner shown at the same time as the editor.
    */
  private enum ContactFlowState:
    case Closed
    case Editing
    case Submitting
    case Succeeded
    case Failed(details: String)

  def render(): Div =
    println("Rendering business card")
    val frameSize: Var[(Double, Double)] = Var((0.0, 0.0))
    val qrExpanded: Var[Boolean] = Var(false)
    val contactFlow: Var[ContactFlowState] = Var(ContactFlowState.Closed)
    val messageText: Var[String] = Var(loadMessageDraft())
    val particulars: Var[PersonalParticulars] = Var(EmptyParticulars)

    // The very first font-size measurement for the fetched particulars (name/designation/tagline) can be wrong
    // in two independent, unrelated ways: against a temporary fallback font if the web fonts haven't finished
    // loading yet (see `whenFontsReady`), and/or against the empty placeholder if this fetch hasn't resolved yet.
    // Rather than coupling this file's font-loading/fetch code to `fitFontSizeWithText`'s internals, both
    // readiness signals converge here on dispatching a synthetic "resize" event once *both* have settled —
    // the same event `.viewport-frame`'s own visualViewport listener below already reacts to by remeasuring and
    // refitting every piece of text on the card, exactly as a real resize does.
    var fontsReady = false
    var particularsReady = false
    def triggerResizeOnceBothReady(): Unit =
      if fontsReady && particularsReady then
        fontsReady = false
        particularsReady = false
        triggerViewportResize()

    whenFontsReady { () =>
      fontsReady = true
      triggerResizeOnceBothReady()
    }

    fetchPersonalParticulars().onComplete { result =>
      result match
        case Success(value) => particulars.set(value)
        case Failure(error) =>
          dom.console.warn(
            s"Could not load personal particulars: ${Option(error.getMessage).getOrElse(error.getClass.getSimpleName)}"
          )
      particularsReady = true
      triggerResizeOnceBothReady()
    }

    val layoutSignal: Signal[GridMath.LayoutResult] =
      frameSize.signal.map((width, height) => GridMath.compute(width, height))

    val metricsSignal: Signal[GridMath.GridMetrics] =
      layoutSignal.map:
        case GridMath.LayoutResult.Fits(metrics)   => metrics
        case GridMath.LayoutResult.TooExtreme(_, _) => PlaceholderMetrics

    val cardGrid = grid(metricsSignal, qrExpanded, contactFlow, messageText, particulars.signal)
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

  /** Shown only for the brief window before `fetchPersonalParticulars` resolves. */
  private val EmptyParticulars = PersonalParticulars("", "", "", "", "", "", "")

  /** The card's personal content (name, designation, tagline, link URLs) lives in `CardConfig` on the backend,
    * not in the frontend bundle, so it's fetched once at startup instead of compiled in. A failure (network or
    * malformed JSON) leaves the card blank rather than crashing the page, logged for diagnosis.
    */
  private def fetchPersonalParticulars(): Future[PersonalParticulars] =
    dom.fetch("/personalparticulars").flatMap { response =>
      if response.ok then
        val body: Future[String] = response.text()
        body.map(_.fromJson[PersonalParticulars].getOrElse(EmptyParticulars))
      else Future.successful(EmptyParticulars)
    }

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

  /** Dispatches a synthetic "resize" event on `visualViewport` — the same event `.viewport-frame`'s own listener
    * (see `render`) already reacts to by remeasuring and refitting every piece of text on the card via
    * `metricsSignal`. A no-op if `visualViewport` is unavailable, matching that listener's own fallback.
    */
  private def triggerViewportResize(): Unit =
    val visualViewport = dom.window.asInstanceOf[js.Dynamic].visualViewport
    if !js.isUndefined(visualViewport) then
      val _ = visualViewport.dispatchEvent(new dom.Event("resize"))
      ()

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

  /** Like `fitFontSize`, but for an element whose text content itself arrives asynchronously (the fetched
    * personal particulars) rather than being fixed at render time. The text is set here, imperatively, rather
    * than through a separate `text <-- textSignal` binder, specifically so the fit-measurement below always runs
    * immediately after the text it's measuring was actually written to the DOM — two independent subscribers to
    * the same signal would race on that ordering instead.
    *
    * Unlike `fitFontSize`, this doesn't hook `whenFontsReady` itself: `render`'s own fonts-ready/particulars-ready
    * join already forces a `metricsSignal` update (via a synthetic resize) once both are settled, which this
    * element's `metricsSignal.foreach` subscription below picks up like any other metrics change.
    */
  private def fitFontSizeWithText[El <: ReactiveHtmlElement[? <: dom.HTMLElement]](
      metricsSignal: Signal[GridMath.GridMetrics],
      textSignal: Signal[String],
      rowIndex: Int,
      fraction: Double
  ): Modifier[El] =
    onMountCallback[El]: ctx =>
      val element: dom.HTMLElement = ctx.thisNode.ref
      var lastMetrics: GridMath.GridMetrics = PlaceholderMetrics

      def applyNow(): Unit = applyFittedFontSize(element, lastMetrics.rowHeights(rowIndex) * fraction)

      textSignal.foreach { value =>
        element.textContent = value
        applyNow()
      }(using ctx.owner): Unit
      metricsSignal.foreach { metrics =>
        lastMetrics = metrics
        applyNow()
      }(using ctx.owner): Unit

  /** Adds `className` on click and removes it again after `durationMillis`, so a CSS animation keyed off that
    * class plays once per click. Toggled directly via `classList` (rather than through reactive state) so a
    * rapid re-click restarts the animation: forcing a reflow between removing and re-adding the class is what
    * makes the browser notice, since re-setting an already-present class wouldn't otherwise retrigger anything.
    */
  private def pulseOnClick[El <: ReactiveHtmlElement[? <: dom.HTMLElement]](className: String, durationMillis: Int): Modifier[El] =
    inContext[El]: thisElement =>
      onClick --> { _ =>
        val element = thisElement.ref
        element.classList.remove(className)
        val _ = element.offsetWidth
        element.classList.add(className)
        val _ = dom.window.setTimeout(() => element.classList.remove(className), durationMillis.toDouble)
      }

  private val MessageDraftStorageKey = "sgrv.contact-message-draft.v1"

  /** The drafted message survives closing the panel (Cancel) and page reloads alike, and is only cleared once a
    * submission actually succeeds. Storage access is wrapped defensively (matching the rest of the app's
    * localStorage usage): private browsing or a full storage quota can make it throw, and losing a draft is a
    * much smaller problem than crashing the page over it.
    */
  private def loadMessageDraft(): String =
    try Option(dom.window.localStorage.getItem(MessageDraftStorageKey)).getOrElse("")
    catch case NonFatal(_) => ""

  private def saveMessageDraft(value: String): Unit =
    try dom.window.localStorage.setItem(MessageDraftStorageKey, value)
    catch case NonFatal(_) => ()

  private final case class SubmitMessageFailure(status: Int, responseBody: String)
      extends RuntimeException(s"HTTP $status: $responseBody")

  private def submitMessage(text: String): Future[Unit] =
    val init = new dom.RequestInit:
      method = dom.HttpMethod.POST
      headers = js.Dictionary("Content-Type" -> "application/json")
      body = SubmitMessageRequest(text).toJson
    dom.fetch("/submit-message", init).flatMap { response =>
      if response.ok then Future.successful(())
      else
        val responseBody: Future[String] = response.text()
        responseBody.flatMap(body => Future.failed(SubmitMessageFailure(response.status, body)))
    }

  /** The text shown in the error reading pane: the server already returns a client-safe diagnostic message as the
    * response body (see `SubmitMessage.responseFor` on the backend), so it's surfaced as-is; a network-level
    * failure (no response at all) falls back to the exception's own message.
    */
  private def submitErrorDetails(error: Throwable): String =
    error match
      case SubmitMessageFailure(status, body) if body.trim.nonEmpty => s"HTTP $status\n\n$body"
      case SubmitMessageFailure(status, _)                          => s"HTTP $status"
      case other =>
        Option(other.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(other.getClass.getSimpleName)

  private def externalLink(cssClass: String, label: String, urlSignal: Signal[String]): Anchor =
    a(
      cls := s"card-link $cssClass",
      href <-- urlSignal,
      targetAttr := "_blank",
      rel := "noopener noreferrer",
      label
    )

  private def grid(
      metricsSignal: Signal[GridMath.GridMetrics],
      qrExpanded: Var[Boolean],
      contactFlow: Var[ContactFlowState],
      messageText: Var[String],
      particulars: Signal[PersonalParticulars]
  ): Div =
    div(
      cls := "card-grid",
      styleAttr <-- metricsSignal.map(gridTemplateStyle),
      img(
        cls := "card-photo",
        src := "/mug.jpg",
        alt <-- particulars.map(_.name)
      ),
      div(
        cls := "card-name",
        fitFontSizeWithText(metricsSignal, particulars.map(_.name), rowIndex = 4, GridSpec.NameFontFraction)
      ),
      div(
        cls := "card-designation",
        fitFontSizeWithText(
          metricsSignal,
          particulars.map(_.designation),
          rowIndex = 6,
          GridSpec.DesignationFontFraction
        )
      ),
      div(
        cls := "card-tagline",
        fitFontSizeWithText(metricsSignal, particulars.map(_.tagline), rowIndex = 8, GridSpec.TaglineFontFraction)
      ),
      a(
        cls := "card-link card-link-resume",
        href <-- particulars.map(_.link1),
        fitFontSize(metricsSignal, rowIndex = 10, GridSpec.LinkFontFraction),
        CardText.ResumeLinkLabel
      ),
      externalLink("card-link-linkedin", CardText.LinkedInLabel, particulars.map(_.link2)).amend(
        fitFontSize(metricsSignal, rowIndex = 10, GridSpec.LinkFontFraction)
      ),
      externalLink("card-link-13-left", CardText.Link13LeftLabel, particulars.map(_.link3)).amend(
        fitFontSize(metricsSignal, rowIndex = 12, GridSpec.LinkFontFraction)
      ),
      externalLink("card-link-13-right", CardText.Link13RightLabel, particulars.map(_.link4)).amend(
        fitFontSize(metricsSignal, rowIndex = 12, GridSpec.LinkFontFraction)
      ),
      button(
        typ := "button",
        cls := "card-contact-button",
        fitFontSize(metricsSignal, rowIndex = 14, GridSpec.ButtonFontFraction),
        // The animation gives a press-and-bounce acknowledgement independently of the click's actual effect.
        pulseOnClick("is-clicked", durationMillis = 400),
        onClick --> (_ => contactFlow.set(ContactFlowState.Editing)),
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
      ),
      div(
        cls := "card-message-overlay",
        cls("is-open") <-- contactFlow.signal.map(_ == ContactFlowState.Editing),
        div(
          cls := "card-message-panel",
          role := "dialog",
          aria.label := CardText.MessageTitle,
          div(cls := "card-message-title", CardText.MessageTitle),
          textArea(
            cls := "card-message-editor",
            placeholder := CardText.MessagePlaceholder,
            tabIndex <-- contactFlow.signal.map(state => if state == ContactFlowState.Editing then 0 else -1),
            value <-- messageText.signal,
            onInput.mapToValue --> { text =>
              messageText.set(text)
              saveMessageDraft(text)
            }
          ),
          div(
            cls := "card-message-buttons",
            button(
              typ := "button",
              cls := "card-message-button card-message-cancel",
              tabIndex <-- contactFlow.signal.map(state => if state == ContactFlowState.Editing then 0 else -1),
              onClick --> (_ => contactFlow.set(ContactFlowState.Closed)),
              CardText.MessageCancelLabel
            ),
            button(
              typ := "button",
              cls := "card-message-button card-message-submit",
              disabled <-- messageText.signal.map(_.trim.isEmpty),
              tabIndex <-- contactFlow.signal.map(state => if state == ContactFlowState.Editing then 0 else -1),
              onClick --> { _ =>
                val text = messageText.now().trim
                contactFlow.set(ContactFlowState.Submitting)
                submitMessage(text).onComplete {
                  case Success(_) =>
                    messageText.set("")
                    saveMessageDraft("")
                    contactFlow.set(ContactFlowState.Succeeded)
                  case Failure(error) =>
                    contactFlow.set(ContactFlowState.Failed(submitErrorDetails(error)))
                }
              },
              CardText.MessageSubmitLabel
            )
          )
        )
      ),
      div(
        cls := "card-waiting-overlay",
        cls("is-open") <-- contactFlow.signal.map(_ == ContactFlowState.Submitting),
        div(
          cls := "card-spinner",
          role := "status",
          aria.label := CardText.MessageWaitingLabel
        )
      ),
      div(
        cls := "card-result-overlay",
        cls("is-open") <-- contactFlow.signal.map {
          case ContactFlowState.Succeeded | ContactFlowState.Failed(_) => true
          case _                                                       => false
        },
        div(
          cls := "card-result-panel",
          role := "dialog",
          aria.label := CardText.MessageTitle,
          div(
            cls := "card-result-content",
            cls("is-error") <-- contactFlow.signal.map {
              case ContactFlowState.Failed(_) => true
              case _                          => false
            },
            text <-- contactFlow.signal.map {
              case ContactFlowState.Failed(details) => details
              case _                                => CardText.MessageSuccessText
            }
          ),
          button(
            typ := "button",
            cls := "card-message-button card-result-ok",
            tabIndex <-- contactFlow.signal.map {
              case ContactFlowState.Succeeded | ContactFlowState.Failed(_) => 0
              case _                                                       => -1
            },
            onClick --> (_ => contactFlow.set(ContactFlowState.Closed)),
            CardText.MessageOkLabel
          )
        )
      )
    )

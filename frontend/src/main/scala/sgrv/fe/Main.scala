package sgrv.fe

import com.raquo.laminar.api.L.*
import org.scalajs.dom
import sgrv.fe.card.BusinessCard

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.util.control.NonFatal

object Main:
  def main(args: Array[String]): Unit =
    registerServiceWorker()
    renderOnDomContentLoaded(dom.document.body, BusinessCard.render())

  /** A minimal, no-op service worker (`sw.js`) exists purely so Chrome's PWA installability check — which has
    * historically required a registered service worker with a fetch handler, on top of the manifest — is
    * satisfied. It does no caching: the app has no offline/app-shell support by design and still needs a live
    * connection. Registration silently no-ops if the API is unavailable, and its rejection (e.g. on an insecure,
    * non-HTTPS/non-localhost origin, where `register` is refused) is logged rather than left as an unhandled
    * promise rejection.
    */
  private def registerServiceWorker(): Unit =
    try
      if !js.isUndefined(dom.window.navigator.asInstanceOf[js.Dynamic].serviceWorker) then
        dom.window.navigator.serviceWorker
          .register("/sw.js")
          .recover { case NonFatal(error) =>
            dom.console.warn(s"Service worker registration failed: ${errorMessage(error)}")
          }: Unit
    catch case NonFatal(error) => dom.console.warn(s"Service worker registration failed: ${errorMessage(error)}")

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).map(_.trim).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

  // Whenever the font or particulars are slow to load, a fake resize event will save the day.
  dom.window.onload = _ => dom.window.setTimeout(() => BusinessCard.render(), 1000.0)

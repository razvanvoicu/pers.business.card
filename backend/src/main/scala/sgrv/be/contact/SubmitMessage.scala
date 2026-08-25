package sgrv.be.contact

import sgrv.api.SubmitMessageRequest
import sgrv.be.BackendCapabilities
import sgrv.be.core.{AccessPolicy, BackendPlugin, CapabilitySet, RequestContext}
import zio.{Cause, Clock, IO, UIO, ZIO}
import zio.http.{Method, Request, Response, Routes, Status, handler}
import zio.json.*

private[contact] sealed trait SubmitMessageError:
  def diagnostic: String
  def cause: Option[Throwable]

private[contact] object SubmitMessageError:
  final case class InvalidInput(diagnostic: String, cause: Option[Throwable] = None) extends SubmitMessageError

  final case class StorageFailure(underlying: Throwable) extends SubmitMessageError:
    override val diagnostic: String = s"Could not save the message to Firestore: ${underlying.getMessage}"
    override val cause: Option[Throwable] = Some(underlying)

  // Widened to the sealed trait (rather than using StorageFailure.apply directly) so mapError call sites infer
  // SubmitMessageError as the error type, matching the other cases in the same for-comprehension.
  def fromStorageFailure(underlying: Throwable): SubmitMessageError = StorageFailure(underlying)

/** Saves a visitor-submitted contact message, with its arrival timestamp and origin IP address, to the "Messages"
  * Firestore collection.
  */
object SubmitMessage extends BackendPlugin:
  type Requires = MessageStore

  override val id = "contact-submit-message"
  override val requirements: CapabilitySet[Requires] = CapabilitySet.one(BackendCapabilities.messageStore)
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
  override val routes: Routes[Requires & RequestContext, Nothing] =
    Routes(Method.POST / "submit-message" -> handler((request: Request) => apply(request)))

  import SubmitMessageError.*

  private def apply(request: Request): ZIO[Requires, Nothing, Response] =
    val result =
      for
        text <- requireMessage(request)
        now <- Clock.instant
        _ <- MessageStore.submit(text, clientAddress(request), now).mapError(fromStorageFailure)
      yield Response.status(Status.NoContent)
    result.foldZIO(handle, ZIO.succeed)

  private def requireMessage(request: Request): IO[SubmitMessageError, String] =
    for
      body <- request.body.asString.mapError(error =>
        InvalidInput("The request body could not be read.", Some(error))
      )
      decoded <- ZIO.fromEither(
        body
          .fromJson[SubmitMessageRequest]
          .left
          .map(details => InvalidInput(s"""Request body must be valid JSON with a string "message" field: $details"""))
      )
      text <- ZIO
        .fromOption(Option(decoded.message).map(_.trim).filter(_.nonEmpty))
        .orElseFail(InvalidInput("""Missing or empty "message" in the request body"""))
    yield text

  private[contact] def clientAddress(request: Request): String =
    request.headers
      .get("X-Forwarded-For")
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(_.split(",").head.trim)
      .orElse(request.remoteAddress.map(_.getHostAddress))
      .getOrElse("unknown")

  private def responseFor(error: SubmitMessageError): Response =
    val status = error match
      case _: InvalidInput    => Status.BadRequest
      case _: StorageFailure => Status.InternalServerError
    Response.text(error.diagnostic).status(status)

  private def handle(error: SubmitMessageError): UIO[Response] =
    val log = error.cause match
      case Some(cause) => ZIO.logErrorCause(error.diagnostic, Cause.fail(cause))
      case None        => ZIO.logError(error.diagnostic)
    log.as(responseFor(error))

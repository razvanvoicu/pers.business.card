package sgrv.be.core

import zio.ZIO
import zio.http.{Request, Response, Routes}

/** The request data resolved by a plugin's access policy and supplied to its route handlers. */
sealed trait RequestContext:
  def request: Request

object RequestContext:
  final case class Public(request: Request) extends RequestContext

/** Request-level access control that can itself require capabilities from a plugin's environment. */
trait AccessPolicy[-R]:
  def authorize(request: Request): ZIO[R, Nothing, Either[Response, RequestContext]]

object AccessPolicy:
  case object Public extends AccessPolicy[Any]:
    override def authorize(request: Request): ZIO[Any, Nothing, Either[Response, RequestContext]] =
      ZIO.succeed(Right(RequestContext.Public(request)))

/** Nominal contract implemented by independently discoverable backend components.
  *
  * The abstract type member keeps a loaded plugin's runtime capability declaration tied to the environment of its
  * routes, even after discovery has erased the plugin's concrete class.
  */
trait BackendPlugin:
  type Requires

  def id: String
  def apiVersion: Int = BackendPlugin.ApiVersion
  def requirements: CapabilitySet[Requires]
  def accessPolicy: AccessPolicy[Requires]
  def routes: Routes[Requires & RequestContext, Nothing]

object BackendPlugin:
  val ApiVersion = 1

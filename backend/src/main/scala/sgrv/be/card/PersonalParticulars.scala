package sgrv.be.card

import sgrv.api.{PersonalParticulars as PersonalParticularsDto}
import sgrv.be.BackendCapabilities
import sgrv.be.auth.CardConfig
import sgrv.be.core.{AccessPolicy, BackendPlugin, CapabilitySet, RequestContext}
import zio.ZIO
import zio.http.{Method, Request, Response, Routes, handler}
import zio.json.*

/** Reports the business card's personal content (name, designation, tagline, and the four link URLs), sourced
  * from `CardConfig` so the frontend bundle itself carries none of it.
  */
object PersonalParticulars extends BackendPlugin:
  type Requires = CardConfig

  override val id = "card-personal-particulars"
  override val requirements: CapabilitySet[Requires] = CapabilitySet.one(BackendCapabilities.cardConfig)
  override val accessPolicy: AccessPolicy[Requires] = AccessPolicy.Public
  override val routes: Routes[Requires & RequestContext, Nothing] =
    Routes(Method.GET / "personalparticulars" -> handler((_: Request) => apply))

  private def apply: ZIO[Requires, Nothing, Response] =
    ZIO.serviceWith[CardConfig](config => Response.json(toDto(config).toJson))

  private def toDto(config: CardConfig): PersonalParticularsDto =
    PersonalParticularsDto(
      config.name,
      config.designation,
      config.tagline,
      config.link1,
      config.link2,
      config.link3,
      config.link4
    )

package sgrv.be.auth

import zio.{System, Task, ZIO, ZLayer}

private[be] final case class FirestoreConfig(projectId: String, databaseId: String, location: String)
private[be] final case class CardConfig(
    name: String,
    designation: String,
    tagline: String,
    link1: String,
    link2: String,
    link3: String,
    link4: String
)
private[be] final case class AppConfig(firestore: FirestoreConfig, card: CardConfig)

private[be] object AppConfig:
  def load: Task[AppConfig] = System.envs.flatMap(environment => ZIO.fromEither(fromEnvironment(environment)))

  val live: ZLayer[Any, Throwable, AppConfig] = ZLayer.fromZIO(load)

  private[auth] def fromEnvironment(environment: Map[String, String]): Either[IllegalArgumentException, AppConfig] =
    def required(name: String): Either[IllegalArgumentException, String] =
      environment
        .get(name)
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(new IllegalArgumentException(s"Environment variable $name is not set or is empty; see prod.env"))

    for
      projectId <- required("GCP_PROJECT_ID")
      databaseId <- required("FIRESTORE_DATABASE_ID")
      location <- required("FIRESTORE_LOCATION")
      cardName <- required("CARD_NAME")
      cardDesignation <- required("CARD_DESIGNATION")
      cardTagline <- required("CARD_TAGLINE")
      cardLink1 <- required("CARD_LINK1")
      cardLink2 <- required("CARD_LINK2")
      cardLink3 <- required("CARD_LINK3")
      cardLink4 <- required("CARD_LINK4")
    yield AppConfig(
      FirestoreConfig(projectId, databaseId, location),
      CardConfig(cardName, cardDesignation, cardTagline, cardLink1, cardLink2, cardLink3, cardLink4)
    )

package sgrv.be

import sgrv.be.auth.{AppConfig, CardConfig, FirestoreConfig}

class AppConfigSuite extends munit.FunSuite:

  test("loads and trims the environment-supplied application configuration"):
    val configuration = AppConfig.fromEnvironment(
      Map(
        "GCP_PROJECT_ID" -> " project-id ",
        "FIRESTORE_DATABASE_ID" -> " database-id ",
        "FIRESTORE_LOCATION" -> " location ",
        "CARD_NAME" -> " Jane Doe ",
        "CARD_DESIGNATION" -> " Consultant ",
        "CARD_TAGLINE" -> " Building things ",
        "CARD_LINK1" -> " /resume ",
        "CARD_LINK2" -> " https://linkedin.example.com/in/jane ",
        "CARD_LINK3" -> " https://blog.example.com ",
        "CARD_LINK4" -> " https://portfolio.example.com "
      )
    )

    assertEquals(
      configuration,
      Right(
        AppConfig(
          FirestoreConfig("project-id", "database-id", "location"),
          CardConfig(
            "Jane Doe",
            "Consultant",
            "Building things",
            "/resume",
            "https://linkedin.example.com/in/jane",
            "https://blog.example.com",
            "https://portfolio.example.com"
          )
        )
      )
    )

  test("requires every configured field, reporting the first missing one"):
    val error = AppConfig
      .fromEnvironment(
        Map(
          "GCP_PROJECT_ID" -> "project-id",
          "FIRESTORE_DATABASE_ID" -> "database-id",
          "FIRESTORE_LOCATION" -> "location"
        )
      )
      .left
      .toOption
      .get

    assertEquals(error.getMessage, "Environment variable CARD_NAME is not set or is empty; see prod.env")

  test("rejects a blank value the same as a missing one"):
    val error = AppConfig.fromEnvironment(Map("GCP_PROJECT_ID" -> "   ")).left.toOption.get

    assertEquals(error.getMessage, "Environment variable GCP_PROJECT_ID is not set or is empty; see prod.env")

package sgrv.be.auth

import com.google.api.gax.core.NoCredentialsProvider
import com.google.api.gax.rpc.NotFoundException
import com.google.cloud.firestore.v1.{FirestoreAdminClient, FirestoreAdminSettings}
import com.google.firestore.admin.v1.{CreateDatabaseRequest, Database, GetDatabaseRequest}
import zio.{System, Task, ZIO, ZLayer}

private[be] trait DatabaseAdmin:
  def ensureDatabase: Task[Unit]

private[be] object DatabaseAdmin:
  def ensureDatabase: ZIO[DatabaseAdmin, Throwable, Unit] =
    ZIO.serviceWithZIO[DatabaseAdmin](_.ensureDatabase)

  val live: ZLayer[AppConfig, Throwable, DatabaseAdmin] =
    ZLayer.scoped:
      for
        config <- ZIO.service[AppConfig]
        emulatorHost <- System.env("FIRESTORE_EMULATOR_HOST").map(_.map(_.trim).filter(_.nonEmpty))
        client <- ZIO.acquireRelease(ZIO.attemptBlocking(adminClient(emulatorHost)))(value =>
          ZIO.attemptBlocking(value.close()).ignore
        )
      yield Live(config.firestore, client)

  // FirestoreOptions (used by the data client elsewhere) auto-detects FIRESTORE_EMULATOR_HOST, but the raw
  // FirestoreAdminClient GAPIC client does not; without this, ensureDatabase silently checks/creates the
  // database against real GCP instead of the emulator, leaving the emulator's copy uninitialized.
  private def adminClient(emulatorHost: Option[String]): FirestoreAdminClient =
    emulatorHost match
      case None       => FirestoreAdminClient.create()
      case Some(host) =>
        val channelProvider = FirestoreAdminSettings
          .defaultGrpcTransportProviderBuilder()
          .setChannelConfigurator(_.usePlaintext())
          .build()
        val settings = FirestoreAdminSettings
          .newBuilder()
          .setCredentialsProvider(NoCredentialsProvider.create())
          .setTransportChannelProvider(channelProvider)
          .setEndpoint(host)
          .build()
        FirestoreAdminClient.create(settings)

  private final case class Live(config: FirestoreConfig, client: FirestoreAdminClient) extends DatabaseAdmin:
    override def ensureDatabase: Task[Unit] =
      val name = s"projects/${config.projectId}/databases/${config.databaseId}"
      ZIO
        .attemptBlocking(client.getDatabase(GetDatabaseRequest.newBuilder().setName(name).build()))
        .unit
        .catchSome:
          case _: NotFoundException =>
            GoogleFuture
              .fromApiFuture:
                client.createDatabaseAsync(
                  CreateDatabaseRequest
                    .newBuilder()
                    .setParent(s"projects/${config.projectId}")
                    .setDatabaseId(config.databaseId)
                    .setDatabase(
                      Database
                        .newBuilder()
                        .setType(Database.DatabaseType.FIRESTORE_NATIVE)
                        .setLocationId(config.location)
                    )
                    .build()
                )
              .unit

package sgrv.be.contact

import com.google.cloud.Timestamp
import com.google.cloud.firestore.{Firestore, FirestoreOptions}
import sgrv.be.auth.{AppConfig, GoogleFuture}
import java.time.Instant
import zio.{Task, ZIO, ZLayer}

private[contact] object MessageSchema:
  val collection = "Messages"

private[be] trait MessageStore:
  def submit(message: String, clientIp: String, receivedAt: Instant): Task[Unit]

private[be] object MessageStore:
  def submit(message: String, clientIp: String, receivedAt: Instant): ZIO[MessageStore, Throwable, Unit] =
    ZIO.serviceWithZIO[MessageStore](_.submit(message, clientIp, receivedAt))

  val live: ZLayer[AppConfig, Throwable, MessageStore] =
    ZLayer.scoped:
      for
        config <- ZIO.service[AppConfig]
        firestore <- ZIO.acquireRelease(
          ZIO.attemptBlocking:
            FirestoreOptions
              .newBuilder()
              .setProjectId(config.firestore.projectId)
              .setDatabaseId(config.firestore.databaseId)
              .build()
              .getService
        )(value => ZIO.attemptBlocking(value.close()).ignore)
      yield Live(firestore)

  private final case class Live(firestore: Firestore) extends MessageStore:
    override def submit(message: String, clientIp: String, receivedAt: Instant): Task[Unit] =
      for
        fields <- ZIO.attempt(documentFields(message, clientIp, receivedAt))
        _ <- GoogleFuture.fromApiFuture(firestore.collection(MessageSchema.collection).add(fields))
      yield ()

  private[contact] def documentFields(
      message: String,
      clientIp: String,
      receivedAt: Instant
  ): java.util.Map[String, AnyRef] =
    val values = new java.util.HashMap[String, AnyRef]
    values.put("message", message)
    values.put("ipAddress", clientIp)
    values.put("receivedAt", Timestamp.ofTimeSecondsAndNanos(receivedAt.getEpochSecond, receivedAt.getNano))
    values

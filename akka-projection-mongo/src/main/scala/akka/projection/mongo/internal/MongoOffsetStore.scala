/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.projection.mongo.internal

import java.time.Clock
import java.time.Instant

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import akka.Done
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.projection.MergeableOffset
import akka.projection.ProjectionId
import akka.projection.internal.OffsetSerialization
import akka.projection.mongo.TransactionCtx
import org.bson.BsonDocumentWrapper
import org.bson.codecs.configuration.CodecRegistries
import org.mongodb.scala._
import org.mongodb.scala.bson.codecs.Macros

/**
 * INTERNAL API
 */
@InternalApi private[akka] class MongoOffsetStore(val db: MongoClient, mongoSettings: MongoSettings, clock: Clock) {
  import OffsetSerialization.MultipleOffsets
  import OffsetSerialization.SingleOffset
  import OffsetSerialization.fromStorageRepresentation
  import OffsetSerialization.toStorageRepresentation

  def this(db: MongoClient, mongoSettings: MongoSettings) =
    this(db, mongoSettings, Clock.systemUTC())

  def readOffset[Offset](projectionId: ProjectionId)(implicit ec: ExecutionContext): Future[Option[Offset]] = {
    val results = offsetTable.find(Document("_id.projectionName" -> projectionId.name)).toFuture().map { maybeRow =>
      maybeRow.map(
        row =>
          SingleOffset(
            ProjectionId(projectionId.name, row._id.projectionKey),
            row.manifest,
            row.offsetStr,
            row.mergeable))
    }

    results.map {
      case Nil => None
      case reps if reps.forall(_.mergeable) =>
        Some(fromStorageRepresentation[MergeableOffset[_, _], Offset](MultipleOffsets(reps)).asInstanceOf[Offset])
      case reps =>
        reps.find(_.id == projectionId) match {
          case Some(rep) => Some(fromStorageRepresentation[Offset, Offset](rep))
          case _         => None
        }
    }
  }

  private def newRow[Offset](rep: SingleOffset, now: Instant): TransactionCtx[_] = { clientSession =>
    val _id = OffsetRowId(rep.id.name, rep.id.key)
    offsetTable
      .replaceOne(
        filter = Document("_id" -> new BsonDocumentWrapper(_id, offsetTable.codecRegistry.get(classOf[OffsetRowId]))),
        replacement = OffsetRow(_id, rep.offsetStr, rep.manifest, rep.mergeable, now),
        clientSession = clientSession,
        options = model.ReplaceOptions().upsert(true))
      .head()
  }

  def saveOffset[Offset](projectionId: ProjectionId, offset: Offset)(
      implicit ec: ExecutionContext): TransactionCtx[_] = {
    val now: Instant = Instant.now(clock)
    toStorageRepresentation(projectionId, offset) match {
      case offset: SingleOffset  => newRow(offset, now)
      case MultipleOffsets(reps) => TransactionCtx.sequence(reps.map(rep => newRow(rep, now)))
    }
  }

  def clearOffset(projectionId: ProjectionId): TransactionCtx[_] = { clientSession =>
    offsetTable
      .deleteOne(
        clientSession,
        Document("_id.projectionName" -> projectionId.name, "_id.projectionKey" -> projectionId.key))
      .toFuture()
  }

  case class OffsetRow(_id: OffsetRowId, offsetStr: String, manifest: String, mergeable: Boolean, lastUpdated: Instant)

  case class OffsetRowId(projectionName: String, projectionKey: String)

  val offsetTable: MongoCollection[OffsetRow] =
    db.getDatabase(mongoSettings.schema)
      .getCollection[OffsetRow](mongoSettings.table)
      .withCodecRegistry(
        CodecRegistries.fromRegistries(
          CodecRegistries.fromProviders(Macros.createCodecProvider[OffsetRowId], Macros.createCodecProvider[OffsetRow]),
          MongoClient.DEFAULT_CODEC_REGISTRY))

  def createIfNotExists: Future[Done] = {
    import org.mongodb.scala.model.Indexes._
    offsetTable
      .createIndex(ascending("_id.projectionName", "_id.projectionKey"))
      .toFuture()
      .map(_ => Done)(ExecutionContexts.parasitic)
  }
}
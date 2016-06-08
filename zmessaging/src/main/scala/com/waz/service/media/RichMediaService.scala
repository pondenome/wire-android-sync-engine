/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.service.media

import android.net.Uri
import com.waz.ZLog._
import com.waz.api.impl.ErrorResponse
import com.waz.api.{MediaProvider, Message}
import com.waz.model._
import com.waz.model.messages.media.MediaAssetData
import com.waz.service.assets.AssetService
import com.waz.service.messages.MessagesContentUpdater
import com.waz.sync.SyncServiceHandle
import com.waz.threading.Threading
import com.waz.utils.events.EventContext
import com.waz.znet.ZNetClient.ErrorOr

import scala.concurrent.Future

class RichMediaService(assets: AssetService, messages: MessagesContentUpdater, sync: SyncServiceHandle, youTube: YouTubeMediaService, soundCloud: SoundCloudMediaService, spotify: SpotifyMediaService, googleMaps: GoogleMapsMediaService) {
  import com.waz.api.Message.Part.Type._
  private implicit val logTag: LogTag = logTagFor[RichMediaService]
  import Threading.Implicits.Background
  private implicit val ec = EventContext.Global

  import messages._

  val richMediaParser = new RichMediaContentParser

  private def isSyncableMsg(msg: MessageData) = msg.msgType == Message.Type.RICH_MEDIA && msg.content.exists(isSyncable)

  private def isSyncable(c: MessageContent) = c.tpe match {
    case YOUTUBE | GOOGLE_MAPS | SOUNDCLOUD | SPOTIFY => true
    case _ => false
  }

  private def syncableContentChanged(prev: MessageData, updated: MessageData) = {
    prev.content.size != updated.content.size || prev.content.zip(updated.content).exists { case (c1, c2) => c1.content != c2.content && isSyncable(c2) }
  }

  messagesStorage.messageAdded { msgs =>
    scheduleSyncFor(msgs filter isSyncableMsg)
  }

  messagesStorage.messageUpdated { _ foreach {
    case (prev, updated) =>
      if (isSyncableMsg(updated) && syncableContentChanged(prev, updated)) {
        verbose(s"Updated rich media message: $updated, scheduling sync")
        sync.syncRichMedia(updated.id)
      }
  } }

  private def scheduleSyncFor(ms: Seq[MessageData]) = if (ms.nonEmpty) {
    verbose(s"Scheduling sync for added rich media messages: $ms")

    messagesStorage.updateAll2(ms.map(_.id), m => m.copy(content = m.content map (c => c.copy(syncNeeded = isSyncable(c))))) map { _ =>
      Future.traverse(ms) { m => sync.syncRichMedia(m.id) }
    }
  }

  def updateRichMedia(id: MessageId): Future[Seq[ErrorResponse]] = {
    messagesStorage.getMessage(id) flatMap {
      case Some(data) =>
        for {
          results <- Future.traverse(data.content) { updateRichMediaContent(data, _) }
          newContent = data.content.zip(results) map {
            case (prev, Right(updated)) => updated.copy(syncNeeded = false)
            case (prev, Left(_)) => prev.copy(syncNeeded = false)
          }
          _ <- updateMessage(id)(_.copy(content = newContent))
        } yield {
          results.collect { case Left(error) => error }
        }
      case None =>
        error(s"No message data found with id: $id")
        Future.successful(Seq(ErrorResponse.InternalError))
    }
  }

  private def updateRichMediaContent(msg: MessageData, content: MessageContent): Future[Either[ErrorResponse, MessageContent]] = content.tpe match {
    case YOUTUBE     => youTube.updateMedia(msg, content)
    case GOOGLE_MAPS => googleMaps.updateMedia(msg, content)
    case SOUNDCLOUD  => soundCloud.updateMedia(msg, content)
    case SPOTIFY     => spotify.updateMedia(msg, content)
    case _           => Future.successful(Right(content))
  }

  def prepareStreaming(media: MediaAssetData): ErrorOr[Vector[Uri]] = media.provider match {
    case MediaProvider.YOUTUBE    => youTube.prepareStreaming(media)
    case MediaProvider.SOUNDCLOUD => soundCloud.prepareStreaming(media)
    case MediaProvider.SPOTIFY    => spotify.prepareStreaming(media)
    case other =>
      warn(s"Unable to prepare streaming for rich media from $other.")
      Future.successful(Right(Vector.empty))
  }
}
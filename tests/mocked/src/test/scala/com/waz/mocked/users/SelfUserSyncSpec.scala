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
package com.waz.mocked.users

import com.waz.api.{ImageAssetFactory, MockedClientApiSpec}
import com.waz.cache.LocalData
import com.waz.mocked.MockBackend
import com.waz.model._
import com.waz.testutils.Implicits._
import com.waz.testutils.Matchers._
import com.waz.threading.{CancellableFuture, Threading}
import com.waz.utils.IoUtils.toByteArray
import com.waz.utils.returning
import com.waz.znet.ZNetClient._
import org.scalatest.{BeforeAndAfterAll, FeatureSpec, Matchers, OptionValues}

import scala.concurrent.duration._

class SelfUserSyncSpec extends FeatureSpec with Matchers with OptionValues with BeforeAndAfterAll with MockBackend with MockedClientApiSpec { test =>
  import DefaultPushBehaviour.Implicit

  lazy val self = api.getSelf

  scenario("update self user phone and email address") {
    val phone = "+1234567890"

    soon {
      self.isLoggedIn shouldEqual true
      self.getEmail shouldEqual "email@test.com"
      self.isEmailVerified shouldBe true
      self.getPhone shouldBe empty
      self.isPhoneVerified shouldBe false
      self.zuser.value.phone shouldEqual None
      self.zuser.value.email should be (defined)
      self.isEmailVerified shouldEqual true
    }

    val selfId = self.getUser.getId

    addNotification(UserUpdateEvent(Uid(), UserInfo(UserId(selfId), name = Some("name"), email = None, phone = Some(PhoneNumber(phone)))))

    soon {
      self.getName shouldEqual "name"
      self.getEmail shouldEqual "email@test.com"
      self.getPhone shouldEqual phone
      self.isEmailVerified shouldBe true
      self.isPhoneVerified shouldBe true
      self.zuser.value.email shouldEqual Some(EmailAddress("email@test.com"))
      self.zuser.value.phone shouldEqual Some(PhoneNumber(phone))
    }

    addNotification(UserUpdateEvent(Uid(), UserInfo(UserId(selfId), email = Some(EmailAddress("email1@test.com")), phone = None)))

    soon {
      self.getName shouldEqual "name"
      self.getEmail shouldEqual "email1@test.com"
      self.getPhone shouldEqual phone
      self.isEmailVerified shouldBe true
      self.isPhoneVerified shouldBe true
      self.zuser.value.email shouldEqual Some(EmailAddress("email1@test.com"))
      self.zuser.value.phone shouldEqual Some(PhoneNumber(phone))
    }
  }

  scenario("update self user picture") {
    self.setPicture(ImageAssetFactory.getImageAsset(toByteArray(getClass.getResourceAsStream("/images/penguin.png"))))

    val selfPicture = soon(returning(self.getPicture)(_ should not be empty))
    def selfPictureData = selfPicture.data.versions

    soon {
      sentUserInfo.value.picture.value.versions(0).remoteId shouldBe Some(RAssetDataId("smallProfile-picture"))
      sentUserInfo.value.picture.value.versions(1).remoteId shouldBe Some(RAssetDataId("medium-picture"))
      selfPictureData(0).remoteId shouldBe Some(RAssetDataId("smallProfile-picture"))
      selfPictureData(1).remoteId shouldBe Some(RAssetDataId("medium-picture"))
    }
  }

  override def postImageAssetData(image: ImageData, assetId: AssetId, convId: RConvId, data: LocalData, nativePush: Boolean): ErrorOrResponse[ImageData] = {
    import Threading.Implicits.Background
    def response(delay: FiniteDuration, id: String) = CancellableFuture.delayed(delay)(Right(image.copy(remoteId = Some(RAssetDataId(id)), data64 = None, sent = true)))

    if (image.tag == "medium") response(2.seconds, "medium-picture")
    else response(100.millis, "smallProfile-picture")
  }

  override def updateSelf(info: UserInfo): ErrorOrResponse[Unit] = {
    sentUserInfo = Some(info)
    super.updateSelf(info)
  }

  @volatile private var sentUserInfo = Option.empty[UserInfo]
}
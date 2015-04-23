/*
 * Copyright 1998-2015 Linux.org.ru
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ru.org.linux.user

import java.io.{File, FileNotFoundException, IOException}
import java.sql.Timestamp
import javax.annotation.Nullable

import com.typesafe.scalalogging.StrictLogging
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import ru.org.linux.spring.SiteConfig
import ru.org.linux.util.image.{ImageInfo, ImageParam, ImageUtil}
import ru.org.linux.util.{BadImageException, StringUtil}

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

@Service
object UserService {
  val MaxFileSize = 35000
  val MinImageSize = 50
  val MaxImageSize = 150

  val DisabledUserpic = new Userpic("/img/p.gif", 1, 1)
}

@Service
class UserService @Autowired() (siteConfig: SiteConfig, userDao: UserDao) extends StrictLogging {
  @throws(classOf[UserErrorException])
  @throws(classOf[IOException])
  @throws(classOf[BadImageException])
  def checkUserPic(file: File): ImageParam = {
    if (!file.isFile) {
      throw new UserErrorException("Сбой загрузки изображения: не файл")
    }

    val param = ImageUtil.imageCheck(file)

    if (param.isAnimated) {
      throw new UserErrorException("Сбой загрузки изображения: анимация не допустима")
    }

    if (param.getSize > UserService.MaxFileSize) {
      throw new UserErrorException("Сбой загрузки изображения: слишком большой файл")
    }

    if (param.getHeight < UserService.MinImageSize || param.getHeight > UserService.MaxImageSize) {
      throw new UserErrorException("Сбой загрузки изображения: недопустимые размеры фотографии")
    }

    if (param.getWidth < UserService.MinImageSize || param.getWidth > UserService.MaxImageSize) {
      throw new UserErrorException("Сбой загрузки изображения: недопустимые размеры фотографии")
    }

    param
  }

  private def getStars(user: User): java.util.List[java.lang.Boolean] =
    (Seq.fill(user.getGreenStars)(java.lang.Boolean.TRUE)
      ++ Seq.fill(user.getGreyStars)(java.lang.Boolean.FALSE)).asJava


  def ref(user: User, @Nullable requestUser: User): ApiUserRef = {
    if (requestUser != null && requestUser.isModerator && !user.isAnonymous) {
      new ApiUserRef(user.getNick, user.isBlocked, user.isAnonymous, getStars(user), user.getScore, user.getMaxScore)
    } else {
      new ApiUserRef(user.getNick, user.isBlocked, user.isAnonymous, getStars(user), null, null)
    }
  }

  def getUserpic(user: User, secure: Boolean, avatarStyle: String, misteryMan: Boolean): Userpic = {
    val avatarMode = if (misteryMan && ("empty" == avatarStyle)) {
       "mm"
    } else {
      avatarStyle
    }

    val userpic = if (user.isAnonymous && misteryMan) {
      Some(new Userpic(User.getGravatar("anonymous@linux.org.ru", avatarMode, 150, secure), 150, 150))
    } else if (user.getPhoto != null && !user.getPhoto.isEmpty) {
      Try {
        val info = new ImageInfo(siteConfig.getHTMLPathPrefix + "/photos/" + user.getPhoto)
        new Userpic("/photos/" + user.getPhoto, info.getWidth, info.getHeight)
      } match {
        case Failure(e: FileNotFoundException) ⇒
          logger.warn(s"Userpic not found for ${user.getNick}: ${e.getMessage}")
          None
        case Failure(e) ⇒
          logger.warn(s"Bad userpic for ${user.getNick}", e)
          None
        case Success(u) ⇒
          Some(u)
      }
    } else {
      None
    }

    userpic.getOrElse {
      if (user.hasGravatar && !("" == user.getPhoto)) {
        new Userpic(user.getGravatar(avatarMode, 150, secure), 150, 150)
      } else {
        UserService.DisabledUserpic
      }
    }
  }

  def getResetCode(nick: String, email: String, tm: Timestamp): String = {
    val base = siteConfig.getSecret
    StringUtil.md5hash(base + ':' + nick + ':' + email + ':' + tm.getTime.toString + ":reset")
  }

  def getUsersCached(ids: java.lang.Iterable[Integer]): java.util.List[User] =
    ids.asScala.map(x ⇒ userDao.getUserCached(x)).toSeq.asJava

  def getNewUsers = getUsersCached(userDao.getNewUserIds)

  def getModerators = getUsersCached(userDao.getModeratorIds)

  def getCorrectors = getUsersCached(userDao.getCorrectorIds)

  def getUserCached(nick: String) = userDao.getUserCached(userDao.findUserId(nick))

  def getUserCached(id: Int) = userDao.getUserCached(id)
}
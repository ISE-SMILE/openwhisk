/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.containerpool

import java.time.Instant

import akka.actor.ActorSystem
import akka.event.Logging.InfoLevel
import akka.stream.scaladsl.Source
import akka.util.ByteString
import pureconfig._
import spray.json.DefaultJsonProtocol._
import spray.json.JsObject
import spray.json._
import org.apache.openwhisk.common.{Logging, LoggingMarkers, TransactionId}
import org.apache.openwhisk.core.ConfigKeys
import org.apache.openwhisk.core.connector.ActivationMessage
import org.apache.openwhisk.core.containerpool.logging.LogCollectingException
import org.apache.openwhisk.core.database.UserContext
import org.apache.openwhisk.core.entity.ActivationResponse.{ContainerConnectionError, ContainerResponse}
import org.apache.openwhisk.core.entity.{
  ActivationEntityLimit,
  ActivationId,
  ActivationLogs,
  ActivationResponse,
  ByteSize,
  ExecutableWhiskAction,
  Identity,
  Parameters,
  WhiskActivation
}

/** important, otherwise the config cannot be read correctly */
import org.apache.openwhisk.core.entity.size._

import org.apache.openwhisk.http.Messages

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{Duration, FiniteDuration, _}
import scala.util.{Failure, Success}

/**
 * An OpenWhisk biased container abstraction. This is **not only** an abstraction
 * for different container providers, but the implementation also needs to include
 * OpenWhisk specific behavior, especially for initialize and run.
 */
case class ContainerId(asString: String) {
  require(asString.nonEmpty, "ContainerId must not be empty")
}

case class ContainerAddress(host: String, port: Int = 8080) {
  require(host.nonEmpty, "ContainerIp must not be empty")
}

case class ActivationStoreOptions(
  action: ExecutableWhiskAction,
  msg: ActivationMessage,
  storeActivation: (TransactionId, WhiskActivation, UserContext) => Future[Any],
  collectLogs: (TransactionId, Identity, WhiskActivation, Container, ExecutableWhiskAction) => Future[ActivationLogs])

object Container {

  /**
   * The action proxies insert this line in the logs at the end of each activation for stdout/stderr.
   *
   * Note: Blackbox containers might not add this sentinel, as we cannot be sure the action developer actually does this.
   */
  val ACTIVATION_LOG_SENTINEL = "XXX_THE_END_OF_A_WHISK_ACTIVATION_XXX"

  protected[containerpool] val config: ContainerPoolConfig =
    loadConfigOrThrow[ContainerPoolConfig](ConfigKeys.containerPool)
}

/**
 * Abstraction for Container operations.
 * Container manipulation (specifically suspend/resume/destroy) is NOT thread-safe and MUST be synchronized by caller.
 * Container access (specifically run) is thread-safe (e.g. for concurrent activation processing).
 */
trait Container {

  implicit protected val as: ActorSystem
  protected val id: ContainerId
  protected val addr: ContainerAddress
  protected implicit val logging: Logging
  protected implicit val ec: ExecutionContext

  /** HTTP connection to the container, will be lazily established by callContainer */
  protected var httpConnection: Option[ContainerClient] = None

  /** maxConcurrent+timeout are cached during first init, so that resuming connections can reference */
  protected var containerHttpMaxConcurrent: Int = 1
  protected var containerHttpTimeout: FiniteDuration = 60.seconds

  protected var onStartHandlerOK = false
  protected var onPauseHandlerOK = false
  protected var onFinishHandlerOK = false

  protected var activationStoreOptions: ActivationStoreOptions = _

  def containerId: ContainerId = id

  /** Stops the container from consuming CPU cycles. NOT thread-safe - caller must synchronize. */
  def suspend()(implicit transid: TransactionId): Future[Unit] = {
    val waitForHandler = if (onPauseHandlerOK) {
      callLifecycleHook("onpause")
    } else {
      Future.successful({})
    }

    waitForHandler.flatMap { _ =>
      //close connection first, then close connection pool
      //(testing pool recreation vs connection closing, time was similar - so using the simpler recreation approach)
      val toClose = httpConnection
      httpConnection = None
      closeConnections(toClose)
    }

  }

  /** Dual of halt. NOT thread-safe - caller must synchronize. */
  def resume()(implicit transid: TransactionId): Future[Unit] = {
    httpConnection = Some(openConnections(containerHttpTimeout, containerHttpMaxConcurrent))
    Future.successful({})
  }

  /** Obtains logs up to a given threshold from the container. Optionally waits for a sentinel to appear. */
  def logs(limit: ByteSize, waitForSentinel: Boolean)(implicit transid: TransactionId): Source[ByteString, Any]

  /** Completely destroys this instance of the container. */
  def destroy()(implicit transid: TransactionId): Future[Unit] = {
    val waitForHandler = if (onFinishHandlerOK) {
      callLifecycleHook("onfinish")
    } else {
      Future.successful({})
    }

    waitForHandler.flatMap { _ =>
      closeConnections(httpConnection)
    }
  }

  def callLifecycleHook(handlerType: String, initDuration: Long = 0)(implicit transid: TransactionId): Future[Unit] = {

    val start = Instant.now().minusMillis(initDuration)

    //TODO JsObject() mit richtigen Daten füllen
    callContainer("/" + handlerType, JsObject(), containerHttpTimeout, containerHttpMaxConcurrent)
      .flatMap { result =>
        val end = Instant.now()

        val activation = WhiskActivation(
          activationStoreOptions.msg.user.namespace.name.toPath,
          activationStoreOptions.action.name,
          activationStoreOptions.msg.user.subject,
          ActivationId.generate(),
          start,
          end,
          None,
          ActivationResponse.success(),
          ActivationLogs(),
          activationStoreOptions.action.version,
          publish = false, {
            Parameters(WhiskActivation.limitsAnnotation, activationStoreOptions.action.limits.toJson) ++
              Parameters(
                WhiskActivation.pathAnnotation,
                JsString(activationStoreOptions.action.fullyQualifiedName(false).asString)) ++
              Parameters(WhiskActivation.kindAnnotation, JsString(activationStoreOptions.action.exec.kind)) ++
              Parameters(WhiskActivation.timeoutAnnotation, JsBoolean(result.interval.duration >= containerHttpTimeout)) ++
              Parameters("lifecycle_hook_type", handlerType)
          },
          Some(Interval(start, end).duration.toMillis))

        /** Indicates reading logs for an activation failed (terminally, truncated) */
        case class ActivationLogReadingError(activation: WhiskActivation) extends ActivationError

        /** Indicates that something went wrong with an activation and the container should be removed */
        trait ActivationError extends Exception {
          val activation: WhiskActivation
        }

        val activationWithLogs =
          // Skips log collection entirely, if the limit is set to 0
          if (activationStoreOptions.action.limits.logs.asMegaBytes == 0.MB) {
            Future.successful(Right(activation))
          } else {
            val start = transid.started(this, LoggingMarkers.INVOKER_COLLECT_LOGS, logLevel = InfoLevel)
            activationStoreOptions
              .collectLogs(transid, activationStoreOptions.msg.user, activation, this, activationStoreOptions.action)
              .andThen {
                case Success(_) => transid.finished(this, start)
                case Failure(t) => transid.failed(this, start, s"reading logs failed: $t")
              }
              .map(logs => Right(activation.withLogs(logs)))
              .recover {
                case LogCollectingException(logs) =>
                  Left(ActivationLogReadingError(activation.withLogs(logs)))
                case _ =>
                  Left(ActivationLogReadingError(activation.withLogs(ActivationLogs(Vector(Messages.logFailure)))))
              }

          }

        activationWithLogs
          .map(_.fold(_.activation, identity))
          .foreach { activation =>
            // Storing the record. Entirely asynchronous and not waited upon.
            activationStoreOptions.storeActivation(transid, activation, UserContext(activationStoreOptions.msg.user))
          }

        activationWithLogs.flatMap { _ =>
          Future.successful({})
        }
      }
  }

  /** Initializes code in the container. */
  def initialize(initializer: JsObject,
                 timeout: FiniteDuration,
                 maxConcurrent: Int,
                 activationStoreOptions: ActivationStoreOptions)(implicit transid: TransactionId): Future[Interval] = {

    this.activationStoreOptions = activationStoreOptions

    val start = transid.started(
      this,
      LoggingMarkers.INVOKER_ACTIVATION_INIT,
      s"sending initialization to $id $addr",
      logLevel = InfoLevel)
    containerHttpMaxConcurrent = maxConcurrent
    containerHttpTimeout = timeout
    val body = JsObject("value" -> initializer)
    callContainer("/init", body, timeout, maxConcurrent, retry = true)
      .andThen { // never fails
        case Success(r: RunResult) =>
          transid.finished(
            this,
            start.copy(start = r.interval.start),
            s"initialization result: ${r.toBriefString}",
            endTime = r.interval.end,
            logLevel = InfoLevel)
        case Failure(t) =>
          transid.failed(this, start, s"initializiation failed with $t")
      }
      .flatMap { result =>
        result.response match {
          case Left(_) =>
          case Right(value) =>
            val resp = value.entity.parseJson.asJsObject
            onStartHandlerOK = resp.fields.getOrElse("onStartHandlerOK", JsFalse) == JsTrue
            onPauseHandlerOK = resp.fields.getOrElse("onPauseHandlerOK", JsFalse) == JsTrue
            onFinishHandlerOK = resp.fields.getOrElse("onFinishHandlerOK", JsFalse) == JsTrue
        }
        Future.successful(result)
      }
      .flatMap { result =>
        if (onStartHandlerOK) {
          callLifecycleHook("onstart", result.interval.duration.toMillis).flatMap { _ =>
            Future.successful(result)
          }
        } else {
          Future.successful(result)
        }
      }
      .flatMap { result =>
        if (result.ok) {
          Future.successful(result.interval)
        } else if (result.interval.duration >= timeout) {
          Future.failed(
            InitializationError(
              result.interval,
              ActivationResponse.developerError(Messages.timedoutActivation(timeout, true))))
        } else {
          Future.failed(
            InitializationError(
              result.interval,
              ActivationResponse.processInitResponseContent(result.response, logging)))
        }
      }
  }

  /** Runs code in the container. Thread-safe - caller may invoke concurrently for concurrent activation processing. */
  def run(parameters: JsObject, environment: JsObject, timeout: FiniteDuration, maxConcurrent: Int)(
    implicit transid: TransactionId): Future[(Interval, ActivationResponse)] = {
    val actionName = environment.fields.get("action_name").map(_.convertTo[String]).getOrElse("")
    val start =
      transid.started(
        this,
        LoggingMarkers.INVOKER_ACTIVATION_RUN,
        s"sending arguments to $actionName at $id $addr",
        logLevel = InfoLevel)

    val parameterWrapper = JsObject("value" -> parameters)
    val body = JsObject(parameterWrapper.fields ++ environment.fields)
    callContainer("/run", body, timeout, maxConcurrent, retry = false)
      .andThen { // never fails
        case Success(r: RunResult) =>
          transid.finished(
            this,
            start.copy(start = r.interval.start),
            s"running result: ${r.toBriefString}",
            endTime = r.interval.end,
            logLevel = InfoLevel)
        case Failure(t) =>
          transid.failed(this, start, s"run failed with $t")
      }
      .map { result =>
        val response = if (result.interval.duration >= timeout) {
          ActivationResponse.developerError(Messages.timedoutActivation(timeout, false))
        } else {
          ActivationResponse.processRunResponseContent(result.response, logging)
        }

        (result.interval, response)
      }
  }

  /**
   * Makes an HTTP request to the container.
   *
   * Note that `http.post` will not throw an exception, hence the generated Future cannot fail.
   *
   * @param path    relative path to use in the http request
   * @param body    body to send
   * @param timeout timeout of the request
   * @param retry   whether or not to retry the request
   */
  protected def callContainer(path: String,
                              body: JsObject,
                              timeout: FiniteDuration,
                              maxConcurrent: Int,
                              retry: Boolean = false)(implicit transid: TransactionId): Future[RunResult] = {
    val started = Instant.now()
    val http = httpConnection.getOrElse {
      val conn = openConnections(timeout, maxConcurrent)
      httpConnection = Some(conn)
      conn
    }
    http
      .post(path, body, retry)
      .map { response =>
        val finished = Instant.now()
        RunResult(Interval(started, finished), response)
      }
  }

  private def openConnections(timeout: FiniteDuration, maxConcurrent: Int) = {
    if (Container.config.akkaClient) {
      new AkkaContainerClient(addr.host, addr.port, timeout, ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT, 1024)
    } else {
      new ApacheBlockingContainerClient(
        s"${addr.host}:${addr.port}",
        timeout,
        ActivationEntityLimit.MAX_ACTIVATION_ENTITY_LIMIT,
        maxConcurrent)
    }
  }

  private def closeConnections(toClose: Option[ContainerClient]): Future[Unit] = {
    toClose.map(_.close()).getOrElse(Future.successful(()))
  }

  /** This is so that we can easily log the container id during ContainerPool.logContainerStart().
   * Null check is here since some tests use stub[Container] so id is null during those tests. */
  override def toString() = if (id == null) "no-container-id" else id.toString
}

/** Indicates a general error with the container */
sealed abstract class ContainerError(msg: String) extends Exception(msg)

/** Indicates an error while starting a container */
sealed abstract class ContainerStartupError(msg: String) extends ContainerError(msg)

/** Indicates any error while starting a container either of a managed runtime or a non-application-specific blackbox container */
case class WhiskContainerStartupError(msg: String) extends ContainerStartupError(msg)

/** Indicates an application-specific error while starting a blackbox container */
case class BlackboxStartupError(msg: String) extends ContainerStartupError(msg)

/** Indicates an error while initializing a container */
case class InitializationError(interval: Interval, response: ActivationResponse) extends Exception(response.toString)

case class Interval(start: Instant, end: Instant) {
  def duration = Duration.create(end.toEpochMilli() - start.toEpochMilli(), MILLISECONDS)
}

case class RunResult(interval: Interval, response: Either[ContainerConnectionError, ContainerResponse]) {
  def ok = response.right.exists(_.ok)

  def toBriefString = response.fold(_.toString, _.toString)
}

object Interval {

  /** An interval starting now with zero duration. */
  def zero = {
    val now = Instant.now
    Interval(now, now)
  }
}

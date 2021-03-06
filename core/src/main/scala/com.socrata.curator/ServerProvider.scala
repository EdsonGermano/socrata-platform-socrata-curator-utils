package com.socrata.curator

import com.socrata.http.client._
import com.socrata.http.client.exceptions._
import java.io.Closeable
import org.apache.curator.x.discovery.ServiceProvider
import org.apache.http.client.CircularRedirectException
import scala.annotation.tailrec

import ServerProvider._

// scalastyle:off cyclomatic.complexity null
/**
 * Classes and objects related to ServerProvider class
 */
object ServerProvider {
  sealed abstract class RetryWhen
  case object RetryOnConnectFailure extends RetryWhen
  case object RetryOnAllExceptionsDuringInitialRequest extends RetryWhen
  case class RetryOn(decision: Exception => RetryDecision) extends RetryWhen

  sealed abstract class RetryDecision
  case object DoNotRetry extends RetryDecision
  case class DoRetry(markBad: Boolean) extends RetryDecision

  val standardRetryOn: RetryWhen = RetryOn(standardRetryOnDecision)

  @tailrec
  private def ultimateCause(t: Throwable): Throwable =
    if (t.getCause != null) ultimateCause(t.getCause) else t

  private def standardRetryOnDecision(e: Exception): RetryDecision =
    ultimateCause(e) match {
      case _: CircularRedirectException =>
        // This leaks the fact that an Apache HttpClient underlies
        // HttpClientHttpClient.  The redirect loop should be exposed
        // in an underlying-client-independent manner.  But for now,
        // this'll do.
        DoNotRetry
      case _ =>
        DoRetry(markBad = false)
    }

  trait Service {
    def baseRequest: RequestBuilder
    def markBad(): Unit
  }

  sealed abstract class RetryResult[+T]
  case class Retry(exceptionIfOutOfRetries: Exception, markBad: Boolean = false) extends RetryResult[Nothing]
  case class Complete[T](result: T) extends RetryResult[T]
}

/**
 * Makes requests to an HTTP service with configurable retry
 * and ability to mark failed nodes as bad.
 * @param finder Method that returns a service instance
 * @param http   HTTP client object
 */
class ServerProvider(val finder: () => Option[ServerProvider.Service], val http: HttpClient) {
  private val log = org.slf4j.LoggerFactory.getLogger(getClass)

  private sealed abstract class FailedCause
  private case object FailedDueToConnection extends FailedCause
  private case object FailedDueToOther extends FailedCause

  private abstract class RequestResult
  private case class Failed(e: Exception, cause: FailedCause) extends RequestResult
  private case object NoServices extends RequestResult
  private case class RequestMade(host: ServerProvider.Service, response: Response with Closeable) extends RequestResult

  private def makeRequest(completer: RequestBuilder => SimpleHttpRequest,
                          retryWhen: RetryWhen): RequestResult = {
    finder() match {
      case Some(hp) =>
        def maybeAbort(e: Exception, causeClassifier: FailedCause, markBad: Boolean = true) = {
          if (markBad) hp.markBad()
          Failed(e, causeClassifier)
        }
        val req = completer(hp.baseRequest)
        try {
          RequestMade(hp, http.executeUnmanaged(req))
        } catch {
          case e: ConnectTimeout =>
            maybeAbort(e, FailedDueToConnection)
          case e: ConnectFailed =>
            maybeAbort(e, FailedDueToConnection)
          case e: Exception =>
            retryWhen match {
              case RetryOnConnectFailure =>
                throw e
              case RetryOnAllExceptionsDuringInitialRequest =>
                maybeAbort(e, FailedDueToOther)
              case RetryOn(decision) =>
                decision(e) match {
                  case DoNotRetry =>
                    throw e
                  case DoRetry(markBad) =>
                    maybeAbort(e, FailedDueToOther, markBad)
                }
            }
        }
      case None =>
        log.warn("No servers found")
        NoServices
    }
  }

  /**
   * Makes an request to the service based on the specified retry rules
   * @param retryCount  Number of times to retry on failure
   * @param completer   Method to generate the HTTP request
   * @param retryWhen   Defines the scenarios in which retry should happen
   * @param onNoServers Handler for the scenario where no service instance was found
   * @return            HTTP response
   */
  def withRetriesUnmanaged(retryCount: Int,
                           completer: RequestBuilder => SimpleHttpRequest,
                           retryWhen: RetryWhen)
                          (onNoServers: => Nothing): Response with Closeable = {
    @tailrec
    def loop(retriesLeft: Int): Response with Closeable = {
      makeRequest(completer, retryWhen) match {
        case RequestMade(_, resp) =>
          resp
        case Failed(_, FailedDueToConnection) =>
          if (retriesLeft <= 0) {
            onNoServers
          } else {
            loop(retriesLeft - 1)
          }
        case Failed(ex, FailedDueToOther) =>
          if (retriesLeft <= 0) {
            throw ex
          } else {
            loop(retriesLeft - 1)
          }
        case NoServices =>
          onNoServers
      }
    }
    loop(retryCount)
  }

  /**
   * Makes a request to the service based on the specified retry rules and
   * converts the response into something that is useful to the caller.
   *
   * @tparam T          A type that is useful to the caller in understanding the response
   * @param retryCount  Number of times to retry on failure
   * @param completer   Method to generate the HTTP request
   * @param retryWhen   Defines the scenarios in which retry should happen
   * @param f           Method to convert the HTTP response to something useful to the caller
   * @return            The HTTP response converted to something that is useful to the caller
   */
  def withRetries[T](retryCount: Int,
                     completer: RequestBuilder => SimpleHttpRequest,
                     retryWhen: RetryWhen)
                    (f: Option[Response] => RetryResult[T]): T = {
    @tailrec
    def loop(retriesLeft: Int): T = {
      makeRequest(completer, retryWhen) match {
        case RequestMade(service, response) =>
          val result =
            try { f(Some(response)) } finally { response.close() }
          result match {
            case Complete(r) => r
            case Retry(ex, markBad) =>
              if(markBad) service.markBad()
              if(retriesLeft <= 0) throw ex
              loop(retriesLeft - 1)
          }
        case Failed(ex, FailedDueToOther) =>
          if (retriesLeft <= 0) {
            throw ex
          } else {
            loop(retriesLeft - 1)
          }
        case Failed(_, FailedDueToConnection) if retriesLeft > 0 =>
          loop(retriesLeft - 1)
        case Failed(_, FailedDueToConnection) | NoServices =>
          f(None) match {
            case Complete(answer) =>
              answer
            case Retry(ex, _) =>
              if(retriesLeft <= 0) throw ex
              loop(retriesLeft - 1)
          }
      }
    }
    loop(retryCount)
  }
}

/**
 * Provisions a curator service.
 */
object CuratorServerProvider {
  def apply[T](http: HttpClient,
               provider: ServiceProvider[T],
               requestBuilder: RequestBuilder => RequestBuilder): ServerProvider = {
    val find = { () =>
      Option(provider.getInstance()).map { i =>
        new ServerProvider.Service {
          override val baseRequest: RequestBuilder =
          // TODO: Provide ability to set liveness check info
          // If you wanted to set livenessCheckInfo from the instance's AuxiliaryData, you'd do it here.
          // I'm not, because core doesn't provide an instance payload.
            requestBuilder(RequestBuilder(i.getAddress).port(i.getPort))

          override def markBad(): Unit =
            provider.noteError(i)
        }
      }
    }
    new ServerProvider(find, http)
  }
}

/**
 * Provisions a service based on a static host and port number
 */
object SingleServerProvider {
  def apply(http: HttpClient,
            host: String,
            port: Int,
            requestBuilder: RequestBuilder => RequestBuilder): ServerProvider = {
    val find = new ServerProvider.Service {
      override val baseRequest = requestBuilder(RequestBuilder(host).port(port))
      override def markBad(): Unit = {}
    }
    new ServerProvider(() => Some(find), http)
  }
}

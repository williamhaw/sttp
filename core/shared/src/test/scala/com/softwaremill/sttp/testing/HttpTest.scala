package com.softwaremill.sttp.testing

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

import com.softwaremill.sttp._
import org.scalatest.{AsyncFreeSpec, BeforeAndAfterAll, Matchers, OptionValues}

import scala.concurrent.duration._
import scala.concurrent.Future
import scala.language.higherKinds

import org.scalatest.Assertion

trait HttpTest[R[_]]
    extends AsyncFreeSpec
    with Matchers
    with ToFutureWrapper
    with OptionValues
    with BeforeAndAfterAll
    with HttpTestExtensions[R] {

  protected def endpoint: String

  protected val binaryFileMD5Hash = "565370873a38d91f34a3091082e63933"
  protected val textFileMD5Hash = "b048a88ece8e4ec5eb386b8fc5006d13"

  implicit val backend: SttpBackend[R, Nothing]
  implicit val convertToFuture: ConvertToFuture[R]

  protected def postEcho: Request[String, Nothing] = sttp.post(uri"$endpoint/echo")
  protected val testBody = "this is the body"
  protected val testBodyBytes: Array[Byte] = testBody.getBytes("UTF-8")
  protected val expectedPostEchoResponse = "POST /echo this is the body"

  protected val sttpIgnore: ResponseAs[Unit, Nothing] = com.softwaremill.sttp.ignore

  protected def supportsRequestTimeout = true

  "parse response" - {
    "as string" in {
      postEcho.body(testBody).send().toFuture().map { response =>
        response.unsafeBody should be(expectedPostEchoResponse)
      }
    }

    "as string with mapping using map" in {
      postEcho
        .body(testBody)
        .response(asString.map(_.length))
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should be(expectedPostEchoResponse.length)
        }
    }

    "as string with mapping using mapResponse" in {
      postEcho
        .body(testBody)
        .mapResponse(_.length)
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should be(expectedPostEchoResponse.length)
        }
    }

    "as string with mapping using mapWithHeaders" in {
      postEcho
        .body(testBody)
        .response(asString.mapWithMetadata { (b, h) =>
          b + " " + h.contentType.getOrElse("")
        })
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should include(expectedPostEchoResponse)
          response.unsafeBody should include("text/plain")
        }
    }

    "as a byte array" in {
      postEcho.body(testBody).response(asByteArray).send().toFuture().map { response =>
        val fc = new String(response.unsafeBody, "UTF-8")
        fc should be(expectedPostEchoResponse)
      }
    }

    "as parameters" in {
      val params = List("a" -> "b", "c" -> "d", "e=" -> "&f")
      sttp
        .post(uri"$endpoint/echo/form_params/as_params")
        .body(params: _*)
        .response(asParams)
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody.toList should be(params)
        }
    }

    "as string with expected unsuccessful response via metadata" in {
      val expectedStatus = 400
      sttp
        .post(uri"$endpoint/echo/custom_status/$expectedStatus")
        .body(testBody)
        .response(asString)
        .parseResponseIfMetadata(_.code == expectedStatus)
        .send()
        .toFuture()
        .map { response =>
          response.body should be(Right(s"POST /echo/custom_status/$expectedStatus $testBody"))
        }
    }

    "as string with expected unsuccessful response" in {
      val expectedStatus = 400
      sttp
        .post(uri"$endpoint/echo/custom_status/$expectedStatus")
        .body(testBody)
        .response(asString)
        .parseResponseIf(_ == expectedStatus)
        .send()
        .toFuture()
        .map { response =>
          response.body should be(Right(s"POST /echo/custom_status/$expectedStatus $testBody"))
        }
    }

    "as error string if status code is not supported" in {
      val unexpectedStatus = 200
      sttp
        .post(uri"$endpoint/echo/custom_status/$unexpectedStatus")
        .body(testBody)
        .response(asString)
        .parseResponseIf(_ != unexpectedStatus)
        .send()
        .toFuture()
        .map { response =>
          response.body should be(Left(s"POST /echo/custom_status/$unexpectedStatus $testBody"))
        }
    }
  }

  "parameters" - {
    "make a get request with parameters" in {
      sttp
        .get(uri"$endpoint/echo?p2=v2&p1=v1")
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should be("GET /echo p1=v1 p2=v2")
        }
    }
  }

  "body" - {
    "post a string" in {
      postEcho.body(testBody).send().toFuture().map { response =>
        response.unsafeBody should be(expectedPostEchoResponse)
      }
    }

    "post a byte array" in {
      postEcho.body(testBodyBytes).send().toFuture().map { response =>
        response.unsafeBody should be(expectedPostEchoResponse)
      }
    }

    "post an input stream" in {
      postEcho
        .body(new ByteArrayInputStream(testBodyBytes))
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should be(expectedPostEchoResponse)
        }
    }

    "post a byte buffer" in {
      postEcho
        .body(ByteBuffer.wrap(testBodyBytes))
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should be(expectedPostEchoResponse)
        }
    }

    "post form data" in {
      sttp
        .post(uri"$endpoint/echo/form_params/as_string")
        .body("a" -> "b", "c" -> "d")
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should be("a=b c=d")
        }
    }

    "post form data with special characters" in {
      sttp
        .post(uri"$endpoint/echo/form_params/as_string")
        .body("a=" -> "/b", "c:" -> "/d")
        .send()
        .toFuture()
        .map { response =>
          response.unsafeBody should be("a==/b c:=/d")
        }
    }

    "post without a body" in {
      postEcho.send().toFuture().map { response =>
        response.unsafeBody should be("POST /echo")
      }
    }
  }

  protected def cacheControlHeaders: Set[String] = Set("no-cache", "max-age=1000")

  "headers" - {
    def getHeaders = sttp.get(uri"$endpoint/set_headers")

    "read response headers" in {
      getHeaders.response(sttpIgnore).send().toFuture().map { response =>
        response.headers should have length (4 + cacheControlHeaders.size).toLong
        response.headers("Cache-Control").toSet should be(cacheControlHeaders)
        response.header("Server").exists(_.startsWith("akka-http")) should be(true)
        response.contentType should be(Some("text/plain; charset=UTF-8"))
        response.contentLength should be(Some(2L))
      }
    }
  }

  "errors" - {
    "return 405 when method not allowed" in {
      sttp.post(uri"$endpoint/set_headers").response(sttpIgnore).send().toFuture().map { response =>
        response.code should be(405)
        response.isClientError should be(true)
        response.body.isLeft should be(true)
      }
    }

    "return 404 when not found" in {
      sttp.get(uri"$endpoint/not/found").response(sttpIgnore).send().toFuture().map { response =>
        response.code should be(404)
        response.isClientError should be(true)
        response.body.isLeft should be(true)
      }
    }
  }

  "auth" - {
    def secureBasic = sttp.get(uri"$endpoint/secure_basic")

    "return a 401 when authorization fails" in {
      val req = secureBasic
      req.send().toFuture().map { resp =>
        resp.code should be(401)
        resp.header("WWW-Authenticate") should be(Some("""Basic realm="test realm",charset=UTF-8"""))
      }
    }

    "perform basic authorization" in {
      val req = secureBasic.auth.basic("adam", "1234")
      req.send().toFuture().map { resp =>
        resp.code should be(200)
        resp.unsafeBody should be("Hello, adam!")
      }
    }
  }

  "compression" - {
    def compress = sttp.get(uri"$endpoint/compress")
    val decompressedBody = "I'm compressed!"

    "decompress using the default accept encoding header" in {
      val req = compress
      req.send().toFuture().map { resp =>
        resp.unsafeBody should be(decompressedBody)
      }
    }

    "decompress using gzip" in {
      val req = compress.header("Accept-Encoding", "gzip", replaceExisting = true)
      req.send().toFuture().map { resp =>
        resp.unsafeBody should be(decompressedBody)
      }
    }

    "decompress using deflate" in {
      val req = compress.header("Accept-Encoding", "deflate", replaceExisting = true)
      req.send().toFuture().map { resp =>
        resp.unsafeBody should be(decompressedBody)
      }
    }

    "work despite providing an unsupported encoding" in {
      val req = compress.header("Accept-Encoding", "br", replaceExisting = true)
      req.send().toFuture().map { resp =>
        resp.unsafeBody should be(decompressedBody)
      }
    }

    "not attempt to decompress HEAD requests" in {
      val req = sttp.head(uri"$endpoint/compress")
      req.send().toFuture().map { resp =>
        resp.code shouldBe StatusCodes.Ok
      }
    }
  }

  // in JavaScript the only way to set the content type is to use a Blob which defaults the filename to 'blob'
  protected def multipartStringDefaultFileName: Option[String] = None
  protected def defaultFileName: String = multipartStringDefaultFileName match {
    case None       => ""
    case Some(name) => s" ($name)"
  }

  "multipart" - {
    def mp = sttp.post(uri"$endpoint/multipart")

    "send a multipart message" in {
      val req = mp.multipartBody(multipart("p1", "v1"), multipart("p2", "v2"))
      req.send().toFuture().map { resp =>
        resp.unsafeBody should be(s"p1=v1$defaultFileName, p2=v2$defaultFileName")
      }
    }

    "send a multipart message with filenames" in {
      val req = mp.multipartBody(multipart("p1", "v1").fileName("f1"), multipart("p2", "v2").fileName("f2"))
      req.send().toFuture().map { resp =>
        resp.unsafeBody should be("p1=v1 (f1), p2=v2 (f2)")
      }
    }
  }

  protected def expectRedirectResponse(
      response: R[Response[String]],
      code: Int
  ): Future[Assertion] = {
    response.toFuture().map { resp =>
      resp.code should be(code)
      resp.body should be('left)
      resp.history should be('empty)
    }
  }

  "redirect" - {
    def r1 = sttp.post(uri"$endpoint/redirect/r1")
    def r2 = sttp.post(uri"$endpoint/redirect/r2")
    val r4response = "819"
    def loop = sttp.post(uri"$endpoint/redirect/loop")

    "not redirect when redirects shouldn't be followed (temporary)" in {
      expectRedirectResponse(r1.followRedirects(false).send(), 307)
    }

    "not redirect when redirects shouldn't be followed (permanent)" in {
      expectRedirectResponse(r2.followRedirects(false).send(), 308)
    }

    "redirect when redirects should be followed" in {
      r2.send().toFuture().map { resp =>
        resp.code should be(200)
        resp.unsafeBody should be(r4response)
      }
    }

    "redirect twice when redirects should be followed" in {
      r1.send().toFuture().map { resp =>
        resp.code should be(200)
        resp.unsafeBody should be(r4response)
      }
    }

    "redirect when redirects should be followed, and the response is parsed" in {
      r2.response(asString.map(_.toInt)).send().toFuture().map { resp =>
        resp.code should be(200)
        resp.unsafeBody should be(r4response.toInt)
      }
    }

    "not redirect when maxRedirects is less than or equal to 0" in {
      expectRedirectResponse(loop.maxRedirects(-1).send(), 302)
    }
  }

  if (supportsRequestTimeout) {
    "timeout" - {
      "fail if read timeout is not big enough" in {
        val request = sttp
          .get(uri"$endpoint/timeout")
          .readTimeout(200.milliseconds)
          .response(asString)

        Future(request.send()).flatMap(_.toFuture()).failed.map { _ =>
          succeed
        }
      }

      "not fail if read timeout is big enough" in {
        val request = sttp
          .get(uri"$endpoint/timeout")
          .readTimeout(5.seconds)
          .response(asString)

        request.send().toFuture().map { response =>
          response.unsafeBody should be("Done")
        }
      }
    }
  }

  "empty response" - {
    def postEmptyResponse =
      sttp
        .post(uri"$endpoint/empty_unauthorized_response")
        .body("{}")
        .contentType("application/json")

    "parse an empty error response as empty string" in {
      postEmptyResponse.send().toFuture().map { response =>
        response.body should be(Left(""))
      }
    }
  }

  override protected def afterAll(): Unit = {
    backend.close()
    super.afterAll()
  }

}

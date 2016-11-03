/*
* [y] hybris Platform
*
* Copyright (c) 2000-2016 hybris AG
* All rights reserved.
*
* This software is the confidential and proprietary information of hybris
* ("Confidential Information"). You shall not disclose such Confidential
* Information and shall use it only in accordance with the terms of the
* license agreement you entered into with hybris.
*/
package com.hybris.core.dbr.document

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken, RawHeader}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.hybris.core.dbr.exceptions.DocumentBackupClientException
import de.heikoseeberger.akkahttpcirce.CirceSupport
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder

import scala.concurrent.{ExecutionContext, Future}

class DefaultDocumentBackupClient(documentBackupUrl: String,
                                  token: Option[String])
                                 (implicit system: ActorSystem,
                                  materializer: Materializer,
                                  executionContext: ExecutionContext)
  extends DocumentBackupClient
    with CirceSupport {

  private case class InsertResult(documentsImported: Int)

  private case class GetTypesResponse(types: List[String])

  private implicit val getTypesResponseDecoder: Decoder[GetTypesResponse] = deriveDecoder
  private implicit val insertResultDecoder: Decoder[InsertResult] = deriveDecoder

  private val authorizationHeader = token.map(t => Authorization(OAuth2BearerToken(t)))

  override def getTypes(client: String, tenant: String): Future[List[String]] = {

    val request = HttpRequest(
      uri = s"$documentBackupUrl/$tenant/$client",
      headers = getHeaders(client, tenant))

    Http()
      .singleRequest(request)
      .flatMap {
        case response if response.status.isSuccess() =>
          Unmarshal(response).to[GetTypesResponse].map(_.types)

        case response =>
          response.discardEntityBytes()
          Future.failed(DocumentBackupClientException(s"Failed to get types for client '$client' and tenant '$tenant'," +
            s" status: ${response.status.intValue()}"))
      }
  }

  override def getDocuments(client: String, tenant: String, `type`: String): Future[Source[ByteString, Any]] = {

    val request = HttpRequest(
      uri = s"$documentBackupUrl/$tenant/$client/data/${`type`}?fetchAll=true",
      headers = getHeaders(client, tenant))

    Http()
      .singleRequest(request)
      .flatMap {
        case response if response.status.isSuccess() =>
          Future.successful(response.entity.withoutSizeLimit().dataBytes)

        case response =>
          response.discardEntityBytes()
          Future.failed(DocumentBackupClientException(s"Failed to get documents for client '$client',tenant '$tenant'" +
            s" and type '${`type`}', status: ${response.status.intValue()}"))
      }
  }

  override def insertRawDocuments(client: String, tenant: String, `type`: String, documents: Source[ByteString, _]): Future[Int] = {

    val request = HttpRequest(method = HttpMethods.POST,
      uri = s"$documentBackupUrl/$tenant/$client/data/${`type`}",
      entity = HttpEntity(ContentTypes.`application/json`, data = documents),
      headers = getHeaders(client, tenant))

    Http()
      .singleRequest(request)
      .flatMap {

        case response if response.status.isSuccess() ⇒
          Unmarshal(response).to[InsertResult].map(_.documentsImported)

        case response ⇒
          response.discardEntityBytes()
          Future.failed(DocumentBackupClientException(s"Failed to inserting raw document. Response code: ${response.status}"))
      }
  }

  private def getHeaders(client: String, tenant: String): List[HttpHeader] = {
    authorizationHeader match {
      case Some(authHeader) ⇒ authHeader :: Nil
      case None ⇒ RawHeader("hybris-client", client) :: RawHeader("hybris-tenant", tenant) :: Nil
    }
  }
}
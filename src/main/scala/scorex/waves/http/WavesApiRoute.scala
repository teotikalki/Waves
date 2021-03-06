package scorex.waves.http

import javax.ws.rs.Path

import akka.actor.ActorRefFactory
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Route
import io.swagger.annotations._
import play.api.libs.json.{JsError, JsSuccess, Json}
import scorex.account.Account
import scorex.api.http.{NegativeFee, NoBalance, _}
import scorex.app.RunnableApplication
import scorex.crypto.encode.Base58
import scorex.transaction.LagonakiTransaction.ValidationResult
import scorex.transaction.state.wallet.Payment
import scorex.waves.settings.WavesSettings
import scorex.waves.transaction.{ExternalPayment, SignedPayment, WavesTransactionModule}
import scorex.waves.wallet.Wallet

import scala.util.{Failure, Success, Try}

@Path("/waves")
@Api(value = "waves", description = "Waves specific commands.", position = 1)
case class WavesApiRoute(override val application: RunnableApplication)(implicit val context: ActorRefFactory)
  extends ApiRoute with CommonTransactionApiFunctions {

  lazy val wallet = application.wallet

  val suspendedSenders = application.settings.asInstanceOf[WavesSettings].suspendedSenders

  // TODO asInstanceOf
  implicit lazy val transactionModule: WavesTransactionModule = application.transactionModule.asInstanceOf[WavesTransactionModule]

  override lazy val route = pathPrefix("waves") {
    externalPayment ~ address ~ signPayment ~ broadcastSignedPayment ~ payment ~ createdSignedPayment
  }

  // TODO: Should be moved to Scorex
  @Path("/payment")
  @ApiOperation(value = "Send payment from wallet",
    notes = "Send payment from wallet to another wallet. Each call sends new payment",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.transaction.state.wallet.Payment",
      defaultValue = "{\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"sender\":\"senderId\",\n\t\"recipient\":\"recipientId\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def payment: Route = path("payment") {
    withCors {
      entity(as[String]) { body =>
        withAuth {
          postJsonRoute {
            walletNotExists(wallet).getOrElse {
              Try(Json.parse(body)).map { js =>
                js.validate[Payment] match {
                  case err: JsError =>
                    WrongTransactionJson(err).response
                  case JsSuccess(payment: Payment, _) =>
                    val txOpt = transactionModule.createPayment(payment, wallet)
                    txOpt match {
                      case Some(tx) =>
                        tx.validate match {
                          case ValidationResult.ValidateOke =>
                            val signed = SignedPayment(tx.timestamp, tx.amount, tx.fee, tx.recipient.toString,
                              Base58.encode(tx.sender.publicKey), tx.sender.address, Base58.encode(tx.signature))
                            JsonResponse(Json.toJson(signed), StatusCodes.OK)

                          case ValidationResult.InvalidAddress =>
                            InvalidAddress.response

                          case ValidationResult.NegativeAmount =>
                            NegativeAmount.response

                          case ValidationResult.NegativeFee =>
                            NegativeFee.response

                          case ValidationResult.NoBalance =>
                            NoBalance.response
                        }
                      case None =>
                        InvalidSender.response
                    }
                }
              }.getOrElse(WrongJson.response)
            }
          }
        }
      }
    }
  }

  // TODO: Should be moved to Scorex
  @Path("/payment/signature")
  @ApiOperation(value = "Create payment signed by address from wallet",
    notes = "Create unique payment signed by address from wallet. Without broadcasting to network.",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.transaction.state.wallet.Payment",
      defaultValue = "{\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"sender\":\"senderId\",\n\t\"recipient\":\"recipientId\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def signPayment: Route = path("payment" / "signature") {
    withCors {
      entity(as[String]) { body =>
        withAuth {
          postJsonRoute {
            walletNotExists(wallet).getOrElse {
              Try(Json.parse(body)).map { js =>
                js.validate[Payment] match {
                  case err: JsError =>
                    WrongTransactionJson(err).response
                  case JsSuccess(payment: Payment, _) =>
                    val txOpt = transactionModule.signPayment(payment, wallet)
                    txOpt match {
                      case Some(tx) =>
                        tx.validate match {
                          case ValidationResult.ValidateOke =>
                            val signed = SignedPayment(tx.timestamp, tx.amount, tx.fee, tx.recipient.toString,
                              Base58.encode(tx.sender.publicKey), tx.sender.address, Base58.encode(tx.signature))
                            JsonResponse(Json.toJson(signed), StatusCodes.OK)

                          case ValidationResult.InvalidAddress =>
                            InvalidAddress.response

                          case ValidationResult.NegativeAmount =>
                            NegativeAmount.response

                          case ValidationResult.NegativeFee =>
                            NegativeFee.response

                          case ValidationResult.NoBalance =>
                            NoBalance.response
                        }
                      case None =>
                        InvalidSender.response
                    }
                }
              }.getOrElse(WrongJson.response)
            }
          }
        }
      }
    }
  }

  @Path("/create-signed-payment")
  @ApiOperation(value = "Sign payment",
    notes = "Sign payment by provided wallet seed",
    httpMethod = "POST",
    produces = "application/json",
    consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.waves.http.UnsignedPayment",
      defaultValue = "{\n\t\"timestamp\": 0,\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"recipient\":\"recipientAddress\", \n\t\"senderWalletSeed\":\"seed\",\n\t\"senderAddressNonce\":\"0\"\n}"
    )
  ))
  @ApiResponses(Array(
    new ApiResponse(code = 200, message = "Json with response or error")
  ))
  def createdSignedPayment: Route = path("create-signed-payment") {
    withCors {
      entity(as[String]) { body =>
        postJsonRoute {
          Try(Json.parse(body)).map { js =>
            js.validate[UnsignedPayment] match {
              case err: JsError =>
                WrongTransactionJson(err).response
              case JsSuccess(payment: UnsignedPayment, _) =>
                val senderWalletSeed = Base58.decode(payment.senderWalletSeed).getOrElse(Array.empty)
                if (senderWalletSeed.isEmpty)
                  WrongJson.response
                else {
                  val senderAccount = Wallet.generateNewAccount(senderWalletSeed, payment.senderAddressNonce)
                  val recipientAccount = new Account(payment.recipient)

                  transactionModule.createSignedPayment(senderAccount, recipientAccount,
                    payment.amount, payment.fee, payment.timestamp) match {
                    case Right(tx) =>
                      val signature = Base58.encode(tx.signature)
                      val senderPubKey = Base58.encode(tx.sender.publicKey)
                      val signedTx = SignedPayment(tx.timestamp, tx.amount, tx.fee, tx.recipient.toString,
                        senderPubKey, tx.sender.address, signature)
                      JsonResponse(Json.toJson(signedTx), StatusCodes.OK)

                    case Left(e) => e match {
                      case ValidationResult.NoBalance => NoBalance.response
                      case ValidationResult.InvalidAddress => InvalidAddress.response
                    }
                  }
                }
            }
          }.getOrElse(WrongJson.response)
        }
      }
    }
  }

  @Path("/address")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(name = "publicKey", value = "Public key as a plain string", required = true, paramType = "body", dataType = "String")
  ))
  @ApiOperation(value = "Generate", notes = "Generate a address from public key", httpMethod = "POST")
  def address: Route = {
    path("address") {
      withCors {
        entity(as[String]) { publicKey =>
          postJsonRoute {
            val account = Account.fromPublicKey(Base58.decode(publicKey).get)
            JsonResponse(Json.obj("address" -> account.address), StatusCodes.OK)
          }
        }
      }
    }
  }

  @Deprecated
  @Path("/external-payment")
  @ApiOperation(value = "Broadcast payment", notes = "Publish signed payment to the Blockchain", httpMethod = "POST", produces = "application/json", consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.waves.transaction.ExternalPayment",
      defaultValue = "{\n\t\"timestamp\": 0,\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"senderPublicKey\":\"senderPubKey\",\n\t\"recipient\":\"recipientId\",\n\t\"signature\":\"sig\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def externalPayment: Route = path("external-payment") {
    withCors {
      entity(as[String]) { body =>
        postJsonRoute {
          Try {
            val js = Json.parse(body)
            js.validate[ExternalPayment] match {
              case _: JsError => WrongJson.response
              case JsSuccess(payment: ExternalPayment, _) =>
                Base58.decode(payment.senderPublicKey) match {
                  case Success(senderPublicKeyBytes) =>
                    val senderAccount = Account.fromPublicKey(senderPublicKeyBytes)
                    if (suspendedSenders.contains(senderAccount.address))
                      InvalidSender.response
                    else
                      broadcastPayment(payment)
                  case Failure(_) => InvalidSender.response
                }
            }
          }.getOrElse(WrongJson.response)
        }
      }
    }
  }

  @Path("/broadcast-signed-payment")
  @ApiOperation(value = "Broadcast signed payment", notes = "Publish signed payment to the Blockchain", httpMethod = "POST", produces = "application/json", consumes = "application/json")
  @ApiImplicitParams(Array(
    new ApiImplicitParam(
      name = "body",
      value = "Json with data",
      required = true,
      paramType = "body",
      dataType = "scorex.waves.transaction.SignedPayment",
      defaultValue = "{\n\t\"timestamp\": 0,\n\t\"amount\":400,\n\t\"fee\":1,\n\t\"senderPublicKey\":\"senderPubKey\",\n\t\"senderAddress\":\"senderAddress\",\n\t\"recipient\":\"recipientId\",\n\t\"signature\":\"sig\"\n}"
    )
  ))
  @ApiResponses(Array(new ApiResponse(code = 200, message = "Json with response or error")))
  def broadcastSignedPayment: Route = path("broadcast-signed-payment") {
    withCors {
      entity(as[String]) { body =>
        postJsonRoute {
          Try(Json.parse(body)).map { js =>
            js.validate[SignedPayment] match {
              case _: JsError =>
                WrongJson.response
              case JsSuccess(payment: SignedPayment, _) =>
                Base58.decode(payment.senderPublicKey) match {
                  case Success(senderPubKeyBytes) =>
                    val senderAccount = Account.fromPublicKey(senderPubKeyBytes)
                    if (suspendedSenders.contains(senderAccount.address)) InvalidSender.response
                    else broadcastPayment(payment)
                  case Failure(e) => InvalidSender.response
                }
            }
          }.getOrElse(WrongJson.response)
        }
      }
    }
  }

  private def broadcastPayment(payment: SignedPayment): JsonResponse = {
    transactionModule.broadcastPayment(payment) match {
      case Right(tx) =>
        if (!tx.signatureValid) InvalidSignature.response
        else {
          tx.validate match {
            case ValidationResult.ValidateOke =>
              JsonResponse(tx.json, StatusCodes.OK)

            case ValidationResult.InvalidAddress =>
              InvalidAddress.response

            case ValidationResult.NegativeAmount =>
              NegativeAmount.response

            case ValidationResult.NegativeFee =>
              NegativeFee.response
          }
        }
      case Left(e) => e match {
        case ValidationResult.NoBalance => NoBalance.response
        case ValidationResult.InvalidAddress => InvalidAddress.response
        case ValidationResult.NegativeFee => NegativeFee.response
        case _ => Unknown.response
      }
    }
  }

  @Deprecated
  private def broadcastPayment(payment: ExternalPayment): JsonResponse = {
    val senderAccount = Account.fromPublicKey(Base58.decode(payment.senderPublicKey).get)
    val signedPayment = SignedPayment(payment.timestamp, payment.amount, payment.fee, payment.recipient,
      payment.senderPublicKey, senderAccount.address, payment.signature)

    transactionModule.broadcastPayment(signedPayment) match {
      case Right(tx) =>
        if (!tx.signatureValid) InvalidSignature.response
        else {
          tx.validate match {
            case ValidationResult.ValidateOke =>
              JsonResponse(tx.json, StatusCodes.OK)

            case ValidationResult.InvalidAddress =>
              InvalidAddress.response

            case ValidationResult.NegativeAmount =>
              NegativeAmount.response

            case ValidationResult.NegativeFee =>
              NegativeFee.response
          }
        }
      case Left(e) => e match {
        case ValidationResult.NoBalance => NoBalance.response
        case ValidationResult.InvalidAddress => InvalidAddress.response
        case ValidationResult.NegativeFee => NegativeFee.response
        case _ => Unknown.response
      }
    }
  }
}
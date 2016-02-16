package ch.wsl.rest.service

import java.io.FileOutputStream
import com.typesafe.config.{ConfigFactory, Config}
import slick.model.Table

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.language.postfixOps
import akka.actor.Actor
import net.liftweb.json.DefaultFormats
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import spray.http.MediaTypes.{ `text/html` }
import spray.httpx.LiftJsonSupport
import spray.httpx.marshalling.Marshaller
import spray.httpx.unmarshalling.Unmarshaller
import spray.routing.Route
import spray.routing.Directive.pimpApply
import spray.routing.HttpService
import spray.routing.authentication.BasicAuth
import spray.routing.authentication.UserPass
import spray.routing.authentication.UserPassAuthenticator
import spray.routing.directives.AuthMagnet.fromContextAuthenticator
import spray.routing.directives.FieldDefMagnet.apply
import spray.routing.RejectionHandler
import ch.wsl.rest.domain.JSONSchema
import ch.wsl.rest.domain.JSONForm
import ch.wsl.rest.domain.JSONQuery
import ch.wsl.model.Tables._

import ch.wsl.rest.domain.JSONField
import ch.wsl.rest.domain.UglyDBFilters
import ch.wsl.rest.domain.DBFilters

import slick.driver.PostgresDriver.api._

import ch.wsl.rest.domain.EnhancedTable._

import com.typesafe.config._
import net.ceedubs.ficus.Ficus._

import scala.util.Try

// we don't implement our route structure directly in the service actor because
// we want to be able to test it independently, without having to spin up an actor
class MainServiceActor extends Actor with MainService  {

  // the HttpService trait defines only one abstract member, which
  // connects the services environment to the enclosing actor or test
  def actorRefFactory = context

  
  implicit val myRejectionHandler = RejectionHandler {
    case t => {
      println(t)
      complete("Something went wrong here")
    }
    case _ => complete("Something went wrong here")
  }

  
  // this actor only runs our route, but you could add
  // other things here, like request stream processing
  // or timeout handling
  def receive = runRoute(s4Route)
}




/**
 *  this trait defines our service behavior independently from the service actor
 */
trait MainService extends HttpService with CORSSupport with UglyDBFilters {
  
  object JsonProtocol extends LiftJsonSupport {
    implicit def liftJsonFormats = DefaultFormats
  
  //object JsonProtocol extends LiftJsonSupport {
  //  implicit def json4sFormats: Formats = DefaultFormats
    
  }

  //TODO Extend UserProfile class depending on project requirements
  case class UserProfile(name: String, db: Database)

  def getUserProfile(name: String, password: String): Option[UserProfile] = {
    //TODO Here you should check if this is a valid user on your system and return his profile

    val dbConf: Config = ConfigFactory.load().as[Config]("db")

    println("Connecting to DB with " + name )


    val db:Database = Database.forURL(dbConf.as[String]("url"),
      driver="org.postgresql.Driver",
      user=name,
      password=password)

      Try {
        //check if login data are valid
        db.createSession().close()
      }.toOption.map{ _ =>
        UserProfile(name,db)
      }

  }

  object CustomUserPassAuthenticator extends UserPassAuthenticator[UserProfile] {
    def apply(userPass: Option[UserPass]) = Promise.successful(
      userPass match {
        case Some(UserPass(user, pass)) => {
          getUserProfile(user, pass)
        }
        case _ => None
      }).future
  }

  var models = Set[String]()





  def modelRoute[T <: slick.driver.PostgresDriver.api.Table[M],M](name:String, table:TableQuery[T])(implicit mar:Marshaller[M], unmar: Unmarshaller[M], db:Database):Route = {

    case class JSONResult(count:Int,data:List[M])

    models = Set(name) ++ models

    import JsonProtocol._



    pathPrefix(name) {
            path(IntNumber) { i=>
              get {

                def fil(pk:String):Rep[Seq[T#TableElementType]] =  table.filter(x => ==(x.col(pk),i)).take(1)

                val result = for{
                  pks <- JSONSchema.keysOf(name,db)
                  result <- db.run{ fil(pks.head).result }
                } yield result.head

                complete{result}

              } ~
              put {
                entity(as[M]) { e =>
                  val result = db.run{ table.insertOrUpdate(e) }.map(_ => e)
                  complete(result) //result should be in the same future as e
                }
              } ~
              post {
                entity(as[M]) { e =>
                  val result = db.run{ table.forceInsert(e) }.map(_ => e)
                  complete(result)
                }
              }

            } ~
            path("schema") {
              get {
                complete{ JSONSchema.of(name,db) }
              }
            } ~
            path("form") {
              get {
                complete{ JSONForm.of(name,db) }
              }
            } ~
            path("keys") {
              get {
                complete{ JSONSchema.keysOf(name,db) }
              }
            } ~
            path("count") {
                get { ctx =>

                  db.run{table.length.result}.map{ result =>
                    ctx.complete{ JObject(List(JField("count",JInt(result)))) }
                  }

                }
            } ~
//            path("list") {
//                post {
//                  entity(as[JSONQuery]) { query =>
//                    val (result,count) = db withSession { implicit s =>
//
//
//
//                        val qFiltered = query.filter.foldRight[Query[T,M,Seq]](table.tq){case ((field,jsFilter),query) =>
//                          println(jsFilter)
//                          query.filter(table.filter(field, operator(jsFilter.operator.getOrElse("=")), jsFilter.value))
//                        }
//
//                        val qSorted = query.sorting.foldRight[Query[T,M,Seq]](qFiltered){case ((field,dir),query) =>
//                          query.sortBy{ x =>
//                            val c = table.columns(field)(x)
//                            dir match {
//                              case "asc" => c.asc
//                              case "desc" => c.desc
//                            }
//                          }
//                        }
//
//                        (qSorted
//                        .drop((query.page - 1) * query.count)
//                        .take(query.count)
//                        .list,
//                        qSorted.length.run)
//
//
//                    }
//
//
//                    complete(JSONResult(count,result))
//                  }
//                }
//            } ~
            pathEnd{
              get { ctx =>
                ctx.complete {
                  val q:Rep[Seq[T#TableElementType]] = table.take(50)
                  val result: Future[Seq[M]] = db.run{ q.result }
                  result
                }
              } ~
              post {
                entity(as[M]) { e =>
                  val result = db.run { table.forceInsert(e) }.map(_ => e)
                  complete(result)
                }
              } ~
              put {
                entity(as[M]) { e =>
                  val result = db.run { table.insertOrUpdate(e) }.map(_ => e)
                  complete(result)
                }
              }
            }
        }
  }


  def viewRoute[T <: slick.driver.PostgresDriver.api.Table[M],M](name:String, table:TableQuery[T])(implicit mar:Marshaller[M], unmar: Unmarshaller[M], db:Database):Route = {

    case class JSONResult(count:Int,data:List[M])

    models = Set(name) ++ models

    import JsonProtocol._


    pathPrefix(name) {
            path("schema") {
              get {
                complete{ JSONSchema.of(name,db) }
              }
            } ~
            path("form") {
              get {
                complete{ JSONForm.of(name,db) }
              }
            } ~
            path("keys") {
              get {
                complete{ JSONSchema.keysOf(name,db) }
              }
            } ~
            path("count") {
                get { ctx =>

                  val result = db.run { table.length.result }.map{r =>
                    JObject(List(JField("count",JInt(r))))
                  }
                  ctx.complete{ result }
                }
            } ~
//            path("list") {
//                post {
//                  entity(as[JSONQuery]) { query =>
//                    val (result,count) = db withSession { implicit s =>
//
//
//
//                        val qFiltered = query.filter.foldRight[Query[T,M,Seq]](table.tq){case ((field,jsFilter),query) =>
//                          println(jsFilter)
//                          query.filter(table.filter(field, operator(jsFilter.operator.getOrElse("=")), jsFilter.value))
//                        }
//
//                        val qSorted = query.sorting.foldRight[Query[T,M,Seq]](qFiltered){case ((field,dir),query) =>
//                          query.sortBy{ x =>
//                            val c = table.columns(field)(x)
//                            dir match {
//                              case "asc" => c.asc
//                              case "desc" => c.desc
//                            }
//                          }
//                        }
//
//                        (qSorted
//                        .drop((query.page - 1) * query.count)
//                        .take(query.count)
//                        .list,
//                        qSorted.length.run)
//
//
//                    }
//
//
//                    complete(JSONResult(count,result))
//                  }
//                }
//            } ~
            pathEnd{
              get { ctx =>
                ctx.complete {
                  val q:Rep[Seq[T#TableElementType]] = table.take(50)
                  val result: Future[Seq[M]] = db.run{ q.result }
                  result
                }

              }
            }
        }
  }

  
  val index = get { ctx =>
          respondWithMediaType(`text/html`) {  // XML is marshalled to `text/xml` by default, so we simply override here
            complete {
              <html>
                <body>
                  <h1>The <b>S4</b> - <i>Slick Spray Scala Stack</i> is running :-)</h1>
                </body>
              </html>
            }
          }
        }
  
  val s4Route = {
    
      import JsonProtocol._

    
    


//    val test = db withSession { implicit s =>
//
//      Fire.tq.filter(_.locality === "test")
//
//
//    }
//
//    println(test)

    
      pathEnd {
        index
      } ~
      cors{
        options {
           complete(spray.http.StatusCodes.OK)
        } ~
        authenticate(BasicAuth(CustomUserPassAuthenticator, "person-security-realm")) { userProfile =>
          implicit val db = userProfile.db
          modelRoute[Canton,CantonRow]("canton",Canton) ~
          modelRoute[CatCause,CatCauseRow]("cat_cause", CatCause) ~
          modelRoute[CatCauseBafu,CatCauseBafuRow]("cat_cause_bafu", CatCauseBafu) ~
          modelRoute[Days,DaysRow]("days", Days) ~
          modelRoute[Fire,FireRow]("fire",Fire)  ~
          modelRoute[ValAttribute,ValAttributeRow]("val_attribute",ValAttribute)  ~
          modelRoute[ValBafuForestType,ValBafuForestTypeRow]("val_bafu_forest_type",ValBafuForestType) ~
          modelRoute[ValCause,ValCauseRow]("val_cause",ValCause)  ~
          modelRoute[ValCauseReliability,ValCauseReliabilityRow]("val_cause_reliability",ValCauseReliability)  ~
          modelRoute[ValCoordReliability,ValCoordReliabilityRow]("val_coord_reliability",ValCoordReliability)  ~
          modelRoute[ValDamage,ValDamageRow]("val_damage",ValDamage)  ~
          modelRoute[ValDateReliability,ValDateReliabilityRow]("val_date_reliability",ValDateReliability)  ~
          modelRoute[ValDefinition,ValDefinitionRow]("val_definition",ValDefinition)  ~
          modelRoute[ValExposition,ValExpositionRow]("val_exposition",ValExposition)  ~
          modelRoute[ValLayerAbundance,ValLayerAbundanceRow]("val_layer_abundance",ValLayerAbundance)  ~
          modelRoute[ValMonth,ValMonthRow]("val_month",ValMonth)  ~
          modelRoute[ValSite,ValSiteRow]("val_site",ValSite)  ~
          modelRoute[SysForm,SysFormRow]("sys_form",SysForm)  ~
          //viewRoute[VRegionMunicipality,VRegionMunicipalityRow]("v_region_municipality",VRegionMunicipality)  ~
          path("models") {
            get{
              complete(models)
            }
          }
        }
      }
    
  }


}


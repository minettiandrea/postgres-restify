package postgresweb.controllers


import ch.wsl.jsonmodels.JSONQuery
import ch.wsl.model.shared.{Table, JSONSchemaUI}
import japgolly.scalajs.react.{Callback, ReactElement}
import japgolly.scalajs.react.extra.router.RouterCtl
import postgresweb.model.Menu

import scala.concurrent.Future
import scala.scalajs.js

/**
  * Created by andreaminetti on 14/03/16.
  */
trait Controller {

  private var _container:Container = null
  private var routeController:RouterCtl[Container] = null

  def setRouteController(r:RouterCtl[Container]) = routeController = r
  //private def routeController = _routeController

  def setContainer(c: Container): Unit = _container = c
  def container = _container

  protected def routingMessage = s"Routing to ${container.title} with model ${container.model}"

  def routeTo(c:Container):Callback = {
    setContainer(c)
    Callback.log(routingMessage) >>
    routeController.set(c)
  }

  def menu:Vector[Menu] = Vector()

  def menuClick(m:Menu):Callback

  def entityClick(e:String):Callback

  def render():ReactElement = container.component

}

trait CRUDController extends Controller {

  val containers = new Containers(this)

  protected var filter:JSONQuery = JSONQuery.baseQuery
  protected var id:String = "none"

  protected def load(jq: JSONQuery):Future[Table]

  def table:Future[Table] = load(filter)
  def schemaAsString:Future[String]
  def uiSchema:Future[JSONSchemaUI]
  def get(id:String): Future[js.Any]
  def get: Future[js.Any]

  def selectId(id:String) = this.id = id
  def setQuery(jsonQuery: JSONQuery) = filter = jsonQuery
  def query:JSONQuery = filter

  def onInsert(data:js.Any):Callback
  def onUpdate(data:js.Any):Callback

  override def menuClick(m:Menu):Callback = routeTo(m.route(container.model,id))

  override def routingMessage = super.routingMessage + s" and id: $id"

  override def menu:Vector[Menu] = containers.menu

  override def entityClick(e: String): Callback = {
    val table = containers.Table(e) //default table
    routeTo(table)
  }

}
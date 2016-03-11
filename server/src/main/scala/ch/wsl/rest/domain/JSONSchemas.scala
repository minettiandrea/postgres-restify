package ch.wsl.rest.domain

import ch.wsl.jsonmodels.{JSONSchemaL2, JSONSchema}
import ch.wsl.rest.service.Auth

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

/**
  * Created by andreaminetti on 10/03/16.
  */
object JSONSchemas {

  import StringHelper._

  def of(table:String,db:slick.driver.PostgresDriver.api.Database):Future[JSONSchema] = {

    println("Getting JSONSchema of:" + table)

    val schema = new PgSchema(table,db)

    val map:Future[Map[String,JSONSchemaL2]] = schema.columns.map{ c => Map(properties(c): _*) }

    println("columns")

    for{
      m <- map
      c <- schema.columns
    } yield {
      JSONSchema(
        `type` = "object",
        title = Some(table),
        properties = m,
        readonly = Some(c.forall { x => x.is_updatable == "NO" }),
        required = Some(c.filter(_.is_nullable == "NO").map(_.column_name.slickfy))
      )
    }
  }



  def keysOf(table:String):Future[Seq[String]] = {
    new PgSchema(table,Auth.adminDB).pk.map { pks =>
      pks.map(_.slickfy)
    }

  }


  def properties(columns:Seq[PgColumn]):Seq[(String,JSONSchemaL2)] = {

    val cols = {for{
      c <- columns
    } yield {
      c.column_name.slickfy -> JSONSchemaL2(typesMapping(c.data_type),Some(c.column_name.slickfy),order=Some(c.ordinal_position),readonly=Some(c.is_updatable == "NO"))
    }}.toList


    cols
  }

  val typesMapping =  Map(
    "integer" -> "number",
    "character varying" -> "string",
    "character" -> "string",
    "smallint" -> "number",
    "bigint" -> "number",
    "double precision" -> "number",
    "timestamp without time zone" -> "string",
    "date" -> "string",
    "real" -> "number",
    "boolean" -> "checkbox",
    "bytea" -> "string",
    "numeric" -> "number",
    "text" -> "string",
    "USER-DEFINED" -> "string"
  )

}
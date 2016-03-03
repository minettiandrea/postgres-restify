package ch.wsl.rest.domain

import ch.wsl.rest.service.Auth
import slick.driver.PostgresDriver
import PostgresDriver.api._

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global



case class PgColumn(
  column_name:String,
  is_nullable:String,
  is_updatable:String,
  data_type:String,
  character_maximum_length:Option[Int],
  numeric_precision:Option[Int],
  numeric_scale:Option[Int],
  table_name:String,
  table_schema:String,
  ordinal_position:Int
)

class PgColumns(tag: Tag) extends Table[PgColumn](tag,  Some("information_schema"), "columns") {

  def column_name = column[String]("column_name")
  def is_nullable = column[String]("is_nullable")
  def is_updatable = column[String]("is_updatable")
  def data_type = column[String]("data_type")
  def character_maximum_length = column[Option[Int]]("character_maximum_length")
  def numeric_precision = column[Option[Int]]("numeric_precision")
  def numeric_scale = column[Option[Int]]("numeric_scale")
  def table_name = column[String]("table_name")
  def table_schema = column[String]("table_schema")
  def ordinal_position = column[Int]("ordinal_position")
    
  def * = (column_name, is_nullable, is_updatable, data_type, character_maximum_length, numeric_precision, numeric_scale, table_name, table_schema, ordinal_position) <> (PgColumn.tupled, PgColumn.unapply)
}

case class PgConstraint(
  table_name:String,
  constraint_name:String,
  constraint_type:String
)

class PgConstraints(tag: Tag) extends Table[PgConstraint](tag,  Some("information_schema"), "table_constraints") {

  def table_name = column[String]("table_name")
  def constraint_name = column[String]("constraint_name")
  def constraint_type = column[String]("constraint_type")

    
  def * = (table_name, constraint_name, constraint_type) <> (PgConstraint.tupled, PgConstraint.unapply)
}

case class PgConstraintReference(
  constraint_name:String,
  referencing_constraint_name:String
)

class PgConstraintReferences(tag: Tag) extends Table[PgConstraintReference](tag,  Some("information_schema"), "referential_constraints") {

  def constraint_name = column[String]("constraint_name")
  def referencing_constraint_name = column[String]("unique_constraint_name")

    
  def * = (constraint_name, referencing_constraint_name) <> (PgConstraintReference.tupled, PgConstraintReference.unapply)
}

case class PgConstraintUsage(
  constraint_name:String,
  table_name:String,
  column_name:String
)

class PgConstraintUsages(tag: Tag) extends Table[PgConstraintUsage](tag,  Some("information_schema"), "constraint_column_usage") {

  def constraint_name = column[String]("constraint_name")
  def table_name = column[String]("table_name")
  def column_name = column[String]("column_name")

    
  def * = (constraint_name, table_name, column_name) <> (PgConstraintUsage.tupled, PgConstraintUsage.unapply)
}

case class PgKeyUsage(
  constraint_name:String,
  table_name:String,
  column_name:String
)

class PgKeyUsages(tag: Tag) extends Table[PgKeyUsage](tag,  Some("information_schema"), "key_column_usage") {

  def constraint_name = column[String]("constraint_name")
  def table_name = column[String]("table_name")
  def column_name = column[String]("column_name")

    
  def * = (constraint_name, table_name, column_name) <> (PgKeyUsage.tupled, PgKeyUsage.unapply)
}

class PgSchema(table:String, db:Database) {

  private val FOREIGNKEY = "FOREIGN KEY"
  private val PRIMARYKEY = "PRIMARY KEY"
  
  val pgColumns = TableQuery[PgColumns]
  val pgConstraints = TableQuery[PgConstraints]
  val pgConstraintsReference = TableQuery[PgConstraintReferences]
  val pgContraintsUsage = TableQuery[PgConstraintUsages]
  val pgKeyUsage = TableQuery[PgKeyUsages]
  
  case class ForeignKey(keys:Seq[String], referencingKeys:Seq[String], referencingTable:String, contraintName:String)

  private val columnsQuery:Rep[Seq[PgColumns#TableElementType]] = pgColumns
    .filter(e => e.table_name === table && e.table_schema === "public")
    .sortBy(_.ordinal_position)


  lazy val columns:Future[Seq[PgColumn]] = db.run{
      columnsQuery.result
  }

  private val pkQ:Rep[Seq[String]] = for{
    constraint <- pgConstraints if constraint.table_name === table && constraint.constraint_type === PRIMARYKEY
    usage <- pgContraintsUsage if usage.constraint_name === constraint.constraint_name && usage.table_name === table
  } yield usage.column_name

  def pk:Future[Seq[String]] = Auth.adminDB.run{ //needs admin right to access information_schema.constraint_column_usage
    val action = pkQ.result
    println(action.statements)
    action
  }

  private val fkQ1:Rep[Seq[(PgConstraintReferences#TableElementType,PgConstraints#TableElementType)]] = for{
    constraint <- pgConstraints if constraint.table_name === table && constraint.constraint_type === FOREIGNKEY
    constraintBind <- pgConstraintsReference if constraint.constraint_name === constraintBind.constraint_name
    referencingContraint <- pgConstraints if referencingContraint.constraint_name === constraintBind.referencing_constraint_name
  } yield (constraintBind,referencingContraint)

  private def fkQ2(c:PgConstraintReferences#TableElementType,ref:PgConstraints#TableElementType):Rep[Seq[(String,String)]] = for{
    usageRef <- pgContraintsUsage if usageRef.constraint_name === c.constraint_name && usageRef.table_name === ref.table_name
    usage <- pgKeyUsage if usage.constraint_name === c.constraint_name && usage.table_name === table
  } yield (usage.column_name,usageRef.column_name)



  lazy val fk:Future[Seq[ForeignKey]] =  {

    db.run(fkQ1.result).flatMap { references =>
      Future.sequence(references.map { case (c, ref) =>
          db.run(fkQ2(c,ref).result).map{ keys =>
            ForeignKey(keys.map(_._1),keys.map(_._2),ref.table_name, c.constraint_name)
          }
      })
    }

  }
  
  def findFk(field:String):Future[Option[ForeignKey]] = fk.map(_.find(_.keys.exists(_ == field)))
  
}







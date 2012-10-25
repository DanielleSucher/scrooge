package com.twitter.scrooge.backend

/*
 * Copyright 2011 Twitter, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.{FileWriter, File}
import com.twitter.conversions.string._
import com.twitter.scrooge.mustache.HandlebarLoader
import com.twitter.scrooge.ast._
import com.twitter.scrooge.mustache.Dictionary
import com.twitter.scrooge.ScroogeInternalException


abstract sealed class ServiceOption

case object WithFinagleClient extends ServiceOption
case object WithFinagleService extends ServiceOption
case object WithOstrichServer extends ServiceOption
case class JavaService(service: Service, options: Set[ServiceOption])

abstract class Generator
  extends StructTemplate with ServiceTemplate with ConstsTemplate with EnumTemplate
{
  import Dictionary._

  /******************** helper functions ************************/
  private[this] def namespacedFolder(destFolder: File, namespace: String) = {
    val file = new File(destFolder, namespace.replace('.', File.separatorChar))
    file.mkdirs()
    file
  }

  def normalizeCase[N <: Node](node: N): N
  def getNamespace(doc0: Document): Identifier
  val fileExtension: String
  val templateDirName: String
  lazy val templates = new HandlebarLoader(templateDirName, fileExtension)
  def quote(str: String) = "\"" + str.quoteC() + "\""
  def quoteKeyword(str: String): String
  def isNullableType(t: FieldType, isOptional: Boolean = false) = {
    !isOptional && (
      t match {
        case TBool | TByte | TI16 | TI32 | TI64 | TDouble => false
        case _ => true
      }
      )
  }

  /**
   * Generates a prefix and suffix to wrap around a field expression that will
   * convert the value to a mutable equivalent.
   */
  def toMutable(t: FieldType): (String, String)

  /**
   * Generates a prefix and suffix to wrap around a field expression that will
   * convert the value to a mutable equivalent.
   */
  def toMutable(f: Field): (String, String)

  /**
   * get the ID of a service parent.  Java and Scala implementations are different.
   */
  def getServiceParentID(parent: ServiceParent): Identifier

  def isPrimitive(t: FunctionType): Boolean = {
    t match {
      case Void | TBool | TByte | TI16 | TI32 | TI64 | TDouble => true
      case _ => false
    }
  }

  private[this] def writeFile(file: File, fileHeader: String, fileContent: String) {
    val writer = new FileWriter(file)
    try {
      writer.write(fileHeader)
      writer.write(fileContent)
    } finally {
      writer.close()
    }
  }

  // methods that convert AST nodes to CodeFragment
  def genID(data: Identifier): CodeFragment = data match {
    case SimpleID(name) => codify(quoteKeyword(name))
    case QualifiedID(names) => codify(names.map { quoteKeyword(_) }.mkString("."))
  }

  def genConstant(constant: RHS, mutable: Boolean = false): CodeFragment = {
    constant match {
      case NullLiteral => codify("null")
      case StringLiteral(value) => codify(quote(value))
      case DoubleLiteral(value) => codify(value.toString)
      case IntLiteral(value) => codify(value.toString)
      case BoolLiteral(value) => codify(value.toString)
      case c@ListRHS(_) => genList(c, mutable)
      case c@SetRHS(_) => genSet(c, mutable)
      case c@MapRHS(_) => genMap(c, mutable)
      case EnumRHS(enum, value) => genID(value.sid.addScope(enum.sid.toTitleCase))
      case iv@IdRHS(id) => genID(id)
    }
  }

  def genList(list: ListRHS, mutable: Boolean = false): CodeFragment

  def genSet(set: SetRHS, mutable: Boolean = false): CodeFragment

  def genMap(map: MapRHS, mutable: Boolean = false): CodeFragment

  /**
   * The default value for the specified type and mutability.
   */
  def genDefaultValue(fieldType: FieldType, mutable: Boolean = false): CodeFragment = {
    val code = fieldType match {
      case TBool => "false"
      case TByte | TI16 | TI32 => "0"
      case TDouble => "0.0"
      case _ => "null"
    }
    codify(code)
  }

  def genDefaultFieldValue(f: Field): Option[CodeFragment] = {
    if (f.requiredness.isOptional) {
      None
    } else {
      f.default.map(genConstant(_, false)) orElse {
        if (f.fieldType.isInstanceOf[ContainerType]) {
          Some(genDefaultValue(f.fieldType))
        } else {
          None
        }
      }
    }
  }

  def genDefaultReadValue(f: Field): CodeFragment = {
    genDefaultFieldValue(f).getOrElse {
      genDefaultValue(f.fieldType, false)
    }
  }

  def genConstType(t: FunctionType): CodeFragment = {
    val code = t match {
      case Void => "VOID"
      case TBool => "BOOL"
      case TByte => "BYTE"
      case TDouble => "DOUBLE"
      case TI16 => "I16"
      case TI32 => "I32"
      case TI64 => "I64"
      case TString => "STRING"
      case TBinary => "STRING" // thrift's idea of "string" is based on old broken c++ semantics.
      case StructType(_, _) => "STRUCT"
      case EnumType(_, _) => "I32" // enums are converted to ints
      case MapType(_, _, _) => "MAP"
      case SetType(_, _) => "SET"
      case ListType(_, _) => "LIST"
      case x => throw new InternalError("constType#" + t)
    }
    codify(code)
  }

  def genProtocolReadMethod(t: FunctionType): CodeFragment = {
    val code = t match {
      case TBool => "readBool"
      case TByte => "readByte"
      case TI16 => "readI16"
      case TI32 => "readI32"
      case TI64 => "readI64"
      case TDouble => "readDouble"
      case TString => "readString"
      case TBinary => "readBinary"
      case x => throw new ScroogeInternalException("protocolReadMethod#" + t)
    }
    codify(code)
  }

  def genProtocolWriteMethod(t: FunctionType): CodeFragment = {
    val code = t match {
      case TBool => "writeBool"
      case TByte => "writeByte"
      case TI16 => "writeI16"
      case TI32 => "writeI32"
      case TI64 => "writeI64"
      case TDouble => "writeDouble"
      case TString => "writeString"
      case TBinary => "writeBinary"
      case x => throw new ScroogeInternalException("protocolWriteMethod#" + t)
    }
    codify(code)
  }

  /**
   * Generates a suffix to append to a field expression that will
   * convert the value to an immutable equivalent.
   */
  def genToImmutable(t: FieldType): CodeFragment

  /**
   * Generates a suffix to append to a field expression that will
   * convert the value to an immutable equivalent.
   */
  def genToImmutable(f: Field): CodeFragment

  def genType(t: FunctionType, mutable: Boolean = false): CodeFragment

  def genPrimitiveType(t: FunctionType, mutable: Boolean = false): CodeFragment

  def genFieldType(f: Field, mutable: Boolean = false): CodeFragment

  def genFieldParams(fields: Seq[Field], asVal: Boolean = false): CodeFragment

  def genBaseFinagleService: CodeFragment

  /**
   * Creates a sequence of dictionaries describing aliased imports.
   */
  protected def importsDicts(includes: Seq[Include]) = {
    includes map { include =>
      val id = getNamespace(include.document)
      val prefix = include.prefix

      val (parentPackage, subPackage) = id match {
        case sid: SimpleID => (SimpleID("_root_"), sid)
        case qid: QualifiedID => (qid.qualifier, qid.name)
      }

      Dictionary(
        "parentpackage" -> genID(parentPackage),
        "subpackage" -> genID(subPackage),
        "_alias_" -> genID(prefix.prepend("_").append("_"))
      )
    }
  }

  // main entry
  def apply(_doc: Document, serviceOptions: Set[ServiceOption], outputPath: File) {
    val doc = normalizeCase(_doc)
    val namespace = getNamespace(_doc)
    val packageDir = namespacedFolder(outputPath, namespace.fullName)
    val includes = doc.headers.collect {
      case x@ Include(_, doc) => x
    }

    if (doc.consts.nonEmpty) {
      val file = new File(packageDir, "Constants" + fileExtension)
      val dict = constDict(namespace, doc.consts)
      writeFile(file, templates.header, templates("consts").generate(dict))
    }

    doc.enums.foreach {
      enum =>
        val file = new File(packageDir, enum.sid.toTitleCase.name + fileExtension)
        val dict = enumDict(namespace, enum)
        writeFile(file, templates.header, templates("enum").generate(dict))
    }

    doc.structs.foreach {
      struct =>
        val file = new File(packageDir, struct.sid.toTitleCase.name + fileExtension)
        val dict = structDict(struct, Some(namespace), includes, serviceOptions)
        writeFile(file, templates.header, templates("struct").generate(dict))
    }
    doc.services.foreach {
      service =>
        val file = new File(packageDir, service.sid.toTitleCase.name + fileExtension)
        val dict = serviceDict(JavaService(service, serviceOptions), namespace, includes, serviceOptions)
        writeFile(file, templates.header, templates("service").generate(dict))
    }
  }
}